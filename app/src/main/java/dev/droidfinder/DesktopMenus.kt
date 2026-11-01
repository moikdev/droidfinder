package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Observe at Final pass: file targets consume their right click before background sees it. */
fun Modifier.folderContextMenu(model: FinderModel): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { coordinates = it }
        .pointerInput(model.location) {
            awaitPointerEventScope {
                var secondaryDown = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (secondaryDown && event.type == PointerEventType.Release) {
                        secondaryDown = false
                        model.requestBackgroundContext(
                            coordinates?.localToWindow(event.changes.first().position),
                        )
                        event.changes.forEach { it.consume() }
                    }
                    if (
                        event.type == PointerEventType.Press &&
                            event.buttons.isSecondaryPressed &&
                            event.changes.none { it.isConsumed }
                    ) {
                        event.changes.firstOrNull()?.let { pointer ->
                            secondaryDown = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        }
}

@Composable
fun CursorMenu(
    point: Offset?,
    onDismiss: () -> Unit,
    tag: String = "file-context-menu",
    content: @Composable ColumnScope.() -> Unit,
) {
    val provider =
        remember(point) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = point?.x?.toInt() ?: (windowSize.width / 2)
                    val y = point?.y?.toInt() ?: (windowSize.height / 3)
                    val left =
                        if (x + popupContentSize.width > windowSize.width - 8)
                            x - popupContentSize.width
                        else x
                    val top =
                        when {
                            y + popupContentSize.height <= windowSize.height - 8 -> y
                            y - popupContentSize.height >= 8 -> y - popupContentSize.height
                            else -> windowSize.height - popupContentSize.height - 8
                        }
                    return IntOffset(
                        left.coerceIn(
                            8,
                            (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8),
                        ),
                        top.coerceIn(
                            8,
                            (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8),
                        ),
                    )
                }
            }
        }
    val height = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(160).dp
    Popup(provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(Unit) { focus.requestFocus() }
        Surface(
            Modifier.width(250.dp)
                .heightIn(max = height)
                .testTag(tag)
                .semantics { paneTitle = "Context menu" }
                .focusRequester(focus)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false
                    else
                        when (it.key) {
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            Key.DirectionDown -> {
                                focusManager.moveFocus(FocusDirection.Next)
                                true
                            }
                            Key.DirectionUp -> {
                                focusManager.moveFocus(FocusDirection.Previous)
                                true
                            }
                            else -> false
                        }
                }
                .focusable(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
            color = SubtleSurface,
            shadowElevation = 12.dp,
            border = BorderStroke(.5.dp, Border),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(vertical = 5.dp),
                content = content,
            )
        }
    }
}

@Composable
fun ContextItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    shortcut: String? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (enabled) Ink else Muted.copy(alpha = .5f),
            )
        },
        leadingIcon = {
            Icon(
                icon,
                null,
                Modifier.size(17.dp),
                tint = if (enabled) Muted else Muted.copy(alpha = .4f),
            )
        },
        trailingIcon = shortcut?.let { { Text(it, color = Muted, fontSize = 10.sp) } },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(LocalFinderDensity.current.rowHeight),
        contentPadding = PaddingValues(horizontal = 13.dp),
    )
}

@Composable
fun FileActionsButton(model: FinderModel, entry: Entry) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    IconButton(
        onClick = {
            val position = coordinates?.let {
                it.localToWindow(Offset(0f, it.size.height.toFloat()))
            }
            model.requestContext(entry, position)
        },
        modifier =
            Modifier.size(LocalFinderDensity.current.targetSize).onGloballyPositioned {
                coordinates = it
            },
    ) {
        Icon(
            Icons.Outlined.MoreHoriz,
            "Actions for ${entry.name}",
            Modifier.size(17.dp),
            tint = Muted,
        )
    }
}

@Composable
fun FileContextMenu(
    request: FinderContextRequest,
    model: FinderModel,
    action: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val idle = model.busy == null
    var section by remember(request) { mutableStateOf("") }
    fun run(command: String) {
        onDismiss()
        action(command)
    }
    CursorMenu(request.point, onDismiss) {
        if (section.isNotEmpty()) {
            ContextItem("Back", Icons.AutoMirrored.Outlined.ArrowBack) { section = "" }
            HorizontalDivider(color = Border)
            when (section) {
                "view" ->
                    ViewMode.entries.forEach { mode ->
                        ContextItem(
                            "${mode.title} view",
                            if (model.mode == mode) Icons.Outlined.Check
                            else Icons.Outlined.Dashboard,
                        ) {
                            model.changeMode(mode)
                            onDismiss()
                        }
                    }
                "sort" ->
                    SortBy.entries.forEach { sort ->
                        ContextItem(
                            sort.title +
                                if (model.sort == sort) if (model.ascending) " ↑" else " ↓" else "",
                            Icons.AutoMirrored.Outlined.Sort,
                        ) {
                            model.changeSort(sort)
                            onDismiss()
                        }
                    }
                "group" ->
                    GroupBy.entries.forEach { group ->
                        ContextItem(
                            group.title,
                            if (model.grouping == group) Icons.Outlined.Check
                            else Icons.Outlined.ViewAgenda,
                        ) {
                            model.changeGrouping(group)
                            onDismiss()
                        }
                    }
            }
        } else {
            val items = model.selectedEntries
            if (request.background) {
                ContextItem(
                    "New folder",
                    Icons.Outlined.CreateNewFolder,
                    model.canCreate,
                    "Ctrl Shift N",
                ) {
                    run("new")
                }
                ContextItem(
                    "New text file…",
                    Icons.AutoMirrored.Outlined.NoteAdd,
                    model.canCreate,
                ) {
                    run("newText")
                }
                ContextItem(
                    "Paste",
                    Icons.Outlined.ContentPaste,
                    model.clipboard != null && model.canCreate,
                    "Ctrl V",
                ) {
                    run("paste")
                }
                HorizontalDivider(color = Border)
                ContextItem(
                    "Select all",
                    Icons.Outlined.SelectAll,
                    model.entries.isNotEmpty(),
                    "Ctrl A",
                ) {
                    model.selectAll()
                    onDismiss()
                }
                ContextItem("View", Icons.Outlined.Dashboard, shortcut = "›") { section = "view" }
                ContextItem("Sort by", Icons.AutoMirrored.Outlined.Sort, shortcut = "›") {
                    section = "sort"
                }
                ContextItem("Group by", Icons.Outlined.ViewAgenda, shortcut = "›") {
                    section = "group"
                }
                ContextItem("Refresh", Icons.Outlined.Refresh, shortcut = "Ctrl R") {
                    model.refresh()
                    onDismiss()
                }
                ContextItem("Folder info", Icons.Outlined.Info, model.folderInfo != null) {
                    run("folderInfo")
                }
                if (model.isTrash)
                    ContextItem(
                        "Empty Trash…",
                        Icons.Outlined.DeleteForever,
                        model.entries.isNotEmpty(),
                    ) {
                        run("empty")
                    }
            } else if (items.isEmpty()) {
                ContextItem("Item unavailable", Icons.Outlined.Info, false) {}
                ContextItem("Refresh", Icons.Outlined.Refresh) {
                    model.refresh()
                    onDismiss()
                }
            } else if (model.isTrash) {
                ContextItem("Restore", Icons.Outlined.Restore, idle) { run("restore") }
                ContextItem("Get info", Icons.Outlined.Info) { run("info") }
                ContextItem("Delete permanently…", Icons.Outlined.DeleteForever, idle) {
                    run("delete")
                }
            } else {
                val one = items.singleOrNull()
                if (one != null) {
                    ContextItem(
                        if (one.folder) "Open folder"
                        else if (one.extension == "zip") "Browse archive" else "Open",
                        Icons.Outlined.FolderOpen,
                    ) {
                        run("open")
                    }
                    ContextItem("Quick Look", Icons.Outlined.Visibility, shortcut = "Space") {
                        run("quicklook")
                    }
                    if (one.folder)
                        ContextItem("Open in new tab", Icons.Outlined.Tab) { run("tab") }
                    else
                        ContextItem("Open with app…", Icons.AutoMirrored.Outlined.OpenInNew) {
                            run("external")
                        }
                    if (one.folder) {
                        ContextItem(
                            if (one.path in model.favorites) "Remove from favorites"
                            else "Add to favorites",
                            Icons.Outlined.StarOutline,
                        ) {
                            run("favorite")
                        }
                        ContextItem("Customize folder…", Icons.Outlined.Palette) {
                            run("appearance")
                        }
                    }
                }
                ContextItem("Get info", Icons.Outlined.Info, shortcut = "Ctrl I") { run("info") }
                HorizontalDivider(color = Border)
                ContextItem("Copy", Icons.Outlined.ContentCopy, shortcut = "Ctrl C") { run("copy") }
                ContextItem(
                    "Cut",
                    Icons.Outlined.ContentCut,
                    items.all { it.canDelete },
                    "Ctrl X",
                ) {
                    run("cut")
                }
                if (one?.folder == true)
                    ContextItem(
                        "Paste into folder",
                        Icons.Outlined.ContentPaste,
                        model.clipboard != null && one.writable,
                    ) {
                        model.paste(one.path)
                        onDismiss()
                    }
                ContextItem(
                    if (items.size > 1) "Rename ${items.size} items…" else "Rename…",
                    Icons.Outlined.DriveFileRenameOutline,
                    items.all { it.canRename } && idle,
                    "F2",
                ) {
                    run("rename")
                }
                ContextItem(
                    "Duplicate",
                    Icons.Outlined.FileCopy,
                    enabled = idle,
                    shortcut = "Meta D",
                ) {
                    run("duplicate")
                }
                ContextItem("Copy to…", Icons.Outlined.FolderCopy) { run("copyTo") }
                ContextItem(
                    "Move to…",
                    Icons.AutoMirrored.Outlined.DriveFileMove,
                    items.all { it.canDelete },
                ) {
                    run("moveTo")
                }
                HorizontalDivider(color = Border)
                ContextItem("Compress to ZIP…", Icons.Outlined.FolderZip, idle) { run("compress") }
                if (one?.extension == "zip") {
                    ContextItem(
                        "Extract here",
                        Icons.Outlined.Unarchive,
                        model.canCreate && idle && !model.effectiveSearchRules.active,
                    ) {
                        run("extract")
                    }
                    ContextItem("Extract to…", Icons.Outlined.DriveFolderUpload, idle) {
                        run("extractTo")
                    }
                }
                ContextItem("Tags…", Icons.AutoMirrored.Outlined.Label, idle) { run("tags") }
                ContextItem("Collections…", Icons.Outlined.CollectionsBookmark, idle) {
                    run("collections")
                }
                if (one != null)
                    ContextItem("Copy full path", Icons.Outlined.Link) { run("copyPath") }
                ContextItem("Share…", Icons.Outlined.IosShare) { run("share") }
                ContextItem(
                    "Move to Trash",
                    Icons.Outlined.DeleteOutline,
                    items.all { it.canDelete } && idle,
                    "Delete",
                ) {
                    run("trash")
                }
            }
            if (model.undoLabel != null || model.redoLabel != null) {
                HorizontalDivider(color = Border)
                model.undoLabel?.let {
                    ContextItem(
                        "Undo $it",
                        Icons.AutoMirrored.Outlined.Undo,
                        enabled = idle,
                        shortcut = "Ctrl Z",
                    ) {
                        run("undo")
                    }
                }
                model.redoLabel?.let {
                    ContextItem(
                        "Redo $it",
                        Icons.AutoMirrored.Outlined.Redo,
                        enabled = idle,
                        shortcut = "Ctrl Shift Z",
                    ) {
                        run("redo")
                    }
                }
            }
        }
    }
}
