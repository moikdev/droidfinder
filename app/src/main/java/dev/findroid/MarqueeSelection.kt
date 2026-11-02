package dev.findroid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

class MarqueeState {
    val items = mutableMapOf<String, Rect>()
    var origin by mutableStateOf(Offset.Zero)
    var rectangle by mutableStateOf<Rect?>(null)
}

fun Modifier.fileFrame(state: MarqueeState, path: String): Modifier = composed {
    DisposableEffect(path) { onDispose { state.items.remove(path) } }
    onGloballyPositioned { state.items[path] = it.boundsInRoot() }
}

fun Modifier.marqueeSelection(
    model: FinderModel,
    state: MarqueeState,
    excludeTop: Dp = 0.dp,
): Modifier = composed {
    val top = with(LocalDensity.current) { excludeTop.toPx() }
    onGloballyPositioned { state.origin = it.boundsInRoot().topLeft }
        .pointerInput(model.location, top) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val rootStart = down.position + state.origin
                if (
                    down.type == PointerType.Mouse &&
                        currentEvent.buttons.isPrimaryPressed &&
                        down.position.y >= top &&
                        state.items.values.none { it.contains(rootStart) }
                ) {
                    val append =
                        currentEvent.keyboardModifiers.isCtrlPressed ||
                            currentEvent.keyboardModifiers.isMetaPressed
                    val baseline = if (append) model.selected else emptySet()
                    fun update(position: Offset) {
                        val end = position + state.origin
                        val rect =
                            Rect(
                                minOf(rootStart.x, end.x),
                                minOf(rootStart.y, end.y),
                                maxOf(rootStart.x, end.x),
                                maxOf(rootStart.y, end.y),
                            )
                        state.rectangle = rect
                        model.selectPaths(
                            baseline + state.items.filterValues { it.overlaps(rect) }.keys,
                        )
                    }
                    val first =
                        awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                            update(change.position)
                        }
                    if (first == null) {
                        if (!append) model.clearSelection()
                    } else {
                        var pointer: PointerInputChange = first
                        while (pointer.pressed) {
                            update(pointer.position)
                            pointer.consume()
                            pointer =
                                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: break
                        }
                    }
                    state.rectangle = null
                }
            }
        }
        .drawWithContent {
            drawContent()
            state.rectangle?.let { root ->
                val rect = root.translate(-state.origin)
                drawRect(Blue.copy(alpha = .12f), rect.topLeft, rect.size)
                drawRect(
                    Blue.copy(alpha = .75f),
                    rect.topLeft,
                    rect.size,
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
}
