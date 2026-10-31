package dev.droidfinder

import android.content.Intent
import android.provider.OpenableColumns
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaQuickLook(entry: Entry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(entry.path, entry.modified, entry.size) { MediaPlayback(context, entry) }
    val video = entry.mediaKind == "video"
    var speedMenu by remember(state) { mutableStateOf(false) }
    var subtitleMenu by remember(state) { mutableStateOf(false) }
    var subtitleError by remember(state) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentState by rememberUpdatedState(state)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val target = state
                scope.launch {
                    val mime =
                        withContext(Dispatchers.IO) {
                            val name = runCatching {
                                context.contentResolver
                                    .query(
                                        uri,
                                        arrayOf(OpenableColumns.DISPLAY_NAME),
                                        null,
                                        null,
                                        null,
                                    )
                                    ?.use {
                                        if (it.moveToFirst()) it.getString(0) else ""
                                    }
                            }
                                .getOrNull()
                                .orEmpty()
                            val type =
                                when (name.substringAfterLast('.', "").lowercase()) {
                                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                                    "vtt" -> MimeTypes.TEXT_VTT
                                    else -> null
                                }
                            if (type != null)
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                            type
                        }
                    // A slow provider response must not attach a previous file's captions to a new
                    // Peek.
                    if (currentState === target) {
                        if (mime == null)
                            subtitleError = "Choose a subtitle file ending in .srt or .vtt."
                        else {
                            target.loadSubtitle(uri, mime)
                            subtitleError = null
                        }
                    }
                }
            }
        }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.start()
                Lifecycle.Event.ON_STOP -> state.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) state.start()
        onDispose {
            lifecycle.removeObserver(observer)
            state.stop()
        }
    }
    LaunchedEffect(state) {
        while (true) {
            delay(250)
            state.tick()
        }
    }
    Column(
        modifier.testTag(if (video) "video-preview" else "audio-preview").semantics {
            contentDescription = "${if (video) "Video" else "Audio"} preview of ${entry.name}"
        },
    ) {
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .background(if (video) Color.Black else ToolbarSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (video)
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            useController = false
                            isFocusable = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        }
                    },
                    update = { it.player = state.player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            else
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.GraphicEq, null, Modifier.size(72.dp), tint = Blue)
                    Text(
                        entry.name,
                        Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Audio preview",
                        Modifier.padding(top = 6.dp),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            if (state.error != null)
                Surface(color = ToolbarSurface, modifier = Modifier.padding(24.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error!!, color = Ink, fontSize = 13.sp)
                        TextButton(onClick = { state.retry() }) { Text("Try again") }
                    }
                }
            else if (state.buffering)
                CircularProgressIndicator(Modifier.size(24.dp), color = Blue, strokeWidth = 2.dp)
        }
        val notice = subtitleError ?: state.notice
        if (notice != null)
            Text(
                notice,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = Muted,
                fontSize = 12.sp,
            )
        val transport: @Composable RowScope.() -> Unit = {
            ToolButton(
                Icons.Outlined.RestartAlt,
                "Restart playback",
                { state.seek(0) },
                enabled = state.ready,
            )
            ToolButton(
                Icons.Outlined.Replay10,
                "Back 10 seconds",
                { state.seek(state.position - 10_000) },
                enabled = state.ready,
            )
            IconButton(
                onClick = { state.toggle() },
                enabled = state.ready,
                modifier =
                    Modifier.size(40.dp)
                        .background(
                            if (state.ready) Blue else Muted.copy(alpha = .25f),
                            RoundedCornerShape(10.dp),
                        ),
            ) {
                Icon(
                    if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    "${if (state.playing) "Pause" else "Play"} ${if (video) "video" else "audio"}",
                    tint = Color.White,
                )
            }
            ToolButton(
                Icons.Outlined.Forward10,
                "Forward 10 seconds",
                { state.seek(state.position + 10_000) },
                enabled = state.ready,
            )
        }
        val options: @Composable RowScope.() -> Unit = {
            ToolButton(
                if (state.muted) Icons.AutoMirrored.Outlined.VolumeOff
                else Icons.AutoMirrored.Outlined.VolumeUp,
                if (state.muted) "Unmute" else "Mute",
                { state.toggleMute() },
            )
            if (video)
                Box {
                    ToolButton(
                        Icons.Outlined.Subtitles,
                        "Subtitles",
                        { subtitleMenu = true },
                        active = !state.subtitlesOff && state.selectedTrack != null,
                    )
                    DropdownMenu(subtitleMenu, { subtitleMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Off") },
                            onClick = {
                                state.chooseTrack(null)
                                subtitleMenu = false
                            },
                            leadingIcon = {
                                if (state.subtitlesOff) Icon(Icons.Outlined.Check, null)
                            },
                        )
                        state.tracks.forEach { track ->
                            DropdownMenuItem(
                                text = { Text(track.label) },
                                onClick = {
                                    state.chooseTrack(track)
                                    subtitleMenu = false
                                },
                                leadingIcon = {
                                    if (!state.subtitlesOff && track.key == state.selectedTrack)
                                        Icon(Icons.Outlined.Check, null)
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Open subtitle file…") },
                            onClick = {
                                subtitleMenu = false
                                picker.launch(arrayOf("*/*"))
                            },
                            leadingIcon = { Icon(Icons.Outlined.Add, null) },
                        )
                    }
                }
            Box {
                TextButton(
                    onClick = { speedMenu = true },
                    modifier = Modifier.semantics { contentDescription = "Playback speed" },
                ) {
                    Text(
                        "${state.speed.toString().removeSuffix(".0")}×",
                        color = Ink,
                        fontSize = 12.sp,
                    )
                }
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    MediaProgressStore.speeds.forEach { speed ->
                        DropdownMenuItem(
                            text = { Text("${speed.toString().removeSuffix(".0")}×") },
                            onClick = {
                                state.changeSpeed(speed)
                                speedMenu = false
                            },
                            leadingIcon = {
                                if (state.speed == speed) Icon(Icons.Outlined.Check, null)
                            },
                        )
                    }
                }
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().background(ToolbarSurface).padding(horizontal = 12.dp),
        ) {
            if (maxWidth >= 800.dp)
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    transport()
                    MediaTimeline(state, Modifier.weight(1f).padding(horizontal = 12.dp))
                    options()
                }
            else
                Column {
                    MediaTimeline(state, Modifier.fillMaxWidth().height(36.dp))
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        transport()
                        Spacer(Modifier.weight(1f))
                        options()
                    }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaTimeline(state: MediaPlayback, modifier: Modifier) {
    val blue = Blue
    val muted = Muted
    var scrubPosition by remember(state) { mutableStateOf<Float?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            mediaTime(scrubPosition?.toLong() ?: state.position),
            Modifier.testTag("media-position").widthIn(min = 38.dp),
            color = Muted,
            fontSize = 11.sp,
        )
        Slider(
            (scrubPosition ?: state.position.toFloat()).coerceIn(
                0f,
                state.duration.coerceAtLeast(1).toFloat(),
            ),
            { scrubPosition = it },
            onValueChangeFinished = {
                scrubPosition?.let { state.seek(it.toLong()) }
                scrubPosition = null
            },
            valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
            enabled = state.ready,
            modifier =
                Modifier.weight(1f).padding(horizontal = 10.dp).semantics {
                    contentDescription = "Playback position"
                },
            thumb = {
                Box(Modifier.size(10.dp).background(if (state.ready) Blue else Muted, CircleShape))
            },
            track = { slider ->
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    drawLine(
                        muted.copy(alpha = .22f),
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        size.height,
                    )
                    val fraction = slider.value / state.duration.coerceAtLeast(1).toFloat()
                    drawLine(
                        blue,
                        Offset(0f, size.height / 2),
                        Offset(size.width * fraction, size.height / 2),
                        size.height,
                    )
                }
            },
        )
        Text(mediaTime(state.duration), color = Muted, fontSize = 11.sp)
    }
}
