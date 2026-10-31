@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.droidfinder

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

private data class FinderDrag(val paths: List<String>, val copy: Boolean)

private fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

private fun dragData(context: Context, items: List<Entry>, copy: Boolean): DragAndDropTransferData {
    val local = FinderDrag(items.map { it.path }, copy)
    val files = items.filterNot { it.folder }
    val clip =
        if (files.size == items.size) {
            ClipData.newUri(
                    context.contentResolver,
                    "DroidFinder files",
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
        } else ClipData.newPlainText("DroidFinder folders", items.joinToString("\n") { it.path })
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
    dragAndDropSource {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (
                down.type == PointerType.Mouse &&
                    currentEvent.buttons.isPrimaryPressed &&
                    !model.isTrash
            ) {
                val copy =
                    currentEvent.keyboardModifiers.isAltPressed ||
                        currentEvent.keyboardModifiers.isCtrlPressed
                awaitTouchSlopOrCancellation(down.id) { change, _ ->
                    change.consume()
                    val items =
                        if (entry.path in model.selected) model.actionEntries else listOf(entry)
                    if (items.isNotEmpty()) startTransfer(dragData(context, items, copy))
                }
            }
        }
    }
}

@Composable
fun DragSelectionHandle(model: FinderModel) {
    val context = LocalContext.current
    val selection by rememberUpdatedState(model.actionEntries)
    var copy by remember { mutableStateOf(false) }
    Box(
        Modifier.size(38.dp)
            .semanticsLabel("Hold to drag selected files into a folder")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press)
                            copy =
                                event.keyboardModifiers.isAltPressed ||
                                    event.keyboardModifiers.isCtrlPressed
                    }
                }
            }
            .dragAndDropSource {
                detectTapGestures(
                    onLongPress = {
                        val items = selection
                        if (items.isNotEmpty() && !model.isTrash) {
                            startTransfer(dragData(context, items, copy))
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragIndicator, null, tint = Muted, modifier = Modifier.size(21.dp))
    }
}

@Composable
fun DraggableFileGlyph(model: FinderModel, entry: Entry, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).mouseFileDrag(model, entry)) { FileGlyph(entry, size) }
}

fun Modifier.folderDropTarget(model: FinderModel, folder: String?): Modifier = composed {
    if (folder == null || folder.startsWith("@")) return@composed this
    val context = LocalContext.current
    var hovering by remember(folder) { mutableStateOf(false) }
    val target =
        remember(folder, model) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    hovering = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    hovering = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    hovering = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    hovering = false
                    val parent = File(folder)
                    if (!model.allowedPath(folder)) return false
                    val native = event.toAndroidDragEvent()
                    val local = native.localState as? FinderDrag
                    if (local != null) {
                        model.transferPaths(local.paths, folder, move = !local.copy)
                        return true
                    }
                    if (model.busy != null) return false
                    val data = native.clipData ?: return false
                    val uris =
                        (0 until data.itemCount)
                            .mapNotNull { data.getItemAt(it).uri }
                            .filter { it.scheme == "content" }
                    if (uris.isEmpty()) return false
                    val permission = context.activity()?.requestDragAndDropPermissions(native)
                    model.importToPath(uris, folder) { permission?.release() }
                    return true
                }
            }
        }
    then(
            if (hovering)
                Modifier.background(Blue.copy(alpha = .12f))
                    .border(2.dp, Blue, RoundedCornerShape(5.dp))
            else Modifier,
        )
        .dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.toAndroidDragEvent().localState is FinderDrag ||
                    (model.busy == null &&
                        event.mimeTypes().any {
                            it.startsWith("image/") ||
                                it.startsWith("application/") ||
                                it == "text/uri-list" ||
                                it.startsWith("audio/") ||
                                it.startsWith("video/") ||
                                it == "text/plain"
                        })
            },
            target = target,
        )
}
