package dev.droidfinder

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged

/** Each tab/directory/view owns its viewport, even while another pane or tab is visible. */
@Composable
fun rememberKeyboardListState(
    model: FinderModel,
    paths: List<String?>,
    view: String = "list",
): LazyListState {
    val tabId = model.tabs[model.activeTab].id
    val viewport =
        remember(model.location, view, model.query, model.searchRules) { model.viewportKey(view) }
    return key(tabId, viewport) {
        val state = rememberLazyListState()
        val saved = remember { model.savedViewport(tabId, viewport) }
        val latestPaths by rememberUpdatedState(paths)
        var ready by remember { mutableStateOf(false) }
        var restoring by remember { mutableStateOf(false) }
        val initialInteraction = remember { model.viewportInteraction }
        val initialRequest = remember { model.keyboardScrollRequest }
        fun capture(): ViewportAnchor? {
            if (!ready || restoring || latestPaths.isEmpty()) return null
            val index = state.firstVisibleItemIndex
            return ViewportAnchor(
                (state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
                        as? String)
                    ?.takeUnless { it.startsWith("group:") },
                index,
                state.firstVisibleItemScrollOffset,
            )
        }
        fun record() {
            capture()?.let { model.recordViewport(tabId, viewport, it) }
        }
        LaunchedEffect(model.loading, paths) {
            val anchor = saved?.path?.let(paths::indexOf) ?: -1
            if (!ready && (saved == null || anchor >= 0 || !model.loading)) {
                withFrameNanos {}
                if (ready) return@LaunchedEffect
                restoring = true
                try {
                    if (paths.isNotEmpty() && saved != null) {
                        val at =
                            saved.path?.let(paths::indexOf)?.takeIf { it >= 0 }
                                ?: saved.index.coerceIn(paths.indices)
                        state.scrollToItem(at, saved.offset)
                    }
                    ready = true
                } finally {
                    restoring = false
                }
            }
        }
        LaunchedEffect(model.viewportInteraction, model.keyboardScrollRequest) {
            if (
                model.viewportInteraction != initialInteraction ||
                    model.keyboardScrollRequest != initialRequest
            )
                ready = true
        }
        LaunchedEffect(state) {
            snapshotFlow { state.isScrollInProgress }.collect { if (it && !restoring) ready = true }
        }
        LaunchedEffect(state) {
            snapshotFlow {
                Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, ready)
            }
                .distinctUntilChanged()
                .collect { record() }
        }
        DisposableEffect(state) {
            model.registerViewport(tabId, viewport, state, ::capture)
            onDispose { model.unregisterViewport(tabId, viewport, state) }
        }
        val lead = model.keyboardFocusPath
        LaunchedEffect(model.keyboardScrollRequest, lead, paths, ready) {
            if (!ready || model.keyboardScrollRequest == initialRequest) return@LaunchedEffect
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
                        -(layout.viewportEndOffset - layout.viewportStartOffset - row)
                            .coerceAtLeast(0)
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
        state
    }
}

@Composable
fun rememberKeyboardGridState(model: FinderModel, paths: List<String?>): LazyGridState {
    val tabId = model.tabs[model.activeTab].id
    val viewport =
        remember(model.location, model.query, model.searchRules) { model.viewportKey("icons") }
    return key(tabId, viewport) {
        val state = rememberLazyGridState()
        val saved = remember { model.savedViewport(tabId, viewport) }
        val latestPaths by rememberUpdatedState(paths)
        var ready by remember { mutableStateOf(false) }
        var restoring by remember { mutableStateOf(false) }
        val initialInteraction = remember { model.viewportInteraction }
        val initialRequest = remember { model.keyboardScrollRequest }
        fun capture(): ViewportAnchor? {
            if (!ready || restoring || latestPaths.isEmpty()) return null
            val index = state.firstVisibleItemIndex
            return ViewportAnchor(
                (state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
                        as? String)
                    ?.takeUnless { it.startsWith("group:") },
                index,
                state.firstVisibleItemScrollOffset,
            )
        }
        fun record() {
            capture()?.let { model.recordViewport(tabId, viewport, it) }
        }
        LaunchedEffect(model.loading, paths) {
            val anchor = saved?.path?.let(paths::indexOf) ?: -1
            if (!ready && (saved == null || anchor >= 0 || !model.loading)) {
                withFrameNanos {}
                if (ready) return@LaunchedEffect
                restoring = true
                try {
                    if (paths.isNotEmpty() && saved != null) {
                        val at =
                            saved.path?.let(paths::indexOf)?.takeIf { it >= 0 }
                                ?: saved.index.coerceIn(paths.indices)
                        state.scrollToItem(at, saved.offset)
                    }
                    ready = true
                } finally {
                    restoring = false
                }
            }
        }
        LaunchedEffect(model.viewportInteraction, model.keyboardScrollRequest) {
            if (
                model.viewportInteraction != initialInteraction ||
                    model.keyboardScrollRequest != initialRequest
            )
                ready = true
        }
        LaunchedEffect(state) {
            snapshotFlow { state.isScrollInProgress }.collect { if (it && !restoring) ready = true }
        }
        LaunchedEffect(state) {
            snapshotFlow {
                Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, ready)
            }
                .distinctUntilChanged()
                .collect { record() }
        }
        DisposableEffect(state) {
            model.registerViewport(tabId, viewport, state, ::capture)
            onDispose { model.unregisterViewport(tabId, viewport, state) }
        }
        val lead = model.keyboardFocusPath
        LaunchedEffect(model.keyboardScrollRequest, lead, paths, ready) {
            if (!ready || model.keyboardScrollRequest == initialRequest || lead == null)
                return@LaunchedEffect
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
                        -(layout.viewportEndOffset - layout.viewportStartOffset - row)
                            .coerceAtLeast(0)
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
        state
    }
}

@Composable
fun rememberBrowserHorizontalState(model: FinderModel, view: String): ScrollState {
    val tabId = model.tabs[model.activeTab].id
    val viewport =
        remember(model.location, view, model.query, model.searchRules) { model.viewportKey(view) }
    return key(tabId, viewport) {
        val state = remember { ScrollState(0) }
        val saved = remember { model.savedViewport(tabId, viewport)?.offset ?: 0 }
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(model.loading) {
            if (!ready && (!model.loading || saved == 0)) {
                withFrameNanos {}
                if (!ready) {
                    state.scrollTo(saved)
                    ready = true
                }
            }
        }
        fun record() {
            if (ready) model.recordViewport(tabId, viewport, ViewportAnchor(offset = state.value))
        }
        LaunchedEffect(state) {
            snapshotFlow { state.isScrollInProgress }.collect { if (it) ready = true }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.value to ready }.distinctUntilChanged().collect { record() }
        }
        DisposableEffect(state) {
            model.registerViewport(tabId, viewport, state) {
                if (ready) ViewportAnchor(offset = state.value) else null
            }
            onDispose { model.unregisterViewport(tabId, viewport, state) }
        }
        state
    }
}
