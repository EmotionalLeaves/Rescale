package app.rescale.engine.pipeline

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.rescale.core.model.JobSettings
import app.rescale.core.model.Limits
import app.rescale.core.model.PackModels
import app.rescale.core.model.Packs
import app.rescale.core.model.Planner
import app.rescale.core.model.Stage
import app.rescale.engine.nativebridge.NativeEngine
import app.rescale.engine.nativebridge.NativeStatus
import app.rescale.engine.nativebridge.PostOptions
import app.rescale.engine.nativebridge.ProgressListener
import java.io.File

data class UpscaleRequest(val input: Uri, val settings: JobSettings)

sealed interface UpscaleOutcome {
    data class Success(
        val output: File,
        val preview: File,
        val width: Int,
        val height: Int,
        val backend: BackendKind,
        val millis: Long,
        /** Human summary of what ran, e.g. "De-JPEG → Real-ESRGAN general x4v3". */
        val pipeline: String,
    ) : UpscaleOutcome

    data object Cancelled : UpscaleOutcome
    data class Failed(val message: String) : UpscaleOutcome
}

/** Interface the worker uses to stop a running job from another coroutine. */
fun interface Cancellable {
    fun cancel()
}

class Upscaler(private val context: Context, appVersion: Long) {
    val packs = PackStore(context)
    private val models = ModelStore(context, packs)
    val backends = BackendSelector(context, appVersion)

    /**
     * Blocking. Runs on the caller's thread; [onStarted] hands back a handle that can cancel
     * between tiles.
     */
    fun run(
        req: UpscaleRequest,
        outDir: File,
        onStarted: (Cancellable) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
    ): UpscaleOutcome {
        val info = try {
            ImageLoader.info(context, req.input)
        } catch (e: Exception) {
            return UpscaleOutcome.Failed(e.message ?: "Can't read this image")
        }
        if (info.width.toLong() * info.height > Limits.MAX_INPUT_PIXELS) {
            return UpscaleOutcome.Failed("Input is larger than ${Limits.MAX_INPUT_PIXELS / 1_000_000} MP")
        }

        val plan = Planner.plan(req.settings, packs.installed())
        val scale = plan.outputScale
        val outW = info.width.toLong() * scale
        val outH = info.height.toLong() * scale
        // Output PNG plus raw RGBA intermediates (only the latest two exist at once, but be safe).
        var w = info.width.toLong()
        var h = info.height.toLong()
        var intermediates = 0L
        plan.passes.dropLast(1).forEach { p ->
            w = w * p.model.scale / p.downsample
            h = h * p.model.scale / p.downsample
            intermediates += w * h * 4
        }
        val needBytes = outW * outH * 3 / 2 + intermediates + (64L shl 20)
        if (outDir.apply { mkdirs() }.usableSpace < needBytes) {
            return UpscaleOutcome.Failed("Not enough free storage (need about ${needBytes shr 20} MB)")
        }

        val sessions = mutableListOf<NativeEngine.Session>()
        val passes = try {
            plan.passes.map { p ->
                val s = if (p.model.builtin) {
                    NativeEngine.openBicubic(p.model.scale)
                } else {
                    val (param, bin) = models.resolve(p.model)
                    backends.open(p.model, param, bin, p.extra)
                }.also { sessions += it }
                NativeEngine.Pass(
                    session = s,
                    tile = when {
                        backends.gentle -> minOf(p.model.tile, GENTLE_TILE)
                        s.usingGpu -> p.model.tile
                        else -> minOf(p.model.tile, CPU_TILE)
                    },
                    pad = p.model.pad,
                    downsample = p.downsample,
                    post = PostOptions(p.tuning.detail, p.tuning.lumaOnly, p.tuning.colorFix, p.preBlur),
                )
            }
        } catch (e: Exception) {
            sessions.forEach { it.close() }
            return UpscaleOutcome.Failed(e.message ?: "Couldn't load the model")
        }
        // Faces stage: detector + GFPGAN, blended into the final output.
        var faces: NativeEngine.Faces? = null
        var detector: NativeEngine.FaceDetector? = null
        if (Stage.FACES in req.settings.stages && Packs.FACES in packs.installed()) {
            try {
                val (dp, db) = models.resolve(PackModels.RETINAFACE)
                val (gp, gb) = models.resolve(PackModels.GFPGAN)
                detector = NativeEngine.openFaceDetector(dp.path, db.path, backends.gpuTrusted(), BackendSelector.cpuThreads())
                    ?: error("Couldn't load the face detector")
                val gfp = backends.openTrusted(PackModels.GFPGAN, gp, gb).also { sessions += it }
                gfp.setNormalization(2f, -1f)
                faces = NativeEngine.Faces(detector, gfp, req.settings.faceStrength, MAX_FACE_PX)
            } catch (e: Exception) {
                detector?.close()
                sessions.forEach { it.close() }
                return UpscaleOutcome.Failed(e.message ?: "Couldn't load face models")
            }
        }

        val bitmap = try {
            ImageLoader.decodeForEngine(context, req.input)
        } catch (e: Throwable) {
            sessions.forEach { it.close() }
            detector?.close()
            return UpscaleOutcome.Failed(if (e is OutOfMemoryError) "Not enough memory to open this image" else e.message ?: "Decode failed")
        }

        val output = File(outDir, "output.png")
        val preview = File(outDir, "preview.png")
        val tmp = File(context.cacheDir, "passes").apply { deleteRecursively(); mkdirs() }
        val started = SystemClock.elapsedRealtime()
        val backend = if (plan.passes.zip(passes).all { (p, s) -> p.model.builtin || s.session.usingGpu }) {
            BackendKind.VULKAN
        } else {
            BackendKind.CPU
        }
        val status = try {
            onStarted { passes.first().session.cancel() }
            NativeEngine.runChain(
                passes = passes,
                bitmap = bitmap,
                exifOrientation = info.exifOrientation,
                maxBandBytes = bandBudget(),
                tmpDir = tmp.path,
                outPath = output.path,
                previewPath = preview.path,
                previewMax = PREVIEW_MAX,
                listener = ProgressListener { d, t -> onProgress(d, t) },
                faces = faces,
            )
        } finally {
            sessions.forEach { it.close() }
            detector?.close()
            bitmap.recycle()
            tmp.deleteRecursively()
        }

        return when (status) {
            NativeStatus.OK -> UpscaleOutcome.Success(
                output, preview, outW.toInt(), outH.toInt(), backend, SystemClock.elapsedRealtime() - started,
                (plan.passes.map { it.model.displayName } + listOfNotNull(faces?.let { "GFPGAN faces" })).joinToString(" → "),
            )
            NativeStatus.CANCELLED -> {
                output.delete(); preview.delete()
                UpscaleOutcome.Cancelled
            }
            NativeStatus.WRITE_ERROR -> UpscaleOutcome.Failed("Couldn't write the output (storage full?)")
            NativeStatus.BAD_INPUT -> UpscaleOutcome.Failed("Unsupported image format")
            else -> {
                // A model error on GPU usually means a driver problem: don't trust it again.
                if (backend == BackendKind.VULKAN) backends.forceCpu = true
                UpscaleOutcome.Failed("The model failed on this device. Switched to CPU; please retry.")
            }
        }
    }

    /** Native memory for the blend band: an eighth of RAM, capped at 384 MB. */
    private fun bandBudget(): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return (mi.totalMem / 8).coerceIn(48L shl 20, 384L shl 20)
    }

    companion object {
        const val CPU_TILE = 128

        /** Faces bigger than this (input px) are left alone: they already have real detail. */
        const val MAX_FACE_PX = 400f
        const val GENTLE_TILE = 96
        const val PREVIEW_MAX = 2048
    }
}
