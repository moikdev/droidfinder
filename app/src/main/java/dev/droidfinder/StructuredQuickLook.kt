package dev.droidfinder

import android.view.KeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

internal val Entry.structuredKind: String?
    get() {
        if (folder || isPdfDocument || mediaKind != null) return null
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
        return when {
            extension in setOf("md", "markdown", "mdown") ||
                mime in setOf("text/markdown", "text/x-markdown") -> "Markdown"
            extension in setOf("csv", "tsv") ||
                mime in setOf("text/csv", "text/tab-separated-values") -> "Table"
            extension == "json" || mime == "application/json" || mime.endsWith("+json") -> "JSON"
            fileType(extension, mime).group == FileTypeGroup.CODE -> "Code"
            extension in setOf("txt", "log", "srt", "vtt", "ini", "cfg", "properties") ||
                mime.startsWith("text/") -> "Text"
            else -> null
        }
    }

private data class StructuredView(
    val text: String,
    val lines: List<String>,
    val markdown: MarkdownDocument? = null,
    val table: CsvDocument? = null,
    val notice: String? = null,
)

@Composable
fun StructuredQuickLook(entry: Entry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val gateway = remember { DocumentAccess(context.applicationContext) }
    val kind = entry.structuredKind ?: "Text"
    val touch = LocalFinderDensity.current.touch
    val identity = listOf(entry.path, entry.name, entry.modified, entry.size, entry.mimeType)
    var mode by
        remember(identity) {
            mutableStateOf(if (kind in listOf("Markdown", "Table", "JSON")) "preview" else "source")
        }
    var encoding by remember(identity) { mutableStateOf<String?>(null) }
    var limit by remember(identity) { mutableIntStateOf(512 * 1024) }
    var retry by remember(identity) { mutableIntStateOf(0) }
    var stopped by remember(identity, encoding, limit, retry) { mutableStateOf(false) }
    var encodingMenu by remember { mutableStateOf(false) }
    var delimiterMenu by remember { mutableStateOf(false) }
    var delimiter by
        remember(identity) { mutableStateOf<Char?>(if (entry.extension == "tsv") '\t' else null) }
    var header by remember(identity) { mutableStateOf(true) }
    var wrap by remember(identity) { mutableStateOf(true) }
    var search by remember(identity) { mutableStateOf(false) }
    var query by remember(identity) { mutableStateOf("") }
    var active by remember(identity) { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    var focusRequest by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keys = LocalPeekKeys.current
    val copyScope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    val control = remember(identity, encoding, limit, retry) { ListingControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    val loaded by
        produceState<Result<TextDocument>?>(null, control) {
            value = null
            try {
                val document =
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext().job
                        gateway.input(entry.path, control).use { input ->
                            readTextDocument(input, limit, encoding) {
                                control.check()
                                job.ensureActive()
                            }
                        }
                    }
                value = Result.success(document)
            } catch (error: Exception) {
                if (error is CancellationException) return@produceState
                if (!stopped) value = Result.failure(error)
            }
        }
    val document = loaded?.getOrNull()
    val view by
        produceState<StructuredView?>(null, document, mode, delimiter, control) {
            value = null
            if (document == null) return@produceState
            value =
                withContext(Dispatchers.Default) {
                    val job = currentCoroutineContext().job
                    val check = {
                        job.ensureActive()
                        control.check()
                    }
                    var text = document.text
                    var markdown: MarkdownDocument? = null
                    var table: CsvDocument? = null
                    var notice: String? = null
                    if (mode == "preview")
                        try {
                            when (kind) {
                                "Markdown" -> markdown = markdownDocument(text, check)
                                "Table" ->
                                    table =
                                        parseCsv(
                                            text,
                                            delimiter ?: csvDelimiter(text),
                                            document.clipped,
                                            check,
                                        )
                                "JSON" ->
                                    if (document.clipped)
                                        notice =
                                            "Load the complete text to format JSON. Source is shown below."
                                    else text = formatJson(text, check)
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            notice =
                                if (kind == "JSON")
                                    "Couldn't format JSON: ${failure.message.orEmpty().take(180)}. Source is shown below."
                                else "Couldn't format this document. Source is shown below."
                        }
                    val lines = text.lineSequence().take(20_001).map { it.take(4096) }.toList()
                    if (
                        lines.size > 20_000 ||
                            text.lineSequence().take(20_000).any { it.length > 4096 }
                    )
                        notice =
                            listOfNotNull(
                                    notice,
                                    "Source display limited to 20,000 lines and 4,096 characters per line. Copy includes all loaded text.",
                                )
                                .joinToString(" ")
                    StructuredView(text, lines.take(20_000), markdown, table, notice)
                }
        }
    val list = rememberLazyListState()
    val horizontal = rememberScrollState()
    val matches by
        produceState<List<Int>>(emptyList(), view, query, header) {
            value = emptyList()
            val current = view
            if (query.isNotBlank() && current != null)
                value =
                    withContext(Dispatchers.Default) {
                        val job = currentCoroutineContext().job
                        val rows =
                            current.table?.rows?.map { it.joinToString("\t") } ?: current.lines
                        rows.indices
                            .filter {
                                job.ensureActive()
                                rows[it].contains(query, ignoreCase = true)
                            }
                            .take(2000)
                    }
        }
    LaunchedEffect(view) {
        list.scrollToItem(0)
        active = 0
    }
    LaunchedEffect(matches, active) {
        if (matches.isNotEmpty()) {
            val row = matches[Math.floorMod(active, matches.size)]
            list.animateScrollToItem(
                if (view?.table != null && header) (row - 1).coerceAtLeast(0) else row,
            )
        }
    }
    LaunchedEffect(focusRequest) { if (focusRequest > 0 && search) focus.requestFocus() }
    fun find() {
        if (kind == "Markdown") mode = "source"
        search = true
        focusRequest++
    }
    fun finishEditing() {
        keys?.editing = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val handle by
        rememberUpdatedState<(KeyEvent) -> Boolean>(
            { event ->
                when {
                    event.action != KeyEvent.ACTION_DOWN -> false
                    (event.isCtrlPressed || event.isMetaPressed) &&
                        event.keyCode == KeyEvent.KEYCODE_F -> {
                        find()
                        true
                    }
                    event.keyCode == KeyEvent.KEYCODE_F3 && search -> {
                        active += if (event.isShiftPressed) -1 else 1
                        true
                    }
                    event.keyCode == KeyEvent.KEYCODE_ESCAPE && search -> {
                        search = false
                        finishEditing()
                        true
                    }
                    else -> false
                }
            },
        )
    DisposableEffect(keys) {
        keys?.handle = { handle(it) }
        onDispose {
            keys?.handle = null
            keys?.editing = false
        }
    }
    Column(modifier.testTag("structured-preview")) {
        Row(
            Modifier.fillMaxWidth()
                .height(if (touch) 48.dp else 46.dp)
                .background(ToolbarSurface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kind in listOf("Markdown", "JSON", "Table")) {
                TextButton(
                    onClick = {
                        mode = "preview"
                        search = false
                        finishEditing()
                    },
                ) {
                    Text(
                        if (kind == "JSON") "Formatted"
                        else if (kind == "Table") "Table" else "Read",
                        color = if (mode == "preview") Blue else Muted,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { mode = "source" }) {
                    Text("Source", color = if (mode == "source") Blue else Muted, fontSize = 12.sp)
                }
            } else Text(kind, Modifier.padding(start = 10.dp), fontSize = 12.sp, color = Muted)
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { encodingMenu = true }) {
                    Text(document?.encoding ?: encoding ?: "Auto", color = Muted, fontSize = 11.sp)
                }
                DropdownMenu(encodingMenu, { encodingMenu = false }) {
                    listOf(null, "UTF-8", "UTF-16LE", "UTF-16BE", "windows-1252").forEach { value ->
                        DropdownMenuItem(
                            text = { Text(value ?: "Auto (BOM / UTF-8)") },
                            onClick = {
                                encoding = value
                                encodingMenu = false
                            },
                            leadingIcon = {
                                if (encoding == value) Icon(Icons.Outlined.Check, null)
                            },
                        )
                    }
                }
            }
            if (mode == "source" || kind == "JSON")
                ToolButton(
                    Icons.AutoMirrored.Outlined.WrapText,
                    "Wrap lines",
                    { wrap = !wrap },
                    active = wrap,
                    compact = true,
                )
            ToolButton(
                Icons.Outlined.Search,
                "Find in document",
                { find() },
                enabled = view != null,
                compact = true,
            )
            ToolButton(
                Icons.Outlined.ContentCopy,
                "Copy loaded text",
                {
                    val text = view?.text ?: document?.text.orEmpty()
                    copyScope.launch {
                        copying = true
                        try {
                            copyPreviewText(context, entry.name, text)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            android.widget.Toast.makeText(
                                    context,
                                    "Couldn't copy this text",
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        } finally {
                            copying = false
                        }
                    }
                },
                enabled = document != null && !copying,
                compact = true,
            )
        }
        if (search)
            Row(
                Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    query,
                    {
                        query = it
                        active = 0
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    placeholder = {
                        Text(
                            if (view?.table != null) "Find in table" else "Find in source",
                            fontSize = 12.sp,
                        )
                    },
                    modifier =
                        Modifier.weight(1f)
                            .height(50.dp)
                            .testTag("preview-find")
                            .focusRequester(focus)
                            .onFocusChanged { keys?.editing = it.isFocused },
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onSearch = {
                                finishEditing()
                                active = 0
                            },
                        ),
                )
                Text(
                    if (matches.isEmpty()) "No matches"
                    else
                        "${Math.floorMod(active, matches.size) + 1} / ${matches.size}${if (matches.size == 2000) "+" else ""}",
                    Modifier.padding(horizontal = 8.dp),
                    color = Muted,
                    fontSize = 11.sp,
                )
                ToolButton(
                    Icons.Outlined.KeyboardArrowUp,
                    "Previous text match",
                    { active-- },
                    enabled = matches.isNotEmpty(),
                    compact = true,
                )
                ToolButton(
                    Icons.Outlined.KeyboardArrowDown,
                    "Next text match",
                    { active++ },
                    enabled = matches.isNotEmpty(),
                    compact = true,
                )
                ToolButton(
                    Icons.Outlined.Close,
                    "Close text search",
                    {
                        search = false
                        finishEditing()
                    },
                    compact = true,
                )
            }
        val table = view?.table
        if (table != null)
            Row(
                Modifier.fillMaxWidth()
                    .height(if (touch) 48.dp else 42.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { delimiterMenu = true }) {
                        Text("Delimiter: ${delimiterLabel(delimiter)}", fontSize = 11.sp)
                    }
                    DropdownMenu(delimiterMenu, { delimiterMenu = false }) {
                        listOf(null, ',', ';', '\t', '|').forEach { value ->
                            DropdownMenuItem(
                                text = { Text(delimiterLabel(value)) },
                                onClick = {
                                    delimiter = value
                                    delimiterMenu = false
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.height(if (touch) 48.dp else 38.dp)
                        .toggleable(header, role = Role.Checkbox, onValueChange = { header = it })
                        .semantics { contentDescription = "First row is header" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(header, null)
                    Text("First row is header", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${(table.rows.size - if (header && table.rows.isNotEmpty()) 1 else 0).coerceAtLeast(0)} rows · ${table.columns} columns",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        val notice =
            listOfNotNull(
                view?.notice,
                table?.notice,
                if (view?.markdown?.limited == true)
                    "Markdown display limit reached. Source remains available."
                else null,
            )
        notice.forEach {
            Text(
                it,
                Modifier.fillMaxWidth()
                    .background(SelectionSurface)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                color = Ink,
                fontSize = 11.sp,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                stopped ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Preview stopped", color = Muted)
                        TextButton(onClick = { retry++ }) { Text("Restart preview") }
                    }
                loaded?.isFailure == true ->
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val error = loaded!!.exceptionOrNull()
                        Text(
                            if (error is java.nio.charset.CharacterCodingException)
                                "Couldn't decode this text. Choose another encoding above."
                            else if (
                                error is IllegalArgumentException || error is IllegalStateException
                            )
                                error.message.orEmpty()
                            else "Couldn't read this document. Check its access and try again.",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                        TextButton(onClick = { retry++ }) { Text("Try again") }
                    }
                view == null ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        TextButton(
                            onClick = {
                                stopped = true
                                control.cancel()
                            },
                        ) {
                            Text("Stop preview")
                        }
                    }
                view!!.markdown != null -> MarkdownReadView(view!!.markdown!!)
                table != null ->
                    CsvReadView(
                        table,
                        header,
                        list,
                        horizontal,
                        query,
                        matches.getOrNull(
                            if (matches.isEmpty()) 0 else Math.floorMod(active, matches.size),
                        ),
                    )
                else ->
                    SourceReadView(
                        view!!.lines,
                        list,
                        horizontal,
                        wrap,
                        query,
                        matches.getOrNull(
                            if (matches.isEmpty()) 0 else Math.floorMod(active, matches.size),
                        ),
                        kind in listOf("JSON", "Code"),
                    )
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .height(
                    if (touch && document?.clipped == true && limit < 2 * 1024 * 1024) 48.dp
                    else 36.dp,
                )
                .background(ToolbarSurface)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (document?.clipped == true)
                    "First ${if (limit < 1024 * 1024) "512 KiB" else "2 MiB"} loaded · incomplete"
                else if (document?.text?.isEmpty() == true) "Empty document"
                else if (document != null) "${document.text.length} characters loaded"
                else "Text preview",
                color = Muted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            if (document?.clipped == true && limit < 2 * 1024 * 1024)
                TextButton(onClick = { limit = 2 * 1024 * 1024 }) {
                    Text("Load up to 2 MiB", fontSize = 11.sp)
                }
        }
    }
}

private fun delimiterLabel(char: Char?) =
    when (char) {
        ',' -> "Comma"
        ';' -> "Semicolon"
        '\t' -> "Tab"
        '|' -> "Pipe"
        else -> "Auto"
    }
