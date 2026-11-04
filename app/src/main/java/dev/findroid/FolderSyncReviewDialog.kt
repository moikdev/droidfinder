package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderSyncReviewDialog(model: FinderModel) {
    val owner = model.primaryPane
    val state = owner.folderSyncReview?.takeIf { it.visible } ?: return
    val preview = state.inspection?.preview
    val selected = preview?.entries.orEmpty().filter { it.relative in state.selected }
    val updates = selected.count { it.category == FolderSyncCategory.UPDATE }
    val trash = selected.count { it.category == FolderSyncCategory.DELETION }
    val source = preview?.sourceRoot ?: if (state.direction == FolderSyncDirection.PRIMARY_TO_SECONDARY) state.left else state.right
    val destination = preview?.destinationRoot ?: if (state.direction == FolderSyncDirection.PRIMARY_TO_SECONDARY) state.right else state.left
    val density = LocalDensity.current
    Dialog(owner::closeFolderSyncReview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            Surface(Modifier.widthIn(max = 960.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("folder-sync-review"),
                shape = MaterialTheme.shapes.large, color = CanvasBackground, shadowElevation = 16.dp) {
                CompositionLocalProvider(LocalDensity provides density) {
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("folder-sync-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(when {
                            state.applying -> "Synchronizing folders"
                            state.finished && state.error == null -> "Synchronization finished"
                            state.finished -> "Synchronization stopped"
                            state.confirming -> "Confirm selected changes"
                            else -> "Review folder synchronization"
                        }, Modifier.padding(top = 20.dp), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                    item {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Source\n$source", color = Ink, fontSize = 13.sp, modifier = Modifier.testTag("sync-source"))
                                Text("Destination\n$destination", color = Ink, fontSize = 13.sp, modifier = Modifier.testTag("sync-destination"))
                            }
                        }
                    }
                    if (!state.applying && !state.finished && !state.confirming) item {
                        Text("Choose one direction. File contents are checked, including hidden files. Dates do not choose which copy wins.", color = Muted, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = state.direction == FolderSyncDirection.PRIMARY_TO_SECONDARY,
                                onClick = { owner.reviewFolderSynchronization(state.left, state.right, FolderSyncDirection.PRIMARY_TO_SECONDARY) },
                                label = { Text("Left → right") }, modifier = Modifier.heightIn(min = 48.dp).testTag("sync-left-to-right"))
                            FilterChip(selected = state.direction == FolderSyncDirection.SECONDARY_TO_PRIMARY,
                                onClick = { owner.reviewFolderSynchronization(state.left, state.right, FolderSyncDirection.SECONDARY_TO_PRIMARY) },
                                label = { Text("Right → left") }, modifier = Modifier.heightIn(min = 48.dp).testTag("sync-right-to-left"))
                        }
                    }
                    if (state.checking || state.applying) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.checking) state.progress.phase.ifBlank { "Checking complete folders and file contents…" }
                            else owner.transferProgress.phase.ifBlank { "Preparing reviewed working copies…" }, color = Ink, fontSize = 13.sp)
                        if (state.applying) Text("Closing this screen keeps the operation running. Stopping keeps completed files, original copies in Trash, and working records for review.", color = Muted, fontSize = 13.sp)
                    }
                    state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("sync-error")) } }
                    if (state.finished) {
                        item {
                            val applied = state.applied
                            Text(if (applied == null) "No completed changes were verified by this run. Review its folders and records before trying again."
                                else "${applied.completed.size} complete changes · ${applied.publishedRoots.size} published items · ${applied.preservedOriginals.size} originals preserved in Trash",
                                color = Ink, fontSize = 14.sp, modifier = Modifier.testTag("sync-result"))
                            if (state.error == null) Text("Use Recent actions to undo a completed run. Runs containing replacements or destination-only Trash moves keep Undo for this session. Original destination items remain in Trash.", color = Muted, fontSize = 13.sp)
                            else Text("No automatic retry, rollback or cleanup will run. Review the destination and Trash before starting a fresh comparison.", color = Muted, fontSize = 13.sp)
                        }
                        item {
                            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.workingDirectory?.let { Text("Working files and reviewed plan\n$it", color = Muted, fontSize = 12.sp) }
                                state.journalDirectory?.let { Text("Move records (when created)\n$it", color = Muted, fontSize = 12.sp) }
                                state.applied?.preservation?.let { Text("Pending original move\n${it.original}\n→ ${it.recovery}", color = Ink, fontSize = 13.sp) }
                                state.applied?.publication?.let { Text("Pending publication\n${it.working}\n→ ${it.destination}", color = Ink, fontSize = 13.sp) }
                            } }
                        }
                    } else if (!state.checking && !state.applying && preview != null) {
                        item {
                            Text("Changes selected: ${selected.size} · New: ${selected.count { it.category == FolderSyncCategory.ADDITION }} · Replace: $updates · Trash: $trash",
                                color = Ink, fontSize = 14.sp, modifier = Modifier.testTag("sync-selection-count"))
                            Text(if (state.confirming) "Only the selected changes below will be applied. Newly added folders include every disclosed child."
                                else "Additions are selected initially. Existing destination files and destination-only items stay in place unless you select them.", color = Muted, fontSize = 13.sp)
                            preview.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        }
                        for (category in FolderSyncCategory.entries) {
                            val rows = preview.entries.filter { it.category == category && (!state.confirming || it.relative in state.selected || it.coveredBy in state.selected) }
                            if (rows.isNotEmpty()) {
                                item(key = "heading-$category") {
                                    Text(when (category) {
                                        FolderSyncCategory.ADDITION -> "New at the source"
                                        FolderSyncCategory.UPDATE -> "Different contents — choose replacements"
                                        FolderSyncCategory.CONFLICT -> "Needs manual attention"
                                        FolderSyncCategory.DELETION -> "Only at the destination — keep or move to Trash"
                                        FolderSyncCategory.UNCHANGED -> "Same contents or compared folders"
                                    }, color = Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                }
                                items(rows, key = { "entry-" + it.relative }) { entry ->
                                    val chosen = entry.relative in state.selected || entry.coveredBy in state.selected
                                    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    val interactive = entry.selectable && !state.confirming && preview.executable
                                    Row(if (interactive) modifier.toggleable(chosen, role = Role.Checkbox,
                                        onValueChange = { owner.selectFolderSyncItem(entry.relative, it) }).testTag("sync-item:" + entry.relative)
                                        else modifier, verticalAlignment = Alignment.CenterVertically) {
                                        if (entry.selectable) Checkbox(chosen, onCheckedChange = null)
                                        Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 8.dp)) {
                                            Text(entry.relative, color = Ink, fontSize = 14.sp)
                                            Text(entry.coveredBy?.let { "Included with $it" } ?: entry.explanation, color = Muted, fontSize = 12.sp)
                                            if (entry.coveredBy == null && entry.descendantCount > 0) Text("Includes ${entry.descendantCount} disclosed descendants below", color = Muted, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                        if (state.confirming && updates > 0) item {
                            SyncConfirmation(state.updatesConfirmed, "Replace $updates destination ${if (updates == 1) "item" else "items"}; preserve the original copies in Trash first.", "sync-confirm-updates", owner::confirmFolderSyncUpdates)
                            Text("Original preservation needs the destination and Findroid's Trash on the same filesystem. If that cannot be verified, the run stops before moving an original.", color = Muted, fontSize = 12.sp)
                        }
                        if (state.confirming && trash > 0) item {
                            SyncConfirmation(state.trashConfirmed, "Move $trash destination-only ${if (trash == 1) "item" else "items"} to Trash. Keep the original copies there.", "sync-confirm-trash", owner::confirmFolderSyncTrash)
                        }
                        if (state.confirming) item {
                            if (updates > 0 || trash > 0) Text("Undo for this run lasts for this app session. Preserved originals remain in Trash after restarting. Original preservation requires the destination and Findroid's Trash on the same filesystem.", color = Muted, fontSize = 12.sp)
                            Text("Working copies are saved beside the destination before originals move. Files or access changed since review will stop the run. Interrupted working data and records remain for manual review.", color = Muted, fontSize = 13.sp)
                            if (owner.session.historyRecoveryProblem.value != null)
                                Text("Review saved Undo in Recent actions before applying changes.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }
                    item {
                        FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.checking || state.applying) TextButton(owner::stopFolderSynchronization,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("sync-stop")) { Text(if (state.checking) "Stop review" else "Stop synchronization") }
                            if (!state.checking && !state.applying && !state.finished && preview != null) {
                                if (state.confirming) {
                                    Button(owner::applyFolderSynchronization,
                                        enabled = selected.isNotEmpty() && (updates == 0 || state.updatesConfirmed) && (trash == 0 || state.trashConfirmed) &&
                                            owner.busy == null && owner.session.historyRecoveryProblem.value == null,
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("sync-apply")) { Text("Apply changes (${selected.size})") }
                                    TextButton({ owner.confirmFolderSyncReview(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to choices") }
                                } else Button({ owner.confirmFolderSyncReview(true) }, enabled = selected.isNotEmpty() && preview.executable && owner.busy == null,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("sync-review-choices")) { Text("Review changes (${selected.size})") }
                            }
                            if (!state.checking && !state.applying) TextButton({ owner.reviewFolderSynchronization(state.left, state.right, state.direction) },
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Compare again") }
                            TextButton(owner::showFolderSyncRecords, modifier = Modifier.heightIn(min = 48.dp)) { Text("Synchronization records") }
                            if (state.finished) {
                                TextButton({ owner.closeFolderSyncReview(); if (owner.allowedPath(destination)) owner.navigate(destination) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open destination") }
                                if (state.applied?.preservedOriginals?.isNotEmpty() == true) TextButton({ owner.closeFolderSyncReview(); owner.navigate("@trash") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Trash") }
                            }
                            TextButton(owner::closeFolderSyncReview, modifier = Modifier.heightIn(min = 48.dp).testTag("sync-close")) { Text(if (state.applying) "Continue in background" else "Close") }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SyncConfirmation(checked: Boolean, label: String, tag: String, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChecked).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.weight(1f).padding(8.dp), color = Ink, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderSyncRecordsDialog(model: FinderModel) {
    val owner = model.primaryPane
    val state = owner.folderSyncRecords ?: return
    val density = LocalDensity.current
    Dialog(owner::closeFolderSyncRecords, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("folder-sync-records"),
                shape = MaterialTheme.shapes.large, color = CanvasBackground, shadowElevation = 16.dp) {
                CompositionLocalProvider(LocalDensity provides density) {
                LazyColumn(Modifier.padding(horizontal = 20.dp).testTag("folder-sync-records-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("Synchronization records", Modifier.padding(top = 20.dp), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Text("Each record was saved before preparing its run. It does not prove that every move finished. Review the destination, Trash and the recorded working locations. Opening this screen never retries, restores or removes files.", color = Muted, fontSize = 13.sp)
                    }
                    if (state.checking) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
                    if (!state.checking && state.records.isEmpty() && state.error == null) item { Text("No synchronization runs have been recorded yet.", color = Muted) }
                    items(state.records, key = { it.id }) { record ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(color = Border)
                            Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.createdAt))} · ${record.operations} reviewed changes", color = Ink, fontSize = 14.sp)
                            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Source\n${record.source}\nDestination\n${record.destination}", color = Ink, fontSize = 13.sp)
                                Text("Working files (when created)\n${record.working}\nMove records (when created)\n${record.journal}", color = Muted, fontSize = 12.sp)
                            } }
                            TextButton({ if (owner.allowedPath(record.destination)) { owner.closeFolderSyncRecords(); owner.navigate(record.destination) }
                                else owner.message = "Reconnect the destination before opening it." }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open destination folder") }
                        }
                    }
                    item {
                        FlowRow(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ owner.closeFolderSyncRecords(); owner.navigate("@trash") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Trash") }
                            TextButton(owner::showFolderSyncRecords, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check records again") }
                            TextButton(owner::closeFolderSyncRecords, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                        }
                    }
                }
                }
            }
        }
    }
}
