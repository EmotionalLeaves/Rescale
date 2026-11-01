package app.rescale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rescale.PackUi
import app.rescale.worker.PackDownload

@Composable
fun ModelsScreen(
    packs: List<PackUi>,
    published: Boolean,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Optional models that add restoration stages and higher-quality upscalers. " +
                    "Rescale only connects to the internet when you tap Download. Every pack is checked " +
                    "against a SHA-256 checksum built into the app before it's used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!published) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Model packs aren't published for this version yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(packs, key = { it.info.id }) { p -> PackCard(p, published, onDownload, onCancel, onDelete) }
        item {
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Import a pack file (.zip)")
            }
            Text(
                "For phones without internet: download rescale-pack-*.zip from the project's " +
                    "\"Model packs\" release on GitHub and import it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PackCard(
    p: PackUi,
    published: Boolean,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.info.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (p.installed) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Installed", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(p.info.description, style = MaterialTheme.typography.bodySmall)
            Text(
                "Adds: ${p.info.enables}" + if (p.sizeBytes > 0) " · ${p.sizeBytes shr 20} MB download" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val d = p.download) {
                is PackDownload.Running -> {
                    if (d.total > 0) {
                        LinearProgressIndicator(progress = { d.bytes.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
                        Text("${d.bytes shr 20} of ${d.total shr 20} MB", style = MaterialTheme.typography.labelSmall)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { onCancel(p.info.id) }) { Text("Cancel") }
                }
                else -> {
                    if (d is PackDownload.Failed) {
                        Text(d.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.installed) {
                        TextButton(onClick = { onDelete(p.info.id) }) { Text("Delete") }
                    } else {
                        Button(onClick = { onDownload(p.info.id) }, enabled = published && p.sizeBytes > 0) {
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}
