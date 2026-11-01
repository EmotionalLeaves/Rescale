package app.rescale.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rescale.RescaleViewModel
import app.rescale.worker.JobState

private enum class Screen { Home, Editor, Running, Result, Models, Batch }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescaleApp(vm: RescaleViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showBatch by rememberSaveable { mutableStateOf(false) }
    var batchPick by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val batchItems by vm.batch.collectAsStateWithLifecycle()
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) batchPick = uris
    }
    val pickBatch = { multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val packs by vm.packs.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPack(uri)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pick(uri)
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // Notification permission is asked for the first time a job starts, not at launch.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val start = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    BackHandler(enabled = showModels || showBatch) { showModels = false; showBatch = false }

    val screen = when {
        showModels -> Screen.Models
        showBatch -> Screen.Batch
        job is JobState.Running -> Screen.Running
        job is JobState.Done && !state.resultDismissed -> Screen.Result
        state.input != null -> Screen.Editor
        else -> Screen.Home
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Models -> "Model packs"
                            Screen.Batch -> "Batch"
                            else -> "Rescale"
                        },
                    )
                },
                navigationIcon = {
                    if (screen == Screen.Models || screen == Screen.Batch) {
                        IconButton(onClick = { showModels = false; showBatch = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close model packs")
                        }
                    } else if (screen == Screen.Editor) {
                        IconButton(onClick = vm::newImage) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to start")
                        }
                    } else if (screen == Screen.Result) {
                        IconButton(onClick = vm::backToEditor) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Use CPU only") },
                            trailingIcon = { Checkbox(checked = state.forceCpu, onCheckedChange = null) },
                            onClick = { vm.setForceCpu(!state.forceCpu) },
                        )
                        DropdownMenuItem(
                            text = { Text("Gentle mode (cooler, slower)") },
                            trailingIcon = { Checkbox(checked = state.gentle, onCheckedChange = null) },
                            onClick = { vm.setGentle(!state.gentle) },
                        )
                        DropdownMenuItem(text = { Text("Batch queue") }, onClick = {
                            menuOpen = false; showBatch = true
                        })
                        DropdownMenuItem(text = { Text("Model packs") }, onClick = {
                            menuOpen = false; showModels = true
                        })
                        DropdownMenuItem(text = { Text("Model licenses") }, onClick = {
                            menuOpen = false; dialog = "licenses"
                        })
                        DropdownMenuItem(text = { Text("About & privacy") }, onClick = {
                            menuOpen = false; dialog = "about"
                        })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    loading = state.loadingInput,
                    error = state.error ?: (job as? JobState.Failed)?.message,
                    onPick = pickImage,
                    onBatch = pickBatch,
                )
                Screen.Batch -> BatchScreen(
                    items = batchItems,
                    onAdd = pickBatch,
                    onCancel = vm::cancelBatch,
                    onClear = vm::clearBatch,
                )
                Screen.Editor -> EditorScreen(
                    state = state,
                    lastJob = job,
                    onPick = pickImage,
                    onMode = vm::setMode,
                    onAuto = vm::useAuto,
                    onScale = vm::setScale,
                    onDetail = vm::setDetail,
                    onColorFix = vm::setColorFix,
                    onLumaOnly = vm::setLumaOnly,
                    onToggleStage = vm::toggleStage,
                    onPro = vm::setPro,
                    onOpenModels = { showModels = true },
                    onFinishBicubic = vm::setFinishWithBicubic,
                    batchActive = batchItems.any {
                        it.state == app.rescale.worker.BatchItem.State.QUEUED || it.state == app.rescale.worker.BatchItem.State.RUNNING
                    },
                    onStart = start,
                    onDismissError = vm::dismissError,
                )
                Screen.Running -> RunningScreen(job as JobState.Running, state.input?.thumbnail, vm::cancel)
                Screen.Models -> ModelsScreen(
                    packs = packs,
                    published = vm.packsPublished,
                    onDownload = vm::downloadPack,
                    onCancel = vm::cancelDownload,
                    onDelete = vm::deletePack,
                    onImport = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                )
                Screen.Result -> ResultScreen(
                    done = job as JobState.Done,
                    before = state.before,
                    after = state.after,
                    exportOptions = vm.exportOptions,
                    exporting = state.exporting,
                    onSave = { vm.save(job as JobState.Done, it) },
                    onShare = { vm.share(job as JobState.Done, it) { intent -> context.startActivity(intent) } },
                    onNew = vm::newImage,
                )
            }
        }
    }

    if (batchPick.isNotEmpty()) {
        BatchSetupSheet(
            count = batchPick.size,
            initialExport = vm.exportOptions,
            onDismiss = { batchPick = emptyList() },
            onStart = { scale, export, charging ->
                vm.startBatch(batchPick, scale, export, charging)
                batchPick = emptyList()
                showBatch = true
            },
        )
    }

    when (dialog) {
        "licenses" -> InfoDialog("Model licenses", LICENSES_TEXT) { dialog = null }
        "about" -> InfoDialog("About Rescale", ABOUT_TEXT) { dialog = null }
    }
}

@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(title) },
        text = { Text(body) },
    )
}

private const val LICENSES_TEXT =
    "Rescale is GPL-3.0. Bundled models:\n\n" +
        "• realesr-general-x4v3 (photos): BSD-3-Clause, © Xintao Wang (Real-ESRGAN)\n" +
        "• realesr-animevideov3 x2/x4 (anime & art): BSD-3-Clause, © Xintao Wang (Real-ESRGAN)\n\n" +
        "Runtime: ncnn by Tencent (BSD-3-Clause)."

private const val ABOUT_TEXT =
    "Rescale upscales images entirely on your phone. It has no internet permission, no accounts, " +
        "no ads, no analytics and no watermarks.\n\n" +
        "Upscaling invents plausible detail. It doesn't recover what was never captured, so don't " +
        "treat results as evidence (faces and text especially).\n\n" +
        "Source: github.com/EmotionalLeaves/Rescale"
