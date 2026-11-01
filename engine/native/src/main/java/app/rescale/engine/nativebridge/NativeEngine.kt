package app.rescale.engine.nativebridge

import android.graphics.Bitmap

/** Called from the native thread after every finished tile. */
fun interface ProgressListener {
    fun onProgress(done: Int, total: Int)
}

/**
 * Artifact controls. [detail]: 1 = pure model, 0 = pure bicubic. [lumaOnly]: keep the
 * model's brightness but the source's colour. [colorFix]: pull large-scale colour and
 * brightness back to the source.
 */
data class PostOptions(val detail: Float = 1f, val lumaOnly: Boolean = false, val colorFix: Boolean = false)

/** Result codes from the native engine (mirror of rescale::Status). */
object NativeStatus {
    const val OK = 0
    const val CANCELLED = 1
    const val MODEL_ERROR = -1
    const val WRITE_ERROR = -2
    const val BAD_INPUT = -3
}

/**
 * Thin wrapper around the C++ engine. One [NativeEngine.Session] = one loaded model on
 * one backend (Vulkan or CPU).
 */
object NativeEngine {
    init {
        System.loadLibrary("rescale")
    }

    fun gpuCount(): Int = nativeGpuCount()

    class Session internal constructor(private var handle: Long) : AutoCloseable {
        val usingGpu: Boolean get() = nativeUsingGpu(handle)

        fun probe(): FloatArray? = nativeProbe(handle)

        /**
         * Upscale [bitmap] (RGBA_8888, ideally not premultiplied) and stream a PNG to
         * [outPath]. A small preview PNG (long side <= [previewMax]) is written alongside.
         */
        fun upscale(
            bitmap: Bitmap,
            exifOrientation: Int,
            tile: Int,
            downsample: Int,
            post: PostOptions,
            maxBandBytes: Long,
            outPath: String,
            previewPath: String?,
            previewMax: Int,
            listener: ProgressListener?,
        ): Int = nativeUpscale(
            handle, bitmap, exifOrientation, tile, downsample,
            post.detail, post.lumaOnly, post.colorFix, maxBandBytes,
            outPath, previewPath, previewMax, listener,
        )

        fun cancel() = nativeCancel(handle)

        override fun close() {
            if (handle != 0L) nativeDestroy(handle)
            handle = 0L
        }
    }

    /** Returns null if the model files can't be loaded. */
    fun open(
        paramPath: String,
        binPath: String,
        inputBlob: String,
        outputBlob: String,
        scale: Int,
        gpu: Boolean,
        threads: Int,
    ): Session? {
        val h = nativeCreate(paramPath, binPath, inputBlob, outputBlob, scale, gpu, threads)
        return if (h == 0L) null else Session(h)
    }

    @JvmStatic private external fun nativeGpuCount(): Int
    @JvmStatic private external fun nativeCreate(
        param: String, bin: String, inBlob: String, outBlob: String, scale: Int, gpu: Boolean, threads: Int,
    ): Long
    @JvmStatic private external fun nativeUsingGpu(handle: Long): Boolean
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeProbe(handle: Long): FloatArray?
    @JvmStatic private external fun nativeUpscale(
        handle: Long, bitmap: Bitmap, orientation: Int, tile: Int, downsample: Int,
        detail: Float, lumaOnly: Boolean, colorFix: Boolean, maxBandBytes: Long,
        outPath: String, previewPath: String?, previewMax: Int, listener: ProgressListener?,
    ): Int
}
