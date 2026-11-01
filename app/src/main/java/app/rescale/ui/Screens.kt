package app.rescale.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import app.rescale.core.model.Stage
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rescale.EditorState
import app.rescale.core.model.Limits
import app.rescale.core.model.Mode
import app.rescale.core.model.Planner
import app.rescale.worker.JobState
import app.rescale.worker.UpscaleWorker
import java.text.NumberFormat

private val nf = NumberFormat.getIntegerInstance()

private fun dims(w: Long, h: Long) = "${nf.format(w)} × ${nf.format(h)}"

private fun bytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b shr 20} MB"
    else -> "${b shr 10} KB"
}

@Composable
fun HomeScreen(loading: Boolean, error: String?, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Upscale and restore images", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make small or blurry photos and art 2× or 4× bigger. Everything runs on this phone.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onPick, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Pick an image")
            }
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                "Offline. No uploads, accounts or tracking.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EditorScreen(
    state: EditorState,
    lastJob: JobState,
    onPick: () -> Unit,
    onMode: (Mode) -> Unit,
    onAuto: () -> Unit,
    onScale: (Int) -> Unit,
    onDetail: (Float) -> Unit,
    onColorFix: (Boolean) -> Unit,
    onLumaOnly: (Boolean) -> Unit,
    onToggleStage: (Stage) -> Unit,
    onPro: (Boolean) -> Unit,
    onOpenModels: () -> Unit,
    onFinishBicubic: (Boolean) -> Unit,
    onStart: () -> Unit,
    onDismissError: () -> Unit,
) {
    val input = state.input ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        input.thumbnail?.let { thumb ->
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = "Selected image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Input: ${dims(input.info.width.toLong(), input.info.height.toLong())}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onPick) { Text("Change") }
        }

        Text("Image type", style = MaterialTheme.typography.titleSmall)
        val count = Mode.entries.size + 1
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.auto,
                onClick = onAuto,
                shape = SegmentedButtonDefaults.itemShape(0, count),
            ) { Text("Auto") }
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = !state.auto && state.mode == m,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i + 1, count),
                ) { Text(m.label) }
            }
        }
        state.autoPlan?.let { plan ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(plan.description, style = MaterialTheme.typography.bodyMedium)
                    if (state.auto) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Auto picked: " + plan.chips.joinToString(" → "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Text("Scale", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Planner.supportedScales.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = state.scale == s,
                    onClick = { onScale(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, Planner.supportedScales.size),
                ) { Text("$s×") }
            }
        }
        val passes = Planner.factors(state.scale).size
        if (passes >= 2) {
            Notice(
                if (passes >= 3) "Beyond 8×, detail is mostly invented. $passes passes, each blending in less generated detail."
                else "8× runs two passes (4× then 2×). Expect invented texture at full zoom.",
            )
        }
        if (passes >= 3) {
            ToggleRow(
                "Finish with plain resize",
                "Do the last 2× with bicubic instead of the model: softer, fewer artifacts",
                state.finishWithBicubic,
                onFinishBicubic,
            )
        }

        RestorationControls(state, onToggleStage, onPro, onOpenModels)

        DetailControls(state, onDetail, onColorFix, onLumaOnly)

        state.estimate?.let { e ->
            Text(
                "Output: ${dims(e.width, e.height)} · ~${bytes(e.approxPngBytes)} PNG",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (e.width * e.height > Limits.GALLERY_WARN_PIXELS) {
                Notice("Over 100 megapixels. Some gallery apps can't open images this large.")
            }
        }
        if (state.tooLarge) {
            Notice("This image is too large to upscale yet (limit ${Limits.MAX_INPUT_PIXELS / 1_000_000} MP).")
        }
        if (state.mode == Mode.PHOTO && input.info.let { minOf(it.width, it.height) } < 64) {
            Notice("Very small image: the model will invent most of the detail.")
        }

        val message = state.error ?: when (lastJob) {
            is JobState.Failed -> lastJob.message
            is JobState.Cancelled -> "Cancelled."
            else -> null
        }
        if (message != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    if (state.error != null) TextButton(onClick = onDismissError) { Text("OK") }
                }
            }
        }

        Button(
            onClick = onStart,
            enabled = !state.tooLarge,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Upscale ${state.scale}×")
        }
        Text(
            "Upscaling invents plausible detail. It doesn't recover lost detail.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestorationControls(
    state: EditorState,
    onToggleStage: (Stage) -> Unit,
    onPro: (Boolean) -> Unit,
    onOpenModels: () -> Unit,
) {
    Column {
        Text("Restoration", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stage.entries.forEach { stage ->
                val available = stage.packId in state.installedPacks
                val usable = available && !(stage == Stage.DENOISE && state.mode != Mode.PHOTO)
                FilterChip(
                    selected = usable && stage in state.stages,
                    onClick = { if (available) onToggleStage(stage) else onOpenModels() },
                    enabled = usable || !available,
                    label = { Text(if (available) stage.label else "${stage.label} · get pack") },
                    leadingIcon = if (!available) {
                        { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
        if (state.proAvailable) {
            ToggleRow(
                "Pro model",
                "Larger model with stronger texture; several times slower",
                state.pro,
                onPro,
            )
        }
    }
}

@Composable
private fun DetailControls(
    state: EditorState,
    onDetail: (Float) -> Unit,
    onColorFix: (Boolean) -> Unit,
    onLumaOnly: (Boolean) -> Unit,
) {
    val t = state.tuning
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Detail", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${(t.detail * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = t.detail,
            onValueChange = onDetail,
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier.semantics { contentDescription = "Detail, ${(t.detail * 100).roundToInt()} percent" },
        )
        Row {
            Text("Faithful (softer)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Generated detail", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Match source colors", "Fixes color and brightness drift", t.colorFix, onColorFix)
        ToggleRow("Keep original colors", "Only sharpen brightness; good for scans and skin tones", t.lumaOnly, onLumaOnly)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Notice(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun RunningScreen(job: JobState.Running, thumbnail: Bitmap?, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        thumbnail?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(24.dp))
        }
        Text("Upscaling…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        if (job.total > 0) {
            LinearProgressIndicator(
                progress = { job.done.toFloat() / job.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tile ${job.done} of ${job.total}" + (job.etaSeconds?.let { " · ${UpscaleWorker.formatEta(it)} left" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Loading model…", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "You can leave the app. Progress shows in the notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
fun ResultScreen(
    done: JobState.Done,
    before: Bitmap?,
    after: Bitmap?,
    onSave: () -> Unit,
    shareIntent: () -> Intent,
    onNew: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (after != null) {
                CompareViewer(before = before, after = after, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${dims(done.width.toLong(), done.height.toLong())} · ${bytes(done.output.length())} · " +
                    "${"%.1f".format(done.millis / 1000.0)} s on ${if (done.backend == "VULKAN") "GPU" else "CPU"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Save")
                }
                FilledTonalButton(
                    onClick = { context.startActivity(shareIntent()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Share")
                }
            }
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New image") }
        }
    }
}
