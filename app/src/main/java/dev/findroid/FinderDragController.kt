@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.findroid

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

internal data class SpringNavigationState(val tabs: List<FinderTab>, val active: Int)

internal class DropZone(
    val model: FinderModel,
    val folder: String,
    val spring: Boolean,
    val tabId: String?,
    val visible: () -> Boolean,
) {
    var coordinates: LayoutCoordinates? = null
    val bounds: Rect?
        get() = coordinates?.takeIf { it.isAttached && visible() }?.boundsInRoot()
}

private class DragScrollZone(val state: ScrollableState, val horizontal: Boolean) {
    var coordinates: LayoutCoordinates? = null
    val bounds: Rect?
        get() = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
}

/**
 * One stable native target: folders added by scrolling or spring navigation participate
 * immediately.
 */
internal class FinderDragController(private val scope: CoroutineScope) {
    val zones = mutableSetOf<DropZone>()
    private val scrollZones = mutableSetOf<DragScrollZone>()
    var active by mutableStateOf(false)
        private set

    var hovered by mutableStateOf<DropZone?>(null)
        private set

    var checked by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var scrolling by mutableStateOf(false)
        private set

    var local: FinderDrag? = null
        private set

    private var pointer: Offset? = null
    private var validation: Job? = null
    private var entered = 0L
    private var opened: DropZone? = null
    private var trail = SpringTrail()
    private var dropped = false

    private class SpringTrail {
        private val panes =
            mutableMapOf<FinderModel, Pair<SpringNavigationState, SpringNavigationState>>()

        fun open(zone: DropZone) {
            val pane = zone.model
            val before = pane.captureSpringNavigation()
            val previous = panes[pane]
            val original =
                previous?.takeIf { pane.matchesSpringNavigation(it.second) }?.first ?: before
            if (zone.tabId != null) {
                val index = pane.tabs.indexOfFirst { it.id == zone.tabId }
                if (index < 0) return
                pane.switchTab(index)
            } else pane.navigate(zone.folder)
            panes[pane] = original to pane.captureSpringNavigation()
        }

        fun finish(keep: FinderModel? = null) {
            panes.forEach { (pane, states) ->
                if (pane !== keep) pane.restoreSpringNavigation(states.first, states.second)
            }
            panes.clear()
        }
    }

    fun begin(event: DragAndDropEvent) {
        end()
        trail = SpringTrail()
        dropped = false
        local = event.toAndroidDragEvent().localState as? FinderDrag
        active = true
        pointer = null
    }

    fun move(event: DragAndDropEvent) {
        pointer = event.toAndroidDragEvent().let { Offset(it.x, it.y) }
        select(targetAt(pointer))
    }

    private fun targetAt(point: Offset?): DropZone? = point?.let { at ->
        zones
            .filter { it.bounds?.contains(at) == true }
            .minByOrNull { it.bounds?.let { b -> b.width * b.height } ?: Float.MAX_VALUE }
    }

    fun exit() {
        pointer = null
        select(null)
    }

    fun end() {
        validation?.cancel()
        if (!dropped) trail.finish()
        active = false
        pointer = null
        hovered = null
        local = null
        progress = 0f
        scrolling = false
        checked = false
        error = null
    }

    private fun select(zone: DropZone?) {
        if (hovered === zone) return
        validation?.cancel()
        hovered = zone
        entered = android.os.SystemClock.uptimeMillis()
        checked = false
        error = null
        progress = 0f
        opened = null
        if (zone == null) return
        val payload = local
        validation = scope.launch {
            val result = validate(zone, payload)
            if (hovered === zone && active) {
                error = result.exceptionOrNull()?.message
                checked = true
            }
        }
    }

    private suspend fun validate(zone: DropZone, payload: FinderDrag?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val model = zone.model
                check(model.allowedPath(zone.folder)) { "Storage access required" }
                val parent = model.documents.read(zone.folder)
                check(parent.folder && parent.writable) { "This folder is read only" }
                if (payload != null) {
                    if (payload.mode == FinderDragMode.SHORTCUT)
                        check(payload.origin.busy == null) { "Wait for the current operation" }
                    for (path in payload.paths) {
                        ensureActive()
                        check(payload.origin.allowedPath(path)) { "Source access required" }
                        if (payload.mode != FinderDragMode.SHORTCUT)
                            model.documents.validateTransfer(
                                path,
                                zone.folder,
                                payload.mode == FinderDragMode.MOVE,
                            )
                    }
                } else check(model.busy == null) { "Wait for the current operation" }
            }
        }

    private fun canSpring(zone: DropZone): Boolean =
        zone.spring &&
            if (zone.tabId != null) zone.model.tabs[zone.model.activeTab].id != zone.tabId
            else zone.folder != zone.model.location

    suspend fun tick(density: Float, seconds: Float) {
        if (dropped) return
        val point = pointer
        val zone = targetAt(point)
        select(zone)
        scrolling = false
        if (point != null) {
            val candidates =
                scrollZones
                    .filter { it.bounds?.contains(point) == true }
                    .sortedBy { it.bounds?.let { b -> b.width * b.height } ?: Float.MAX_VALUE }
            for (scroll in candidates) {
                val bounds = scroll.bounds ?: continue
                val velocity =
                    if (scroll.horizontal)
                        dragEdgeVelocity(point.x, bounds.left, bounds.right, 48f * density)
                    else dragEdgeVelocity(point.y, bounds.top, bounds.bottom, 48f * density)
                if (
                    velocity == 0f ||
                        velocity < 0 && !scroll.state.canScrollBackward ||
                        velocity > 0 && !scroll.state.canScrollForward
                )
                    continue
                val amount = scroll.state.scrollBy(velocity * 640f * density * seconds)
                if (kotlin.math.abs(amount) > .1f) {
                    scrolling = true
                    break
                }
            }
        }
        if (
            zone != null &&
                zone === hovered &&
                checked &&
                error == null &&
                canSpring(zone) &&
                opened !== zone &&
                !scrolling
        ) {
            progress = ((android.os.SystemClock.uptimeMillis() - entered) / 800f).coerceIn(0f, 1f)
            if (progress >= 1f) {
                opened = zone
                trail.open(zone)
                progress = 0f
            }
        } else progress = 0f
        if (scrolling) entered = android.os.SystemClock.uptimeMillis()
    }

    fun drop(event: DragAndDropEvent, context: Context): Boolean {
        val point = event.toAndroidDragEvent().let { Offset(it.x, it.y) }
        // Resolve again: scrolling/navigation may have changed the folder under the pointer.
        val zone = targetAt(point) ?: return false
        if (zone === hovered && checked && error != null) return false
        val native = event.toAndroidDragEvent()
        val payload = native.localState as? FinderDrag
        val uris =
            if (payload == null)
                native.clipData
                    ?.let { clip ->
                        (0 until clip.itemCount)
                            .mapNotNull { clip.getItemAt(it).uri }
                            .filter { it.scheme == "content" }
                    }
                    .orEmpty()
            else emptyList()
        if (payload == null && uris.isEmpty()) return false
        val permission =
            if (payload == null) context.activity()?.requestDragAndDropPermissions(native) else null
        val pendingTrail = trail
        dropped = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var imported = false
            var committed = false
            try {
                val result = validate(zone, payload)
                if (result.isFailure) {
                    zone.model.message =
                        result.exceptionOrNull()?.message ?: "Can't drop files here"
                    pendingTrail.finish()
                    return@launch
                }
                if (payload != null) {
                    if (payload.mode == FinderDragMode.SHORTCUT)
                        payload.origin.makeShortcuts(payload.paths, zone.folder)
                    else
                        payload.origin.transferPaths(
                            payload.paths,
                            zone.folder,
                            payload.mode == FinderDragMode.MOVE,
                        )
                } else {
                    zone.model.importToPath(uris, zone.folder) { permission?.release() }
                    imported = true
                }
                pendingTrail.finish(keep = zone.model)
                committed = true
            } finally {
                if (!committed) pendingTrail.finish()
                if (!imported) permission?.release()
            }
        }
        return true
    }

    fun registerScroll(state: ScrollableState, horizontal: Boolean): Any =
        DragScrollZone(state, horizontal).also(scrollZones::add)

    fun positionScroll(token: Any, coordinates: LayoutCoordinates) {
        (token as DragScrollZone).coordinates = coordinates
    }

    fun removeScroll(token: Any) {
        scrollZones.remove(token as DragScrollZone)
    }
}

internal val LocalFinderDrag = staticCompositionLocalOf<FinderDragController?> { null }

internal fun Modifier.finderDragHost(controller: FinderDragController): Modifier = composed {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current.density
    val target =
        remember(controller) {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) = controller.begin(event)

                override fun onEntered(event: DragAndDropEvent) = controller.move(event)

                override fun onMoved(event: DragAndDropEvent) = controller.move(event)

                override fun onExited(event: DragAndDropEvent) = controller.exit()

                override fun onEnded(event: DragAndDropEvent) = controller.end()

                override fun onDrop(event: DragAndDropEvent) = controller.drop(event, context)
            }
        }
    LaunchedEffect(controller.active, density) {
        if (controller.active) {
            var previous = System.nanoTime()
            while (controller.active) {
                delay(16)
                val now = System.nanoTime()
                controller.tick(density, ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f))
                previous = now
            }
        }
    }
    DisposableEffect(controller) { onDispose { controller.end() } }
    dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.toAndroidDragEvent().localState is FinderDrag ||
                event.mimeTypes().any {
                    it.startsWith("image/") ||
                        it.startsWith("application/") ||
                        it == "text/uri-list" ||
                        it.startsWith("audio/") ||
                        it.startsWith("video/") ||
                        it == "text/plain"
                }
        },
        target = target,
    )
}

internal fun Modifier.dragAutoScroll(
    state: ScrollableState,
    horizontal: Boolean = false,
): Modifier = composed {
    val controller = LocalFinderDrag.current ?: return@composed this
    val token =
        remember(controller, state, horizontal) { controller.registerScroll(state, horizontal) }
    DisposableEffect(token) { onDispose { controller.removeScroll(token) } }
    onGloballyPositioned { controller.positionScroll(token, it) }
}

@Composable
internal fun DragFeedback(controller: FinderDragController, modifier: Modifier = Modifier) {
    if (!controller.active) return
    val zone = controller.hovered
    val payload = controller.local
    val action =
        when (payload?.mode) {
            FinderDragMode.MOVE -> "Move"
            FinderDragMode.SHORTCUT -> "Create shortcuts"
            else -> "Copy"
        }
    val count = payload?.paths?.size
    val title =
        if (zone == null && payload?.externalFiles == true) "Drag to a folder or another app"
        else if (zone == null) "Drag to a folder"
        else
            "$action${count?.let { " $it item${if (it == 1) "" else "s"}" }.orEmpty()} to ${zone.model.title(zone.folder)}"
    val detail =
        controller.error
            ?: when {
                zone == null && payload?.externalFiles == true -> "Other apps receive a copy. Hold over folders or tabs to open them."
                zone == null -> "Hold over a folder or tab to open it"
                !controller.checked -> "Checking destination…"
                controller.scrolling -> "Scrolling · move away from the edge to stop"
                controller.progress > 0 -> "Hold to open"
                else -> "Release to $action".lowercase().replaceFirstChar(Char::uppercase)
            }
    Surface(
        modifier.widthIn(max = 360.dp).testTag("drag-feedback"),
        color = CanvasBackground,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Border),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (controller.progress > 0)
                CircularProgressIndicator(
                    progress = { controller.progress },
                    modifier = Modifier.size(22.dp),
                    color = Blue,
                    strokeWidth = 2.dp,
                )
            else
                Icon(
                    if (controller.error != null) Icons.Outlined.Block
                    else if (payload?.mode == FinderDragMode.SHORTCUT) Icons.Outlined.Link
                    else if (payload?.mode == FinderDragMode.MOVE)
                        Icons.AutoMirrored.Outlined.DriveFileMove
                    else Icons.Outlined.ContentCopy,
                    null,
                    Modifier.size(22.dp),
                    tint = if (controller.error != null) MaterialTheme.colorScheme.error else Blue,
                )
            Column(Modifier.padding(start = 12.dp).weight(1f, fill = false)) {
                Text(
                    title,
                    color = Ink,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
