package dev.droidfinder

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

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

@Composable
fun MediaQuickLook(entry: Entry, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> active = true
                Lifecycle.Event.ON_STOP -> active = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!active) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Playback paused", color = Muted)
        }
        return
    }
    if (entry.kind == "Video") VideoQuickLook(entry, modifier) else AudioQuickLook(entry, modifier)
}

@Composable
private fun VideoQuickLook(entry: Entry, modifier: Modifier) {
    var video by remember(entry.path) { mutableStateOf<VideoView?>(null) }
    var ready by remember(entry.path) { mutableStateOf(false) }
    var playing by remember(entry.path) { mutableStateOf(false) }
    var position by remember(entry.path) { mutableIntStateOf(0) }
    var duration by remember(entry.path) { mutableIntStateOf(0) }
    var error by remember(entry.path) { mutableStateOf(false) }
    DisposableEffect(entry.path) {
        onDispose {
            ready = false
            video?.stopPlayback()
            video = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { video?.currentPosition ?: 0 }.getOrDefault(0)
            delay(250)
        }
    }
    Column(
        modifier.testTag("video-preview").semantics {
            contentDescription = "Video preview of ${entry.name}"
        },
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        video = this
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setOnErrorListener { _, _, _ ->
                            error = true
                            playing = false
                            true
                        }
                        setOnPreparedListener {
                            duration = it.duration
                            ready = true
                            seekTo(1)
                        }
                        setOnCompletionListener {
                            playing = false
                            position = duration
                        }
                        setVideoURI(entry.documentUri ?: Uri.fromFile(entry.file))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (error)
                Text(
                    "This device can't decode this video. Open it with another app.",
                    Modifier.padding(24.dp),
                    color = Color.White,
                )
            else if (!ready)
                CircularProgressIndicator(
                    Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                if (playing) "Pause video" else "Play video",
                {
                    if (playing) video?.pause() else video?.start()
                    playing = !playing
                },
                enabled = ready && !error,
            )
            Slider(
                position.toFloat(),
                {
                    position = it.toInt()
                    if (ready) video?.seekTo(position)
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = ready && !error,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("${mediaTime(position)} / ${mediaTime(duration)}", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AudioQuickLook(entry: Entry, modifier: Modifier) {
    val context = LocalContext.current
    var player by remember(entry.path) { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember(entry.path) { mutableStateOf(false) }
    var playing by remember(entry.path) { mutableStateOf(false) }
    var duration by remember(entry.path) { mutableIntStateOf(0) }
    var position by remember(entry.path) { mutableIntStateOf(0) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }
    DisposableEffect(entry.path) {
        val media = MediaPlayer()
        player = media
        runCatching {
            if (entry.documentUri != null) media.setDataSource(context, entry.documentUri)
            else media.setDataSource(entry.file.absolutePath)
            media.setOnPreparedListener {
                ready = true
                duration = it.duration
            }
            media.setOnCompletionListener {
                playing = false
                position = duration
            }
            media.setOnErrorListener { _, _, _ ->
                error = "This device can't decode this audio."
                true
            }
            media.prepareAsync()
        }
            .onFailure { error = it.message }
        onDispose {
            ready = false
            player = null
            media.release()
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(250)
        }
    }
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.MusicNote, null, Modifier.size(90.dp), tint = Blue)
        Text(entry.name, Modifier.padding(top = 20.dp), color = Ink)
        if (error != null) Text(error!!, color = Muted, modifier = Modifier.padding(top = 12.dp))
        else {
            Slider(
                position.toFloat(),
                {
                    position = it.toInt()
                    if (ready) player?.seekTo(position)
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = ready,
                modifier = Modifier.widthIn(max = 500.dp).padding(top = 20.dp),
            )
            Text("${mediaTime(position)} / ${mediaTime(duration)}", fontSize = 12.sp, color = Muted)
            IconButton(
                onClick = {
                    if (ready) {
                        if (playing) player?.pause() else player?.start()
                        playing = !playing
                    }
                },
                enabled = ready,
                modifier = Modifier.padding(top = 12.dp).size(54.dp),
            ) {
                Icon(
                    if (playing) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                    if (playing) "Pause audio" else "Play audio",
                    Modifier.size(48.dp),
                    tint = Blue,
                )
            }
        }
    }
}

private fun mediaTime(milliseconds: Int) =
    "%d:%02d".format(milliseconds / 60000, (milliseconds / 1000) % 60)
