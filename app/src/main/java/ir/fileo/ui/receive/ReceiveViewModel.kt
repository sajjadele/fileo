package ir.fileo.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ir.fileo.FileoApp
import ir.fileo.core.hotspot.HotspotManager
import ir.fileo.core.qr.QrContent
import ir.fileo.core.security.CertManager
import ir.fileo.core.transfer.TransferEngine
import ir.fileo.core.transfer.TransferSession
import ir.fileo.core.transfer.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** وضعیت بخش «اتصال سریع» صفحهٔ دریافت */
data class ConnectionUiState(
    val starting: Boolean = false,
    val error: String? = null,
    val qr: QrContent? = null,
    val hotspotActive: Boolean = false,
)

class ReceiveViewModel(
    private val engine: TransferEngine,
    private val hotspotManager: HotspotManager,
    private val certManager: CertManager,
    private val selfDeviceId: String,
    private val selfDeviceName: String,
) : ViewModel() {

    val sessions = engine.sessions

    /** پیشنهاد در انتظار تصمیم کاربر (سرور یک سشن فعال دارد) */
    val incomingOffer: StateFlow<TransferSession?> = engine.sessions
        .map { list ->
            list.firstOrNull {
                it.direction == TransferSession.Direction.RECEIVE &&
                    it.state.value is TransferState.IncomingOffer
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _connection = MutableStateFlow(ConnectionUiState())
    val connection: StateFlow<ConnectionUiState> = _connection.asStateFlow()

    /** QR ساده روی شبکهٔ موجود (بدون هات‌اسپات) */
    fun showPairingQr() {
        stopConnection()
        _connection.value = ConnectionUiState(qr = buildQr(null))
    }

    /** ساخت هات‌اسپات محلی + QR اتصال به آن */
    fun startHotspot() {
        stopConnection()
        _connection.value = ConnectionUiState(starting = true)
        viewModelScope.launch {
            try {
                val info = hotspotManager.start()
                _connection.value = ConnectionUiState(
                    qr = buildQr(QrContent.HotspotInfo(info.ssid, info.passphrase)),
                    hotspotActive = true,
                )
            } catch (e: Exception) {
                _connection.value = ConnectionUiState(error = e.message ?: "خطای ناشناخته")
            }
        }
    }

    fun stopConnection() {
        hotspotManager.stop()
        _connection.value = ConnectionUiState()
    }

    fun accept(transferId: String) = engine.acceptIncoming(transferId)

    fun reject(transferId: String) = engine.rejectIncoming(transferId)

    fun cancel(transferId: String) = engine.cancel(transferId)

    private fun buildQr(hotspot: QrContent.HotspotInfo?): QrContent = QrContent(
        deviceId = selfDeviceId,
        deviceName = selfDeviceName,
        fingerprint = certManager.identity().certificateFingerprint().orEmpty(),
        hotspot = hotspot,
        // IP فعلی — در حالت هات‌اسپات بعد از بالا آمدن AP خوانده می‌شود
        ip = ir.fileo.core.net.NetworkUtils.findLocalIpv4(),
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as FileoApp).container
                ReceiveViewModel(
                    engine = container.transferEngine,
                    hotspotManager = container.hotspotManager,
                    certManager = container.certManager,
                    selfDeviceId = container.deviceId,
                    selfDeviceName = container.deviceName,
                )
            }
        }
    }
}
