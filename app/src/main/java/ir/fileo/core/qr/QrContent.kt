package ir.fileo.core.qr

import java.util.Base64

/**
 * محتوای QR اتصال فایلو:
 * `fileo://connect?v=1&did=…&name=…&fp=…&hot=0|1&ssid=…&pass=…`
 *
 * hot=1 → شامل مشخصات هات‌اسپات (گوشی مقابل باید به آن شبکه وصل شود)
 * hot=0 → اتصال روی شبکهٔ موجود (بعد از اتصال، discovery خودش پیدایش می‌کند)
 *
 * مقادیر free-text با Base64URL رمز می‌شوند تا هر کاراکتری (فارسی/فاصله/&…) امن باشد.
 */
data class QrContent(
    val deviceId: String,
    val deviceName: String,
    /** SHA-256 گواهی TLS — نمایش کد اعتماد (تطبیق واقعی در فاز امنیتی بعدی) */
    val fingerprint: String,
    val hotspot: HotspotInfo?,
) {
    data class HotspotInfo(val ssid: String, val passphrase: String)

    fun encode(): String = buildString {
        append("fileo://connect?v=").append(VERSION)
        append("&did=").append(b64url(deviceId))
        append("&name=").append(b64url(deviceName))
        append("&fp=").append(b64url(fingerprint))
        val hot = hotspot
        if (hot != null) {
            append("&hot=1")
            append("&ssid=").append(b64url(hot.ssid))
            append("&pass=").append(b64url(hot.passphrase))
        } else {
            append("&hot=0")
        }
    }

    companion object {
        const val VERSION = 1

        /** پارس امن — هر ورودی نامعتبر/غریبه null، هرگز exception نمی‌دهد */
        fun parse(raw: String): QrContent? = runCatching {
            if (!raw.startsWith("fileo://connect?")) return@runCatching null
            val params = raw.substringAfter('?')
                .split('&')
                .mapNotNull { part ->
                    part.split('=', limit = 2)
                        .takeIf { it.size == 2 }
                        ?.let { it[0] to it[1] }
                }
                .toMap()

            if (params["v"]?.toIntOrNull() != VERSION) return@runCatching null

            val deviceId = unb64(params["did"]) ?: return@runCatching null
            val deviceName = unb64(params["name"]) ?: return@runCatching null
            val fingerprint = unb64(params["fp"]) ?: return@runCatching null

            val hotspot = when (params["hot"]) {
                "1" -> QrContent.HotspotInfo(
                    ssid = unb64(params["ssid"]) ?: return@runCatching null,
                    passphrase = unb64(params["pass"]) ?: return@runCatching null,
                )
                "0" -> null
                else -> return@runCatching null
            }

            QrContent(deviceId, deviceName, fingerprint, hotspot)
        }.getOrNull()

        private fun b64url(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun unb64(value: String?): String? =
            value?.let {
                runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
            }
    }
}
