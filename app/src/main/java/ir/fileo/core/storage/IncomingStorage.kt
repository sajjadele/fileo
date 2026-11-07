package ir.fileo.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ir.fileo.core.transfer.IncomingSink
import ir.fileo.core.transfer.IncomingSinkFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * مقصد ذخیرهٔ فایل دریافتی:
 * - API 29+: MediaStore — رسانه‌ها در گالری، بقیه در Downloads/Fileo — بدون مجوز
 * - API 26-28: مسیر مستقیم Downloads/Fileo (با مجوز WRITE_EXTERNAL_STORAGE)
 */
class AndroidIncomingSinkFactory(private val context: Context) : IncomingSinkFactory {

    override fun open(fileName: String, mimeType: String, size: Long): IncomingSink =
        if (Build.VERSION.SDK_INT >= 29) {
            mediaStoreSink(fileName, mimeType)
        } else {
            legacyFileSink(fileName)
        }

    // ---------- API 29+ : MediaStore ----------

    private fun mediaStoreSink(fileName: String, mimeType: String): IncomingSink {
        val resolver = context.contentResolver
        val (collection, relativePath) = when {
            mimeType.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                    "${Environment.DIRECTORY_PICTURES}/Fileo"
            mimeType.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
                    "${Environment.DIRECTORY_MOVIES}/Fileo"
            mimeType.startsWith("audio/") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to
                    "${Environment.DIRECTORY_MUSIC}/Fileo"
            else ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to
                    "${Environment.DIRECTORY_DOWNLOADS}/Fileo"
        }

        val safeName = sanitize(fileName)
        val uniqueName = uniqueMediaName(resolver, collection, relativePath, safeName)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")
        val stream = resolver.openOutputStream(uri)
            ?: run {
                resolver.delete(uri, null, null)
                throw IOException("cannot open output stream for $uniqueName")
            }

        return object : IncomingSink {
            override val displayName: String = "$relativePath/$uniqueName"

            override fun outputStream(): OutputStream = stream

            override fun onCompleted() {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                runCatching { resolver.update(uri, done, null, null) }
            }

            override fun onAborted() {
                runCatching { stream.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    /** جلوگیری از بازنویسی فایل موجود — نام با «(n)» تکرار می‌شود */
    private fun uniqueMediaName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        name: String,
    ): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var counter = 1
        while (mediaNameExists(resolver, collection, relativePath, candidate)) {
            candidate = "$base ($counter)$ext"
            counter++
        }
        return candidate
    }

    private fun mediaNameExists(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        name: String,
    ): Boolean = runCatching {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, name),
            null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    // ---------- API 26-28 : مسیر مستقیم ----------

    private fun legacyFileSink(fileName: String): IncomingSink {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Fileo",
        ).apply { mkdirs() }
        val unique = uniqueDiskName(dir, sanitize(fileName))
        val target = File(dir, unique)
        return object : IncomingSink {
            override val displayName: String = "Download/Fileo/$unique"

            override fun outputStream(): OutputStream = FileOutputStream(target).buffered(BUFFER)

            override fun onAborted() {
                target.delete()
            }
        }
    }

    private fun uniqueDiskName(dir: File, name: String): String {
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

    private fun sanitize(name: String): String =
        name.substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|\u0000]"), "_")
            .ifBlank { "file" }

    companion object {
        private const val BUFFER = 64 * 1024
    }
}
