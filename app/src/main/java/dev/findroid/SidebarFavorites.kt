@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class FavoriteAvailability(val loading: Boolean = true, val problem: String? = null)

@Composable
internal fun FavoritesSidebar(model: FinderModel, navigate: (String) -> Unit, grant: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Favorites", Modifier.weight(1f).padding(start = 12.dp), color = Muted,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag("edit-sidebar")) { Text("Edit", fontSize = 12.sp) }
    }
    FavoritesDropArea(model)
    model.sidebarError?.let {
        Text("Sidebar setup couldn't be read. Original data has been kept. $it",
            Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    model.sidebar.builtins.filterNot { it in model.sidebar.hidden }.forEach { builtin ->
        val path = when (builtin) {
            SidebarBuiltin.RECENTS -> "@recent"
            SidebarBuiltin.WORKSPACE -> model.workspace.path
            else -> File(model.deviceRoot, builtin.directory!!).path
        }
        val icon = when (builtin) {
            SidebarBuiltin.RECENTS -> Icons.Outlined.Schedule
            SidebarBuiltin.WORKSPACE -> Icons.Outlined.Workspaces
            SidebarBuiltin.DOCUMENTS -> Icons.Outlined.Description
            SidebarBuiltin.DOWNLOADS -> Icons.Outlined.Download
            SidebarBuiltin.PICTURES -> Icons.Outlined.Image
            SidebarBuiltin.MOVIES -> Icons.Outlined.Movie
            SidebarBuiltin.MUSIC -> Icons.Outlined.MusicNote
        }
        val accessible = builtin.directory == null || model.hasAccess
        SidebarItem(builtin.label, icon, model.location == path, enabled = accessible,
            modifier = Modifier.testTag("builtin-${builtin.name}").folderDropTarget(model, path)) {
            if (accessible) navigate(path) else grant()
        }
    }
    val names = model.sidebar.favorites.groupingBy { model.favoriteTitle(it.path) }.eachCount()
    @Composable fun shortcuts(group: String?) {
        model.sidebar.favorites.filter { it.group == group }.forEach { shortcut ->
            key(shortcut.id) {
                FavoriteInsertion(model, shortcut.id, group)
                FavoriteRow(model, shortcut, names[model.favoriteTitle(shortcut.path)]!! > 1, navigate)
            }
        }
        FavoriteInsertion(model, null, group)
    }
    shortcuts(null)
    model.sidebar.groups.forEach { group ->
        key(group.id) {
            var menu by remember { mutableStateOf(false) }
            var rename by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().combinedClickable(
                onClick = { model.updateFavoriteGroup(group.id, toggle = true) },
                onLongClick = { menu = true },
            ).sidebarSecondaryClick { menu = true }.semantics {
                stateDescription = if (group.collapsed) "Collapsed" else "Expanded"
            }, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (group.collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                    null, Modifier.padding(start = 8.dp).size(16.dp), tint = Muted)
                Text(group.name, Modifier.weight(1f).padding(start = 4.dp), fontSize = 12.sp,
                    color = Ink, fontWeight = FontWeight.SemiBold)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreHoriz, "Options for group ${group.name}", Modifier.size(18.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename group…") }, onClick = { menu = false; rename = true })
                        DropdownMenuItem(text = { Text("Remove group; keep shortcuts") }, onClick = {
                            menu = false; model.removeFavoriteGroup(group.id)
                        })
                    }
                }
            }
            if (!group.collapsed) shortcuts(group.id)
            else FavoriteInsertion(model, null, group.id)
            if (rename) SidebarNameDialog("Rename group", group.name, false, { rename = false }) {
                model.updateFavoriteGroup(group.id, name = it)
            }
        }
    }
    if (editing) SidebarEditorDialog(model) { editing = false }
}

@Composable
private fun FavoriteInsertion(model: FinderModel, before: String?, group: String?) {
    Box(Modifier.fillMaxWidth().height(8.dp).testTag("favorite-insert-${before ?: "end"}-${group ?: "none"}")
        .favoriteInsertionTarget(model, before, group))
}

@Composable
private fun FavoriteRow(model: FinderModel, shortcut: FavoriteShortcut, duplicate: Boolean, navigate: (String) -> Unit) {
    val path = shortcut.path
    val name = model.favoriteTitle(path)
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var repair by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var recheck by remember { mutableIntStateOf(0) }
    val control = remember(path, model.hasAccess, model.connectedLocations, model.loading, model.listingRefreshRevision, recheck) { ListingControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    val availability by produceState(FavoriteAvailability(), control) {
        value = FavoriteAvailability()
        value = withContext(Dispatchers.IO) {
            try {
                check(model.allowedPath(path)) { "Storage access is missing, or this folder was moved." }
                check(model.documents.read(path, control).folder) { "The saved location is no longer a folder." }
                FavoriteAvailability(false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FavoriteAvailability(false, e.message ?: "This folder is unavailable.")
            }
        }
    }
    fun open(action: () -> Unit) {
        if (availability.loading) model.message = "Checking this folder…"
        else if (availability.problem != null) unavailable = true
        else action()
    }
    val subtitle = when {
        availability.problem != null -> "Unavailable · options to repair"
        duplicate -> model.displayPath(path)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalFinderDensity.current.rowHeight)
            .testTag("favorite-$path").folderDropTarget(model, path)
            .clip(RoundedCornerShape(6.dp))
            .background(if (model.location == path) SelectionSurface else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = { open { navigate(path) } },
                onLongClickLabel = "Options for $name", onLongClick = {
                    if (model.windowState.iconDragGesture == null) menu = true
                })
            .sidebarSecondaryClick { menu = true }
            .semantics {
                selected = model.location == path
                stateDescription = if (availability.problem != null) "Unavailable" else if (availability.loading) "Checking folder" else "Folder shortcut"
            }.padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).testTag("favorite-drag-${shortcut.id}").favoriteDragSource(model, shortcut)
            .semantics { contentDescription = "Drag favorite $name to reorder" }, contentAlignment = Alignment.Center) {
            Icon(if (availability.problem != null) Icons.Outlined.FolderOff else Icons.Outlined.Folder,
                null, Modifier.size(18.dp), tint = if (availability.problem != null) Muted else Blue)
        }
        Column(Modifier.weight(1f).padding(start = 6.dp, top = 5.dp, bottom = 5.dp)) {
            Text(name, color = Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier
                .size(if (LocalFinderDensity.current.touch) 48.dp else 32.dp).testTag("favorite-menu-${shortcut.id}")) {
                Icon(Icons.Outlined.MoreHoriz, "Options for $name", Modifier.size(18.dp), tint = Muted)
            }
            DropdownMenu(menu, { menu = false }, modifier = Modifier.testTag("favorite-options")) {
                Text(model.displayPath(path), Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { menu = false; open { model.newTab(path) } })
                DropdownMenuItem(text = { Text("Open in other pane") }, onClick = {
                    menu = false; open {
                        if (!model.dualPane) model.toggleDualPane()
                        model.otherPane.navigate(path); model.otherPane.activatePane()
                    }
                })
                DropdownMenuItem(text = { Text("Reveal original folder") }, onClick = { menu = false; open { model.revealFavorite(path) } })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Rename sidebar label…") }, onClick = { menu = false; rename = true })
                val peers = model.sidebar.favorites.filter { it.group == shortcut.group }
                val index = peers.indexOfFirst { it.id == shortcut.id }
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = {
                    menu = false; model.placeFavorite(shortcut.id, peers[index - 1].id, shortcut.group)
                })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index >= 0 && index < peers.lastIndex, onClick = {
                    menu = false; model.placeFavorite(shortcut.id, peers.getOrNull(index + 2)?.id, shortcut.group)
                })
                if (shortcut.group != null) DropdownMenuItem(text = { Text("Move out of group") }, onClick = {
                    menu = false; model.placeFavorite(shortcut.id, null, null)
                })
                model.sidebar.groups.filterNot { it.id == shortcut.group }.forEach { group ->
                    DropdownMenuItem(text = { Text("Move to ${group.name}") }, onClick = {
                        menu = false; model.placeFavorite(shortcut.id, null, group.id)
                    })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Repair shortcut…") }, onClick = { menu = false; repair = true })
                if (availability.problem != null) DropdownMenuItem(text = { Text("Reconnect storage…") }, onClick = {
                    menu = false; model.storageRequested = true
                })
                DropdownMenuItem(text = { Text("Remove shortcut") }, onClick = { menu = false; model.removeFavorite(shortcut.id) })
            }
        }
    }
    if (rename) SidebarNameDialog("Rename sidebar label", shortcut.label ?: name, true, { rename = false }) {
        model.labelFavorite(shortcut.id, it)
    }
    if (unavailable) AlertDialog(onDismissRequest = { unavailable = false },
        title = { Text("Folder unavailable") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text(model.displayPath(path), Modifier.padding(vertical = 8.dp), fontSize = 13.sp)
            Text(availability.problem.orEmpty())
            Text("Reconnect its storage or choose the folder's new location. Your original folder is never changed.", Modifier.padding(top = 8.dp))
            TextButton(onClick = { unavailable = false; repair = true }) { Text("Repair shortcut…") }
            TextButton(onClick = { unavailable = false; recheck++ }) { Text("Check again") }
            TextButton(onClick = { unavailable = false; model.removeFavorite(shortcut.id) }) { Text("Remove shortcut") }
        } },
        confirmButton = { TextButton(onClick = { unavailable = false; model.storageRequested = true }) { Text("Reconnect storage…") } },
        dismissButton = { TextButton(onClick = { unavailable = false }) { Text("Close") } },
    )
    if (repair) DestinationDialog(model, false, titleOverride = "Choose replacement for $name", confirmLabel = "Use this folder",
        requireWritable = false, onDestination = { model.repairFavorite(shortcut.id, it) }, dismiss = { repair = false })
}

@Composable
private fun SidebarNameDialog(title: String, initial: String, allowDefault: Boolean, dismiss: () -> Unit, save: (String) -> Boolean) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column {
            OutlinedTextField(text, { text = it; error = null }, label = { Text("Name") }, singleLine = true,
                isError = error != null, modifier = Modifier.testTag("sidebar-name"))
            Text(if (allowDefault) "Only this shortcut's label changes. Leave blank to use the folder name." else "Folders stay in their original locations.",
                Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            val valid = runCatching { if (!allowDefault || text.isNotBlank()) libraryName(text, if (allowDefault) 80 else 48) }.exceptionOrNull()
            if (valid != null) error = valid.message
            else if (save(text)) dismiss() else error = "Couldn't save. Check the sidebar message."
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SidebarEditorDialog(model: FinderModel, dismiss: () -> Unit) {
    var groupName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Edit sidebar") }, modifier = Modifier.testTag("sidebar-editor"),
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            Text("Built-in locations", fontWeight = FontWeight.SemiBold)
            Text("Choose which locations appear in Favorites, then set their order.", fontSize = 12.sp, color = Muted)
            model.sidebar.builtins.forEachIndexed { index, builtin ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(builtin !in model.sidebar.hidden, { show -> model.editSidebar {
                        it.copy(hidden = if (show) it.hidden - builtin else it.hidden + builtin)
                    } }, Modifier.semantics { contentDescription = "Show ${builtin.label}" })
                    Text(builtin.label, Modifier.weight(1f), fontSize = 13.sp)
                    IconButton(enabled = index > 0, onClick = { model.editSidebar {
                        it.copy(builtins = it.builtins.toMutableList().apply { java.util.Collections.swap(this, index, index - 1) })
                    } }) { Icon(Icons.Outlined.ArrowUpward, "Move ${builtin.label} up", Modifier.size(18.dp)) }
                    IconButton(enabled = index < model.sidebar.builtins.lastIndex, onClick = { model.editSidebar {
                        it.copy(builtins = it.builtins.toMutableList().apply { java.util.Collections.swap(this, index, index + 1) })
                    } }) { Icon(Icons.Outlined.ArrowDownward, "Move ${builtin.label} down", Modifier.size(18.dp)) }
                }
            }
            TextButton(onClick = { model.editSidebar { it.copy(builtins = SidebarBuiltin.entries.toList(), hidden = emptySet()) } }) {
                Text("Restore built-in defaults")
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Favorite groups", fontWeight = FontWeight.SemiBold)
            Text("Move shortcuts into a group from their Options menu, or drop folders at a group's insertion line.", fontSize = 12.sp, color = Muted)
            OutlinedTextField(groupName, { groupName = it; error = null }, label = { Text("New group name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("favorite-group-name"))
            TextButton(onClick = {
                val failure = runCatching { libraryName(groupName) }.exceptionOrNull()
                if (failure != null) error = failure.message
                else if (model.addFavoriteGroup(groupName)) groupName = ""
            }) { Text("Create group") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Hold a shortcut's folder icon to reorder it. Its Options menu also has Move up and Move down.", fontSize = 12.sp, color = Muted)
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
}

private fun Modifier.sidebarSecondaryClick(open: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(open)
    pointerInput(Unit) {
        awaitPointerEventScope {
            var secondary = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    secondary = true; event.changes.forEach { it.consume() }
                } else if (secondary && event.type == PointerEventType.Release) {
                    secondary = false; event.changes.forEach { it.consume() }; current()
                }
            }
        }
    }
}
