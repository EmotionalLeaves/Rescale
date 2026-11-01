package app.rescale.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Before/after viewer: draggable divider (before on the left), pinch-zoom and pan applied to
 * both images together, double-tap to zoom in/out.
 */
@Composable
fun CompareViewer(before: Bitmap?, after: Bitmap, modifier: Modifier = Modifier) {
    val beforeImg = remember(before) { before?.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var split by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val handleWidth = 48.dp
    val handlePx = with(LocalDensity.current) { handleWidth.toPx() }

    fun clampPan(p: Offset, z: Float): Offset {
        val maxX = size.width * (z - 1) / 2f
        val maxY = size.height * (z - 1) / 2f
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }

    val layer = Modifier.fillMaxSize().graphicsLayer {
        scaleX = zoom
        scaleY = zoom
        translationX = pan.x
        translationY = pan.y
    }

    Box(
        modifier
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val newZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    // Keep the point under the fingers fixed while zooming.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val anchored = (pan - (centroid - center)) * (newZoom / zoom) + (centroid - center)
                    pan = clampPan(anchored + panChange, newZoom)
                    zoom = newZoom
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (zoom > 1.01f) {
                        zoom = 1f
                        pan = Offset.Zero
                    } else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        zoom = 3f
                        pan = clampPan((center - tap) * 3f, 3f)
                    }
                })
            },
    ) {
        if (beforeImg != null) {
            Image(
                beforeImg,
                contentDescription = "Original",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None, // show the original's real pixels when zoomed
                modifier = layer,
            )
        }
        val dividerX = size.width * split
        Box(
            Modifier.fillMaxSize().drawWithContent {
                if (beforeImg == null) drawContent() else clipRect(left = dividerX) { this@drawWithContent.drawContent() }
            },
        ) {
            Image(
                afterImg,
                contentDescription = "Upscaled",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = layer,
            )
        }

        if (beforeImg != null) {
            // Divider handle (wide touch target, thin line).
            Box(
                Modifier
                    .offset { IntOffset((dividerX - handlePx / 2).roundToInt(), 0) }
                    .width(handleWidth)
                    .fillMaxHeight()
                    .semantics { contentDescription = "Comparison divider, ${(split * 100).roundToInt()}% original" }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            if (size.width > 0) split = (split + dx / size.width).coerceIn(0f, 1f)
                        }
                    },
            ) {
                Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White))
                Box(
                    Modifier.align(Alignment.Center).size(28.dp)
                        .background(Color.White, CircleShape),
                )
            }
            Label("Before", Modifier.align(Alignment.TopStart))
            Label("After", Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .padding(8.dp)
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
