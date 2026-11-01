package app.rescale.engine.nativebridge

import android.graphics.Bitmap

/** Called from the native thread: [onPhase] when a stage starts, [onProgress] after every tile. */
interface ProgressListener {
    fun onProgress(done: Int, total: Int)

    /** One of [Phase]. Tile counts restart from 0 in each phase. */
    fun onPhase(phase: Int)
}

object Phase {
    const val UPSCALE = 0
    const val REPAIR = 1
}

/**
 * Artifact controls. [detail]: 1 = pure model, 0 = pure bicubic. [lumaOnly]: keep the
 * model's brightness but the source's colour. [colorFix]: pull large-scale colour and
 * brightness back to the source.
 */
data class PostOptions(
    val detail: Float = 1f,
    val lumaOnly: Boolean = false,
    val colorFix: Boolean = false,
    /** Gaussian sigma (input px) applied before inference; used on chained passes. */
    val preBlur: Float = 0f,
)

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

        /** Feed [values] to the model's second input [blob] on every tile. */
        fun setExtraInput(blob: String, values: FloatArray) = nativeSetExtraInput(handle, blob, values)

        /** Model value range: feeds in*mul+add (GFPGAN wants [-1,1]: mul 2, add -1). */
        fun setNormalization(mul: Float, add: Float) = nativeSetNormalization(handle, mul, add)

        /** Pad tiles to a multiple of [align] inside the runner. */
        fun setAlignment(align: Int) = nativeSetAlignment(handle, align)

        /** Match each output tile's per-channel mean/contrast to its input tile. */
        fun setStatMatch(on: Boolean) = nativeSetStatMatch(handle, on)

        internal val rawHandle: Long get() = handle

        fun cancel() = nativeCancel(handle)

        override fun close() {
            if (handle != 0L) nativeDestroy(handle)
            handle = 0L
        }
    }

    /** RetinaFace detector (separate from Sessions: it has three outputs and no tiling). */
    class FaceDetector internal constructor(private var handle: Long) : AutoCloseable {
        internal val raw get() = handle

        /** (count, largest face px, smallest eye distance px) in the oriented image, or null. */
        fun detect(bitmap: Bitmap, exifOrientation: Int): FloatArray? = nativeDetectFaces(handle, bitmap, exifOrientation)

        override fun close() {
            if (handle != 0L) nativeDestroyDetector(handle)
            handle = 0L
        }
    }

    fun openFaceDetector(param: String, bin: String, gpu: Boolean, threads: Int): FaceDetector? =
        nativeCreateDetector(param, bin, gpu, threads).takeIf { it != 0L }?.let { FaceDetector(it) }

    /** Scratch/tear detector (Repair stage). */
    class ScratchDetector internal constructor(private var handle: Long) : AutoCloseable {
        internal val raw get() = handle

        /**
         * Damage in [bitmap] (an already-rotated preview): ALPHA_8 mask of the same size (255 =
         * damage) and a damage score (share flagged by the fine pass), or null on failure.
         */
        fun detect(bitmap: Bitmap): Pair<Bitmap, Float>? {
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
            val f = nativeScratchMask(handle, bitmap, 1, out)
            if (f < 0f) {
                out.recycle()
                return null
            }
            return out to f
        }

        override fun close() {
            if (handle != 0L) nativeDestroyScratchDetector(handle)
            handle = 0L
        }
    }

    /** MI-GAN inpainter (Repair stage). */
    class Inpainter internal constructor(private var handle: Long) : AutoCloseable {
        internal val raw get() = handle

        override fun close() {
            if (handle != 0L) nativeDestroyInpainter(handle)
            handle = 0L
        }
    }

    fun openScratchDetector(param: String, bin: String, gpu: Boolean, threads: Int): ScratchDetector? =
        nativeCreateScratchDetector(param, bin, gpu, threads).takeIf { it != 0L }?.let { ScratchDetector(it) }

    fun openInpainter(param: String, bin: String, gpu: Boolean, threads: Int): Inpainter? =
        nativeCreateInpainter(param, bin, gpu, threads).takeIf { it != 0L }?.let { Inpainter(it) }

    /**
     * Repair stage, run on the input before everything else: holes come from [detector] (if set),
     * then [mask] edits them (over the oriented image, any size): ALPHA_8 opaque = fill;
     * RGBA_8888 opaque red = fill, opaque green = keep.
     */
    class Repair(val detector: ScratchDetector?, val inpainter: Inpainter, val mask: Bitmap?)

    /** Face restoration applied to the final output: detect on the input, restore, blend. */
    class Faces(val detector: FaceDetector, val gfpgan: Session, val strength: Float, val maxFacePx: Float)

    /** One pass of a chain: run [session], then area-downsample by [downsample]. */
    class Pass(
        val session: Session,
        val tile: Int,
        val pad: Int,
        val downsample: Int,
        val post: PostOptions,
    )

    /**
     * Runs [passes] in order on [bitmap] (RGBA_8888, ideally not premultiplied) and streams the
     * final PNG to [outPath], plus a preview (long side <= [previewMax]). Intermediates go to
     * [tmpDir]. Cancel with the first pass's session.
     */
    fun runChain(
        passes: List<Pass>,
        bitmap: Bitmap,
        exifOrientation: Int,
        maxBandBytes: Long,
        tmpDir: String,
        outPath: String,
        previewPath: String?,
        previewMax: Int,
        listener: ProgressListener?,
        faces: Faces? = null,
        repair: Repair? = null,
    ): Int = nativeRunChain(
        passes.map { it.session.rawHandle }.toLongArray(),
        passes.map { it.tile }.toIntArray(),
        passes.map { it.pad }.toIntArray(),
        passes.map { it.downsample }.toIntArray(),
        passes.map { it.post.detail }.toFloatArray(),
        passes.map { it.post.lumaOnly }.toBooleanArray(),
        passes.map { it.post.colorFix }.toBooleanArray(),
        passes.map { it.post.preBlur }.toFloatArray(),
        bitmap, exifOrientation, maxBandBytes, tmpDir, outPath, previewPath, previewMax, listener,
        faces?.detector?.raw ?: 0L, faces?.gfpgan?.rawHandle ?: 0L, faces?.strength ?: 0f, faces?.maxFacePx ?: 0f,
        repair?.detector?.raw ?: 0L, repair?.inpainter?.raw ?: 0L, repair?.mask,
    )

    /** Streams a PNG into a baseline JPEG (alpha over white). Works for any size up to 65535 px. */
    fun pngToJpeg(png: String, jpeg: String, quality: Int): Boolean = nativePngToJpeg(png, jpeg, quality)

    /** A plain bicubic "model" (no network), to finish long chains without invented detail. */
    fun openBicubic(scale: Int): Session = Session(nativeCreateBicubic(scale))

    /** Returns null if the model files can't be loaded. */
    fun open(
        paramPath: String,
        binPath: String,
        inputBlob: String,
        outputBlob: String,
        scale: Int,
        gpu: Boolean,
        threads: Int,
        lowMemory: Boolean = false,
    ): Session? {
        val h = nativeCreate(paramPath, binPath, inputBlob, outputBlob, scale, gpu, threads, lowMemory)
        return if (h == 0L) null else Session(h)
    }

    @JvmStatic private external fun nativeGpuCount(): Int
    @JvmStatic private external fun nativeCreate(
        param: String, bin: String, inBlob: String, outBlob: String, scale: Int, gpu: Boolean, threads: Int, lowMemory: Boolean,
    ): Long
    @JvmStatic private external fun nativeUsingGpu(handle: Long): Boolean
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeProbe(handle: Long): FloatArray?
    @JvmStatic private external fun nativeCreateBicubic(scale: Int): Long
    @JvmStatic private external fun nativePngToJpeg(png: String, jpeg: String, quality: Int): Boolean
    @JvmStatic private external fun nativeSetExtraInput(handle: Long, blob: String, values: FloatArray)
    @JvmStatic private external fun nativeSetAlignment(handle: Long, align: Int)
    @JvmStatic private external fun nativeRunChain(
        handles: LongArray, tiles: IntArray, pads: IntArray, downsamples: IntArray,
        details: FloatArray, lumaOnly: BooleanArray, colorFix: BooleanArray, preBlur: FloatArray,
        bitmap: Bitmap, orientation: Int, maxBandBytes: Long, tmpDir: String,
        outPath: String, previewPath: String?, previewMax: Int, listener: ProgressListener?,
        detector: Long, gfpgan: Long, faceStrength: Float, maxFacePx: Float,
        scratchDetector: Long, inpainter: Long, repairMask: Bitmap?,
    ): Int
    @JvmStatic private external fun nativeCreateDetector(param: String, bin: String, gpu: Boolean, threads: Int): Long
    @JvmStatic private external fun nativeDestroyDetector(handle: Long)
    @JvmStatic private external fun nativeDetectFaces(handle: Long, bitmap: Bitmap, orientation: Int): FloatArray?
    @JvmStatic private external fun nativeSetNormalization(handle: Long, mul: Float, add: Float)
    @JvmStatic private external fun nativeSetStatMatch(handle: Long, on: Boolean)
    @JvmStatic private external fun nativeCreateScratchDetector(param: String, bin: String, gpu: Boolean, threads: Int): Long
    @JvmStatic private external fun nativeDestroyScratchDetector(handle: Long)
    @JvmStatic private external fun nativeScratchMask(handle: Long, bitmap: Bitmap, orientation: Int, out: Bitmap): Float
    @JvmStatic private external fun nativeCreateInpainter(param: String, bin: String, gpu: Boolean, threads: Int): Long
    @JvmStatic private external fun nativeDestroyInpainter(handle: Long)
}
