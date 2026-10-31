package dev.droidfinder

import android.view.KeyEvent as AndroidKeyEvent
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Quick Look owns keyboard focus in its own window, including after media controls are used. */
@Composable
fun PeekOverlay(
    entry: Entry,
    entries: List<Entry>,
    onSelect: (Entry) -> Unit,
    onOpenWith: (Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val index = entries.indexOfFirst { it.path == entry.path }
    val compact = LocalConfiguration.current.screenWidthDp < 700
    fun step(delta: Int) {
        entries.getOrNull(index + delta)?.takeIf { index >= 0 }?.let(onSelect)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Native media views can receive focus outside Compose's focus tree. Intercept at the
        // dialog window too, so the same shortcuts survive clicking playback/seek controls.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val handleNativeKey by
            rememberUpdatedState<(AndroidKeyEvent) -> Boolean>(
                { event ->
                    if (
                        event.action != AndroidKeyEvent.ACTION_DOWN ||
                            event.isCtrlPressed ||
                            event.isMetaPressed ||
                            event.isAltPressed
                    )
                        false
                    else
                        when (event.keyCode) {
                            AndroidKeyEvent.KEYCODE_SPACE,
                            AndroidKeyEvent.KEYCODE_ESCAPE -> {
                                if (event.repeatCount == 0) onDismiss()
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                step(-1)
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                step(1)
                                true
                            }
                            else -> false
                        }
                },
            )
        DisposableEffect(window) {
            val previous = window?.callback
            val callback = previous?.let {
                object : Window.Callback by it {
                    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean =
                        handleNativeKey(event) || previous.dispatchKeyEvent(event)
                }
            }
            if (window != null && callback != null) window.callback = callback
            onDispose { if (window?.callback === callback) window?.callback = previous }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Surface(
            Modifier.fillMaxWidth(if (compact) .96f else .86f)
                .fillMaxHeight(.9f)
                .testTag("peek-window")
                .semantics { paneTitle = "Peek" }
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (
                        event.type != KeyEventType.KeyDown ||
                            event.isCtrlPressed ||
                            event.isMetaPressed ||
                            event.isAltPressed
                    )
                        false
                    else
                        when (event.key) {
                            Key.Spacebar,
                            Key.Escape -> {
                                if (event.nativeKeyEvent.repeatCount == 0) onDismiss()
                                true
                            }
                            Key.DirectionLeft,
                            Key.DirectionUp -> {
                                step(-1)
                                true
                            }
                            Key.DirectionRight,
                            Key.DirectionDown -> {
                                step(1)
                                true
                            }
                            else -> false
                        }
                }
                .focusable(),
            shape = RoundedCornerShape(12.dp),
            color = AppBackground,
            shadowElevation = 24.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton(Icons.Outlined.Close, "Close Quick Look", onDismiss)
                    FileGlyph(entry, 23.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            entry.name,
                            color = Ink,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Peek", color = Muted, fontSize = 10.sp)
                    }
                    if (!entry.folder)
                        TextButton(onClick = { onOpenWith(entry) }) {
                            Text("Open with app", fontSize = 12.sp)
                        }
                }
                HorizontalDivider(color = Border, thickness = .5.dp)
                // A changed file creates a new native player/rendering session and releases the old
                // one.
                key(entry.path, entry.modified) {
                    PreviewContent(
                        entry,
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .background(CanvasBackground)
                            .padding(14.dp),
                        large = true,
                    )
                }
                HorizontalDivider(color = Border, thickness = .5.dp)
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.kind + if (entry.folder) "" else " · ${entry.displaySize}",
                        Modifier.weight(1f),
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compact)
                        Text(
                            "Space to close",
                            color = Muted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    ToolButton(
                        Icons.Outlined.ChevronLeft,
                        "Previous file",
                        { step(-1) },
                        enabled = index > 0,
                    )
                    Text(
                        "${(index + 1).coerceAtLeast(1)} of ${entries.size.coerceAtLeast(1)}",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    ToolButton(
                        Icons.Outlined.ChevronRight,
                        "Next file",
                        { step(1) },
                        enabled = index >= 0 && index < entries.lastIndex,
                    )
                }
            }
        }
    }
}
