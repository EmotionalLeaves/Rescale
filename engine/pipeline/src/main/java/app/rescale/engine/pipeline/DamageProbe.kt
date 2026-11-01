package app.rescale.engine.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.rescale.core.model.PackModels
import app.rescale.core.model.Packs
import app.rescale.engine.nativebridge.NativeEngine

/** Detected damage on the display preview: shown in the mask editor, and its share feeds Auto. */
class DamagePreview(val mask: Bitmap, val coverage: Float)

/**
 * Runs the scratch/tear detector on a preview (needs the Repair pack). Quick: the network sees
 * the image at ~512 px on the short side whatever the preview size.
 */
object DamageProbe {
    fun detect(context: Context, preview: Bitmap?, packs: PackStore = PackStore(context)): DamagePreview? {
        if (preview == null || Packs.REPAIR !in packs.installed()) return null
        return runCatching {
            val (p, b) = ModelStore(context, packs).resolve(PackModels.SCRATCH)
            NativeEngine.openScratchDetector(p.path, b.path, false, 2)?.use { det ->
                val src = if (preview.config == Bitmap.Config.ARGB_8888) preview else preview.copy(Bitmap.Config.ARGB_8888, false)
                det.detect(src)?.let { (mask, f) -> DamagePreview(mask, f) }
                    .also { if (src !== preview) src.recycle() }
            }
        }.getOrNull()
    }

    /** Coverage for Auto in the batch queue (no preview kept). */
    fun coverage(context: Context, uri: Uri, packs: PackStore = PackStore(context)): Float {
        if (Packs.REPAIR !in packs.installed()) return 0f
        val bmp = ImageLoader.decodePreview(context, uri, 1280) ?: return 0f
        return try {
            detect(context, bmp, packs)?.also { it.mask.recycle() }?.coverage ?: 0f
        } finally {
            bmp.recycle()
        }
    }
}
