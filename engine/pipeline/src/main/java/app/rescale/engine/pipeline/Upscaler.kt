package app.rescale.engine.pipeline

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.rescale.core.model.BundledModels
import app.rescale.core.model.Generative
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        onPhase: (phase: Int) -> Unit = {},
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
        if (plan.passes.any { it.model == PackModels.ADCSR } && !Generative.fits(info.width, info.height)) {
            return UpscaleOutcome.Failed(
                "Too big for Generative detail (${Generative.tiles(info.width, info.height)} tiles, limit ${Generative.MAX_TILES}). " +
                    "Crop it or turn Generative detail off.",
            )
        }
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
        if (Stage.REPAIR in req.settings.stages) intermediates += info.width.toLong() * info.height * 4
        val needBytes = outW * outH * 3 / 2 + intermediates + (64L shl 20)
        if (outDir.apply { mkdirs() }.usableSpace < needBytes) {
            return UpscaleOutcome.Failed("Not enough free storage (need about ${needBytes shr 20} MB)")
        }

        // Big models and the repair nets follow the GPU verdict of the self-tested models: make sure
        // there is one (the built-in model's test is quick) before the first such job.
        if ((plan.passes.any { it.model.lowMemory } || Stage.REPAIR in req.settings.stages) && !backends.hasVerdict()) {
            runCatching {
                val (p, b) = models.resolve(BundledModels.GENERAL_X4V3)
                backends.open(BundledModels.GENERAL_X4V3, p, b).close()
            }
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

        // Repair stage: damage detector (optional) + MI-GAN, run on the input before everything else.
        var repair: NativeEngine.Repair? = null
        val repairOn = Stage.REPAIR in req.settings.stages && Packs.REPAIR in packs.installed()
        if (repairOn) {
            var det: NativeEngine.ScratchDetector? = null
            try {
                val gpu = backends.gpuTrusted()
                val threads = BackendSelector.cpuThreads()
                if (req.settings.autoDamage) {
                    val (dp, db) = models.resolve(PackModels.SCRATCH)
                    det = NativeEngine.openScratchDetector(dp.path, db.path, gpu, threads)
                        ?: error("Couldn't load the damage detector")
                }
                val (ip, ib) = models.resolve(PackModels.MIGAN)
                val inp = NativeEngine.openInpainter(ip.path, ib.path, gpu, threads) ?: error("Couldn't load the inpainter")
                val mask = req.settings.repairMask?.let { path ->
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                }
                repair = NativeEngine.Repair(det, inp, mask)
            } catch (e: Exception) {
                det?.close()
                detector?.close()
                sessions.forEach { it.close() }
                return UpscaleOutcome.Failed(e.message ?: "Couldn't load repair models")
            }
        }
        fun closeRepair() {
            repair?.detector?.close()
            repair?.inpainter?.close()
            repair?.mask?.recycle()
        }

        val bitmap = try {
            ImageLoader.decodeForEngine(context, req.input)
        } catch (e: Throwable) {
            sessions.forEach { it.close() }
            detector?.close()
            closeRepair()
            return UpscaleOutcome.Failed(if (e is OutOfMemoryError) "Not enough memory to open this image" else e.message ?: "Decode failed")
        }

        val output = File(outDir, "output.png")
        val preview = File(outDir, "preview.png")
        // Per-job dir: if Android kills the process mid-job, WorkManager re-runs it with the same
        // outDir and the native chain resumes after the last finished pass.
        val tmp = File(outDir, "passes").apply { mkdirs() }
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
                listener = object : ProgressListener {
                    override fun onProgress(done: Int, total: Int) = onProgress(done, total)
                    override fun onPhase(phase: Int) = onPhase(phase)
                },
                faces = faces,
                repair = repair,
            )
        } finally {
            sessions.forEach { it.close() }
            detector?.close()
            closeRepair()
            bitmap.recycle()
            tmp.deleteRecursively()
        }

        return when (status) {
            NativeStatus.OK -> UpscaleOutcome.Success(
                output, preview, outW.toInt(), outH.toInt(), backend, SystemClock.elapsedRealtime() - started,
                (listOfNotNull(repair?.let { "MI-GAN repair" }) + plan.passes.map { it.model.displayName } +
                    listOfNotNull(faces?.let { "GFPGAN faces" })).joinToString(" → "),
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
        /** Values passed to onPhase (same as the native bridge's Phase). */
        const val PHASE_UPSCALE = 0
        const val PHASE_REPAIR = 1
        const val CPU_TILE = 128

        /** Faces bigger than this (input px) are left alone: they already have real detail. */
        const val MAX_FACE_PX = 400f
        const val GENTLE_TILE = 96
        const val PREVIEW_MAX = 2048
    }
}
