package ir.fileo.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryCodecTest {

    @Test
    fun `encode and parse roundtrip preserves packet`() {
        val packet = AnnouncePacket(
            deviceId = "abc-123",
            deviceName = "Sajjad Phone",
            model = "Pixel 7",
            tcpPort = 47778,
        )
        val parsed = DiscoveryCodec.parse(DiscoveryCodec.encode(packet))
        assertEquals(packet, parsed)
    }

    @Test
    fun `parse accepts unknown extra fields - forward compatibility`() {
        val raw = """
            {"protocolVersion":1,"deviceId":"x","deviceName":"n","model":"m","tcpPort":47778,"futureField":42}
        """.trimIndent()
        val parsed = DiscoveryCodec.parse(raw)
        assertEquals("x", parsed?.deviceId)
    }

    @Test
    fun `parse returns null on garbage`() {
        assertNull(DiscoveryCodec.parse("hello"))
        assertNull(DiscoveryCodec.parse(""))
        assertNull(DiscoveryCodec.parse("{{{"))
    }

    @Test
    fun `parse returns null on missing fields`() {
        assertNull(DiscoveryCodec.parse("""{"deviceId":"x"}"""))
        assertNull(DiscoveryCodec.parse("""{"deviceId":"x","deviceName":"n"}"""))
    }

    @Test
    fun `parse rejects wrong protocol version`() {
        val raw = """
            {"protocolVersion":99,"deviceId":"x","deviceName":"n","model":"m","tcpPort":47778}
        """.trimIndent()
        assertNull(DiscoveryCodec.parse(raw))
    }

    @Test
    fun `parse handles persian device name`() {
        val packet = AnnouncePacket(
            deviceId = "id-1",
            deviceName = "گوشی سجاد",
            model = "SM-A515F",
            tcpPort = 47778,
        )
        assertEquals(packet, DiscoveryCodec.parse(DiscoveryCodec.encode(packet)))
    }
}
