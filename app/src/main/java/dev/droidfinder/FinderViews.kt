@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.fileGestures(model: FinderModel, entry: Entry, open: (Entry) -> Unit, context: ((Entry) -> Unit)? = null): Modifier = composed {
    var control by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    pointerInput(entry.path) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    control = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                    shift = event.keyboardModifiers.isShiftPressed
                    if (event.buttons.isSecondaryPressed && context != null) { context(entry); event.changes.forEach { it.consume() } }
                }
            }
        }
    }.combinedClickable(onClickLabel = "Select ${entry.name}", onClick = { model.select(entry, multiple = control, range = shift) }, onDoubleClick = { open(entry) }, onLongClickLabel = "Select multiple files", onLongClick = { model.select(entry, true) })
}

@Composable fun ListView(model: FinderModel, open: (Entry) -> Unit, context: (Entry) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val roomy = maxWidth > 610.dp
        val showDate = maxWidth > 440.dp
        Column {
            Row(Modifier.fillMaxWidth().height(32.dp).background(Color(0xFFFAFBFC)).padding(start = 26.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SortHeader("Name", SortBy.NAME, model, Modifier.weight(1f))
                if (showDate) SortHeader("Date modified", SortBy.MODIFIED, model, Modifier.width(if (roomy) 150.dp else 125.dp))
                SortHeader("Size", SortBy.SIZE, model, Modifier.width(75.dp))
                if (roomy) SortHeader("Kind", SortBy.KIND, model, Modifier.width(110.dp))
                Spacer(Modifier.width(28.dp))
            }
            HorizontalDivider(color = Border, thickness = .5.dp)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(model.entries, key = { _, item -> item.path }) { index, item ->
                    val selected = item.path in model.selected
                    Row(Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 8.dp).background(if (selected) Color(0xFFE4EEFC) else if (index % 2 == 1) Color(0xFFF7F8FA) else Color.White, RoundedCornerShape(5.dp)).fileGestures(model, item, open, context).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileGlyph(item, 27.dp)
                        Text(item.name, Modifier.weight(1f).padding(horizontal = 9.dp), fontSize = 12.sp, color = if (selected) Color(0xFF235B9B) else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        model.tags[item.path]?.let { Box(Modifier.padding(end = 10.dp).size(6.dp).background(tagColor(it), CircleShape)) }
                        if (showDate) Text(formatDate(item.modified), Modifier.width(if (roomy) 150.dp else 125.dp), color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (item.folder) "—" else formatSize(item.size), Modifier.width(75.dp), color = Muted, fontSize = 10.sp)
                        if (roomy) Text(item.kind, Modifier.width(110.dp), color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { context(item) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.MoreHoriz, "Actions for ${item.name}", Modifier.size(16.dp), tint = Muted) }
                    }
                }
            }
        }
    }
}

@Composable private fun SortHeader(title: String, sort: SortBy, model: FinderModel, modifier: Modifier) { Row(modifier.fillMaxHeight().clickable { model.changeSort(sort) }, verticalAlignment = Alignment.CenterVertically) { Text(title, color = if (model.sort == sort) Ink else Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium); if (model.sort == sort) Icon(if (model.ascending) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, Modifier.padding(start = 3.dp).size(12.dp), tint = Muted) } }

@Composable fun IconView(model: FinderModel, open: (Entry) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(model.entries, key = { it.path }) { item ->
            val selected = item.path in model.selected
            Column(Modifier.height(139.dp).background(if (selected) Color(0xFFE6EFFC) else Color.Transparent, RoundedCornerShape(8.dp)).fileGestures(model, item, open).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(78.dp), contentAlignment = Alignment.Center) { FileGlyph(item, 78.dp); if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", Modifier.align(Alignment.TopEnd).size(16.dp), tint = Blue) }
                Text(item.name, Modifier.fillMaxWidth().padding(top = 3.dp), fontSize = 11.sp, color = Ink, maxLines = 2, lineHeight = 14.sp, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                model.tags[item.path]?.let { Box(Modifier.padding(top = 4.dp).size(5.dp).background(tagColor(it), CircleShape)) }
            }
        }
    }
}

@Composable fun ColumnsView(model: FinderModel, open: (Entry) -> Unit) {
    val parent = model.directory?.parentFile?.takeIf(model::allowed)
    val previous by produceState<List<Entry>>(emptyList(), parent?.absolutePath, model.showHidden) {
        value = withContext(Dispatchers.IO) { parent?.listFiles()?.filter { model.showHidden || !it.name.startsWith('.') }?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })?.map(::Entry).orEmpty() }
    }
    val focus = model.focused
    val children by produceState<List<Entry>>(emptyList(), focus?.path, model.showHidden) { value = withContext(Dispatchers.IO) { focus?.takeIf { it.folder }?.file?.listFiles()?.filter { model.showHidden || !it.name.startsWith('.') }?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })?.map(::Entry).orEmpty() } }
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        if (parent != null && model.query.isEmpty()) {
            FileColumn(model.title(parent.absolutePath), previous, model.location, Modifier.width(180.dp), { if (it.folder) model.navigate(it.path) else open(it) }, open)
            VerticalDivider(color = Border)
        }
        FileColumn(model.title(), model.entries, focus?.path, Modifier.width(240.dp), { model.select(it) }, open, { model.select(it, true) })
        VerticalDivider(color = Border)
        if (focus?.folder == true) FileColumn(focus.name, children, null, Modifier.width(240.dp), open, open)
        else if (focus != null) Column(Modifier.width(240.dp).fillMaxHeight().background(Color(0xFFFAFBFC)).padding(16.dp)) { PreviewContent(focus, Modifier.fillMaxWidth().height(190.dp)); Metadata(focus, model, Modifier.padding(top = 20.dp)) }
        else Box(Modifier.width(220.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("Select a folder to explore", fontSize = 11.sp, color = Muted) }
    }
}

@Composable private fun FileColumn(title: String, entries: List<Entry>, selected: String?, modifier: Modifier, select: (Entry) -> Unit, open: (Entry) -> Unit, multi: ((Entry) -> Unit)? = null) {
    Column(modifier.fillMaxHeight()) {
        Text(title, Modifier.fillMaxWidth().background(Color(0xFFFAFBFC)).padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyColumn(Modifier.fillMaxSize().padding(5.dp)) { items(entries, key = { it.path }) { item ->
            Row(Modifier.fillMaxWidth().height(36.dp).background(if (selected == item.path) Color(0xFFE4EEFC) else Color.Transparent, RoundedCornerShape(4.dp)).combinedClickable(onClick = { select(item) }, onDoubleClick = { open(item) }, onLongClick = { multi?.invoke(item) }).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { FileGlyph(item, 24.dp); Text(item.name, Modifier.weight(1f).padding(horizontal = 6.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); if (item.folder) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(12.dp), tint = Muted) }
        } }
    }
}

@Composable fun GalleryView(model: FinderModel, open: (Entry) -> Unit) {
    val item = model.focused ?: model.entries.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        if (item != null) {
            Row(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF6F7F9))) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(18.dp).combinedClickable(onClick = { model.select(item) }, onDoubleClick = { open(item) })) { PreviewContent(item, Modifier.fillMaxSize(), large = true) }
                Metadata(item, model, Modifier.width(200.dp).padding(top = 28.dp, end = 20.dp).verticalScroll(rememberScrollState()))
            }
            HorizontalDivider(color = Border)
            LazyRow(Modifier.fillMaxWidth().height(128.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(model.entries, key = { it.path }) { entry ->
                    Column(Modifier.width(95.dp).fillMaxHeight().background(if (entry.path == item.path) Color(0xFFE6EFFC) else Color.Transparent, RoundedCornerShape(7.dp)).fileGestures(model, entry, open).padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally) { FileGlyph(entry, 62.dp); Text(entry.name, Modifier.padding(top = 4.dp), fontSize = 9.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable fun PreviewPane(model: FinderModel, quicklook: (Entry) -> Unit, open: (Entry) -> Unit, modifier: Modifier) {
    val entry = model.focused
    Column(modifier.fillMaxHeight().background(Color(0xFFFCFCFD))) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 18.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text("PREVIEW", Modifier.weight(1f), fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Muted); ToolButton(Icons.Outlined.Close, "Hide preview", model::togglePreview, compact = true) }
        if (entry == null) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(Icons.Outlined.Visibility, null, Modifier.size(29.dp), tint = Color(0xFFC7CED8)); Text("A closer look", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Muted); Text("Select a file to see its preview\nand details here.", Modifier.padding(top = 7.dp, bottom = 60.dp), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 17.sp, color = Muted) }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                PreviewContent(entry, Modifier.fillMaxWidth().height(205.dp).padding(horizontal = 14.dp, vertical = 8.dp))
                Metadata(entry, model, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                if (model.selected.size > 1) Text("${model.selected.size} items selected", Modifier.padding(horizontal = 20.dp), color = Blue, fontSize = 11.sp)
            }
            if (!model.isTrash) Column(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedButton(onClick = { quicklook(entry) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(8.dp)) { Icon(Icons.Outlined.Visibility, null, Modifier.size(15.dp)); Text("Quick Look", Modifier.padding(start = 8.dp), fontSize = 11.sp) }
                if (!entry.folder) TextButton(onClick = { open(entry) }, modifier = Modifier.fillMaxWidth()) { Text("Open with app", fontSize = 11.sp) }
            }
        }
    }
}

@Composable fun PathBar(model: FinderModel, goTo: () -> Unit) {
    HorizontalDivider(color = Border)
    Row(Modifier.fillMaxWidth().height(34.dp).background(Color(0xFFFAFBFC)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            val directory = model.directory
            if (directory == null) { Icon(if (model.isTrash) Icons.Outlined.DeleteOutline else Icons.Outlined.FolderSpecial, null, Modifier.size(13.dp), tint = Muted); Text(model.title(), Modifier.padding(start = 7.dp), fontSize = 10.sp, color = Ink) }
            else {
                val ancestors = generateSequence(directory) { it.parentFile?.takeIf(model::allowed) }.toList().reversed()
                ancestors.forEachIndexed { index, folder ->
                    if (index > 0) Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(horizontal = 4.dp).size(11.dp), tint = Muted)
                    Row(Modifier.clickable { model.navigate(folder.absolutePath) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { if (index == 0) Icon(Icons.Outlined.TabletMac, null, Modifier.padding(end = 5.dp).size(13.dp), tint = Muted); Text(model.title(folder.absolutePath), color = if (index == ancestors.lastIndex) Ink else Muted, fontSize = 10.sp) }
                }
            }
        }
        IconButton(onClick = goTo, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.EditLocationAlt, "Go to folder", Modifier.size(14.dp), tint = Muted) }
    }
}

@Composable fun StatusBar(model: FinderModel) {
    HorizontalDivider(color = Border, thickness = .5.dp)
    Row(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFF4F5F7)).padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        val count = model.entries.size
        val text = when { model.loading -> "Loading…"; model.selected.isNotEmpty() -> "${model.selected.size} of $count selected"; model.query.isNotBlank() -> "$count matches · includes subfolders"; else -> "$count item${if (count == 1) "" else "s"}" }
        Text(text + if (model.truncated) " · results limited" else "", fontSize = 9.sp, color = Muted)
        if (model.selectedEntries.any { !it.folder }) Text(" · ${formatSize(model.selectedEntries.filter { !it.folder }.sumOf { it.size })}", color = Muted, fontSize = 9.sp)
        Spacer(Modifier.weight(1f))
        Text(if (model.selected.isEmpty()) "Double-tap to open · Hold to select multiple" else "Space for Quick Look", fontSize = 9.sp, color = Muted)
    }
}

@Composable fun EmptyState(icon: ImageVector, title: String, description: String, button: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(48.dp), tint = Color(0xFFB0C0D4))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 19.dp), textAlign = TextAlign.Center)
        Text(description, Modifier.widthIn(max = 350.dp).padding(top = 9.dp), color = Muted, fontSize = 12.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        if (button != null) OutlinedButton(onClick = onAction, Modifier.padding(top = 23.dp), shape = RoundedCornerShape(7.dp)) { Text(button, fontSize = 12.sp) }
    }
}

@Composable fun WelcomeState(grant: () -> Unit, create: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        FolderGlyph(100.dp)
        Text("Hello, everything.", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp, color = Ink, modifier = Modifier.padding(top = 15.dp))
        Text("A familiar home for the files on your tablet.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 9.dp), textAlign = TextAlign.Center)
        Text("Connect your storage to browse, organize, and find\nyour files. You're always in control of access.", Modifier.padding(top = 18.dp), fontSize = 12.sp, lineHeight = 20.sp, color = Muted, textAlign = TextAlign.Center)
        Button(onClick = grant, modifier = Modifier.padding(top = 24.dp).height(43.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 22.dp)) { Icon(Icons.Outlined.TabletMac, null, Modifier.size(17.dp)); Text("Connect tablet storage", Modifier.padding(start = 9.dp), fontSize = 12.sp) }
        TextButton(onClick = create, modifier = Modifier.padding(top = 5.dp)) { Text("Or create a folder in Workspace", fontSize = 11.sp) }
        Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = Muted); Text("On your device. In your hands.", Modifier.padding(start = 6.dp), fontSize = 10.sp, color = Muted) }
    }
}

@Composable fun NameDialog(title: String, initial: String, button: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, if (title == "Rename item" && initial.lastIndexOf('.') > 0) initial.lastIndexOf('.') else initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    fun confirm() {
        val problem = if (title == "Go to folder") null else runCatching { validateFilename(value.text) }.exceptionOrNull()?.message
        if (problem != null) error = problem else onConfirm(value.text)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title, fontSize = 21.sp) }, text = {
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(value, { value = it; error = null }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, isError = error != null, supportingText = error?.let { { Text(it) } }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { confirm() }), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp))
    }, confirmButton = { TextButton(onClick = { confirm() }, enabled = value.text.isNotBlank()) { Text(button) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun MetaInfoText(label: String, value: String) { Text(label, fontWeight = FontWeight.Medium, color = Muted, fontSize = 11.sp); Text(value, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Ink) }
