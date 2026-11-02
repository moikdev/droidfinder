@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalPhoneLayout = staticCompositionLocalOf { false }

@Composable
internal fun PhoneToolbar(
    model: FinderModel,
    action: (String) -> Unit,
    addressActive: Boolean,
    addressValue: TextFieldValue,
    onAddressChange: (TextFieldValue) -> Unit,
    addressFocus: FocusRequester,
    onAddressFocused: (Boolean) -> Unit,
    onAddressCommit: () -> Unit,
    onAddressClose: () -> Unit,
    searchActive: Boolean,
    onSearch: () -> Unit,
    searchFocus: FocusRequester,
    focusRequest: Int,
    onSearchFocused: (Boolean) -> Unit,
    onCloseSearch: () -> Unit,
    menuExpanded: Boolean,
    onMenuChange: (Boolean) -> Unit,
) {
    val editing = addressActive || searchActive
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(AppBackground)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(
            if (editing || model.selected.isNotEmpty()) Icons.Outlined.Close
            else Icons.AutoMirrored.Outlined.ArrowBack,
            when {
                addressActive -> "Close address bar"
                searchActive -> "Close search"
                model.selected.isNotEmpty() -> "Clear selection"
                else -> "Back"
            },
            {
                when {
                    addressActive -> onAddressClose()
                    searchActive -> onCloseSearch()
                    model.selected.isNotEmpty() -> model.clearSelection()
                    else -> if (!model.back()) model.up()
                }
            },
            enabled =
                editing ||
                    model.selected.isNotEmpty() ||
                    model.tabs[model.activeTab].index > 0 ||
                    model.parentPath() != null,
        )
        if (editing) {
            var placed by remember(addressActive, searchActive) { mutableStateOf(false) }
            val focus = if (addressActive) addressFocus else searchFocus
            LaunchedEffect(placed, focusRequest) { if (placed) focus.requestFocus() }
            val field =
                Modifier.weight(1f)
                    .heightIn(min = 48.dp)
                    .focusRequester(focus)
                    .onGloballyPositioned { placed = true }
                    .onFocusChanged {
                        if (addressActive) onAddressFocused(it.isFocused)
                        else onSearchFocused(it.isFocused)
                    }
                    .semantics {
                        contentDescription = if (addressActive) "Address bar" else "Search files"
                    }
                    .onPreInterceptKeyBeforeSoftKeyboard {
                        if (addressActive && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                            if (it.type == KeyEventType.KeyDown) onAddressCommit()
                            true
                        } else false
                    }
            val decoration: @Composable (@Composable () -> Unit) -> Unit = { inner ->
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(CanvasBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, Border, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (!addressActive && model.query.isEmpty())
                        Text("Search files", color = Muted, fontSize = 15.sp)
                    inner()
                }
            }
            if (addressActive)
                BasicTextField(
                    addressValue,
                    onAddressChange,
                    field,
                    singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(Blue),
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onGo = { onAddressCommit() },
                        ),
                    decorationBox = decoration,
                )
            else
                SearchQueryField(
                    model = model,
                    modifier = Modifier.weight(1f),
                    fieldModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .focusRequester(searchFocus)
                        .onGloballyPositioned { placed = true },
                    textStyle = TextStyle(color = Ink, fontSize = 15.sp),
                    onFocused = onSearchFocused,
                    action = action,
                    decorationBox = decoration,
                )
            if (!addressActive)
                ToolButton(
                    Icons.Outlined.FilterAlt,
                    "Search filters",
                    { action("filters") },
                    active = model.effectiveSearchRules.active,
                )
        } else {
            Column(
                Modifier.weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Go to folder") { action("path") }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                Text(
                    if (model.selected.isNotEmpty()) "${model.selected.size} selected"
                    else model.title(),
                    color = Ink,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (model.multiSelecting) "Tap items to select · Back to finish"
                    else model.listingCountText,
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.selected.isNotEmpty())
                ToolButton(Icons.Outlined.SelectAll, "Select all", model::selectAll)
            else ToolButton(Icons.Outlined.Search, "Search", onSearch)
        }
        Box {
            ToolButton(
                Icons.Outlined.MoreVert,
                "Actions",
                {
                    model.selectedEntries.firstOrNull()?.let { model.requestContext(it) }
                        ?: onMenuChange(true)
                },
            )
            ActionMenu(menuExpanded, { onMenuChange(false) }, model, action)
        }
    }
    HorizontalDivider(color = Border, thickness = .5.dp)
}

@Composable
internal fun PhoneBottomBar(
    model: FinderModel,
    locations: () -> Unit,
    action: (String) -> Unit,
    more: () -> Unit,
) {
    val selected = model.actionEntries
    Column(Modifier.fillMaxWidth().background(AppBackground).testTag("phone-actions")) {
        HorizontalDivider(color = Border, thickness = .5.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            if (selected.isNotEmpty()) {
                if (model.isTrash) {
                    PhoneAction(Icons.Outlined.Restore, "Restore", model.busy == null) {
                        action("restore")
                    }
                    PhoneAction(Icons.Outlined.DeleteForever, "Delete", model.busy == null) {
                        action("delete")
                    }
                } else {
                    PhoneAction(Icons.Outlined.ContentCopy, "Copy") {
                        action("copy")
                        model.clearSelection()
                    }
                    PhoneAction(Icons.Outlined.ContentCut, "Cut", selected.all { it.canDelete }) {
                        action("cut")
                        model.clearSelection()
                    }
                    PhoneAction(
                        Icons.Outlined.DeleteOutline,
                        "Trash",
                        selected.all { it.canDelete } && model.busy == null,
                    ) {
                        action("trash")
                    }
                }
                PhoneAction(Icons.Outlined.MoreHoriz, "More", click = more)
            } else {
                PhoneAction(Icons.Outlined.FolderOpen, "Locations", click = locations)
                PhoneAction(Icons.Outlined.Add, "New", model.canCreate && model.busy == null) {
                    action("phoneNew")
                }
                PhoneAction(Icons.Outlined.Dashboard, "View") { action("phoneView") }
                PhoneAction(Icons.Outlined.SyncAlt, "Transfers") { action("transfers") }
                if (model.clipboard != null)
                    PhoneAction(Icons.Outlined.ContentPaste, "Paste", model.canCreate) {
                        action("paste")
                    }
            }
        }
    }
}

@Composable
private fun RowScope.PhoneAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    click: () -> Unit,
) {
    Column(
        Modifier.weight(1f)
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = click)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            null,
            Modifier.size(23.dp),
            tint = if (enabled) Blue else Muted.copy(alpha = .5f),
        )
        Text(
            label,
            Modifier.padding(top = 4.dp),
            color = if (enabled) Ink else Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneViewDialog(model: FinderModel, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("View and sort") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text("View", color = Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ViewMode.entries.forEach { mode ->
                        FilterChip(
                            model.mode == mode,
                            { model.changeMode(mode) },
                            label = { Text(mode.title) },
                            modifier =
                                Modifier.heightIn(min = 48.dp).testTag("phone-view-${mode.name}"),
                        )
                    }
                }
                Text("Sort by", Modifier.padding(top = 12.dp), color = Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortBy.entries.forEach { sort ->
                        FilterChip(
                            model.sort == sort,
                            { model.changeSort(sort) },
                            label = { Text(sort.title) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                TextButton(onClick = { model.changeSort(model.sort) }) {
                    Text(if (model.ascending) "Ascending ↑" else "Descending ↓")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        model.foldersFirst,
                        { model.toggleFoldersFirst() },
                        label = { Text("Folders first") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        model.showHidden,
                        { model.toggleHidden() },
                        label = { Text("Hidden files") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                Text("Group by", Modifier.padding(top = 12.dp), color = Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupBy.entries.forEach { group ->
                        FilterChip(
                            model.grouping == group,
                            { model.changeGrouping(group) },
                            label = { Text(group.title) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
}

@Composable
internal fun PhoneTabStrip(model: FinderModel) {
    if (
        model.tabs.size <= 1 ||
            androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    )
        return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Sidebar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { model.tabOverviewRequested = true },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.Tab, null, Modifier.size(18.dp))
            Text(
                "${model.tabs.size} tabs · ${model.title()}",
                Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ToolButton(Icons.Outlined.Add, "New tab", { model.newTab(atEnd = true) })
    }
}
