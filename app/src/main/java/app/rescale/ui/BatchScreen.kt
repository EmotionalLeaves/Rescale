package app.rescale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rescale.engine.pipeline.ExportFormat
import app.rescale.engine.pipeline.ExportOptions
import app.rescale.worker.BatchItem

@Composable
fun BatchScreen(items: List<BatchItem>, onAdd: () -> Unit, onCancel: () -> Unit, onClear: () -> Unit) {
    val active = items.any { it.state == BatchItem.State.QUEUED || it.state == BatchItem.State.RUNNING }
    Column(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("No batch yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add several images and Rescale does them one after another, each with its own Auto settings, " +
                        "saving them to Pictures/Rescale.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Summary(items, active) }
                items(items, key = { "${it.index}-${it.name}" }) { ItemRow(it) }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (active) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text("Cancel the rest")
                    }
                } else if (items.isNotEmpty()) {
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text("Clear list")
                    }
                }
                Button(onClick = onAdd, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Add images")
                }
            }
        }
    }
}

@Composable
private fun Summary(items: List<BatchItem>, active: Boolean) {
    val saved = items.count { it.state == BatchItem.State.SAVED }
    val failed = items.count { it.state == BatchItem.State.FAILED }
    val finished = items.count { it.state != BatchItem.State.QUEUED && it.state != BatchItem.State.RUNNING }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (active) "Working on it" else "Done",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "$saved of ${items.size} saved",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "To Pictures/Rescale" + if (failed > 0) " · ${plural(failed, "failed", "failed")}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (active) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { finished.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ItemRow(item: BatchItem) {
    val (icon, tint, label) = when (item.state) {
        BatchItem.State.SAVED -> Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "Saved")
        BatchItem.State.FAILED -> Triple(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, "Failed")
        BatchItem.State.CANCELLED -> Triple(Icons.Outlined.Cancel, MaterialTheme.colorScheme.onSurfaceVariant, "Cancelled")
        BatchItem.State.RUNNING -> Triple(Icons.Default.AutoAwesome, MaterialTheme.colorScheme.tertiary, "Working")
        BatchItem.State.QUEUED -> Triple(Icons.Default.HourglassEmpty, MaterialTheme.colorScheme.onSurfaceVariant, "Waiting")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.state == BatchItem.State.RUNNING) {
                    if (item.total > 0) {
                        LinearProgressIndicator(
                            progress = { item.done.toFloat() / item.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                } else {
                    Text(
                        item.message ?: label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == BatchItem.State.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Options shown after picking images for a batch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchSetupSheet(
    count: Int,
    initialExport: ExportOptions,
    onDismiss: () -> Unit,
    onStart: (scale: Int, export: ExportOptions, onlyWhileCharging: Boolean) -> Unit,
) {
    var scale by remember { mutableIntStateOf(0) }
    var format by remember { mutableStateOf(initialExport.format) }
    var charging by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Batch of ${plural(count, "image")}", style = MaterialTheme.typography.titleLarge)
            Text("Each image is analysed and gets its own Auto settings.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("Size")
            val scales = listOf(0 to "Auto", 2 to "2×", 4 to "4×", 8 to "8×")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                scales.forEachIndexed { i, (v, label) ->
                    SegmentedButton(selected = scale == v, onClick = { scale = v },
                        shape = SegmentedButtonDefaults.itemShape(i, scales.size)) { Text(label) }
                }
            }
            SectionTitle("Save as")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(selected = format == f, onClick = { format = f },
                        shape = SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size)) { Text(f.label) }
                }
            }
            ToggleRow("Only while charging", "Starts when the phone is plugged in", charging) { charging = it }
            Button(
                onClick = { onStart(scale, initialExport.copy(format = format), charging) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Start batch") }
        }
    }
}
