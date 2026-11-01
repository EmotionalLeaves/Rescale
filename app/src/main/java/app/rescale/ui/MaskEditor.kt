package app.rescale.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.rescale.MaskStroke
import kotlin.math.roundToInt

private val MaskColor = Color(0xFFFF2D95)

/**
 * Marks what the Repair stage fills. Detected damage is shown in pink; Paint adds areas (anything
 * you want gone), Erase keeps areas the detector got wrong (thin railings, wires, eyelashes).
 * Strokes are stored in normalised image coordinates and rendered into a mask at job time.
 */
@Composable
fun MaskEditorScreen(
    image: Bitmap,
    detected: Bitmap?,
    initialStrokes: List<MaskStroke>,
    initialAutoDetect: Boolean,
    onDone: (strokes: List<MaskStroke>, autoDetect: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val strokes = remember { mutableStateListOf<MaskStroke>().apply { addAll(initialStrokes) } }
    var current by remember { mutableStateOf<MaskStroke?>(null) }
    var erase by remember { mutableStateOf(false) }
    var brush by remember { mutableStateOf(0.02f) }  // radius as a fraction of the image width
    var autoDetect by remember { mutableStateOf(initialAutoDetect && detected != null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val img = remember(image) { image.asImageBitmap() }
    val det = remember(detected) { detected?.asImageBitmap() }

    fun norm(o: Offset) = Offset(o.x / size.width.coerceAtLeast(1), o.y / size.height.coerceAtLeast(1))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (detected != null) {
                "Pink areas will be filled in. Paint over anything else you want gone; erase what isn't damage."
            } else {
                "Paint over scratches, spots, tears or anything you want removed. Painted areas are filled in."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            Modifier.fillMaxWidth().heightIn(max = 480.dp)
                .aspectRatio(image.width.toFloat() / image.height, matchHeightConstraintsFirst = false),
        ) {
            Image(img, contentDescription = "Image to repair", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen, alpha = 0.6f)
                    .pointerInput(erase, brush) {
                        size = this.size
                        detectDragGestures(
                            onDragStart = { o -> current = MaskStroke(listOf(norm(o).toPair()), brush, erase) },
                            onDrag = { change, _ ->
                                current = current?.let { it.copy(points = it.points + norm(change.position).toPair()) }
                            },
                            onDragEnd = { current?.let { strokes += it }; current = null },
                            onDragCancel = { current = null },
                        )
                    }
                    .pointerInput(erase, brush) {
                        size = this.size
                        detectTapGestures { o -> strokes += MaskStroke(listOf(norm(o).toPair()), brush, erase) }
                    },
            ) {
                if (autoDetect && det != null) {
                    drawImage(
                        det,
                        dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                        colorFilter = ColorFilter.tint(MaskColor, BlendMode.SrcIn),
                    )
                }
                (strokes + listOfNotNull(current)).forEach { s ->
                    val r = s.radius * this.size.width
                    val path = Path()
                    s.points.forEachIndexed { i, (x, y) ->
                        val p = Offset(x * this.size.width, y * this.size.height)
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    if (s.points.size == 1) {
                        val (x, y) = s.points[0]
                        drawCircle(
                            MaskColor, r, Offset(x * this.size.width, y * this.size.height),
                            blendMode = if (s.erase) BlendMode.Clear else BlendMode.SrcOver,
                        )
                    } else {
                        drawPath(
                            path, MaskColor,
                            style = Stroke(width = 2 * r, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            blendMode = if (s.erase) BlendMode.Clear else BlendMode.SrcOver,
                        )
                    }
                }
            }
        }
        if (detected != null) {
            ToggleRow(
                "Find damage automatically",
                "Shown in pink, found on this preview. The full-resolution pass looks again, so small " +
                    "differences are normal.",
                autoDetect,
            ) { autoDetect = it }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !erase, onClick = { erase = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text("Paint")
            }
            SegmentedButton(selected = erase, onClick = { erase = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text("Erase")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brush", style = MaterialTheme.typography.bodyMedium)
            Slider(value = brush, onValueChange = { brush = it }, valueRange = 0.005f..0.08f, modifier = Modifier.weight(1f).padding(start = 12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Undo")
            }
            TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear painting") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = { onDone(strokes.toList(), autoDetect) },
                enabled = autoDetect || strokes.any { !it.erase },
                modifier = Modifier.weight(1f),
            ) { Text("Done") }
        }
        Text(
            "Filled areas are invented from their surroundings: fine for scratches and blemishes, " +
                "but don't use repaired images as evidence.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Offset.toPair() = x to y
