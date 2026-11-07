package ir.fileo.core.transfer

import android.util.Log
import ir.fileo.core.security.TlsIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

/**
 * گیرندهٔ انتقال — روی پورت ثابت گوش می‌دهد؛ یک سشن فعال و درخواست‌های هم‌زمان با busy رد می‌شوند.
 */
class TransferServer(
    private val identity: TlsIdentity,
    private val scope: CoroutineScope,
    private val sinkFactory: IncomingSinkFactory,
    private val onIncomingOffer: (TransferSession) -> Unit,
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var started = false

    @Volatile
    private var activeSession: TransferSession? = null

    /** پورت واقعیِ بسته‌شده (در تست با پورت ۰ تصادفی می‌شود) */
    val port: Int get() = serverSocket?.localPort ?: -1

    fun start(bindPort: Int = DEFAULT_PORT) {
        if (started) return
        val server = try {
            identity.serverSocketFactory()
                .createServerSocket(bindPort, BACKLOG, InetAddress.getByName("0.0.0.0"))
        } catch (e: Exception) {
            Log.w(TAG, "cannot start transfer server on $bindPort: $e")
            return
        }
        started = true
        serverSocket = server
        acceptJob = scope.launch(Dispatchers.IO) {
            while (started && !server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    if (started) Log.d(TAG, "accept ended: ${e.message}")
                    break
                }
                scope.launch(Dispatchers.IO) {
                    val ssl = socket as? SSLSocket
                    if (ssl == null) {
                        runCatching { socket.close() }
                    } else {
                        handleConnection(ssl)
                    }
                }
            }
        }
    }

    fun stop() {
        started = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleConnection(socket: SSLSocket) {
        var session: TransferSession? = null
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.startHandshake()

            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER))

            val hello = Protocol.readMessage(input) as? WireMessage.Hello
                ?: throw IOException("expected hello")
            if (hello.protocolVersion != Protocol.PROTOCOL_VERSION) {
                Protocol.writeMessage(
                    output,
                    WireMessage.Error(code = CODE_UNSUPPORTED_VERSION, message = "unsupported protocol version ${hello.protocolVersion}"),
                )
                return
            }

            val offer = Protocol.readMessage(input) as? WireMessage.Offer
                ?: throw IOException("expected offer")

            if (activeSession != null) {
                Protocol.writeMessage(
                    output,
                    WireMessage.Response(offer.transferId, accepted = false, reason = "busy"),
                )
                return
            }

            val incoming = TransferSession(
                transferId = offer.transferId,
                direction = TransferSession.Direction.RECEIVE,
                peerName = hello.deviceName.ifBlank { "دستگاه ناشناس" },
                files = offer.files,
            )
            incoming.updateState(TransferState.IncomingOffer)
            session = incoming
            activeSession = incoming
            onIncomingOffer(incoming)

            when (incoming.awaitDecision(DECISION_TIMEOUT_MS)) {
                TransferSession.Decision.ACCEPT -> {
                    Protocol.writeMessage(output, WireMessage.Response(incoming.transferId, accepted = true))
                    receiveFiles(incoming, input, output)
                }
                TransferSession.Decision.REJECT -> {
                    if (incoming.cancelRequested) {
                        // لغو از سمت خود گیرنده — فرستنده هم‌زمان قطع شده است
                        incoming.updateState(TransferState.Cancelled)
                    } else {
                        runCatching {
                            Protocol.writeMessage(
                                output,
                                WireMessage.Response(incoming.transferId, accepted = false, reason = "rejected"),
                            )
                        }
                        incoming.updateState(TransferState.Rejected)
                    }
                }
                null -> incoming.updateState(TransferState.Cancelled) // کاربر پاسخ نداد
            }
        } catch (e: TransferCancelledException) {
            session?.updateState(TransferState.Cancelled)
        } catch (e: Exception) {
            Log.d(TAG, "connection ended: ${e.message}")
            session?.updateState(
                if (session?.cancelRequested == true) {
                    TransferState.Cancelled
                } else {
                    TransferState.Failed(e.message ?: "خطای اتصال")
                },
            )
        } finally {
            runCatching { socket.close() }
            if (activeSession === session) activeSession = null
        }
    }

    private fun receiveFiles(session: TransferSession, input: DataInputStream, output: DataOutputStream) {
        val totalBytes = session.totalBytes
        val buffer = ByteArray(CHUNK)
        var doneBytes = 0L
        val startedAt = System.currentTimeMillis()
        var lastUiUpdate = 0L
        val savedNames = mutableListOf<String>()

        fun progress(fileIndex: Int, fileName: String) {
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            session.updateState(
                TransferState.Transferring(
                    fileIndex = fileIndex,
                    fileCount = session.files.size,
                    currentFileName = fileName,
                    bytesDone = doneBytes,
                    totalBytes = totalBytes,
                    bytesPerSecond = doneBytes * 1000 / elapsed,
                ),
            )
        }

        session.updateState(
            TransferState.Transferring(1, session.files.size, session.files.firstOrNull()?.name.orEmpty(), 0, totalBytes, 0),
        )

        for ((index, file) in session.files.withIndex()) {
            if (session.cancelRequested) throw TransferCancelledException()

            val start = Protocol.readMessage(input)
            val fileStart = start as? WireMessage.FileStart
                ?: throw IOException("expected file start, got ${start::class.simpleName}")
            if (fileStart.fileId != file.id) throw IOException("file id mismatch: ${fileStart.fileId} != ${file.id}")

            val sink = sinkFactory.open(file.name, file.mimeType, file.size)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val out = sink.outputStream().buffered(BUFFER)
                Protocol.readRaw(input, file.size, buffer) { count ->
                    out.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    doneBytes += count
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate > PROGRESS_UPDATE_MS) {
                        lastUiUpdate = now
                        progress(index + 1, file.name)
                    }
                    if (session.cancelRequested) throw TransferCancelledException()
                }
                out.flush()
                out.close()

                val end = Protocol.readMessage(input)
                val fileEnd = end as? WireMessage.FileEnd ?: throw IOException("expected file end")
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(fileEnd.sha256, ignoreCase = true)) {
                    throw IOException("checksum mismatch: ${file.name}")
                }
                runCatching { sink.onCompleted() }
                savedNames += sink.displayName
            } catch (e: Exception) {
                runCatching { sink.onAborted() }
                throw e
            }
            progress(index + 1, file.name)
        }

        Protocol.writeMessage(output, WireMessage.Complete(session.transferId))
        session.updateState(TransferState.Completed(savedNames))
    }

    companion object {
        private const val TAG = "TransferServer"
        const val DEFAULT_PORT = 47_778
        private const val BACKLOG = 4
        private const val BUFFER = 64 * 1024
        private const val CHUNK = 64 * 1024
        private const val PROGRESS_UPDATE_MS = 200L

        /** فاصلهٔ خواندن زنجیره‌ای — گپ بیشتر یعنی اتصال مرده */
        private const val SOCKET_TIMEOUT_MS = 30_000

        /** حداکثر زمان انتظار برای تصمیم کاربر گیرنده */
        private const val DECISION_TIMEOUT_MS = 120_000L
        private const val CODE_UNSUPPORTED_VERSION = 2
    }
}
