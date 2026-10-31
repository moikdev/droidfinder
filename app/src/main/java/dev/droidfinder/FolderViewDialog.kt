package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun ViewSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f).padding(end = 12.dp),
            fontSize = 13.sp,
            color = if (enabled) Ink else Muted,
        )
        Switch(checked, null, enabled = enabled)
    }
}

@Composable
private fun <T> ViewChoice(
    label: String,
    value: String,
    choices: List<T>,
    title: (T) -> String,
    choose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = Ink)
        Box {
            TextButton(
                onClick = { open = true },
                modifier = Modifier.semantics { contentDescription = "$label: $value" },
            ) {
                Text(value, fontSize = 13.sp)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(open, { open = false }) {
                choices.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(title(option)) },
                        onClick = {
                            choose(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewSize(label: String, value: Int, range: IntRange, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, color = Ink)
        Text("$value", fontSize = 11.sp, color = Muted)
    }
    Slider(
        value.toFloat(),
        { change(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
fun FolderViewDialog(model: FinderModel, manage: () -> Unit, dismiss: () -> Unit) {
    var columns by remember { mutableStateOf(false) }
    var sizes by remember { mutableStateOf(false) }
    WorkspaceUtility(
        "View settings",
        model.displayPath(model.location),
        "folder-view-settings",
        dismiss,
        width = 480.dp,
    ) { limit ->
        if (model.folderViews.saving)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Blue)
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = limit)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            ViewSwitch(
                "Remember for this folder",
                model.folderRemembered,
                folderViewKey(model.location) != null && !model.folderViews.protectedFile,
                model::rememberFolderView,
            )
            Text(
                if (model.folderRemembered)
                    "Changes are saved for this folder across tabs, panes and windows."
                else "Folders without saved settings use this pane’s defaults.",
                fontSize = 11.sp,
                color = Muted,
            )
            model.folderViews.notice?.let { Text(it, fontSize = 11.sp, color = Muted) }
            model.folderViews.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (!model.folderViews.protectedFile)
                    TextButton(onClick = model.folderViews::retry) { Text("Retry saving") }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Border)
            ViewChoice("View", model.mode.title, ViewMode.entries, { it.title }, model::changeMode)
            ViewChoice("Sort", model.sort.title, SortBy.entries, { it.title }) {
                if (model.sort != it) model.changeSort(it)
            }
            ViewChoice(
                "Order",
                if (model.ascending) "Ascending" else "Descending",
                listOf(true, false),
                { if (it) "Ascending" else "Descending" },
            ) {
                if (model.ascending != it) model.changeSort(model.sort)
            }
            ViewChoice(
                "Group",
                model.grouping.title,
                GroupBy.entries,
                { it.title },
                model::changeGrouping,
            )
            ViewSwitch("Folders first", model.foldersFirst) { model.toggleFoldersFirst() }
            ViewSwitch("Show hidden files", model.showHidden) { model.toggleHidden() }
            TextButton(
                onClick = { columns = !columns },
                modifier = Modifier.fillMaxWidth().testTag("view-columns-toggle"),
            ) {
                Text("List columns", Modifier.weight(1f))
                Icon(if (columns) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (columns) {
                val c = model.detailColumns
                ViewSwitch("Adapt columns to pane width", c.adaptive) {
                    model.changeDetailColumns(c.copy(adaptive = it))
                }
                Text(
                    if (c.adaptive) "Some columns hide in narrow panes."
                    else "All enabled columns stay visible. Scroll horizontally when needed.",
                    fontSize = 11.sp,
                    color = Muted,
                )
                ViewSwitch("Date modified column", c.date) {
                    model.changeDetailColumns(c.copy(date = it))
                }
                if (c.date)
                    ViewSize("Date column width", c.dateWidth, 100..320) {
                        model.changeDetailColumns(c.copy(dateWidth = it))
                    }
                ViewSwitch("Size column", c.size) { model.changeDetailColumns(c.copy(size = it)) }
                if (c.size)
                    ViewSize("Size column width", c.sizeWidth, 60..180) {
                        model.changeDetailColumns(c.copy(sizeWidth = it))
                    }
                ViewSwitch("Kind column", c.kind) { model.changeDetailColumns(c.copy(kind = it)) }
                if (c.kind)
                    ViewSize("Kind column width", c.kindWidth, 80..260) {
                        model.changeDetailColumns(c.copy(kindWidth = it))
                    }
                TextButton(onClick = model::fitColumnsToContents) {
                    Text("Fit list columns to contents")
                }
            }
            TextButton(onClick = { sizes = !sizes }, modifier = Modifier.fillMaxWidth()) {
                Text("Icon, browsing column and preview sizes", Modifier.weight(1f))
                Icon(if (sizes) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (sizes) {
                ViewSize("Icon size", model.iconSize, 90..200, model::changeIconSize)
                ViewSize(
                    "Browsing column width",
                    model.columnWidth,
                    150..500,
                    model::changeColumnWidth,
                )
                ViewSize("Preview width", model.previewWidth, 200..480, model::changePreviewWidth)
            }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(color = Border)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = model::useViewAsPaneDefaults) {
                Text("Use as pane defaults", fontSize = 12.sp)
            }
            TextButton(onClick = manage) {
                Text("Saved folders (${model.folderViews.values.size})", fontSize = 12.sp)
            }
            TextButton(
                onClick = { model.rememberFolderView(false) },
                enabled = model.folderRemembered && !model.folderViews.protectedFile,
            ) {
                Text("Reset folder", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun FolderViewsManagerDialog(model: FinderModel, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    WorkspaceUtility(
        "Remembered folders",
        "Reset saved views to use each pane’s defaults",
        "folder-view-manager",
        dismiss,
    ) { limit ->
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("Find a folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        model.folderViews.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!model.folderViews.protectedFile)
                TextButton(onClick = model.folderViews::retry) { Text("Retry saving") }
        }
        if (model.folderViews.values.isEmpty() && model.folderViews.error == null)
            Text(
                "No folders have saved view settings.",
                color = Muted,
                modifier = Modifier.padding(20.dp),
            )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = limit),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(
                model.folderViews.values.keys.sorted().filter {
                    model.displayPath(it).contains(query, true)
                },
                key = { it },
            ) { path ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp), tint = Blue)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            model.title(path),
                            fontSize = 13.sp,
                            color = Ink,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            model.displayPath(path),
                            fontSize = 11.sp,
                            color = Muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { model.forgetFolderView(path) },
                        enabled = !model.folderViews.protectedFile,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Reset folder view ${model.displayPath(path)}"
                            },
                    ) {
                        Text("Reset")
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
}
