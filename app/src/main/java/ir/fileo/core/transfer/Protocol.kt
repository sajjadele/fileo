package ir.fileo.core.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * پیام‌های کنترلی پروتکل — JSON طول‌پیشوندی (4 بایت big-endian + JSON UTF-8)
 * بایت‌های خام فایل خارج از این فریم‌بندی، بین FileStart و FileEnd جریان می‌یابند.
 */
@Serializable
sealed class WireMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int = Protocol.PROTOCOL_VERSION,
        val deviceId: String,
        val deviceName: String,
        val model: String,
    ) : WireMessage()

    @Serializable
    @SerialName("offer")
    data class Offer(
        val transferId: String,
        val files: List<OfferedFile>,
        val totalSize: Long,
    ) : WireMessage()

    @Serializable
    @SerialName("response")
    data class Response(
        val transferId: String,
        val accepted: Boolean,
        val reason: String? = null,
    ) : WireMessage()

    @Serializable
    @SerialName("file_start")
    data class FileStart(
        val transferId: String,
        val fileId: String,
        val size: Long,
    ) : WireMessage()

    @Serializable
    @SerialName("file_end")
    data class FileEnd(
        val transferId: String,
        val fileId: String,
        val sha256: String,
    ) : WireMessage()

    @Serializable
    @SerialName("complete")
    data class Complete(
        val transferId: String,
    ) : WireMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val transferId: String? = null,
        val code: Int,
        val message: String,
    ) : WireMessage()

    @Serializable
    @SerialName("cancel")
    data class Cancel(
        val transferId: String,
    ) : WireMessage()
}

object Protocol {

    const val PROTOCOL_VERSION = 1

    /** سقف پیام کنترلی — ۱MB (دفاع در برابر ورودی خراب/مخرب) */
    const val MAX_MESSAGE_BYTES = 1 shl 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun writeMessage(out: DataOutputStream, message: WireMessage) {
        val bytes = json.encodeToString(WireMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MESSAGE_BYTES) throw IOException("message too large: ${bytes.size}")
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** خواندن امن — طول خارج از سقف یا JSON خراب = IOException */
    fun readMessage(input: DataInputStream): WireMessage {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE_BYTES) throw IOException("bad message length: $length")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return runCatching {
            json.decodeFromString(WireMessage.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrElse { throw IOException("malformed message: ${it.message}") }
    }

    /**
     * خواندن دقیقاً [size] بایت خام فایل از جریان، تکه‌تکه به [onChunk].
     * اگر جریان قبل از اتمام بسته شود = EOFException.
     */
    fun readRaw(input: DataInputStream, size: Long, buffer: ByteArray, onChunk: (Int) -> Unit) {
        require(buffer.size >= 64 * 1024) { "read buffer too small" }
        var remaining = size
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) throw EOFException("stream ended during file data ($remaining bytes missing)")
            onChunk(read)
            remaining -= read
        }
    }
}
