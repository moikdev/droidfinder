package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CompressDialog(
    model: FinderModel,
    onDismiss: () -> Unit,
    onCompress: (String, Int, Boolean) -> Unit,
) {
    val selected = remember { model.actionEntries }
    var name by remember {
        mutableStateOf(
            if (selected.size == 1)
                (if (selected.first().folder) selected.first().name
                else selected.first().name.substringBeforeLast('.', selected.first().name)) + ".zip"
            else "Archive.zip",
        )
    }
    var compression by remember { mutableStateOf(ZipCompression.BALANCED) }
    val valid = runCatching { zipFilename(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress to ZIP") },
        text = {
            Column(Modifier.widthIn(min = 340.dp)) {
                Text(
                    "${selected.size} item${if (selected.size == 1) "" else "s"} · originals are kept",
                    fontSize = 12.sp,
                    color = Muted,
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Archive name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    isError = valid.isFailure,
                )
                if (valid.isFailure)
                    Text(
                        valid.exceptionOrNull()?.message.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                Text("Compression", Modifier.padding(top = 16.dp, bottom = 5.dp), fontSize = 12.sp)
                ZipCompression.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { compression = option }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(compression == option, { compression = option })
                        Text(option.title, fontSize = 12.sp)
                    }
                }
                Text(
                    "Destination: ${if (model.canCreate) model.title() else "Choose a writable folder"}",
                    Modifier.padding(top = 8.dp),
                    fontSize = 11.sp,
                    color = Muted,
                )
                TextButton(
                    onClick = { onCompress(valid.getOrThrow(), compression.level, true) },
                    enabled = valid.isSuccess,
                ) {
                    Text("Choose destination…")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCompress(valid.getOrThrow(), compression.level, !model.canCreate) },
                enabled = valid.isSuccess && selected.isNotEmpty(),
            ) {
                Text(if (model.canCreate) "Compress" else "Choose folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ArchiveBrowserDialog(
    model: FinderModel,
    entry: Entry,
    onDismiss: () -> Unit,
    onExtract: (Boolean) -> Unit,
    onOpenWith: () -> Unit,
) {
    var prefix by remember(entry.path) { mutableStateOf("") }
    val result by
        produceState<Result<ZipContents>?>(null, entry.path, entry.modified) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        inspectZip(
                            if (entry.documentUri != null) model.documents.materialize(entry)
                            else entry.file,
                        )
                    }
                }
        }
    val contents = result?.getOrNull()
    val cacheLimited = result?.exceptionOrNull() is PreviewSizeLimitException
    val canExtract = contents != null || cacheLimited
    val children =
        contents
            ?.items
            .orEmpty()
            .filter { it.path.substringBeforeLast('/', "") == prefix }
            .sortedWith(
                compareByDescending<ZipItem> { it.folder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.path },
            )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth(.85f).fillMaxHeight(.84f).testTag("archive-browser").semantics {
                paneTitle = "Archive contents"
            },
            color = CanvasBackground,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FolderZip, null, tint = SelectedInk)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            entry.name,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("ZIP archive · read-only contents", color = Muted, fontSize = 11.sp)
                    }
                    ToolButton(Icons.Outlined.Close, "Close archive", onDismiss)
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton(
                        Icons.Outlined.ArrowUpward,
                        "Parent archive folder",
                        { prefix = prefix.substringBeforeLast('/', "") },
                        enabled = prefix.isNotEmpty(),
                    )
                    Text(
                        prefix.ifBlank { "Archive root" },
                        Modifier.weight(1f),
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    contents?.let {
                        Text(
                            "${it.items.count { !it.folder }} ${if (it.items.count { !it.folder } == 1) "file" else "files"} · ${formatSize(it.size)}",
                            fontSize = 11.sp,
                            color = Muted,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        result == null ->
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                        result!!.isFailure ->
                            Text(
                                if (cacheLimited)
                                    "This archive is too large to browse in the preview cache. You can still extract it."
                                else result!!.exceptionOrNull()?.message ?: "Couldn't open archive",
                                Modifier.padding(24.dp),
                                color = Muted,
                            )
                        children.isEmpty() -> Text("This archive folder is empty", color = Muted)
                        else ->
                            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                items(children, key = { it.path }) { item ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .height(46.dp)
                                            .clickable(enabled = item.folder) { prefix = item.path }
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (item.folder) FolderGlyph(28.dp)
                                        else
                                            Icon(
                                                Icons.AutoMirrored.Outlined.InsertDriveFile,
                                                null,
                                                Modifier.size(24.dp),
                                                tint = Muted,
                                            )
                                        Text(
                                            item.path.substringAfterLast('/'),
                                            Modifier.weight(1f).padding(horizontal = 12.dp),
                                            fontSize = 13.sp,
                                        )
                                        Text(
                                            if (item.folder) "Folder"
                                            else if (item.size < 0) "—" else formatSize(item.size),
                                            color = Muted,
                                            fontSize = 11.sp,
                                        )
                                        if (item.folder)
                                            Icon(
                                                Icons.Outlined.ChevronRight,
                                                "Browse ${item.path}",
                                                tint = Muted,
                                                modifier =
                                                    Modifier.padding(start = 8.dp).size(17.dp),
                                            )
                                    }
                                }
                            }
                    }
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenWith) { Text("Open with app") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onExtract(true) },
                        enabled = canExtract && model.busy == null,
                    ) {
                        Text("Extract to…")
                    }
                    Button(
                        onClick = { onExtract(false) },
                        enabled =
                            canExtract &&
                                model.busy == null &&
                                model.canCreate &&
                                !model.effectiveSearchRules.active,
                    ) {
                        Text("Extract here")
                    }
                }
            }
        }
    }
}
