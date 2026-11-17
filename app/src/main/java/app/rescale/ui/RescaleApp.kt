package app.rescale.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rescale.RescaleViewModel
import app.rescale.worker.BatchItem
import app.rescale.worker.JobState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescaleApp(vm: RescaleViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val batchItems by vm.batch.collectAsStateWithLifecycle()
    val pendingBatch by vm.pendingBatch.collectAsStateWithLifecycle()
    val packs by vm.packs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf<Page?>(null) }

    // One picker for the front door: one image opens the editor, several set up a batch.
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        when {
            uris.size == 1 -> vm.pick(uris[0])
            uris.size > 1 -> vm.offerBatch(uris)
        }
    }
    val chooseImages = { choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val changeOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pick(uri)
    }
    val addToBatch = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.offerBatch(uris)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPack(uri)
    }

    // Notification permission is asked for the first time a job starts, not at launch.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val start = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val screen = screenFor(
        page = page,
        running = job is JobState.Running,
        done = job is JobState.Done,
        resultDismissed = state.resultDismissed,
        editingMask = state.editingMask,
        hasInput = state.input != null,
        hasThumbnail = state.input?.thumbnail != null,
    )
    val batchActive = batchItems.any { it.state == BatchItem.State.QUEUED || it.state == BatchItem.State.RUNNING }

    // System back does the same as the arrow in the top bar.
    val back: (() -> Unit)? = when (backFrom(screen)) {
        BackTo.ClosePage -> ({ page = null })
        BackTo.CancelMask -> vm::cancelMask
        BackTo.Editor -> vm::backToEditor
        BackTo.Home -> vm::newImage
        null -> null
    }
    BackHandler(enabled = back != null) { back?.invoke() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = {
                    Text(
                        titleFor(screen, state.input?.displayName),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (back != null) {
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (showsSettings(screen)) {
                        IconButton(onClick = { page = Page.Settings }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "screen",
        ) { target ->
            Box(Modifier.fillMaxSize()) {
                when (target) {
                    Screen.Home -> HomeScreen(
                        loading = state.loadingInput,
                        error = state.error ?: (job as? JobState.Failed)?.message,
                        batch = batchItems,
                        onChoose = chooseImages,
                        onOpenBatch = { page = Page.Batch },
                    )
                    Screen.Editor -> EditorScreen(
                        state = state,
                        lastJob = job,
                        batchActive = batchActive,
                        act = remember(vm) {
                            EditorActions(
                                pick = { changeOne.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                mode = vm::setMode,
                                auto = vm::useAuto,
                                scale = vm::setScale,
                                detail = vm::setDetail,
                                colorFix = vm::setColorFix,
                                lumaOnly = vm::setLumaOnly,
                                toggleStage = vm::toggleStage,
                                pro = vm::setPro,
                                getPacks = { page = Page.Settings },
                                finishBicubic = vm::setFinishWithBicubic,
                                faceStrength = vm::setFaceStrength,
                                generative = vm::setGenerative,
                                editMask = vm::editMask,
                                start = start,
                                dismissError = vm::dismissError,
                            )
                        },
                    )
                    Screen.Running -> (job as? JobState.Running)?.let { RunningScreen(it, state.input?.thumbnail, vm::cancel) }
                    Screen.Mask -> state.input?.thumbnail?.let { thumb ->
                        MaskEditorScreen(
                            image = thumb,
                            detected = state.input?.damage?.mask,
                            initialStrokes = state.repairStrokes,
                            initialAutoDetect = state.autoDamage,
                            onDone = vm::saveMask,
                            onCancel = vm::cancelMask,
                        )
                    }
                    Screen.Result -> (job as? JobState.Done)?.let { done ->
                        ResultScreen(
                            done = done,
                            before = state.before,
                            after = state.after,
                            exportOptions = vm.exportOptions,
                            exporting = state.exporting,
                            onSave = { vm.save(done, it) },
                            onShare = { vm.share(done, it) { intent -> context.startActivity(intent) } },
                            onNew = vm::newImage,
                        )
                    }
                    Screen.Settings -> SettingsScreen(
                        forceCpu = state.forceCpu,
                        gentle = state.gentle,
                        packs = packs,
                        published = vm.packsPublished,
                        deviceRamGb = vm.deviceRamGb,
                        act = remember(vm) {
                            SettingsActions(
                                forceCpu = vm::setForceCpu,
                                gentle = vm::setGentle,
                                download = vm::downloadPack,
                                cancelDownload = vm::cancelDownload,
                                delete = vm::deletePack,
                                import = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            )
                        },
                    )
                    Screen.Batch -> BatchScreen(
                        items = batchItems,
                        onAdd = { addToBatch.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onCancel = vm::cancelBatch,
                        onClear = vm::clearBatch,
                    )
                }
            }
        }
    }

    if (pendingBatch.isNotEmpty()) {
        BatchSetupSheet(
            count = pendingBatch.size,
            initialExport = vm.exportOptions,
            onDismiss = vm::dismissBatch,
            onStart = { scale, export, charging ->
                vm.startBatch(pendingBatch, scale, export, charging)
                page = Page.Batch
            },
        )
    }
}
