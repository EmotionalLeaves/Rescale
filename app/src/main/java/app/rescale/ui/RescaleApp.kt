package app.rescale.ui

import android.Manifest
import android.os.Build
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

private enum class Screen { Home, Editor, Running, Result }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescaleApp(vm: RescaleViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pick(uri)
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // Notification permission is asked for the first time a job starts, not at launch.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val start = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val screen = when {
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
                title = { Text("Rescale") },
                navigationIcon = {
                    if (screen == Screen.Editor) {
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
                )
                Screen.Editor -> EditorScreen(
                    state = state,
                    lastJob = job,
                    onPick = pickImage,
                    onMode = vm::setMode,
                    onScale = vm::setScale,
                    onDetail = vm::setDetail,
                    onColorFix = vm::setColorFix,
                    onLumaOnly = vm::setLumaOnly,
                    onStart = start,
                    onDismissError = vm::dismissError,
                )
                Screen.Running -> RunningScreen(job as JobState.Running, state.input?.thumbnail, vm::cancel)
                Screen.Result -> ResultScreen(
                    done = job as JobState.Done,
                    before = state.before,
                    after = state.after,
                    onSave = { vm.save(job as JobState.Done) },
                    shareIntent = { vm.shareIntent(job as JobState.Done) },
                    onNew = vm::newImage,
                )
            }
        }
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
