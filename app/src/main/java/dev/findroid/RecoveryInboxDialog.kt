package dev.findroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A single scrolling surface keeps named review controls reachable at enlarged system text. */
@Composable
internal fun RecoveryInboxDialog(model: FinderModel, route: (RecoveryInboxLink) -> Unit) {
    val owner = model.primaryPane
    val state = owner.recoveryInbox ?: return
    var kind by remember { mutableStateOf<RecoveryInboxKind?>(null) }
    var shown by remember(state.token, kind) { mutableIntStateOf(20) }
    val items = state.items.filter { kind == null || it.kind == kind }
    val density = LocalDensity.current
    Dialog(owner::closeRecoveryInbox, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("recovery-inbox"),
                color = CanvasBackground, shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, Border)) {
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("recovery-inbox-list"), contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("Recovery inbox", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Review interrupted work and preserved originals. Opening this inbox does not resume, restore or remove files.", color = Muted, fontSize = 14.sp)
                        Text("Locations are recorded references. Access and file identity are checked by the action you choose.", color = Muted, fontSize = 13.sp)
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("All (${state.items.size})") }, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-filter:ALL"))
                            RecoveryInboxKind.entries.forEach { choice ->
                                val count = state.items.count { it.kind == choice }
                                if (count > 0) FilterChip(selected = kind == choice, onClick = { kind = choice },
                                    label = { Text("${choice.title} ($count)") }, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-filter:${choice.name}"))
                            }
                        }
                    }
                    if (state.checking) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Reading saved records and Findroid Trash…", color = Muted, fontSize = 14.sp)
                    }
                    items(state.notices) { notice -> Text(notice, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                    if (!state.checking && items.isEmpty()) item {
                        Text(if (state.notices.isEmpty()) "No saved items need review in this list." else "Some records could not be listed. Their data was kept.", color = Ink, fontSize = 15.sp)
                        Text("Untracked working data can still remain in a destination. This inbox does not scan connected providers or infer that an interrupted operation finished.", color = Muted, fontSize = 13.sp)
                    }
                    items(items.take(shown), key = { it.id }) { item ->
                        var details by remember(item.id) { mutableStateOf(false) }
                        Surface(modifier = Modifier.testTag("recovery-item:${item.id}"), color = AppBackground, shape = RoundedCornerShape(10.dp), border = BorderStroke(.5.dp, Border)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.kind.title, color = Muted, fontSize = 12.sp)
                                Text(item.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(item.explanation, color = Ink, fontSize = 14.sp)
                                Text(item.nextStep, color = Muted, fontSize = 14.sp)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item.links.forEach { link -> TextButton(onClick = { route(link) }, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-link:${item.id}:${link.route.name}")) { Text(link.label) } }
                                    if (item.places.isNotEmpty()) TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-locations:${item.id}")) {
                                        Text(if (details) "Hide recorded locations" else "Show recorded locations")
                                    }
                                }
                                if (details) SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        item.places.forEach { place ->
                                            Text("${place.label}\n${place.path}", color = Ink, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (items.size > shown) item {
                        TextButton(onClick = { shown += 20 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show more items ($shown of ${items.size})") }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = owner::showRecoveryInbox, enabled = !state.checking, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh records") }
                            TextButton(onClick = { route(RecoveryInboxLink("Review storage access", RecoveryInboxRoute.STORAGE)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review storage access") }
                            TextButton(onClick = owner::closeRecoveryInbox, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-close")) { Text("Close") }
                        }
                    }
                }
            }
        }
        }
    }
}
