package dev.findroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun ListingStatus(model: FinderModel) {
    val progress = model.listingProgress
    val rules = model.effectiveSearchRules
    val scoped =
        rules.scoped &&
            (model.folderPath != null ||
                model.currentSmartFolder != null ||
                model.location == "@recent")
    var showLoading by
        remember(model.location, model.query, model.searchRules) { mutableStateOf(false) }
    LaunchedEffect(model.loading) {
        if (model.loading) {
            delay(250)
            showLoading = true
        } else showLoading = false
    }
    val dragging = LocalFinderDrag.current?.active == true
    var pinnedVisibility by remember { mutableStateOf(false) }
    val wanted =
        (model.loading && showLoading) ||
            progress.incomplete ||
            progress.providerInfo.isNotEmpty() ||
            scoped
    val visible = if (dragging) pinnedVisibility else wanted
    SideEffect { if (!dragging) pinnedVisibility = wanted }
    if (!visible) return
    Surface(
        color = SubtleSurface,
        border = BorderStroke(.5.dp, Border),
        modifier = Modifier.fillMaxWidth().testTag("listing-status"),
    ) {
        Row(
            Modifier.heightIn(min = model.density.targetSize).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (model.loading) Icons.Outlined.FolderOpen
                else if (progress.stopped) Icons.Outlined.PauseCircleOutline
                else Icons.Outlined.Info,
                null,
                Modifier.size(17.dp),
                tint = if (progress.issueCount > 0) MaterialTheme.colorScheme.error else Muted,
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                val title =
                    when {
                        model.loading && progress.matches < model.entries.size ->
                            "${model.entries.size} shown · refreshing"
                        model.loading ->
                            "${model.entries.size} ${if (model.effectiveSearchRules.active || model.currentSmartFolder != null) "matches" else "items"} so far"
                        progress.stopped -> "Stopped · ${model.entries.size} results kept"
                        progress.resultLimit -> "Showing ${model.entries.size} results"
                        progress.scanLimit ->
                            "Scan limit reached · ${progress.scanned} items scanned"
                        progress.providerPending -> "Provider is still loading results"
                        progress.issueCount > 0 -> "Some items couldn't be scanned"
                        progress.providerInfo.isNotEmpty() -> "Storage information"
                        scoped -> "Search scope · ${model.entries.size} results"
                        else -> "Ready · ${model.entries.size} items"
                    }
                Text(
                    title,
                    color = Ink,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        model.loading -> "${progress.scanned} scanned · ${progress.folders} folders"
                        progress.resultLimit || progress.scanLimit ->
                            if (model.canLoadMore) "Load more to continue with a higher limit"
                            else "Refine the search or open a smaller folder"
                        progress.stopped -> "These results are incomplete"
                        progress.providerPending -> "Available files are shown; refresh when ready"
                        progress.issueCount > 0 ->
                            if (progress.issueCount == 1) "1 check needs attention"
                            else "${progress.issueCount} checks need attention"
                        else ->
                            progress.providerInfo.firstOrNull()
                                ?: if (scoped) rules.scopeDescription else "Scan complete"
                    },
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.loading) {
                TextButton(
                    onClick = model::stopLoading,
                    modifier = Modifier.testTag("stop-listing"),
                ) {
                    Text("Stop", fontSize = 11.sp)
                }
            } else {
                if ((progress.resultLimit || progress.scanLimit) && model.canLoadMore)
                    TextButton(
                        onClick = model::loadMoreResults,
                        modifier = Modifier.testTag("load-more-results"),
                    ) {
                        Text("Load more", fontSize = 11.sp)
                    }
                else
                    TextButton(
                        onClick = { model.refresh() },
                        modifier = Modifier.testTag("retry-listing"),
                    ) {
                        Text(if (progress.stopped) "Restart" else "Refresh", fontSize = 11.sp)
                    }
                IconButton(
                    onClick = { model.listingDetailsRequested = true },
                    modifier = Modifier.size(model.density.targetSize),
                ) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        "Result details",
                        Modifier.size(18.dp),
                        tint = Muted,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ListingDetails(model: FinderModel) {
    if (!model.listingDetailsRequested) return
    WorkspaceUtility(
        "Result details",
        "${model.entries.size} shown · ${model.listingProgress.scanned} scanned",
        "listing-details",
        { model.listingDetailsRequested = false },
        width = 500.dp,
    ) { limit ->
        val progress = model.listingProgress
        Column(
            Modifier.weight(1f, fill = false)
                .heightIn(max = limit)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    progress.stopped ->
                        "The scan was stopped. Restart to check the remaining items."
                    (progress.resultLimit || progress.scanLimit) && !model.canLoadMore ->
                        "The browser limit has been reached. Refine the search or open a smaller folder."
                    progress.resultLimit || progress.scanLimit ->
                        "Results are limited to keep browsing responsive. Load more to repeat the scan with a higher limit."
                    progress.incomplete ->
                        "Some files or locations have not been fully scanned. Available results are shown."
                    else -> "The scan completed."
                },
                color = Ink,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            progress.providerInfo.forEach { Text(it, color = Muted, fontSize = 12.sp) }
            if (model.effectiveSearchRules.scoped) {
                val rules = model.effectiveSearchRules
                Text(rules.scopeDescription, color = Ink, fontSize = 12.sp)
                if (rules.excludedFolderNames.isNotEmpty()) {
                    Text("Excluded folder names (exact match):", color = Muted, fontSize = 12.sp)
                    rules.excludedFolderNames.sorted().forEach {
                        Text(it, color = Ink, fontSize = 12.sp)
                    }
                }
            }
            if (progress.providerPending)
                Text(
                    "The document provider reported that it is still preparing results. Refresh this location to check again.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            progress.issues.forEach { issue ->
                Surface(color = AppBackground, shape = RoundedCornerShape(7.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            if (isDocumentPath(issue.path)) model.documents.cachedTitle(issue.path)
                            else model.displayPath(issue.path),
                            color = Ink,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            issue.reason,
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (progress.issueCount > progress.issues.size)
                Text(
                    "${progress.issueCount - progress.issues.size} more issues · showing the first ${progress.issues.size}",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
        }
    }
}
