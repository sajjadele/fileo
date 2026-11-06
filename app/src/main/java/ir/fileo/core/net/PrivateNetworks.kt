package ir.fileo.core.net

/**
 * تشخیص آدرس‌های IP خصوصی — بخشی از «قفل شبکه» فایلو:
 * اپ فقط اجازه اتصال به دستگاه‌های شبکه محلی را دارد و هرگز به اینترنت وصل نمی‌شود.
 */
object PrivateNetworks {

    /** آیا این IP متعلق به شبکه محلی/خصوصی است؟ */
    fun isPrivateIp(ip: String): Boolean {
        val v4 = toIpv4Long(ip)
        if (v4 != null) {
            return v4 in IPV4_LOOPBACK ||
                v4 in IPV4_PRIVATE_10 ||
                v4 in IPV4_LINK_LOCAL ||
                v4 in IPV4_PRIVATE_172 ||
                v4 in IPV4_PRIVATE_192
        }
        return isPrivateIpv6(ip)
    }

    private fun isPrivateIpv6(ip: String): Boolean {
        val v6 = ip.trim().lowercase()
        return v6 == "::1" || // loopback
            v6.startsWith("fc") || v6.startsWith("fd") || // Unique Local Address fc00::/7
            v6.startsWith("fe80") // link-local fe80::/10
    }

    private fun toIpv4Long(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            result = (result shl 8) or n.toLong()
        }
        return result
    }

    private val IPV4_LOOPBACK = 0x7F000000L..0x7FFFFFFFL // 127.0.0.0/8
    private val IPV4_PRIVATE_10 = 0x0A000000L..0x0AFFFFFFL // 10.0.0.0/8
    private val IPV4_LINK_LOCAL = 0xA9FE0000L..0xA9FEFFFFL // 169.254.0.0/16
    private val IPV4_PRIVATE_172 = 0xAC100000L..0xAC1FFFFFL // 172.16.0.0/12
    private val IPV4_PRIVATE_192 = 0xC0A80000L..0xC0AFFFFFL // 192.168.0.0/16
}
