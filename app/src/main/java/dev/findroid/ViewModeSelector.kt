package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class ViewMenuPage { VIEW, SORT, GROUP }

/** One named entry point for how files are displayed, at every tablet width. */
@Composable
fun ViewModeSelector(model: FinderModel) {
    val previewDisplayed = model.previewVisible &&
        (model.browserWidthDp >= 900 || model.inspectorRequested) && !model.previewDetailsOnly
    var expanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(ViewMenuPage.VIEW) }
    Box {
        ToolbarAction("${model.mode.title} view", {
            page = ViewMenuPage.VIEW
            expanded = true
        }, label = "Change view", dropdown = true,
            modifier = Modifier.semantics { stateDescription = "${model.mode.title} view" })
        DropdownMenu(expanded, { expanded = false }) {
            if (page != ViewMenuPage.VIEW) {
                DropdownMenuItem(
                    text = { Text("View options") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                    onClick = { page = ViewMenuPage.VIEW },
                )
                HorizontalDivider()
            }
            when (page) {
                ViewMenuPage.VIEW -> {
                    ViewMenuHeading("View")
                    ViewMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text("${mode.title} view") },
                            modifier = Modifier.semantics {
                                contentDescription = "${mode.title} view"
                                selected = model.mode == mode
                            },
                            trailingIcon = { if (model.mode == mode) Icon(Icons.Outlined.Check, null) },
                            onClick = {
                                model.changeMode(mode)
                                expanded = false
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Sort by: ${model.sort.title}") },
                        trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) },
                        onClick = { page = ViewMenuPage.SORT },
                    )
                    DropdownMenuItem(
                        text = { Text("Group by: ${model.grouping.title}") },
                        trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) },
                        onClick = { page = ViewMenuPage.GROUP },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (previewDisplayed) "Hide preview pane" else "Show preview pane") },
                        onClick = {
                            model.toggleInspector()
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Folder view settings…") },
                        onClick = {
                            model.folderViewRequested = true
                            expanded = false
                        },
                    )
                }
                ViewMenuPage.SORT -> {
                    ViewMenuHeading("Sort by")
                    SortBy.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.title) },
                            trailingIcon = { if (model.sort == sort) Icon(Icons.Outlined.Check, null) },
                            onClick = {
                                if (model.sort != sort) model.changeSort(sort)
                                expanded = false
                            },
                        )
                    }
                    HorizontalDivider()
                    listOf(true to "Ascending", false to "Descending").forEach { (ascending, title) ->
                        DropdownMenuItem(
                            text = { Text(title) },
                            trailingIcon = { if (model.ascending == ascending) Icon(Icons.Outlined.Check, null) },
                            onClick = {
                                if (model.ascending != ascending) model.changeSort(model.sort)
                                expanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Folders first") },
                        trailingIcon = { if (model.foldersFirst) Icon(Icons.Outlined.Check, null) },
                        onClick = {
                            model.toggleFoldersFirst()
                            expanded = false
                        },
                    )
                }
                ViewMenuPage.GROUP -> {
                    ViewMenuHeading("Group by")
                    GroupBy.entries.forEach { group ->
                        DropdownMenuItem(
                            text = { Text(group.title) },
                            trailingIcon = { if (model.grouping == group) Icon(Icons.Outlined.Check, null) },
                            onClick = {
                                model.changeGrouping(group)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewMenuHeading(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Muted, fontSize = 12.sp)
}
