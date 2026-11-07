package ir.fileo.core.transfer

import android.util.Log
import ir.fileo.core.discovery.DiscoveredDevice
import ir.fileo.core.net.PrivateNetworks
import ir.fileo.core.security.TlsIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLSocket

/**
 * فرستندهٔ انتقال — اتصال TLS، HELLO/OFFER، انتقال جریانی فایل‌ها با SHA-256.
 * سشن بلافاصله برگردانده می‌شود؛ پیشرفت از طریق session.state دیده می‌شود.
 */
class TransferClient(
    private val identity: TlsIdentity,
    private val scope: CoroutineScope,
) {

    fun send(
        device: DiscoveredDevice,
        files: List<OutgoingFile>,
        selfDeviceId: String,
        selfDeviceName: String,
        selfModel: String,
    ): TransferSession {
        val session = TransferSession(
            transferId = UUID.randomUUID().toString(),
            direction = TransferSession.Direction.SEND,
            peerName = device.name,
            files = files.map { OfferedFile(it.id, it.name, it.size, it.mimeType) },
        )

        // قفل شبکه — هرگز به بیرون از شبکهٔ محلی وصل نمی‌شویم
        if (!PrivateNetworks.isPrivateIp(device.ip)) {
            session.updateState(TransferState.Failed("آدرس غیرمحلی مسدود شد: ${device.ip}"))
            return session
        }
        if (files.isEmpty()) {
            session.updateState(TransferState.Failed("هیچ فایلی انتخاب نشده است"))
            return session
        }

        session.updateState(TransferState.Offering)
        scope.launch(Dispatchers.IO) {
            runTransfer(device, files, session, selfDeviceId, selfDeviceName, selfModel)
        }
        return session
    }

    private fun runTransfer(
        device: DiscoveredDevice,
        files: List<OutgoingFile>,
        session: TransferSession,
        selfDeviceId: String,
        selfDeviceName: String,
        selfModel: String,
    ) {
        var socket: SSLSocket? = null
        try {
            val s = identity.socketFactory().createSocket() as SSLSocket
            socket = s
            s.tcpNoDelay = true
            // تا گرفتن پاسخ گیرنده (تصمیم کاربر او) ممکن است دقیقه‌ها طول بکشد
            s.soTimeout = APPROVAL_SOCKET_TIMEOUT_MS
            s.connect(InetSocketAddress(device.ip, device.port), CONNECT_TIMEOUT_MS)
            s.startHandshake()

            val input = DataInputStream(BufferedInputStream(s.getInputStream(), BUFFER))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), BUFFER))

            Protocol.writeMessage(
                output,
                WireMessage.Hello(deviceId = selfDeviceId, deviceName = selfDeviceName, model = selfModel),
            )
            Protocol.writeMessage(output, WireMessage.Offer(session.transferId, session.files, session.totalBytes))
            session.updateState(TransferState.AwaitingApproval)

            val response = Protocol.readMessage(input) as? WireMessage.Response
                ?: throw IOException("پاسخ نامعتبر از گیرنده")
            if (session.cancelRequested) throw TransferCancelledException()
            if (!response.accepted) {
                session.updateState(TransferState.Rejected)
                return
            }

            // فاز انتقال داده — گپ خواندن بیش از این یعنی اتصال مرده
            s.soTimeout = DATA_SOCKET_TIMEOUT_MS

            val totalBytes = session.totalBytes
            var doneBytes = 0L
            val startedAt = System.currentTimeMillis()
            val buffer = ByteArray(CHUNK)

            session.updateState(
                TransferState.Transferring(1, files.size, files.firstOrNull()?.name.orEmpty(), 0, totalBytes, 0),
            )

            files.forEachIndexed { index, file ->
                if (session.cancelRequested) throw TransferCancelledException()
                Protocol.writeMessage(output, WireMessage.FileStart(session.transferId, file.id, file.size))

                val digest = MessageDigest.getInstance("SHA-256")
                file.openStream().use { stream ->
                    var lastUpdate = 0L
                    while (true) {
                        if (session.cancelRequested) throw TransferCancelledException()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        doneBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > PROGRESS_UPDATE_MS) {
                            lastUpdate = now
                            val elapsed = (now - startedAt).coerceAtLeast(1)
                            session.updateState(
                                TransferState.Transferring(
                                    fileIndex = index + 1,
                                    fileCount = files.size,
                                    currentFileName = file.name,
                                    bytesDone = doneBytes,
                                    totalBytes = totalBytes,
                                    bytesPerSecond = doneBytes * 1000 / elapsed,
                                ),
                            )
                        }
                    }
                }
                output.flush()
                Protocol.writeMessage(
                    output,
                    WireMessage.FileEnd(session.transferId, file.id, digest.digest().joinToString("") { "%02x".format(it) }),
                )
            }

            val complete = Protocol.readMessage(input) as? WireMessage.Complete
                ?: throw IOException("تأیید نهایی دریافت نشد")
            session.updateState(TransferState.Completed(emptyList()))
        } catch (e: TransferCancelledException) {
            session.updateState(TransferState.Cancelled)
        } catch (e: Exception) {
            Log.d(TAG, "transfer failed: ${e.message}")
            session.updateState(TransferState.Failed(e.message ?: "انتقال ناموفق بود"))
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        private const val TAG = "TransferClient"
        private const val BUFFER = 64 * 1024
        private const val CHUNK = 64 * 1024
        private const val PROGRESS_UPDATE_MS = 200L
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** گپ خواندن در فاز انتقال داده */
        private const val DATA_SOCKET_TIMEOUT_MS = 30_000

        /** تا وقتی گیرنده در حال تصمیم است، خواندن باید باز بماند (گیرنده تا ۱۲۰ ثانیه فرصت دارد) */
        private const val APPROVAL_SOCKET_TIMEOUT_MS = 150_000
    }
}
