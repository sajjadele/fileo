package ir.fileo.core.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class ProtocolTest {

    private fun roundtrip(message: WireMessage): WireMessage {
        val bytes = ByteArrayOutputStream()
        Protocol.writeMessage(DataOutputStream(bytes), message)
        return Protocol.readMessage(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test
    fun `hello roundtrip with persian name`() {
        val msg = WireMessage.Hello(deviceId = "d1", deviceName = "گوشی سجاد", model = "SM-A515F")
        assertEquals(msg, roundtrip(msg))
    }

    @Test
    fun `offer roundtrip with multiple files`() {
        val msg = WireMessage.Offer(
            transferId = "t1",
            files = listOf(
                OfferedFile("f1", "a.png", 123L, "image/png"),
                OfferedFile("f2", "b.mp4", 999L, "video/mp4"),
            ),
            totalSize = 1122L,
        )
        assertEquals(msg, roundtrip(msg))
    }

    @Test
    fun `all other message types roundtrip`() {
        assertEquals(WireMessage.Response("t1", accepted = true), roundtrip(WireMessage.Response("t1", accepted = true)))
        assertEquals(WireMessage.Response("t1", accepted = false, reason = "busy"), roundtrip(WireMessage.Response("t1", accepted = false, reason = "busy")))
        assertEquals(WireMessage.FileStart("t1", "f1", 5L), roundtrip(WireMessage.FileStart("t1", "f1", 5L)))
        assertEquals(WireMessage.FileEnd("t1", "f1", "abc123"), roundtrip(WireMessage.FileEnd("t1", "f1", "abc123")))
        assertEquals(WireMessage.Complete("t1"), roundtrip(WireMessage.Complete("t1")))
        assertEquals(WireMessage.Error(transferId = null, code = 2, message = "boom"), roundtrip(WireMessage.Error(code = 2, message = "boom")))
        assertEquals(WireMessage.Cancel("t1"), roundtrip(WireMessage.Cancel("t1")))
    }

    @Test
    fun `unknown message type is rejected`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).let {
            val payload = """{"type":"mystery"}""".toByteArray(Charsets.UTF_8)
            it.writeInt(payload.size)
            it.write(payload)
        }
        assertThrows(IOException::class.java) {
            Protocol.readMessage(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }

    @Test
    fun `oversized length is rejected`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(Protocol.MAX_MESSAGE_BYTES + 1)
        assertThrows(IOException::class.java) {
            Protocol.readMessage(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }

    @Test
    fun `readRaw reads exactly size bytes across chunk boundary`() {
        val payload = ByteArray(150_000) { (it % 251).toByte() }
        val input = DataInputStream(ByteArrayInputStream(payload))
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)

        Protocol.readRaw(input, payload.size.toLong(), buffer) { count ->
            collected.write(buffer, 0, count)
        }

        assertArrayEquals(payload, collected.toByteArray())
    }

    @Test
    fun `readRaw throws when stream ends early`() {
        val short = ByteArray(100)
        val input = DataInputStream(ByteArrayInputStream(short))
        assertThrows(java.io.EOFException::class.java) {
            Protocol.readRaw(input, 1_000L, ByteArray(64 * 1024)) {}
        }
    }
}
