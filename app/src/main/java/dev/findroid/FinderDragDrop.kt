@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.findroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

enum class FinderDragMode {
    MOVE,
    COPY,
    SHORTCUT,
}

fun finderDragMode(ctrl: Boolean, shift: Boolean, alt: Boolean) =
    when {
        alt || ctrl && shift -> FinderDragMode.SHORTCUT
        ctrl -> FinderDragMode.COPY
        else -> FinderDragMode.MOVE
    }

internal data class FinderDrag(
    val origin: FinderModel,
    val paths: List<String>,
    val mode: FinderDragMode,
    val externalFiles: Boolean = false,
)

internal fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

internal fun finderDragData(
    context: Context,
    model: FinderModel,
    items: List<Entry>,
    mode: FinderDragMode,
): DragAndDropTransferData {
    require(items.isNotEmpty()) { "Select files to drag." }
    val local = FinderDrag(model, items.map { it.path }, mode, items.none { it.folder })
    val files = items.filterNot { it.folder }
    val clip = if (files.size == items.size) {
        val uris = files.map { entry ->
            entry.documentUri ?: FileProvider.getUriForFile(
                context, "${context.packageName}.files", entry.file,
            )
        }
        // Advertise every selected type, rather than only the first file's type.
        // uri-list also lets general-purpose file receivers recognize the payload.
        val types = (files.map { it.mime } + ClipDescription.MIMETYPE_TEXT_URILIST).distinct()
        ClipData("Findroid files", types.toTypedArray(), ClipData.Item(uris.first())).also { data ->
            uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
        }
    } else ClipData.newPlainText("Findroid folders", items.joinToString("\n") { it.path })

    return DragAndDropTransferData(
        clip,
        localState = local,
        flags =
            if (files.size == items.size) View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
            else 0,
    )
}

/** Drag an icon with a mouse, or hold it with touch/pen to lift the file. */
fun Modifier.fileDragSource(model: FinderModel, entry: Entry): Modifier = composed {
    val context = LocalContext.current
    dragAndDropSource(block = {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val key = "${model.paneId}:${entry.path}"
            val window = model.windowState
            if (!model.isTrash) {
                window.iconDragGesture = key
                try {
                    val mode = finderDragMode(
                        currentEvent.keyboardModifiers.isCtrlPressed || currentEvent.keyboardModifiers.isMetaPressed,
                        currentEvent.keyboardModifiers.isShiftPressed,
                        currentEvent.keyboardModifiers.isAltPressed,
                    )
                    // Freeze the selected files at pointer-down for the entire transfer.
                    val items = if (entry.path in model.selected) model.actionEntries else listOf(entry)
                    fun payload() = finderDragData(context, model, items, mode)
                    if (down.type == PointerType.Mouse && currentEvent.buttons.isPrimaryPressed) {
                        awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                            runCatching { startTransfer(payload()) }.onFailure {
                                model.message = "Couldn't drag these files. Try Share from More."
                            }
                        }
                    } else if (down.type != PointerType.Mouse) {
                        // Some Android input drivers report an unknown tool type.
                        // Treat those presses like touch, including pen/eraser tools.
                        val held = awaitLongPressOrCancellation(down.id)
                        if (held != null) {
                            currentEvent.changes.forEach { it.consume() }
                            runCatching { startTransfer(payload()) }.onFailure {
                                model.message = "Couldn't drag these files. Try Share from More."
                            }
                        }
                    }
                    // Keep ownership until up/cancel, including the row's hold timeout.
                    while (currentEvent.changes.any { it.pressed }) awaitPointerEvent()
                } finally {
                    if (window.iconDragGesture == key) window.iconDragGesture = null
                }
            }
        }
    })
}

@Composable
fun DragSelectionHandle(model: FinderModel) {
    val context = LocalContext.current
    val selection by rememberUpdatedState(model.actionEntries)
    var mode by remember { mutableStateOf(FinderDragMode.MOVE) }
    val origin by rememberUpdatedState(model)
    val hint = if (selection.none { it.folder })
        "Hold Drag, then drop into a folder or another app. Other apps receive a copy."
    else "Hold Drag to move into a Findroid folder. To send folders to another app, use Share."
    Row(
        Modifier.heightIn(min = if (LocalFinderDensity.current.touch) 48.dp else 38.dp)
            .testTag("drag-selection")
            .clip(RoundedCornerShape(7.dp))
            .semantics {
                contentDescription = "Hold to drag selected files"
                onClick(label = "How to drag") { origin.message = hint; true }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press)
                            mode = finderDragMode(
                                event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed,
                                event.keyboardModifiers.isShiftPressed,
                                event.keyboardModifiers.isAltPressed,
                            )
                    }
                }
            }
            .dragAndDropSource(block = {
                detectTapGestures(
                    onTap = { origin.message = hint },
                    onLongPress = {
                        val items = selection
                        if (items.isNotEmpty() && !origin.isTrash) {
                            runCatching { startTransfer(finderDragData(context, origin, items, mode)) }
                                .onFailure { origin.message = "Couldn't drag these files. Try Share from More." }
                        }
                    },
                )
            })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.DragIndicator, null, tint = Muted, modifier = Modifier.size(18.dp))
        Text("Drag", color = Ink, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun DraggableFileGlyph(model: FinderModel, entry: Entry, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).testTag("drag-icon-${entry.path}").fileDragSource(model, entry)) {
        FileGlyph(entry, size)
    }
}

/** Logical targets remain live when a native drag opens a folder or scrolls new rows into view. */
fun Modifier.folderDropTarget(
    model: FinderModel,
    folder: String?,
    spring: Boolean = true,
    tabId: String? = null,
    visible: (() -> Boolean)? = null,
): Modifier = composed {
    if (folder == null || folder.startsWith("@")) return@composed this
    val controller = LocalFinderDrag.current ?: return@composed this
    val currentVisible by rememberUpdatedState(visible)
    val zone =
        remember(controller, model, folder, spring, tabId) {
            DropZone(model, folder, spring, tabId) { currentVisible?.invoke() != false }
        }
    DisposableEffect(zone) {
        controller.zones.add(zone)
        onDispose { controller.zones.remove(zone) }
    }
    val hovering = controller.hovered === zone
    onGloballyPositioned { zone.coordinates = it }
        .drawWithContent {
            drawContent()
            if (hovering) {
                val color =
                    if (controller.error == null) Blue
                    else androidx.compose.ui.graphics.Color(0xFFC95454)
                drawRoundRect(
                    color.copy(alpha = .10f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                )
                drawRoundRect(
                    color,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                )
            }
        }
}
