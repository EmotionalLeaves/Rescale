package app.rescale.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rescale.engine.pipeline.ExportFormat
import app.rescale.engine.pipeline.ExportOptions
import app.rescale.engine.pipeline.Exporter
import app.rescale.worker.JobState
import app.rescale.worker.UpscaleWorker
import kotlin.math.roundToInt

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
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(MaterialTheme.shapes.large),
            )
            Spacer(Modifier.height(32.dp))
        }
        Text(
            if (job.repairing) "Repairing damage" else "Upscaling",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (job.total > 0) {
            Text(
                "${(job.done * 100f / job.total).roundToInt()}%",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { job.done.toFloat() / job.total },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraSmall),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                (if (job.repairing) "Window" else "Tile") + " ${job.done} of ${job.total}" +
                    (job.etaSeconds?.let { " · ${UpscaleWorker.formatEta(it)} left" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("Loading model…", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraSmall))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "You can leave the app. Progress shows in the notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
fun ResultScreen(
    done: JobState.Done,
    before: Bitmap?,
    after: Bitmap?,
    exportOptions: ExportOptions,
    exporting: Boolean,
    onSave: (ExportOptions) -> Unit,
    onShare: (ExportOptions) -> Unit,
    onNew: () -> Unit,
) {
    var sheet by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (after != null) {
                CompareViewer(before = before, after = after, full = done.output, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    dims(done.width.toLong(), done.height.toLong()) + " px",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${bytes(done.output.length())} · ${"%.1f".format(done.millis / 1000.0)} s on " +
                        (if (done.backend == "VULKAN") "GPU" else "CPU") +
                        (if (done.pipeline.isNotEmpty()) " · ${done.pipeline}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { sheet = "save" },
                        enabled = !exporting,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (exporting) "Exporting…" else "Save")
                    }
                    FilledTonalButton(
                        onClick = { sheet = "share" },
                        enabled = !exporting,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Share")
                    }
                }
                TextButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Start over with a new image") }
            }
        }
    }
    sheet?.let { action ->
        ExportSheet(
            initial = exportOptions,
            width = done.width,
            height = done.height,
            actionLabel = if (action == "save") "Save to gallery" else "Share",
            onDismiss = { sheet = null },
            onConfirm = { opts ->
                sheet = null
                if (action == "save") onSave(opts) else onShare(opts)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    initial: ExportOptions,
    width: Int,
    height: Int,
    actionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (ExportOptions) -> Unit,
) {
    var opts by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(actionLabel, style = MaterialTheme.typography.titleLarge)
            SectionTitle("Format")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = opts.format == f,
                        onClick = { opts = opts.copy(format = f) },
                        shape = SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size),
                    ) { Text(f.label) }
                }
            }
            val longSide = maxOf(width, height)
            when (opts.format) {
                ExportFormat.PNG -> Text(
                    "Lossless. Largest files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExportFormat.JPEG -> {
                    if (longSide > Exporter.JPEG_MAX) Notice("Too large for JPEG; it will be saved as PNG.")
                    QualitySlider(opts.quality) { opts = opts.copy(quality = it) }
                }
                ExportFormat.WEBP -> {
                    if (longSide > Exporter.WEBP_MAX) Notice("WebP is limited to ${Exporter.WEBP_MAX} px; it will be saved as PNG.")
                    ToggleRow("Lossless", "Exact pixels, bigger file", opts.webpLossless) { opts = opts.copy(webpLossless = it) }
                    if (!opts.webpLossless) QualitySlider(opts.quality) { opts = opts.copy(quality = it) }
                }
            }
            ToggleRow(
                "Keep camera info",
                "Copies camera, date and exposure details. Location is always removed.",
                opts.keepExif,
            ) { opts = opts.copy(keepExif = it) }
            Button(onClick = { onConfirm(opts) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun QualitySlider(q: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quality", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$q", style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = q.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 50f..100f)
    }
}
