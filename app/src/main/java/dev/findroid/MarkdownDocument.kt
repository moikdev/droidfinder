package dev.findroid

import org.commonmark.ext.gfm.strikethrough.*
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.task.list.items.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class MarkdownRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

internal data class MarkdownBlock(
    val kind: String,
    val runs: List<MarkdownRun> = emptyList(),
    val level: Int = 0,
    val indent: Int = 0,
    val marker: String = "",
    val language: String = "",
    val table: List<List<List<MarkdownRun>>> = emptyList(),
)

internal data class MarkdownDocument(val blocks: List<MarkdownBlock>, val limited: Boolean)

internal fun markdownDocument(text: String, check: () -> Unit = {}): MarkdownDocument {
    check()
    val parser =
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                ),
            )
            .maxOpenBlockParsers(64)
            .maxInlineNesting(64)
            .build()
    val root = parser.parse(text)
    check()
    val blocks = ArrayList<MarkdownBlock>()
    var nodes = 0
    var limited = false
    fun visit(): Boolean {
        check()
        nodes++
        if (nodes > 50_000 || blocks.size >= 2000) {
            limited = true
            return false
        }
        return true
    }
    fun inline(parent: Node): List<MarkdownRun> {
        val result = ArrayList<MarkdownRun>()
        fun walk(node: Node, style: MarkdownRun) {
            if (!visit()) return
            val next =
                when (node) {
                    is StrongEmphasis -> style.copy(bold = true)
                    is Emphasis -> style.copy(italic = true)
                    is Strikethrough -> style.copy(strike = true)
                    is Link -> style.copy(link = node.destination)
                    else -> style
                }
            when (node) {
                is Text -> result += next.copy(text = node.literal)
                is Code -> result += next.copy(text = node.literal, code = true)
                is SoftLineBreak -> result += next.copy(text = " ")
                is HardLineBreak -> result += next.copy(text = "\n")
                is HtmlInline -> result += next.copy(text = node.literal, code = true)
                is TaskListItemMarker ->
                    result += next.copy(text = if (node.isChecked) "☑ " else "☐ ")
                is Image -> {
                    result += next.copy(text = "[Image: ")
                    var child = node.firstChild
                    while (child != null && !limited) {
                        walk(child, next.copy(link = null))
                        child = child.next
                    }
                    result += next.copy(text = "]", link = null)
                }
                else -> {
                    var child = node.firstChild
                    while (child != null && !limited) {
                        walk(child, next)
                        child = child.next
                    }
                }
            }
        }
        var child = parent.firstChild
        while (child != null && !limited) {
            walk(child, MarkdownRun(""))
            child = child.next
        }
        return result
    }
    fun walk(parent: Node, indent: Int = 0, marker: String = "", quote: Boolean = false) {
        var node = parent.firstChild
        var pendingMarker = marker
        while (node != null && !limited) {
            if (!visit()) break
            when (val n = node) {
                is Heading ->
                    blocks += MarkdownBlock("heading", inline(n), n.level, indent, pendingMarker)
                is Paragraph -> {
                    val task = n.previous as? TaskListItemMarker
                    val runs =
                        if (task == null) inline(n)
                        else listOf(MarkdownRun(if (task.isChecked) "☑ " else "☐ ")) + inline(n)
                    blocks +=
                        MarkdownBlock(
                            if (quote) "quote" else "paragraph",
                            runs,
                            indent = indent,
                            marker = if (task == null) pendingMarker else "",
                        )
                }
                is FencedCodeBlock ->
                    blocks +=
                        MarkdownBlock(
                            "code",
                            listOf(MarkdownRun(n.literal.trimEnd('\n'))),
                            indent = indent,
                            language = n.info.orEmpty().take(80),
                        )
                is IndentedCodeBlock ->
                    blocks +=
                        MarkdownBlock(
                            "code",
                            listOf(MarkdownRun(n.literal.trimEnd('\n'))),
                            indent = indent,
                        )
                is HtmlBlock -> blocks += MarkdownBlock("html", listOf(MarkdownRun(n.literal)))
                is ThematicBreak -> blocks += MarkdownBlock("rule")
                is BlockQuote -> walk(n, indent + 1, pendingMarker, true)
                is BulletList,
                is OrderedList -> {
                    var item = n.firstChild
                    var number = (n as? OrderedList)?.markerStartNumber ?: 1
                    while (item != null && !limited) {
                        walk(item, indent + 1, if (n is OrderedList) "${number++}." else "•", quote)
                        item = item.next
                    }
                }
                is TableBlock -> {
                    val table = ArrayList<List<List<MarkdownRun>>>()
                    var section = n.firstChild
                    while (section != null && !limited) {
                        var row = section.firstChild
                        while (row != null && !limited) {
                            val cells = ArrayList<List<MarkdownRun>>()
                            var cell = row.firstChild
                            while (cell != null && cells.size < 16 && !limited) {
                                cells += inline(cell)
                                cell = cell.next
                            }
                            if (cell != null || table.size >= 50) {
                                limited = true
                                break
                            }
                            table += cells
                            row = row.next
                        }
                        section = section.next
                    }
                    blocks += MarkdownBlock("table", table = table)
                }
                else -> walk(n, indent, pendingMarker, quote)
            }
            pendingMarker = ""
            node = node.next
        }
    }
    walk(root)
    val bounded = blocks.map { block ->
        var remaining = 16_384
        val runs = buildList {
            for (run in block.runs) {
                if (remaining <= 0) {
                    limited = true
                    break
                }
                val text = run.text.take(remaining)
                if (text.length != run.text.length) limited = true
                remaining -= text.length
                add(
                    run.copy(
                        text =
                            if (block.kind in listOf("code", "html"))
                                text.lineSequence().joinToString("\n") { line ->
                                    if (line.length > 4096) limited = true
                                    line.take(4096)
                                }
                            else text,
                    ),
                )
            }
        }
        var tableCharacters = 0
        val table = buildList {
            for (row in block.table) {
                val count = row.sumOf { cell -> cell.sumOf { it.text.length } }
                if (count > 16_384 - tableCharacters) {
                    limited = true
                    break
                }
                add(row)
                tableCharacters += count
            }
        }
        block.copy(runs = runs, table = table)
    }
    return MarkdownDocument(bounded, limited)
}
