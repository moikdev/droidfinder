@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.findroid

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
)

internal fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

private fun dragData(
    context: Context,
    model: FinderModel,
    items: List<Entry>,
    mode: FinderDragMode,
): DragAndDropTransferData {
    val local = FinderDrag(model, items.map { it.path }, mode)
    val files = items.filterNot { it.folder }
    val clip =
        if (files.size == items.size) {
            ClipData.newUri(
                    context.contentResolver,
                    "Findroid files",
                    files.first().documentUri
                        ?: FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.files",
                            files.first().file,
                        ),
                )
                .also { data ->
                    files.drop(1).forEach {
                        data.addItem(
                            ClipData.Item(
                                it.documentUri
                                    ?: FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.files",
                                        it.file,
                                    ),
                            ),
                        )
                    }
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

/** Mouse users drag an icon directly; touch users can hold the selection's drag grip. */
fun Modifier.mouseFileDrag(model: FinderModel, entry: Entry): Modifier = composed {
    val context = LocalContext.current
    dragAndDropSource(
        block = {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (
                    down.type == PointerType.Mouse &&
                        currentEvent.buttons.isPrimaryPressed &&
                        !model.isTrash
                ) {
                    val mode =
                        finderDragMode(
                            currentEvent.keyboardModifiers.isCtrlPressed ||
                                currentEvent.keyboardModifiers.isMetaPressed,
                            currentEvent.keyboardModifiers.isShiftPressed,
                            currentEvent.keyboardModifiers.isAltPressed,
                        )
                    awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                        val items =
                            if (entry.path in model.selected) model.actionEntries else listOf(entry)
                        if (items.isNotEmpty()) startTransfer(dragData(context, model, items, mode))
                    }
                }
            }
        },
    )
}

@Composable
fun DragSelectionHandle(model: FinderModel) {
    val context = LocalContext.current
    val selection by rememberUpdatedState(model.actionEntries)
    var mode by remember { mutableStateOf(FinderDragMode.MOVE) }
    val origin by rememberUpdatedState(model)
    Box(
        Modifier.size(if (LocalFinderDensity.current.touch) 48.dp else 38.dp)
            .semanticsLabel("Hold to drag selected files into a folder")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press)
                            mode =
                                finderDragMode(
                                    event.keyboardModifiers.isCtrlPressed ||
                                        event.keyboardModifiers.isMetaPressed,
                                    event.keyboardModifiers.isShiftPressed,
                                    event.keyboardModifiers.isAltPressed,
                                )
                    }
                }
            }
            .dragAndDropSource(
                block = {
                    detectTapGestures(
                        onLongPress = {
                            val items = selection
                            if (items.isNotEmpty() && !origin.isTrash) {
                                startTransfer(dragData(context, origin, items, mode))
                            }
                        },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragIndicator, null, tint = Muted, modifier = Modifier.size(21.dp))
    }
}

@Composable
fun DraggableFileGlyph(model: FinderModel, entry: Entry, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).testTag("drag-icon-${entry.path}").mouseFileDrag(model, entry)) {
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
