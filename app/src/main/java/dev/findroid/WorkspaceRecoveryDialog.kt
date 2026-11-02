package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkspaceRecoveryDialog(model: FinderModel, connect: () -> Unit) {
    val state = model.workspaceRecovery
    val workspace = state.workspace ?: return
    val review = state.review
    var choosing by remember { mutableStateOf<String?>(null) }
    val idle = !state.working && !state.applying
    fun location(item: WorkspaceDestinationStatus): String = item.search?.let { "Smart Folder: ${it.name}\n${it.root}" }
        ?: if (item.destination.path.startsWith("@")) model.title(item.destination.path) else item.destination.path
    val scroll = rememberScrollState()
    LaunchedEffect(review, state.error) { scroll.scrollTo(0) }
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height.toDp() - 48.dp).coerceAtLeast(240.dp) }
    Dialog(state::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.applying, dismissOnClickOutside = !state.applying)) {
        Surface(Modifier.widthIn(max = 820.dp).fillMaxWidth(.94f).heightIn(max = height).testTag("workspace-recovery"), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (review == null) "Open saved workspace" else "Review workspace locations", style = MaterialTheme.typography.titleLarge)
                    Text(workspace.name, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.working || state.applying) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.applying) "Saving repaired locations…" else "Checking workspace folders…")
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workspace-recovery-error")) }
                    if (review != null) {
                        Text("${workspace.tabCount} tabs · ${if (workspace.dual) "Two panes" else "One pane"}", fontWeight = FontWeight.SemiBold)
                        Text("Working tabs, pane sizes, pinned tabs and search settings stay in place. Only the chosen unavailable destinations change.", fontSize = 13.sp)
                        review.replacements.forEach { (old, target) ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(state.statuses.first { it.destination.path == old }.destination.uses.joinToString(" · "), fontWeight = FontWeight.Medium)
                                Text("${location(state.statuses.first { it.destination.path == old })}\n→ $target", fontSize = 13.sp, modifier = Modifier.testTag("workspace-reviewed-mapping"))
                            }
                        }
                        if (review.replacements.isEmpty()) Text("The saved layout will open with its original locations.", fontSize = 13.sp)
                        if (review.originals.any { it.search != null && it.destination.path in review.replacements }) Text("Repaired saved-search tabs keep their filters on the chosen folder. The shared Smart Folder stays unchanged.", fontSize = 13.sp, color = Muted)
                        if (review.unresolved > 0) {
                            HorizontalDivider()
                            Text(if (review.unresolved == 1) "1 location is still unavailable" else "${review.unresolved} locations are still unavailable", fontWeight = FontWeight.SemiBold)
                            review.destinations.filter { it.problem != null }.forEach {
                                Text(location(it), fontSize = 13.sp)
                                Text(it.problem.orEmpty(), color = Muted, fontSize = 12.sp)
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workspace-keep-unavailable")
                                .toggleable(state.keepUnavailable, enabled = idle, role = Role.Checkbox, onValueChange = state::acknowledgeUnavailable),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(state.keepUnavailable, null, enabled = idle)
                                Text("Keep these locations unavailable for later repair", Modifier.weight(1f), fontSize = 14.sp)
                            }
                        }
                        Text("Open once leaves the saved workspace unchanged. Saving repairs updates this workspace only; your files, Favorites, tags and other layouts stay where they are.", fontSize = 13.sp, color = Muted)
                        if (model.workspaceError != null) Text("Repairs can't be saved: ${model.workspaceError}. You can still open this layout once.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        else if (model.busy != null && !state.applying) Text("Wait for the file operation before saving repairs. You can open this layout once.", fontSize = 13.sp, color = Muted)
                    } else if (state.statuses.isNotEmpty()) {
                        Text(if (state.missing.size == 1) "1 unavailable location" else "${state.missing.size} unavailable locations", fontWeight = FontWeight.SemiBold)
                        Text("Reconnect storage and check again, choose replacement folders, or review opening the layout with unavailable locations. Working tabs will stay intact.", fontSize = 13.sp)
                        state.missing.forEachIndexed { index, item ->
                            Column(Modifier.fillMaxWidth().testTag("workspace-missing-$index"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.destination.uses.joinToString(" · "), fontWeight = FontWeight.Medium)
                                Text(location(item), fontSize = 13.sp)
                                Text(item.problem.orEmpty(), color = Muted, fontSize = 12.sp)
                                state.replacements[item.destination.path]?.let { Text("Use: $it", fontSize = 13.sp, color = Blue) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ choosing = item.destination.path }, enabled = idle) { Text("Choose replacement folder…") }
                                    if (item.destination.path in state.replacements) TextButton({ state.replacement(item.destination.path, null) }, enabled = idle) { Text("Keep original") }
                                }
                            }
                            HorizontalDivider()
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(connect, enabled = idle) { Text("Connect storage folder…") }
                            TextButton(state::recheck, enabled = idle) { Text("Check locations again") }
                        }
                    }
                }
                HorizontalDivider()
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (review != null) {
                        TextButton(state::edit, enabled = idle) { Text("Edit choices") }
                        if (review.replacements.isNotEmpty()) {
                            Button({ state.apply(true) }, enabled = idle && model.workspaceError == null && !model.workspaceSaving && model.busy == null && (review.unresolved == 0 || state.keepUnavailable), modifier = Modifier.testTag("workspace-save-repairs")) { Text("Save repairs and open") }
                            OutlinedButton({ state.apply(false) }, enabled = idle && (review.unresolved == 0 || state.keepUnavailable), modifier = Modifier.testTag("workspace-open-once")) { Text("Open once") }
                        } else Button({ state.apply(false) }, enabled = idle && (review.unresolved == 0 || state.keepUnavailable), modifier = Modifier.testTag("workspace-open-once")) { Text("Open workspace") }
                    } else if (state.statuses.isNotEmpty()) Button(state::prepareReview, enabled = idle, modifier = Modifier.testTag("workspace-review-repairs")) { Text("Review locations") }
                    TextButton(state::close, enabled = !state.applying) { Text("Cancel") }
                }
            }
        }
    }
    choosing?.let { old ->
        DestinationDialog(model, move = false, titleOverride = "Replacement workspace folder", confirmLabel = "Use this folder", requireWritable = false,
            onDestination = { state.replacement(old, it); choosing = null }, dismiss = { choosing = null })
    }
}
