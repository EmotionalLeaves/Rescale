package app.rescale.engine.pipeline

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import app.rescale.engine.nativebridge.NativeEngine
import java.io.File

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.PNG,
    /** JPEG / lossy WebP quality, 1..100. */
    val quality: Int = 92,
    val webpLossless: Boolean = false,
    /** Copy camera info (make, model, date, exposure…) from the original. GPS is never copied. */
    val keepExif: Boolean = true,
)

data class Exported(val file: File, val format: ExportFormat, val note: String?)

object Exporter {
    const val WEBP_MAX = 16383
    const val JPEG_MAX = 65535

    // Camera tags worth keeping. Location (GPS*) is deliberately absent.
    private val KEEP = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
    )

    /**
     * Converts the job's PNG into [opts].format inside [dir]. Falls back to PNG (with a note)
     * when the format can't hold the image (WebP > 16383 px, JPEG > 65535 px) or memory is short.
     */
    fun export(context: Context, png: File, original: Uri?, opts: ExportOptions, dir: File, baseName: String): Exported {
        dir.mkdirs()
        val (w, h) = pngSize(png)
        var format = opts.format
        var note: String? = null
        if (format == ExportFormat.WEBP && maxOf(w, h) > WEBP_MAX) {
            format = ExportFormat.PNG
            note = "WebP is limited to $WEBP_MAX px, so this was saved as PNG"
        }
        if (format == ExportFormat.JPEG && maxOf(w, h) > JPEG_MAX) {
            format = ExportFormat.PNG
            note = "JPEG is limited to $JPEG_MAX px, so this was saved as PNG"
        }
        if (format == ExportFormat.WEBP && !fitsInMemory(context, w.toLong() * h * 4 * 2)) {
            format = ExportFormat.PNG
            note = "Not enough memory to encode WebP at this size, so this was saved as PNG"
        }
        val out = File(dir, "$baseName.${format.ext}")
        when (format) {
            ExportFormat.PNG -> png.copyTo(out, overwrite = true)
            ExportFormat.JPEG -> if (!NativeEngine.pngToJpeg(png.path, out.path, opts.quality)) error("JPEG encoding failed")
            ExportFormat.WEBP -> encodeWebp(png, out, opts)
        }
        if (opts.keepExif && original != null) copyExif(context, original, out, w, h)
        return Exported(out, format, note)
    }

    private fun encodeWebp(png: File, out: File, opts: ExportOptions) {
        val bmp = BitmapFactory.decodeFile(png.path) ?: error("Couldn't read the result")
        try {
            val fmt = when {
                Build.VERSION.SDK_INT >= 30 && opts.webpLossless -> Bitmap.CompressFormat.WEBP_LOSSLESS
                Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            val q = if (opts.webpLossless) 100 else opts.quality
            out.outputStream().use { if (!bmp.compress(fmt, q, it)) error("WebP encoding failed") }
        } finally {
            bmp.recycle()
        }
    }

    private fun copyExif(context: Context, original: Uri, out: File, w: Int, h: Int) = runCatching {
        val src = context.contentResolver.openInputStream(original)?.use { ExifInterface(it) } ?: return@runCatching
        val dst = ExifInterface(out)
        var any = false
        for (tag in KEEP) src.getAttribute(tag)?.let { dst.setAttribute(tag, it); any = true }
        if (!any) return@runCatching
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
        dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Rescale")
        dst.saveAttributes()
    }

    fun pngSize(png: File): Pair<Int, Int> {
        val b = ByteArray(24)
        png.inputStream().use { it.read(b) }
        fun be(o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        return be(16) to be(20)
    }

    private fun fitsInMemory(context: Context, bytes: Long): Boolean {
        val mi = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return bytes < mi.availMem / 2
    }
}
