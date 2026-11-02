package dev.findroid

import java.io.Reader

/** Linear, bounded-memory text matching; a match may cross any decoder/read boundary. */
internal class StreamingText(needle: String) {
    private fun fold(value: Char) = value.uppercaseChar().lowercaseChar()

    private val pattern = needle.map(::fold).toCharArray()
    private val prefix =
        IntArray(pattern.size).also { table ->
            var previous = 0
            for (at in 1 until pattern.size) {
                while (previous > 0 && pattern[at] != pattern[previous]) previous =
                    table[previous - 1]
                if (pattern[at] == pattern[previous]) previous++
                table[at] = previous
            }
        }

    fun contains(reader: Reader, check: () -> Unit, binary: () -> Unit): Boolean =
        find(reader, check, binary) != null

    /** The small ring captures evidence during this read; explaining a result never reopens it. */
    fun find(reader: Reader, check: () -> Unit, binary: () -> Unit): ContentSnippet? {
        if (pattern.isEmpty()) return ContentSnippet("", emptyList(), false, false)
        val buffer = CharArray(8192)
        val ring = CharArray(192)
        var seen = 0L
        var matched = 0
        while (true) {
            check()
            val count = reader.read(buffer)
            if (count < 0) return null
            if (count == 0) continue
            for (at in 0 until count) {
                if (at % 1024 == 0) check()
                if (buffer[at] == '\u0000') {
                    binary()
                    return null
                }
                ring[(seen % ring.size).toInt()] = buffer[at]
                seen++
                val value = fold(buffer[at])
                while (matched > 0 && value != pattern[matched]) matched = prefix[matched - 1]
                if (value == pattern[matched]) matched++
                if (matched == pattern.size) {
                    val matchLength = minOf(pattern.size, 128)
                    val before = if (pattern.size > 128) 0 else minOf(48L, (seen - pattern.size).coerceAtLeast(0)).toInt()
                    val length = matchLength + before
                    var text = buildString {
                        for (offset in length downTo 1) append(ring[((seen - offset) % ring.size).toInt()])
                    }
                    var start = before
                    // Never cut a surrogate pair at the edge of the bounded snippet.
                    if (text.firstOrNull()?.isLowSurrogate() == true) {
                        text = text.drop(1)
                        start = (start - 1).coerceAtLeast(0)
                    }
                    val matchEnd = text.length
                    var after = at + 1
                    while (after < count && after <= at + 48 && buffer[after] != '\u0000') {
                        text += buffer[after++]
                    }
                    if (text.lastOrNull()?.isHighSurrogate() == true) text = text.dropLast(1)
                    text = text.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }.joinToString("")
                    return ContentSnippet(
                        text,
                        listOf(MatchRange(start.coerceAtMost(text.length), matchEnd.coerceAtMost(text.length))),
                        seen > length,
                        after < count || count == buffer.size,
                    )
                }
            }
        }
    }
}
