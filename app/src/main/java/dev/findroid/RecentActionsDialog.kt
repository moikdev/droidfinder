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
    var resetting by remember { mutableStateOf(false) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height.toDp() - 48.dp).coerceAtLeast(160.dp) }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 820.dp).fillMaxWidth(.94f).heightIn(max = height).testTag("recent-actions"), shape = MaterialTheme.shapes.extraLarge) {
            LazyColumn(Modifier.fillMaxWidth().testTag("recent-actions-list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (resetting) "Reset Undo list?" else "Recent actions", style = MaterialTheme.typography.titleLarge)
                        Text(if (resetting) "This removes the current Undo and Redo references. Your files and everything in Trash stay in place. The prior saved record is preserved separately in app storage."
                            else "Undo or redo in order. Findroid checks the affected files and stops if they have changed.", fontSize = 13.sp)
                        if (!resetting) Text("Supported local actions survive restart · Up to 50 actions · Shared across panes and windows", color = Muted, fontSize = 12.sp)
                    }
                }
                if (resetting) {
                    item(key = "reset-controls") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ resetting = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep Undo list") }
                            Button({ resetting = false; model.resetSavedUndo() }, enabled = model.busy == null,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-reset-undo")) { Text("Reset Undo list") }
                        }
                    }
                } else {
                    model.historyRecoveryProblem?.let { problem -> item(key = "recovery") {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.testTag("saved-undo-review")) {
                            Text("Saved Undo needs review", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            Text(problem, fontSize = 13.sp)
                            Text("The list may show the state before the stopped operation. Check the affected folders and Trash. Undo and Redo stay unavailable until you reset these references; the reset keeps your documents and preserves the old record.", fontSize = 12.sp)
                        }
                    } }
                    model.historyFailure?.let { failure -> item(key = "failure") {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.testTag("recent-action-failure")) {
                            Text("The last Undo or Redo stopped", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            Text(failure, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            Text("Review the changed files before trying again. Findroid checks them again and keeps occupied destinations.", fontSize = 12.sp)
                        }
                    } }
                    model.historyPersistenceNotice?.let { notice -> item(key = "notice") { Text(notice, color = Muted, fontSize = 12.sp) } }
                    model.busy?.let { busy -> item(key = "busy") {
                        Column {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Wait for the current operation: $busy", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                            TextButton({ action("transfers") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View transfers") }
                        }
                    } }
                    if (actions.isEmpty()) item(key = "empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No recent actions", fontWeight = FontWeight.Medium)
                            Text("New folders, copies, moves, renames and Trash actions appear here when safe Undo is available. Nothing runs automatically when you reopen the app.", color = Muted, fontSize = 13.sp)
                        }
                    }
                    items(actions, key = { it.id }) { entry ->
                        Surface(color = if (entry.nextUndo || entry.nextRedo) SelectionSurface else CanvasBackground,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recent-action-${entry.id}")
                                .clickable { expanded = if (entry.id in expanded) expanded - entry.id else expanded + entry.id }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.label, fontWeight = FontWeight.SemiBold)
                                Text(time.format(Date(entry.recordedAt)), color = Muted, fontSize = 12.sp)
                                Text(entry.state.title + when {
                                    entry.nextUndo -> " · Next to undo"; entry.nextRedo -> " · Next to redo"; else -> ""
                                }, color = if (entry.nextUndo || entry.nextRedo) SelectedInk else Muted, fontSize = 12.sp)
                                Text(when {
                                    model.historyRecoveryProblem != null -> "Saved state needs review"
                                    entry.savedForRestart -> "Saved for restart"
                                    else -> "This session only"
                                }, color = Muted, fontSize = 12.sp)
                                if (entry.detail.isNotBlank()) Text(entry.detail, fontSize = 12.sp,
                                    maxLines = if (entry.id in expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    item(key = "scope") {
                        Text("Provider, replacement and organization actions stay in this session. The next continuous local actions on each side are saved. An action hidden behind a session-only boundary stays in this session even if that boundary action is undone. Unsupported storage, links and very large histories also stay in this session.", color = Muted, fontSize = 12.sp)
                    }
                    item(key = "controls") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button({ model.undo() }, enabled = model.undoLabel != null && model.busy == null,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("recent-undo")) { Text(model.undoLabel?.let { "Undo $it" } ?: "Nothing to undo") }
                            OutlinedButton({ model.redo() }, enabled = model.redoLabel != null && model.busy == null,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("recent-redo")) { Text(model.redoLabel?.let { "Redo $it" } ?: "Nothing to redo") }
                            if (actions.isNotEmpty() || model.historyRecoveryProblem != null) TextButton({ resetting = true }, enabled = model.busy == null,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("reset-undo")) { Text("Reset Undo list…") }
                        }
                    }
                }
                item(key = "close") { TextButton(dismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("close-recent-actions")) { Text("Close") } }
            }
        }
    }
}
