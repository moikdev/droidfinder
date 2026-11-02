package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun SavedLocationsDialog(model: FinderModel) {
    val state = model.organization.locations
    var detail by remember { mutableStateOf<OrganizationItem?>(null) }
    OrganizationUtility("Saved locations", "Tags and collection references", state::dismiss) {
        OutlinedTextField(
            state.query,
            state::search,
            singleLine = true,
            label = { Text("Find saved locations") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .testTag("saved-locations-search"),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.running) state.progress.ifBlank { "Checking this page…" }
                else "${state.total} saved locations · up to ${state.pageSize} checked per page",
                Modifier.weight(1f),
                color = Muted,
                fontSize = 12.sp,
            )
            IconButton(onClick = { if (state.running) state.stop() else state.load() }) {
                Icon(
                    if (state.running) Icons.Outlined.Stop else Icons.Outlined.Refresh,
                    if (state.running) "Stop saved location checks" else "Refresh saved locations",
                    tint = Muted,
                )
            }
        }
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { UtilityError(it) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("saved-locations-list")) {
            items(state.rows, key = { it.item.path }) { row ->
                val item = row.item
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { detail = item }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .heightIn(min = 54.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.entry != null) FileGlyph(item.entry, 25.dp, thumbnail = false)
                    else Icon(Icons.Outlined.LinkOff, null, Modifier.size(25.dp), tint = Muted)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            item.name,
                            color = Ink,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.path,
                            color = Muted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (row.tags.sorted() + row.collections.map { "Collection: $it" })
                                .joinToString(" · "),
                            color = Blue,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.problem?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        Icons.Outlined.Info,
                        "Details for saved location ${item.name}",
                        Modifier.size(18.dp),
                        tint = Muted,
                    )
                }
                HorizontalDivider(color = Border, thickness = .5.dp)
            }
            if (!state.running && state.rows.isEmpty())
                item { Text("No matching saved locations", Modifier.padding(18.dp), color = Muted) }
        }
        HorizontalDivider(color = Border)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.total == 0) "0 locations"
                else
                    "${state.page * state.pageSize + 1}–${minOf((state.page + 1) * state.pageSize, state.total)} of ${state.total}",
                Modifier.weight(1f),
                color = Muted,
                fontSize = 12.sp,
            )
            TextButton({ state.move(-1) }, enabled = !state.running && state.page > 0) {
                Text("Previous")
            }
            TextButton(
                { state.move(1) },
                enabled = !state.running && (state.page + 1) * state.pageSize < state.total,
            ) {
                Text("Next")
            }
            TextButton(state::dismiss) { Text("Done") }
        }
    }
    detail?.let { ReferenceDetails(model, it) { detail = null } }
}

@Composable
internal fun ReferenceRepairDialog(model: FinderModel) {
    val state = model.organization.repair
    val value = state.review
    OrganizationUtility(
        if (state.picking) "Locate replacement" else "Review relink",
        "Update saved metadata · original files stay in place",
        state::dismiss,
        !state.applying,
    ) {
        if (state.picking) ReplacementPicker(model)
        else {
            if (state.running || state.applying) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { UtilityError(it) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("reference-repair-review"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    Text("Saved location", color = Muted, fontSize = 12.sp)
                    SelectionContainer {
                        Text(
                            state.source,
                            color = Ink,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    if (value != null) {
                        Text(
                            value.original.problem
                                ?: "The original is still available. This changes references, not its files.",
                            color =
                                if (value.original.problem != null) MaterialTheme.colorScheme.error
                                else Muted,
                            fontSize = 12.sp,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Border)
                        Text("Replacement", color = Muted, fontSize = 12.sp)
                        Text(
                            value.replacement.name,
                            color = Ink,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        SelectionContainer {
                            Text(
                                value.repair.target,
                                color = Ink,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                        value.replacement.entry?.let {
                            Text(
                                "${it.kind} · ${it.displaySize} · ${if (it.writable) "Read and write" else "Read only"}",
                                color = Muted,
                                fontSize = 12.sp,
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Border)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(state.carryTags, state::tags, enabled = !state.applying)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Carry saved tags to replacement",
                                    color = Ink,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    "Merges with existing tags. Turn off to keep tags at the saved location.",
                                    color = Muted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Text(
                            "Saved tags: ${value.repair.sourceTags.sorted().joinToString(", ").ifEmpty { "Untagged" }} → ${if (state.carryTags) "Removed from saved location" else "Kept"}",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                        Text(
                            "Replacement tags: ${value.repair.targetTags.sorted().joinToString(", ").ifEmpty { "Untagged" }} → ${(value.repair.targetTags + if (state.carryTags) value.repair.sourceTags else emptySet()).sorted().joinToString(", ").ifEmpty { "Untagged" }}",
                            color = Blue,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp, bottom = 14.dp),
                        )
                        Text(
                            "${value.repair.affectedCollections.size} affected ${if (value.repair.affectedCollections.size == 1) "collection" else "collections"}",
                            color = Ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Every exact reference to this saved location is relinked. Folder descendants and other provider document IDs are separate locations.",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                    } else if (state.running)
                        Text(
                            "Reading the replacement and current library…",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                }
                items(value?.repair?.affectedCollections.orEmpty(), key = { it.id }) { collection ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.CollectionsBookmark,
                            null,
                            Modifier.size(22.dp),
                            tint = tagColor(collection.color),
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(collection.name, color = Ink, fontSize = 13.sp)
                            Text(
                                if (value!!.repair.target in collection.paths)
                                    "${collection.paths.size} → ${collection.paths.size - 1} references · existing replacement is deduplicated"
                                else
                                    "${collection.paths.size} references · saved location → replacement",
                                color = Muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    HorizontalDivider(color = Border, thickness = .5.dp)
                }
            }
            HorizontalDivider(color = Border)
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(state::changeReplacement, enabled = !state.applying) {
                    Text("Choose replacement…")
                }
                TextButton(
                    state::refreshReview,
                    enabled = value != null && !state.running && !state.applying,
                ) {
                    Text("Refresh review")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    state::apply,
                    enabled =
                        value != null &&
                            !state.running &&
                            !state.applying &&
                            state.error == null &&
                            model.busy == null &&
                            (value.repair.affectedCollections.isNotEmpty() ||
                                state.carryTags && value.repair.sourceTags.isNotEmpty()),
                ) {
                    Text(if (state.applying) "Relinking…" else "Relink location")
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ReplacementPicker(model: FinderModel) {
    val state = model.organization.repair
    var address by rememberSaveable(state.folder) { mutableStateOf(state.folder) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AssistChip({ state.chooseFolder(model.workspace.path) }, label = { Text("Workspace") })
        if (model.hasAccess)
            AssistChip({ state.chooseFolder(model.deviceRoot.path) }, label = { Text("Tablet") })
        model.connectedLocations.forEach { location ->
            AssistChip({ state.chooseFolder(location.uri) }, label = { Text(location.name) })
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            { model.parentPath(state.folder)?.let(state::chooseFolder) },
            enabled = model.parentPath(state.folder) != null,
        ) {
            Icon(Icons.Outlined.ArrowUpward, "Parent replacement folder", tint = Muted)
        }
        OutlinedTextField(
            address,
            { address = it.take(8192) },
            singleLine = true,
            label = { Text("Replacement path or provider URI") },
            modifier = Modifier.weight(1f),
        )
        IconButton({ state.openPath(address) }) {
            Icon(Icons.Outlined.ArrowForward, "Open replacement location", tint = Blue)
        }
    }
    OutlinedTextField(
        state.query,
        state::search,
        singleLine = true,
        label = { Text("Find replacement") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { UtilityError(it) }
    if (state.listing.incomplete)
        Text(
            "Partial listing: ${state.listing.issues.firstOrNull()?.reason ?: if (state.listing.providerPending) "provider is still loading" else "listing budget reached"}. Known items can be reviewed independently.",
            Modifier.padding(horizontal = 14.dp),
            color = Muted,
            fontSize = 11.sp,
        )
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("replacement-picker-list")) {
        items(state.entries, key = { it.path }) { entry ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        if (entry.folder) state.chooseFolder(entry.path) else state.select(entry)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .heightIn(min = 46.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileGlyph(entry, 25.dp, thumbnail = false)
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(
                        entry.name,
                        color = Ink,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (entry.folder) "Open folder" else "${entry.kind} · ${entry.displaySize}",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Icon(
                    if (entry.folder) Icons.Outlined.ChevronRight else Icons.Outlined.Link,
                    "${if (entry.folder) "Browse" else "Use"} replacement ${entry.name}",
                    Modifier.size(18.dp),
                    tint = Muted,
                )
            }
            HorizontalDivider(color = Border, thickness = .5.dp)
        }
        if (state.entries.isEmpty() && !state.running)
            item {
                Text(
                    "No matching items in this folder",
                    Modifier.padding(16.dp),
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
    }
    HorizontalDivider(color = Border)
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(state.hidden, state::showHidden)
        Text("Hidden", fontSize = 12.sp, color = Muted)
        TextButton({ if (state.running) state.stop() else state.browse() }) {
            Text(if (state.running) "Stop" else "Refresh")
        }
        if (state.listing.resultLimit || state.listing.scanLimit)
            TextButton(state::more, enabled = !state.running) { Text("Load more") }
        Spacer(Modifier.weight(1f))
        Button({ state.folderEntry?.let(state::select) }, enabled = state.folderEntry != null) {
            Text("Use this folder")
        }
    }
}

@Composable
private fun UtilityError(message: String) {
    Text(
        message,
        Modifier.fillMaxWidth()
            .heightIn(max = 80.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
    )
}

@Composable
private fun OrganizationUtility(
    title: String,
    subtitle: String,
    dismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val config = LocalConfiguration.current
    Dialog(
        onDismissRequest = { if (dismissEnabled) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.width((config.screenWidthDp * .94f).coerceAtMost(760f).dp)
                .height((config.screenHeightDp * .88f).coerceAtMost(540f).dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            color = CanvasBackground,
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(subtitle, color = Muted, fontSize = 11.sp)
                    }
                    IconButton(dismiss, enabled = dismissEnabled) {
                        Icon(Icons.Outlined.Close, "Close $title", tint = Muted)
                    }
                }
                HorizontalDivider(color = Border)
                content()
            }
        }
    }
}
