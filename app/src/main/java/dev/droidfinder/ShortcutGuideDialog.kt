package dev.droidfinder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class ShortcutHelp(val group: String, val keys: String, val action: String)

val explorerShortcutHelp =
    listOf(
        ShortcutHelp("Navigate", "Alt+← / Backspace", "Back"),
        ShortcutHelp("Navigate", "Alt+→ / Alt+↑", "Forward / parent folder"),
        ShortcutHelp("Navigate", "Alt+D / Ctrl+L / F4", "Edit address"),
        ShortcutHelp("Navigate", "Ctrl+E / Ctrl+F / F3", "Search"),
        ShortcutHelp("Navigate", "F5 / Ctrl+R", "Refresh"),
        ShortcutHelp("Navigate", "F6 / Shift+F6", "Cycle browser, address and search areas"),
        ShortcutHelp("Navigate", "Ctrl+Shift+E", "Reveal folder in navigation tree"),
        ShortcutHelp("Navigate", "Num + / − / *", "Expand / collapse / expand subtree"),
        ShortcutHelp("Files", "Ctrl+A", "Select all"),
        ShortcutHelp("Files", "Ctrl+C / Ctrl+Insert", "Copy"),
        ShortcutHelp("Files", "Ctrl+X", "Cut"),
        ShortcutHelp("Files", "Ctrl+V / Shift+Insert", "Paste"),
        ShortcutHelp("Files", "Ctrl+D / Delete", "Move to Trash"),
        ShortcutHelp("Files", "Shift+Delete", "Permanent deletion confirmation"),
        ShortcutHelp("Files", "Ctrl+Z / Ctrl+Y", "Undo / redo"),
        ShortcutHelp("Files", "Ctrl+Shift+N", "New folder"),
        ShortcutHelp("Files", "F2 / Alt+Enter", "Rename / properties"),
        ShortcutHelp("Files", "Enter", "Open"),
        ShortcutHelp("Files", "Ctrl+Shift+C", "Copy full path"),
        ShortcutHelp("Tabs & windows", "Ctrl+N", "New independent window"),
        ShortcutHelp("Tabs & windows", "Ctrl+T / Ctrl+W", "New tab / close tab or last window"),
        ShortcutHelp("Tabs & windows", "Ctrl+Shift+T", "Reopen closed tab"),
        ShortcutHelp("Tabs & windows", "Ctrl+Tab / Ctrl+Shift+Tab", "Next / previous tab"),
        ShortcutHelp("Tabs & windows", "Ctrl+1–8 / Ctrl+9", "Numbered tab / last tab"),
        ShortcutHelp("Tabs & windows", "Alt+F4", "Close this window"),
        ShortcutHelp("Views", "Ctrl+Shift+1–4", "Extra large → small icons"),
        ShortcutHelp("Views", "Ctrl+Shift+5 / 6", "Names list / details"),
        ShortcutHelp("Views", "Ctrl+Shift+7 / 8 / 9", "Tiles / content / gallery"),
        ShortcutHelp("Views", "Ctrl+Num +", "Fit list columns to contents"),
        ShortcutHelp("Views", "Alt+P / Alt+Shift+P", "Preview / details inspector"),
        ShortcutHelp("Views", "F11", "Toggle full screen"),
        ShortcutHelp("Selection", "Arrows / Shift+arrows", "Move / extend selection"),
        ShortcutHelp("Selection", "Ctrl+arrows, Ctrl+Space", "Move focus, toggle focused item"),
        ShortcutHelp("Selection", "Home / End / Page ↑ / ↓", "First / last / page of files"),
        ShortcutHelp("Selection", "Shift+F10 / Menu", "File context menu"),
        ShortcutHelp("Selection", "F10 / Escape", "Actions / dismiss or clear selection"),
        ShortcutHelp("Mouse", "Ctrl+drag / Shift+drag", "Copy / move"),
        ShortcutHelp("Mouse", "Alt+drag", "Create app shortcut"),
        ShortcutHelp("Mouse", "Ctrl+wheel", "Zoom icons"),
        ShortcutHelp("Finder extras", "Space", "Peek / Quick Look"),
        ShortcutHelp("Finder extras", "Meta+1–4 / Meta+D", "Finder views / duplicate"),
        ShortcutHelp("Finder extras", "Ctrl+\\", "Toggle dual panes"),
        ShortcutHelp("Help", "F1", "This shortcut guide"),
    )

@Composable
fun ShortcutGuideDialog(dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = explorerShortcutHelp.filter {
        query.isBlank() || (it.group + it.keys + it.action).contains(query, true)
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val height = (maxHeight - 190.dp).coerceIn(120.dp, 480.dp)
            Surface(
                Modifier.widthIn(max = 650.dp).fillMaxWidth().testTag("shortcut-guide"),
                shape = RoundedCornerShape(14.dp),
                color = CanvasBackground,
                shadowElevation = 16.dp,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Keyboard shortcuts",
                            Modifier.weight(1f),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                        )
                        ToolButton(Icons.Outlined.Close, "Close shortcut guide", dismiss)
                    }
                    Text(
                        "Windows controls · Finder aliases",
                        Modifier.padding(horizontal = 20.dp),
                        fontSize = 12.sp,
                        color = Muted,
                    )
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Find a shortcut") },
                        singleLine = true,
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = height),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    ) {
                        shown
                            .groupBy { it.group }
                            .forEach { (group, entries) ->
                                item {
                                    Text(
                                        group,
                                        Modifier.padding(top = 12.dp, bottom = 8.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Muted,
                                    )
                                }
                                items(entries, key = { it.group + it.keys }) { item ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                        Text(
                                            item.keys,
                                            Modifier.weight(.46f).padding(end = 12.dp),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = Ink,
                                        )
                                        Text(
                                            item.action,
                                            Modifier.weight(.54f),
                                            fontSize = 12.sp,
                                            color = Ink,
                                        )
                                    }
                                }
                            }
                        item {
                            Text(
                                "Android controls global Windows-key shortcuts and window placement. F11 requests immersive full screen.",
                                Modifier.padding(top = 16.dp),
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        }
                    }
                }
            }
        }
    }
}
