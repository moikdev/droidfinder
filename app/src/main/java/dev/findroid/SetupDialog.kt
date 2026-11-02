package dev.findroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetupDialog(model: FinderModel) {
    val state = model.setup
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(state::export) }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(state::load) }
    var choosingRoot by remember { mutableStateOf<String?>(null) }
    val review = state.review
    val source = state.archive
    val export = state.exportArchive
    val idle = !state.working && !state.applying
    val scroll = rememberScrollState()
    val page = when { review != null -> "review"; source != null -> "import"; export != null -> "export"; else -> "home" }
    LaunchedEffect(page, state.error) { scroll.scrollTo(0) }
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height.toDp() - 48.dp).coerceAtLeast(240.dp) }
    Dialog(onDismissRequest = state::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.applying, dismissOnClickOutside = !state.applying)) {
        Surface(Modifier.widthIn(max = 820.dp).fillMaxWidth(.94f).heightIn(max = height).testTag("setup-dialog"), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                Text(if (review != null) "Review setup restore" else "Back up and restore setup", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.working || state.applying) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.applying) "Restoring setup… Keep Findroid open until this finishes." else state.progress)
                    }
                    state.error?.let { Text(it, Modifier.testTag("setup-error"), color = MaterialTheme.colorScheme.error) }
                    state.notice?.let { Text(it, Modifier.testTag("setup-notice")) }
                    if (review != null) {
                        Text(if (state.replace) "Replace selected sections" else "Merge with current setup", fontWeight = FontWeight.SemiBold)
                        Text("${review.merged.changedSections.size} sections change · ${review.resolution.changed} saved locations remap", fontSize = 13.sp)
                        Text("A recovery copy of your current setup will be saved before applying. Files stay in their folders. Restoring setup resets session Undo history.", fontSize = 13.sp, color = Muted)
                        state.sections.forEach { section ->
                            val changed = section in review.merged.changedSections
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(section.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(setupReviewDetail(section, review.before, review.merged.value, source?.snapshot, changed), fontSize = 12.sp, color = Muted)
                            }
                        }
                        source?.roots?.forEach { root ->
                            val target = state.targets[root.id]
                            if (target != null && target != root.source) {
                                Text(root.name, fontWeight = FontWeight.Medium)
                                Text("${root.source}\n→ $target", fontSize = 12.sp, modifier = Modifier.testTag("setup-mapping-${root.id}"))
                            }
                        }
                        if (review.merged.notes.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Merges and names", fontWeight = FontWeight.SemiBold)
                            review.merged.notes.take(40).forEach { Text(it, fontSize = 13.sp) }
                            if (review.merged.notes.size > 40) Text("${review.merged.notes.size - 40} more references use the same merge rules.", fontSize = 12.sp, color = Muted)
                        }
                        if (review.resolution.issueCount > 0) {
                            HorizontalDivider()
                            Text("${review.resolution.issueCount} unavailable references", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("setup-unavailable"))
                            Text("They can stay saved for later repair. Reconnect storage or edit the root mappings to resolve them now. No missing folder is created.", fontSize = 13.sp)
                            review.resolution.issues.take(12).forEach { issue ->
                                Column(Modifier.fillMaxWidth().background(AppBackground, MaterialTheme.shapes.small).padding(12.dp)) {
                                    Text(issue.target, fontSize = 12.sp)
                                    Text(issue.reason, fontSize = 12.sp, color = Muted)
                                }
                            }
                            if (review.resolution.issueCount > 12) Text("${review.resolution.issueCount - 12} further unavailable references are also kept for repair.", fontSize = 12.sp, color = Muted)
                            SetupCheckbox("Keep unavailable references for later repair", state.keepUnavailable, idle, "setup-keep-unavailable", state::acknowledgeUnavailable)
                        }
                        if (review.merged.changedSections.isEmpty()) Text("No changes are needed with these choices. You can edit the review to choose other sections or replacement behavior.")
                    } else if (source != null) {
                        Text("Backup from ${source.sourceVersion} · ${setupDate(source.created)}", fontWeight = FontWeight.SemiBold)
                        Text("Choose the sections to restore. Open tabs become a named workspace so you can open them after reviewing unavailable folders.", fontSize = 13.sp, color = Muted)
                        SetupSection.entries.forEach { section -> SetupCheckbox("${section.title} · ${setupSummary(section, source.snapshot)}", section in state.sections, idle, "setup-section-${section.name}") { state.section(section, it) } }
                        HorizontalDivider()
                        Text("Existing setup", fontWeight = FontWeight.SemiBold)
                        SetupRadio("Merge · keep existing labels, colors and preferences", !state.replace, idle, "setup-merge") { state.mode(false) }
                        SetupRadio("Replace selected sections with the backup", state.replace, idle, "setup-replace") { state.mode(true) }
                        if (state.replace) Text("Existing entries in the selected sections will be replaced. Other sections keep their current values. Review the result before applying.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        HorizontalDivider()
                        Text("Storage folders", fontWeight = FontWeight.SemiBold)
                        Text("Choose where each old storage folder lives on this tablet. References keep their relative names. Connect storage and sign in to providers on this tablet before choosing their folders.", fontSize = 13.sp, color = Muted)
                        source.roots.forEach { root ->
                            Column(Modifier.fillMaxWidth().background(AppBackground, MaterialTheme.shapes.medium).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(root.name, fontWeight = FontWeight.Medium)
                                Text("From: ${root.source}", fontSize = 12.sp)
                                Text("To: ${state.targets[root.id] ?: "Keep original references"}", fontSize = 12.sp)
                                Text(state.rootStatus[root.id].orEmpty(), fontSize = 12.sp, color = Muted)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton({ choosingRoot = root.id }, enabled = idle, modifier = Modifier.testTag("setup-root-${root.id}")) { Text("Choose replacement folder…") }
                                    TextButton({ state.target(root.id, null) }, enabled = idle && state.targets[root.id] != null) { Text("Keep original") }
                                }
                            }
                        }
                    } else if (export != null) {
                        Text("Ready to save · ${setupDate(export.created)}", fontWeight = FontWeight.SemiBold)
                        SetupSection.entries.forEach { Text("${it.title}: ${setupSummary(it, export.snapshot)}", fontSize = 13.sp) }
                        Text("${export.references.size} saved references · ${export.roots.size} storage roots", fontSize = 13.sp)
                        Text("The JSON backup contains folder paths, names, tags, search rules and preferences. Save it somewhere you can access on your other tablet.", fontSize = 13.sp, color = Muted)
                    } else {
                        Text("Keep your Finder setup when you move to another tablet.", style = MaterialTheme.typography.titleMedium)
                        Text("Save your Favorites, tags, collections, saved searches, and workspaces. The backup also includes open tabs, folder appearance, rename presets, and browsing preferences.")
                        Text("Back up your files separately. Connect storage folders and sign in to providers again on the new tablet.", fontSize = 13.sp, color = Muted)
                        Text("Restore lets you choose sections, resolve name conflicts, and review folder locations before applying. A recovery copy keeps your previous setup available.", fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(state::prepareExport, enabled = idle, modifier = Modifier.testTag("setup-export")) { Text("Create setup backup") }
                            OutlinedButton({ load.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = idle, modifier = Modifier.testTag("setup-import")) { Text("Choose backup to restore…") }
                        }
                        if (state.hasPrevious) TextButton(state::loadPrevious, enabled = idle, modifier = Modifier.testTag("setup-previous")) { Text("Review previous setup for recovery…") }
                        if (state.restoredTabsId != null) TextButton(state::openRestoredTabs, enabled = idle) { Text("Open restored tabs workspace") }
                    }
                }
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (review != null) {
                        TextButton(state::editReview, enabled = idle) { Text("Edit choices") }
                        Button(state::applyReview, enabled = idle && review.merged.changedSections.isNotEmpty() && (review.resolution.issueCount == 0 || state.keepUnavailable), modifier = Modifier.testTag("setup-apply")) { Text("Restore setup") }
                    } else if (source != null) {
                        TextButton(state::home, enabled = idle) { Text("Back") }
                        Button(state::prepareReview, enabled = idle && state.sections.isNotEmpty(), modifier = Modifier.testTag("setup-review")) { Text("Review changes") }
                    } else if (export != null) {
                        TextButton(state::home, enabled = idle) { Text("Back") }
                        Button({ save.launch("Findroid-setup-${export.created.substringBefore('T')}.json") }, enabled = idle, modifier = Modifier.testTag("setup-save")) { Text("Save backup…") }
                    }
                    if (state.working) TextButton(state::stop) { Text("Cancel checking") }
                    TextButton(state::close, enabled = !state.applying) { Text("Close") }
                }
            }
        }
    }
    choosingRoot?.let { id ->
        DestinationDialog(model, move = false, titleOverride = "Replacement root folder", confirmLabel = "Use this folder", requireWritable = false,
            onDestination = { state.target(id, it); choosingRoot = null }, dismiss = { choosingRoot = null })
    }
}

@Composable
private fun SetupCheckbox(label: String, checked: Boolean, enabled: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
    }
}
@Composable
private fun SetupRadio(label: String, selected: Boolean, enabled: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
    }
}
private fun setupDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

@Composable
internal fun SetupRecoveryScreen(message: String, retry: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Recover interrupted setup restore", style = MaterialTheme.typography.headlineSmall)
                Text(message)
                Text("Make space in app storage if it is full, then retry. Clearing app data removes this recovery copy.")
                Button(retry) { Text("Retry setup recovery") }
            }
        }
    }
}

private fun setupSummary(section: SetupSection, value: SetupSnapshot): String = when (section) {
    SetupSection.SIDEBAR -> "${value.sidebar.favorites.size} Favorites, ${value.sidebar.groups.size} groups"
    SetupSection.ORGANIZATION -> "${value.organization.tags.definitions.size} tags, ${value.organization.tags.assignments.size} tagged locations, ${value.organization.collections.size} collections"
    SetupSection.SEARCHES -> "${value.searches.size} saved searches"
    SetupSection.WORKSPACES -> "${value.workspaces.size} workspaces"
    SetupSection.OPEN_TABS -> "${value.openTabs?.tabCount ?: 0} tabs"
    SetupSection.APPEARANCE -> "${value.folderViews.size} views, ${value.folderStyles.size} appearances"
    SetupSection.PRESETS -> "${value.presets.size} presets"
    SetupSection.PREFERENCES -> "Browsing, conflict and ZIP settings"
}
private fun setupReviewDetail(section: SetupSection, before: SetupSnapshot, after: SetupSnapshot, source: SetupSnapshot?, changed: Boolean): String = when (section) {
    SetupSection.OPEN_TABS -> if (changed) "Save ${source?.openTabs?.tabCount ?: 0} backup tabs in “Restored open tabs”." else "No new workspace is needed."
    SetupSection.PREFERENCES -> if (changed) "Use the backup’s browsing, conflict and ZIP settings." else "Keep this tablet’s current preferences."
    else -> "${setupSummary(section, before)} → ${setupSummary(section, after)}${if (changed) "" else " · unchanged"}"
}
