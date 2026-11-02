package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PhoneFileList(model: FinderModel, open: (Entry) -> Unit) {
    val groups = model.groups()
    val keys = groups.flatMap { (heading, entries) ->
        (if (heading.isEmpty()) emptyList() else listOf<String?>(null)) + entries.map { it.path }
    }
    val state = rememberKeyboardListState(model, keys, "phone-list")
    LazyColumn(
        Modifier.fillMaxSize().dragAutoScroll(state).testTag("browser-phone-list"),
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        groups.forEach { (heading, entries) ->
            if (heading.isNotEmpty())
                item(key = "group:$heading") {
                    Text(
                        "$heading · ${entries.size}",
                        Modifier.padding(12.dp),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            items(entries, key = { it.path }) { entry ->
                val selected = entry.path in model.selected
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .folderDropTarget(
                            model,
                            entry.path.takeIf { entry.folder },
                            visible = {
                                state.layoutInfo.visibleItemsInfo.any { it.key == entry.path }
                            },
                        )
                        .background(
                            if (selected) SelectionSurface else CanvasBackground,
                            RoundedCornerShape(10.dp),
                        )
                        .fileGestures(model, entry, open)
                        .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        DraggableFileGlyph(model, entry, 36.dp)
                        if (selected)
                            Icon(
                                Icons.Outlined.CheckCircle,
                                "Selected",
                                Modifier.align(Alignment.BottomEnd).size(18.dp),
                                tint = Blue,
                            )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            model.displayName(entry),
                            color = Ink,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (entry.folder) "Folder" else "${entry.displaySize} · ${entry.kind}",
                            Modifier.padding(top = 3.dp),
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FileActionsButton(model, entry)
                }
            }
        }
    }
}
