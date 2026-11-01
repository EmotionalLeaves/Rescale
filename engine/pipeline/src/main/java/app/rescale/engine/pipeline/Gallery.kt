package app.rescale.engine.pipeline

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Saves exported files into Pictures/Rescale (MediaStore on 10+, public folder on 8–9). */
object Gallery {
    fun save(context: Context, e: Exported) {
        val name = e.file.name
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, e.format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Rescale")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the file")
            try {
                resolver.openOutputStream(uri)?.use { out -> e.file.inputStream().use { it.copyTo(out) } }
                    ?: error("Can't write to gallery")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (ex: Exception) {
                resolver.delete(uri, null, null)
                throw ex
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Rescale")
            dir.mkdirs()
            val target = File(dir, name)
            e.file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(e.format.mime), null)
        }
    }

    /** "IMG_1234.jpg" → "IMG_1234" (file-system safe), or null. */
    fun stem(displayName: String?): String? = displayName?.substringBeforeLast('.')?.take(60)
        ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")?.trim('_')?.takeIf { it.isNotEmpty() }
}
