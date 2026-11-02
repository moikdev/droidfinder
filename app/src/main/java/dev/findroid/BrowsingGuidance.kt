package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FileGestureHint(model: FinderModel) {
    Row(Modifier.fillMaxWidth().background(Sidebar).padding(start = 12.dp, end = 4.dp).testTag("file-gesture-hint"),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Hold a file's name to select. Hold its icon to drag. With a mouse, drag the icon directly.",
            Modifier.weight(1f).padding(vertical = 8.dp), color = Muted, fontSize = 12.sp)
        TextButton(onClick = { model.showGestureHint(false) }) { Text("Got it") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionScopeStrip(model: FinderModel) {
    val pane = if (!model.dualPane) "" else if ((model.paneId == "primary") != model.panesReversed) "Left pane · " else "Right pane · "
    BoxWithConstraints(Modifier.fillMaxWidth().background(SelectionSurface)) {
        val wide = maxWidth / androidx.compose.ui.platform.LocalDensity.current.fontScale >= 380.dp
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)
                    .testTag("selection-scope-${model.paneId}")
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text("$pane${model.selected.size} selected", color = SelectedInk, fontSize = 13.sp)
                    Text(model.displayPath(model.location), color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = model::clearSelection, modifier = Modifier.semantics { contentDescription = "Clear selection in ${pane.ifBlank { "this folder" }}" }) { Text("Clear") }
            }
            FlowRow(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.paneActive && !model.isTrash && model.actionEntries.isNotEmpty()) {
                    DragSelectionHandle(model)
                    if (wide) ToolbarAction("Copy", { model.copySelection(false) })
                }
                ToolbarAction("Selection actions", {
                    model.activatePane()
                    model.selectedEntries.firstOrNull()?.let { model.requestContext(it) }
                        ?: run { model.message = "The selected items are unavailable. Refresh this location and select them again." }
                }, modifier = Modifier.testTag("selection-actions-${model.paneId}"))
            }
        }
    }
}
