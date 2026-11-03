package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvailableActionsDialog(model: FinderModel, locations: () -> Unit, dismiss: () -> Unit) {
    val selected = model.selectedEntries
    val folder = model.folderInfo
    val location = model.location
    val loading = model.loading
    val issue = model.folderStorageIssue
    val error = model.listingError
    val trash = model.isTrash
    val connected = model.connectedLocations
    val facts = remember(selected, folder, location, loading, issue, error, trash, connected) {
        if (selected.isNotEmpty()) model.storageAccessFacts(selected)
        else listOfNotNull(model.currentStorageAccessFacts())
    }
    val summaries by produceState<List<StorageActionSummary>?>(null, facts) {
        value = null
        value = withContext(Dispatchers.Default) { StorageAction.entries.map { storageActionSummary(facts, it) } }
    }
    val subtitle = if (selected.isNotEmpty()) "${selected.size} selected item${if (selected.size == 1) "" else "s"} · this pane"
        else "Current folder · this pane"
    WorkspaceUtility("Available actions", subtitle, "available-actions", dismiss, width = 660.dp) { limit ->
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = limit).testTag("available-actions-list"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("Based on information from the last folder load. Opening this window does not contact a provider. Operations check access again before changing files.", fontSize = 13.sp)
                Text("A folder's capabilities describe that folder, not every file inside it. Missing provider information is shown as Not reported.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
                if (facts.size == 1) {
                    Text(facts.single().name, Modifier.padding(top = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(model.storageAccessLocation(facts.single().path), fontSize = 12.sp, color = Muted,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (facts.isEmpty()) Text("Select files or open a real folder to review its available actions. A library or search view is not a destination for new files.", Modifier.padding(top = 12.dp), fontSize = 14.sp)
                else if (summaries == null) Text("Summarizing the loaded selection…", Modifier.padding(top = 12.dp), fontSize = 13.sp)
                if (model.storageActionRefresh || loading) Text("Refreshing folder information…", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Muted)
            }
            items(summaries.orEmpty().filter { it.total > 0 }, key = { it.action }) { summary ->
                Surface(color = AppBackground, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().testTag("available-action-${summary.action.name.lowercase()}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(summary.action.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (facts.size == 1) {
                            val detail = storageActionDetail(facts.single(), summary.action)
                            Text(detail.support.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(detail.reason, fontSize = 13.sp, color = Muted)
                        } else {
                            Text("${summary.available} reported available · ${summary.unavailable} unavailable · ${summary.unknown} not reported · ${summary.notApplicable} do not apply", fontSize = 13.sp)
                            summary.examples.forEach { example ->
                                Text(example.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(model.storageAccessLocation(example.path), fontSize = 11.sp, color = Muted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(example.detail.reason, fontSize = 12.sp, color = Muted)
                            }
                            if (summary.unavailable + summary.unknown + summary.notApplicable > summary.examples.size)
                                Text("Showing up to three examples; the counts cover all ${summary.total} selected items.", fontSize = 12.sp, color = Muted)
                            if (summary.action == StorageAction.TRASH)
                                Text("Provider items use a local Findroid recovery copy before removal. They do not use the provider's own Trash. Restoring may create new document addresses.", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ model.refreshStorageActionFacts() }, enabled = model.folderPath != null && !model.loading &&
                !model.storageActionRefresh && model.busy == null, modifier = Modifier.heightIn(min = 48.dp).testTag("refresh-available-actions")) {
                Text("Refresh access info")
            }
            TextButton(locations, Modifier.heightIn(min = 48.dp).testTag("available-actions-storage")) { Text("Storage locations…") }
            Button(dismiss, Modifier.heightIn(min = 48.dp).testTag("close-available-actions")) { Text("Done") }
        }
    }
}
