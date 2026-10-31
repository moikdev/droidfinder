package dev.droidfinder

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class BitmapZoomState {
    var zoom by mutableStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}

@Composable
internal fun BitmapZoomButtons(state: BitmapZoomState) {
    ToolButton(
        Icons.Outlined.Remove,
        "Zoom out",
        { state.zoom = (state.zoom - .25f).coerceAtLeast(1f) },
        enabled = state.zoom > 1f,
    )
    TextButton(onClick = state::reset) {
        Text(if (state.zoom == 1f) "Fit" else "${(state.zoom * 100).toInt()}%", fontSize = 11.sp)
    }
    ToolButton(
        Icons.Outlined.Add,
        "Zoom in",
        { state.zoom = (state.zoom + .25f).coerceAtMost(5f) },
        enabled = state.zoom < 5f,
    )
}

@Composable
internal fun ZoomableBitmap(
    bitmap: Bitmap,
    description: String,
    modifier: Modifier = Modifier,
    controls: Boolean = true,
    highlights: List<android.graphics.RectF> = emptyList(),
    activeHighlights: List<android.graphics.RectF> = emptyList(),
    stateKey: Any = bitmap,
    zoomState: BitmapZoomState = remember(stateKey) { BitmapZoomState() },
    paper: Boolean = false,
) {
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun clampPan() {
        val scale =
            minOf(
                viewport.width.toFloat() / bitmap.width,
                viewport.height.toFloat() / bitmap.height,
            )
        val dx = ((bitmap.width * scale * zoomState.zoom - viewport.width) / 2).coerceAtLeast(0f)
        val dy = ((bitmap.height * scale * zoomState.zoom - viewport.height) / 2).coerceAtLeast(0f)
        zoomState.pan = Offset(zoomState.pan.x.coerceIn(-dx, dx), zoomState.pan.y.coerceIn(-dy, dy))
    }
    val transform = rememberTransformableState { scale, offset, _ ->
        zoomState.zoom = (zoomState.zoom * scale).coerceIn(1f, 5f)
        zoomState.pan += offset
        clampPan()
    }
    LaunchedEffect(zoomState.zoom, viewport, bitmap) { clampPan() }
    Column(modifier) {
        BoxWithConstraints(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .transformable(transform)
                .onSizeChanged { viewport = it },
            contentAlignment = Alignment.Center,
        ) {
            val scale = minOf(maxWidth.value / bitmap.width, maxHeight.value / bitmap.height)
            Box(
                Modifier.width((bitmap.width * scale).dp)
                    .height((bitmap.height * scale).dp)
                    .graphicsLayer(
                        scaleX = zoomState.zoom,
                        scaleY = zoomState.zoom,
                        translationX = zoomState.pan.x,
                        translationY = zoomState.pan.y,
                    )
                    .then(
                        if (paper)
                            Modifier.shadow(2.dp).border(.5.dp, Border).background(Color.White)
                        else Modifier,
                    ),
            ) {
                Image(
                    bitmap.asImageBitmap(),
                    description,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Canvas(Modifier.fillMaxSize()) {
                    fun mark(rect: android.graphics.RectF, color: Color) {
                        drawRect(
                            color,
                            Offset(rect.left * size.width, rect.top * size.height),
                            androidx.compose.ui.geometry.Size(
                                (rect.width() * size.width).coerceAtLeast(1f),
                                (rect.height() * size.height).coerceAtLeast(1f),
                            ),
                        )
                    }
                    highlights.forEach { mark(it, Color(0x55FFD54F)) }
                    activeHighlights.forEach { mark(it, Color(0x66FF9800)) }
                }
            }
        }
        if (controls)
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BitmapZoomButtons(zoomState)
            }
    }
}
