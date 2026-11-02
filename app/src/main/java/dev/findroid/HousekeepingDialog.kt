package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date

private data class CleanupReview(val trash: List<TrashSpaceItem>, val cache: List<CacheSpaceItem>)

@Composable
internal fun HousekeepingDialog(model: FinderModel) {
    val state = model.housekeeping
    val report = state.report
    val config = LocalConfiguration.current
    val list = rememberLazyListState()
    LaunchedEffect(state.view) { list.scrollToItem(0) }
    LaunchedEffect(state.outcome, state.running) {
        if (state.outcome != null && !state.running) list.scrollToItem(0)
    }
    var review by remember { mutableStateOf<CleanupReview?>(null) }
    var details by remember { mutableStateOf(false) }
    val idle = !state.running && model.busy == null
    Dialog(
        onDismissRequest = state::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.width((config.screenWidthDp * .94f).coerceAtMost(720f).dp)
                .height((config.screenHeightDp * .86f).coerceAtMost(510f).dp)
                .testTag("housekeeping"),
            shape = RoundedCornerShape(14.dp),
            color = CanvasBackground,
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(62.dp).padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CleaningServices, null, tint = Blue)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            "Trash & cache",
                            fontSize = 18.sp,
                            color = Ink,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Storage housekeeping", fontSize = 12.sp, color = Muted)
                    }
                    if (state.running) TextButton(onClick = state::stop) { Text("Stop") }
                    else
                        ToolButton(
                            Icons.Outlined.Refresh,
                            "Refresh housekeeping",
                            state::refresh,
                            enabled = model.busy == null,
                        )
                    ToolButton(Icons.Outlined.Info, "Housekeeping details", { details = true })
                    ToolButton(Icons.Outlined.Close, "Close housekeeping", state::close)
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth()
                        .background(Sidebar)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HousekeepingView.entries.forEach { view ->
                        FilterChip(
                            state.view == view,
                            { state.changeView(view) },
                            label = { Text(view.title) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (report != null && !state.running)
                        Text(
                            "Measured ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.at))}",
                            Modifier.align(Alignment.CenterVertically),
                            fontSize = 11.sp,
                            color = Muted,
                        )
                }
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("housekeeping-list"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.error?.let { problem ->
                        item {
                            Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                    state.outcome?.let { outcome ->
                        item {
                            Surface(color = Sidebar, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    outcome,
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    fontSize = 12.sp,
                                    color = Ink,
                                )
                            }
                        }
                    }
                    if (report == null)
                        item {
                            Text(
                                if (state.running) "Measuring local Trash and disposable caches…"
                                else "Refresh to measure storage.",
                                color = Muted,
                            )
                        }
                    else if (state.view == HousekeepingView.TRASH) {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    HousekeepingTotal(
                                        report.trash.logicalBytes { it.disk.bytes },
                                        "${report.trash.size} ${if (report.trash.size == 1) "item" else "items"} in Trash",
                                        report.trashIncomplete,
                                    )
                                }
                                RetentionMenu(
                                    "Review after",
                                    state.trashDays,
                                    listOf(0, 7, 30, 90),
                                    idle,
                                    state::changeTrashDays,
                                )
                            }
                            Text(
                                "Items stay here until you confirm permanent deletion.",
                                color = Muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("Select items", color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { state.selectTrash(true) },
                                    enabled = idle && state.trashDays > 0,
                                ) {
                                    Text("Select expired")
                                }
                                TextButton(
                                    onClick = { state.selectTrash(false) },
                                    enabled = idle && report.trash.isNotEmpty(),
                                ) {
                                    Text("Select all")
                                }
                            }
                        }
                        if (report.trash.isEmpty())
                            item {
                                HousekeepingEmpty(
                                    "Trash is empty",
                                    "Deleted items will appear here for recovery.",
                                )
                            }
                        items(report.trash, key = { it.file.path }) { item ->
                            val date =
                                item.trashedAt?.let {
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
                                } ?: "Date unavailable · kept until selected"
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 60.dp)
                                    .toggleable(
                                        item.file.path in state.selected,
                                        enabled = idle && item.disk.complete,
                                        role = Role.Checkbox,
                                    ) {
                                        state.toggle(item.file.path)
                                    }
                                    .semantics {
                                        contentDescription =
                                            "Select Trash item ${item.file.name}, ${item.original ?: item.file.parentFile?.name}"
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    item.file.path in state.selected,
                                    null,
                                    enabled = idle && item.disk.complete,
                                )
                                FileGlyph(Entry(item.file), 27.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(
                                        item.file.name,
                                        fontSize = 13.sp,
                                        color = Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (item.trashedAt != null) "Moved to Trash $date"
                                        else date,
                                        color = Muted,
                                        fontSize = 11.sp,
                                    )
                                    Text(
                                        item.original?.let(model::displayPath)
                                            ?: "Original location unavailable",
                                        color = Muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    item.disk.issue?.let {
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                                Text(
                                    (if (!item.disk.complete) "≥ " else "") +
                                        formatSize(item.disk.bytes),
                                    color = Muted,
                                    fontSize = 12.sp,
                                )
                            }
                            HorizontalDivider(color = Border)
                        }
                    } else {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    HousekeepingTotal(
                                        report.cache.logicalBytes { it.disk.bytes },
                                        "Disposable files on disk",
                                        report.cacheIncomplete,
                                    )
                                }
                                RetentionMenu(
                                    "Older than",
                                    state.cacheDays,
                                    listOf(1, 7, 30),
                                    idle,
                                    state::changeCacheDays,
                                )
                            }
                            Text(
                                "Open previews are protected. Cleanup keeps shared files younger than 24 hours.",
                                color = Muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("Select disposable files", color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { state.selectCache(true) }, enabled = idle) {
                                    Text("Select expired")
                                }
                                TextButton(onClick = { state.selectCache(false) }, enabled = idle) {
                                    Text("Select idle")
                                }
                            }
                        }
                        items(
                            CacheKind.entries.chunked(
                                if (config.screenWidthDp < 700 || config.fontScale > 1.3f) 1 else 2,
                            ),
                        ) { kinds ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                kinds.forEach { kind ->
                                    val units = report.cache.filter { it.kind == kind }
                                    val eligible = units.filter {
                                        it.eligible(System.currentTimeMillis())
                                    }
                                    val chosen = eligible.count { it.file.path in state.selected }
                                    Surface(
                                        Modifier.weight(1f),
                                        color = Sidebar,
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .heightIn(min = 64.dp)
                                                .toggleable(
                                                    chosen > 0 && chosen == eligible.size,
                                                    enabled = idle && eligible.isNotEmpty(),
                                                    role = Role.Checkbox,
                                                ) {
                                                    state.toggleCacheKind(kind)
                                                }
                                                .semantics {
                                                    contentDescription = "Select ${kind.title}"
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                chosen > 0 && chosen == eligible.size,
                                                null,
                                                enabled = idle && eligible.isNotEmpty(),
                                            )
                                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                                Text(kind.title, color = Ink, fontSize = 13.sp)
                                                Text(
                                                    "${eligible.size} idle · ${units.size - eligible.size} protected",
                                                    color = Muted,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                            Text(
                                                (if (units.any { !it.disk.complete }) "≥ "
                                                else "") +
                                                    formatSize(
                                                        units.logicalBytes { it.disk.bytes },
                                                    ),
                                                fontSize = 11.sp,
                                                color = Muted,
                                                modifier = Modifier.padding(horizontal = 10.dp),
                                            )
                                        }
                                    }
                                }
                                if (
                                    kinds.size == 1 &&
                                        config.screenWidthDp >= 700 &&
                                        config.fontScale <= 1.3f
                                )
                                    Spacer(Modifier.weight(1f))
                            }
                        }
                        item {
                            HorizontalDivider(color = Border)
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Thumbnail memory", color = Ink, fontSize = 13.sp)
                                    Text(
                                        "${formatSize(report.thumbnails.bytes.toLong())} · ${report.thumbnails.cached} images",
                                        color = Muted,
                                        fontSize = 11.sp,
                                    )
                                }
                                TextButton(onClick = state::clearThumbnails, enabled = idle) {
                                    Text("Clear memory")
                                }
                            }
                            Text(
                                "Cache files regenerate when needed. Settings, originals and recovery records are kept.",
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        }
                    }
                    if (report?.issues?.isNotEmpty() == true)
                        item {
                            TextButton(onClick = { details = true }) {
                                Text("${report.issueCount} measurement notices")
                            }
                        }
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 62.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.view == HousekeepingView.TRASH)
                        TextButton(
                            onClick = {
                                state.close()
                                model.navigate("@trash")
                            },
                        ) {
                            Text("Open Trash")
                        }
                    else Text("Logical sizes", color = Muted, fontSize = 11.sp)
                    if (state.selectedCount > 0)
                        Text(
                            "${state.selectedCount} selected · ${formatSize(state.selectedBytes)}",
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    else Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = state::clearSelection,
                        enabled = state.selected.isNotEmpty() && idle,
                    ) {
                        Text("Clear")
                    }
                    Button(
                        onClick = {
                            review =
                                CleanupReview(
                                    state.selectedTrash.toList(),
                                    state.selectedCache.toList(),
                                )
                        },
                        enabled = idle && state.selectedCount > 0,
                    ) {
                        Text("Review cleanup")
                    }
                }
            }
        }
    }
    review?.let { pending ->
        val rows =
            pending.trash.map {
                Triple(it.file.name, it.original?.let(model::displayPath) ?: "Trash", it.disk.bytes)
            } + pending.cache.map { Triple(it.displayName, it.kind.title, it.disk.bytes) }
        AlertDialog(
            onDismissRequest = { review = null },
            title = {
                Text(
                    if (pending.trash.isNotEmpty())
                        "Permanently delete ${rows.size} ${if (rows.size == 1) "item" else "items"}?"
                    else "Clear ${rows.size} cache ${if (rows.size == 1) "unit" else "units"}?",
                )
            },
            text = {
                Column {
                    Text(
                        if (pending.trash.isNotEmpty())
                            "These items cannot be restored after deletion. Session Undo and Redo will be cleared."
                        else
                            "Open, changed or recently shared files will be skipped. Cached files regenerate when needed.",
                        fontSize = 12.sp,
                    )
                    Text(
                        "${formatSize(rows.logicalBytes { it.third })} logical size",
                        Modifier.padding(vertical = 12.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                    LazyColumn(Modifier.heightIn(max = 260.dp).testTag("cleanup-review-list")) {
                        items(rows) { (name, parent, bytes) ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(name, fontSize = 13.sp)
                                Text(
                                    "$parent · ${formatSize(bytes)}",
                                    color = Muted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.cleanHousekeeping(pending.trash, pending.cache)
                        review = null
                        state.clearSelection()
                    },
                    enabled = model.busy == null,
                ) {
                    Text(
                        if (pending.trash.isNotEmpty()) "Delete permanently" else "Clear cache",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Cancel") } },
        )
    }
    if (details)
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("About housekeeping") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Trash age starts when an item is moved to Trash, not its modification date. Older entries without a recorded date are never selected as expired. Retention selections are reviewed; nothing is deleted automatically.\n\nSizes are logical file lengths, not guaranteed free space. Links are not followed. Incomplete or changed items are kept. Measurements are snapshots; refresh after closing another window's preview.\n\nCache age uses the cache unit's last-used or creation time. This cleanup skips shared files and text clips younger than 24 hours. Android and existing bounded preview/clipboard caches may reclaim them sooner. Workspace files, settings, transfer staging, and recovery records are excluded.",
                        fontSize = 12.sp,
                    )
                    if (report != null && report.issueCount > report.issues.size)
                        Text(
                            "Showing ${report.issues.size} of ${report.issueCount} notices",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    report?.issues.orEmpty().forEach {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } },
        )
}

@Composable
private fun HousekeepingTotal(bytes: Long, subtitle: String, partial: Boolean) {
    Text(
        (if (partial) "≥ " else "") + formatSize(bytes),
        color = Ink,
        fontSize = 27.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Text(subtitle, color = Muted, fontSize = 12.sp)
}

@Composable
private fun HousekeepingEmpty(title: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.DeleteOutline, null, tint = Muted, modifier = Modifier.size(30.dp))
        Text(title, color = Ink, modifier = Modifier.padding(top = 8.dp))
        Text(message, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun RetentionMenu(
    label: String,
    days: Int,
    choices: List<Int>,
    enabled: Boolean,
    choose: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(if (days == 0) "Manual selection" else "$label $days days", fontSize = 12.sp)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { value ->
                DropdownMenuItem(
                    text = { Text(if (value == 0) "Manual selection" else "$value days") },
                    onClick = {
                        choose(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
