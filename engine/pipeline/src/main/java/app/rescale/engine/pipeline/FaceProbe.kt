package app.rescale.engine.pipeline

import android.content.Context
import android.net.Uri
import app.rescale.core.model.PackModels
import app.rescale.core.model.Packs
import app.rescale.engine.nativebridge.NativeEngine

/** Counts faces small enough to restore, for Auto mode. Needs the face pack; quick (~0.5 s). */
object FaceProbe {
    fun smallFaces(context: Context, uri: Uri, info: ImageInfo, packs: PackStore = PackStore(context)): Int {
        if (Packs.FACES !in packs.installed()) return 0
        return runCatching {
            val (p, b) = ModelStore(context, packs).resolve(PackModels.RETINAFACE)
            val det = NativeEngine.openFaceDetector(p.path, b.path, false, 2) ?: return 0
            det.use {
                val bmp = ImageLoader.decodePreview(context, uri, 1280) ?: return 0
                val scale = maxOf(info.width, info.height).toFloat() / maxOf(bmp.width, bmp.height)
                val r = it.detect(bmp, 1) ?: return 0
                bmp.recycle()
                val count = r[0].toInt()
                val largest = r[1] * scale
                if (count > 0 && largest <= Upscaler.MAX_FACE_PX) count else 0
            }
        }.getOrDefault(0)
    }
}
