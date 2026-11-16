package app.rescale.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.rescale.BuildConfig
import app.rescale.PackUi
import app.rescale.worker.PackDownload

class SettingsActions(
    val forceCpu: (Boolean) -> Unit,
    val gentle: (Boolean) -> Unit,
    val download: (String) -> Unit,
    val cancelDownload: (String) -> Unit,
    val delete: (String) -> Unit,
    val import: () -> Unit,
)

/** One place for everything that isn't about the current image: processing, model packs, about. */
@Composable
fun SettingsScreen(
    forceCpu: Boolean,
    gentle: Boolean,
    packs: List<PackUi>,
    published: Boolean,
    deviceRamGb: Int,
    act: SettingsActions,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("Processing") {
                ToggleRow(
                    "Gentle mode",
                    "Runs cooler and slower. Good for long jobs and batches.",
                    gentle,
                    act.gentle,
                )
                ToggleRow(
                    "Use CPU only",
                    "Much slower. Rescale already switches to CPU by itself if the GPU gives bad results.",
                    forceCpu,
                    act.forceCpu,
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Model packs", Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                "Optional extras that add Fix options and stronger upscalers. Rescale only goes online when you " +
                    "tap Download, and checks every pack against a checksum built into the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
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
        items(packs, key = { it.info.id }) { p -> PackCard(p, published, deviceRamGb, act) }
        item {
            OutlinedButton(onClick = act.import, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Import a pack file (.zip)")
            }
            Text(
                "No internet on this phone? Download rescale-pack-*.zip from the project's \"Model packs\" " +
                    "release on GitHub and import it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
            Section("About") {
                Text("Rescale ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(ABOUT_TEXT, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Expandable("Licenses") {
                    Text(LICENSES_TEXT, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, contentDescription = if (open) "Collapse" else "Expand",
                modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        AnimatedVisibility(open) { Column(Modifier.padding(top = 8.dp)) { content() } }
    }
}

@Composable
private fun PackCard(p: PackUi, published: Boolean, deviceRamGb: Int, act: SettingsActions) {
    var askTerms by remember { mutableStateOf(false) }
    val tooLittleRam = deviceRamGb < p.info.minRamGb
    if (askTerms) {
        AlertDialog(
            onDismissRequest = { askTerms = false },
            title = { Text("${p.info.name}: licence terms") },
            text = { Text(p.info.terms.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { askTerms = false; act.download(p.info.id) }) { Text("Agree and download") }
            },
            dismissButton = { TextButton(onClick = { askTerms = false }) { Text("Cancel") } },
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
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
                    TextButton(onClick = { act.cancelDownload(p.info.id) }) { Text("Cancel") }
                }
                else -> {
                    if (d is PackDownload.Failed) {
                        Text(d.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.installed) {
                        TextButton(onClick = { act.delete(p.info.id) }) { Text("Delete") }
                    } else {
                        if (tooLittleRam) {
                            Text(
                                "Needs a phone with at least ${p.info.minRamGb} GB of RAM (this one has about $deviceRamGb GB).",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = { if (p.info.terms != null) askTerms = true else act.download(p.info.id) },
                            enabled = published && p.sizeBytes > 0 && !tooLittleRam,
                        ) {
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

private const val LICENSES_TEXT =
    "Rescale is GPL-3.0. Bundled models:\n\n" +
        "• realesr-general-x4v3 (photos): BSD-3-Clause, © Xintao Wang (Real-ESRGAN)\n" +
        "• realesr-animevideov3 x2/x4 (anime & art): BSD-3-Clause, © Xintao Wang (Real-ESRGAN)\n\n" +
        "Optional packs: FBCNN (Apache-2.0), Real-ESRGAN x4plus and RealESRNet x4plus (BSD-3-Clause), " +
        "GFPGAN v1.4 (Apache-2.0), RetinaFace/facexlib (MIT), MI-GAN (MIT, Picsart AI Research), old-photo damage " +
        "detector (MIT, Microsoft \"Bringing Old Photos Back to Life\"), AdcSR (code Apache-2.0; weights derived from " +
        "Stable Diffusion 2.1 under CreativeML Open RAIL++-M, with use restrictions).\n\n" +
        "Runtime: ncnn by Tencent (BSD-3-Clause)."

private const val ABOUT_TEXT =
    "Rescale upscales and repairs images entirely on your phone. It only goes online when you " +
        "download a model pack. No accounts, ads, analytics or watermarks.\n\n" +
        "Upscaling invents plausible detail. It doesn't recover what was never captured, so don't " +
        "treat results as evidence (faces and text especially).\n\n" +
        "Source: github.com/EmotionalLeaves/Rescale"
