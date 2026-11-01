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
import app.rescale.core.model.Tuning
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
)

data class EditorState(
    val input: InputState? = null,
    val loadingInput: Boolean = false,
    val mode: Mode = Mode.PHOTO,
    val scale: Int = 4,
    val tuning: Tuning = Tuning.defaultFor(Mode.PHOTO),
    val error: String? = null,
    val resultDismissed: Boolean = false,
    val before: Bitmap? = null,
    val after: Bitmap? = null,
    val message: String? = null,
    val forceCpu: Boolean = false,
) {
    val estimate: OutputEstimate? get() = input?.let { OutputEstimate.of(it.info.width, it.info.height, scale) }
    val tooLarge: Boolean get() = input?.let { it.info.width.toLong() * it.info.height > Limits.MAX_INPUT_PIXELS } ?: false
}

class RescaleViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val backends = BackendSelector(app, BuildConfig.VERSION_CODE.toLong())

    private val _state = MutableStateFlow(EditorState(forceCpu = backends.forceCpu))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val job: StateFlow<JobState> = UpscaleWorker.observe(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, JobState.Idle)

    private var loadedResultFor: File? = null

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
                    InputState(local, info, ImageLoader.decodePreview(ctx, local, 1600))
                }
            }
            result.onSuccess { input ->
                _state.update {
                    it.copy(input = input, loadingInput = false, resultDismissed = true, after = null, before = null)
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
        it.copy(mode = mode, tuning = it.tuning.copy(detail = mode.defaultDetail))
    }
    fun setDetail(v: Float) = _state.update { it.copy(tuning = it.tuning.copy(detail = v.coerceIn(0f, 1f))) }
    fun setColorFix(v: Boolean) = _state.update { it.copy(tuning = it.tuning.copy(colorFix = v)) }
    fun setLumaOnly(v: Boolean) = _state.update { it.copy(tuning = it.tuning.copy(lumaOnly = v)) }
    fun setScale(scale: Int) = _state.update { it.copy(scale = scale) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

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
        UpscaleWorker.enqueue(ctx, input.uri, s.mode, s.scale, s.tuning)
    }

    fun cancel() = UpscaleWorker.cancel(ctx)

    /** Leave the result screen but keep the same input, e.g. to try 2× instead. */
    fun backToEditor() = _state.update { it.copy(resultDismissed = true) }

    fun newImage() {
        UpscaleWorker.clear(ctx)
        loadedResultFor = null
        _state.update { EditorState(forceCpu = it.forceCpu) }
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

    fun save(done: JobState.Done) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { saveToGallery(done) }.fold(
                    onSuccess = { "Saved to Pictures/Rescale" },
                    onFailure = { "Couldn't save: ${it.message}" },
                )
            }
            _state.update { it.copy(message = msg) }
        }
    }

    private fun fileName(done: JobState.Done): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "Rescale_${stamp}_${done.width}x${done.height}.png"
    }

    private fun saveToGallery(done: JobState.Done) {
        val name = fileName(done)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Rescale")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the file")
            try {
                resolver.openOutputStream(uri)?.use { out -> done.output.inputStream().use { it.copyTo(out) } }
                    ?: error("Can't write to gallery")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Rescale")
            dir.mkdirs()
            val target = File(dir, name)
            done.output.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(target.path), arrayOf("image/png"), null)
        }
    }

    fun shareIntent(done: JobState.Done): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", done.output)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share upscaled image") }
    }
}
