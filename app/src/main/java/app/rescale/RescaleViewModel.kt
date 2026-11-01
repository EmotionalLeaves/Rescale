package app.rescale

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rescale.core.model.Limits
import app.rescale.core.model.Mode
import app.rescale.core.model.OutputEstimate
import app.rescale.core.model.Analysis
import app.rescale.core.model.AutoPlan
import app.rescale.core.model.AutoPlanner
import app.rescale.core.model.JobSettings
import app.rescale.core.model.Stage
import app.rescale.core.model.Tuning
import app.rescale.engine.pipeline.ExportFormat
import app.rescale.engine.pipeline.ExportOptions
import app.rescale.engine.pipeline.Exported
import app.rescale.engine.pipeline.Exporter
import app.rescale.engine.pipeline.Gallery
import app.rescale.engine.pipeline.FaceProbe
import app.rescale.engine.pipeline.DamageProbe
import app.rescale.engine.pipeline.DamagePreview
import app.rescale.engine.pipeline.ImageAnalyzer
import app.rescale.engine.pipeline.PackStore
import app.rescale.core.model.Packs
import app.rescale.worker.PackDownload
import app.rescale.worker.PackDownloadWorker
import app.rescale.worker.BatchItem
import app.rescale.worker.BatchWorker
import kotlinx.coroutines.flow.combine
import app.rescale.engine.pipeline.BackendSelector
import app.rescale.engine.pipeline.ImageInfo
import app.rescale.engine.pipeline.ImageLoader
import app.rescale.worker.JobState
import app.rescale.worker.UpscaleWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InputState(
    val uri: Uri,
    val info: ImageInfo,
    val thumbnail: Bitmap?,
    val analysis: Analysis? = null,
    val displayName: String? = null,
    /** Damage detected on [thumbnail] (Repair pack installed). */
    val damage: DamagePreview? = null,
)

/** One brush stroke in the mask editor, in image coordinates normalised to 0..1. */
data class MaskStroke(val points: List<Pair<Float, Float>>, val radius: Float, val erase: Boolean)

data class EditorState(
    val input: InputState? = null,
    val loadingInput: Boolean = false,
    val mode: Mode = Mode.PHOTO,
    val scale: Int = 4,
    val tuning: Tuning = Tuning.defaultFor(Mode.PHOTO),
    val stages: List<Stage> = emptyList(),
    /** True while the settings are the ones Auto picked. */
    val auto: Boolean = true,
    val pro: Boolean = false,
    val jpegQuality: Int = 50,
    val strongDenoise: Boolean = false,
    val finishWithBicubic: Boolean = false,
    val faceStrength: Float = 0.6f,
    val installedPacks: Set<String> = emptySet(),
    val error: String? = null,
    val resultDismissed: Boolean = false,
    val before: Bitmap? = null,
    val after: Bitmap? = null,
    val message: String? = null,
    val forceCpu: Boolean = false,
    val gentle: Boolean = false,
    val exporting: Boolean = false,
    val generative: Boolean = false,
    /** The generative pack is installed and this phone has the RAM for it. */
    val generativeCapable: Boolean = false,
    val autoDamage: Boolean = true,
    val repairStrokes: List<MaskStroke> = emptyList(),
    val editingMask: Boolean = false,
) {
    val estimate: OutputEstimate? get() = input?.let { OutputEstimate.of(it.info.width, it.info.height, scale) }
    /** What Auto would pick now (re-evaluated when packs are installed). */
    val autoPlan: AutoPlan?
        get() = input?.analysis?.let { a ->
            AutoPlanner.plan(a, Stage.entries.filter { it.packId in installedPacks }.toSet(), generativeCapable)
        }
    val settings: JobSettings
        get() = JobSettings(
            mode, scale, tuning, stages, pro, jpegQuality, strongDenoise, finishWithBicubic, faceStrength,
            generative = generative && generativeCapable && mode == Mode.PHOTO,
            autoDamage = autoDamage,
        )
    val generativeAvailable: Boolean get() = generativeCapable && mode == Mode.PHOTO
    val proAvailable: Boolean
        get() = (if (mode == Mode.PHOTO) Packs.PHOTO_PRO else Packs.ANIME_PRO) in installedPacks
    val tooLarge: Boolean get() = input?.let { it.info.width.toLong() * it.info.height > Limits.MAX_INPUT_PIXELS } ?: false
}

private fun EditorState.applying(s: JobSettings) = copy(
    mode = s.mode, scale = s.scale, tuning = s.tuning,
    // Repair is never picked by Auto, but a repair the user set up survives re-applying Auto.
    stages = (s.stages + stages.filter { it == Stage.REPAIR }).distinct().sortedBy { it.ordinal }, auto = true,
    jpegQuality = s.jpegQuality, strongDenoise = s.strongDenoise, generative = s.generative,
)

data class PackUi(val info: Packs.Info, val installed: Boolean, val sizeBytes: Long, val download: PackDownload)

class RescaleViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val backends = BackendSelector(app, BuildConfig.VERSION_CODE.toLong())

    private val packStore = PackStore(app)

    /** Total RAM in whole GB (rounded up: an "8 GB" phone reports a little less). */
    val deviceRamGb: Int = run {
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        ((mi.totalMem + (512L shl 20)) shr 30).toInt()
    }

    private fun generativeCapable(installed: Set<String>) =
        Packs.GENERATIVE in installed && deviceRamGb >= (Packs.all.first { it.id == Packs.GENERATIVE }.minRamGb)

    private val _state = MutableStateFlow(
        packStore.installed().let { installed ->
            EditorState(
                forceCpu = backends.forceCpu, gentle = backends.gentle, installedPacks = installed,
                generativeCapable = generativeCapable(installed),
            )
        },
    )
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val job: StateFlow<JobState> = UpscaleWorker.observe(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, JobState.Idle)

    private var loadedResultFor: File? = null

    /** Pack rows for the Models screen; refreshes installed state when downloads finish. */
    val packs: StateFlow<List<PackUi>> = combine(Packs.all.map { info ->
        PackDownloadWorker.observe(app, info.id)
    }) { downloads ->
        refreshInstalled()
        Packs.all.mapIndexed { i, info ->
            PackUi(info, packStore.isInstalled(info.id),
                packStore.manifest[info.id]?.size ?: 0L, downloads[i])
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val packsPublished: Boolean get() = packStore.manifest.isNotEmpty()

    private fun refreshInstalled() {
        val installed = packStore.installed()
        _state.update { s ->
            // Drop stages/pro whose pack disappeared.
            val capable = generativeCapable(installed)
            val next = s.copy(
                installedPacks = installed, stages = s.stages.filter { it.packId in installed },
                generativeCapable = capable, generative = s.generative && capable,
            )
            // New packs can change what Auto would do.
            val plan = next.autoPlan
            if (next.auto && plan != null && installed != s.installedPacks) next.applying(plan.settings) else next
        }
    }

    fun downloadPack(id: String) = PackDownloadWorker.start(ctx, id)
    fun cancelDownload(id: String) = PackDownloadWorker.cancel(ctx, id)
    fun deletePack(id: String) {
        packStore.delete(id)
        refreshInstalled()
    }

    fun importPack(uri: Uri) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { packStore.installZip(it) } ?: error("Can't open file")
                }.fold(
                    onSuccess = { id -> "Installed ${Packs.all.find { it.id == id }?.name ?: id}" },
                    onFailure = { it.message ?: "Import failed" },
                )
            }
            refreshInstalled()
            _state.update { it.copy(message = msg) }
        }
    }

    val batch: StateFlow<List<BatchItem>> = BatchWorker.observe(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Copies the picked images into app storage and queues them (Auto settings per image). */
    fun startBatch(uris: List<Uri>, scale: Int, export: ExportOptions, onlyWhileCharging: Boolean) {
        exportOptions = export
        viewModelScope.launch {
            val inputs = withContext(Dispatchers.IO) {
                val dir = File(ctx.filesDir, "batch-in").apply { mkdirs() }
                uris.mapIndexedNotNull { i, uri ->
                    runCatching {
                        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "image-${i + 1}"
                        val copy = File(dir, "${System.nanoTime()}-$i")
                        ctx.contentResolver.openInputStream(uri)?.use { s -> copy.outputStream().use { s.copyTo(it) } }
                            ?: error("unreadable")
                        Uri.fromFile(copy) to name
                    }.getOrNull()
                }
            }
            BatchWorker.enqueue(ctx, inputs, scale, export, onlyWhileCharging)
            val skipped = uris.size - inputs.size
            _state.update {
                it.copy(message = "Queued ${inputs.size} image(s)" + if (skipped > 0) ", skipped $skipped unreadable" else "")
            }
        }
    }

    fun cancelBatch() = BatchWorker.cancelAll(ctx)
    fun clearBatch() = BatchWorker.clearFinished(ctx)

    fun setPro(v: Boolean) = _state.update { it.copy(pro = v, auto = false) }
    fun setFaceStrength(v: Float) = _state.update { it.copy(faceStrength = v.coerceIn(0f, 1f)) }
    fun setFinishWithBicubic(v: Boolean) = _state.update { it.copy(finishWithBicubic = v) }

    init {
        viewModelScope.launch {
            job.collect { j -> if (j is JobState.Done) loadResult(j) }
        }
    }

    /** Copies the picked/shared image into app storage so the background job can always read it. */
    fun pick(uri: Uri) {
        _state.update { it.copy(loadingInput = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.filesDir, "input").apply { deleteRecursively(); mkdirs() }
                    val copy = File(dir, "input-${System.currentTimeMillis()}")
                    ctx.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { i.copyTo(it) } }
                        ?: error("Can't open this image")
                    val local = Uri.fromFile(copy)
                    val info = ImageLoader.info(ctx, local)
                    val faces = FaceProbe.smallFaces(ctx, local, info, packStore)
                    val thumb = ImageLoader.decodePreview(ctx, local, 1600)
                    val damage = DamageProbe.detect(ctx, thumb, packStore)
                    val analysis = ImageAnalyzer.analyze(ctx, local, info)
                        ?.copy(smallFaces = faces, damage = damage?.coverage ?: 0f)
                    val name = runCatching {
                        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull()
                    InputState(local, info, thumb, analysis, name, damage)
                }
            }
            result.onSuccess { input ->
                _state.update {
                    val base = it.copy(
                        input = input, loadingInput = false, resultDismissed = true, after = null, before = null,
                        stages = it.stages - Stage.REPAIR, repairStrokes = emptyList(), autoDamage = true, editingMask = false,
                    )
                    val plan = base.autoPlan
                    if (it.auto && plan != null) base.applying(plan.settings) else base
                }
                UpscaleWorker.clear(ctx)
                loadedResultFor = null
            }.onFailure { e ->
                _state.update { it.copy(loadingInput = false, error = e.message ?: "Can't open this image") }
            }
        }
    }

    fun setMode(mode: Mode) = _state.update {
        // Switching mode resets Detail to that mode's default; the toggles stay as set.
        it.copy(mode = mode, tuning = it.tuning.copy(detail = mode.defaultDetail), auto = false)
    }

    /** Re-applies what Auto picked for the current image. */
    fun useAuto() = _state.update { s ->
        val plan = s.autoPlan ?: return@update s.copy(auto = true)
        s.applying(plan.settings)
    }

    fun toggleStage(stage: Stage) {
        // Turning Repair on opens the mask editor so the user sees what will be filled.
        if (stage == Stage.REPAIR && stage !in _state.value.stages) {
            _state.update { it.copy(editingMask = true) }
            return
        }
        _state.update {
            it.copy(stages = if (stage in it.stages) it.stages - stage else (it.stages + stage).sortedBy { s -> s.ordinal })
        }
    }

    fun setGenerative(v: Boolean) = _state.update { it.copy(generative = v, auto = false) }

    fun editMask() = _state.update { it.copy(editingMask = true) }
    fun cancelMask() = _state.update { it.copy(editingMask = false) }

    /** Saves the editor's result and switches Repair on. */
    fun saveMask(strokes: List<MaskStroke>, autoDetect: Boolean) = _state.update {
        it.copy(
            repairStrokes = strokes, autoDamage = autoDetect, editingMask = false,
            stages = (it.stages + Stage.REPAIR).distinct().sortedBy { s -> s.ordinal },
        )
    }

    /** Renders the painted strokes over the oriented image (red = fill, green = keep), or null. */
    private fun renderMask(s: EditorState): String? {
        if (s.repairStrokes.isEmpty() || Stage.REPAIR !in s.stages) return null
        val info = s.input?.info ?: return null
        val k = minOf(1f, 2048f / maxOf(info.width, info.height))
        val w = maxOf(1, (info.width * k).toInt())
        val h = maxOf(1, (info.height * k).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        for (st in s.repairStrokes) {
            val paint = android.graphics.Paint().apply {
                isAntiAlias = false
                color = if (st.erase) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
                strokeWidth = 2 * st.radius * w
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                style = android.graphics.Paint.Style.STROKE
            }
            if (st.points.size == 1) {
                paint.style = android.graphics.Paint.Style.FILL
                c.drawCircle(st.points[0].first * w, st.points[0].second * h, st.radius * w, paint)
            } else {
                val path = android.graphics.Path()
                st.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
                c.drawPath(path, paint)
            }
        }
        val f = File(ctx.filesDir, "input/repair-mask.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.path
    }


    fun setDetail(v: Float) = _state.update { it.copy(tuning = it.tuning.copy(detail = v.coerceIn(0f, 1f))) }
    fun setColorFix(v: Boolean) = _state.update { it.copy(tuning = it.tuning.copy(colorFix = v)) }
    fun setLumaOnly(v: Boolean) = _state.update { it.copy(tuning = it.tuning.copy(lumaOnly = v)) }
    fun setScale(scale: Int) = _state.update { it.copy(scale = scale) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setGentle(v: Boolean) {
        backends.gentle = v
        _state.update { it.copy(gentle = v) }
    }

    fun setForceCpu(v: Boolean) {
        backends.forceCpu = v
        if (!v) backends.resetSelfTests()
        _state.update { it.copy(forceCpu = v) }
    }

    fun start() {
        val s = _state.value
        val input = s.input ?: return
        if (s.tooLarge) return
        _state.update { it.copy(resultDismissed = false, after = null, error = null) }
        loadedResultFor = null
        viewModelScope.launch {
            val mask = withContext(Dispatchers.IO) { runCatching { renderMask(s) }.getOrNull() }
            UpscaleWorker.enqueue(ctx, input.uri, s.settings.copy(repairMask = mask))
        }
    }

    fun cancel() = UpscaleWorker.cancel(ctx)

    /** Leave the result screen but keep the same input, e.g. to try 2× instead. */
    fun backToEditor() = _state.update { it.copy(resultDismissed = true) }

    fun newImage() {
        UpscaleWorker.clear(ctx)
        loadedResultFor = null
        _state.update {
            EditorState(
                forceCpu = it.forceCpu, gentle = it.gentle, installedPacks = it.installedPacks,
                generativeCapable = it.generativeCapable,
            )
        }
    }

    private suspend fun loadResult(done: JobState.Done) {
        if (loadedResultFor == done.output) return
        loadedResultFor = done.output
        val (after, before) = withContext(Dispatchers.IO) {
            val a = BitmapFactory.decodeFile(done.preview.path)
            val b = done.input?.let { ImageLoader.decodePreview(ctx, it, 2048) }
            a to b
        }
        _state.update { s ->
            // If the app was restarted, rebuild the input from the job so "back" works.
            val input = s.input ?: done.input?.let { uri ->
                runCatching { InputState(uri, ImageLoader.info(ctx, uri), before) }.getOrNull()
            }
            s.copy(after = after, before = before, input = input)
        }
    }

    private val exportPrefs = app.getSharedPreferences("export", android.content.Context.MODE_PRIVATE)

    var exportOptions: ExportOptions
        get() = ExportOptions(
            format = ExportFormat.entries.find { it.name == exportPrefs.getString("format", null) } ?: ExportFormat.PNG,
            quality = exportPrefs.getInt("quality", 92),
            webpLossless = exportPrefs.getBoolean("lossless", false),
            keepExif = exportPrefs.getBoolean("exif", true),
        )
        set(o) = exportPrefs.edit().putString("format", o.format.name).putInt("quality", o.quality)
            .putBoolean("lossless", o.webpLossless).putBoolean("exif", o.keepExif).apply()

    private fun baseName(done: JobState.Done): String {
        val stem = Gallery.stem(state.value.input?.displayName)
            ?: "Rescale_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${stem}_${done.width}x${done.height}"
    }

    private fun exportFile(done: JobState.Done, opts: ExportOptions): Exported {
        exportOptions = opts
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        return Exporter.export(ctx, done.output, done.input, opts, dir, baseName(done))
    }

    fun save(done: JobState.Done, opts: ExportOptions) {
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val e = exportFile(done, opts)
                    saveToGallery(e)
                    e.note ?: "Saved to Pictures/Rescale as ${e.format.label}"
                }.getOrElse { "Couldn't save: ${it.message}" }
            }
            _state.update { it.copy(message = msg, exporting = false) }
        }
    }

    /** Exports with [opts] and hands back a share intent (or null with a message on failure). */
    fun share(done: JobState.Done, opts: ExportOptions, launch: (Intent) -> Unit) {
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { exportFile(done, opts) } }
            _state.update { it.copy(exporting = false, message = result.exceptionOrNull()?.let { "Couldn't export: ${it.message}" }) }
            result.getOrNull()?.let { e ->
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", e.file)
                launch(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = e.format.mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share upscaled image",
                    ),
                )
            }
        }
    }

    private fun saveToGallery(e: Exported) = Gallery.save(ctx, e)
}
