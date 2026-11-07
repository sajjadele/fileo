package ir.fileo.core.discovery

/** یک دستگاه فایلو که روی شبکه محلی پیدا شده است. */
data class DiscoveredDevice(
    /** شناسه پایدار دستگاه — از اعلان UDP یا TXT رکورد mDNS؛ برای ادغام دو منبع */
    val deviceId: String,
    val name: String,
    val model: String,
    val ip: String,
    /** پورت TCP سرویس انتقال فایل روی دستگاه مقابل */
    val port: Int,
    val source: Source,
    val lastSeenMs: Long,
) {
    enum class Source { MDNS, UDP }
}

/** وضعیت موتور کشف دستگاه‌ها */
sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data object Scanning : DiscoveryState
}
