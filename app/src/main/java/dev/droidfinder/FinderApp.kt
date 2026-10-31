@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.droidfinder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun finderColors(dark: Boolean) =
    if (dark)
        darkColorScheme(
            primary = Color(0xFF8DBBFF),
            primaryContainer = Color(0xFF304365),
            onPrimaryContainer = Color(0xFFB7CEFC),
            secondary = Color(0xFFB8BBC3),
            secondaryContainer = Color(0xFF3A3C42),
            onSecondaryContainer = Color(0xFFEDEDEF),
            background = Color(0xFF202123),
            surface = Color(0xFF26272A),
            onSurface = Color(0xFFEDEDEF),
            onSurfaceVariant = Color(0xFFA6A8AF),
            outline = Color(0xFF3A3B3F),
            surfaceContainer = Color(0xFF292A2D),
            surfaceContainerLow = Color(0xFF242528),
            surfaceContainerHigh = Color(0xFF2E2F32),
            surfaceContainerHighest = Color(0xFF343539),
            surfaceTint = Color.Transparent,
        )
    else
        lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE8F0FE),
            onPrimaryContainer = Color(0xFF2465D4),
            secondary = Color(0xFF6E727A),
            secondaryContainer = Color(0xFFE7E9EE),
            onSecondaryContainer = Color(0xFF4C5058),
            background = Color.White,
            surface = Color.White,
            onSurface = Color(0xFF282A2F),
            onSurfaceVariant = Color(0xFF747880),
            outline = Color(0xFFE5E6E9),
            surfaceContainer = Color(0xFFF1F2F4),
            surfaceContainerLow = Color(0xFFFAFAFB),
            surfaceContainerHigh = Color(0xFFF3F4F6),
            surfaceContainerHighest = Color(0xFFECEDEF),
            surfaceTint = Color.Transparent,
        )

@Composable
fun FinderApp(
    model: FinderModel,
    onGrantAccess: () -> Unit,
    onOpen: (Entry) -> Unit,
    onShare: (List<Entry>) -> Unit,
    onConnectFolder: () -> Unit,
    onOpenWith: (Entry) -> Unit,
) {
    CompositionLocalProvider(LocalFinderDark provides model.darkTheme) {
        MaterialTheme(
            colorScheme = finderColors(model.darkTheme),
            typography =
                Typography(
                    bodyLarge = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp),
                    bodyMedium =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            letterSpacing = 0.sp,
                        ),
                    bodySmall = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp),
                    labelLarge = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
                    labelMedium = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                    labelSmall = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                ),
        ) {
            var dialog by remember { mutableStateOf<String?>(null) }
            var quickLook by remember { mutableStateOf<Entry?>(null) }
            var quickLookCandidates by remember { mutableStateOf<List<Entry>>(emptyList()) }
            var peekMultipleSelection by remember { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            var searchActive by remember { mutableStateOf(false) }
            var searchFocused by remember { mutableStateOf(false) }
            val searchFocus = remember { FocusRequester() }
            val rootFocus = remember { FocusRequester() }
            val snackbar = remember { SnackbarHostState() }
            val clipboard = LocalClipboardManager.current
            val screenWidth = LocalConfiguration.current.screenWidthDp
            val selected = model.selectedEntries
            val open: (Entry) -> Unit = { entry ->
                if (entry.extension == "finderlink")
                    model.followShortcut(entry) { target ->
                        if (target.folder) model.navigate(target.path) else onOpen(target)
                    }
                else if (entry.folder) model.navigate(entry.path) else onOpen(entry)
            }
            val showQuickLook: (Entry) -> Unit = { entry ->
                val show: (Entry) -> Unit = { resolved ->
                    peekMultipleSelection = model.selectedEntries.size > 1
                    val candidates =
                        if (peekMultipleSelection) model.selectedEntries else model.visibleEntries
                    quickLookCandidates =
                        if (candidates.any { it.path == resolved.path }) candidates
                        else listOf(resolved)
                    quickLook = resolved
                }
                if (entry.extension == "finderlink") model.followShortcut(entry, show)
                else show(entry)
            }
            LaunchedEffect(quickLook) {
                if (quickLook == null && !searchActive) rootFocus.requestFocus()
            }
            val action: (String) -> Unit = { command ->
                menu = false
                when (command) {
                    "open" -> model.focused?.let(open)
                    "external" -> model.focused?.let(onOpenWith)
                    "quicklook" -> model.focused?.let(showQuickLook)
                    "new" -> dialog = "new"
                    "rename" -> dialog = if (selected.size > 1) "batchRename" else "rename"
                    "groupSelection" -> dialog = "groupSelection"
                    "tags" -> dialog = "tags"
                    "copyTo" -> dialog = "copyTo"
                    "moveTo" -> dialog = "moveTo"
                    "reveal" -> model.revealSelection()
                    "copy" -> model.copySelection(false)
                    "cut" -> model.copySelection(true)
                    "paste" -> model.paste()
                    "duplicate" -> model.duplicate()
                    "trash" -> model.trash()
                    "restore" -> model.restore()
                    "undo" -> model.undo()
                    "redo" -> model.redo()
                    "delete" -> dialog = "delete"
                    "empty" -> dialog = "empty"
                    "compress" ->
                        if (model.folderPath == null) dialog = "compressTo" else model.compress()
                    "extract" -> if (!model.canCreate) dialog = "extractTo" else model.extract()
                    "share" -> onShare(selected)
                    "info" -> if (model.focused != null) dialog = "info"
                    "path" -> dialog = "path"
                    "copyPath" -> {
                        model.focused?.let { clipboard.setText(AnnotatedString(it.path)) }
                        model.message = "Path copied"
                    }
                    "favorite" ->
                        model.focused?.takeIf { it.folder }?.let { model.favoritePath(it.path) }
                    "tab" -> model.focused?.takeIf { it.folder }?.let { model.newTab(it.path) }
                    "about" -> dialog = "about"
                    "filters" -> dialog = "filters"
                    "saveSearch" -> dialog = "saveSearch"
                    "renameSmart" -> dialog = "renameSmart"
                    "settings" -> dialog = "settings"
                    "connectFolder" -> onConnectFolder()
                    "appearance" -> dialog = "appearance"
                    "shortcut" -> model.makeShortcut()
                    "sidebar" -> model.toggleSidebar()
                    "newTab" -> model.newTab()
                }
            }
            LaunchedEffect(model.message) {
                model.message?.let { text ->
                    val undo = model.canUndoTrash && text.endsWith("moved to Trash")
                    val result =
                        snackbar.showSnackbar(
                            text,
                            actionLabel = if (undo) "Undo" else null,
                            duration = SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) model.undoTrash()
                    if (model.message == text) model.message = null
                }
            }
            LaunchedEffect(searchActive) { if (searchActive) searchFocus.requestFocus() }
            LaunchedEffect(Unit) { rootFocus.requestFocus() }
            LaunchedEffect(model.interruptedTransfer) {
                if (model.interruptedTransfer != null) dialog = "recovery"
            }
            LaunchedEffect(model.quickLookRequested) {
                model.quickLookRequested?.let {
                    showQuickLook(it)
                    model.quickLookRequested = null
                }
            }
            LaunchedEffect(model.contextRequested) {
                if (model.contextRequested != null) {
                    menu = true
                    model.contextRequested = null
                }
            }
            BackHandler(
                enabled =
                    quickLook != null ||
                        model.selected.isNotEmpty() ||
                        model.tabs[model.activeTab].index > 0,
            ) {
                when {
                    quickLook != null -> quickLook = null
                    model.selected.isNotEmpty() -> model.clearSelection()
                    else -> model.back()
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .background(AppBackground)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .focusRequester(rootFocus)
                    .onPreviewKeyEvent { event ->
                        if (
                            event.type != KeyEventType.KeyDown ||
                                dialog != null ||
                                model.busy != null
                        )
                            false
                        else if (event.key == Key.Spacebar && event.nativeKeyEvent.repeatCount > 0)
                            true
                        else if (event.isCtrlPressed || event.isMetaPressed) {
                            when (event.key) {
                                Key.F -> {
                                    searchActive = true
                                    true
                                }
                                Key.L -> {
                                    dialog = "path"
                                    true
                                }
                                Key.T -> {
                                    model.newTab()
                                    true
                                }
                                Key.W -> {
                                    model.closeTab(model.activeTab)
                                    true
                                }
                                Key.N -> {
                                    if (model.canCreate) dialog = "new"
                                    true
                                }
                                Key.A ->
                                    if (!searchFocused) {
                                        model.selectAll()
                                        true
                                    } else false
                                Key.C ->
                                    if (!searchFocused) {
                                        model.copySelection(false)
                                        true
                                    } else false
                                Key.X ->
                                    if (!searchFocused && !model.isTrash) {
                                        model.copySelection(true)
                                        true
                                    } else false
                                Key.V ->
                                    if (!searchFocused && !model.isTrash) {
                                        model.paste()
                                        true
                                    } else false
                                Key.D ->
                                    if (!searchFocused && !model.isTrash) {
                                        model.duplicate()
                                        true
                                    } else false
                                Key.Z ->
                                    if (!searchFocused) {
                                        if (event.isShiftPressed) model.redo() else model.undo()
                                        true
                                    } else false
                                Key.Y ->
                                    if (!searchFocused) {
                                        model.redo()
                                        true
                                    } else false
                                Key.O -> {
                                    model.focused?.let(open)
                                    true
                                }
                                Key.S ->
                                    if (event.isAltPressed) {
                                        model.toggleSidebar()
                                        true
                                    } else false
                                Key.Delete,
                                Key.Backspace ->
                                    if (!searchFocused) {
                                        if (event.isShiftPressed) dialog = "empty"
                                        else if (model.isTrash) dialog = "delete" else model.trash()
                                        true
                                    } else false
                                Key.I -> {
                                    if (model.focused != null) dialog = "info"
                                    true
                                }
                                Key.Period -> {
                                    model.toggleHidden()
                                    true
                                }
                                else -> false
                            }
                        } else if (event.isAltPressed) {
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    model.back()
                                    true
                                }
                                Key.DirectionRight -> {
                                    model.forward()
                                    true
                                }
                                Key.DirectionUp -> {
                                    model.up()
                                    true
                                }
                                else -> false
                            }
                        } else if (!searchFocused)
                            when (event.key) {
                                Key.Spacebar -> {
                                    if (quickLook == null) model.focused?.let(showQuickLook)
                                    else quickLook = null
                                    true
                                }
                                Key.Enter -> {
                                    model.focused?.let(open)
                                    true
                                }
                                Key.F2 -> {
                                    if (selected.isNotEmpty() && !model.isTrash)
                                        dialog = if (selected.size > 1) "batchRename" else "rename"
                                    true
                                }
                                Key.DirectionDown -> {
                                    model.stepSelection(
                                        if (model.mode == ViewMode.ICONS) model.gridColumns else 1,
                                        event.isShiftPressed,
                                    )
                                    true
                                }
                                Key.DirectionUp -> {
                                    model.stepSelection(
                                        if (model.mode == ViewMode.ICONS) -model.gridColumns
                                        else -1,
                                        event.isShiftPressed,
                                    )
                                    true
                                }
                                Key.DirectionLeft -> {
                                    if (
                                        model.mode == ViewMode.ICONS ||
                                            model.mode == ViewMode.GALLERY
                                    )
                                        model.stepSelection(-1, event.isShiftPressed)
                                    else model.up()
                                    true
                                }
                                Key.DirectionRight -> {
                                    if (
                                        model.mode == ViewMode.ICONS ||
                                            model.mode == ViewMode.GALLERY
                                    )
                                        model.stepSelection(1, event.isShiftPressed)
                                    else model.focused?.takeIf { it.folder }?.let(open)
                                    true
                                }
                                Key.MoveHome -> {
                                    model.stepSelection(-model.entries.size, event.isShiftPressed)
                                    true
                                }
                                Key.MoveEnd -> {
                                    model.stepSelection(model.entries.size, event.isShiftPressed)
                                    true
                                }
                                Key.Delete,
                                Key.Backspace -> {
                                    if (model.isTrash) {
                                        if (selected.isNotEmpty()) dialog = "delete"
                                    } else model.trash()
                                    true
                                }
                                Key.Escape -> {
                                    model.clearSelection()
                                    quickLook = null
                                    true
                                }
                                else ->
                                    if (event.utf16CodePoint > 31 && event.utf16CodePoint < 65536) {
                                        model.typeSelect(event.utf16CodePoint.toChar())
                                        true
                                    } else false
                            }
                        else false
                    }
                    .focusable(),
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (model.sidebarVisible && screenWidth >= 600) {
                        FinderSidebar(
                            model,
                            onGrantAccess,
                            onConnectFolder,
                            Modifier.width(
                                minOf(model.sidebarWidth, (screenWidth * .3f).toInt()).dp,
                            ),
                        )
                        ResizeHandle(
                            "Resize sidebar",
                            { model.changeSidebarWidth(model.sidebarWidth + it) },
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        RefinedToolbar(
                            model = model,
                            action = action,
                            searchActive = searchActive || model.query.isNotEmpty(),
                            onSearch = { searchActive = true },
                            searchFocus = searchFocus,
                            onSearchFocused = { searchFocused = it },
                            onCloseSearch = {
                                model.updateQuery("")
                                searchActive = false
                                rootFocus.requestFocus()
                            },
                            menuExpanded = menu,
                            onMenuChange = { menu = it },
                        )
                        if (model.tabs.size > 1) TabStrip(model)
                        Box(
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .background(CanvasBackground)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        // Selecting a search result returns keyboard control to
                                        // files.
                                        rootFocus.requestFocus()
                                    }
                                },
                        ) {
                            Row(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier.weight(1f)
                                        .fillMaxHeight()
                                        .folderDropTarget(model, model.folderPath),
                                ) {
                                    when {
                                        model.listingError != null ->
                                            EmptyState(
                                                Icons.Outlined.FolderOff,
                                                "Folder unavailable",
                                                model.listingError!!,
                                                "Try again",
                                                model::refresh,
                                            )
                                        model.entries.isEmpty() && !model.loading -> {
                                            if (
                                                model.directory == model.workspace &&
                                                    !model.effectiveSearchRules.active &&
                                                    !model.hasAccess
                                            )
                                                WelcomeState(onGrantAccess, { action("new") })
                                            else
                                                EmptyState(
                                                    if (
                                                        (model.query.isNotEmpty() ||
                                                            model.effectiveSearchRules.active ||
                                                            model.currentSmartFolder != null)
                                                    )
                                                        Icons.Outlined.SearchOff
                                                    else if (model.isTrash)
                                                        Icons.Outlined.DeleteOutline
                                                    else Icons.Outlined.FolderOpen,
                                                    if (
                                                        (model.query.isNotEmpty() ||
                                                            model.effectiveSearchRules.active ||
                                                            model.currentSmartFolder != null)
                                                    )
                                                        "No matching files"
                                                    else if (model.isTrash) "Trash is empty"
                                                    else "This folder is empty",
                                                    if (
                                                        (model.query.isNotEmpty() ||
                                                            model.effectiveSearchRules.active ||
                                                            model.currentSmartFolder != null)
                                                    )
                                                        "Try another name. Search includes subfolders."
                                                    else if (model.isTrash)
                                                        "Items you move to Trash will appear here."
                                                    else
                                                        "Create a folder or paste files to get started.",
                                                    if (model.canCreate && model.query.isEmpty())
                                                        "New folder"
                                                    else null,
                                                    { action("new") },
                                                )
                                        }
                                        model.mode == ViewMode.ICONS -> IconView(model, open)
                                        model.mode == ViewMode.LIST ->
                                            ListView(model, open) { entry ->
                                                if (entry.path !in model.selected) {
                                                    model.clearSelection()
                                                    model.select(entry)
                                                }
                                                menu = true
                                            }
                                        model.mode == ViewMode.COLUMNS -> ColumnsView(model, open)
                                        model.mode == ViewMode.GALLERY -> GalleryView(model, open)
                                    }
                                    if (model.loading)
                                        LinearProgressIndicator(
                                            Modifier.fillMaxWidth().height(2.dp),
                                            color = Blue,
                                        )
                                }
                                if (
                                    model.previewVisible &&
                                        model.mode != ViewMode.GALLERY &&
                                        screenWidth >= 900
                                ) {
                                    ResizeHandle(
                                        "Resize preview pane",
                                        { delta ->
                                            model.changePreviewWidth(model.previewWidth - delta)
                                        },
                                    )
                                    PreviewPane(
                                        model,
                                        showQuickLook,
                                        onOpenWith,
                                        Modifier.width(
                                            minOf(model.previewWidth, (screenWidth * .3f).toInt())
                                                .dp,
                                        ),
                                    )
                                }
                            }
                            if (model.busy != null)
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(CanvasBackground.copy(alpha = .9f))
                                        .clickable(
                                            interactionSource =
                                                remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {},
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(
                                            Modifier.size(30.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            model.busy!!,
                                            Modifier.padding(top = 18.dp),
                                            color = Ink,
                                            fontSize = 14.sp,
                                        )
                                        Text(
                                            "${model.transferProgress.items} items · ${formatSize(model.transferProgress.bytes)}",
                                            Modifier.padding(top = 7.dp),
                                            color = Muted,
                                            fontSize = 11.sp,
                                        )
                                        Text(
                                            model.transferProgress.current,
                                            Modifier.padding(top = 6.dp).widthIn(max = 320.dp),
                                            color = Muted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        TextButton(onClick = model::cancelTransfer) {
                                            Text("Cancel transfer")
                                        }
                                    }
                                }
                        }
                        PathBar(model) { action("path") }
                    }
                }
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp),
                )
            }

            when (dialog) {
                "appearance" -> FolderAppearanceDialog(model) { dialog = null }
                "recovery" ->
                    AlertDialog(
                        onDismissRequest = {
                            model.dismissRecovery()
                            dialog = null
                        },
                        title = { Text("A transfer was interrupted") },
                        text = {
                            Text(
                                "Android stopped the previous session before this operation finished:\n\n${model.interruptedTransfer?.lineSequence()?.firstOrNull().orEmpty()}\n\nCompleted items were kept. Hidden .droidfinder-* staging files may remain in the destination. Review the destination before retrying; existing files will not be overwritten.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val path = model.interruptedTransfer?.lines()?.getOrNull(1)
                                    model.dismissRecovery()
                                    dialog = null
                                    if (path != null && model.allowedPath(path))
                                        model.navigate(path)
                                },
                            ) {
                                Text("Review files")
                            }
                        },
                    )
                "batchRename" -> BatchRenameDialog(model) { dialog = null }
                "filters" -> SearchFiltersDialog(model) { dialog = null }
                "settings" -> PreferencesDialog(model) { dialog = null }
                "saveSearch" ->
                    NameDialog(
                        "Save Smart Folder",
                        model.query.ifBlank { "Smart Folder" },
                        "Save",
                        { dialog = null },
                        {
                            model.saveSmartFolder(it)
                            dialog = null
                        },
                    )
                "renameSmart" ->
                    NameDialog(
                        "Rename Smart Folder",
                        model.title(),
                        "Rename",
                        { dialog = null },
                        {
                            model.renameSmartFolder(it)
                            dialog = null
                        },
                    )
                "tags" -> TagManagerDialog(model) { dialog = null }
                "copyTo",
                "moveTo" -> DestinationDialog(model, dialog == "moveTo") { dialog = null }
                "compressTo" -> DestinationDialog(model, false, archive = true) { dialog = null }
                "extractTo" -> DestinationDialog(model, false, extract = true) { dialog = null }
                "groupSelection" ->
                    NameDialog(
                        "New folder with selection",
                        "New folder",
                        "Create",
                        { dialog = null },
                        {
                            model.newFolderWithSelection(it)
                            dialog = null
                        },
                    )
                "new",
                "rename",
                "path" -> {
                    val kind = dialog!!
                    NameDialog(
                        title =
                            when (kind) {
                                "new" -> "New folder"
                                "rename" -> "Rename item"
                                else -> "Go to folder"
                            },
                        initial =
                            when (kind) {
                                "rename" -> model.focused?.name.orEmpty()
                                "path" -> model.folderPath ?: model.deviceRoot.absolutePath
                                else -> "Untitled folder"
                            },
                        button =
                            if (kind == "path") "Go"
                            else if (kind == "rename") "Rename" else "Create",
                        onDismiss = { dialog = null },
                        onConfirm = { value ->
                            if (kind == "path") model.goTo(value)
                            else if (kind == "new") model.createFolder(value)
                            else model.rename(value)
                            dialog = null
                        },
                    )
                }
                "delete",
                "empty" ->
                    AlertDialog(
                        onDismissRequest = { dialog = null },
                        title = { Text("Delete permanently?") },
                        text = {
                            Text(
                                if (dialog == "empty")
                                    "All items in Trash will be permanently deleted. This cannot be undone."
                                else
                                    "${selected.size} selected item(s) will be permanently deleted. This cannot be undone.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (dialog == "empty") model.emptyTrash()
                                    else model.deleteForever()
                                    dialog = null
                                },
                            ) {
                                Text("Delete permanently", color = Color(0xFFCD4545))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { dialog = null }) { Text("Cancel") }
                        },
                    )
                "info" ->
                    if (selected.size > 1) SelectionInfoDialog(model) { dialog = null }
                    else
                        model.focused?.let { entry ->
                            AlertDialog(
                                onDismissRequest = { dialog = null },
                                title = { Text("Get info") },
                                text = {
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        FileGlyph(entry, 70.dp)
                                        Spacer(Modifier.height(20.dp))
                                        Metadata(entry, model)
                                        MetaInfoText("Full path", entry.path)
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { dialog = null }) { Text("Done") }
                                },
                            )
                        }
                "about" ->
                    AlertDialog(
                        onDismissRequest = { dialog = null },
                        title = { Text("Made for your files.") },
                        text = {
                            Column {
                                Text(
                                    "DroidFinder 0.2 · Tablet edition",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "A native Android file explorer inspired by Finder. Your files and tags stay on this device. No account. Cloud access uses the Android providers you connect.",
                                )
                                Spacer(Modifier.height(16.dp))
                                Text("Keyboard shortcuts", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Ctrl F  Search\nCtrl T / W  New / close tab\nCtrl Shift N or Ctrl N  New folder\nCtrl C / X / V  Copy / cut / paste\nCtrl D  Duplicate\nCtrl Z / Shift Z  Undo / Redo\nCtrl L  Go to folder\nCtrl I  Get info\nCtrl .  Show hidden files\nAlt ← / → / ↑  Navigate\nSpace  Quick Look · F2  Rename",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { dialog = null }) { Text("Done") }
                        },
                    )
            }
            quickLook?.let { entry ->
                PeekOverlay(
                    entry = entry,
                    entries = quickLookCandidates,
                    onSelect = { next ->
                        quickLook = next
                        if (!peekMultipleSelection) {
                            model.clearSelection()
                            model.select(next)
                        }
                    },
                    onOpenWith = onOpenWith,
                    onDismiss = { quickLook = null },
                )
            }
        }
    }
}

@Composable
private fun FinderSidebar(
    model: FinderModel,
    onGrantAccess: () -> Unit,
    onConnectFolder: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxHeight().background(Sidebar)) {
        Row(
            Modifier.height(60.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Blue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    "DroidFinder",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                    letterSpacing = (-.4).sp,
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp),
        ) {
            SidebarHeading("Favorites")
            SidebarItem("Recents", Icons.Outlined.Schedule, model.location == "@recent") {
                model.navigate("@recent")
            }
            SidebarItem(
                "Workspace",
                Icons.Outlined.Workspaces,
                model.location == model.workspace.absolutePath,
                modifier = Modifier.folderDropTarget(model, model.workspace.absolutePath),
            ) {
                model.navigate(model.workspace.absolutePath)
            }
            listOf(
                    "Documents" to Icons.Outlined.Description,
                    "Download" to Icons.Outlined.Download,
                    "Pictures" to Icons.Outlined.Image,
                    "Movies" to Icons.Outlined.Movie,
                    "Music" to Icons.Outlined.MusicNote,
                )
                .forEach { (name, icon) ->
                    val folder = File(model.deviceRoot, name)
                    SidebarItem(
                        if (name == "Download") "Downloads" else name,
                        icon,
                        model.location == folder.absolutePath,
                        enabled = model.hasAccess,
                        modifier = Modifier.folderDropTarget(model, folder.absolutePath),
                    ) {
                        if (model.hasAccess) model.navigate(folder.absolutePath)
                        else onGrantAccess()
                    }
                }
            model.favorites.forEach { path ->
                SidebarItem(
                    model.title(path),
                    Icons.Outlined.Folder,
                    model.location == path,
                    modifier = Modifier.folderDropTarget(model, path),
                ) {
                    model.navigate(path)
                }
            }
            Spacer(Modifier.height(8.dp))
            SidebarHeading("Locations")
            SidebarItem(
                "This tablet",
                Icons.Outlined.TabletMac,
                model.location == model.deviceRoot.absolutePath,
                badge = if (!model.hasAccess) "Connect" else null,
            ) {
                if (model.hasAccess) model.navigate(model.deviceRoot.absolutePath)
                else onGrantAccess()
            }
            model.volumes.forEach { volume ->
                SidebarItem(
                    volume.name,
                    Icons.Outlined.Usb,
                    model.location == volume.absolutePath,
                ) {
                    model.navigate(volume.absolutePath)
                }
            }
            model.connectedLocations.forEach { root ->
                SidebarItem(
                    root.name,
                    Icons.Outlined.CloudQueue,
                    model.location == root.uri,
                    modifier = Modifier.folderDropTarget(model, root.uri),
                ) {
                    model.navigate(root.uri)
                }
            }
            SidebarItem("Connect folder…", Icons.Outlined.Add, false) { onConnectFolder() }
            SidebarItem("Trash", Icons.Outlined.DeleteOutline, model.isTrash) {
                model.navigate("@trash")
            }
            if (model.smartFolders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SidebarHeading("Smart Folders")
                model.smartFolders.forEach { smart ->
                    SidebarItem(
                        smart.name,
                        Icons.Outlined.SavedSearch,
                        model.location == "@smart:${smart.id}",
                    ) {
                        model.navigate("@smart:${smart.id}")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (model.tagsVisible) {
                SidebarHeading("Tags")
                model.tagNames.forEach { tag ->
                    SidebarItem(
                        tag,
                        null,
                        model.location == "@tag:$tag",
                        dot = tagColor(model.tagColorName(tag)),
                    ) {
                        model.navigate("@tag:$tag")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        val disk by
            produceState<Pair<Long, Long>?>(null, model.location, model.hasAccess, model.busy) {
                value =
                    withContext(Dispatchers.IO) {
                        val root = if (model.hasAccess) model.deviceRoot else model.workspace
                        root.usableSpace to root.totalSpace
                    }
            }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null, Modifier.size(15.dp), tint = Muted)
                Text(
                    if (model.hasAccess) "Tablet storage" else "Workspace storage",
                    fontSize = 11.sp,
                    color = Ink,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            disk?.let { (free, total) ->
                LinearProgressIndicator(
                    progress = { if (total > 0) 1f - free.toFloat() / total else 0f },
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(top = 12.dp, bottom = 7.dp)
                            .height(4.dp)
                            .clip(CircleShape),
                    color = Muted.copy(alpha = .65f),
                    trackColor = Border,
                )
                Text("${formatSize(free)} available", fontSize = 9.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun SidebarHeading(text: String) {
    Text(
        text,
        Modifier.padding(start = 12.dp, top = 8.dp, bottom = 7.dp),
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        letterSpacing = 0.sp,
        color = Muted,
    )
}

@Composable
private fun SidebarItem(
    text: String,
    icon: ImageVector?,
    active: Boolean,
    enabled: Boolean = true,
    badge: String? = null,
    dot: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) SelectionSurface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null)
            Icon(
                icon,
                null,
                Modifier.size(18.dp),
                tint = if (active) SelectedInk else Muted.copy(alpha = if (enabled) 1f else .55f),
            )
        if (dot != null)
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
            }
        Text(
            text,
            Modifier.weight(1f).padding(start = 10.dp),
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) SelectedInk else if (enabled) Ink else Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) Text(badge, color = Blue, fontSize = 9.sp)
    }
}

@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    compact: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier.size(if (compact) 34.dp else 38.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (active) SelectionSurface else Color.Transparent),
    ) {
        Icon(
            icon,
            label,
            Modifier.size(if (compact) 19.dp else 20.dp),
            tint = if (!enabled) Muted.copy(alpha = .35f) else if (active) SelectedInk else Muted,
        )
    }
}

@Composable
private fun TabStrip(model: FinderModel) {
    Row(
        Modifier.fillMaxWidth().height(35.dp).background(Sidebar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            model.tabs.forEachIndexed { index, tab ->
                Column(
                    Modifier.folderDropTarget(model, tab.location)
                        .widthIn(min = 120.dp, max = 200.dp)
                        .fillMaxHeight()
                        .background(
                            if (index == model.activeTab) CanvasBackground else Color.Transparent,
                        )
                        .clickable { model.switchTab(index) },
                ) {
                    Row(
                        Modifier.weight(1f).padding(start = 13.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (tab.location.startsWith("@")) Icons.Outlined.FolderSpecial
                            else Icons.Outlined.FolderOpen,
                            null,
                            Modifier.size(13.dp),
                            tint = if (index == model.activeTab) Blue else Muted,
                        )
                        Text(
                            model.title(tab.location),
                            Modifier.widthIn(max = 120.dp).padding(start = 6.dp, end = 12.dp),
                            fontSize = 10.sp,
                            color = if (index == model.activeTab) Ink else Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (model.tabs.size > 1)
                            IconButton(
                                onClick = { model.closeTab(index) },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    "Close ${model.title(tab.location)} tab",
                                    Modifier.size(12.dp),
                                    tint = Muted,
                                )
                            }
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .height(2.dp)
                            .background(if (index == model.activeTab) Blue else Color.Transparent),
                    )
                }
                VerticalDivider(color = Border)
            }
        }
        ToolButton(Icons.Outlined.Add, "New tab", { model.newTab() }, compact = true)
    }
}

@Composable
internal fun ActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    model: FinderModel,
    action: (String) -> Unit,
) {
    DropdownMenu(expanded, onDismiss) {
        val entries = model.selectedEntries
        if (model.undoLabel != null)
            MenuAction("Undo ${model.undoLabel}", Icons.AutoMirrored.Outlined.Undo) {
                action("undo")
            }
        if (model.redoLabel != null)
            MenuAction("Redo ${model.redoLabel}", Icons.AutoMirrored.Outlined.Redo) {
                action("redo")
            }
        MenuAction("New tab", Icons.Outlined.AddBox) { action("newTab") }
        if (entries.isEmpty()) {

            MenuAction("New folder", Icons.Outlined.CreateNewFolder, model.canCreate) {
                action("new")
            }
            MenuAction(
                "Paste",
                Icons.Outlined.ContentPaste,
                model.clipboard != null && model.canCreate,
            ) {
                action("paste")
            }
            MenuAction("Select all", Icons.Outlined.SelectAll, model.entries.isNotEmpty()) {
                model.selectAll()
                onDismiss()
            }
            if (model.isTrash)
                MenuAction(
                    "Empty Trash…",
                    Icons.Outlined.DeleteForever,
                    model.entries.isNotEmpty(),
                ) {
                    action("empty")
                }
            MenuAction("Go to folder…", Icons.AutoMirrored.Outlined.DriveFileMove) {
                action("path")
            }
        } else if (model.isTrash) {
            MenuAction("Restore", Icons.Outlined.Restore) { action("restore") }
            MenuAction("Copy", Icons.Outlined.ContentCopy) { action("copy") }
            MenuAction("Delete permanently…", Icons.Outlined.DeleteForever) { action("delete") }
        } else {
            if (entries.size > 1) MenuAction("Get info", Icons.Outlined.Info) { action("info") }
            if (entries.size == 1) {
                MenuAction(
                    if (entries.first().folder) "Open folder" else "Quick Look",
                    Icons.Outlined.Visibility,
                ) {
                    action(if (entries.first().folder) "open" else "quicklook")
                }
                if (!entries.first().folder)
                    MenuAction("Open with app", Icons.AutoMirrored.Outlined.OpenInNew) {
                        action("external")
                    }
                if (entries.first().folder)
                    MenuAction("Open in new tab", Icons.Outlined.Tab) { action("tab") }
                MenuAction("Get info", Icons.Outlined.Info) { action("info") }

                if (entries.first().folder)
                    MenuAction(
                        if (entries.first().path in model.favorites) "Remove from favorites"
                        else "Add to favorites",
                        Icons.Outlined.StarOutline,
                    ) {
                        action("favorite")
                    }
                if (entries.first().folder)
                    MenuAction("Customize folder…", Icons.Outlined.Palette) { action("appearance") }
                MenuAction("Make shortcut", Icons.AutoMirrored.Outlined.Shortcut) {
                    action("shortcut")
                }
            }
            HorizontalDivider()
            MenuAction(
                if (entries.size == 1) "Rename…" else "Rename ${entries.size} items…",
                Icons.Outlined.DriveFileRenameOutline,
                entries.all { it.canRename },
            ) {
                action("rename")
            }
            MenuAction(
                "New folder with selection…",
                Icons.Outlined.CreateNewFolder,
                model.canCreate,
            ) {
                action("groupSelection")
            }
            MenuAction("Show in enclosing folder", Icons.Outlined.FolderOpen, entries.size == 1) {
                action("reveal")
            }
            MenuAction("Copy to…", Icons.Outlined.ContentCopy) { action("copyTo") }
            MenuAction("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) { action("moveTo") }
            MenuAction("Copy", Icons.Outlined.ContentCopy) { action("copy") }
            MenuAction("Cut", Icons.Outlined.ContentCut, entries.all { it.canDelete }) {
                action("cut")
            }
            MenuAction("Duplicate", Icons.Outlined.FileCopy) { action("duplicate") }
            MenuAction("Compress", Icons.Outlined.FolderZip) { action("compress") }
            if (entries.size == 1 && entries.first().extension == "zip")
                MenuAction("Extract archive", Icons.Outlined.Unarchive) { action("extract") }
            MenuAction("Share…", Icons.Outlined.IosShare) { action("share") }
            if (entries.size == 1)
                MenuAction("Copy full path", Icons.Outlined.Link) { action("copyPath") }
            MenuAction(
                "Move to Trash",
                Icons.Outlined.DeleteOutline,
                entries.all { it.canDelete },
            ) {
                action("trash")
            }
            HorizontalDivider()
            Text(
                "TAGS",
                Modifier.padding(start = 14.dp, top = 10.dp),
                color = Muted,
                fontSize = 10.sp,
            )
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                model.tagNames.take(6).forEach { tag ->
                    IconButton(
                        onClick = {
                            model.tag(tag)
                            onDismiss()
                        },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(
                            Modifier.size(15.dp)
                                .background(tagColor(model.tagColorName(tag)), CircleShape),
                        ) {
                            if (entries.all { tag in model.tagAssignments[it.path].orEmpty() })
                                Icon(
                                    Icons.Outlined.Check,
                                    "Tag $tag",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            else Box(Modifier.fillMaxSize().semanticsLabel("Tag $tag"))
                        }
                    }
                }
                IconButton(
                    onClick = {
                        model.tag(null)
                        onDismiss()
                    },
                    Modifier.size(30.dp),
                ) {
                    Icon(Icons.Outlined.Close, "Remove tag", Modifier.size(15.dp), tint = Muted)
                }
            }
        }
        MenuAction("Tags…", Icons.AutoMirrored.Outlined.Label) { action("tags") }
        MenuAction("Search filters…", Icons.Outlined.FilterAlt) { action("filters") }
        MenuAction(
            "Save search as Smart Folder…",
            Icons.Outlined.SavedSearch,
            model.folderPath != null || model.currentSmartFolder != null,
        ) {
            action("saveSearch")
        }
        if (model.currentSmartFolder != null)
            MenuAction("Rename Smart Folder…", Icons.Outlined.DriveFileRenameOutline) {
                action("renameSmart")
            }
        MenuAction("Preferences…", Icons.Outlined.Tune) { action("settings") }
        MenuAction("Connect provider folder…", Icons.Outlined.CloudQueue) {
            action("connectFolder")
        }
        MenuAction(
            if (model.sidebarVisible) "Hide sidebar" else "Show sidebar",
            Icons.AutoMirrored.Outlined.ViewSidebar,
        ) {
            action("sidebar")
        }
        HorizontalDivider()
        MenuAction("About & keyboard shortcuts", Icons.Outlined.Keyboard) { action("about") }
    }
}

@Composable
private fun MenuAction(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 12.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) },
        enabled = enabled,
        onClick = onClick,
    )
}
