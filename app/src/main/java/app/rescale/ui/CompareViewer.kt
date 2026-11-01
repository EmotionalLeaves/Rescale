package app.rescale.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ImageBitmap
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
import app.rescale.engine.pipeline.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A decoded piece of the full-resolution image, placed in layer coordinates. */
private class DetailTile(val image: ImageBitmap, val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Before/after viewer: draggable divider (before on the left), pinch-zoom and pan applied to
 * both images together, double-tap to zoom, a 1:1 button, and deep zoom: once the preview
 * runs out of pixels, the visible region is decoded from the full-resolution output.
 */
@Composable
fun CompareViewer(before: Bitmap?, after: Bitmap, full: File? = null, modifier: Modifier = Modifier) {
    val beforeImg = remember(before) { before?.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var split by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var detail by remember { mutableStateOf<DetailTile?>(null) }
    val handleWidth = 48.dp
    val handlePx = with(LocalDensity.current) { handleWidth.toPx() }

    // Full-resolution size and a region decoder for the output PNG.
    val fullSize = remember(full) { full?.let { runCatching { Exporter.pngSize(it) }.getOrNull() } }
    val decoder = remember(full) {
        full?.let {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(it.path)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(it.path, false)
            }.getOrNull()
        }
    }
    DisposableEffect(decoder) { onDispose { decoder?.recycle() } }

    // Where the image sits inside the (untransformed) layer with ContentScale.Fit.
    val imgW = (fullSize?.first ?: after.width).toFloat()
    val imgH = (fullSize?.second ?: after.height).toFloat()
    val fit = if (size.width > 0) min(size.width / imgW, size.height / imgH) else 1f
    val fitW = imgW * fit
    val fitH = imgH * fit
    val fitLeft = (size.width - fitW) / 2f
    val fitTop = (size.height - fitH) / 2f

    fun clampPan(p: Offset, z: Float): Offset {
        val maxX = max(0f, (fitW * z - size.width) / 2f)
        val maxY = max(0f, (fitH * z - size.height) / 2f)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }

    val maxZoom = max(8f, if (fit > 0) 4f / fit else 8f) // up to 4 screen px per output px
    val oneToOne = if (fit > 0) 1f / fit else 1f

    // Deep zoom: after the gesture settles, decode the visible region at a matching sample size.
    LaunchedEffect(zoom, pan, size, decoder) {
        val dec = decoder ?: return@LaunchedEffect
        if (size.width == 0 || fullSize == null) return@LaunchedEffect
        val shownPx = fitW * zoom // on-screen width of the whole image
        if (shownPx <= after.width * 1.25f) {
            detail = null
            return@LaunchedEffect
        }
        delay(150)
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Screen → layer: p = c + (s - pan - c) / zoom
        val l = cx + (0f - pan.x - cx) / zoom
        val t = cy + (0f - pan.y - cy) / zoom
        val r = cx + (size.width - pan.x - cx) / zoom
        val b = cy + (size.height - pan.y - cy) / zoom
        val x0 = ((l - fitLeft) / fit).coerceIn(0f, imgW).toInt()
        val y0 = ((t - fitTop) / fit).coerceIn(0f, imgH).toInt()
        val x1 = ((r - fitLeft) / fit).coerceIn(0f, imgW).roundToInt()
        val y1 = ((b - fitTop) / fit).coerceIn(0f, imgH).roundToInt()
        if (x1 - x0 < 2 || y1 - y0 < 2) return@LaunchedEffect
        var sample = 1
        while ((x1 - x0) / (sample * 2) >= size.width) sample *= 2
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                dec.decodeRegion(Rect(x0, y0, x1, y1), BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        } ?: return@LaunchedEffect
        detail = DetailTile(
            bmp.asImageBitmap(),
            fitLeft + x0 * fit, fitTop + y0 * fit, (x1 - x0) * fit, (y1 - y0) * fit,
        )
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
            .pointerInput(fitW) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val newZoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
                    // Keep the point under the fingers fixed while zooming.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val anchored = (pan - (centroid - center)) * (newZoom / zoom) + (centroid - center)
                    pan = clampPan(anchored + panChange, newZoom)
                    zoom = newZoom
                }
            }
            .pointerInput(fitW) {
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
            detail?.let { d ->
                Canvas(layer) {
                    drawImage(
                        d.image,
                        dstOffset = IntOffset(d.left.roundToInt(), d.top.roundToInt()),
                        dstSize = IntSize(d.width.roundToInt(), d.height.roundToInt()),
                        filterQuality = if (zoom > oneToOne * 1.5f) FilterQuality.None else FilterQuality.Medium,
                    )
                }
            }
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
                Box(Modifier.align(Alignment.Center).size(28.dp).background(Color.White, CircleShape))
            }
            Label("Before", Modifier.align(Alignment.TopStart))
            Label("After", Modifier.align(Alignment.TopEnd))
        }
        if (fullSize != null) {
            SmallFloatingActionButton(
                onClick = {
                    if (zoom in oneToOne * 0.95f..oneToOne * 1.05f) {
                        zoom = 1f; pan = Offset.Zero
                    } else {
                        zoom = oneToOne.coerceIn(1f, maxZoom); pan = clampPan(pan, zoom)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .semantics { contentDescription = "Toggle 1:1 pixel view" },
            ) { Text("1:1", style = MaterialTheme.typography.labelLarge) }
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
