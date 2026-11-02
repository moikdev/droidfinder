@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.*
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

@Composable
fun NavigationTree(model: FinderModel) {
    if (!model.navigationTreeVisible) return
    val rows = model.navigationRows()
    val current = remember { BringIntoViewRequester() }
    LaunchedEffect(model.location, rows) {
        if (rows.any { it.first.path == model.location }) {
            withFrameNanos {}
            current.bringIntoView()
        }
    }
    Column(Modifier.testTag("navigation-tree")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Folders", Modifier.weight(1f), fontSize = 10.sp, color = Muted)
            ToolButton(
                Icons.Outlined.Close,
                "Hide folder tree",
                model::hideNavigationTree,
                compact = true,
            )
        }
        rows.forEach { (entry, depth) ->
            Row(
                Modifier.fillMaxWidth()
                    .height(model.density.rowHeight)
                    .then(
                        if (model.location == entry.path) Modifier.bringIntoViewRequester(current)
                        else Modifier,
                    )
                    .background(if (model.location == entry.path) SelectionSurface else Sidebar)
                    .folderDropTarget(model, entry.path)
                    .clickable { model.navigate(entry.path) }
                    .padding(start = (depth * 12).coerceAtMost(72).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { model.toggleNavigationFolder(entry) },
                    modifier = Modifier.size(if (model.density.touch) 40.dp else 28.dp),
                ) {
                    Icon(
                        if (entry.path in model.navigationExpanded) Icons.Outlined.ExpandMore
                        else Icons.Outlined.ChevronRight,
                        if (entry.path in model.navigationExpanded) "Collapse tree ${entry.name}"
                        else "Expand tree ${entry.name}",
                        Modifier.size(15.dp),
                        tint = Muted,
                    )
                }
                FolderGlyph(18.dp)
                Text(
                    model.title(entry.path),
                    Modifier.weight(1f).padding(start = 6.dp, end = 8.dp),
                    fontSize = 11.sp,
                    color = if (model.location == entry.path) Blue else Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
