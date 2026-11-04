package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LauncherChoice(
    val id: String,
    val title: String,
    val detail: String,
    val command: String? = null,
    val path: String? = null,
    val reason: String? = null,
    val keywords: String = "",
) {
    fun matches(query: String): Boolean {
        val haystack = "$title $detail $keywords ${path.orEmpty()}".lowercase(Locale.ROOT)
        return query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).all { it in haystack }
    }
}

private fun launcherCommands(model: FinderModel): List<LauncherChoice> {
    val selected = model.actionEntries
    val busy = model.busy?.let { "Wait for $it to finish." }
    val selection = if (selected.isEmpty()) "Select a file or folder first." else null
    fun command(id: String, title: String, detail: String, reason: String? = null, keywords: String = "") =
        LauncherChoice("action-$id", title, detail, command = id, reason = model.commandProblem(id)?.reason ?: reason, keywords = keywords)
    return listOf(
        command("new", "New folder", "Create in the current folder", keywords = "create directory"),
        command("newText", "New text file", "Create a note in the current folder", keywords = "note document"),
        command("rename", "Rename selection", "Review before changing file names", keywords = "batch name"),
        command("copyTo", "Copy to folder…", "Choose the full destination", keywords = "transfer duplicate"),
        command("moveTo", "Move to folder…", "Choose the full destination", keywords = "transfer cut"),
        command("info", "Get file information", "Review the selected files", selection, "properties size metadata"),
        command("compareFiles", "Compare files…", "Read-only text differences or linked side-by-side images", keywords = "diff changes photos pictures compare text"),
        command("hidden", if (model.showHidden) "Hide hidden files" else "Show hidden files", "Change the current view", keywords = "dot files visibility"),
        command("filters", "Search filters…", "Names, types, dates, sizes and tags", keywords = "find criteria"),
        command("searchIndex", "Search index…", "Manage optional local snapshots, freshness and cache storage", keywords = "find metadata index refresh pause rebuild delete"),
        command("searchLocations", "Search locations…", "Choose folders and review unavailable storage", keywords = "roots scope multiple folders providers"),
        command("recentSearches", "Recent searches…", "Reuse queries and filters, pause recording or clear history", keywords = "find suggestions history recent query"),
        command("tags", "Tags…", "Organize files with named tags", keywords = "labels colors"),
        command("collections", "Collections…", "Manage cross-folder references", keywords = "library organization"),
        command("recentActions", "Recent actions…", "Undo, Redo and reasons recovery stopped in this session", keywords = "undo redo history recover changed files"),
        command("transfers", "Transfers and recovery…", "Review recent jobs and interrupted operations", keywords = "queue history retry"),
        command("recoveryInbox", "Recovery inbox…", "Review interrupted transfers, saved copies, originals and run records", keywords = "recover partial backup conflict interrupted journal"),
        command("undo", "Undo ${model.undoLabel ?: "recent action"}", "Recovery checks protect files changed later", if (model.undoLabel == null) "There is no eligible action to undo in this session." else busy),
        command("dualPane", if (model.dualPane) "Close second pane" else "Open second pane", "Compare folders side by side", keywords = "split two panels"),
        command("workspaces", "Saved workspaces…", "Save or restore your tablet layout", keywords = "tabs session"),
        command("availableActions", "Available actions…", "Explain read, create, rename, delete and Trash support for this folder or selection", keywords = "permissions capability provider read only unavailable write access"),
        command("storage", "Storage locations…", "Review permissions, capacity and reconnect options", keywords = "grant provider usb drive unavailable"),
        command("setup", "Back up or restore setup…", "Review Favorites, tags, workspaces and root mappings", keywords = "backup import export migrate metadata preferences"),
        command("settings", "Preferences…", "Browsing, sidebar and advanced choices", keywords = "settings density theme appearance"),
        command("shortcuts", "Keyboard shortcuts…", "Search the active keyboard guide", keywords = "help controls"),
    )
}

/** One named surface, reachable from More or the keyboard, without adding toolbar icons. */
@Composable
internal fun ExplorerLauncherDialog(model: FinderModel, foldersOnly: Boolean, action: (String) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var onlyFolders by remember { mutableStateOf(foldersOnly) }
    var selected by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf<CommandRecovery?>(null) }
    val focus = remember { FocusRequester() }
    var fieldPlaced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val roots = remember(model.sidebar, model.tabs, model.entries, model.connectedLocations, model.hasAccess) {
        buildList<Pair<String, String>> {
            add(model.workspace.path to "Workspace")
            if (model.hasAccess) add(model.deviceRoot.path to "Tablet storage")
            model.connectedLocations.forEach { add(it.uri to it.name) }
            model.sidebar.favorites.forEach { add(it.path to model.favoriteTitle(it.path)) }
            model.entries.filter { it.folder }.forEach { add(it.path to it.name) }
            model.session.panes.flatMap { it.tabs }.flatMap { it.history }.asReversed()
                .filterNot { it.startsWith("@") }.take(40).forEach { add(it to model.favoriteTitle(it)) }
        }.distinctBy { it.first }
    }
    val folders by produceState<List<LauncherChoice>>(emptyList(), roots, model.location) {
        value = withContext(Dispatchers.IO) {
            val parents = mutableListOf<Pair<String, String>>()
            var path = model.folderPath
            val seen = mutableSetOf<String>()
            while (path != null && seen.add(path) && parents.size < 12) {
                parents += path to model.favoriteTitle(path)
                path = model.parentPath(path)
            }
            (parents + roots).distinctBy { it.first }.map { (path, title) ->
                LauncherChoice("folder-$path", title, model.displayPath(path), path = path,
                    keywords = if (parents.any { it.first == path }) "parent breadcrumb current" else "favorite recent location")
            }
        }
    }
    val exact by produceState<LauncherChoice?>(null, query, folders) {
        value = null
        val input = query.trim()
        if (libraryPath(input) && folders.none { it.path == input }) value = withContext(Dispatchers.IO) {
            runCatching {
                check(model.allowedPath(input))
                val entry = model.documents.read(input)
                check(entry.folder)
                LauncherChoice("folder-$input", entry.name, model.displayPath(input), path = input)
            }.getOrNull()
        }
    }
    val choices = (if (onlyFolders) emptyList() else launcherCommands(model)) + folders
    val shown = (listOfNotNull(exact) + choices.filter { it.matches(query) }).take(100)
    fun choose(item: LauncherChoice) {
        if (opening) return
        val problem = item.command?.let { model.commandProblem(it) }
        if (problem != null || item.reason != null) {
            error = problem?.reason ?: item.reason
            recovery = problem?.recovery
            return
        }
        if (item.command != null) { dismiss(); action(item.command); return }
        val path = item.path ?: return
        opening = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(model.allowedPath(path)) { "Storage access is missing. Reconnect this location in Storage locations." }
                    check(model.documents.read(path).folder) { "This saved destination is no longer a folder." }
                }
                model.navigate(path); dismiss()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "This location is unavailable. Reconnect its storage or repair the shortcut."
            } finally { opening = false }
        }
    }
    LaunchedEffect(query, onlyFolders) { selected = 0; list.scrollToItem(0) }
    LaunchedEffect(fieldPlaced) { if (fieldPlaced) focus.requestFocus() }
    LaunchedEffect(selected) { if (shown.isNotEmpty()) list.animateScrollToItem(selected.coerceIn(0, shown.lastIndex)) }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val listHeight = (maxHeight - 255.dp).coerceIn(100.dp, 420.dp)
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("explorer-launcher")
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionDown -> { if (shown.isNotEmpty()) selected = (selected + 1).coerceAtMost(shown.lastIndex); true }
                        Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                        Key.Enter, Key.NumPadEnter -> { shown.getOrNull(selected)?.let(::choose); true }
                        Key.Escape -> { dismiss(); true }
                        else -> false
                    }
                }, shape = RoundedCornerShape(14.dp), color = CanvasBackground, shadowElevation = 16.dp) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (onlyFolders) "Jump to folder" else "Find an action or folder", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                    OutlinedTextField(query, { query = it; error = null; recovery = null }, singleLine = true,
                        label = { Text(if (onlyFolders) "Search locations or enter a folder path" else "Search actions and folders") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus).onGloballyPositioned { fieldPlaced = true }.testTag("launcher-query"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!onlyFolders, { onlyFolders = false }, label = { Text("Actions and folders") })
                        FilterChip(onlyFolders, { onlyFolders = true }, label = { Text("Folders") })
                    }
                    Text("↑ ↓ to choose · Enter to open · Esc to close", color = Muted, fontSize = 11.sp)
                    error?.let { Text(it, Modifier.padding(vertical = 8.dp).testTag("launcher-error"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    recovery?.let { next -> TextButton({ dismiss(); action(next.command) }, Modifier.testTag("launcher-recovery")) { Text(next.label) } }
                    if (opening) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (shown.isEmpty()) Text("No matches. Try another name, or enter a full folder path.", Modifier.padding(vertical = 24.dp), color = Muted)
                    LazyColumn(Modifier.heightIn(max = listHeight).padding(top = 8.dp), state = list) {
                        itemsIndexed(shown, key = { _, item -> item.id }) { index, item ->
                            Column(Modifier.fillMaxWidth().testTag(item.id)
                                .background(if (selected == index) SelectionSurface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { selected = index; choose(item) }
                                .semantics { this.selected = selected == index; item.reason?.let { stateDescription = it } }
                                .padding(12.dp)) {
                                Text(item.title, color = if (item.reason == null) Ink else Muted, fontSize = 14.sp)
                                Text(item.reason ?: item.detail, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    TextButton(dismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}
