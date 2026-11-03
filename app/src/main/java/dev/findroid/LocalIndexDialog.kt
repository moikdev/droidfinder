package dev.findroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun indexTimestamp(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
internal const val INDEX_FRESHNESS_EXPLANATION = "Results come from a saved snapshot. New, renamed or changed files may be missed until you refresh the index. Files that match the snapshot are checked again using their current information and tags. Choose Live folders for a new folder scan."

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalIndexDialog(model: FinderModel, dismiss: () -> Unit) {
    val index = model.session.searchIndex
    val state by index.state.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LocalIndexRoot?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(index) { index.load(force = true) }
    if (choosing) {
        LocalIndexFolderPicker(model, { path -> index.add(path, model::allowedPath); choosing = false }) { choosing = false }
        return
    }
    WorkspaceUtility("Search index", "Optional metadata snapshots of folders you choose. Live search remains the default.",
        "local-index", dismiss, width = 660.dp) { limit ->
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = limit).testTag("local-index-list"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("Only filenames, paths, types, sizes and dates are saved on this tablet. Document contents and tags are not copied into the index. Nothing is uploaded.", fontSize = 13.sp)
                Text(INDEX_FRESHNESS_EXPLANATION, Modifier.padding(top = 8.dp), color = Muted, fontSize = 12.sp)
                Text("Cache storage (last measured): ${NumberFormat.getIntegerInstance().format(state.storageBytes)} bytes", Modifier.padding(top = 12.dp).testTag("local-index-storage"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Exact total for the database and its journals. Setup backups exclude indexed folders and cache rows. Refreshes run only when you request them; closing this dialog keeps an active refresh running.", fontSize = 12.sp, color = Muted)
                if (state.loading) Text("Reading the index…", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                if (!state.loading && state.roots.isEmpty() && state.error == null)
                    Text("No folders indexed. Add a local folder to begin.", Modifier.padding(top = 12.dp).testTag("local-index-empty"), fontSize = 14.sp)
            }
            items(state.roots, key = { it.id }) { root ->
                val availability by produceState<String?>("Checking current folder…", root.path, root.filesystemIdentity, state.checkRevision) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            check(model.allowedPath(root.path)) { "Storage access is needed for this folder." }
                            check(currentIndexedFile(root.path, root.path).isDirectory) { "This folder is unavailable." }
                            check(root.filesystemIdentity == null || root.filesystemIdentity == localIndexFileIdentity(root.path)) { "The original folder was replaced. Refresh its snapshot." }
                        }.exceptionOrNull()?.let { "Current folder: ${it.message ?: "unavailable"}" }
                    }
                }
                Surface(color = AppBackground, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().testTag("local-index-root-${root.id}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.displayPath(root.path), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(root.path, fontSize = 11.sp, color = Muted)
                        Text(availability ?: "Current folder: available", fontSize = 12.sp, color = if (availability == null) Muted else MaterialTheme.colorScheme.error)
                        Text(root.state.title, fontSize = 13.sp, modifier = Modifier.testTag("local-index-state-${root.id}"))
                        if (root.hasSnapshot) {
                            Text("${root.entries} entries · snapshot finished ${indexTimestamp(root.refreshedAt!!)}", fontSize = 12.sp)
                            Text("Scan began ${indexTimestamp(root.scannedFrom!!)}. " + if (root.retainedSnapshot) "The previous complete snapshot remains searchable." else "External changes may not have been observed.", fontSize = 12.sp, color = Muted)
                        } else Text("No complete snapshot yet. This folder cannot supply indexed results.", fontSize = 12.sp, color = Muted)
                        root.changedAt?.let { Text("Changes observed ${indexTimestamp(it)}. Refresh to update saved entries.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        root.reason?.let { Text(it, fontSize = 12.sp, color = Muted) }
                        if (state.activeRoot == root.id) {
                            Text("${state.progress.entries} entries · ${state.progress.folders} folders visited", fontSize = 12.sp, modifier = Modifier.testTag("local-index-progress"))
                            Text("The new snapshot becomes searchable only after a complete scan. Pause keeps the last complete snapshot.", fontSize = 12.sp, color = Muted)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.activeRoot == root.id) TextButton(index::pause, enabled = !state.stopping,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("pause-local-index")) { Text(if (state.stopping) "Pausing…" else "Pause refresh") }
                            else TextButton({ index.refresh(root.id) }, enabled = state.activeRoot == null,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("refresh-local-index-${root.id}")) {
                                Text(if (root.state == IndexRefreshState.PAUSED || root.state == IndexRefreshState.INTERRUPTED) "Resume (restart scan)" else if (root.hasSnapshot) "Refresh snapshot" else "Build snapshot")
                            }
                            TextButton({ removing = root }, modifier = Modifier.heightIn(min = 48.dp).testTag("remove-local-index-${root.id}")) { Text("Remove folder…") }
                        }
                    }
                }
            }
            item {
                state.error?.let { message ->
                    Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("local-index-error"))
                    TextButton({ index.dismissError(); index.load(force = true) }, Modifier.heightIn(min = 48.dp)) { Text("Check again") }
                }
                Text("Up to 10 local folders, 100,000 entries per folder and 250,000 stored rows including retained snapshots. Very deep or large scans stop with an explanation. Document providers use live search.", fontSize = 12.sp, color = Muted)
            }
        }
        HorizontalDivider()
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ choosing = true }, enabled = !state.loading && state.error == null && state.roots.size < 10 && state.activeRoot == null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("add-local-index")) { Text("Add local folder…") }
            TextButton({ index.load(force = true) }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("check-local-index")) { Text("Check folders") }
            TextButton({ deleting = true }, enabled = state.storageBytes > 0 || state.roots.isNotEmpty() || state.error != null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("delete-local-index")) { Text("Delete index cache…") }
            TextButton(dismiss, Modifier.heightIn(min = 48.dp).testTag("close-local-index")) { Text("Done") }
        }
    }
    removing?.let { root ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove indexed folder?") },
            text = { Text("${model.displayPath(root.path)}\n\nIts cached snapshots will be removed and its refresh stopped. Original files, Favorites and saved searches are kept. Indexed searches using this folder may need Live folders.") },
            confirmButton = { TextButton({ index.remove(root.id); removing = null }, Modifier.heightIn(min = 48.dp).testTag("confirm-remove-local-index")) { Text("Remove from index") } },
            dismissButton = { TextButton({ removing = null }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete index cache?") },
        text = { Text("All indexed folder registrations and cached snapshots will be removed. Active refreshes will stop. Original files, Favorites, tags, search history and saved searches are kept. Add folders again to rebuild their snapshots.") },
        confirmButton = { TextButton({ index.deleteCache(); deleting = false }, Modifier.heightIn(min = 48.dp).testTag("confirm-delete-local-index")) { Text("Delete cache") } },
        dismissButton = { TextButton({ deleting = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}

private data class IndexFolderChoice(val path: String = "", val loading: Boolean = true, val ready: Boolean = false,
    val children: List<Entry> = emptyList(), val error: String? = null, val limited: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalIndexFolderPicker(model: FinderModel, choose: (String) -> Unit, dismiss: () -> Unit) {
    var path by remember { mutableStateOf(model.folderPath?.takeIf { it.startsWith('/') } ?: model.workspace.path) }
    val control = remember(path) { ListingControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    val status by produceState(IndexFolderChoice(), path) {
        value = IndexFolderChoice()
        value = withContext(Dispatchers.IO) {
            try {
                check(model.allowedPath(path) && path.startsWith('/')) { "Choose an available local folder with storage access." }
                val folder = File(path).canonicalPath
                check(currentIndexedFile(folder, folder).isDirectory) { "Choose a local folder." }
                val children = mutableListOf<Entry>()
                var checked = 0
                var limited = false
                model.documents.visitChildren(folder, control) { entry ->
                    if (++checked > 50_000 || children.size >= 1_000) { limited = true; false }
                    else { if (entry.folder && !java.nio.file.Files.isSymbolicLink(entry.file.toPath())) children += entry; true }
                }
                IndexFolderChoice(path, false, true, children.sortedWith(filenameEntryComparator(SortBy.NAME, true, true)), limited = limited)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                IndexFolderChoice(path, false, error = failure.message ?: "This folder could not be read.")
            }
        }
    }
    WorkspaceUtility("Index this folder", "Choose an ordinary local folder. Its metadata will be scanned after you confirm.",
        "local-index-picker", dismiss, width = 620.dp) { limit ->
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = limit).testTag("local-index-picker-list"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ path = model.workspace.path }, Modifier.heightIn(min = 48.dp)) { Text("Workspace") }
                    if (model.hasAccess) TextButton({ path = model.deviceRoot.path }, Modifier.heightIn(min = 48.dp)) { Text("Tablet storage") }
                    model.favorites.filter { it.startsWith('/') }.forEach { folder ->
                        TextButton({ path = folder }, Modifier.heightIn(min = 48.dp)) { Text(model.favoriteTitle(folder)) }
                    }
                    File(path).parent?.let { parent -> TextButton({ path = parent }, enabled = model.allowedPath(parent), modifier = Modifier.heightIn(min = 48.dp)) { Text("Parent folder") } }
                }
                Text(model.displayPath(path), fontSize = 14.sp)
                Text(path, fontSize = 11.sp, color = Muted, modifier = Modifier.testTag("local-index-picker-path"))
                if (status.path != path || status.loading) Text("Reading folders…", fontSize = 13.sp)
                status.error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                if (status.ready && status.children.isEmpty()) Text("No child folders. You can index this folder.", fontSize = 13.sp)
                if (status.limited) Text("Only the first available child folders are shown. The index scan has its own limits.", fontSize = 12.sp, color = Muted)
            }
            items(status.children.takeIf { status.path == path }.orEmpty(), key = { it.path }) { entry ->
                Surface(color = AppBackground, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("index-folder-${entry.path}").clickable { path = entry.path }) {
                    Text(entry.name, Modifier.padding(14.dp), fontSize = 14.sp)
                }
            }
        }
        HorizontalDivider()
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(dismiss, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            Button({ choose(path) }, enabled = status.path == path && status.ready && !status.loading,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-index-folder")) { Text("Index this folder") }
        }
    }
}
