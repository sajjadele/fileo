package ir.fileo.core.transfer

import ir.fileo.core.discovery.DiscoveredDevice
import ir.fileo.core.security.TlsIdentityFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * تست یکپارچهٔ کامل: سرور ↔ کلاینت واقعی روی TLS با سوکت localhost.
 * کل چرخهٔ پروتکل (HELLO/OFFER/RESPONSE/فایل‌ها/SHA/COMPLETE) را ثابت می‌کند.
 */
class TransferIntegrationTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun randomBytes(size: Int, seed: Long): ByteArray {
        val bytes = ByteArray(size)
        Random(seed).nextBytes(bytes)
        return bytes
    }

    private fun memorySinkFactory(store: ConcurrentHashMap<String, ByteArray>): IncomingSinkFactory =
        IncomingSinkFactory { name, _, _ ->
            object : IncomingSink {
                override val displayName: String = name
                private val buffer = ByteArrayOutputStream()

                override fun outputStream(): OutputStream = object : OutputStream() {
                    override fun write(b: Int) = buffer.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
                    override fun close() {
                        store[name] = buffer.toByteArray()
                    }
                }

                override fun onAborted() {
                    store.remove(name)
                }
            }
        }

    private fun startServer(
        sinkFactory: IncomingSinkFactory,
        onOffer: (TransferSession) -> Unit,
    ): TransferServer {
        val server = TransferServer(
            identity = TlsIdentityFactory.generate(),
            scope = scope,
            sinkFactory = sinkFactory,
            onIncomingOffer = onOffer,
        )
        server.start(0) // پورت تصادفی
        assertTrue("server did not bind", server.port > 0)
        return server
    }

    private fun peerDevice(port: Int) = DiscoveredDevice(
        deviceId = "peer-device",
        name = "Peer Phone",
        model = "Test Model",
        ip = "127.0.0.1",
        port = port,
        source = DiscoveredDevice.Source.UDP,
        lastSeenMs = 0L,
    )

    @Test
    fun `full transfer of multiple files over tls succeeds with correct content`() {
        val store = ConcurrentHashMap<String, ByteArray>()
        val server = startServer(memorySinkFactory(store)) { session ->
            scope.launch {
                delay(200) // شبیه‌سازی تصمیم کاربر
                session.accept()
            }
        }

        val data1 = randomBytes(256 * 1024, seed = 1)
        val data2 = randomBytes(2 * 1024 * 1024 + 123, seed = 2)
        val files = listOf(
            OutgoingFile("f1", "alpha.bin", data1.size.toLong(), "application/octet-stream") { data1.inputStream() },
            OutgoingFile("f2", "beta.bin", data2.size.toLong(), "application/octet-stream") { data2.inputStream() },
        )

        val client = TransferClient(TlsIdentityFactory.generate(), scope)
        val session = client.send(peerDevice(server.port), files, "self-id", "Self Phone", "Test")

        val finalState = runBlocking {
            withTimeoutOrNull(60_000) { session.state.first { it.isTerminal } }
        }

        assertTrue("expected Completed but was ${session.state.value}", finalState is TransferState.Completed)
        assertTrue(data1.contentEquals(store["alpha.bin"]))
        assertTrue(data2.contentEquals(store["beta.bin"]))
        assertEquals(listOf("alpha.bin", "beta.bin"), finalState.let { (it as TransferState.Completed).savedNames })

        server.stop()
    }

    @Test
    fun `rejected offer ends sender session as Rejected`() {
        val server = startServer(memorySinkFactory(ConcurrentHashMap())) { session ->
            scope.launch {
                delay(100)
                session.reject()
            }
        }

        val data = randomBytes(1024, seed = 3)
        val files = listOf(
            OutgoingFile("f1", "c.bin", data.size.toLong(), "application/octet-stream") { data.inputStream() },
        )

        val client = TransferClient(TlsIdentityFactory.generate(), scope)
        val session = client.send(peerDevice(server.port), files, "self-id", "Self Phone", "Test")

        val finalState = runBlocking {
            withTimeoutOrNull(30_000) { session.state.first { it.isTerminal } }
        }

        assertTrue("expected Rejected but was ${session.state.value}", finalState is TransferState.Rejected)

        server.stop()
    }

    @Test
    fun `cancel mid transfer ends as Cancelled`() {
        val store = ConcurrentHashMap<String, ByteArray>()
        val server = startServer(memorySinkFactory(store)) { session ->
            scope.launch {
                delay(100)
                session.accept()
            }
        }

        val data = randomBytes(16 * 1024 * 1024, seed = 4) // بزرگ — زمان کافی برای لغو وسط کار
        val files = listOf(
            OutgoingFile("f1", "big.bin", data.size.toLong(), "application/octet-stream") { data.inputStream() },
        )

        val client = TransferClient(TlsIdentityFactory.generate(), scope)
        val session = client.send(peerDevice(server.port), files, "self-id", "Self Phone", "Test")

        val finalState = runBlocking {
            withTimeoutOrNull(15_000) {
                session.state.first { it is TransferState.Transferring && it.bytesDone > 0 }
                session.requestCancel()
                withTimeoutOrNull(15_000) { session.state.first { it.isTerminal } }
            }
        }

        assertTrue("expected Cancelled but was ${session.state.value}", finalState is TransferState.Cancelled)

        server.stop()
    }

    @Test
    fun `second concurrent offer is rejected as busy`() {
        val pendingSession = AtomicReference<TransferSession?>(null)
        val server = startServer(memorySinkFactory(ConcurrentHashMap())) { session ->
            pendingSession.set(session) // بدون تصمیم — گیرنده منتظر می‌ماند
        }

        val data = randomBytes(1024, seed = 5)
        val files = listOf(
            OutgoingFile("f1", "x.bin", data.size.toLong(), "application/octet-stream") { data.inputStream() },
        )

        val client = TransferClient(TlsIdentityFactory.generate(), scope)

        // سشن اول — گیرنده هنوز تصمیم نگرفته → سرور busy است
        val first = client.send(peerDevice(server.port), files, "self-id", "Self Phone", "Test")
        runBlocking {
            withTimeoutOrNull(15_000) { first.state.first { it is TransferState.AwaitingApproval } }
        }

        // سشن دوم باید با busy رد شود
        val second = client.send(peerDevice(server.port), files, "self-id", "Self Phone", "Test")
        val secondFinal = runBlocking {
            withTimeoutOrNull(15_000) { second.state.first { it.isTerminal } }
        }
        assertTrue("expected Rejected (busy) but was ${second.state.value}", secondFinal is TransferState.Rejected)

        // پاک‌سازی
        pendingSession.get()?.reject()
        server.stop()
    }

    @Test
    fun `sender refuses non-local address immediately`() {
        val client = TransferClient(TlsIdentityFactory.generate(), scope)
        val session = client.send(
            peerDevice(port = 47_778).copy(ip = "8.8.8.8"),
            listOf(OutgoingFile("f1", "y.bin", 10L, "application/octet-stream") { ByteArray(10).inputStream() }),
            "self-id",
            "Self Phone",
            "Test",
        )
        val finalState = runBlocking {
            withTimeoutOrNull(5_000) { session.state.first { it.isTerminal } }
        }
        assertTrue("expected Failed (network lockdown) but was ${session.state.value}", finalState is TransferState.Failed)
    }
}
