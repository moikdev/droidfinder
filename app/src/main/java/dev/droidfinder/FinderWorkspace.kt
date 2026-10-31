@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One shared toolbar/operation session, independently navigable browser surfaces. */
@Composable
fun FinderWorkspace(
    model: FinderModel,
    onOpen: (FinderModel, Entry) -> Unit,
    showQuickLook: (Entry) -> Unit,
    onOpenWith: (Entry) -> Unit,
    onGrantAccess: () -> Unit,
    onNew: (FinderModel) -> Unit,
    onGoTo: (FinderModel) -> Unit,
    focusFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val workspaceWidth = maxWidth.value
        val sideBySide = model.dualPane && maxWidth >= 800.dp
        val minimumPane = if (model.density.touch) 360f else 320f
        val paneSpace = (workspaceWidth - 6f).coerceAtLeast(1f)
        val minimumRatio = (minimumPane / paneSpace).coerceAtMost(.5f)
        val ratio = model.paneRatio.coerceIn(minimumRatio, 1f - minimumRatio)
        val primary = model.primaryPane
        val secondary = if (model.dualPane) model.secondaryPane else null
        val panes =
            if (!model.dualPane) listOf(model)
            else if (model.panesReversed) listOf(secondary!!, primary)
            else listOf(primary, secondary!!)
        Column(Modifier.fillMaxSize()) {
            if (model.dualPane && !sideBySide) {
                Row(Modifier.fillMaxWidth().background(Sidebar)) {
                    panes.forEachIndexed { index, pane ->
                        TextButton(
                            onClick = {
                                pane.activatePane()
                                focusFiles()
                            },
                            modifier =
                                Modifier.weight(1f)
                                    .testTag("pane-switch-${pane.paneId}")
                                    .semantics { selected = pane.paneActive },
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = if (pane.paneActive) Blue else Muted,
                                ),
                        ) {
                            Text(
                                "${index + 1}  ${pane.title()}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val visible = if (sideBySide) panes else listOf(model.activePaneModel)
                visible.forEachIndexed { index, pane ->
                    key(pane.paneId) {
                        Column(
                            Modifier.weight(
                                    if (sideBySide) if (index == 0) ratio else 1f - ratio else 1f,
                                )
                                .fillMaxHeight()
                                .testTag("pane-${pane.paneId}")
                                .pointerInput(pane) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (event.type == PointerEventType.Press)
                                                pane.activatePane()
                                            if (
                                                event.type == PointerEventType.Scroll &&
                                                    event.keyboardModifiers.isCtrlPressed
                                            ) {
                                                val amount =
                                                    event.changes.sumOf {
                                                        it.scrollDelta.y.toDouble()
                                                    }
                                                if (amount != 0.0) {
                                                    pane.activatePane()
                                                    if (pane.mode != ViewMode.ICONS)
                                                        pane.changeMode(ViewMode.ICONS)
                                                    pane.changeIconSize(
                                                        pane.iconSize + if (amount < 0) 20 else -20,
                                                    )
                                                    event.changes.forEach { it.consume() }
                                                }
                                            }
                                            // File handlers observe button-down at Initial before
                                            // keyboard focus returns to the browser at Main.
                                            val main = awaitPointerEvent(PointerEventPass.Main)
                                            if (main.type == PointerEventType.Press) focusFiles()
                                        }
                                    }
                                },
                        ) {
                            if (model.dualPane && sideBySide) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .height(38.dp)
                                        .background(
                                            if (pane.paneActive) SelectionSurface else Sidebar,
                                        )
                                        .clickable {
                                            pane.activatePane()
                                            focusFiles()
                                        }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (index == 0) "LEFT" else "RIGHT",
                                        color = if (pane.paneActive) Blue else Muted,
                                        fontSize = 10.sp,
                                    )
                                    Text(
                                        pane.displayPath(pane.location),
                                        Modifier.weight(1f).padding(start = 10.dp),
                                        color = Muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            DesktopTabStrip(pane)
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                PaneFiles(
                                    pane,
                                    { onOpen(pane, it) },
                                    showQuickLook,
                                    onOpenWith,
                                    onGrantAccess,
                                    { onNew(pane) },
                                )
                            }
                            if (model.dualPane) PathBar(pane) { onGoTo(pane) }
                        }
                    }
                    if (sideBySide && index == 0) {
                        val width = workspaceWidth
                        ResizeHandle(
                            "Resize panes",
                            { delta -> model.changePaneRatio(ratio + delta / (width - 6f)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.PaneFiles(
    model: FinderModel,
    open: (Entry) -> Unit,
    showQuickLook: (Entry) -> Unit,
    onOpenWith: (Entry) -> Unit,
    onGrantAccess: () -> Unit,
    onNew: () -> Unit,
) {
    val paneWidth = maxWidth
    val paneHeight = maxHeight
    LaunchedEffect(paneWidth, paneHeight) {
        model.browserWidthDp = paneWidth.value
        model.browserHeightDp = paneHeight.value
    }
    Box(
        Modifier.fillMaxSize().fillMaxWidth().background(CanvasBackground),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .testTag("file-browser")
                    .folderContextMenu(model)
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
                            WelcomeState(onGrantAccess, onNew)
                        else
                            EmptyState(
                                if (
                                    (model.query.isNotEmpty() ||
                                        model.effectiveSearchRules.active ||
                                        model.currentSmartFolder != null)
                                )
                                    Icons.Outlined.SearchOff
                                else if (model.isTrash) Icons.Outlined.DeleteOutline
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
                                else if (model.isTrash) "Items you move to Trash will appear here."
                                else "Create a folder or paste files to get started.",
                                if (model.canCreate && model.busy == null && model.query.isEmpty())
                                    "New folder"
                                else null,
                                onNew,
                            )
                    }
                    model.mode == ViewMode.ICONS -> IconView(model, open)
                    model.mode == ViewMode.LIST && model.windowsLayout == 8 ->
                        ContentListView(model, open)
                    model.mode == ViewMode.LIST ->
                        ListView(model, open) { entry ->
                            model.requestContext(entry)
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
            if (model.previewVisible && model.mode != ViewMode.GALLERY && paneWidth >= 900.dp) {
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
                        minOf(model.previewWidth, (paneWidth.value * .3f).toInt()).dp,
                    ),
                )
            }
        }
        if (model.previewVisible && model.inspectorRequested && paneWidth < 900.dp) {
            Surface(
                Modifier.align(Alignment.CenterEnd)
                    .width(minOf(320.dp, paneWidth * .8f))
                    .fillMaxHeight(),
                color = SubtleSurface,
                shadowElevation = 12.dp,
            ) {
                PreviewPane(model, showQuickLook, onOpenWith, Modifier.fillMaxSize())
            }
        }
    }
}
