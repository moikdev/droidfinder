@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.droidfinder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val FinderColors = lightColorScheme(primary = Blue, onPrimary = Color.White, background = Color.White, surface = Color.White, onSurface = Ink, onSurfaceVariant = Muted, outline = Border, surfaceContainer = Sidebar)

@Composable fun FinderApp(model: FinderModel, onGrantAccess: () -> Unit, onOpen: (Entry) -> Unit, onShare: (List<Entry>) -> Unit) {
    MaterialTheme(colorScheme = FinderColors, typography = Typography(bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))) {
        var dialog by remember { mutableStateOf<String?>(null) }
        var quickLook by remember { mutableStateOf<Entry?>(null) }
        var menu by remember { mutableStateOf(false) }
        var sortMenu by remember { mutableStateOf(false) }
        var searchActive by remember { mutableStateOf(false) }
        var searchFocused by remember { mutableStateOf(false) }
        val searchFocus = remember { FocusRequester() }
        val rootFocus = remember { FocusRequester() }
        val snackbar = remember { SnackbarHostState() }
        val clipboard = LocalClipboardManager.current
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val selected = model.selectedEntries
        val open: (Entry) -> Unit = { if (it.folder) model.navigate(it.path) else quickLook = it }
        val action: (String) -> Unit = { command ->
            menu = false
            when (command) {
                "open" -> model.focused?.let(open)
                "external" -> model.focused?.let(onOpen)
                "quicklook" -> quickLook = model.focused
                "new" -> dialog = "new"
                "rename" -> dialog = "rename"
                "copy" -> model.copySelection(false)
                "cut" -> model.copySelection(true)
                "paste" -> model.paste()
                "duplicate" -> model.duplicate()
                "trash" -> model.trash()
                "restore" -> model.restore()
                "undo" -> model.undoTrash()
                "delete" -> dialog = "delete"
                "empty" -> dialog = "empty"
                "compress" -> model.compress()
                "extract" -> model.extract()
                "share" -> onShare(selected)
                "info" -> if (model.focused != null) dialog = "info"
                "path" -> dialog = "path"
                "copyPath" -> { model.focused?.let { clipboard.setText(AnnotatedString(it.path)) }; model.message = "Path copied" }
                "favorite" -> model.focused?.takeIf { it.folder }?.let { model.favorite(it.file) }
                "tab" -> model.focused?.takeIf { it.folder }?.let { model.newTab(it.path) }
                "about" -> dialog = "about"
            }
        }
        LaunchedEffect(model.message) { model.message?.let { text ->
            val undo = model.canUndoTrash && text.endsWith("moved to Trash")
            val result = snackbar.showSnackbar(text, actionLabel = if (undo) "Undo" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) model.undoTrash()
            if (model.message == text) model.message = null
        } }
        LaunchedEffect(searchActive) { if (searchActive) searchFocus.requestFocus() }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }
        BackHandler(enabled = quickLook != null || model.selected.isNotEmpty() || model.tabs[model.activeTab].index > 0) {
            when { quickLook != null -> quickLook = null; model.selected.isNotEmpty() -> model.clearSelection(); else -> model.back() }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFFF7F8FA)).windowInsetsPadding(WindowInsets.safeDrawing).focusRequester(rootFocus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || dialog != null || model.busy != null) false
            else if (event.isCtrlPressed || event.isMetaPressed) {
                when (event.key) {
                    Key.F -> { searchActive = true; true }
                    Key.L -> { dialog = "path"; true }
                    Key.T -> { model.newTab(); true }
                    Key.W -> { model.closeTab(model.activeTab); true }
                    Key.N -> { if (model.directory != null) dialog = "new"; true }
                    Key.A -> if (!searchFocused) { model.selectAll(); true } else false
                    Key.C -> if (!searchFocused) { model.copySelection(false); true } else false
                    Key.X -> if (!searchFocused && !model.isTrash) { model.copySelection(true); true } else false
                    Key.V -> if (!searchFocused && !model.isTrash) { model.paste(); true } else false
                    Key.D -> if (!searchFocused && !model.isTrash) { model.duplicate(); true } else false
                    Key.Z -> if (!searchFocused && model.canUndoTrash) { model.undoTrash(); true } else false
                    Key.I -> { if (model.focused != null) dialog = "info"; true }
                    Key.Period -> { model.toggleHidden(); true }
                    else -> false
                }
            } else if (event.isAltPressed) {
                when (event.key) { Key.DirectionLeft -> { model.back(); true }; Key.DirectionRight -> { model.forward(); true }; Key.DirectionUp -> { model.up(); true }; else -> false }
            } else if (!searchFocused) when (event.key) {
                Key.Spacebar -> { quickLook = if (quickLook == null) model.focused else null; true }
                Key.Enter -> { model.focused?.let(open); true }
                Key.F2 -> { if (selected.size == 1 && !model.isTrash) dialog = "rename"; true }
                Key.DirectionDown -> { model.stepSelection(1, event.isShiftPressed); true }
                Key.DirectionUp -> { model.stepSelection(-1, event.isShiftPressed); true }
                Key.Delete, Key.Backspace -> { if (model.isTrash) { if (selected.isNotEmpty()) dialog = "delete" } else model.trash(); true }
                Key.Escape -> { model.clearSelection(); quickLook = null; true }
                else -> false
            } else false
        }.focusable()) {
            Row(Modifier.fillMaxSize()) {
                FinderSidebar(model, onGrantAccess, Modifier.width(if (screenWidth >= 1100) 220.dp else 190.dp))
                VerticalDivider(color = Border)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(model.title(), fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (model.location.startsWith("@tag:")) Box(Modifier.padding(start = 10.dp).size(9.dp).background(tagColor(model.title()), CircleShape))
                            }
                            Text(when { model.isTrash -> "Deleted items stay here until you empty Trash"; model.location == "@recent" -> "Your 200 most recently modified files"; model.location.startsWith("@tag:") -> "Files with the ${model.title().lowercase()} tag"; model.directory == model.workspace -> "Your private space · saved on this tablet"; else -> model.directory?.parent?.replace(model.deviceRoot.absolutePath, "Tablet") ?: "" }, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (searchActive || model.query.isNotEmpty()) {
                            OutlinedTextField(model.query, model::updateQuery, Modifier.width(235.dp).height(48.dp).focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused }, singleLine = true, placeholder = { Text("Search this location", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) }, trailingIcon = { IconButton(onClick = { model.updateQuery(""); searchActive = false; rootFocus.requestFocus() }) { Icon(Icons.Outlined.Close, "Close search", Modifier.size(16.dp)) } }, shape = RoundedCornerShape(9.dp), textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                        } else ToolButton(Icons.Outlined.Search, "Search", { searchActive = true })
                        Spacer(Modifier.width(8.dp))
                        ToolButton(Icons.Outlined.Add, "New folder", { action("new") }, enabled = model.directory != null && model.busy == null)
                    }
                    HorizontalDivider(color = Border)
                    Row(Modifier.fillMaxWidth().height(53.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", { model.back() }, enabled = model.tabs[model.activeTab].index > 0)
                        ToolButton(Icons.AutoMirrored.Outlined.ArrowForward, "Forward", model::forward, enabled = model.tabs[model.activeTab].index < model.tabs[model.activeTab].history.lastIndex)
                        ToolButton(Icons.Outlined.ArrowUpward, "Enclosing folder", model::up, enabled = model.directory?.parentFile?.let(model::allowed) == true)
                        Spacer(Modifier.width(12.dp))
                        Row(Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFE9ECF0)).padding(3.dp)) {
                            ViewMode.entries.forEach { mode ->
                                val icon = when (mode) { ViewMode.ICONS -> Icons.Outlined.GridView; ViewMode.LIST -> Icons.AutoMirrored.Outlined.ViewList; ViewMode.COLUMNS -> Icons.Outlined.ViewColumn; ViewMode.GALLERY -> Icons.Outlined.Panorama }
                                ToolButton(icon, "${mode.title} view", { model.changeMode(mode) }, active = model.mode == mode, compact = true)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Box {
                            ToolButton(Icons.AutoMirrored.Outlined.Sort, "Sort and view options", { sortMenu = true })
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                Text("SORT BY", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 10.sp, color = Muted)
                                SortBy.entries.forEach { type -> DropdownMenuItem(text = { Text(type.title + if (model.sort == type) if (model.ascending) " ↑" else " ↓" else "") }, onClick = { model.changeSort(type); sortMenu = false }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Folders first") }, trailingIcon = { if (model.foldersFirst) Icon(Icons.Outlined.Check, null) }, onClick = { model.toggleFoldersFirst(); sortMenu = false })
                                DropdownMenuItem(text = { Text("Show hidden files") }, trailingIcon = { if (model.showHidden) Icon(Icons.Outlined.Check, null) }, onClick = { model.toggleHidden(); sortMenu = false })
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (model.clipboard != null) ToolButton(Icons.Outlined.ContentPaste, "Paste ${model.clipboard!!.files.size} items", model::paste, enabled = model.directory != null)
                        ToolButton(Icons.Outlined.IosShare, "Share", { action("share") }, enabled = selected.isNotEmpty() && !model.isTrash)
                        ToolButton(Icons.Outlined.VerticalSplit, "Toggle preview pane", model::togglePreview, active = model.previewVisible, enabled = screenWidth >= 900)
                        Box {
                            ToolButton(Icons.Outlined.MoreHoriz, "Actions", { menu = true })
                            ActionMenu(menu, { menu = false }, model, action)
                        }
                    }
                    TabStrip(model)
                    if (selected.isNotEmpty()) SelectionBar(model, action)
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.White)) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                when {
                                    model.listingError != null -> EmptyState(Icons.Outlined.FolderOff, "Folder unavailable", model.listingError!!, "Try again", model::refresh)
                                    model.entries.isEmpty() && !model.loading -> {
                                        if (model.directory == model.workspace && model.query.isBlank() && !model.hasAccess) WelcomeState(onGrantAccess, { action("new") })
                                        else EmptyState(if (model.query.isNotEmpty()) Icons.Outlined.SearchOff else if (model.isTrash) Icons.Outlined.DeleteOutline else Icons.Outlined.FolderOpen, if (model.query.isNotEmpty()) "No matching files" else if (model.isTrash) "Trash is empty" else "This folder is empty", if (model.query.isNotEmpty()) "Try another name. Search includes subfolders." else if (model.isTrash) "Items you move to Trash will appear here." else "Create a folder or paste files to get started.", if (model.directory != null && model.query.isEmpty()) "New folder" else null, { action("new") })
                                    }
                                    model.mode == ViewMode.ICONS -> IconView(model, open)
                                    model.mode == ViewMode.LIST -> ListView(model, open) { entry -> if (entry.path !in model.selected) { model.clearSelection(); model.select(entry) }; menu = true }
                                    model.mode == ViewMode.COLUMNS -> ColumnsView(model, open)
                                    model.mode == ViewMode.GALLERY -> GalleryView(model, open)
                                }
                                if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Blue)
                            }
                            if (model.previewVisible && model.mode != ViewMode.GALLERY && screenWidth >= 900) {
                                VerticalDivider(color = Border)
                                PreviewPane(model, { quickLook = it }, onOpen, Modifier.width(if (screenWidth >= 1200) 258.dp else 224.dp))
                            }
                        }
                        if (model.busy != null) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .9f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp); Text(model.busy!!, Modifier.padding(top = 18.dp), color = Ink, fontSize = 14.sp); Text("Keep DroidFinder open until this finishes", Modifier.padding(top = 7.dp), color = Muted, fontSize = 11.sp) }
                        }
                    }
                    PathBar(model) { action("path") }
                    StatusBar(model)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp))
        }

        when (dialog) {
            "new", "rename", "path" -> {
                val kind = dialog!!
                NameDialog(title = when (kind) { "new" -> "New folder"; "rename" -> "Rename item"; else -> "Go to folder" }, initial = when (kind) { "rename" -> model.focused?.name.orEmpty(); "path" -> model.directory?.absolutePath ?: model.deviceRoot.absolutePath; else -> "Untitled folder" }, button = if (kind == "path") "Go" else if (kind == "rename") "Rename" else "Create", onDismiss = { dialog = null }, onConfirm = { value -> if (kind == "path") model.goTo(value) else if (kind == "new") model.createFolder(value) else model.rename(value); dialog = null })
            }
            "delete", "empty" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Delete permanently?") }, text = { Text(if (dialog == "empty") "All items in Trash will be permanently deleted. This cannot be undone." else "${selected.size} selected item(s) will be permanently deleted. This cannot be undone.") }, confirmButton = { TextButton(onClick = { if (dialog == "empty") model.emptyTrash() else model.deleteForever(); dialog = null }) { Text("Delete permanently", color = Color(0xFFCD4545)) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
            "info" -> model.focused?.let { entry -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Get info") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { FileGlyph(entry, 70.dp); Spacer(Modifier.height(20.dp)); Metadata(entry, model); MetaInfoText("Full path", entry.path) } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } }) }
            "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Made for your files.") }, text = { Column { Text("DroidFinder 0.1 · Tablet edition", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp)); Text("A native Android file explorer inspired by Finder. Your files and tags stay on this device. No account. No network access."); Spacer(Modifier.height(16.dp)); Text("Keyboard shortcuts", fontWeight = FontWeight.SemiBold); Text("Ctrl F  Search\nCtrl T / W  New / close tab\nCtrl Shift N or Ctrl N  New folder\nCtrl C / X / V  Copy / cut / paste\nCtrl D  Duplicate\nCtrl L  Go to folder\nCtrl I  Get info\nCtrl .  Show hidden files\nAlt ← / → / ↑  Navigate\nSpace  Quick Look · F2  Rename", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } })
        }
        quickLook?.let { entry ->
            Dialog(onDismissRequest = { quickLook = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxWidth(.82f).fillMaxHeight(.87f), shape = RoundedCornerShape(14.dp), color = Color(0xFFF5F6F8), shadowElevation = 16.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) { FileGlyph(entry, 28.dp); Text(entry.name, Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); if (!entry.folder) TextButton(onClick = { onOpen(entry) }) { Text("Open with app") }; ToolButton(Icons.Outlined.Close, "Close Quick Look", { quickLook = null }) }
                        HorizontalDivider(color = Border)
                        PreviewContent(entry, Modifier.weight(1f).fillMaxWidth().padding(18.dp), large = true)
                        Row(Modifier.fillMaxWidth().height(45.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.kind + if (entry.folder) "" else " · ${formatSize(entry.size)}", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                            val index = model.entries.indexOfFirst { it.path == entry.path }
                            ToolButton(Icons.Outlined.ChevronLeft, "Previous file", { if (index > 0) quickLook = model.entries[index - 1] }, enabled = index > 0)
                            ToolButton(Icons.Outlined.ChevronRight, "Next file", { if (index < model.entries.lastIndex) quickLook = model.entries[index + 1] }, enabled = index >= 0 && index < model.entries.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun FinderSidebar(model: FinderModel, onGrantAccess: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(Sidebar)) {
        Row(Modifier.height(74.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Blue), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.FolderOpen, null, tint = Color.White, modifier = Modifier.size(21.dp)) }
            Column(Modifier.padding(start = 10.dp)) { Text("DroidFinder", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-.4).sp); Text("A place for everything", fontSize = 9.sp, color = Muted, modifier = Modifier.padding(top = 2.dp)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            SidebarHeading("FAVORITES")
            SidebarItem("Recents", Icons.Outlined.Schedule, model.location == "@recent") { model.navigate("@recent") }
            SidebarItem("Workspace", Icons.Outlined.Workspaces, model.location == model.workspace.absolutePath) { model.navigate(model.workspace.absolutePath) }
            listOf("Documents" to Icons.Outlined.Description, "Download" to Icons.Outlined.Download, "Pictures" to Icons.Outlined.Image, "Movies" to Icons.Outlined.Movie, "Music" to Icons.Outlined.MusicNote).forEach { (name, icon) ->
                val folder = File(model.deviceRoot, name)
                SidebarItem(if (name == "Download") "Downloads" else name, icon, model.location == folder.absolutePath, enabled = model.hasAccess) { if (model.hasAccess) model.navigate(folder.absolutePath) else onGrantAccess() }
            }
            model.favorites.forEach { path -> SidebarItem(File(path).name, Icons.Outlined.Folder, model.location == path) { model.navigate(path) } }
            Spacer(Modifier.height(17.dp))
            SidebarHeading("LOCATIONS")
            SidebarItem("This tablet", Icons.Outlined.TabletMac, model.location == model.deviceRoot.absolutePath, badge = if (!model.hasAccess) "Connect" else null) { if (model.hasAccess) model.navigate(model.deviceRoot.absolutePath) else onGrantAccess() }
            model.volumes.forEach { volume -> SidebarItem(volume.name, Icons.Outlined.Usb, model.location == volume.absolutePath) { model.navigate(volume.absolutePath) } }
            SidebarItem("Trash", Icons.Outlined.DeleteOutline, model.isTrash) { model.navigate("@trash") }
            Spacer(Modifier.height(17.dp))
            SidebarHeading("TAGS")
            tagNames.forEach { tag -> SidebarItem(tag, null, model.location == "@tag:$tag", dot = tagColor(tag)) { model.navigate("@tag:$tag") } }
            Spacer(Modifier.height(20.dp))
        }
        val disk by produceState<Pair<Long, Long>?>(null, model.location, model.hasAccess, model.busy) { value = withContext(Dispatchers.IO) { val root = if (model.hasAccess) model.deviceRoot else model.workspace; root.usableSpace to root.totalSpace } }
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Storage, null, Modifier.size(15.dp), tint = Muted); Text(if (model.hasAccess) "Tablet storage" else "Workspace storage", fontSize = 11.sp, color = Ink, modifier = Modifier.padding(start = 8.dp)) }
            disk?.let { (free, total) ->
                LinearProgressIndicator(progress = { if (total > 0) 1f - free.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 7.dp).height(4.dp).clip(CircleShape), color = Blue.copy(alpha = .7f), trackColor = Color(0xFFDBE0E8))
                Text("${formatSize(free)} available", fontSize = 9.sp, color = Muted)
            }
        }
    }
}

@Composable private fun SidebarHeading(text: String) { Text(text, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 7.dp), fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.sp, color = Muted) }
@Composable private fun SidebarItem(text: String, icon: ImageVector?, active: Boolean, enabled: Boolean = true, badge: String? = null, dot: Color? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(35.dp).clip(RoundedCornerShape(6.dp)).background(if (active) Color(0xFFDCE8F9) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (active) Blue else if (enabled) Color(0xFF75859B) else Color(0xFFADB7C4))
        if (dot != null) Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).background(dot, CircleShape)) }
        Text(text, Modifier.weight(1f).padding(start = 10.dp), fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) Color(0xFF225B9D) else if (enabled) Ink else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) Text(badge, color = Blue, fontSize = 9.sp)
    }
}

@Composable fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, active: Boolean = false, compact: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (compact) 34.dp else 38.dp).clip(RoundedCornerShape(5.dp)).background(if (active) Color.White else Color.Transparent)) { Icon(icon, label, Modifier.size(if (compact) 19.dp else 20.dp), tint = if (!enabled) Color(0xFFC3C8D0) else if (active) Blue else Color(0xFF667383)) }
}

@Composable private fun TabStrip(model: FinderModel) {
    Row(Modifier.fillMaxWidth().height(35.dp).background(Color(0xFFEFF1F4)), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            model.tabs.forEachIndexed { index, tab ->
                Column(Modifier.widthIn(min = 120.dp, max = 200.dp).fillMaxHeight().background(if (index == model.activeTab) Color.White else Color.Transparent).clickable { model.switchTab(index) }) {
                    Row(Modifier.weight(1f).padding(start = 13.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (tab.location.startsWith("@")) Icons.Outlined.FolderSpecial else Icons.Outlined.FolderOpen, null, Modifier.size(13.dp), tint = if (index == model.activeTab) Blue else Muted)
                        Text(model.title(tab.location), Modifier.widthIn(max = 120.dp).padding(start = 6.dp, end = 12.dp), fontSize = 10.sp, color = if (index == model.activeTab) Ink else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (model.tabs.size > 1) IconButton(onClick = { model.closeTab(index) }, modifier = Modifier.size(26.dp)) { Icon(Icons.Outlined.Close, "Close ${model.title(tab.location)} tab", Modifier.size(12.dp), tint = Muted) }
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (index == model.activeTab) Blue else Color.Transparent))
                }
                VerticalDivider(color = Border)
            }
        }
        ToolButton(Icons.Outlined.Add, "New tab", { model.newTab() }, compact = true)
    }
}

@Composable private fun SelectionBar(model: FinderModel, action: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(43.dp).background(Color(0xFFF0F6FF)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolButton(Icons.Outlined.Close, "Clear selection", model::clearSelection, compact = true)
        Text("${model.selected.size} selected", Modifier.weight(1f).padding(start = 6.dp), fontSize = 11.sp, color = Blue)
        if (model.isTrash) {
            TextButton(onClick = { action("restore") }) { Text("Restore", fontSize = 11.sp) }
            ToolButton(Icons.Outlined.DeleteForever, "Delete permanently", { action("delete") })
        } else {
            ToolButton(Icons.Outlined.ContentCopy, "Copy", { action("copy") })
            ToolButton(Icons.Outlined.ContentCut, "Cut", { action("cut") })
            if (model.selected.size == 1) ToolButton(Icons.Outlined.DriveFileRenameOutline, "Rename", { action("rename") })
            ToolButton(Icons.Outlined.DeleteOutline, "Move to Trash", { action("trash") })
        }
    }
}

@Composable private fun ActionMenu(expanded: Boolean, onDismiss: () -> Unit, model: FinderModel, action: (String) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        val entries = model.selectedEntries
        if (entries.isEmpty()) {
            if (model.canUndoTrash) MenuAction("Undo move to Trash", Icons.AutoMirrored.Outlined.Undo) { action("undo") }
            MenuAction("New folder", Icons.Outlined.CreateNewFolder, model.directory != null) { action("new") }
            MenuAction("Paste", Icons.Outlined.ContentPaste, model.clipboard != null && model.directory != null) { action("paste") }
            MenuAction("Select all", Icons.Outlined.SelectAll, model.entries.isNotEmpty()) { model.selectAll(); onDismiss() }
            if (model.isTrash) MenuAction("Empty Trash…", Icons.Outlined.DeleteForever, model.entries.isNotEmpty()) { action("empty") }
            MenuAction("Go to folder…", Icons.AutoMirrored.Outlined.DriveFileMove) { action("path") }
        } else if (model.isTrash) {
            MenuAction("Restore", Icons.Outlined.Restore) { action("restore") }
            MenuAction("Copy", Icons.Outlined.ContentCopy) { action("copy") }
            MenuAction("Delete permanently…", Icons.Outlined.DeleteForever) { action("delete") }
        } else {
            if (entries.size == 1) {
                MenuAction(if (entries.first().folder) "Open folder" else "Quick Look", Icons.Outlined.Visibility) { action("open") }
                if (!entries.first().folder) MenuAction("Open with app", Icons.AutoMirrored.Outlined.OpenInNew) { action("external") }
                if (entries.first().folder) MenuAction("Open in new tab", Icons.Outlined.Tab) { action("tab") }
                MenuAction("Get info", Icons.Outlined.Info) { action("info") }
                MenuAction("Rename…", Icons.Outlined.DriveFileRenameOutline) { action("rename") }
                if (entries.first().folder) MenuAction(if (entries.first().path in model.favorites) "Remove from favorites" else "Add to favorites", Icons.Outlined.StarOutline) { action("favorite") }
            }
            HorizontalDivider()
            MenuAction("Copy", Icons.Outlined.ContentCopy) { action("copy") }
            MenuAction("Cut", Icons.Outlined.ContentCut) { action("cut") }
            MenuAction("Duplicate", Icons.Outlined.FileCopy) { action("duplicate") }
            MenuAction("Compress", Icons.Outlined.FolderZip) { action("compress") }
            if (entries.size == 1 && entries.first().extension == "zip") MenuAction("Extract archive", Icons.Outlined.Unarchive) { action("extract") }
            MenuAction("Share…", Icons.Outlined.IosShare) { action("share") }
            if (entries.size == 1) MenuAction("Copy full path", Icons.Outlined.Link) { action("copyPath") }
            MenuAction("Move to Trash", Icons.Outlined.DeleteOutline) { action("trash") }
            HorizontalDivider()
            Text("TAGS", Modifier.padding(start = 14.dp, top = 10.dp), color = Muted, fontSize = 10.sp)
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                tagNames.forEach { tag -> IconButton(onClick = { model.tag(tag); onDismiss() }, modifier = Modifier.size(30.dp)) { Box(Modifier.size(15.dp).background(tagColor(tag), CircleShape)) { if (entries.all { model.tags[it.path] == tag }) Icon(Icons.Outlined.Check, "Tag $tag", tint = Color.White, modifier = Modifier.size(14.dp)) else Box(Modifier.fillMaxSize().semanticsLabel("Tag $tag")) } } }
                IconButton(onClick = { model.tag(null); onDismiss() }, Modifier.size(30.dp)) { Icon(Icons.Outlined.Close, "Remove tag", Modifier.size(15.dp), tint = Muted) }
            }
        }
        HorizontalDivider()
        MenuAction("About & keyboard shortcuts", Icons.Outlined.Keyboard) { action("about") }
    }
}

@Composable private fun MenuAction(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) { DropdownMenuItem(text = { Text(text, fontSize = 12.sp) }, leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) }, enabled = enabled, onClick = onClick) }
