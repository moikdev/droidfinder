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

    fun contains(reader: Reader, check: () -> Unit, binary: () -> Unit): Boolean {
        if (pattern.isEmpty()) return true
        val buffer = CharArray(8192)
        var matched = 0
        while (true) {
            check()
            val count = reader.read(buffer)
            if (count < 0) return false
            if (count == 0) continue
            for (at in 0 until count) {
                if (at % 1024 == 0) check()
                if (buffer[at] == '\u0000') {
                    binary()
                    return false
                }
                val value = fold(buffer[at])
                while (matched > 0 && value != pattern[matched]) matched = prefix[matched - 1]
                if (value == pattern[matched]) matched++
                if (matched == pattern.size) return true
            }
        }
    }
}
