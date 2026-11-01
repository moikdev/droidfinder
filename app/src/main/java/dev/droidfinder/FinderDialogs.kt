package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DestinationStatus(
    val path: String,
    val loading: Boolean = true,
    val writable: Boolean = false,
    val error: String? = null,
)

private data class DestinationFolders(
    val path: String,
    val loading: Boolean = true,
    val entries: List<Entry> = emptyList(),
    val error: String? = null,
)

@Composable
fun DestinationDialog(
    model: FinderModel,
    move: Boolean,
    archive: Boolean = false,
    extract: Boolean = false,
    archiveName: String? = null,
    compressionLevel: Int = -1,
    titleOverride: String? = null,
    confirmLabel: String? = null,
    onDestination: ((String) -> Unit)? = null,
    dismiss: () -> Unit,
) {
    val paths = remember { model.selectedEntries.map { it.path } }
    var folder by rememberSaveable {
        mutableStateOf(model.folderPath ?: model.workspace.absolutePath)
    }
    val requested = folder
    val control = remember(requested) { ListingControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    val status by
        produceState(DestinationStatus(requested), requested) {
            value = DestinationStatus(requested)
            value =
                withContext(Dispatchers.IO) {
                    try {
                        check(model.allowedPath(requested)) {
                            "Reconnect storage for this destination."
                        }
                        val entry = model.documents.read(requested, control)
                        check(entry.folder) { "Choose a folder as the destination." }
                        DestinationStatus(
                            requested,
                            false,
                            entry.writable,
                            if (entry.writable) null
                            else "This location is read only. Choose another destination.",
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        DestinationStatus(
                            requested,
                            false,
                            error = e.message ?: "Couldn't check this destination.",
                        )
                    }
                }
        }
    val choices by
        produceState(DestinationFolders(requested), requested) {
            value = DestinationFolders(requested)
            value =
                withContext(Dispatchers.IO) {
                    try {
                        check(model.allowedPath(requested)) {
                            "Reconnect storage to browse this destination."
                        }
                        DestinationFolders(
                            requested,
                            false,
                            model.listPath(requested).filter { it.folder },
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        DestinationFolders(
                            requested,
                            false,
                            error = e.message ?: "Couldn't list folders here.",
                        )
                    }
                }
        }
    val writable = status.path == requested && !status.loading && status.writable
    val children = choices.entries.takeIf { choices.path == requested }.orEmpty()
    AlertDialog(
        modifier = Modifier.testTag("destination-picker"),
        onDismissRequest = dismiss,
        title = {
            Text(
                if (titleOverride != null) titleOverride
                else if (archive) "Save archive in…"
                else if (extract) "Extract archive to…"
                else if (move) "Move ${paths.size} items to…" else "Copy ${paths.size} items to…",
            )
        },
        text = {
            Column(Modifier.widthIn(min = 350.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    AssistChip(
                        onClick = { folder = model.workspace.absolutePath },
                        label = { Text("Workspace") },
                    )
                    if (model.hasAccess)
                        AssistChip(
                            onClick = { folder = model.deviceRoot.absolutePath },
                            label = { Text("Tablet") },
                        )
                    model.connectedLocations.forEach { root ->
                        AssistChip(onClick = { folder = root.uri }, label = { Text(root.name) })
                    }
                    model.tabs
                        .mapNotNull { it.location.takeUnless { path -> path.startsWith("@") } }
                        .distinct()
                        .forEach { path ->
                            AssistChip(
                                onClick = { folder = path },
                                label = { Text("Tab: ${model.title(path)}") },
                            )
                        }
                    model.favorites.forEach { path ->
                        AssistChip(onClick = { folder = path }, label = { Text(model.title(path)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(
                        Icons.Outlined.ArrowUpward,
                        "Parent destination folder",
                        { model.parentPath(folder)?.let { folder = it } },
                        enabled = model.parentPath(folder) != null,
                    )
                    Text(
                        model.title(folder),
                        Modifier.weight(1f).semanticsLabel("Destination folder $folder"),
                        fontSize = 12.sp,
                        color = Muted,
                    )
                }
                if (status.path != requested || status.loading)
                    Text("Checking destination…", color = Muted, fontSize = 12.sp)
                val problem =
                    status.error.takeIf { status.path == requested }
                        ?: choices.error.takeIf { choices.path == requested }
                if (problem != null)
                    Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                else if (choices.path != requested || choices.loading)
                    Text("Reading folders…", color = Muted, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(min = 160.dp, max = 280.dp)) {
                    items(children, key = { it.path }) { entry ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { folder = entry.path }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FolderGlyph(32.dp)
                            Text(
                                entry.name,
                                Modifier.weight(1f).padding(start = 8.dp),
                                fontSize = 12.sp,
                            )
                            Icon(
                                Icons.Outlined.ChevronRight,
                                null,
                                tint = Muted,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onDestination != null) onDestination(folder)
                    else if (archive) model.compressTo(folder, archiveName, compressionLevel)
                    else if (extract) model.extractTo(folder)
                    else model.transferPaths(paths, folder, move)
                    dismiss()
                },
                enabled = (paths.isNotEmpty() || onDestination != null) && writable,
            ) {
                Text(
                    if (confirmLabel != null) confirmLabel
                    else if (archive) "Save here"
                    else if (extract) "Extract here" else if (move) "Move here" else "Copy here",
                )
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
fun SearchFiltersDialog(model: FinderModel, dismiss: () -> Unit) {
    var rules by remember { mutableStateOf(model.effectiveSearchRules) }
    var minimum by remember {
        mutableStateOf(if (rules.minimumBytes > 0) (rules.minimumBytes / 1024).toString() else "")
    }
    var maximum by remember {
        mutableStateOf(if (rules.maximumBytes > 0) (rules.maximumBytes / 1024).toString() else "")
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Search criteria") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Search this folder and all subfolders", fontSize = 12.sp, color = Muted)
                OutlinedTextField(
                    rules.name,
                    { rules = rules.copy(name = it) },
                    label = { Text("Name contains") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                if (rules.nameTerms.isNotEmpty())
                    OutlinedTextField(
                        rules.nameTerms.joinToString("\n"),
                        {
                            rules =
                                rules.copy(
                                    nameTerms =
                                        it.lines().map(String::trim).filter(String::isNotEmpty),
                                )
                        },
                        label = { Text("Additional name terms") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                Text(
                    "Kind",
                    Modifier.padding(top = 14.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    listOf(
                            "Any",
                            "Folder",
                            "Image",
                            "PDF document",
                            "Text document",
                            "Video",
                            "Audio",
                            "ZIP archive",
                        )
                        .forEach { kind ->
                            FilterChip(
                                selected = rules.kind == kind,
                                onClick = { rules = rules.copy(kind = kind) },
                                label = { Text(kind, fontSize = 11.sp) },
                            )
                        }
                }
                Text(
                    "Modified",
                    Modifier.padding(top = 12.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    listOf(
                            0 to "Any time",
                            1 to "Today",
                            7 to "7 days",
                            30 to "30 days",
                            365 to "Year",
                        )
                        .forEach { (days, label) ->
                            FilterChip(
                                selected = rules.days == days,
                                onClick = { rules = rules.copy(days = days) },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                }
                Text(
                    "Tag",
                    Modifier.padding(top = 12.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    FilterChip(
                        selected = rules.tag.isEmpty(),
                        onClick = { rules = rules.copy(tag = "") },
                        label = { Text("Any") },
                    )
                    model.tagNames.forEach { tag ->
                        FilterChip(
                            selected = rules.tag == tag,
                            onClick = { rules = rules.copy(tag = tag) },
                            label = { Text(tag, fontSize = 11.sp) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        minimum,
                        { minimum = it },
                        label = { Text("Minimum KB", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        maximum,
                        { maximum = it },
                        label = { Text("Maximum KB", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    rules.content,
                    { rules = rules.copy(content = it) },
                    label = { Text("Text contents contain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Text(
                    "Text search examines the first 1 MB of each supported text file. All criteria must match.",
                    Modifier.padding(top = 8.dp),
                    fontSize = 10.sp,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    model.updateSearchRules(
                        rules.copy(
                            minimumBytes =
                                (minimum.toLongOrNull() ?: 0).coerceIn(0, Long.MAX_VALUE / 1024) *
                                    1024,
                            maximumBytes =
                                (maximum.toLongOrNull() ?: 0).coerceIn(0, Long.MAX_VALUE / 1024) *
                                    1024,
                        ),
                    )
                    dismiss()
                },
            ) {
                Text("Apply filters")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        model.updateSearchRules(SearchRules())
                        dismiss()
                    },
                ) {
                    Text("Clear")
                }
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun PreferencesDialog(model: FinderModel, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Finder preferences") },
        text = {
            Column(
                Modifier.widthIn(min = 330.dp)
                    .heightIn(max = 490.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Interface density", fontWeight = FontWeight.SemiBold)
                FinderDensity.entries.forEach { density ->
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = model.density == density,
                                role = androidx.compose.ui.semantics.Role.RadioButton,
                                onClick = { model.changeDensity(density) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(model.density == density, null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(density.title, fontSize = 13.sp)
                            Text(density.description, fontSize = 11.sp, color = Muted)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("File conflicts", fontWeight = FontWeight.SemiBold)
                ConflictPolicy.entries
                    .filter { it != ConflictPolicy.REPLACE }
                    .forEach { policy ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                model.changeConflictPolicy(policy)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                model.conflictPolicy == policy,
                                { model.changeConflictPolicy(policy) },
                            )
                            Text(policy.title, fontSize = 12.sp)
                        }
                    }
                Text("Views", fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = {
                        model.housekeeping.open()
                        dismiss()
                    },
                ) {
                    Text("Trash & cache…")
                }
                Text(
                    if (model.folderRemembered) "Changes apply to this remembered folder"
                    else "Changes update defaults for this pane",
                    fontSize = 11.sp,
                    color = Muted,
                )
                TextButton(
                    onClick = {
                        model.folderViewRequested = true
                        dismiss()
                    },
                ) {
                    Text("Folder view settings…")
                }
                Text("Icon size", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Slider(
                    model.iconSize.toFloat(),
                    { model.changeIconSize(it.toInt()) },
                    valueRange = 90f..200f,
                )
                Text("Browsing column width", fontSize = 12.sp)
                Slider(
                    model.columnWidth.toFloat(),
                    { model.changeColumnWidth(it.toInt()) },
                    valueRange = 150f..500f,
                )
                Text("Preview width", fontSize = 12.sp)
                Slider(
                    model.previewWidth.toFloat(),
                    { model.changePreviewWidth(it.toInt()) },
                    valueRange = 200f..480f,
                )
                PreferenceSwitch(
                    "Show filename extensions",
                    model.showExtensions,
                    model::toggleExtensions,
                )
                PreferenceSwitch("Show hidden files", model.showHidden, model::toggleHidden)
                PreferenceSwitch(
                    "Folders before files",
                    model.foldersFirst,
                    model::toggleFoldersFirst,
                )
                PreferenceSwitch("Dark appearance", model.darkTheme, model::toggleTheme)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Android integration", fontWeight = FontWeight.SemiBold)
                PreferenceSwitch(
                    "Workspace in Android's file picker",
                    model.workspaceInPicker,
                    model::toggleWorkspacePicker,
                )
                Text(
                    "Other apps can open or save files you select in Workspace. Turning this off blocks new requests; already opened files stay open until closed.",
                    fontSize = 11.sp,
                    color = Muted,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Sidebar width", fontSize = 12.sp)
                Slider(
                    model.sidebarWidth.toFloat(),
                    { model.changeSidebarWidth(it.toInt()) },
                    valueRange = 150f..320f,
                )
                PreferenceSwitch("Show sidebar", model.sidebarVisible, model::toggleSidebar)
                PreferenceSwitch("Show sidebar tags", model.tagsVisible, model::toggleTagSidebar)
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Text("Favorite folders", fontWeight = FontWeight.SemiBold)
                if (model.favorites.isEmpty())
                    Text(
                        "Add a folder from its Actions menu.",
                        fontSize = 11.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                model.favorites.forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.title(path), Modifier.weight(1f), fontSize = 12.sp)
                        IconButton(onClick = { model.favoritePath(path) }) {
                            Icon(
                                Icons.Outlined.Close,
                                "Remove favorite ${model.title(path)}",
                                Modifier.size(15.dp),
                            )
                        }
                    }
                }
                if (model.connectedLocations.isNotEmpty()) {
                    Text(
                        "Connected locations",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    model.connectedLocations.forEach { root ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(root.name, Modifier.weight(1f), fontSize = 12.sp)
                            IconButton(onClick = { model.disconnectLocation(root.uri) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    "Disconnect ${root.name}",
                                    Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
                if (model.smartFolders.isNotEmpty()) {
                    Text(
                        "Saved Smart Folders",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    model.smartFolders.forEach { smart ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(smart.name, Modifier.weight(1f), fontSize = 12.sp)
                            IconButton(onClick = { model.removeSmartFolder(smart.id) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    "Delete Smart Folder ${smart.name}",
                                    Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch) {
            toggle()
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp)
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
fun SelectionInfoDialog(
    model: FinderModel,
    entries: List<Entry> = model.actionEntries,
    dismiss: () -> Unit,
) {
    val items = remember { entries }
    val total by
        produceState<Pair<Long, Boolean>?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    var unknown = false
                    val bytes = items.sumOf {
                        if (it.folder) {
                            if (it.documentUri != null) {
                                unknown = true
                                0L
                            } else
                                folderStats(it.file).let { stats ->
                                    unknown = unknown || stats.limited
                                    stats.bytes
                                }
                        } else it.size
                    }
                    bytes to unknown
                }
        }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Get info · ${items.size} selected") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                items.take(20).forEach { entry ->
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileGlyph(entry, 28.dp)
                        Text(entry.name, Modifier.padding(start = 8.dp), fontSize = 12.sp)
                    }
                }
                if (items.size > 20)
                    Text("…and ${items.size-20} more", fontSize = 12.sp, color = Muted)
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                MetaInfoText(
                    "Total size",
                    total?.let {
                        formatSize(it.first) +
                            if (it.second) " (some folder sizes unavailable or limited)" else ""
                    } ?: "Calculating…",
                )
                Text(
                    "Selected descendants of selected folders are counted once.",
                    Modifier.padding(top = 10.dp),
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
}

@Composable
fun FolderAppearanceDialog(model: FinderModel, dismiss: () -> Unit) {
    val existing = model.focused?.style
    var color by remember { mutableStateOf(existing?.color ?: "Blue") }
    var symbol by remember { mutableStateOf(existing?.symbol.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Customize folder") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FolderGlyph(100.dp, tagColor(color), symbol)
                Row {
                    defaultTags.forEach { tag ->
                        IconButton(
                            onClick = { color = tag.color },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(
                                Modifier.size(20.dp).background(tagColor(tag.color), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (color == tag.color)
                                    Icon(
                                        Icons.Outlined.Check,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    symbol,
                    { symbol = it.take(12) },
                    label = { Text("Symbol or emoji (optional)") },
                    singleLine = true,
                )
                Text(
                    "Appearance is saved in DroidFinder and follows app-initiated moves.",
                    fontSize = 11.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    model.setFolderStyle(FolderStyle(color, symbol))
                    dismiss()
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        model.setFolderStyle(null)
                        dismiss()
                    },
                ) {
                    Text("Reset")
                }
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        },
    )
}
