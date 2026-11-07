package ir.fileo.core.transfer

import ir.fileo.core.discovery.DiscoveredDevice
import ir.fileo.core.security.TlsIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * هماهنگ‌کنندهٔ انتقال — سرور (گیرنده) + کلاینت (فرستنده) + لیست سشن‌ها برای UI و سرویس.
 */
class TransferEngine(
    identity: TlsIdentity,
    private val scope: CoroutineScope,
    sinkFactory: IncomingSinkFactory,
    private val selfDeviceId: String,
    private val selfDeviceName: String,
    private val selfModel: String,
) {

    private val server = TransferServer(
        identity = identity,
        scope = scope,
        sinkFactory = sinkFactory,
        onIncomingOffer = ::onIncomingOffer,
    )

    private val client = TransferClient(identity, scope)

    private val _sessions = MutableStateFlow<List<TransferSession>>(emptyList())
    val sessions: StateFlow<List<TransferSession>> = _sessions.asStateFlow()

    /** تعداد سشن‌های فعال (غیرپایان‌یافته) — سوئیچ Foreground Service با آن */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSessionCount: StateFlow<Int> = sessions.flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(0)
        } else {
            combine(list.map { it.state }) { states -> states.count { !it.isTerminal } }
        }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    /** شروع گوش دادن برای دریافت — با پورت پیش‌فرض در تولید، تصادفی در تست */
    fun startServer(bindPort: Int = TransferServer.DEFAULT_PORT) = server.start(bindPort)

    fun serverPort(): Int = server.port

    fun offerTo(device: DiscoveredDevice, files: List<OutgoingFile>): TransferSession {
        val session = client.send(device, files, selfDeviceId, selfDeviceName, selfModel)
        registerSession(session)
        return session
    }

    fun acceptIncoming(transferId: String) {
        _sessions.value.find { it.transferId == transferId }?.accept()
    }

    fun rejectIncoming(transferId: String) {
        _sessions.value.find { it.transferId == transferId }?.reject()
    }

    fun cancel(transferId: String) {
        _sessions.value.find { it.transferId == transferId }?.requestCancel()
    }

    private fun onIncomingOffer(session: TransferSession) {
        registerSession(session)
    }

    private fun registerSession(session: TransferSession) {
        _sessions.update { list -> list + session }
        scope.launch {
            session.state.first { it.isTerminal }
            delay(REMOVE_TERMINAL_AFTER_MS)
            _sessions.update { list -> list.filterNot { it === session } }
        }
    }

    companion object {
        private const val REMOVE_TERMINAL_AFTER_MS = 60_000L
    }
}
