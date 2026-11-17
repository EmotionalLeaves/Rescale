package app.rescale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rescale.worker.BatchItem

/**
 * The one way in: a single button that takes one image (opens the editor) or several
 * (sets up a batch). A running or finished batch shows up here as a card.
 */
@Composable
fun HomeScreen(
    loading: Boolean,
    error: String?,
    batch: List<BatchItem>,
    onChoose: () -> Unit,
    onOpenBatch: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minHeight)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Hero(loading, error, batch, onChoose, onOpenBatch)
            Footer()
        }
    }
}

@Composable
private fun Hero(
    loading: Boolean,
    error: String?,
    batch: List<BatchItem>,
    onChoose: () -> Unit,
    onOpenBatch: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(88.dp).clip(MaterialTheme.shapes.extraLarge).background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Rescale", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make small, blurry or damaged images bigger and cleaner. Everything runs on this phone.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Reading image…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Choose images", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Pick one to fine-tune it, or several to do them all at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (batch.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            BatchCard(batch, onOpenBatch)
        }
    }
}

@Composable
private fun Footer() {
    Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "Offline. No uploads, accounts or tracking.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatchCard(items: List<BatchItem>, onOpen: () -> Unit) {
    val active = items.any { it.state == BatchItem.State.QUEUED || it.state == BatchItem.State.RUNNING }
    val finished = items.count { it.state != BatchItem.State.QUEUED && it.state != BatchItem.State.RUNNING }
    val saved = items.count { it.state == BatchItem.State.SAVED }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (active) "Batch running" else "Batch finished", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$saved of ${plural(items.size, "image")} saved to Pictures/Rescale",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpen) { Text("View") }
            }
            if (active) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { finished.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
