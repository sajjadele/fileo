package ir.fileo.core.discovery

/**
 * مخزن دستگاه‌های پیدا شده — منطق خالص (بدون اندروید) و کاملاً تست‌پذیر.
 * وظایف: ادغام دو منبع mDNS/UDP بر اساس deviceId، حذف دستگاه‌های stale.
 */
class DeviceRegistry(private val now: () -> Long = System::currentTimeMillis) {

    private data class Entry(
        val device: DiscoveredDevice,
        val aliases: Set<String>,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * افزودن/به‌روزرسانی دستگاه.
     * @param alias شناسه جایگزین (مثلاً نام سرویس mDNS) برای حذف سریع با markLost
     */
    @Synchronized
    fun upsert(device: DiscoveredDevice, alias: String? = null) {
        val existing = entries[device.deviceId]
        if (existing == null) {
            val aliases = buildSet {
                add(device.deviceId)
                alias?.let(::add)
            }
            entries[device.deviceId] = Entry(device, aliases)
            return
        }
        // نتیجهٔ resolve موقت mDNS ممکن است ip خالی داشته باشد — ip معتبر قبلی را نگه می‌داریم
        val merged = if (device.ip.isBlank() && existing.device.ip.isNotBlank()) {
            existing.device.copy(lastSeenMs = device.lastSeenMs)
        } else {
            device
        }
        val updatedAliases = alias?.let { existing.aliases + it } ?: existing.aliases
        entries[device.deviceId] = Entry(merged, updatedAliases)
    }

    /** حذف دستگاه با هر شناسه‌ای که داشته باشد (deviceId یا alias) — وقتی mDNS سرویس را گم می‌کند */
    @Synchronized
    fun markLost(alias: String) {
        entries.entries.removeIf { alias in it.value.aliases }
    }

    /** حذف دستگاه‌هایی که مدتی دیده نشده‌اند و برگرداندن لیست فعلی */
    @Synchronized
    fun prune(maxAgeMs: Long): List<DiscoveredDevice> {
        val currentTime = now()
        entries.entries.removeIf { currentTime - it.value.device.lastSeenMs > maxAgeMs }
        return snapshot()
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun snapshot(): List<DiscoveredDevice> =
        entries.values.map { it.device }.sortedBy { it.name }
}
