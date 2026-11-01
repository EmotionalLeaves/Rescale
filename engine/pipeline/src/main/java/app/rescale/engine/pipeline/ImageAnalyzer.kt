package app.rescale.engine.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import app.rescale.core.model.Analysis
import app.rescale.core.model.JpegQuality

object ImageAnalyzer {
    private const val CROP = 1024

    /**
     * Analyses a full-resolution centre crop (≤ 1024², aligned to the 8×8 JPEG grid in stored
     * pixel coordinates). Orientation doesn't matter for these statistics.
     */
    fun analyze(context: Context, uri: Uri, info: ImageInfo): Analysis? = runCatching {
        val header = context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(64 * 1024)
            var n = 0
            while (n < buf.size) {
                val r = s.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        } ?: return null
        val quality = JpegQuality.estimate(header)

        val swap = info.exifOrientation in 5..8
        val sw = if (swap) info.height else info.width
        val sh = if (swap) info.width else info.height
        val cw = minOf(sw, CROP) / 8 * 8
        val ch = minOf(sh, CROP) / 8 * 8
        val x0 = ((sw - cw) / 2) / 8 * 8
        val y0 = ((sh - ch) / 2) / 8 * 8

        val bmp: Bitmap = if (cw >= 16 && ch >= 16) {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val dec = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(s)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(s, false)
                try {
                    dec?.decodeRegion(Rect(x0, y0, x0 + cw, y0 + ch), BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                } finally {
                    dec?.recycle()
                }
            }
        } else {
            null
        } ?: context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null

        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        Analysis.of(px, w, h, info.width, info.height, quality)
    }.getOrNull()
}
