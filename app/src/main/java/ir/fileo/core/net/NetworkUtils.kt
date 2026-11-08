package ir.fileo.core.net

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** اولین IP محلی (site-local IPv4) این دستگاه — مثلاً 192.168.1.5 */
    fun findLocalIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** گیت‌وی وای‌فای فعلی — در شبکهٔ هات‌اسپات، همان گوشی سازنده است */
    fun wifiGatewayIp(context: Context): String? = runCatching {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return@runCatching null
        @Suppress("DEPRECATION")
        val dhcp = wifi.dhcpInfo ?: return@runCatching null
        if (dhcp.gateway == 0) return@runCatching null
        intToIp(dhcp.gateway)
    }.getOrNull()

    private fun intToIp(value: Int): String =
        "${value and 0xFF}.${value shr 8 and 0xFF}.${value shr 16 and 0xFF}.${value shr 24 and 0xFF}"
}
