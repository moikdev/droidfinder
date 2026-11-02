package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchLocationsDialog(model: FinderModel, dismiss: () -> Unit) {
    val original = remember { Triple(model.tabs[model.activeTab].id, model.location, model.effectiveSearchRules) }
    var chosen by remember { mutableStateOf(model.effectiveSearchRules.locations.ifEmpty {
        listOfNotNull(model.currentSmartFolder?.root ?: model.folderPath)
    }.toList()) }
    var choosing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (choosing) {
        DestinationDialog(model, false, titleOverride = "Add search folder", confirmLabel = "Add folder",
            requireWritable = false, onDestination = { path ->
                choosing = false
                if (path in chosen) error = "That folder is already chosen."
                else runCatching { checkedSearchLocations(chosen + path) }
                    .onSuccess { chosen = it; error = null }.onFailure { error = it.message }
            }) { choosing = false }
        return
    }
    WorkspaceUtility("Search locations", "Choose up to $SEARCH_LOCATION_LIMIT folders. Available results remain visible if another folder is unavailable.",
        "search-locations", dismiss) { limit ->
        if (!model.canChooseSearchLocations) {
            Text("Open a folder before choosing search locations. This view has its own membership rules.",
                Modifier.padding(20.dp), fontSize = 14.sp)
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(dismiss) { Text("Cancel") }
                Button({ model.navigate(model.workspace.path); dismiss() }) { Text("Open Workspace") }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = limit).testTag("search-locations-list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("${chosen.size} ${if (chosen.size == 1) "folder" else "folders"} chosen", fontSize = 14.sp)
                    Text("The first folder names this search. Cancel keeps the current scope.", fontSize = 12.sp, color = Muted)
                }
                items(chosen, key = { it }) { path ->
                    Surface(color = AppBackground, modifier = Modifier.fillMaxWidth().testTag("search-location-$path")) {
                        Column(Modifier.padding(12.dp)) {
                            Text(model.displayPath(path), fontSize = 14.sp)
                            if (isDocumentPath(path)) Text(path, fontSize = 11.sp, color = Muted)
                            TextButton({ chosen = chosen - path; error = null }, Modifier.testTag("remove-search-location-$path")) { Text("Remove") }
                        }
                    }
                }
                item {
                    TextButton({ choosing = true }, enabled = chosen.size < SEARCH_LOCATION_LIMIT,
                        modifier = Modifier.testTag("add-search-location")) { Text("Add folder…") }
                    if (chosen.size == SEARCH_LOCATION_LIMIT) Text("Ten folders are chosen. Remove one to add another.", fontSize = 12.sp, color = Muted)
                    if (chosen.isEmpty()) Text("Choose at least one folder to search.", fontSize = 13.sp)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("search-locations-error")) }
                }
            }
            HorizontalDivider()
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(dismiss) { Text("Cancel") }
                Button({
                    if (original != Triple(model.tabs[model.activeTab].id, model.location, model.effectiveSearchRules))
                        error = "The current tab or search changed. Close this dialog and choose the folders again."
                    else runCatching { model.chooseSearchLocations(chosen) }
                        .onSuccess { dismiss() }.onFailure { error = it.message }
                }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("apply-search-locations")) { Text("Search") }
            }
        }
    }
}
