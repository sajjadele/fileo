package ir.fileo.core.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * کشف دستگاه‌ها با mDNS (سرویس `ش fileo._tcp`).
 * هم خودش روی شبکه اعلام می‌شود و هم بقیه را پیدا می‌کند.
 *
 * نکتهٔ مهم: NsdManager در اندرویدهای قدیمی‌تر از ۱۴ فقط یک resolve هم‌زمان را
 * تحمل می‌کند؛ به همین دلیل resolveها با یک صف سریال می‌شوند.
 */
class MdnsDiscovery(
    private val nsdManager: NsdManager,
    private val scope: CoroutineScope,
    private val selfDeviceId: String,
    private val deviceName: String,
    private val tcpPort: Int,
    private val onDeviceSeen: (DiscoveredDevice) -> Unit,
    private val onDeviceLost: (serviceName: String) -> Unit,
) {

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** نام واقعی ثبت‌شده — ممکن است اندروید در برخورد اسم، پسوند «(2)» اضافه کند */
    @Volatile
    private var registeredName: String = deviceName

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()

    @Volatile
    private var resolveBusy = false

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        registerService()
        startDiscovery()
    }

    fun stop() {
        if (!running) return
        running = false
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        resolveQueue.clear()
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            port = tcpPort
            // deviceId در TXT رکورد → ادغام با نتایج UDP ممکن می‌شود
            setAttribute(TXT_DEVICE_ID, selfDeviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredName = serviceInfo.serviceName
                Log.d(TAG, "service registered as '$registeredName'")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService threw: $it") }
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
                discoveryListener = null
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == registeredName) return // خودمان
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onDeviceLost(serviceInfo.serviceName)
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discoverServices threw: $it"); discoveryListener = null }
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.add(info)
        pumpResolveQueue()
    }

    @Synchronized
    private fun pumpResolveQueue() {
        if (!running || resolveBusy) return
        val info = resolveQueue.poll() ?: return
        resolveBusy = true
        scope.launch {
            val device = runCatching { resolve(info) }.getOrNull()
            resolveBusy = false
            device?.let(onDeviceSeen)
            pumpResolveQueue()
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): DiscoveredDevice? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                nsdManager.resolveService(info, object : NsdManager.ResolveListener() {
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(toDevice(serviceInfo))
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed for '${serviceInfo.serviceName}': $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        }

    private fun toDevice(info: NsdServiceInfo): DiscoveredDevice? {
        val host: java.net.InetAddress? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                info.host
            }
        val ip = host?.hostAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        val deviceId = info.attributes.entries
            .firstOrNull { it.key.equals(TXT_DEVICE_ID, ignoreCase = true) }
            ?.value
            ?.toString(Charsets.UTF_8)
            ?: info.serviceName

        return DiscoveredDevice(
            deviceId = deviceId,
            name = info.serviceName,
            model = "",
            ip = ip.orEmpty(),
            port = info.port,
            source = DiscoveredDevice.Source.MDNS,
            lastSeenMs = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "MdnsDiscovery"
        const val SERVICE_TYPE = "_fileo._tcp."
        const val TXT_DEVICE_ID = "id"
        private const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}
