package app.rescale.engine.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

data class ImageInfo(
    /** Size after EXIF orientation is applied (what the user sees). */
    val width: Int,
    val height: Int,
    val exifOrientation: Int,
    val mimeType: String?,
)

class UnsupportedImageException(message: String) : Exception(message)

object ImageLoader {
    fun info(context: Context, uri: Uri): ImageInfo {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw UnsupportedImageException("Can't read this image")
        val orientation = orientation(context, uri)
        val swap = orientation in 5..8
        return ImageInfo(
            width = if (swap) opts.outHeight else opts.outWidth,
            height = if (swap) opts.outWidth else opts.outHeight,
            exifOrientation = orientation,
            mimeType = opts.outMimeType,
        )
    }

    private fun orientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * Full-resolution, un-rotated, non-premultiplied RGBA. The engine applies the EXIF
     * orientation itself while reading tiles, so we never allocate a rotated copy.
     */
    fun decodeForEngine(context: Context, uri: Uri): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw UnsupportedImageException("Can't decode this image")
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            return copy
        }
        return bmp
    }

    /** Downscaled, correctly rotated bitmap for on-screen display (long side <= maxSide). */
    fun decodePreview(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val info = runCatching { info(context, uri) }.getOrNull() ?: return null
        var sample = 1
        while (maxOf(info.width, info.height) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        return rotate(bmp, info.exifOrientation)
    }

    fun rotate(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
