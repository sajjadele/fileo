package ir.fileo.ui.send

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
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
import ir.fileo.core.qr.QrContent
import ir.fileo.core.transfer.OutgoingFile
import ir.fileo.core.transfer.TransferEngine
import ir.fileo.core.transfer.TransferSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

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

    /** اتصال در جریان از طریق QR — تا پیداشدن دستگاه هدف در discovery */
    private val _connectionAttempt = MutableStateFlow<QrContent?>(null)
    val connectionAttempt: StateFlow<QrContent?> = _connectionAttempt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

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
            else -> {
                qr.hotspot?.let { addWifiSuggestion(it) }
                _connectionAttempt.value = qr
            }
        }
    }

    fun cancelConnectionAttempt() {
        _connectionAttempt.value = null
        clearWifiSuggestion()
    }

    private fun maybeAutoOffer(devices: List<DiscoveredDevice>) {
        val target = _connectionAttempt.value ?: return
        val currentSession = _session.value
        if (currentSession != null && !currentSession.isTerminalState()) return
        if (_pickedUris.value.isEmpty()) return
        val match = devices.firstOrNull { it.deviceId == target.deviceId } ?: return
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
