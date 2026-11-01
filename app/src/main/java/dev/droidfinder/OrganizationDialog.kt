package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun OrganizationDialog(model: FinderModel) {
    val state = model.organization
    val config = LocalConfiguration.current
    val narrow = config.screenWidthDp < 900 || config.fontScale > 1.3f
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var deletion by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<OrganizationItem?>(null) }
    var noticeDetails by rememberSaveable { mutableStateOf(false) }
    var showMembers by rememberSaveable { mutableStateOf(model.currentCollection != null) }
    val idle = state.ready && model.busy == null
    val collection = model.collections.firstOrNull { it.id == state.collectionId }
    Dialog(
        onDismissRequest = state::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.width((config.screenWidthDp * .94f).coerceAtMost(860f).dp)
                .height((config.screenHeightDp * .88f).coerceAtMost(540f).dp)
                .testTag("organization-library"),
            shape = RoundedCornerShape(14.dp),
            color = CanvasBackground,
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CollectionsBookmark, null, tint = Blue)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            "Organize",
                            color = Ink,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (state.running) state.progress else "Collections and tags",
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.applying)
                        IconButton(onClick = model::cancelTransfer) {
                            Icon(Icons.Outlined.Stop, "Stop library change", tint = Muted)
                        }
                    else if (state.running || state.membersLoading)
                        IconButton(onClick = { state.stop() }) {
                            Icon(Icons.Outlined.Stop, "Stop organization review", tint = Muted)
                        }
                    else
                        IconButton(onClick = state::refresh, enabled = !state.applying) {
                            Icon(
                                Icons.Outlined.Refresh,
                                "Refresh organization review",
                                tint = Muted,
                            )
                        }
                    IconButton(onClick = state::openExport, enabled = idle) {
                        Icon(Icons.Outlined.FileUpload, "Export organization library", tint = Muted)
                    }
                    IconButton(onClick = state::close, enabled = !state.applying) {
                        Icon(Icons.Outlined.Close, "Close organization library", tint = Muted)
                    }
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth()
                        .background(AppBackground)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf("Collections", "Tag library", "Selection (${state.items.size})")
                        .forEachIndexed { page, title ->
                            TextButton(
                                onClick = { state.page = page },
                                modifier = if (narrow) Modifier.weight(1f) else Modifier,
                                enabled = page != 2 || state.items.isNotEmpty(),
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        containerColor =
                                            if (state.page == page) SelectionSurface
                                            else Color.Transparent,
                                    ),
                            ) {
                                Text(
                                    title,
                                    color = if (state.page == page) Blue else Muted,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                }
                val issue = state.error ?: model.libraryError
                if (issue != null)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            issue,
                            Modifier.weight(1f)
                                .heightIn(max = 64.dp)
                                .verticalScroll(rememberScrollState()),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) {
                    when (state.page) {
                        0 ->
                            Row(Modifier.fillMaxSize()) {
                                if (!narrow || !showMembers || collection == null) {
                                    CollectionCatalog(
                                        model,
                                        Modifier.then(
                                            if (narrow) Modifier.fillMaxSize()
                                            else Modifier.width(228.dp).fillMaxHeight(),
                                        ),
                                        onCreate = { editor = "newCollection" },
                                        onSelect = {
                                            state.inspect(it)
                                            showMembers = true
                                        },
                                    )
                                }
                                if (!narrow) VerticalDivider(color = Border)
                                if (!narrow || showMembers && collection != null) {
                                    if (collection == null)
                                        LibraryEmpty(
                                            "Collect files from different folders",
                                            "Create a collection, then add reviewed items. Files stay in their original folders.",
                                            Modifier.weight(1f),
                                        )
                                    else
                                        CollectionMembers(
                                            model,
                                            collection,
                                            Modifier.weight(1f),
                                            narrow,
                                            onBack = { showMembers = false },
                                            onEdit = { editor = "collection:${collection.id}" },
                                            onDelete = { deletion = "collection:${collection.id}" },
                                            onRemove = { deletion = "references:${collection.id}" },
                                            onDetail = { detail = it },
                                        )
                                }
                            }
                        1 ->
                            TagCatalog(
                                model,
                                onCreate = { editor = "newTag" },
                                onEdit = { editor = "tag:$it" },
                                onDelete = { deletion = "tag:$it" },
                            )
                        else -> SelectionTagReview(model, narrow, onDetail = { detail = it })
                    }
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.page == 2)
                            "${state.changed} changed · ${state.excluded.size} excluded"
                        else state.notice ?: model.libraryNotice ?: "Original files stay in place",
                        Modifier.weight(1f),
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                    if (model.libraryNotice != null)
                        IconButton(onClick = { noticeDetails = true }) {
                            Icon(
                                Icons.Outlined.Info,
                                "Organization metadata notice",
                                Modifier.size(18.dp),
                                tint = Muted,
                            )
                        }
                    if (state.page == 2) {
                        TextButton(onClick = state::resetDraft, enabled = idle) { Text("Reset") }
                        Button(
                            onClick = state::applyTags,
                            enabled =
                                idle &&
                                    state.changed > 0 &&
                                    state.included.none { it.problem != null },
                        ) {
                            Text(if (state.applying) "Applying…" else "Apply tags")
                        }
                    } else
                        TextButton(onClick = state::close, enabled = !state.applying) {
                            Text("Done")
                        }
                }
            }
        }
    }
    if (noticeDetails)
        AlertDialog(
            onDismissRequest = { noticeDetails = false },
            title = { Text("Organization metadata needs review") },
            text = { Text(model.libraryNotice.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { noticeDetails = false }) { Text("Review library") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        model.libraryNotice = null
                        noticeDetails = false
                    },
                ) {
                    Text("Dismiss note")
                }
            },
        )
    editor?.let { type ->
        key(type) {
            LibraryNameEditor(
                model,
                type,
                onDismiss = { editor = null },
                onCreated = {
                    editor = null
                    showMembers = true
                },
            )
        }
    }
    deletion?.let { type -> key(type) { LibraryDeleteReview(model, type) { deletion = null } } }
    detail?.let { item -> ReferenceDetails(model, item) { detail = null } }
    if (state.exportReview != null) LibraryExportDialog(model)
    if (state.locations.visible) SavedLocationsDialog(model)
    if (state.repair.visible) ReferenceRepairDialog(model)
}

@Composable
private fun LibraryEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxHeight().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.CollectionsBookmark, null, Modifier.size(34.dp), tint = Muted)
        Text(
            title,
            Modifier.padding(top = 12.dp),
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(body, Modifier.padding(top = 7.dp), color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun LibraryFind(
    value: String,
    label: String,
    change: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value,
        { change(it.take(128)) },
        singleLine = true,
        placeholder = { Text(label, fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = Muted) },
        modifier = modifier.fillMaxWidth().semanticsLabel(label).testTag("library-search-$label"),
    )
}

@Composable
private fun CollectionCatalog(
    model: FinderModel,
    modifier: Modifier,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val state = model.organization
    var query by rememberSaveable { mutableStateOf("") }
    val groups = model.collections.filter { it.name.contains(query, true) }
    Column(modifier.padding(12.dp)) {
        LibraryFind(query, "Find collections", { query = it })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${model.collections.size} ${if (model.collections.size == 1) "collection" else "collections"}",
                Modifier.weight(1f),
                color = Muted,
                fontSize = 12.sp,
            )
            IconButton(onClick = onCreate, enabled = state.catalogReady && model.busy == null) {
                Icon(Icons.Outlined.Add, "New collection", tint = Blue)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(groups, key = { it.id }) { collection ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (collection.id == state.collectionId) SelectionSurface
                            else Color.Transparent,
                            RoundedCornerShape(7.dp),
                        )
                        .clickable { onSelect(collection.id) }
                        .padding(horizontal = 9.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CollectionsBookmark,
                        null,
                        Modifier.size(22.dp),
                        tint = tagColor(collection.color),
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            collection.name,
                            color = Ink,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("${collection.paths.size} references", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            if (groups.isEmpty())
                item {
                    Text(
                        if (query.isEmpty()) "No collections yet" else "No matching collections",
                        Modifier.padding(vertical = 16.dp),
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
        }
    }
}

@Composable
private fun CollectionMembers(
    model: FinderModel,
    collection: FinderCollection,
    modifier: Modifier,
    narrow: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
    onDetail: (OrganizationItem) -> Unit,
) {
    val state = model.organization
    var query by rememberSaveable(collection.id) { mutableStateOf("") }
    var unavailable by rememberSaveable(collection.id) { mutableStateOf(false) }
    val rows =
        state.members.filter {
            (!unavailable || it.problem != null) &&
                (it.name.contains(query, true) || it.path.contains(query, true))
        }
    val idle = state.catalogReady && model.busy == null && !state.membersLoading
    Column(modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (narrow)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "All collections", tint = Muted)
                }
            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                Text(
                    collection.name,
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.membersLoading) state.memberProgress
                    else
                        "${state.members.size} references · ${state.members.count { it.problem != null }} unavailable",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onEdit, enabled = idle) {
                Icon(Icons.Outlined.Edit, "Edit collection ${collection.name}", tint = Muted)
            }
            IconButton(onClick = onDelete, enabled = idle) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    "Delete collection ${collection.name}",
                    tint = Muted,
                )
            }
        }
        LibraryFind(query, "Find references", { query = it }, Modifier.padding(top = 3.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TextButton(
                onClick = {
                    state.close()
                    model.navigate("@collection:${collection.id}")
                },
                enabled = !state.applying,
            ) {
                Text("Open collection", fontSize = 12.sp)
            }
            if (state.items.isNotEmpty())
                TextButton(
                    onClick = { state.add(collection.id) },
                    enabled =
                        state.ready &&
                            model.busy == null &&
                            state.included.isNotEmpty() &&
                            state.included.none { it.problem != null },
                ) {
                    Text("Add ${state.included.size} reviewed items", fontSize = 12.sp)
                }
            FilterChip(
                selected = unavailable,
                onClick = { unavailable = !unavailable },
                label = {
                    Text(
                        "Unavailable (${state.members.count { it.problem != null }})",
                        fontSize = 11.sp,
                    )
                },
            )
            if (state.memberSelection.isNotEmpty())
                TextButton(onClick = onRemove, enabled = idle) {
                    Text("Remove ${state.memberSelection.size} references", fontSize = 11.sp)
                }
        }
        if (state.membersLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.path }) { item ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        item.path in state.memberSelection,
                        { state.toggleMember(item.path) },
                        enabled = idle,
                        modifier = Modifier.semanticsLabel("Select reference ${item.name}"),
                    )
                    if (item.entry != null) FileGlyph(item.entry, 25.dp, thumbnail = false)
                    else Icon(Icons.Outlined.LinkOff, null, Modifier.size(25.dp), tint = Muted)
                    Column(
                        Modifier.weight(1f)
                            .clickable { onDetail(item) }
                            .padding(start = 9.dp, top = 7.dp, bottom = 7.dp),
                    ) {
                        Text(
                            item.name,
                            color = Ink,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.problem ?: item.entry?.parentPath?.let(model::title).orEmpty(),
                            color = Muted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onDetail(item) }) {
                        Icon(
                            Icons.Outlined.Info,
                            "Reference details ${item.name}",
                            Modifier.size(18.dp),
                            tint = Muted,
                        )
                    }
                }
                HorizontalDivider(color = Border, thickness = .5.dp)
            }
            if (rows.isEmpty() && !state.membersLoading)
                item {
                    Text(
                        if (collection.paths.isEmpty())
                            "Add files from any authorized folder. Their originals stay where they are."
                        else "No matching references",
                        Modifier.padding(vertical = 18.dp),
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
        }
    }
}

@Composable
private fun TagCatalog(
    model: FinderModel,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val state = model.organization
    val catalog = model.tagDefinitions.filter { it.name.contains(query, true) }
    val counts =
        remember(model.tagAssignments) {
            buildMap<String, Int> {
                model.tagAssignments.values.forEach { tags ->
                    tags.forEach { tag -> put(tag, (get(tag) ?: 0) + 1) }
                }
            }
        }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            LibraryFind(query, "Find tags", { query = it }, Modifier.weight(1f))
            OutlinedButton(onClick = onCreate, enabled = state.catalogReady && model.busy == null) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Text("New tag", Modifier.padding(start = 6.dp))
            }
        }
        TextButton(
            onClick = { model.organization.locations.open() },
            enabled = model.busy == null,
        ) {
            Icon(Icons.Outlined.Link, null, Modifier.size(17.dp))
            Text("Saved locations…", Modifier.padding(start = 7.dp), fontSize = 12.sp)
        }
        Text(
            "${model.tagDefinitions.size} tags · ${model.tagAssignments.size} tagged locations",
            Modifier.padding(vertical = 10.dp),
            color = Muted,
            fontSize = 12.sp,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(catalog, key = { it.name }) { tag ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 58.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(tagColor(tag.color), CircleShape))
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text(tag.name, color = Ink, fontSize = 14.sp)
                        Text(
                            "${counts[tag.name] ?: 0} tagged locations",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(
                        onClick = {
                            state.close()
                            model.navigate("@tag:${tag.name}")
                        },
                    ) {
                        Text("Browse", fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = { onEdit(tag.name) },
                        enabled = state.catalogReady && model.busy == null,
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            "Edit tag ${tag.name}",
                            Modifier.size(18.dp),
                            tint = Muted,
                        )
                    }
                    IconButton(
                        onClick = { onDelete(tag.name) },
                        enabled = state.catalogReady && model.busy == null,
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            "Delete tag ${tag.name}",
                            Modifier.size(18.dp),
                            tint = Muted,
                        )
                    }
                }
                HorizontalDivider(color = Border, thickness = .5.dp)
            }
            if (catalog.isEmpty())
                item {
                    Text(
                        "No matching tags",
                        Modifier.padding(vertical = 18.dp),
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
        }
    }
}

@Composable
private fun SelectionTagReview(
    model: FinderModel,
    narrow: Boolean,
    onDetail: (OrganizationItem) -> Unit,
) {
    val state = model.organization
    var preview by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (narrow)
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { preview = false }) {
                    Text("Choose tags", color = if (!preview) Blue else Muted)
                }
                TextButton(onClick = { preview = true }) {
                    Text("Review changes", color = if (preview) Blue else Muted)
                }
            }
        Row(Modifier.weight(1f)) {
            if (!narrow || !preview)
                SelectionTagChoices(
                    model,
                    if (narrow) Modifier.weight(1f) else Modifier.width(260.dp),
                )
            if (!narrow) VerticalDivider(color = Border)
            if (!narrow || preview) SelectionTagRows(model, Modifier.weight(1f), onDetail)
        }
    }
}

@Composable
private fun SelectionTagChoices(model: FinderModel, modifier: Modifier) {
    val state = model.organization
    var query by rememberSaveable { mutableStateOf("") }
    val catalog =
        state.snapshot?.tags?.definitions.orEmpty().filter { it.name.contains(query, true) }
    val editable = state.ready && model.busy == null
    Column(modifier.fillMaxHeight().padding(12.dp)) {
        LibraryFind(query, "Find selection tags", { query = it })
        Text(
            "Keep preserves mixed assignments.",
            Modifier.padding(vertical = 9.dp),
            fontSize = 11.sp,
            color = Muted,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(catalog, key = { it.name }) { tag ->
                val change = state.changes[tag.name] ?: TagChange.KEEP
                val count = state.included.count { tag.name in state.before(it.key) }
                val all = state.included.isNotEmpty() && count == state.included.size
                val toggle =
                    when (change) {
                        TagChange.ADD -> ToggleableState.On
                        TagChange.REMOVE -> ToggleableState.Off
                        TagChange.KEEP ->
                            if (state.clearAll || count == 0) ToggleableState.Off
                            else if (all) ToggleableState.On else ToggleableState.Indeterminate
                    }
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 58.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        toggle,
                        onClick = {
                            state.change(
                                tag.name,
                                when (change) {
                                    TagChange.KEEP -> if (all) TagChange.REMOVE else TagChange.ADD
                                    TagChange.ADD -> TagChange.REMOVE
                                    TagChange.REMOVE -> TagChange.KEEP
                                },
                            )
                        },
                        enabled = editable,
                        modifier = Modifier.semanticsLabel("Change ${tag.name} for selection"),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(tagColor(tag.color), CircleShape))
                            Text(
                                tag.name,
                                Modifier.padding(start = 6.dp),
                                color = Ink,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("$count of ${state.included.size}", color = Muted, fontSize = 11.sp)
                    }
                    Box {
                        TextButton(
                            onClick = { menu = true },
                            enabled = editable,
                            modifier = Modifier.semanticsLabel("Selection rule for ${tag.name}"),
                        ) {
                            Text(change.title, fontSize = 11.sp)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            TagChange.entries.forEach { value ->
                                DropdownMenuItem(
                                    text = { Text("${value.title} ${tag.name}") },
                                    onClick = {
                                        state.change(tag.name, value)
                                        menu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    state.clearAll,
                    enabled = editable,
                    role = Role.Checkbox,
                    onValueChange = state::clear,
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(state.clearAll, null)
            Text(
                "Clear existing tags first",
                Modifier.padding(start = 8.dp),
                fontSize = 12.sp,
                color = Ink,
            )
        }
    }
}

@Composable
private fun SelectionTagRows(
    model: FinderModel,
    modifier: Modifier,
    detail: (OrganizationItem) -> Unit,
) {
    val state = model.organization
    var query by rememberSaveable { mutableStateOf("") }
    val rows = state.items.filter { it.name.contains(query, true) || it.path.contains(query, true) }
    Column(modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 12.dp)) {
        LibraryFind(query, "Filter tag preview", { query = it })
        Text(
            "${rows.size} of ${state.items.size} · filtering does not change the apply scope",
            Modifier.padding(vertical = 8.dp),
            color = Muted,
            fontSize = 11.sp,
        )
        if (state.items.any { it.problem != null })
            TextButton(onClick = state::excludeUnavailable, enabled = state.ready) {
                Text("Exclude unavailable items")
            }
        LazyColumn(Modifier.weight(1f).testTag("organization-tag-preview")) {
            items(rows, key = { it.path }) { item ->
                val included = item.path !in state.excluded
                val before = state.before(item.key)
                val after = state.after(item.key)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        included,
                        { state.toggle(item.path) },
                        enabled = state.ready && model.busy == null,
                        modifier = Modifier.semanticsLabel("Include ${item.name} in tag changes"),
                    )
                    if (item.entry != null) FileGlyph(item.entry, 23.dp, thumbnail = false)
                    Column(
                        Modifier.weight(1f)
                            .clickable { detail(item) }
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            item.name,
                            color = Ink,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.entry?.parentPath != null)
                            Text(
                                model.title(item.entry.parentPath),
                                color = Muted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        Text(
                            if (!included) "Excluded"
                            else
                                item.problem
                                    ?: "${before.sorted().joinToString(", ").ifEmpty { "Untagged" }} → ${after.sorted().joinToString(", ").ifEmpty { "Untagged" }}",
                            color =
                                if (included && before != after && item.problem == null) Blue
                                else Muted,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = Border, thickness = .5.dp)
            }
        }
    }
}

@Composable
private fun LibraryNameEditor(
    model: FinderModel,
    type: String,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val state = model.organization
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val isCollection = type == "newCollection" || type.startsWith("collection:")
    val collection = model.collections.firstOrNull { type == "collection:${it.id}" }
    val tag = model.tagDefinitions.firstOrNull { type == "tag:${it.name}" }
    var name by rememberSaveable { mutableStateOf(collection?.name ?: tag?.name ?: "") }
    var color by rememberSaveable { mutableStateOf(collection?.color ?: tag?.color ?: "Blue") }
    var include by rememberSaveable {
        mutableStateOf(isCollection && state.ready && state.included.isNotEmpty())
    }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val completed = remember { state.completedSerial }
    LaunchedEffect(state.completedSerial) { if (state.completedSerial != completed) onCreated() }
    AlertDialog(
        onDismissRequest = { if (!state.applying) onDismiss() },
        title = {
            Text(
                if (type == "newCollection") "New collection"
                else if (type == "newTag") "New tag"
                else if (isCollection) "Edit collection" else "Edit tag",
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name,
                    { name = it.take(if (isCollection) 64 else 48) },
                    label = { Text(if (isCollection) "Collection name" else "Tag name") },
                    singleLine = true,
                    enabled = !state.applying,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 9.dp)) {
                    defaultTags.forEach { value ->
                        IconButton(
                            onClick = { color = value.color },
                            enabled = !state.applying,
                            modifier = Modifier.semanticsLabel("${value.color} library color"),
                        ) {
                            Box(
                                Modifier.size(24.dp).background(tagColor(value.color), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (color == value.color)
                                    Icon(
                                        Icons.Outlined.Check,
                                        null,
                                        Modifier.size(17.dp),
                                        tint = Color.White,
                                    )
                            }
                        }
                    }
                }
                if (type == "newCollection" && state.items.isNotEmpty())
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                include,
                                enabled = !state.applying && state.ready,
                                role = Role.Checkbox,
                                onValueChange = { include = it },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(include, null)
                        Text(
                            "Add ${state.included.size} reviewed items",
                            Modifier.padding(start = 8.dp),
                            fontSize = 13.sp,
                        )
                    }
                if (isCollection)
                    Text(
                        "A collection holds references. It doesn't move or copy the original files.",
                        fontSize = 12.sp,
                        color = Muted,
                    )
                if (state.applying)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 9.dp))
                val issue = error ?: state.error
                if (issue != null)
                    Text(issue, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error =
                        runCatching { libraryName(name, if (isCollection) 64 else 48) }
                            .exceptionOrNull()
                            ?.message
                    if (error == null) {
                        focus.clearFocus()
                        keyboard?.hide()
                        when {
                            type == "newCollection" -> {
                                state.create(name, color, include)
                                if (!state.applying)
                                    error = state.error ?: "Refresh the reviewed selection first."
                            }
                            collection != null -> state.saveCollection(collection.id, name, color)
                            type == "newTag" -> state.saveTag(null, name, color)
                            tag != null -> state.saveTag(tag.name, name, color)
                            else -> error = "This library entry no longer exists."
                        }
                    }
                },
                enabled = name.isNotBlank() && state.catalogReady && model.busy == null,
            ) {
                Text(if (isCollection) "Save collection" else "Save tag")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.applying) { Text("Cancel") }
        },
    )
}

@Composable
private fun LibraryDeleteReview(model: FinderModel, type: String, dismiss: () -> Unit) {
    val state = model.organization
    val before = remember { model.session.tagStore.snapshot() }
    val tag = before.tags.definitions.firstOrNull { type == "tag:${it.name}" }
    val collection =
        before.collections.firstOrNull {
            type == "collection:${it.id}" || type == "references:${it.id}"
        }
    val references = remember { state.memberSelection.toList() }
    val membership = type.startsWith("references:")
    var error by remember { mutableStateOf<String?>(null) }
    val completed = remember { state.completedSerial }
    LaunchedEffect(state.completedSerial) { if (completed != state.completedSerial) dismiss() }
    AlertDialog(
        onDismissRequest = { if (!state.applying) dismiss() },
        title = {
            Text(
                if (membership) "Remove ${references.size} references?"
                else if (tag != null) "Delete tag ${tag.name}?"
                else "Delete collection ${collection?.name.orEmpty()}?",
            )
        },
        text = {
            Column {
                Text(
                    if (tag != null)
                        "Remove this tag from ${before.tags.assignments.count { tag.name in it.value }} locations. Original files and other tags are kept. Saved searches using this tag keep their criterion; Undo can restore the tag."
                    else if (membership)
                        "Remove these references from this collection. The original files and their tags are kept."
                    else
                        "Remove this collection and its ${collection?.paths?.size ?: 0} references from the library. Original files and tags are kept.",
                    fontSize = 13.sp,
                )
                Text(
                    "Session Undo is available.",
                    Modifier.padding(top = 9.dp),
                    color = Muted,
                    fontSize = 12.sp,
                )
                if (state.applying)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 9.dp))
                val issue = error ?: state.error
                if (issue != null)
                    Text(issue, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        tag != null -> state.deleteTag(tag.name, before.tags)
                        collection != null && membership ->
                            state.removeMembers(collection.id, references, collection)
                        collection != null -> state.deleteCollection(collection.id, collection)
                        else -> dismiss()
                    }
                },
                enabled = state.catalogReady && model.busy == null,
            ) {
                Text(if (membership) "Remove references" else "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !state.applying) { Text("Cancel") }
        },
    )
}

@Composable
internal fun ReferenceDetails(model: FinderModel, item: OrganizationItem, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(item.name) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(item.path, color = Muted, fontSize = 12.sp) }
                if (item.problem != null)
                    Text(
                        item.problem,
                        Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                else
                    Text(
                        if (item.entry?.folder == true) "Folder reference"
                        else "File reference · ${item.entry?.displaySize.orEmpty()}",
                        Modifier.padding(top = 12.dp),
                        fontSize = 13.sp,
                        color = Ink,
                    )
                if (model.organization.page == 2) {
                    Text(
                        "Current: ${model.organization.before(item.key).sorted().joinToString(", ").ifEmpty { "Untagged" }}",
                        Modifier.padding(top = 12.dp),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Result: ${model.organization.after(item.key).sorted().joinToString(", ").ifEmpty { "Untagged" }}",
                        color = Ink,
                        fontSize = 12.sp,
                    )
                }
                if (
                    model.libraryError == null &&
                        (item.key in model.tagAssignments ||
                            model.collections.any { item.key in it.paths })
                )
                    TextButton(
                        onClick = {
                            model.organization.repair.open(item.key)
                            dismiss()
                        },
                        enabled = model.busy == null,
                    ) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(17.dp))
                        Text("Locate replacement…", Modifier.padding(start = 7.dp))
                    }
                TextButton(onClick = { clipboard.setText(AnnotatedString(item.path)) }) {
                    Text("Copy location")
                }
                if (item.node != null)
                    TextButton(
                        onClick = {
                            model.organization.close()
                            dismiss()
                            model.revealAnalyzed(item.node)
                        },
                    ) {
                        Text("Show in folder")
                    }
                else
                    TextButton(
                        onClick = {
                            model.organization.close()
                            dismiss()
                            model.storageRequested = true
                        },
                    ) {
                        Text("Storage locations…")
                    }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
}

@Composable
private fun LibraryExportDialog(model: FinderModel) {
    val state = model.organization
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val value = state.exportReview ?: return
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    if (state.exportDestination) {
        DestinationDialog(
            model,
            false,
            titleOverride = "Export library to…",
            confirmLabel = "Save here",
            onDestination = state::saveExport,
            dismiss = { state.exportDestination = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = state::dismissExport,
        title = { Text("Export organization library") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            value.selection != null,
                            enabled = state.exportSelectionCount > 0 && !state.exportSaving,
                            role = Role.Checkbox,
                            onValueChange = state::exportScope,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(value.selection != null, null)
                    Text(
                        "Only the ${state.exportSelectionCount} reviewed locations",
                        Modifier.padding(start = 8.dp),
                        fontSize = 13.sp,
                    )
                }
                Text(
                    "${value.definitions.size} tags · ${value.assignments.size} assignment records · ${value.collections.size} collections · ${value.locations.size} locations",
                    fontSize = 13.sp,
                    color = Ink,
                )
                Text(
                    "Includes names, tag assignments and file locations. Workspace paths are relative; tablet paths and provider URIs can reveal folder names. File contents and storage permissions are not included. This JSON export is metadata, not a file backup.",
                    Modifier.padding(vertical = 12.dp),
                    fontSize = 12.sp,
                    color = Muted,
                )
                OutlinedTextField(
                    state.exportFilename,
                    { state.exportFilename = it.take(180) },
                    singleLine = true,
                    label = { Text("Export filename") },
                    enabled = !state.exportSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.exportSaving)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                val issue = error ?: state.exportError
                if (issue != null)
                    Text(issue, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error =
                        runCatching { validateFilename(state.exportFilename) }
                            .exceptionOrNull()
                            ?.message
                    if (error == null) {
                        focus.clearFocus()
                        keyboard?.hide()
                        state.exportDestination = true
                    }
                },
                enabled =
                    !state.exportSaving && state.exportFilename.isNotBlank() && model.busy == null,
            ) {
                Text("Choose destination…")
            }
        },
        dismissButton = {
            TextButton(onClick = state::dismissExport, enabled = !state.exportSaving) {
                Text("Cancel")
            }
        },
    )
}
