@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.findroid

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun finderColors(dark: Boolean) =
    if (dark)
        darkColorScheme(
            primary = Color(0xFF8DBBFF),
            onPrimary = Color(0xFF12345A),
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
    onEnableNotifications: () -> Unit = {},
    onNewWindow: (String) -> Unit = {},
    onCloseWindow: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onReconnectFolder: (ConnectedLocation) -> Unit = {},
    onProviderApp: (String) -> Unit = {},
    onUpdates: () -> Unit = {},
) {
    val dragScope = rememberCoroutineScope()
    val dragController = remember { FinderDragController(dragScope) }
    val workspaceModel = model
    val model = workspaceModel.activePaneModel
    val configuration = LocalConfiguration.current
    val phone = configuration.screenWidthDp < 600 || configuration.screenHeightDp < 480
    CompositionLocalProvider(
        LocalFinderDrag provides dragController,
        LocalFinderDark provides model.darkTheme,
        LocalFinderDensity provides if (phone) FinderDensity.COMFORTABLE else model.density,
        LocalPhoneLayout provides phone,
    ) {
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
            var commandExplanation by remember { mutableStateOf<CommandExplanation?>(null) }
            var quickLook by workspaceModel.windowState.peekEntry
            var quickLookCandidates by workspaceModel.windowState.peekCandidates
            var peekMultipleSelection by workspaceModel.windowState.peekMultipleSelection
            var menu by remember { mutableStateOf(false) }
            var phoneLocations by remember { mutableStateOf(false) }
            LaunchedEffect(model.location, phone) { phoneLocations = false }
            var cursorMenu by remember { mutableStateOf<FinderContextRequest?>(null) }
            LaunchedEffect(dialog, cursorMenu, menu, phoneLocations, quickLook) {
                workspaceModel.windowState.fileClicks.reset()
            }
            if (model.analysis.visible) FileAnalysisDialog(model)
            if (model.checksums.visible) FileChecksumDialog(model)
            if (model.housekeeping.visible) HousekeepingDialog(model)
            if (model.batchRename.visible) ReviewedRenameDialog(model, onOpenWith)
            if (model.organization.visible) OrganizationDialog(model)
            if (model.setup.visible) SetupDialog(model)
            model.verifiedCopyReview?.let { VerifiedCopyReviewDialog(model, it) }
            if (workspaceModel.workspaceRecovery.visible) WorkspaceRecoveryDialog(workspaceModel, onConnectFolder)
            LaunchedEffect(model.storageRequested) {
                if (model.storageRequested) {
                    dialog = "storage"
                    model.storageRequested = false
                }
            }
            model.reconnectChoice?.let { choice ->
                AlertDialog(
                    onDismissRequest = { model.reconnectChoice = null },
                    title = { Text("Different folder selected") },
                    text = {
                        Text(
                            "The original location is ${choice.original.name}. Add the selected folder as a separate connection, or choose the original folder again.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                model.reconnectChoice = null
                                model.connectLocation(Uri.parse(choice.selected))
                            },
                        ) {
                            Text("Add as new location")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                model.reconnectChoice = null
                                onReconnectFolder(choice.original)
                            },
                        ) {
                            Text("Choose original folder")
                        }
                    },
                )
            }
            var archiveEntry by remember { mutableStateOf<Entry?>(null) }
            var infoEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
            var archiveSelection by remember { mutableStateOf<Set<String>?>(null) }
            var archiveLimits by remember { mutableStateOf(ZipLimits()) }
            var archiveExtractEntry by remember { mutableStateOf<Entry?>(null) }
            var archiveName by remember { mutableStateOf<String?>(null) }
            var archiveLevel by remember { mutableIntStateOf(-1) }
            var searchActive by
                remember(model.tabs[model.activeTab].id) {
                    mutableStateOf(model.query.isNotEmpty())
                }
            var searchFocused by remember { mutableStateOf(false) }
            var addressActive by remember(model.tabs[model.activeTab].id) { mutableStateOf(false) }
            var addressFocused by remember { mutableStateOf(false) }
            var addressValue by remember {
                mutableStateOf(androidx.compose.ui.text.input.TextFieldValue())
            }
            var keyboardArea by remember { mutableIntStateOf(0) }
            var keyboardFocusRequest by remember { mutableIntStateOf(0) }
            val addressFocus = remember { FocusRequester() }
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
                else if (!entry.folder && entry.extension == "zip") {
                    model.clearSelection()
                    model.select(entry)
                    archiveEntry = entry
                    dialog = "archive"
                } else if (entry.folder) model.navigate(entry.path) else onOpen(entry)
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
                if (quickLook == null && !searchActive && !addressActive) rootFocus.requestFocus()
            }
            LaunchedEffect(dialog, cursorMenu, model.organization.visible, model.verifiedCopyReview) {
                if (
                    dialog == null &&
                        cursorMenu == null &&
                        !model.organization.visible &&
                        model.verifiedCopyReview == null &&
                        quickLook == null &&
                        !searchActive &&
                        !addressActive
                )
                    rootFocus.requestFocus()
            }
            val action: (String) -> Unit = commandAction@{ command ->
                menu = false
                model.commandProblem(command)?.let { problem ->
                    commandExplanation = CommandExplanation(commandTitle(command), problem,
                        (if (model.selected.isEmpty()) "Location: " else "${model.selected.size} selected · ") +
                            if (model.folderPath == null) model.title() else model.displayPath(model.location))
                    dialog = "commandUnavailable"
                    return@commandAction
                }
                when (command) {
                    "open" -> model.focused?.let(open)
                    "external" -> model.focused?.let(onOpenWith)
                    "quicklook" -> model.focused?.let(showQuickLook)
                    "phoneNew" -> dialog = "phoneNew"
                    "phoneView" -> dialog = "phoneView"
                    "new" -> dialog = "new"
                    "rename" ->
                        if (selected.size > 1) model.batchRename.open(selected)
                        else dialog = "rename"
                    "batchRename" -> model.batchRename.open(selected)
                    "groupSelection" -> dialog = "groupSelection"
                    "tags" ->
                        model.organization.open(initialPage = if (selected.isEmpty()) 1 else 2)
                    "collections" -> model.organization.open(initialPage = 0)
                    "copyTo" -> dialog = "copyTo"
                    "moveTo" -> dialog = "moveTo"
                    "reveal" -> model.revealSelection()
                    "copy" -> model.copySelection(false)
                    "cut" -> model.copySelection(true)
                    "paste" -> model.paste()
                    "pasteVerified" -> model.reviewVerifiedPaste()
                    "pasteIntoVerified" -> model.actionEntries.singleOrNull()?.takeIf { it.folder }?.let { model.reviewVerifiedPaste(it.path) }
                    "duplicateVerified" -> model.reviewVerifiedDuplicate()
                    "pasteInto" -> model.actionEntries.singleOrNull()?.takeIf { it.folder }?.let { model.paste(it.path) }
                    "duplicate" -> model.duplicate()
                    "trash" -> model.trash()
                    "restore" -> model.restore()
                    "undo" -> model.undo()
                    "redo" -> model.redo()
                    "delete" -> dialog = "delete"
                    "empty" -> dialog = "empty"
                    "compress" -> dialog = "compress"
                    "extractTo" -> dialog = "extractTo"
                    "newText" -> dialog = "newText"
                    "folderInfo" -> dialog = "folderInfo"
                    "extractHere" -> model.extract()
                    "extract" -> if (!model.canCreate) dialog = "extractTo" else model.extract()
                    "share" -> onShare(selected)
                    "info" -> {
                        infoEntries = model.actionEntries
                        if (infoEntries.isNotEmpty()) dialog = "info"
                    }
                    "path" -> dialog = "path"
                    "copyPath" -> {
                        model.focused?.let { clipboard.setText(AnnotatedString(it.path)) }
                        model.message = "Path copied"
                    }
                    "favorite" ->
                        model.focused?.takeIf { it.folder }?.let { model.favoritePath(it.path) }
                    "tab" -> model.focused?.takeIf { it.folder }?.let { model.newTab(it.path) }
                    "transfers" -> dialog = "transfers"
                    "recentActions" -> dialog = "recentActions"
                    "tabs" -> dialog = "tabs"
                    "workspaces" -> dialog = "workspaces"
                    "setup" -> model.setup.open()
                    "viewSettings" -> dialog = "viewSettings"
                    "folderViews" -> dialog = "folderViews"
                    "about" -> dialog = "about"
                    "filters" -> dialog = "filters"
                    "availableActions" -> dialog = "availableActions"
                    "searchIndex" -> dialog = "searchIndex"
                    "recentSearches" -> { model.rememberCurrentSearch(); dialog = "recentSearches" }
                    "searchLocations" -> { model.rememberCurrentSearch(); dialog = "searchLocations" }
                    "saveSearch" -> dialog = "saveSearch"
                    "renameSmart" -> dialog = "renameSmart"
                    "settings" -> dialog = "settings"
                    "housekeeping" -> model.housekeeping.open()
                    "connectFolder" -> onConnectFolder()
                    "storage" -> {
                        model.storageRequested = true
                    }
                    "analysis" ->
                        (model.focused?.takeIf { it.folder }?.path ?: model.folderPath)?.let {
                            model.analysis.open(it)
                        }
                    "checksums" ->
                        model.focused?.takeIf { !it.folder }?.let { model.checksums.open(it.path) }
                    "appearance" -> dialog = "appearance"
                    "shortcut" -> model.makeShortcut()
                    "sidebar" -> if (phone) phoneLocations = true else model.toggleSidebar()
                    "newTab" -> model.newTab()
                    "dualPane" -> model.toggleDualPane()
                    "otherPane" -> model.focusOtherPane()
                    "swapPanes" -> model.swapPanes()
                    "copyOtherPane" -> model.transferToOtherPane(false)
                    "moveOtherPane" -> model.transferToOtherPane(true)
                    "comparePanes" -> dialog = "comparePanes"
                    "shortcuts" -> dialog = "shortcuts"
                    "launcher" -> dialog = "launcher"
                    "quickJump" -> dialog = "quickJump"
                    "hidden" -> model.toggleHidden()
                    "updates" -> onUpdates()
                    "newWindow" -> onNewWindow(model.location)
                }
            }
            // A successful Trash operation may still be flushing its recovery record.
            // Offer the snackbar action only when a tap can start the next operation.
            val undoSavePending = model.busy != null && model.canUndoTrash && model.message?.endsWith("moved to Trash") == true
            LaunchedEffect(model.message, undoSavePending) {
                if (undoSavePending) return@LaunchedEffect
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
            LaunchedEffect(
                searchActive,
                addressActive,
                model.tabs[model.activeTab].id,
                keyboardFocusRequest,
            ) {
                if (!addressActive && !searchActive) rootFocus.requestFocus()
            }
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
            LaunchedEffect(model.folderViewRequested) {
                if (model.folderViewRequested) {
                    model.folderViewRequested = false
                    dialog = "viewSettings"
                }
            }
            LaunchedEffect(model.tabOverviewRequested) {
                if (model.tabOverviewRequested) {
                    model.tabOverviewRequested = false
                    dialog = "tabs"
                }
            }
            LaunchedEffect(model.contextRequested) {
                if (model.contextRequested != null) {
                    cursorMenu = model.contextRequested
                    model.contextRequested = null
                }
            }
            BackHandler(
                enabled =
                    quickLook != null ||
                        model.selected.isNotEmpty() ||
                        (phone && (searchActive || addressActive || model.query.isNotEmpty())) ||
                        model.tabs[model.activeTab].index > 0,
            ) {
                when {
                    quickLook != null -> quickLook = null
                    model.selected.isNotEmpty() -> model.clearSelection()
                    phone && addressActive -> addressActive = false
                    phone && (searchActive || model.query.isNotEmpty()) -> {
                        model.updateQuery("")
                        searchActive = false
                    }
                    else -> model.back()
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(workspaceModel.windowState) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Scroll)
                                    model.windowState.fileClicks.reset()
                                if (event.type == PointerEventType.Press)
                                    workspaceModel.windowState.fileClicks.pointerDown()
                            }
                        }
                    }
                    .finderDragHost(dragController)
                    .background(AppBackground)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .focusRequester(rootFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || dialog != null || model.verifiedCopyReview != null) false
                        else {
                            val editing = searchFocused || addressFocused
                            val shortcut =
                                explorerShortcut(
                                    event.key,
                                    event.isCtrlPressed,
                                    event.isMetaPressed,
                                    event.isAltPressed,
                                    event.isShiftPressed,
                                    editing,
                                )
                            if (shortcut == null) {
                                if (
                                    !editing &&
                                        !event.isCtrlPressed &&
                                        !event.isMetaPressed &&
                                        !event.isAltPressed &&
                                        event.utf16CodePoint in 32..65535
                                ) {
                                    model.typeSelect(event.utf16CodePoint.toChar())
                                    true
                                } else false
                            } else if (
                                event.nativeKeyEvent.repeatCount > 0 &&
                                    shortcut.action !in
                                        setOf(
                                            "moveSelection",
                                            "left",
                                            "right",
                                            "page",
                                            "home",
                                            "end",
                                        )
                            )
                                true
                            else {
                                fun focusAddress() {
                                    keyboardFocusRequest++
                                    val path = model.folderPath ?: model.workspace.path
                                    addressValue =
                                        androidx.compose.ui.text.input.TextFieldValue(
                                            path,
                                            androidx.compose.ui.text.TextRange(0, path.length),
                                        )
                                    addressActive = true
                                }
                                fun step(delta: Int) =
                                    if (kotlin.math.abs(delta) == 2 && model.mode == ViewMode.ICONS)
                                        (if (delta < 0) -model.gridColumns else model.gridColumns)
                                    else if (delta < 0) -1 else 1
                                when (shortcut.action) {
                                    "search" -> {
                                        keyboardFocusRequest++
                                        addressActive = false
                                        searchActive = true
                                    }
                                    "path" -> focusAddress()
                                    "launcher", "quickJump" -> action(shortcut.action)
                                    "newWindow" -> onNewWindow(model.location)
                                    "closeWindow" -> onCloseWindow()
                                    "newTab" -> model.newTab(atEnd = true)
                                    "reopenTab" -> model.reopenTab()
                                    "closeTab" ->
                                        if (
                                            model.tabs.size == 1 &&
                                                !model.tabs[model.activeTab].pinned
                                        )
                                            onCloseWindow()
                                        else model.closeTab(model.activeTab)
                                    "cycleTab" -> model.cycleTab(shortcut.amount)
                                    "selectTab" ->
                                        model.switchTab(
                                            if (shortcut.amount == 9) model.tabs.lastIndex
                                            else shortcut.amount - 1,
                                        )
                                    "finderView" ->
                                        model.changeMode(ViewMode.entries[shortcut.amount - 1])
                                    "windowsView" -> model.windowsView(shortcut.amount)
                                    "refresh" -> model.refresh()
                                    "selectAll" -> model.selectAll()
                                    "clearSelection" -> model.clearSelection()
                                    "copy", "cut", "paste" -> action(shortcut.action)
                                    "toggleFocused" -> model.toggleFocusedSelection()
                                    "moveSelection" ->
                                        if (shortcut.focusOnly)
                                            model.moveKeyboardFocus(step(shortcut.amount))
                                        else
                                            model.stepSelection(
                                                step(shortcut.amount),
                                                shortcut.extend,
                                                shortcut.additive,
                                            )
                                    "left" ->
                                        if (shortcut.extend) model.stepSelection(-1, true)
                                        else model.keyboardLeft()
                                    "right" ->
                                        if (shortcut.extend) model.stepSelection(1, true)
                                        else model.keyboardRight()
                                    "home" -> model.selectBoundary(false, shortcut.extend)
                                    "end" -> model.selectBoundary(true, shortcut.extend)
                                    "page" ->
                                        model.stepSelection(
                                            shortcut.amount * model.keyboardPageSize,
                                            shortcut.extend,
                                        )
                                    "back" -> model.back()
                                    "forward" -> model.forward()
                                    "up" -> model.up()
                                    "expand" -> model.expandKeyboardSelection()
                                    "expandAll" -> model.expandKeyboardSelection(true)
                                    "collapse" -> model.collapseKeyboardSelection()
                                    "preview" -> model.toggleInspector()
                                    "details" -> model.toggleInspector(true)
                                    "navigationTree" -> model.revealNavigationTree()
                                    "fitColumns" -> model.fitColumnsToContents()
                                    "fullscreen" -> onToggleFullscreen()
                                    "focusArea" -> {
                                        val areas =
                                            if (model.dualPane)
                                                listOf("files", "otherPane", "address", "search")
                                            else listOf("files", "address", "search")
                                        val current =
                                            when {
                                                addressFocused -> areas.indexOf("address")
                                                searchFocused -> areas.indexOf("search")
                                                else -> keyboardArea.coerceAtMost(areas.lastIndex)
                                            }
                                        keyboardArea =
                                            Math.floorMod(current + shortcut.amount, areas.size)
                                        when (areas[keyboardArea]) {
                                            "address" -> focusAddress()
                                            "search" -> {
                                                addressActive = false
                                                searchActive = true
                                            }
                                            else -> {
                                                addressActive = false
                                                searchActive = false
                                                if (areas[keyboardArea] == "otherPane")
                                                    model.focusOtherPane()
                                                rootFocus.requestFocus()
                                            }
                                        }
                                    }
                                    "quicklook" ->
                                        if (quickLook != null) quickLook = null
                                        else model.focused?.let(showQuickLook)
                                    "context" ->
                                        model.focused?.let { model.requestContext(it) }
                                            ?: model.requestBackgroundContext()
                                    "actions" -> menu = true
                                    "cancel" -> {
                                        if (addressActive) addressActive = false
                                        else if (searchFocused) {
                                            model.finishAndClearSearchQuery()
                                            searchActive = false
                                        } else {
                                            model.clearSelection()
                                            quickLook = null
                                        }
                                        rootFocus.requestFocus()
                                    }
                                    "trash" -> action(if (model.isTrash) "delete" else "trash")
                                    "delete", "rename", "new" -> action(shortcut.action)
                                    "hidden" -> model.toggleHidden()
                                    else -> action(shortcut.action)
                                }
                                true
                            }
                        }
                    }
                    .focusable(),
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (!phone && model.sidebarVisible && screenWidth >= 600) {
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
                            addressActive = addressActive,
                            addressValue = addressValue,
                            onAddressChange = { addressValue = it },
                            addressFocus = addressFocus,
                            onAddressFocused = { addressFocused = it },
                            onAddressCommit = {
                                model.goTo(addressValue.text)
                                addressActive = false
                                rootFocus.requestFocus()
                            },
                            onAddressClose = {
                                addressActive = false
                                rootFocus.requestFocus()
                            },
                            searchActive = searchActive || model.query.isNotEmpty(),
                            onSearch = {
                                keyboardFocusRequest++
                                searchActive = true
                            },
                            searchFocus = searchFocus,
                            focusRequest = keyboardFocusRequest,
                            onSearchFocused = { searchFocused = it },
                            onSearchFinished = { rootFocus.requestFocus() },
                            onCloseSearch = {
                                model.finishAndClearSearchQuery()
                                searchActive = false
                                rootFocus.requestFocus()
                            },
                            menuExpanded = menu,
                            onMenuChange = { menu = it },
                        )
                        FinderWorkspace(
                            model,
                            onOpen = { pane, entry ->
                                pane.activatePane()
                                if (entry.extension == "finderlink")
                                    pane.followShortcut(entry) { target ->
                                        if (target.folder) pane.navigate(target.path)
                                        else onOpen(target)
                                    }
                                else if (!entry.folder && entry.extension == "zip") {
                                    pane.clearSelection()
                                    pane.select(entry)
                                    archiveEntry = entry
                                    dialog = "archive"
                                } else if (entry.folder) pane.navigate(entry.path)
                                else onOpen(entry)
                            },
                            showQuickLook = showQuickLook,
                            onOpenWith = onOpenWith,
                            onGrantAccess = onGrantAccess,
                            onNew = { pane ->
                                pane.activatePane()
                                dialog = "new"
                            },
                            onGoTo = { pane ->
                                pane.activatePane()
                                dialog = "path"
                            },
                            focusFiles = { rootFocus.requestFocus() },
                            modifier = Modifier.weight(1f),
                        )
                        if (model.busy != null || model.transferJobs.any { !it.terminal })
                            TransferStrip(model) { action("transfers") }
                        if (!model.dualPane && (!phone || configuration.screenHeightDp >= 480))
                            PathBar(model) { action("path") }
                        if (phone)
                            PhoneBottomBar(
                                model,
                                { phoneLocations = true },
                                action,
                                {
                                    model.selectedEntries.firstOrNull()?.let {
                                        model.requestContext(it)
                                    } ?: run { menu = true }
                                },
                            )
                    }
                }
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = if (model.busy != null) 100.dp else 58.dp),
                ) { data ->
                    FinderFeedback(data)
                }
                DragFeedback(
                    dragController,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
                )
            }

            if (phone && phoneLocations) {
                ModalBottomSheet(
                    onDismissRequest = { phoneLocations = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Sidebar,
                    modifier = Modifier.testTag("phone-locations"),
                ) {
                    FinderSidebar(
                        model,
                        {
                            phoneLocations = false
                            onGrantAccess()
                        },
                        {
                            phoneLocations = false
                            onConnectFolder()
                        },
                        Modifier.fillMaxWidth()
                            .fillMaxHeight(if (configuration.screenHeightDp < 480) 1f else .9f),
                        onLocationChosen = { phoneLocations = false },
                        onClose = { phoneLocations = false },
                    )
                }
            }
            when (dialog) {
                "recentActions" -> RecentActionsDialog(model, action) { dialog = null }
                "commandUnavailable" -> commandExplanation?.let { value ->
                    CommandExplanationDialog(value, recover = { command ->
                        commandExplanation = null; dialog = null; action(command)
                    }, dismiss = { commandExplanation = null; dialog = null })
                }
                "phoneView" -> PhoneViewDialog(model) { dialog = null }
                "phoneNew" ->
                    AlertDialog(
                        onDismissRequest = { dialog = null },
                        title = { Text("New") },
                        text = {
                            Column {
                                CommandMenuAction(model, "new", "New folder", Icons.Outlined.CreateNewFolder) { action("new") }
                                CommandMenuAction(model, "newText", "New text file", Icons.Outlined.NoteAdd) { action("newText") }
                                MenuAction("New tab", Icons.Outlined.Tab) {
                                    model.newTab(atEnd = true)
                                    dialog = null
                                }
                                MenuAction("Connect folder…", Icons.Outlined.Add) {
                                    dialog = null
                                    onConnectFolder()
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { dialog = null }) { Text("Cancel") }
                        },
                    )
                "viewSettings" ->
                    FolderViewDialog(model, { dialog = "folderViews" }) { dialog = null }
                "folderViews" -> FolderViewsManagerDialog(model) { dialog = null }
                "tabs" -> TabOverviewDialog(model) { dialog = null }
                "workspaces" -> SavedWorkspacesDialog(model) { dialog = null }
                "shortcuts" -> ShortcutGuideDialog(model) { dialog = null }
                "comparePanes" ->
                    FolderComparisonDialog(
                        if (model.panesReversed) model.secondaryPane else model.primaryPane,
                        if (model.panesReversed) model.primaryPane else model.secondaryPane,
                    ) {
                        dialog = null
                    }
                "transfers" -> TransferCenterDialog(model, { dialog = null }, onEnableNotifications)
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
                                "Android stopped the previous session before this operation finished:\n\n${model.interruptedTransfer?.lineSequence()?.firstOrNull().orEmpty()}\n\nCompleted items were kept. Hidden .findroid-* staging files may remain in the destination. Review the destination before retrying; existing files will not be overwritten.",
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
                "searchLocations" -> SearchLocationsDialog(model) { dialog = null }
                "filters" -> SearchFiltersDialog(model) { dialog = null }
                "availableActions" -> AvailableActionsDialog(model, { dialog = "storage" }) { dialog = null }
                "searchIndex" -> LocalIndexDialog(model) { dialog = null }
                "recentSearches" -> RecentSearchesDialog(model, action) { dialog = null }
                "launcher", "quickJump" -> ExplorerLauncherDialog(model, dialog == "quickJump", action) { dialog = null }
                "settings" -> PreferencesDialog(model, onUpdates = {
                    dialog = null
                    onUpdates()
                }) { dialog = null }
                "saveSearch" ->
                    NameDialog(
                        "Save Smart Folder",
                        model.query.ifBlank { "Smart Folder" },
                        "Save",
                        { dialog = null },
                        onConfirm = {
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
                        onConfirm = {
                            model.renameSmartFolder(it)
                            dialog = null
                        },
                    )
                "copyTo",
                "moveTo" -> DestinationDialog(model, dialog == "moveTo") { dialog = null }
                "compress" ->
                    CompressDialog(model, { dialog = null }) { name, level, choose ->
                        archiveName = name
                        archiveLevel = level
                        if (choose) dialog = "compressTo"
                        else {
                            model.compress(name, level)
                            dialog = null
                        }
                    }
                "compressTo" ->
                    DestinationDialog(
                        model,
                        false,
                        archive = true,
                        archiveName = archiveName,
                        compressionLevel = archiveLevel,
                    ) {
                        dialog = null
                    }
                "storage" ->
                    StorageLocationsDialog(
                        model,
                        onGrantAccess,
                        onConnectFolder,
                        onReconnectFolder,
                        onProviderApp,
                    ) {
                        dialog = null
                    }
                "archive" ->
                    archiveEntry?.let { entry ->
                        ArchiveBrowserDialog(
                            model,
                            entry,
                            { dialog = null },
                            { choose, selection, limits ->
                                model.clearSelection()
                                model.select(entry)
                                archiveSelection = selection
                                archiveLimits = limits
                                archiveExtractEntry = entry
                                if (choose || !model.canCreate) dialog = "archiveExtractTo"
                                else {
                                    model.extractEntryTo(
                                        entry,
                                        model.folderPath ?: model.workspace.path,
                                        selection,
                                        limits,
                                    )
                                    dialog = null
                                }
                            },
                            { onOpenWith(entry) },
                            onMemberOpenWith = onOpenWith,
                        )
                    }
                "folderInfo" ->
                    model.folderInfo?.let { entry ->
                        AlertDialog(
                            onDismissRequest = { dialog = null },
                            title = { Text("Folder info") },
                            text = {
                                Column(
                                    Modifier.widthIn(min = 300.dp)
                                        .heightIn(max = 480.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Metadata(entry, model)
                                    Text("Full path", color = Muted, fontSize = 11.sp)
                                    Text(entry.path, fontSize = 12.sp)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { dialog = null }) { Text("Done") }
                            },
                        )
                    }
                "newText" ->
                    NameDialog(
                        "New text file",
                        "Untitled.txt",
                        "Create",
                        { dialog = null },
                        onConfirm = {
                            model.createTextFile(it)
                            dialog = null
                        },
                    )
                "archiveExtractTo" ->
                    DestinationDialog(
                        model,
                        false,
                        extract = true,
                        onDestination = { parent ->
                            archiveExtractEntry?.let {
                                model.extractEntryTo(it, parent, archiveSelection, archiveLimits)
                            }
                        },
                    ) {
                        dialog = null
                    }
                "extractTo" -> DestinationDialog(model, false, extract = true) { dialog = null }
                "groupSelection" ->
                    NameDialog(
                        "New folder with selection",
                        "New folder",
                        "Create",
                        { dialog = null },
                        onConfirm = {
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
                    if (infoEntries.size > 1)
                        SelectionInfoDialog(model, entries = infoEntries) { dialog = null }
                    else
                        infoEntries.singleOrNull()?.let { entry ->
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
                                    "Findroid ${BuildConfig.VERSION_NAME}",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "A native Android file explorer inspired by Finder. Your files and tags stay on this device. No account. Cloud access uses the Android providers you connect.",
                                )
                                Spacer(Modifier.height(16.dp))
                                Text("Keyboard shortcuts", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "F1  Full keyboard shortcut guide\nCtrl N  New window\nCtrl T / W  New / close tab\nCtrl Shift N  New folder\nCtrl C / X / V  Copy / cut / paste\nCtrl D / Delete  Move to Trash\nCtrl Z / Y  Undo / Redo\nCtrl L / Alt D / F4  Address bar\nAlt Enter  Get info\nAlt P  Preview · Alt Shift P  Details\nSpace  Quick Look · F2  Rename",
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
            model.fileConflict?.let { FileConflictDialog(model, it) }
            cursorMenu?.let { request ->
                FileContextMenu(request, model, action) { cursorMenu = null }
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
                    onDismiss = {
                        quickLook = null
                        quickLookCandidates = emptyList()
                        peekMultipleSelection = false
                    },
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
    onLocationChosen: () -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val short = LocalConfiguration.current.screenHeightDp < 480
    val navigate: (String) -> Unit = {
        model.navigate(it)
        onLocationChosen()
    }
    Column(modifier.fillMaxHeight().background(Sidebar)) {
        Row(
            Modifier.height(if (short) 48.dp else 60.dp).padding(horizontal = 16.dp),
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
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    "Findroid",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                    letterSpacing = (-.4).sp,
                )
            }
            if (onClose != null) ToolButton(Icons.Outlined.Close, "Close locations", onClose)
        }
        val sidebarScroll = rememberScrollState()
        Column(
            Modifier.weight(1f)
                .dragAutoScroll(sidebarScroll)
                .verticalScroll(sidebarScroll)
                .padding(horizontal = 10.dp),
        ) {
            FavoritesSidebar(model, navigate, onGrantAccess)
            Spacer(Modifier.height(8.dp))
            SidebarHeading("Locations")
            SidebarItem(
                if (LocalPhoneLayout.current) "This phone" else "This tablet",
                if (LocalPhoneLayout.current) Icons.Outlined.PhoneAndroid
                else Icons.Outlined.TabletMac,
                model.location == model.deviceRoot.absolutePath,
                badge = if (!model.hasAccess) "Connect" else null,
            ) {
                if (model.hasAccess) navigate(model.deviceRoot.absolutePath) else onGrantAccess()
            }
            model.volumes.forEach { volume ->
                SidebarItem(
                    volume.name,
                    Icons.Outlined.Usb,
                    model.location == volume.absolutePath,
                ) {
                    navigate(volume.absolutePath)
                }
            }
            model.connectedLocations.forEach { root ->
                SidebarItem(
                    root.name,
                    Icons.Outlined.CloudQueue,
                    model.location == root.uri,
                    modifier = Modifier.folderDropTarget(model, root.uri),
                ) {
                    navigate(root.uri)
                }
            }
            SidebarItem("Connect folder…", Icons.Outlined.Add, false) { onConnectFolder() }
            SidebarItem("Storage locations…", Icons.Outlined.Storage, false) {
                model.storageRequested = true
                onLocationChosen()
            }
            NavigationTree(model)
            Spacer(Modifier.height(8.dp))
            SidebarHeading("Collections")
            (model.collections.take(6) + listOfNotNull(model.currentCollection))
                .distinctBy { it.id }
                .forEach { collection ->
                    SidebarItem(
                        collection.name,
                        Icons.Outlined.CollectionsBookmark,
                        model.location == "@collection:${collection.id}",
                        dot = tagColor(collection.color),
                    ) {
                        navigate("@collection:${collection.id}")
                    }
                }
            SidebarItem(
                "All collections…",
                Icons.Outlined.CollectionsBookmark,
                false,
                badge =
                    if (model.libraryError != null || model.libraryNotice != null) "Review"
                    else null,
            ) {
                model.organization.open(initialPage = 0)
                onLocationChosen()
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
                        navigate("@smart:${smart.id}")
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
                        navigate("@tag:$tag")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        HorizontalDivider(color = Border, thickness = .5.dp)
        Box(Modifier.padding(horizontal = 10.dp)) {
            SidebarItem("Trash", Icons.Outlined.DeleteOutline, model.isTrash) {
                navigate("@trash")
            }
        }
        val disk by
            produceState<Pair<Long, Long>?>(null, model.location, model.hasAccess, model.busy) {
                value =
                    withContext(Dispatchers.IO) {
                        val root = if (model.hasAccess) model.deviceRoot else model.workspace
                        root.usableSpace to root.totalSpace
                    }
            }
        if (!short)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Storage, null, Modifier.size(15.dp), tint = Muted)
                    Text(
                        if (model.hasAccess)
                            (if (LocalPhoneLayout.current) "Phone storage" else "Tablet storage")
                        else "Workspace storage",
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
internal fun FavoritesDropArea(model: FinderModel) {
    val controller = LocalFinderDrag.current
    val hovering = controller?.hovered?.action == DropAction.FAVORITE
    val error = controller?.error.takeIf { hovering }
    Column(Modifier.fillMaxWidth().testTag("favorites-drop-target").favoritesDropTarget(model)) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(bottom = 6.dp)
                .border(1.dp, if (hovering) Color.Transparent else Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (error != null) "Can't add here" else if (hovering) "Release to add" else "Drag folders here",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (error != null) MaterialTheme.colorScheme.error else if (hovering) Blue else Ink,
            )
            Text(
                if (error != null) "Use an available folder"
                else if (hovering) "Folders stay where they are" else "Keep a shortcut in Favorites",
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = Muted,
            )
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
internal fun SidebarItem(
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
            .heightIn(min = LocalFinderDensity.current.rowHeight)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        focusable = false,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier =
                Modifier.size(
                        if (LocalFinderDensity.current.touch) 48.dp
                        else if (compact) 34.dp else 38.dp,
                    )
                    .clip(RoundedCornerShape(6.dp))
                    .hoverable(interaction)
                    .background(
                        if (active) SelectionSurface
                        else if (hovered && enabled) ToolbarSurface else Color.Transparent,
                    ),
        ) {
            Icon(
                icon,
                label,
                Modifier.size(if (compact) 18.dp else 20.dp),
                tint =
                    if (!enabled) Muted.copy(alpha = .35f) else if (active) SelectedInk else Muted,
            )
        }
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
        val idle = model.busy == null
        MenuSection("App and workspaces")
        MenuAction("App updates", Icons.Outlined.SystemUpdateAlt) { action("updates") }
        MenuAction("New window", Icons.AutoMirrored.Outlined.OpenInNew) { action("newWindow") }
        MenuAction("Tab overview", Icons.Outlined.Tab) { action("tabs") }
        MenuAction("Folder view settings…", Icons.Outlined.Tune) { action("viewSettings") }
        MenuAction("Workspaces…", Icons.Outlined.DashboardCustomize) { action("workspaces") }
        MenuAction("Keyboard shortcuts", Icons.Outlined.Keyboard) { action("shortcuts") }
        MenuSection("Panes and transfers")
        MenuAction("Transfers", Icons.Outlined.SyncAlt) { action("transfers") }
        MenuAction(
            if (model.dualPane) "Hide dual panes" else "Show dual panes",
            Icons.Outlined.VerticalSplit,
        ) {
            action("dualPane")
        }
        if (model.dualPane) {
            MenuAction("Focus other pane", Icons.AutoMirrored.Outlined.CompareArrows) {
                action("otherPane")
            }
            MenuAction("Swap panes", Icons.Outlined.SwapHoriz) { action("swapPanes") }
            MenuAction(
                "Compare folders…",
                Icons.Outlined.Compare,
                model.folderPath != null && model.otherPane.folderPath != null,
            ) {
                action("comparePanes")
            }
            CommandMenuAction(model, "copyOtherPane", "Copy to other pane", Icons.Outlined.ContentCopy) { action("copyOtherPane") }
            CommandMenuAction(model, "moveOtherPane", "Move to other pane", Icons.AutoMirrored.Outlined.DriveFileMove) { action("moveOtherPane") }
        }
        MenuSection("Recent actions")
        MenuAction("Recent actions…", Icons.Outlined.History) { action("recentActions") }
        if (model.undoLabel != null)
            CommandMenuAction(model, "undo", "Undo ${model.undoLabel}", Icons.AutoMirrored.Outlined.Undo) { action("undo") }
        if (model.redoLabel != null)
            CommandMenuAction(model, "redo", "Redo ${model.redoLabel}", Icons.AutoMirrored.Outlined.Redo) { action("redo") }
        HorizontalDivider()
        MenuSection("Navigation and view")
        MenuAction("Jump to folder…", Icons.Outlined.FolderOpen) { action("quickJump") }
        MenuAction("Find an action or folder…", Icons.Outlined.Search) { action("launcher") }
        MenuAction(
            "Back",
            Icons.AutoMirrored.Outlined.ArrowBack,
            model.tabs[model.activeTab].index > 0,
        ) {
            model.back()
            onDismiss()
        }
        MenuAction(
            "Forward",
            Icons.AutoMirrored.Outlined.ArrowForward,
            model.tabs[model.activeTab].index < model.tabs[model.activeTab].history.lastIndex,
        ) {
            model.forward()
            onDismiss()
        }
        MenuAction("Enclosing folder", Icons.Outlined.ArrowUpward, model.parentPath() != null) {
            model.up()
            onDismiss()
        }
        MenuAction(
            if (model.previewVisible) "Hide preview pane" else "Show preview pane",
            Icons.Outlined.VerticalSplit,
        ) {
            model.togglePreview()
            onDismiss()
        }
        HorizontalDivider()
        MenuAction("New tab", Icons.Outlined.AddBox) { action("newTab") }
        MenuAction("Reopen closed tab", Icons.Outlined.Restore, model.canReopenTab) {
            model.reopenTab()
            onDismiss()
        }
        MenuSection("Files")
        CommandMenuAction(model, "new", "New folder", Icons.Outlined.CreateNewFolder) { action("new") }
        CommandMenuAction(model, "newText", "New text file…", Icons.AutoMirrored.Outlined.NoteAdd) { action("newText") }
        if (entries.isEmpty()) {

            CommandMenuAction(model, "paste", "Paste", Icons.Outlined.ContentPaste) { action("paste") }
            if (model.clipboard?.cut == false)
                CommandMenuAction(model, "pasteVerified", "Paste and verify…", Icons.Outlined.ContentPaste) { action("pasteVerified") }
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
            CommandMenuAction(model, "restore", "Restore", Icons.Outlined.Restore) { action("restore") }
            CommandMenuAction(model, "copy", "Copy", Icons.Outlined.ContentCopy) { action("copy") }
            CommandMenuAction(model, "delete", "Delete permanently…", Icons.Outlined.DeleteForever) { action("delete") }
        } else {
            if (entries.size > 1) CommandMenuAction(model, "info", "Get info", Icons.Outlined.Info) { action("info") }
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
                if (!entries.first().folder)
                    MenuAction("Checksums…", Icons.Outlined.Fingerprint) { action("checksums") }
                if (entries.first().folder)
                    MenuAction("Open in new tab", Icons.Outlined.Tab) { action("tab") }
                CommandMenuAction(model, "info", "Get info", Icons.Outlined.Info) { action("info") }

                if (entries.first().folder)
                    MenuAction(
                        if (entries.first().path in model.favorites) "Remove from favorites"
                        else "Add to favorites",
                        Icons.Outlined.StarOutline,
                    ) {
                        action("favorite")
                    }
                if (entries.first().folder)
                    CommandMenuAction(model, "appearance", "Customize folder…", Icons.Outlined.Palette) {
                        action("appearance")
                    }
                CommandMenuAction(model, "shortcut", "Make shortcut", Icons.AutoMirrored.Outlined.Shortcut) { action("shortcut") }
            }
            HorizontalDivider()
            CommandMenuAction(model, "rename", if (entries.size == 1) "Rename…" else "Rename ${entries.size} items…", Icons.Outlined.DriveFileRenameOutline) { action("rename") }
            CommandMenuAction(model, "groupSelection", "New folder with selection…", Icons.Outlined.CreateNewFolder) { action("groupSelection") }
            MenuAction("Show in enclosing folder", Icons.Outlined.FolderOpen, entries.size == 1) {
                action("reveal")
            }
            CommandMenuAction(model, "copyTo", "Copy to…", Icons.Outlined.ContentCopy) { action("copyTo") }
            CommandMenuAction(model, "moveTo", "Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) { action("moveTo") }
            CommandMenuAction(model, "copy", "Copy", Icons.Outlined.ContentCopy) { action("copy") }
            CommandMenuAction(model, "cut", "Cut", Icons.Outlined.ContentCut) { action("cut") }
            CommandMenuAction(model, "batchRename", "Rename with rules…", Icons.Outlined.DriveFileRenameOutline) { action("batchRename") }
            CommandMenuAction(model, "duplicate", "Duplicate", Icons.Outlined.FileCopy) { action("duplicate") }
            CommandMenuAction(model, "duplicateVerified", "Duplicate and verify…", Icons.Outlined.FileCopy) { action("duplicateVerified") }
            CommandMenuAction(model, "compress", "Compress", Icons.Outlined.FolderZip) { action("compress") }
            if (entries.size == 1 && entries.first().extension == "zip")
                CommandMenuAction(model, "extract", "Extract archive", Icons.Outlined.Unarchive) { action("extract") }
            CommandMenuAction(model, "share", "Share…", Icons.Outlined.IosShare) { action("share") }
            if (entries.size == 1)
                CommandMenuAction(model, "copyPath", "Copy full path", Icons.Outlined.Link) { action("copyPath") }
            CommandMenuAction(model, "trash", "Move to Trash", Icons.Outlined.DeleteOutline) { action("trash") }
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
                        enabled = idle,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(
                            Modifier.size(15.dp)
                                .background(tagColor(model.tagColorName(tag)), CircleShape),
                        ) {
                            if (
                                entries.all {
                                    tag in model.tagAssignments[it.organizationPath].orEmpty()
                                }
                            )
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
        MenuSection("Organize and search")
        CommandMenuAction(model, "tags", "Tags…", Icons.AutoMirrored.Outlined.Label) { action("tags") }
        CommandMenuAction(model, "collections", "Collections…", Icons.Outlined.CollectionsBookmark) {
            action("collections")
        }
        MenuAction("Search filters…", Icons.Outlined.FilterAlt) { action("filters") }
        MenuAction("Search index…", Icons.Outlined.Storage) { action("searchIndex") }
        MenuAction("Recent searches…", Icons.Outlined.History) { action("recentSearches") }
        MenuAction("Search locations…", Icons.Outlined.FolderOpen) { action("searchLocations") }
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
        MenuSection("Storage and preferences")
        MenuAction(
            "Analyze storage…",
            Icons.Outlined.DataUsage,
            model.folderPath != null || entries.singleOrNull()?.folder == true,
        ) {
            action("analysis")
        }
        MenuAction("Available actions…", Icons.Outlined.Info) { action("availableActions") }
        MenuAction("Storage locations…", Icons.Outlined.Storage) { action("storage") }
        MenuAction("Trash & cache…", Icons.Outlined.CleaningServices) { action("housekeeping") }
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
private fun MenuSection(title: String) {
    Text(title, Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
        color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun MenuAction(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    unavailableReason: String? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(text, fontSize = 12.sp, color = if (unavailableReason != null) Muted else Ink)
                unavailableReason?.let { Text(it, fontSize = 11.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        },
        leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.semantics { unavailableReason?.let { stateDescription = "Unavailable: $it. Activate to see options." } },
    )
}

@Composable
private fun CommandMenuAction(model: FinderModel, command: String, text: String, icon: ImageVector, onClick: () -> Unit) {
    MenuAction(text, icon, unavailableReason = model.commandProblem(command)?.reason, onClick = onClick)
}
