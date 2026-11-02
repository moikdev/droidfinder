@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }

internal fun Modifier.fileGestures(
    model: FinderModel,
    entry: Entry,
    open: (Entry) -> Unit,
): Modifier = composed {
    val currentOpen by rememberUpdatedState(open)
    val currentEntry by rememberUpdatedState(entry)
    fun touch(control: Boolean = false, shift: Boolean = false) {
        model.activatePane()
        if (model.multiSelecting || control || shift)
            model.select(currentEntry, multiple = !shift || control, range = shift)
        else {
            model.focusTouchEntry(currentEntry)
            currentOpen(currentEntry)
        }
    }
    fun mouse(control: Boolean, shift: Boolean) {
        model.activatePane()
        if (!control && !shift) model.endMultipleSelection()
        model.select(currentEntry, multiple = control, range = shift)
    }
    then(
            if (entry.path == model.keyboardFocusPath && entry.path !in model.selected)
                Modifier.border(1.dp, Blue, RoundedCornerShape(4.dp))
            else Modifier,
        )
        .entryGestures(
            model,
            "${model.paneId}:${entry.path}",
            touch = { control, shift -> touch(control, shift) },
            mouse = { double, control, shift ->
                model.activatePane()
                if (double) currentOpen(currentEntry) else mouse(control, shift)
            },
            hold = { isMouse ->
                model.activatePane()
                if (isMouse) mouse(false, false) else model.select(currentEntry, multiple = true)
            },
            context = { model.requestContext(currentEntry, it) },
            middle =
                if (entry.folder) ({ model.newTab(currentEntry.path, activate = false) }) else null,
        )
        .semantics(mergeDescendants = true) {
            selected = entry.path in model.selected
            onClick(if (model.multiSelecting) "Toggle selection" else "Open ${entry.name}") {
                model.windowState.fileClicks.reset()
                touch()
                true
            }
            onLongClick("Select multiple files") {
                model.windowState.fileClicks.reset()
                model.activatePane()
                model.select(currentEntry, multiple = true)
                true
            }
            customActions =
                listOf(
                    CustomAccessibilityAction("Select ${entry.name}") {
                        model.activatePane()
                        model.select(currentEntry, multiple = true)
                        true
                    },
                    CustomAccessibilityAction("Open ${entry.name}") {
                        currentOpen(currentEntry)
                        true
                    },
                    CustomAccessibilityAction("Quick Look") {
                        model.activatePane()
                        model.quickLookRequested = currentEntry
                        true
                    },
                    CustomAccessibilityAction("Show actions") {
                        model.requestContext(currentEntry)
                        true
                    },
                )
        }
}

@Composable
fun ListView(model: FinderModel, open: (Entry) -> Unit, context: (Entry) -> Unit) {
    val marquee = remember(model.location) { MarqueeState() }
    val headerHeight = if (LocalFinderDensity.current.touch) 48.dp else 32.dp
    val rows = model.listRows()
    val depths = rows.associate { it.first.path to it.second }
    val groups = if (model.canExpandList) listOf("" to rows.map { it.first }) else model.groups()
    val paths = groups.flatMap { (group, items) ->
        (if (group.isNotEmpty()) listOf<String?>(null) else emptyList()) + items.map { it.path }
    }
    val listState = rememberKeyboardListState(model, paths)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val measured =
        remember(model.fitColumns, model.entries, model.showExtensions, depths) {
            if (!model.fitColumns) Triple(0.dp, 0.dp, 0.dp)
            else {
                fun width(text: String, font: Int) =
                    with(density) {
                        measurer
                            .measure(
                                text,
                                style = androidx.compose.ui.text.TextStyle(fontSize = font.sp),
                                maxLines = 1,
                                softWrap = false,
                            )
                            .size
                            .width
                            .toDp()
                    }
                Triple(
                    rows.maxOfOrNull {
                        width(model.displayName(it.first), 12) + (it.second * 16).dp
                    } ?: 0.dp,
                    maxOf(
                        125.dp,
                        rows.maxOfOrNull { width(formatDate(it.first.modified), 10) + 16.dp }
                            ?: 0.dp,
                    ),
                    maxOf(90.dp, rows.maxOfOrNull { width(it.first.kind, 10) + 16.dp } ?: 0.dp),
                )
            }
        }
    BoxWithConstraints(Modifier.fillMaxSize().marqueeSelection(model, marquee, headerHeight)) {
        val namesOnly = model.windowsLayout == 5
        val columns = model.detailColumns
        val roomy =
            !columns.adaptive ||
                model.fitColumns ||
                maxWidth > if (LocalFinderDensity.current.touch) 720.dp else 610.dp
        val showDate =
            !namesOnly &&
                columns.date &&
                (!columns.adaptive ||
                    model.fitColumns ||
                    maxWidth > if (LocalFinderDensity.current.touch) 560.dp else 440.dp)
        val showSize = !namesOnly && columns.size
        val showKind = !namesOnly && columns.kind && roomy
        val dateWidth =
            if (model.fitColumns) measured.second
            else if (columns.adaptive && !roomy) minOf(columns.dateWidth.dp, 125.dp)
            else columns.dateWidth.dp
        val sizeWidth = columns.sizeWidth.dp
        val kindWidth = if (model.fitColumns) measured.third else columns.kindWidth.dp
        val fixed =
            (if (showDate) dateWidth else 0.dp) +
                (if (showSize) sizeWidth else 0.dp) +
                (if (showKind) kindWidth else 0.dp)
        val tableWidth =
            if (model.fitColumns || !columns.adaptive)
                maxOf(maxWidth, fixed + (if (model.fitColumns) measured.first + 185.dp else 270.dp))
            else maxWidth
        val horizontalState = rememberBrowserHorizontalState(model, "details-horizontal")
        Box(
            Modifier.dragAutoScroll(horizontalState, horizontal = true)
                .horizontalScroll(horizontalState),
        ) {
            Column(Modifier.width(tableWidth).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth()
                        .height(headerHeight)
                        .background(SubtleSurface)
                        .padding(start = 26.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SortHeader("Name", SortBy.NAME, model, Modifier.weight(1f))
                    if (showDate)
                        DetailColumnHeader("Date modified", SortBy.MODIFIED, model, dateWidth) {
                            val from =
                                if (model.fitColumns || model.detailColumns.adaptive)
                                    dateWidth.value.toInt()
                                else model.detailColumns.dateWidth
                            model.changeDetailColumns(
                                model.detailColumns.copy(
                                    dateWidth = from + it,
                                    adaptive = false,
                                ),
                            )
                        }
                    if (showSize)
                        DetailColumnHeader("Size", SortBy.SIZE, model, sizeWidth) {
                            model.changeDetailColumns(
                                model.detailColumns.copy(
                                    sizeWidth = model.detailColumns.sizeWidth + it,
                                    adaptive = false,
                                ),
                            )
                        }
                    if (showKind)
                        DetailColumnHeader("Kind", SortBy.KIND, model, kindWidth) {
                            val from =
                                if (model.fitColumns) kindWidth.value.toInt()
                                else model.detailColumns.kindWidth
                            model.changeDetailColumns(
                                model.detailColumns.copy(
                                    kindWidth = from + it,
                                    adaptive = false,
                                ),
                            )
                        }
                    Spacer(Modifier.width(LocalFinderDensity.current.targetSize))
                }
                HorizontalDivider(color = Border, thickness = .5.dp)
                LazyColumn(
                    Modifier.fillMaxSize().dragAutoScroll(listState).testTag("browser-list"),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    groups.forEach { (group, entries) ->
                        if (group.isNotEmpty())
                            item(key = "group:$group") { GroupHeading(group, entries.size) }
                        itemsIndexed(entries, key = { _, item -> item.path }) { _, item ->
                            val selected = item.path in model.selected
                            Row(
                                Modifier.fileFrame(marquee, item.path)
                                    .folderDropTarget(
                                        model,
                                        item.path.takeIf { item.folder },
                                        visible = {
                                            listState.layoutInfo.visibleItemsInfo.any {
                                                it.key == item.path
                                            }
                                        },
                                    )
                                    .fillMaxWidth()
                                    .height(LocalFinderDensity.current.rowHeight)
                                    .padding(horizontal = 8.dp)
                                    .background(
                                        if (selected) SelectionSurface else CanvasBackground,
                                        RoundedCornerShape(5.dp),
                                    )
                                    .fileGestures(model, item, open)
                                    .padding(start = 14.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spacer(Modifier.width(((depths[item.path] ?: 0) * 16).dp))
                                if (item.folder && model.canExpandList)
                                    IconButton(
                                        onClick = { model.toggleExpanded(item) },
                                        modifier =
                                            Modifier.size(
                                                if (LocalFinderDensity.current.touch) 48.dp
                                                else 28.dp,
                                            ),
                                    ) {
                                        Icon(
                                            if (item.path in model.expandedFolders)
                                                Icons.Outlined.KeyboardArrowDown
                                            else Icons.Outlined.ChevronRight,
                                            if (item.path in model.expandedFolders)
                                                "Collapse ${item.name}"
                                            else "Expand ${item.name}",
                                            Modifier.size(14.dp),
                                            tint = Muted,
                                        )
                                    }
                                else
                                    Spacer(
                                        Modifier.width(
                                            if (LocalFinderDensity.current.touch) 48.dp else 28.dp,
                                        ),
                                    )
                                DraggableFileGlyph(model, item, 27.dp)
                                Text(
                                    model.displayName(item),
                                    Modifier.weight(1f).padding(horizontal = 9.dp),
                                    fontSize = 12.sp,
                                    color = if (selected) SelectedInk else Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                model.tags[item.organizationPath]?.let {
                                    Box(
                                        Modifier.padding(end = 10.dp)
                                            .size(6.dp)
                                            .background(
                                                tagColor(model.tagColorName(it)),
                                                CircleShape,
                                            ),
                                    )
                                }
                                if (showDate)
                                    Text(
                                        formatDate(item.modified),
                                        Modifier.width(dateWidth).padding(start = 14.dp),
                                        color = Muted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                if (showSize)
                                    Text(
                                        item.displaySize,
                                        Modifier.width(sizeWidth).padding(start = 14.dp),
                                        color = Muted,
                                        fontSize = 10.sp,
                                    )
                                if (showKind)
                                    Text(
                                        item.kind,
                                        Modifier.width(kindWidth).padding(start = 14.dp),
                                        color = Muted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                FileActionsButton(model, item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContentListView(model: FinderModel, open: (Entry) -> Unit) {
    val groups = model.groups()
    val keys = groups.flatMap { (group, entries) ->
        (if (group.isNotEmpty()) listOf<String?>(null) else emptyList()) + entries.map { it.path }
    }
    val state = rememberKeyboardListState(model, keys, "content")
    LazyColumn(
        Modifier.fillMaxSize().dragAutoScroll(state).testTag("browser-content"),
        state = state,
        contentPadding = PaddingValues(8.dp),
    ) {
        groups.forEach { (group, entries) ->
            if (group.isNotEmpty()) item(key = "group:$group") { GroupHeading(group, entries.size) }
            items(entries, key = { it.path }) { item ->
                Row(
                    Modifier.fillMaxWidth()
                        .height(if (LocalFinderDensity.current.touch) 78.dp else 66.dp)
                        .folderDropTarget(
                            model,
                            item.path.takeIf { item.folder },
                            visible = {
                                state.layoutInfo.visibleItemsInfo.any { it.key == item.path }
                            },
                        )
                        .background(
                            if (item.path in model.selected) SelectionSurface else CanvasBackground,
                            RoundedCornerShape(6.dp),
                        )
                        .fileGestures(model, item, open)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DraggableFileGlyph(model, item, 42.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            model.displayName(item),
                            fontSize = 12.sp,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${item.kind} · ${item.displaySize}",
                            Modifier.padding(top = 4.dp),
                            fontSize = 10.sp,
                            color = Muted,
                        )
                    }
                    Text(
                        formatDate(item.modified),
                        Modifier.widthIn(max = 150.dp),
                        fontSize = 10.sp,
                        color = Muted,
                        maxLines = 1,
                    )
                    FileActionsButton(model, item)
                }
            }
        }
    }
}

@Composable
private fun DetailColumnHeader(
    title: String,
    sort: SortBy,
    model: FinderModel,
    width: androidx.compose.ui.unit.Dp,
    resize: (Int) -> Unit,
) {
    val scale = LocalDensity.current.density
    val precision = remember { DragPrecision() }
    val currentResize by rememberUpdatedState(resize)
    Row(Modifier.width(width).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(14.dp)
                .fillMaxHeight()
                .semanticsLabel("Resize $title column")
                .pointerHoverIcon(
                    PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW),
                )
                .draggable(
                    rememberDraggableState { delta ->
                        precision.consume(-delta / scale).takeIf { it != 0 }?.let(currentResize)
                    },
                    Orientation.Horizontal,
                    onDragStarted = { precision.reset() },
                    onDragStopped = { precision.reset() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(1.dp).height(14.dp).background(Border))
        }
        SortHeader(title, sort, model, Modifier.weight(1f))
    }
}

@Composable
private fun SortHeader(title: String, sort: SortBy, model: FinderModel, modifier: Modifier) {
    Row(
        modifier.fillMaxHeight().clickable {
            model.activatePane()
            model.changeSort(sort)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (model.sort == sort) Ink else Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (model.sort == sort)
            Icon(
                if (model.ascending) Icons.Outlined.KeyboardArrowUp
                else Icons.Outlined.KeyboardArrowDown,
                null,
                Modifier.padding(start = 3.dp).size(12.dp),
                tint = Muted,
            )
    }
}

@Composable
fun IconView(model: FinderModel, open: (Entry) -> Unit) {
    val marquee = remember(model.location) { MarqueeState() }
    val groups = model.groups()
    val state =
        rememberKeyboardGridState(
            model,
            groups.flatMap { (group, entries) ->
                (if (group.isNotEmpty()) listOf<String?>(null) else emptyList()) +
                    entries.map { it.path }
            },
        )
    BoxWithConstraints(Modifier.fillMaxSize().marqueeSelection(model, marquee)) {
        LaunchedEffect(maxWidth, model.iconSize) {
            model.gridColumns = (maxWidth.value / (model.iconSize + 8)).toInt().coerceAtLeast(1)
        }
        LazyVerticalGrid(
            GridCells.Adaptive(model.iconSize.dp),
            state = state,
            modifier = Modifier.fillMaxSize().dragAutoScroll(state).testTag("browser-grid"),
            contentPadding = PaddingValues(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { (group, entries) ->
                if (group.isNotEmpty())
                    item(key = "group:$group", span = { GridItemSpan(maxLineSpan) }) {
                        GroupHeading(group, entries.size)
                    }
                items(entries, key = { it.path }) { item ->
                    val selected = item.path in model.selected
                    Column(
                        Modifier.fileFrame(marquee, item.path)
                            .folderDropTarget(
                                model,
                                item.path.takeIf { item.folder },
                                visible = {
                                    state.layoutInfo.visibleItemsInfo.any { it.key == item.path }
                                },
                            )
                            .heightIn(
                                min =
                                    (model.iconSize +
                                            if (LocalPhoneLayout.current) 35
                                            else if (model.windowsLayout == 7) 37 else 19)
                                        .dp,
                            )
                            .background(
                                if (selected) SelectionSurface else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .fileGestures(model, item, open)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.height((model.iconSize - 42).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            DraggableFileGlyph(model, item, (model.iconSize - 42).dp)
                            if (selected)
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    "Selected",
                                    Modifier.align(Alignment.TopEnd).size(16.dp),
                                    tint = Blue,
                                )
                        }
                        Text(
                            model.displayName(item),
                            Modifier.fillMaxWidth().padding(top = 3.dp),
                            fontSize = if (LocalPhoneLayout.current) 13.sp else 11.sp,
                            color = Ink,
                            maxLines = 2,
                            lineHeight = if (LocalPhoneLayout.current) 18.sp else 14.sp,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (model.windowsLayout == 7)
                            Text(
                                item.kind,
                                fontSize = 10.sp,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        model.tags[item.organizationPath]?.let {
                            Box(
                                Modifier.padding(top = 4.dp)
                                    .size(5.dp)
                                    .background(tagColor(model.tagColorName(it)), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColumnsView(model: FinderModel, open: (Entry) -> Unit) {
    val phone = LocalPhoneLayout.current
    val parent = if (phone) null else model.parentPath()
    val previous by
        produceState<List<Entry>>(emptyList(), parent, model.showHidden) {
            value =
                withContext(Dispatchers.IO) {
                    parent
                        ?.let { runCatching { model.listPath(it) }.getOrDefault(emptyList()) }
                        .orEmpty()
                }
        }
    var retainedFocus by remember(model.location) { mutableStateOf<Entry?>(null) }
    val mainSelection =
        model.focused?.takeIf { focused -> model.entries.any { it.path == focused.path } }
            ?: model.entries.firstOrNull { it.path in model.selected }
    val focus = mainSelection ?: retainedFocus.takeIf { model.selected.isNotEmpty() }
    val children by
        produceState<List<Entry>>(emptyList(), focus?.path, model.showHidden) {
            value =
                withContext(Dispatchers.IO) {
                    focus
                        ?.takeIf { it.folder }
                        ?.let { runCatching { model.listPath(it.path) }.getOrDefault(emptyList()) }
                        .orEmpty()
                }
        }
    SideEffect {
        retainedFocus = focus
        model.browsingColumnEntries = previous + children
    }
    DisposableEffect(model) {
        onDispose {
            model.browsingColumnEntries = emptyList()
            if (model.selected.any { path -> model.entries.none { it.path == path } })
                model.clearSelection()
        }
    }
    val horizontalState = rememberBrowserHorizontalState(model, "columns-horizontal")
    val width = if (phone) model.browserWidthDp.coerceAtLeast(240f).dp else model.columnWidth.dp
    Row(
        Modifier.fillMaxSize()
            .dragAutoScroll(horizontalState, horizontal = true)
            .horizontalScroll(horizontalState),
    ) {
        if (parent != null && model.query.isEmpty()) {
            FileColumn(
                model,
                model.title(parent),
                previous,
                model.selected.firstOrNull { path -> previous.any { it.path == path } }
                    ?: model.location,
                Modifier.width(180.dp),
                open,
                viewport = "column:$parent",
            )
            VerticalDivider(color = Border)
        }
        FileColumn(
            model,
            model.title(),
            model.entries,
            mainSelection?.path,
            Modifier.width(width),
            open,
            viewport = "column:${model.location}",
        )
        if (!phone)
            ResizeHandle(
                "Resize browsing columns",
                { delta -> model.changeColumnWidth(model.columnWidth + delta) },
            )
        else VerticalDivider(color = Border)
        if (focus?.folder == true)
            FileColumn(
                model,
                focus.name,
                children,
                model.selected.firstOrNull { path -> children.any { it.path == path } },
                Modifier.width(width),
                open,
                viewport = "column:${focus.path}",
            )
        else if (focus != null)
            Column(Modifier.width(width).fillMaxHeight().background(SubtleSurface).padding(16.dp)) {
                PreviewContent(focus, Modifier.fillMaxWidth().height(190.dp))
                Metadata(focus, model, Modifier.padding(top = 20.dp))
            }
        else if (!phone)
            Box(Modifier.width(220.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Select a folder to explore", fontSize = 11.sp, color = Muted)
            }
    }
}

@Composable
private fun FileColumn(
    model: FinderModel,
    title: String,
    entries: List<Entry>,
    selected: String?,
    modifier: Modifier,
    open: (Entry) -> Unit,
    viewport: String = "column:$title",
) {
    Column(modifier.fillMaxHeight()) {
        Text(
            title,
            Modifier.fillMaxWidth()
                .background(SubtleSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val columnState = rememberKeyboardListState(model, entries.map { it.path }, viewport)
        LazyColumn(
            Modifier.fillMaxSize()
                .padding(5.dp)
                .dragAutoScroll(columnState)
                .testTag("browser-column-$title"),
            state = columnState,
        ) {
            items(entries, key = { it.path }) { item ->
                Row(
                    Modifier.folderDropTarget(
                            model,
                            item.path.takeIf { item.folder },
                            visible = {
                                columnState.layoutInfo.visibleItemsInfo.any { it.key == item.path }
                            },
                        )
                        .fillMaxWidth()
                        .heightIn(
                            min =
                                if (LocalPhoneLayout.current) 56.dp
                                else LocalFinderDensity.current.rowHeight,
                        )
                        .background(
                            if (selected == item.path) SelectionSurface else Color.Transparent,
                            RoundedCornerShape(4.dp),
                        )
                        .fileGestures(model, item, open)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DraggableFileGlyph(model, item, 24.dp)
                    Text(
                        model.displayName(item),
                        Modifier.weight(1f).padding(horizontal = 6.dp),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.folder)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(12.dp), tint = Muted)
                }
            }
        }
    }
}

@Composable
fun GalleryView(model: FinderModel, open: (Entry) -> Unit) {
    val stripState = rememberKeyboardListState(model, model.entries.map { it.path }, "gallery")
    val item = model.focused ?: model.entries.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        if (item != null) {
            Row(Modifier.weight(1f).fillMaxWidth().background(AppBackground)) {
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .padding(18.dp)
                        .fileGestures(model, item, open),
                ) {
                    PreviewContent(item, Modifier.fillMaxSize(), large = true)
                }
                if (!LocalPhoneLayout.current)
                    Metadata(
                        item,
                        model,
                        Modifier.width(200.dp)
                            .padding(top = 28.dp, end = 20.dp)
                            .verticalScroll(rememberScrollState()),
                    )
            }
            HorizontalDivider(color = Border)
            LazyRow(
                Modifier.fillMaxWidth()
                    .height(128.dp)
                    .dragAutoScroll(stripState, horizontal = true)
                    .testTag("gallery-strip"),
                state = stripState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(model.entries, key = { it.path }) { entry ->
                    Column(
                        Modifier.width(95.dp)
                            .folderDropTarget(
                                model,
                                entry.path.takeIf { entry.folder },
                                visible = {
                                    stripState.layoutInfo.visibleItemsInfo.any {
                                        it.key == entry.path
                                    }
                                },
                            )
                            .fillMaxHeight()
                            .background(
                                if (entry.path == item.path) SelectionSurface
                                else Color.Transparent,
                                RoundedCornerShape(7.dp),
                            )
                            .fileGestures(model, entry, open)
                            .padding(7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DraggableFileGlyph(model, entry, 62.dp)
                        Text(
                            entry.name,
                            Modifier.padding(top = 4.dp),
                            fontSize = 9.sp,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PreviewPane(
    model: FinderModel,
    quicklook: (Entry) -> Unit,
    open: (Entry) -> Unit,
    modifier: Modifier,
) {
    val entry = model.focused
    Column(modifier.fillMaxHeight().background(SubtleSurface)) {
        Row(
            Modifier.fillMaxWidth()
                .height(LocalFinderDensity.current.rowHeight)
                .padding(start = 18.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (model.previewDetailsOnly) "Details" else "Preview",
                Modifier.weight(1f),
                fontSize = 9.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.SemiBold,
                color = Muted,
            )
            ToolButton(Icons.Outlined.Close, "Hide preview", model::togglePreview, compact = true)
        }
        if (entry == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Visibility,
                    null,
                    Modifier.size(29.dp),
                    tint = Color(0xFFC7CED8),
                )
                Text(
                    "A closer look",
                    Modifier.padding(top = 12.dp),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Muted,
                )
                Text(
                    "Select a file to see its preview\nand details here.",
                    Modifier.padding(top = 7.dp, bottom = 60.dp),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                    color = Muted,
                )
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (!model.previewDetailsOnly)
                    PreviewContent(
                        entry,
                        Modifier.fillMaxWidth()
                            .height(205.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                Metadata(entry, model, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                if (model.selected.size > 1)
                    Text(
                        "${model.selected.size} items selected",
                        Modifier.padding(horizontal = 20.dp),
                        color = Blue,
                        fontSize = 11.sp,
                    )
            }
            if (!model.isTrash)
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedButton(
                        onClick = { quicklook(entry) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        Icon(Icons.Outlined.Visibility, null, Modifier.size(15.dp))
                        Text(
                            "Quick Look",
                            Modifier.weight(1f).padding(start = 8.dp),
                            fontSize = 11.sp,
                        )
                        Text(
                            "Space",
                            Modifier.background(ToolbarSurface, RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                            color = Muted,
                            fontSize = 9.sp,
                        )
                    }
                    if (!entry.folder)
                        TextButton(onClick = { open(entry) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Open with app", fontSize = 11.sp)
                        }
                }
        }
    }
}

@Composable
fun PathBar(model: FinderModel, goTo: () -> Unit) {
    HorizontalDivider(color = Border)
    Row(
        Modifier.fillMaxWidth()
            .height(if (LocalFinderDensity.current.touch) 48.dp else 34.dp)
            .background(SubtleSurface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val directory = model.folderPath
            if (directory == null) {
                Icon(
                    if (model.isTrash) Icons.Outlined.DeleteOutline
                    else Icons.Outlined.FolderSpecial,
                    null,
                    Modifier.size(13.dp),
                    tint = Muted,
                )
                Text(model.title(), Modifier.padding(start = 7.dp), fontSize = 10.sp, color = Ink)
            } else {
                val ancestors =
                    generateSequence(directory) { model.parentPath(it) }
                        .take(24)
                        .toList()
                        .reversed()
                ancestors.forEachIndexed { index, path ->
                    if (index > 0)
                        Icon(
                            Icons.Outlined.ChevronRight,
                            null,
                            Modifier.padding(horizontal = 4.dp).size(11.dp),
                            tint = Muted,
                        )
                    Row(
                        Modifier.folderDropTarget(model, path)
                            .height(if (LocalFinderDensity.current.touch) 48.dp else 34.dp)
                            .widthIn(min = if (LocalFinderDensity.current.touch) 48.dp else 0.dp)
                            .clickable {
                                model.activatePane()
                                model.navigate(path)
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (index == 0)
                            Icon(
                                if (isDocumentPath(path)) Icons.Outlined.CloudQueue
                                else Icons.Outlined.TabletMac,
                                null,
                                Modifier.padding(end = 5.dp).size(13.dp),
                                tint = Muted,
                            )
                        Text(
                            model.title(path),
                            color = if (index == ancestors.lastIndex) Ink else Muted,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
        if (!LocalPhoneLayout.current)
            Text(
                model.listingCountText,
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        IconButton(
            onClick = goTo,
            modifier = Modifier.size(if (LocalFinderDensity.current.touch) 48.dp else 28.dp),
        ) {
            Icon(Icons.Outlined.EditLocationAlt, "Go to folder", Modifier.size(14.dp), tint = Muted)
        }
    }
}

@Composable
fun StatusBar(model: FinderModel) {
    HorizontalDivider(color = Border, thickness = .5.dp)
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(SubtleSurface).padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = model.entries.size
        val text = model.listingCountText
        Text(
            text,
            fontSize = 9.sp,
            color = Muted,
        )
        if (model.selectedEntries.any { !it.folder })
            Text(
                " · ${formatSize(model.selectedEntries.filter { !it.folder }.sumOf { it.size })}",
                color = Muted,
                fontSize = 9.sp,
            )
        Spacer(Modifier.weight(1f))
        Text(
            if (model.selected.isEmpty()) "Tap to open · Mouse: double-click · Hold to select"
            else "Space to peek",
            fontSize = 9.sp,
            color = Muted,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    button: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(35.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = Color(0xFFB0C0D4))
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
            modifier = Modifier.padding(top = 19.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            description,
            Modifier.widthIn(max = 350.dp).padding(top = 9.dp),
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        if (button != null)
            OutlinedButton(
                onClick = onAction,
                Modifier.padding(top = 23.dp),
                shape = RoundedCornerShape(7.dp),
            ) {
                Text(button, fontSize = 12.sp)
            }
    }
}

@Composable
fun WelcomeState(grant: () -> Unit, create: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FolderGlyph(100.dp)
        Text(
            "Hello, everything.",
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-.8).sp,
            color = Ink,
            modifier = Modifier.padding(top = 15.dp),
        )
        Text(
            "A familiar home for the files on your device.",
            fontSize = 13.sp,
            color = Muted,
            modifier = Modifier.padding(top = 9.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            "Connect your storage to browse, organize, and find\nyour files. You're always in control of access.",
            Modifier.padding(top = 18.dp),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = grant,
            modifier = Modifier.padding(top = 24.dp).height(48.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            Icon(Icons.Outlined.TabletMac, null, Modifier.size(17.dp))
            Text("Connect device storage", Modifier.padding(start = 9.dp), fontSize = 12.sp)
        }
        TextButton(onClick = create, modifier = Modifier.padding(top = 5.dp)) {
            Text("Or create a folder in Workspace", fontSize = 11.sp)
        }
        Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = Muted)
            Text(
                "On your device. In your hands.",
                Modifier.padding(start = 6.dp),
                fontSize = 10.sp,
                color = Muted,
            )
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    button: String,
    onDismiss: () -> Unit,
    validation: ((String) -> String?)? = null,
    onConfirm: (String) -> Unit,
) {
    var value by remember {
        mutableStateOf(
            TextFieldValue(
                initial,
                TextRange(
                    0,
                    if (title == "Rename item" && initial.lastIndexOf('.') > 0)
                        initial.lastIndexOf('.')
                    else initial.length,
                ),
            ),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    fun confirm() {
        val problem =
            if (validation != null) validation(value.text)
            else if (title == "Go to folder") null
            else runCatching { validateFilename(value.text) }.exceptionOrNull()?.message
        if (problem != null) error = problem else onConfirm(value.text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 21.sp) },
        text = {
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value,
                {
                    value = it
                    error = null
                },
                Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if (it.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                    else
                        when (it.key) {
                            androidx.compose.ui.input.key.Key.Escape -> {
                                onDismiss()
                                true
                            }
                            androidx.compose.ui.input.key.Key.Enter -> {
                                confirm()
                                true
                            }
                            else -> false
                        }
                },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(onDone = { confirm() }),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = value.text.isNotBlank()) { Text(button) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MetaInfoText(label: String, value: String) {
    Text(label, fontWeight = FontWeight.Medium, color = Muted, fontSize = 11.sp)
    Text(value, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Ink)
}

@Composable
fun GroupHeading(name: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().background(Sidebar).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            Modifier.weight(1f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
        )
        Text("$count", fontSize = 10.sp, color = Muted)
    }
}

@Composable
fun ResizeHandle(description: String, resize: (Int) -> Unit) {
    val density = LocalDensity.current.density
    val precision = remember { DragPrecision() }
    val currentResize by rememberUpdatedState(resize)
    Box(
        Modifier.width(6.dp)
            .fillMaxHeight()
            .background(Border.copy(alpha = .6f))
            .semanticsLabel(description)
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .draggable(
                rememberDraggableState { delta ->
                    precision.consume(delta / density).takeIf { it != 0 }?.let(currentResize)
                },
                Orientation.Horizontal,
                onDragStarted = { precision.reset() },
                onDragStopped = { precision.reset() },
            ),
    )
}
