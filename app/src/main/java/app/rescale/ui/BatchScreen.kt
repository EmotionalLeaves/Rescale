package app.rescale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val saved = items.count { it.state == BatchItem.State.SAVED }
        Text(
            if (items.isEmpty()) "No batch yet. Each image gets its own Auto settings and is saved to Pictures/Rescale."
            else "$saved of ${items.size} saved to Pictures/Rescale",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd) { Text("Add images") }
            if (active) OutlinedButton(onClick = onCancel) { Text("Cancel remaining") }
            else if (items.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("Clear list") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { "${it.index}-${it.name}-${it.state}" }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (item.state) {
                        BatchItem.State.SAVED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        BatchItem.State.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                        BatchItem.State.CANCELLED -> Icons.Outlined.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = item.state.name.lowercase(), tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        when (item.state) {
                            BatchItem.State.RUNNING -> if (item.total > 0) {
                                LinearProgressIndicator(
                                    progress = { item.done.toFloat() / item.total },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                            BatchItem.State.QUEUED -> Text("Waiting", style = MaterialTheme.typography.labelSmall)
                            else -> item.message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
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
            Text("Batch of $count image(s)", style = MaterialTheme.typography.titleMedium)
            Text("Scale", style = MaterialTheme.typography.titleSmall)
            val scales = listOf(0 to "Auto", 2 to "2×", 4 to "4×", 8 to "8×")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                scales.forEachIndexed { i, (v, label) ->
                    SegmentedButton(selected = scale == v, onClick = { scale = v },
                        shape = SegmentedButtonDefaults.itemShape(i, scales.size)) { Text(label) }
                }
            }
            Text("Save as", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(selected = format == f, onClick = { format = f },
                        shape = SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size)) { Text(f.label) }
                }
            }
            ToggleRow("Only while charging", "Starts when the phone is plugged in", charging) { charging = it }
            Button(
                onClick = { onStart(scale, initialExport.copy(format = format), charging) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start batch") }
        }
    }
}
