package ir.fileo.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import ir.fileo.core.net.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * موتور کشف دستگاه‌ها — ترکیب mDNS و UDP Broadcast با ادغام نتایج.
 * فقط وقتی صفحه‌های ارسال/دریافت باز است روشن است؛ با بستن صفحه کامل خاموش می‌شود
 * (پروندهٔ «بک‌گراند صفر» فایلو).
 */
class DiscoveryEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val selfDeviceId: String,
    private val selfDeviceName: String,
    private val selfModel: String,
    private val tcpPort: Int,
) {

    private val registry = DeviceRegistry()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var mdns: MdnsDiscovery? = null
    private var udp: UdpDiscovery? = null
    private var pruneJob: Job? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        _state.value = DiscoveryState.Scanning

        // UDP همیشه — مکانیزم پایه و جایگزین
        udp = UdpDiscovery(
            scope = scope,
            selfDeviceId = selfDeviceId,
            deviceName = selfDeviceName,
            model = selfModel,
            tcpPort = tcpPort,
            onDeviceSeen = ::onDeviceSeen,
        ).also { it.start() }

        // mDNS اگر در دسترس باشد — کشف دقیق‌تر
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd != null) {
            mdns = MdnsDiscovery(
                nsdManager = nsd,
                scope = scope,
                selfDeviceId = selfDeviceId,
                deviceName = selfDeviceName,
                tcpPort = tcpPort,
                onDeviceSeen = ::onDeviceSeen,
                onDeviceLost = registry::markLost,
            ).also { it.start() }
        } else {
            Log.w(TAG, "NsdManager unavailable — UDP discovery only")
        }

        pruneJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                _devices.value = registry.prune(STALE_DEVICE_MS)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        mdns?.stop()
        mdns = null
        udp?.stop()
        udp = null
        pruneJob?.cancel()
        pruneJob = null
        registry.clear()
        _devices.value = emptyList()
        _state.value = DiscoveryState.Idle
    }

    private fun onDeviceSeen(device: DiscoveredDevice) {
        if (device.deviceId == selfDeviceId) return
        registry.upsert(device)
        _devices.value = registry.snapshot()
    }

    companion object {
        private const val TAG = "DiscoveryEngine"
        const val STALE_DEVICE_MS = 7_000L
        const val PRUNE_INTERVAL_MS = 1_500L
    }
}
