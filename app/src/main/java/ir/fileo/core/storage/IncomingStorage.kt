package ir.fileo.core.storage

import android.content.Context
import ir.fileo.core.transfer.IncomingSink
import ir.fileo.core.transfer.IncomingSinkFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * مقصد ذخیرهٔ فایل دریافتی — نسخهٔ ۱: پوشهٔ Incoming حافظهٔ اپ
 * (پایهٔ تست دو-گوشی؛ مسیریابی MediaStore/Downloads در push بعدی جایگزین می‌شود).
 */
class AndroidIncomingSinkFactory(private val context: Context) : IncomingSinkFactory {

    override fun open(fileName: String, mimeType: String, size: Long): IncomingSink {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Incoming").apply { mkdirs() }
        val unique = uniqueName(dir, sanitize(fileName))
        val target = File(dir, unique)
        return object : IncomingSink {
            override val displayName: String = unique

            override fun outputStream(): OutputStream = FileOutputStream(target).buffered(BUFFER)

            override fun onAborted() {
                target.delete()
            }
        }
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|\u0000]"), "_")
            .ifBlank { "file" }

    private fun uniqueName(dir: File, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var counter = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base ($counter)$ext"
            counter++
        }
        return candidate
    }

    companion object {
        private const val BUFFER = 64 * 1024
    }
}
