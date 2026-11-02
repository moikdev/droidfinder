package dev.findroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecentActionsDialog(model: FinderModel, action: (String) -> Unit, dismiss: () -> Unit) {
    val actions = model.recentActions
    var expanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height.toDp() - 48.dp).coerceAtLeast(240.dp) }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 820.dp).fillMaxWidth(.94f).heightIn(max = height).testTag("recent-actions"), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent actions", style = MaterialTheme.typography.titleLarge)
                    Text("Undo or redo in order. Findroid checks the affected files and stops if they have changed.", fontSize = 13.sp)
                    Text("This session only · Up to 50 actions · Shared across panes and windows", color = Muted, fontSize = 12.sp)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("recent-actions-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    model.historyFailure?.let { failure -> item(key = "failure") {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.testTag("recent-action-failure")) {
                            Text("The last Undo or Redo stopped", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            Text(failure, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            Text("Review the changed files before trying again. Findroid will check them again; it will not overwrite later edits.", fontSize = 12.sp)
                        }
                    } }
                    model.busy?.let { busy -> item(key = "busy") {
                        Column {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Wait for the current operation: $busy", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                            TextButton({ action("transfers") }) { Text("View transfers") }
                        }
                    } }
                    if (actions.isEmpty()) item(key = "empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No recent actions in this session", fontWeight = FontWeight.Medium)
                            Text("New folders, copies, moves, renames and organization changes appear here when safe Undo is available. The list resets when the app process ends.", color = Muted, fontSize = 13.sp)
                        }
                    }
                    items(actions, key = { it.id }) { entry ->
                        Surface(color = if (entry.nextUndo || entry.nextRedo) SelectionSurface else CanvasBackground,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().testTag("recent-action-${entry.id}")
                                .clickable { expanded = if (entry.id in expanded) expanded - entry.id else expanded + entry.id }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(entry.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    Text(time.format(Date(entry.recordedAt)), color = Muted, fontSize = 12.sp)
                                }
                                Text(entry.state.title + when { entry.nextUndo -> " · Next to undo"; entry.nextRedo -> " · Next to redo"; else -> "" },
                                    color = if (entry.nextUndo || entry.nextRedo) SelectedInk else Muted, fontSize = 12.sp)
                                if (entry.detail.isNotBlank()) Text(entry.detail, fontSize = 12.sp,
                                    maxLines = if (entry.id in expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                HorizontalDivider()
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ model.undo() }, enabled = model.undoLabel != null && model.busy == null, modifier = Modifier.testTag("recent-undo")) { Text(model.undoLabel?.let { "Undo $it" } ?: "Nothing to undo") }
                    OutlinedButton({ model.redo() }, enabled = model.redoLabel != null && model.busy == null, modifier = Modifier.testTag("recent-redo")) { Text(model.redoLabel?.let { "Redo $it" } ?: "Nothing to redo") }
                    TextButton(dismiss) { Text("Close") }
                }
            }
        }
    }
}
