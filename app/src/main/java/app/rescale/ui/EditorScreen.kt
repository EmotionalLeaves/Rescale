package app.rescale.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.rescale.EditorState
import app.rescale.core.model.Generative
import app.rescale.core.model.Limits
import app.rescale.core.model.Mode
import app.rescale.core.model.Packs
import app.rescale.core.model.Planner
import app.rescale.core.model.Stage
import app.rescale.worker.JobState
import kotlin.math.roundToInt

/** Everything the editor can ask for. Built once in [RescaleApp] from the view model. */
class EditorActions(
    val pick: () -> Unit,
    val mode: (Mode) -> Unit,
    val auto: () -> Unit,
    val scale: (Int) -> Unit,
    val detail: (Float) -> Unit,
    val colorFix: (Boolean) -> Unit,
    val lumaOnly: (Boolean) -> Unit,
    val toggleStage: (Stage) -> Unit,
    val pro: (Boolean) -> Unit,
    val getPacks: () -> Unit,
    val finishBicubic: (Boolean) -> Unit,
    val faceStrength: (Float) -> Unit,
    val generative: (Boolean) -> Unit,
    val editMask: () -> Unit,
    val start: () -> Unit,
    val dismissError: () -> Unit,
)

@Composable
fun EditorScreen(state: EditorState, lastJob: JobState, batchActive: Boolean, act: EditorActions) {
    val input = state.input ?: return
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column {
                input.thumbnail?.let { thumb ->
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = "Selected image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(MaterialTheme.shapes.large),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        dims(input.info.width.toLong(), input.info.height.toLong()) + " px",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = act.pick) { Text("Change image") }
                }
            }

            TypeSection(state, act)
            SizeSection(state, act)
            FixSection(state, act)
            FineTuneSection(state, act)

            Text(
                "Upscaling invents plausible detail. It doesn't recover what was never captured.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        ActionBar(state, lastJob, batchActive, act)
    }
}

@Composable
private fun TypeSection(state: EditorState, act: EditorActions) {
    Section("Image type") {
        val count = Mode.entries.size + 1
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.auto,
                onClick = act.auto,
                shape = SegmentedButtonDefaults.itemShape(0, count),
            ) { Text("Auto") }
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = !state.auto && state.mode == m,
                    onClick = { act.mode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i + 1, count),
                ) { Text(m.label) }
            }
        }
        state.autoPlan?.let { plan ->
            Spacer(Modifier.height(12.dp))
            Text(plan.description, style = MaterialTheme.typography.bodyMedium)
            if (state.auto) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auto picked: " + plan.chips.joinToString(" → "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.autoPlan?.suggestRepair == true && Stage.REPAIR !in state.stages) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Looks like it has scratches, cracks or spots. Repair can fill them in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = act.editMask) { Text("Review") }
                }
            }
        }
    }
}

@Composable
private fun SizeSection(state: EditorState, act: EditorActions) {
    Section("Size") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Planner.supportedScales.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = state.scale == s,
                    onClick = { act.scale(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, Planner.supportedScales.size),
                ) { Text("$s×") }
            }
        }
        val passes = Planner.factors(state.scale).size
        if (passes >= 2) {
            Spacer(Modifier.height(8.dp))
            Notice(
                if (passes >= 3) "Beyond 8×, detail is mostly invented. $passes passes, each blending in less generated detail."
                else "8× runs two passes (4× then 2×). Expect invented texture at full zoom.",
            )
        }
        state.estimate?.let { e ->
            if (e.width * e.height > Limits.GALLERY_WARN_PIXELS) {
                Notice("Over 100 megapixels. Some gallery apps can't open images this large.")
            }
        }
        if (state.mode == Mode.PHOTO && state.input?.info?.let { minOf(it.width, it.height) < 64 } == true) {
            Notice("Very small image: the model will invent most of the detail.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixSection(state: EditorState, act: EditorActions) {
    Section("Fix") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stage.entries.forEach { stage ->
                val available = stage.packId in state.installedPacks
                val usable = available && !(stage != Stage.DE_JPEG && stage != Stage.REPAIR && state.mode != Mode.PHOTO)
                FilterChip(
                    selected = usable && stage in state.stages,
                    onClick = { if (available) act.toggleStage(stage) else act.getPacks() },
                    enabled = usable || !available,
                    label = { Text(stage.label) },
                    leadingIcon = if (!available) {
                        { Icon(Icons.Default.Download, contentDescription = "needs download", modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
        if (Stage.entries.any { it.packId !in state.installedPacks }) {
            Text(
                "Options with a download icon need a free model pack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (Stage.REPAIR in state.stages) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val painted = state.repairStrokes.count { !it.erase }
                Text(
                    listOfNotNull(
                        if (state.autoDamage) "Detected damage" else null,
                        if (painted > 0) plural(painted, "painted area") else null,
                    ).joinToString(" + ").ifEmpty { "Nothing marked" } + " will be filled in",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = act.editMask) { Text("Edit") }
            }
        }
        if (Stage.FACES in state.stages) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Face restore strength", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(state.faceStrength * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            Slider(value = state.faceStrength, onValueChange = act.faceStrength, valueRange = 0.1f..1f)
            Notice("Restored faces are partly invented. Small faces can end up looking like someone else. Don't use results as evidence.")
        }
        if (state.generativeAvailable) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val tiles = state.input?.info?.let { Generative.tiles(it.width, it.height) } ?: 0
            if (state.generativeFits) {
                ToggleRow(
                    "Generative detail",
                    "Diffusion model: realistic hair, skin and fabric. Slow: $tiles tiles, " +
                        "roughly ${(tiles * 2 + 59) / 60}–${(tiles * 10 + 59) / 60} min depending on the phone.",
                    state.generative,
                    act.generative,
                )
                if (state.generative) {
                    Notice("Generative detail redraws the image: small faces, text and patterns can change. Don't use it where accuracy matters.")
                }
            } else {
                Notice(
                    "Generative detail: this image is too big ($tiles tiles; the limit is ${Generative.MAX_TILES}, " +
                        "about 880 × 720 px). On a phone it would take hours. Crop it or use a smaller image.",
                )
            }
        } else if (state.mode == Mode.PHOTO && Packs.GENERATIVE !in state.installedPacks) {
            TextButton(onClick = act.getPacks, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Get Generative detail (diffusion model)")
            }
        }
    }
}

/** Detail, colours, Pro model and the bicubic finish: collapsed by default, with a one-line summary. */
@Composable
private fun FineTuneSection(state: EditorState, act: EditorActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val t = state.tuning
    val passes = Planner.factors(state.scale).size
    val proShown = state.proAvailable && !state.settings.generative
    val summary = listOfNotNull(
        "Detail ${(t.detail * 100).roundToInt()}%",
        if (t.colorFix) "colors matched" else null,
        if (t.lumaOnly) "original colors" else null,
        if (proShown && state.pro) "Pro model" else null,
        if (passes >= 3 && state.finishWithBicubic) "plain last pass" else null,
    ).joinToString(" · ")
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "arrow")

    Section {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Fine-tune", style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.rotate(arrow))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Detail", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${(t.detail * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = t.detail,
                    onValueChange = act.detail,
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
                ToggleRow("Match source colors", "Fixes color and brightness drift", t.colorFix, act.colorFix)
                ToggleRow("Keep original colors", "Only sharpen brightness; good for scans and skin tones", t.lumaOnly, act.lumaOnly)
                if (proShown) {
                    ToggleRow("Pro model", "Larger model with stronger texture; several times slower", state.pro, act.pro)
                }
                if (passes >= 3) {
                    ToggleRow(
                        "Finish with plain resize",
                        "Do the last 2× with bicubic instead of the model: softer, fewer artifacts",
                        state.finishWithBicubic,
                        act.finishBicubic,
                    )
                }
            }
        }
    }
}

/** Pinned to the bottom: what you'll get, anything blocking it, and the one button that matters. */
@Composable
private fun ActionBar(state: EditorState, lastJob: JobState, batchActive: Boolean, act: EditorActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val message = state.error ?: when (lastJob) {
                is JobState.Failed -> lastJob.message
                is JobState.Cancelled -> "Cancelled."
                else -> null
            }
            if (message != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        if (state.error != null) TextButton(onClick = act.dismissError) { Text("OK") }
                    }
                }
            }
            if (state.tooLarge) Notice("This image is too large to upscale yet (limit ${Limits.MAX_INPUT_PIXELS / 1_000_000} MP).")
            if (batchActive) Notice("A batch is running. Wait for it to finish (or cancel it) before upscaling here.")
            state.estimate?.let { e ->
                Text(
                    "Output ${dims(e.width, e.height)} · about ${bytes(e.approxPngBytes)} as PNG",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = act.start,
                enabled = !state.tooLarge && !batchActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Upscale ${state.scale}×", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
