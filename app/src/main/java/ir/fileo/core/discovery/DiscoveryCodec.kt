package ir.fileo.core.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * بستهٔ اعلام وجود (Announce) که هر ۲ ثانیه با UDP Broadcast پخش می‌شود.
 * کدک آن خالص JVM است تا unit test کامل داشته باشد.
 */
@Serializable
data class AnnouncePacket(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val deviceName: String,
    val model: String,
    val tcpPort: Int,
) {
    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

object DiscoveryCodec {

    // ignoreUnknownKeys برای سازگاری رو به جلو (نسخه‌های جدید فیلد اضافه می‌کنند)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(packet: AnnouncePacket): String =
        json.encodeToString(AnnouncePacket.serializer(), packet)

    /** پارس امن — هر ورودی خراب/ناموثق به null تبدیل می‌شود، هرگز exception نمی‌دهد */
    fun parse(raw: String): AnnouncePacket? = runCatching {
        json.decodeFromString(AnnouncePacket.serializer(), raw)
    }.getOrNull()?.takeIf { it.protocolVersion == AnnouncePacket.PROTOCOL_VERSION }
}
