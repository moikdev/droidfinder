package dev.droidfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

@Composable
private fun WorkspaceUtility(
    title: String,
    subtitle: String,
    tag: String,
    dismiss: () -> Unit,
    content: @Composable ColumnScope.(androidx.compose.ui.unit.Dp) -> Unit,
) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val limit = (maxHeight - 185.dp).coerceIn(100.dp, 480.dp)
            Surface(
                Modifier.widthIn(max = 620.dp).fillMaxWidth().testTag(tag),
                shape = RoundedCornerShape(14.dp),
                color = CanvasBackground,
                shadowElevation = 16.dp,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                        )
                        ToolButton(Icons.Outlined.Close, "Close $title", dismiss)
                    }
                    Text(
                        subtitle,
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = Muted,
                    )
                    content(limit)
                }
            }
        }
    }
}

@Composable
fun TabOverviewDialog(model: FinderModel, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val scale = LocalDensity.current.density
    WorkspaceUtility(
        "Tabs",
        "${model.tabs.size} open · Drag the handle to reorder",
        "tab-overview",
        dismiss,
    ) { limit ->
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("Find a tab") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = limit),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(
                model.tabs.filter {
                    query.isBlank() || model.displayPath(it.location).contains(query, true)
                },
                key = { it.id },
            ) { tab ->
                var drag by remember { mutableFloatStateOf(0f) }
                val active = tab.id == model.tabs[model.activeTab].id
                Row(
                    Modifier.fillMaxWidth()
                        .height(72.dp)
                        .testTag("overview-${tab.id}")
                        .graphicsLayer { translationY = drag }
                        .background(if (active) SelectionSurface else CanvasBackground)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .semantics {
                                contentDescription = "Reorder ${model.title(tab.location)} tab"
                            }
                            .pointerInput(tab.id, query) {
                                if (query.isNotBlank()) return@pointerInput
                                detectDragGestures(
                                    onDragCancel = { drag = 0f },
                                    onDragEnd = {
                                        val from = model.tabs.indexOfFirst { it.id == tab.id }
                                        if (from >= 0)
                                            model.moveTab(
                                                from,
                                                (from + (drag / (72f * scale)).roundToInt())
                                                    .coerceIn(model.tabs.indices),
                                            )
                                        drag = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    drag += amount.y
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.DragIndicator,
                            null,
                            Modifier.size(18.dp),
                            tint = if (query.isBlank()) Muted else Muted.copy(alpha = .3f),
                        )
                    }
                    Column(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                model.activatePane()
                                model.tabs
                                    .indexOfFirst { it.id == tab.id }
                                    .takeIf { it >= 0 }
                                    ?.let(model::switchTab)
                                dismiss()
                            }
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            model.title(tab.location),
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) SelectedInk else Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            model.displayPath(tab.location),
                            fontSize = 11.sp,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ToolButton(
                        Icons.Outlined.PushPin,
                        if (tab.pinned) "Unpin ${model.title(tab.location)} tab"
                        else "Pin ${model.title(tab.location)} tab",
                        {
                            model.tabs
                                .indexOfFirst { it.id == tab.id }
                                .takeIf { it >= 0 }
                                ?.let(model::togglePinTab)
                        },
                        active = tab.pinned,
                        compact = true,
                    )
                    if (!tab.pinned)
                        ToolButton(
                            Icons.Outlined.Close,
                            "Close ${model.title(tab.location)} from overview",
                            {
                                model.tabs
                                    .indexOfFirst { it.id == tab.id }
                                    .takeIf { it >= 0 }
                                    ?.let(model::closeTab)
                            },
                            compact = true,
                        )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    model.newTab(atEnd = true)
                    dismiss()
                },
            ) {
                Text("New tab")
            }
        }
    }
}

@Composable
fun SavedWorkspacesDialog(model: FinderModel, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<SavedWorkspace?>(null) }
    var remove by remember { mutableStateOf<SavedWorkspace?>(null) }
    var update by remember { mutableStateOf<SavedWorkspace?>(null) }
    WorkspaceUtility(
        "Workspaces",
        "Save both panes, tabs and view settings",
        "saved-workspaces",
        dismiss,
    ) { limit ->
        if (model.workspaceSaving)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Blue)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                name,
                {
                    name = it
                    problem = null
                },
                label = { Text("Workspace name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                isError = problem != null,
            )
            TextButton(
                onClick = {
                    if (model.saveWorkspace(name)) {
                        name = ""
                        problem = null
                    } else problem = model.message
                },
                enabled =
                    name.isNotBlank() && model.workspaceError == null && !model.workspaceSaving,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Save current")
            }
        }
        (problem ?: model.workspaceError)?.let {
            Text(
                it,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }
        if (model.workspaceError != null && model.session.workspaceStore.error == null)
            TextButton(
                onClick = model::retryWorkspaceSave,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text("Retry saving")
            }
        if (model.savedWorkspaces.isEmpty())
            Text(
                "Your saved layouts will appear here.",
                Modifier.padding(20.dp),
                color = Muted,
                fontSize = 12.sp,
            )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = limit),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(model.savedWorkspaces, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth()
                        .testTag("workspace-${item.id}")
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (item.dual) Icons.Outlined.VerticalSplit else Icons.Outlined.Tab,
                        null,
                        Modifier.size(22.dp),
                        tint = Blue,
                    )
                    Column(
                        Modifier.weight(1f)
                            .padding(horizontal = 12.dp)
                            .clickable {
                                model.openWorkspace(item.id)
                                dismiss()
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            item.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${item.tabCount} tabs · ${if(item.dual) "Two panes" else "One pane"}",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                    ToolButton(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        "Open workspace ${item.name}",
                        {
                            model.openWorkspace(item.id)
                            dismiss()
                        },
                        compact = true,
                    )
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        ToolButton(
                            Icons.Outlined.MoreHoriz,
                            "Manage workspace ${item.name}",
                            { menu = true },
                            compact = true,
                        )
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Update from current layout") },
                                onClick = {
                                    menu = false
                                    update = item
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menu = false
                                    rename = item
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                onClick = {
                                    menu = false
                                    remove = item
                                },
                            )
                        }
                    }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp),
                    color = Border,
                    thickness = .5.dp,
                )
            }
        }
    }
    rename?.let { item ->
        NameDialog(
            "Rename workspace",
            item.name,
            "Rename",
            { rename = null },
            validation = { model.workspaceNameError(it, item.id) },
        ) { title ->
            if (model.renameWorkspace(item.id, title)) rename = null else problem = model.message
        }
    }
    (remove ?: update)?.let { item ->
        val deleting = remove != null
        AlertDialog(
            onDismissRequest = {
                remove = null
                update = null
            },
            title = { Text(if (deleting) "Remove ${item.name}?" else "Update ${item.name}?") },
            text = {
                Text(
                    if (deleting) "This removes the saved layout. Your files stay in their folders."
                    else
                        "Replace this saved workspace with the current panes, tabs and view settings?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (deleting) model.removeWorkspace(item.id)
                        else model.saveWorkspace(item.name, item.id)
                        remove = null
                        update = null
                    },
                ) {
                    Text(if (deleting) "Remove" else "Update")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        remove = null
                        update = null
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
