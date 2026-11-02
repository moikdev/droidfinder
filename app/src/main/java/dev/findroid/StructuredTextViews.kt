package dev.findroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@Composable
internal fun SourceReadView(
    lines: List<String>,
    list: androidx.compose.foundation.lazy.LazyListState,
    horizontal: ScrollState,
    wrap: Boolean,
    query: String,
    active: Int?,
    code: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(CanvasBackground)) {
        val lineWidth = maxWidth - 66.dp
        val density = LocalDensity.current
        val viewport = with(density) { maxWidth.toPx() }
        val gutter = with(density) { 50.dp.toPx() }
        var matchBounds by remember(lines, query, active, wrap) { mutableStateOf<Rect?>(null) }
        LaunchedEffect(matchBounds, active) {
            val rect = matchBounds
            if (rect != null && active != null && active in lines.indices) {
                list.scrollToItem(active, if (wrap) (rect.top - 24).toInt().coerceAtLeast(0) else 0)
                if (!wrap)
                    horizontal.scrollTo(
                        (rect.left + gutter - viewport / 3).toInt().coerceAtLeast(0),
                    )
            }
        }
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize()
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))
                    .testTag("source-lines"),
                state = list,
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(lines.size) { index ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (active == index) SelectionSurface else Color.Transparent,
                            )
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            "${index + 1}",
                            Modifier.width(50.dp).padding(end = 12.dp),
                            textAlign = TextAlign.End,
                            color = Muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        Text(
                            sourceText(lines[index], query, code),
                            Modifier.then(
                                if (wrap) Modifier.width(lineWidth)
                                else Modifier.widthIn(min = lineWidth),
                            ),
                            color = Ink,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 20.sp,
                            softWrap = wrap,
                            onTextLayout = { result ->
                                if (index == active && query.isNotBlank()) {
                                    val at = lines[index].indexOf(query, ignoreCase = true)
                                    if (at >= 0) matchBounds = result.getBoundingBox(at)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sourceText(text: String, query: String, code: Boolean): AnnotatedString {
    val blue = Blue
    val muted = Muted
    val ink = Ink
    val selection = SelectionSurface
    val strings = if (CanvasBackground.luminance() < .3f) Color(0xFFA0CEAD) else Color(0xFF376742)
    val numbers = if (CanvasBackground.luminance() < .3f) Color(0xFFC5A1E9) else Color(0xFF8553A6)
    return remember(text, query, code, blue, muted, ink, selection, strings, numbers) {
        buildAnnotatedString {
            append(text)
            if (code) {
                var at = 0
                val keywords =
                    setOf(
                        "true",
                        "false",
                        "null",
                        "fun",
                        "val",
                        "var",
                        "class",
                        "return",
                        "if",
                        "else",
                        "import",
                        "package",
                        "def",
                        "const",
                        "let",
                        "function",
                        "async",
                        "await",
                        "public",
                        "private",
                        "for",
                        "while",
                        "in",
                        "interface",
                        "data",
                        "object",
                        "try",
                        "catch",
                        "throw",
                    )
                while (at < text.length) {
                    val start = at
                    val c = text[at++]
                    when {
                        c == '"' || c == '\'' -> {
                            while (at < text.length) {
                                val next = text[at++]
                                if (next == '\\' && at < text.length) at++ else if (next == c) break
                            }
                            var probe = at
                            while (probe < text.length && text[probe].isWhitespace()) probe++
                            val key = text.getOrNull(probe) == ':'
                            addStyle(SpanStyle(color = if (key) blue else strings), start, at)
                        }
                        text.startsWith("//", start) || c == '#' -> {
                            addStyle(SpanStyle(color = muted), start, text.length)
                            break
                        }
                        c.isLetter() || c == '_' -> {
                            while (
                                at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')
                            ) at++
                            if (text.substring(start, at) in keywords)
                                addStyle(
                                    SpanStyle(color = blue, fontWeight = FontWeight.Medium),
                                    start,
                                    at,
                                )
                        }
                        c.isDigit() -> {
                            while (
                                at < text.length && (text[at].isDigit() || text[at] in ".eE+-")
                            ) at++
                            addStyle(SpanStyle(color = numbers), start, at)
                        }
                    }
                }
            }
            if (query.isNotBlank()) {
                var from = 0
                var count = 0
                while (from < text.length && count++ < 200) {
                    val at = text.indexOf(query, from, ignoreCase = true)
                    if (at < 0) break
                    addStyle(
                        SpanStyle(
                            background = selection,
                            color = ink,
                            fontWeight = FontWeight.Bold,
                        ),
                        at,
                        at + query.length,
                    )
                    from = at + query.length
                }
            }
        }
    }
}

@Composable
internal fun CsvReadView(
    table: CsvDocument,
    header: Boolean,
    list: androidx.compose.foundation.lazy.LazyListState,
    horizontal: ScrollState,
    query: String,
    active: Int?,
) {
    if (table.rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No complete records", color = Muted)
        }
        return
    }
    val density = LocalDensity.current
    LaunchedEffect(active, query) {
        if (active != null && query.isNotBlank()) {
            val column =
                table.rows.getOrNull(active)?.indexOfFirst { it.contains(query, ignoreCase = true) }
                    ?: -1
            if (column >= 0)
                horizontal.animateScrollTo(with(density) { (160.dp * column).roundToPx() })
        }
    }
    val width = 160.dp * table.columns.coerceAtLeast(1)
    Column(Modifier.fillMaxSize().horizontalScroll(horizontal).width(width).testTag("csv-table")) {
        if (header) CsvRow(table.rows.first(), table.columns, true, false, query)
        SelectionContainer {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                val start = if (header) 1 else 0
                items((table.rows.size - start).coerceAtLeast(0)) { offset ->
                    val index = offset + start
                    CsvRow(table.rows[index], table.columns, false, active == index, query)
                }
            }
        }
    }
}

@Composable
private fun CsvRow(
    cells: List<String>,
    columns: Int,
    header: Boolean,
    active: Boolean,
    query: String,
) {
    var detail by remember(cells) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    if (detail != null)
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("Cell value") },
            text = {
                SelectionContainer {
                    Text(
                        detail!!,
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        fontSize = 14.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        context
                            .getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Cell value", detail))
                        detail = null
                    },
                ) {
                    Text("Copy cell")
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Done") } },
        )
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = if (LocalFinderDensity.current.touch) 48.dp else 0.dp)
            .height(IntrinsicSize.Min)
            .background(
                if (active) SelectionSurface else if (header) ToolbarSurface else CanvasBackground,
            ),
    ) {
        repeat(columns) { index ->
            Box(
                Modifier.width(160.dp)
                    .fillMaxHeight()
                    .border(.5.dp, Muted.copy(alpha = .15f))
                    .clickable { detail = cells.getOrElse(index) { "" } }
                    .padding(12.dp),
            ) {
                Text(
                    sourceText(cells.getOrElse(index) { "" }, query, false),
                    color = Ink,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun MarkdownReadView(document: MarkdownDocument) {
    BoxWithConstraints(Modifier.fillMaxSize().background(CanvasBackground)) {
        val padding = if (maxWidth > 720.dp) 64.dp else 22.dp
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize().testTag("markdown-reader"),
                contentPadding = PaddingValues(horizontal = padding, vertical = 28.dp),
            ) {
                items(document.blocks.size) { index ->
                    val block = document.blocks[index]
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(
                                start = (block.indent.coerceAtMost(8) * 16).dp,
                                bottom = 14.dp,
                            ),
                    ) {
                        when (block.kind) {
                            "rule" -> HorizontalDivider(color = Muted.copy(alpha = .2f))
                            "code",
                            "html" ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(ToolbarSurface, RoundedCornerShape(6.dp))
                                        .padding(14.dp),
                                ) {
                                    if (block.language.isNotBlank() || block.kind == "html")
                                        Text(
                                            if (block.kind == "html") "HTML · shown as text"
                                            else block.language,
                                            color = Muted,
                                            fontSize = 10.sp,
                                        )
                                    Text(
                                        block.runs.joinToString("") { it.text },
                                        Modifier.horizontalScroll(rememberScrollState()),
                                        color = Ink,
                                        fontSize = 12.sp,
                                        lineHeight = 19.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            "table" ->
                                Column(
                                    Modifier.horizontalScroll(rememberScrollState())
                                        .width(
                                            (160 *
                                                    block.table
                                                        .maxOfOrNull { it.size }
                                                        .let { it ?: 1 })
                                                .dp,
                                        ),
                                ) {
                                    block.table.forEachIndexed { row, cells ->
                                        CsvRow(
                                            cells.map { it.joinToString("") { run -> run.text } },
                                            block.table.maxOf { it.size },
                                            row == 0,
                                            false,
                                            "",
                                        )
                                    }
                                }
                            else ->
                                Row {
                                    if (block.marker.isNotEmpty())
                                        Text(
                                            block.marker,
                                            Modifier.width(24.dp),
                                            color = Muted,
                                            fontSize = 14.sp,
                                        )
                                    if (block.kind == "quote") {
                                        Box(Modifier.width(3.dp).height(32.dp).background(Blue))
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Text(
                                        markdownText(block.runs),
                                        color = Ink,
                                        fontSize =
                                            if (block.kind == "heading")
                                                when (block.level) {
                                                    1 -> 28.sp
                                                    2 -> 21.sp
                                                    else -> 16.sp
                                                }
                                            else 14.sp,
                                        fontWeight =
                                            if (block.kind == "heading") FontWeight.SemiBold
                                            else FontWeight.Normal,
                                        lineHeight = if (block.kind == "heading") 34.sp else 23.sp,
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun markdownText(runs: List<MarkdownRun>): AnnotatedString {
    val blue = Blue
    val surface = ToolbarSurface
    val context = LocalContext.current
    return buildAnnotatedString {
        runs.forEach { run ->
            withStyle(
                SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) surface else Color.Unspecified,
                    textDecoration =
                        if (run.strike) TextDecoration.LineThrough
                        else if (run.link != null) TextDecoration.Underline else null,
                    color = if (run.link != null) blue else Color.Unspecified,
                ),
            ) {
                val link = run.link
                if (
                    link != null &&
                        Uri.parse(link).scheme?.lowercase() in setOf("http", "https", "mailto")
                ) {
                    withLink(
                        LinkAnnotation.Clickable(
                            link,
                            linkInteractionListener = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(link)),
                                    )
                                }
                            },
                        ),
                    ) {
                        append(run.text)
                    }
                } else append(run.text)
            }
        }
    }
}
