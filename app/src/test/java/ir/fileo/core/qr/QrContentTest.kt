package ir.fileo.core.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrContentTest {

    private val content = QrContent(
        deviceId = "device-abc-123",
        deviceName = "گوشی سجاد",
        fingerprint = "a1b2c3d4e5f6",
        hotspot = QrContent.HotspotInfo(ssid = "AndroidShare_1234", passphrase = "p@ss w0rd!#"),
    )

    @Test
    fun `encode parse roundtrip with hotspot`() {
        val parsed = QrContent.parse(content.copy(hotspot = content.hotspot).encode())
        assertEquals(content, parsed)
    }

    @Test
    fun `encode parse roundtrip without hotspot`() {
        val noHotspot = content.copy(hotspot = null)
        assertEquals(noHotspot, QrContent.parse(noHotspot.encode()))
    }

    @Test
    fun `encoded value uses fileo scheme`() {
        assertTrue(content.encode().startsWith("fileo://connect?v=1"))
        assertTrue(content.copy(hotspot = null).encode().contains("hot=0"))
        assertTrue(content.encode().contains("hot=1"))
    }

    @Test
    fun `garbage inputs return null`() {
        assertNull(QrContent.parse(""))
        assertNull(QrContent.parse("hello"))
        assertNull(QrContent.parse("https://example.com/fileo://connect?v=1"))
        assertNull(QrContent.parse("fileo://connect?v=1&did=!!!")) // b64 نامعتبر
    }

    @Test
    fun `foreign scheme or unknown version is rejected`() {
        assertNull(QrContent.parse("fileo://other?v=1&did=a&name=b&fp=c&hot=0"))
        val wrongVersion = content.encode().replace("v=1", "v=99")
        assertNull(QrContent.parse(wrongVersion))
    }

    @Test
    fun `missing hotspot fields on hot=1 rejected`() {
        val broken = "fileo://connect?v=1&did=" + b64("d1") + "&name=" + b64("n") +
            "&fp=" + b64("f") + "&hot=1"
        assertNull(QrContent.parse(broken))
    }

    private fun b64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
