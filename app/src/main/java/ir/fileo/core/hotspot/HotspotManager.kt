package ir.fileo.core.hotspot

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** مشخصات هات‌اسپات فعال */
data class HotspotInfo(val ssid: String, val passphrase: String)

/**
 * هات‌اسپات محلی (Local-Only — بدون اینترنت، فقط شبکهٔ محلی بین دو گوشی).
 * عمرش را فقط تا وقتی صفحهٔ اتصال باز است نگه می‌داریم؛ بستن = خاموشی فوری.
 */
class HotspotManager(private val context: Context) {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val isActive: Boolean get() = reservation != null

    /** هات‌اسپات را بالا می‌آورد و مشخصاتش را برمی‌گرداند — خطا با HotspotException */
    suspend fun start(): HotspotInfo {
        stop()
        val wifi = context.getSystemService(WifiManager::class.java)
            ?: throw HotspotException("Wi-Fi service unavailable")

        val newReservation = suspendCancellableCoroutine { continuation ->
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        if (continuation.isActive) continuation.resume(res)
                    }

                    override fun onFailed(reason: Int) {
                        if (continuation.isActive) {
                            continuation.cancel(HotspotException(failureMessage(reason)))
                        }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
        reservation = newReservation
        return extractInfo(newReservation) ?: run {
            stop()
            throw HotspotException("hotspot configuration unavailable")
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }

    private fun extractInfo(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? =
        if (Build.VERSION.SDK_INT >= 30) {
            val config: SoftApConfiguration = res.softApConfiguration
            config.ssid?.let { ssid -> HotspotInfo(ssid, config.passphrase.orEmpty()) }
        } else {
            @Suppress("DEPRECATION")
            val config: WifiConfiguration? = res.wifiConfiguration
            config?.let {
                HotspotInfo(
                    it.SSID?.removeSurrounding("\"").orEmpty(),
                    it.preSharedKey.orEmpty(),
                )
            }
        }

    private fun failureMessage(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "کانال وای‌فای آزاد نیست"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "ساخت هات‌اسپات ناموفق بود"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "حالت فعلی وای‌فای اجازهٔ هات‌اسپات نمی‌دهد"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "هات‌اسپات روی این گوشی مسدود شده است"
        else -> "خطای ناشناختهٔ هات‌اسپات ($reason)"
    }
}

class HotspotException(message: String) : Exception(message)
