package dev.droidfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A single, stationary toolbar: selection replaces commands, never adds a row. */
@Composable
fun RefinedToolbar(
    model: FinderModel,
    action: (String) -> Unit,
    searchActive: Boolean,
    onSearch: () -> Unit,
    searchFocus: FocusRequester,
    onSearchFocused: (Boolean) -> Unit,
    onCloseSearch: () -> Unit,
    menuExpanded: Boolean,
    onMenuChange: (Boolean) -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 1100
    val selection = model.selectedEntries
    var viewMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(AppBackground).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(
            Icons.AutoMirrored.Outlined.ArrowBack,
            "Back",
            { model.back() },
            enabled = model.tabs[model.activeTab].index > 0,
        )
        ToolButton(
            Icons.AutoMirrored.Outlined.ArrowForward,
            "Forward",
            model::forward,
            enabled =
                model.tabs[model.activeTab].index < model.tabs[model.activeTab].history.lastIndex,
        )
        if (!compact)
            ToolButton(
                Icons.Outlined.ArrowUpward,
                "Enclosing folder",
                model::up,
                enabled = model.parentPath() != null,
            )
        if (searchActive) {
            BasicTextField(
                value = model.query,
                onValueChange = model::updateQuery,
                modifier =
                    Modifier.weight(1f)
                        .padding(horizontal = 8.dp)
                        .height(36.dp)
                        .focusRequester(searchFocus)
                        .onFocusChanged { onSearchFocused(it.isFocused) },
                textStyle = TextStyle(color = Ink, fontSize = 13.sp, letterSpacing = 0.sp),
                cursorBrush = SolidColor(Blue),
                singleLine = true,
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(7.dp))
                            .background(CanvasBackground)
                            .border(1.dp, Border, RoundedCornerShape(7.dp))
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = Muted)
                        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            if (model.query.isEmpty())
                                Text("Search this location", fontSize = 12.sp, color = Muted)
                            inner()
                        }
                        ToolButton(
                            Icons.Outlined.Close,
                            "Close search",
                            onCloseSearch,
                            compact = true,
                        )
                    }
                },
            )
            ToolButton(
                Icons.Outlined.FilterAlt,
                "Search filters",
                { action("filters") },
                active = model.effectiveSearchRules.active,
            )
        } else {
            Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp)) {
                Text(
                    model.title(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                    letterSpacing = (-.35).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selection.isNotEmpty())
                    Text("${selection.size} selected", color = Muted, fontSize = 10.sp)
            }
            if (selection.isNotEmpty() && model.busy == null) {
                ToolButton(
                    Icons.Outlined.Close,
                    "Clear selection",
                    model::clearSelection,
                    compact = true,
                )
                if (model.isTrash && !compact) {
                    TextButton(onClick = { action("restore") }) {
                        Text("Restore", fontSize = 11.sp)
                    }
                    ToolButton(
                        Icons.Outlined.DeleteForever,
                        "Delete permanently",
                        { action("delete") },
                    )
                } else if (!model.isTrash) {
                    DragSelectionHandle(model)
                    if (!compact) {
                        ToolButton(Icons.Outlined.ContentCopy, "Copy", { action("copy") })
                        ToolButton(
                            Icons.Outlined.ContentCut,
                            "Cut",
                            { action("cut") },
                            enabled = selection.all { it.canDelete },
                        )
                        ToolButton(
                            Icons.Outlined.DriveFileRenameOutline,
                            "Rename",
                            { action("rename") },
                            enabled = selection.all { it.canRename },
                        )
                        ToolButton(
                            Icons.Outlined.DeleteOutline,
                            "Move to Trash",
                            { action("trash") },
                            enabled = selection.all { it.canDelete },
                        )
                    }
                }
                if (!compact) Spacer(Modifier.width(10.dp))
            }
            if (compact) {
                Box {
                    ToolButton(Icons.Outlined.Dashboard, "Change view", { viewMenu = true })
                    DropdownMenu(viewMenu, { viewMenu = false }) {
                        ViewMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text("${mode.title} view") },
                                onClick = {
                                    model.changeMode(mode)
                                    viewMenu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        SortBy.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Sort by ${sort.title}" +
                                            if (model.sort == sort)
                                                if (model.ascending) " ↑" else " ↓"
                                            else "",
                                    )
                                },
                                onClick = {
                                    model.changeSort(sort)
                                    viewMenu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        GroupBy.entries.forEach { group ->
                            DropdownMenuItem(
                                text = { Text("Group: ${group.title}") },
                                onClick = {
                                    model.changeGrouping(group)
                                    viewMenu = false
                                },
                            )
                        }
                    }
                }
            } else {
                ViewModeSelector(model)
                Spacer(Modifier.width(6.dp))
                FinderSortMenu(model)
            }
            if (model.clipboard != null)
                ToolButton(
                    Icons.Outlined.ContentPaste,
                    "Paste ${model.clipboard!!.files.size} items",
                    model::paste,
                    enabled = model.canCreate,
                )
            ToolButton(
                if (selection.singleOrNull()?.extension == "zip") Icons.Outlined.Unarchive
                else Icons.Outlined.FolderZip,
                if (selection.singleOrNull()?.extension == "zip") "Browse archive"
                else "Compress to ZIP",
                {
                    action(if (selection.singleOrNull()?.extension == "zip") "open" else "compress")
                },
                enabled = selection.isNotEmpty() && !model.isTrash && model.busy == null,
            )
            ToolButton(
                Icons.Outlined.Visibility,
                "Peek selected file",
                { action("quicklook") },
                enabled = model.focused != null && model.busy == null,
            )
            if (!compact || selection.isEmpty())
                ToolButton(Icons.Outlined.Search, "Search", onSearch)
            if (!compact) {
                ToolButton(
                    Icons.Outlined.CreateNewFolder,
                    "New folder",
                    { action("new") },
                    enabled = model.canCreate && model.busy == null,
                )
                ToolButton(
                    Icons.Outlined.VerticalSplit,
                    "Toggle preview pane",
                    model::togglePreview,
                    active = model.previewVisible,
                )
            }
        }
        Box {
            ToolButton(
                Icons.Outlined.MoreHoriz,
                "Actions",
                { onMenuChange(true) },
                enabled = model.busy == null,
            )
            ActionMenu(menuExpanded, { onMenuChange(false) }, model, action)
        }
    }
    HorizontalDivider(color = Border, thickness = .5.dp)
}

@Composable
private fun FinderSortMenu(model: FinderModel) {
    var sortMenu by remember { mutableStateOf(false) }
    // The same sorting/grouping options are shared by every browsing view.
    Box {
        ToolButton(
            Icons.AutoMirrored.Outlined.Sort,
            "Sort and view options",
            { sortMenu = true },
        )
        DropdownMenu(sortMenu, { sortMenu = false }) {
            Text(
                "SORT BY",
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                fontSize = 10.sp,
                color = Muted,
            )
            SortBy.entries.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Text(
                            type.title +
                                if (model.sort == type) if (model.ascending) " ↑" else " ↓" else "",
                        )
                    },
                    onClick = {
                        model.changeSort(type)
                        sortMenu = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Folders first") },
                trailingIcon = {
                    if (model.foldersFirst) Icon(Icons.Outlined.Check, null)
                },
                onClick = {
                    model.toggleFoldersFirst()
                    sortMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Show hidden files") },
                trailingIcon = {
                    if (model.showHidden) Icon(Icons.Outlined.Check, null)
                },
                onClick = {
                    model.toggleHidden()
                    sortMenu = false
                },
            )
            HorizontalDivider()
            Text(
                "GROUP BY",
                Modifier.padding(14.dp),
                fontSize = 10.sp,
                color = Muted,
            )
            GroupBy.entries.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.title) },
                    trailingIcon = {
                        if (model.grouping == group) Icon(Icons.Outlined.Check, null)
                    },
                    onClick = {
                        model.changeGrouping(group)
                        sortMenu = false
                    },
                )
            }
        }
    }
}
