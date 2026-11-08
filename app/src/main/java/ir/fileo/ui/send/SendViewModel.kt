package ir.fileo.ui.send

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ir.fileo.FileoApp
import ir.fileo.R
import ir.fileo.core.discovery.DiscoveredDevice
import ir.fileo.core.discovery.DiscoveryEngine
import ir.fileo.core.discovery.DiscoveryState
import ir.fileo.core.net.NetworkUtils
import ir.fileo.core.qr.QrContent
import ir.fileo.core.transfer.OutgoingFile
import ir.fileo.core.transfer.TransferEngine
import ir.fileo.core.transfer.TransferSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** فازهای اتصال از طریق QR */
enum class ConnectPhase { CONNECTING_NETWORK, SEARCHING, FAILED }

data class ConnectionAttempt(
    val qr: QrContent,
    val phase: ConnectPhase,
    val startedAtElapsedMs: Long,
)

class SendViewModel(
    private val discovery: DiscoveryEngine,
    private val transfer: TransferEngine,
    private val appContext: Context,
    private val selfDeviceId: String,
) : ViewModel() {

    val devices = discovery.devices
    val discoveryState = discovery.state

    private val _pickedUris = MutableStateFlow<List<Uri>>(emptyList())
    val pickedUris: StateFlow<List<Uri>> = _pickedUris.asStateFlow()

    private val _session = MutableStateFlow<TransferSession?>(null)
    val session: StateFlow<TransferSession?> = _session.asStateFlow()

    private val _connectionAttempt = MutableStateFlow<ConnectionAttempt?>(null)
    val connectionAttempt: StateFlow<ConnectionAttempt?> = _connectionAttempt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var attemptJob: Job? = null

    init {
        // اتصال خودکار: به‌محض ظاهر شدن دستگاه هدف در discovery، پیشنهاد ارسال می‌رود
        viewModelScope.launch {
            devices.collect { list -> maybeAutoOffer(list) }
        }
    }

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

    fun onFilesPicked(uris: List<Uri>) {
        _pickedUris.value = uris
        // اگر در حال انتظار اتصال QR بودیم، شاید الان فایل‌ها آماده شدند
        maybeAutoOffer(devices.value)
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** نتیجهٔ اسکن QR */
    fun onQrScanned(raw: String) {
        val qr = QrContent.parse(raw)
        when {
            qr == null -> _message.value = appContext.getString(R.string.connect_invalid_qr)
            qr.deviceId == selfDeviceId -> _message.value = appContext.getString(R.string.connect_self_qr)
            else -> beginAttempt(qr)
        }
    }

    fun cancelConnectionAttempt() {
        attemptJob?.cancel()
        attemptJob = null
        _connectionAttempt.value = null
        clearWifiSuggestion()
    }

    fun retryConnectionAttempt() {
        _connectionAttempt.value?.qr?.let { beginAttempt(it) }
    }

    private fun beginAttempt(qr: QrContent) {
        attemptJob?.cancel()
        clearWifiSuggestion()
        qr.hotspot?.let { addWifiSuggestion(it) }

        val initialPhase = if (qr.hotspot != null) ConnectPhase.CONNECTING_NETWORK else ConnectPhase.SEARCHING
        _connectionAttempt.value = ConnectionAttempt(
            qr = qr,
            phase = initialPhase,
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
        )

        attemptJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val deadline = startedAt + ATTEMPT_TIMEOUT_MS
            var lastProbeAt = 0L
            var lastPhase: ConnectPhase = initialPhase

            while (kotlin.coroutines.coroutineContext.isActive) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - startedAt

                // فاز: در حالت هات‌اسپات، ۱۵ ثانیهٔ اول «وصل شدن به شبکه» است
                val phase = when {
                    qr.hotspot != null && elapsed < NETWORK_PHASE_MS -> ConnectPhase.CONNECTING_NETWORK
                    else -> ConnectPhase.SEARCHING
                }
                if (phase != lastPhase) {
                    lastPhase = phase
                    _connectionAttempt.update { it?.copy(phase = phase) }
                }

                if (now >= deadline) {
                    _connectionAttempt.update { it?.copy(phase = ConnectPhase.FAILED) }
                    return@launch
                }

                // probe مستقیم — به IP داخل QR و گیت‌وی هات‌اسپات (اگر broadcast گم شود)
                if (now - lastProbeAt >= PROBE_INTERVAL_MS) {
                    lastProbeAt = now
                    val candidates = buildList {
                        qr.ip?.let(::add)
                        NetworkUtils.wifiGatewayIp(appContext)?.let(::add)
                    }.distinct()
                    candidates.forEach { discovery.sendDirectAnnounce(it) }
                }

                delay(300)
            }
        }
    }

    private fun maybeAutoOffer(devices: List<DiscoveredDevice>) {
        val attempt = _connectionAttempt.value ?: return
        if (attempt.phase == ConnectPhase.FAILED) return
        val currentSession = _session.value
        if (currentSession != null && !currentSession.isTerminalState()) return

        val match = devices.firstOrNull { it.deviceId == attempt.qr.deviceId } ?: return

        if (_pickedUris.value.isEmpty()) {
            // فایل‌ها هنوز انتخاب نشده‌اند — منتظر می‌مانیم تا انتخاب شود
            if (_message.value == null) {
                _message.value = appContext.getString(R.string.send_first_pick_files)
            }
            return
        }

        attemptJob?.cancel()
        attemptJob = null
        _connectionAttempt.value = null
        clearWifiSuggestion()
        offerTo(match)
    }

    fun offerTo(device: DiscoveredDevice): TransferSession? {
        val uris = _pickedUris.value
        if (uris.isEmpty()) {
            _message.value = appContext.getString(R.string.send_first_pick_files)
            return null
        }
        val resolver = appContext.contentResolver
        val files = uris.mapIndexed { index, uri ->
            val name = queryDisplayName(resolver, uri) ?: "file-${index + 1}"
            val size = querySize(resolver, uri) ?: 0L
            OutgoingFile(
                id = "f$index",
                name = name,
                size = size,
                mimeType = resolver.getType(uri) ?: "application/octet-stream",
            ) {
                resolver.openInputStream(uri) ?: throw IOException("cannot open $name")
            }
        }
        val session = transfer.offerTo(device, files)
        _session.value = session
        return session
    }

    fun cancelSession() {
        _session.value?.let { transfer.cancel(it.transferId) }
    }

    fun dismissSession() {
        _session.value = null
    }

    /** API 29+: پیشنهاد شبکه به سیستم — گوشی وقتی در محدوده باشد وصل می‌شود */
    private fun addWifiSuggestion(hot: QrContent.HotspotInfo) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
            wifi.removeNetworkSuggestions(emptyList())
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(hot.ssid)
                .setWpa2Passphrase(hot.passphrase)
                .build()
            wifi.addNetworkSuggestions(listOf(suggestion))
        }
    }

    private fun clearWifiSuggestion() {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            appContext.getSystemService(WifiManager::class.java)?.removeNetworkSuggestions(emptyList())
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    companion object {
        private const val NETWORK_PHASE_MS = 15_000L
        private const val ATTEMPT_TIMEOUT_MS = 60_000L
        private const val PROBE_INTERVAL_MS = 1_500L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as FileoApp).container
                SendViewModel(
                    discovery = container.discoveryEngine,
                    transfer = container.transferEngine,
                    appContext = container.appContext,
                    selfDeviceId = container.deviceId,
                )
            }
        }
    }
}
