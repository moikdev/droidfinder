package dev.findroid

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object FileComparisonPolicy {
    const val textBytes = 512 * 1024
    const val lines = 2000
    const val lineCharacters = 8192
    const val imageBytes = 64L * 1024 * 1024
    const val imagePixels = 1_048_576L
    const val imageEdge = 2048
    const val timeoutMillis = 15_000L
}

internal data class ComparisonLine(val text: String, val ending: String) {
    val endingLabel get() = when (ending) {
        "\n" -> "LF"
        "\r\n" -> "CRLF"
        "\r" -> "CR"
        else -> "No line ending"
    }
}

internal data class ComparisonText(val lines: List<ComparisonLine>, val encoding: String)

/** Strict decoding and complete reads: a truncated prefix must never appear identical. */
internal fun readComparisonText(input: InputStream, check: () -> Unit = {}): ComparisonText {
    val bytes = ByteArray(FileComparisonPolicy.textBytes + 1)
    var size = 0
    while (size < bytes.size) {
        check()
        val count = input.read(bytes, size, minOf(8192, bytes.size - size))
        check()
        if (count < 0) break
        if (count > 0) size += count
    }
    require(size <= FileComparisonPolicy.textBytes) { "Text comparison supports complete files up to 512 KiB each. Choose smaller files." }
    fun starts(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
    val (encoding, skip) = when {
        starts(0, 0, 254, 255) || starts(255, 254, 0, 0) -> error("UTF-32 text is not supported. Use UTF-8 or BOM-marked UTF-16 copies.")
        starts(239, 187, 191) -> "UTF-8 BOM" to 3
        starts(255, 254) -> "UTF-16LE" to 2
        starts(254, 255) -> "UTF-16BE" to 2
        else -> "UTF-8" to 0
    }
    val decoder = Charset.forName(encoding.removeSuffix(" BOM")).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = decoder.decode(ByteBuffer.wrap(bytes, skip, size - skip)).toString()
    require(text.none { it.isISOControl() && it !in "\n\r\t" }) { "This file contains binary or unsupported control characters. Choose plain text files." }
    val lines = ArrayList<ComparisonLine>()
    var start = 0
    var at = 0
    fun line(end: Int, ending: String) {
        require(lines.size < FileComparisonPolicy.lines) { "Text comparison supports up to 2,000 lines per file. Choose smaller files." }
        require(end - start <= FileComparisonPolicy.lineCharacters) { "A line exceeds the 8,192 character comparison limit. Choose smaller text files." }
        lines += ComparisonLine(text.substring(start, end), ending)
    }
    while (at < text.length) {
        if (at % 1024 == 0) check()
        if (text[at] == '\r' || text[at] == '\n') {
            val end = at
            val ending = if (text[at] == '\r' && text.getOrNull(at + 1) == '\n') "\r\n" else text[at].toString()
            at += ending.length
            line(end, ending)
            start = at
        } else at++
    }
    if (start < text.length) line(text.length, "")
    check()
    return ComparisonText(lines, encoding)
}

internal data class TextDifferenceRow(val left: Int?, val right: Int?, val changed: Boolean)

internal data class TextComparison(val left: ComparisonText, val right: ComparisonText, val rows: List<TextDifferenceRow>) {
    val changes = rows.indices.filter { rows[it].changed && (it == 0 || !rows[it - 1].changed) }
    val removed = rows.count { it.changed && it.left != null }
    val added = rows.count { it.changed && it.right != null }
}

/** A bounded LCS, at most 4M short cells (8 MB). Prefix/suffix trimming avoids common quadratic work. */
internal fun compareText(left: ComparisonText, right: ComparisonText, check: () -> Unit = {}): TextComparison {
    require(left.lines.size <= FileComparisonPolicy.lines && right.lines.size <= FileComparisonPolicy.lines)
    check()
    val a = left.lines
    val b = right.lines
    var prefix = 0
    while (prefix < minOf(a.size, b.size) && a[prefix] == b[prefix]) { check(); prefix++ }
    var suffix = 0
    while (suffix < minOf(a.size, b.size) - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) { check(); suffix++ }
    val n = a.size - prefix - suffix
    val m = b.size - prefix - suffix
    val stride = m + 1
    val identities = HashMap<ComparisonLine, Int>()
    fun identity(line: ComparisonLine) = identities.getOrPut(line) { identities.size }
    val ai = IntArray(n) { identity(a[prefix + it]) }
    val bi = IntArray(m) { identity(b[prefix + it]) }
    val table = ShortArray((n + 1) * stride)
    for (i in n - 1 downTo 0) {
        check()
        for (j in m - 1 downTo 0) {
            if (j % 256 == 0) check()
            table[i * stride + j] = if (ai[i] == bi[j])
                (table[(i + 1) * stride + j + 1] + 1).toShort()
            else maxOf(table[(i + 1) * stride + j], table[i * stride + j + 1])
        }
    }
    val rows = ArrayList<TextDifferenceRow>()
    repeat(prefix) { rows += TextDifferenceRow(it, it, false) }
    var i = 0
    var j = 0
    while (i < n || j < m) {
        check()
        if (i < n && j < m && ai[i] == bi[j]) {
            rows += TextDifferenceRow(prefix + i++, prefix + j++, false)
        } else {
            val removed = ArrayList<Int>()
            val added = ArrayList<Int>()
            while ((i < n || j < m) && !(i < n && j < m && ai[i] == bi[j])) {
                check()
                if (i < n && (j == m || table[(i + 1) * stride + j] >= table[i * stride + j + 1])) removed += prefix + i++
                else added += prefix + j++
            }
            repeat(maxOf(removed.size, added.size)) { rows += TextDifferenceRow(removed.getOrNull(it), added.getOrNull(it), true) }
        }
    }
    repeat(suffix) { rows += TextDifferenceRow(a.size - suffix + it, b.size - suffix + it, false) }
    return TextComparison(left, right, rows).also { check() }
}

/** Human-readable report, not an applyable patch. Line endings and encodings stay explicit. */
internal fun TextComparison.report(leftName: String, rightName: String): String = buildString {
    append("Left: ").append(leftName).append(" · ").append(left.encoding).append('\n')
    append("Right: ").append(rightName).append(" · ").append(right.encoding).append('\n')
    append("${changes.size} difference ${if (changes.size == 1) "group" else "groups"} · $removed removed · $added added\n")
    rows.filter { it.changed }.forEach { row ->
        row.left?.let { append("− L${it + 1} [${left.lines[it].endingLabel}] ").append(left.lines[it].text).append('\n') }
        row.right?.let { append("+ R${it + 1} [${right.lines[it].endingLabel}] ").append(right.lines[it].text).append('\n') }
    }
}
