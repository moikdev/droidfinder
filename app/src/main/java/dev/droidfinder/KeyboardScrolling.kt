package dev.droidfinder

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*

/** Keep keyboard focus visible without moving a row that is already fully visible. */
@Composable
fun rememberKeyboardListState(model: FinderModel, paths: List<String?>): LazyListState {
    val state = rememberLazyListState()
    val lead = model.keyboardFocusPath
    LaunchedEffect(model.keyboardScrollRequest, lead, paths) {
        if (model.keyboardScrollRequest == 0) return@LaunchedEffect
        val index = paths.indexOf(lead)
        if (lead == null || index < 0) return@LaunchedEffect
        withFrameNanos {}
        val layout = state.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (visible == null) {
            val forward = index > (layout.visibleItemsInfo.lastOrNull()?.index ?: -1)
            val row = layout.visibleItemsInfo.firstOrNull()?.size ?: 0
            state.scrollToItem(
                index,
                if (forward)
                    -(layout.viewportEndOffset - layout.viewportStartOffset - row).coerceAtLeast(0)
                else 0,
            )
        } else
            when {
                visible.offset < layout.viewportStartOffset ->
                    state.scrollBy((visible.offset - layout.viewportStartOffset).toFloat())
                visible.offset + visible.size > layout.viewportEndOffset ->
                    state.scrollBy(
                        (visible.offset + visible.size - layout.viewportEndOffset).toFloat(),
                    )
            }
    }
    return state
}

@Composable
fun rememberKeyboardGridState(model: FinderModel, paths: List<String?>): LazyGridState {
    val state = rememberLazyGridState()
    val lead = model.keyboardFocusPath
    LaunchedEffect(model.keyboardScrollRequest, lead, paths) {
        if (model.keyboardScrollRequest == 0 || lead == null) return@LaunchedEffect
        val index = paths.indexOf(lead)
        if (index < 0) return@LaunchedEffect
        withFrameNanos {}
        val layout = state.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (visible == null) {
            val forward = index > (layout.visibleItemsInfo.lastOrNull()?.index ?: -1)
            val row = layout.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
            state.scrollToItem(
                index,
                if (forward)
                    -(layout.viewportEndOffset - layout.viewportStartOffset - row).coerceAtLeast(0)
                else 0,
            )
        } else
            when {
                visible.offset.y < layout.viewportStartOffset ->
                    state.scrollBy((visible.offset.y - layout.viewportStartOffset).toFloat())
                visible.offset.y + visible.size.height > layout.viewportEndOffset ->
                    state.scrollBy(
                        (visible.offset.y + visible.size.height - layout.viewportEndOffset)
                            .toFloat(),
                    )
            }
    }
    return state
}
