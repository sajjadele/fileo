package ir.fileo.core.net

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
}
