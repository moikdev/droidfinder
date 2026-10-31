@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun DesktopTabStrip(model: FinderModel) {
    var context by remember { mutableStateOf<Pair<String, Offset?>?>(null) }
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(Sidebar).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            model.tabs.forEach { tab ->
                key(tab.id) {
                    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    val active = tab.id == model.tabs[model.activeTab].id
                    Column(
                        Modifier.width(180.dp)
                            .testTag("tab-${tab.id}")
                            .fillMaxHeight()
                            .folderDropTarget(model, tab.location)
                            .onGloballyPositioned { coordinates = it }
                            .pointerInput(tab.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    when {
                                        currentEvent.buttons.isSecondaryPressed -> {
                                            down.consume()
                                            var released: PointerInputChange? = null
                                            while (released == null) {
                                                val event =
                                                    awaitPointerEvent(PointerEventPass.Initial)
                                                if (event.type == PointerEventType.Release)
                                                    released =
                                                        event.changes.firstOrNull {
                                                            it.id == down.id
                                                        }
                                            }
                                            context =
                                                tab.id to
                                                    coordinates?.localToWindow(released.position)
                                        }
                                        currentEvent.buttons.isTertiaryPressed -> {
                                            model.tabs
                                                .indexOfFirst { it.id == tab.id }
                                                .takeIf { it >= 0 }
                                                ?.let(model::closeTab)
                                            down.consume()
                                        }
                                        down.type == PointerType.Mouse &&
                                            currentEvent.buttons.isPrimaryPressed -> {
                                            var distance = 0f
                                            val drag =
                                                awaitTouchSlopOrCancellation(down.id) {
                                                    change,
                                                    delta ->
                                                    distance += delta.x
                                                    change.consume()
                                                }
                                            if (drag != null) {
                                                var pointer: PointerInputChange = drag
                                                while (pointer.pressed) {
                                                    val event = awaitPointerEvent()
                                                    val next =
                                                        event.changes.firstOrNull {
                                                            it.id == down.id
                                                        } ?: break
                                                    distance += next.positionChange().x
                                                    next.consume()
                                                    pointer = next
                                                }
                                                val from =
                                                    model.tabs.indexOfFirst { it.id == tab.id }
                                                val width =
                                                    coordinates?.size?.width?.toFloat() ?: 1f
                                                if (from >= 0)
                                                    model.moveTab(
                                                        from,
                                                        (from + (distance / width).roundToInt())
                                                            .coerceIn(0, model.tabs.lastIndex),
                                                    )
                                            }
                                        }
                                    }
                                }
                            }
                            .background(if (active) CanvasBackground else Sidebar)
                            .semantics { selected = active }
                            .combinedClickable(
                                role = Role.Tab,
                                onLongClick = {
                                    context =
                                        tab.id to
                                            coordinates?.localToWindow(
                                                Offset(
                                                    0f,
                                                    coordinates?.size?.height?.toFloat() ?: 0f,
                                                ),
                                            )
                                },
                                onClick = {
                                    model.tabs
                                        .indexOfFirst { it.id == tab.id }
                                        .takeIf { it >= 0 }
                                        ?.let(model::switchTab)
                                },
                            ),
                    ) {
                        Row(
                            Modifier.weight(1f).padding(start = 12.dp, end = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                null,
                                Modifier.size(15.dp),
                                tint = if (active) SelectedInk else Muted,
                            )
                            Text(
                                model.title(tab.location),
                                Modifier.weight(1f).padding(horizontal = 8.dp),
                                fontSize = 11.sp,
                                color = if (active) Ink else Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = {
                                    model.tabs
                                        .indexOfFirst { it.id == tab.id }
                                        .takeIf { it >= 0 }
                                        ?.let(model::closeTab)
                                },
                                Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    "Close ${model.title(tab.location)} tab",
                                    Modifier.size(13.dp),
                                    tint = Muted,
                                )
                            }
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    if (active) SelectedInk
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                ),
                        )
                    }
                    VerticalDivider(color = Border, thickness = .5.dp)
                }
            }
        }
        ToolButton(Icons.Outlined.Add, "New tab", { model.newTab() })
    }
    HorizontalDivider(color = Border, thickness = .5.dp)
    context?.let { (id, point) ->
        val index = model.tabs.indexOfFirst { it.id == id }
        if (index >= 0)
            CursorMenu(point, { context = null }, "tab-context-menu") {
                fun run(block: () -> Unit) {
                    context = null
                    block()
                }
                ContextItem("New tab", Icons.Outlined.Add) { run { model.newTab() } }
                ContextItem("Duplicate tab", Icons.Outlined.FileCopy) {
                    run { model.duplicateTab(index) }
                }
                ContextItem(
                    "Reopen closed tab",
                    Icons.Outlined.Restore,
                    model.canReopenTab,
                    "Ctrl Shift T",
                ) {
                    run(model::reopenTab)
                }
                HorizontalDivider(color = Border)
                ContextItem("Move tab left", Icons.Outlined.ChevronLeft, index > 0) {
                    run { model.moveTab(index, index - 1) }
                }
                ContextItem(
                    "Move tab right",
                    Icons.Outlined.ChevronRight,
                    index < model.tabs.lastIndex,
                ) {
                    run { model.moveTab(index, index + 1) }
                }
                HorizontalDivider(color = Border)
                ContextItem("Close other tabs", Icons.Outlined.Tab, model.tabs.size > 1) {
                    run { model.closeOtherTabs(index) }
                }
                ContextItem("Close tab", Icons.Outlined.Close, shortcut = "Ctrl W") {
                    run { model.closeTab(index) }
                }
            }
    }
}
