package dev.droidfinder

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal data class TextDocument(val text: String, val encoding: String, val clipped: Boolean)

/** Reads one extra byte so incomplete results never rely on provider size metadata. */
internal fun readTextDocument(
    input: InputStream,
    limit: Int,
    encoding: String?,
    check: () -> Unit,
): TextDocument {
    val buffer = ByteArray(limit + 1)
    var count = 0
    while (count < buffer.size) {
        check()
        val read = input.read(buffer, count, minOf(8192, buffer.size - count))
        check()
        if (read < 0) break
        if (read > 0) count += read
    }
    val clipped = count > limit
    val size = minOf(count, limit)
    fun prefix(vararg values: Int) =
        size >= values.size && values.indices.all { buffer[it].toInt() and 255 == values[it] }
    val bom =
        when {
            prefix(0, 0, 254, 255) || prefix(255, 254, 0, 0) ->
                error("UTF-32 is not supported in this preview. Open with another app.")
            prefix(239, 187, 191) -> "UTF-8" to 3
            prefix(255, 254) -> "UTF-16LE" to 2
            prefix(254, 255) -> "UTF-16BE" to 2
            else -> "UTF-8" to 0
        }
    val charset = Charset.forName(encoding ?: bom.first)
    val skip = if (encoding == null || charset.name().equals(bom.first, true)) bom.second else 0
    val bytes = ByteBuffer.wrap(buffer, skip, (size - skip).coerceAtLeast(0))
    val chars = CharBuffer.allocate(size + 1)
    val decoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    decoder.decode(bytes, chars, !clipped).throwExceptionIfError()
    if (!clipped) decoder.flush(chars).throwExceptionIfError()
    chars.flip()
    val text = chars.toString()
    require('\u0000' !in text) {
        "This file contains binary data. Choose another encoding or open it with another app."
    }
    check()
    return TextDocument(text.replace("\r\n", "\n").replace('\r', '\n'), charset.name(), clipped)
}

private fun java.nio.charset.CoderResult.throwExceptionIfError() {
    if (isError) throwException()
}

internal data class CsvDocument(
    val rows: List<List<String>>,
    val columns: Int,
    val notice: String? = null,
)

internal fun csvDelimiter(text: String): Char {
    val counts = linkedMapOf(',' to 0, '\t' to 0, ';' to 0, '|' to 0)
    var quoted = false
    var at = 0
    while (at < minOf(text.length, 32_768)) {
        val char = text[at++]
        if (char == '"') {
            if (quoted && text.getOrNull(at) == '"') at++ else quoted = !quoted
        } else if (!quoted) {
            if (char == '\n') break
            if (char in counts) counts[char] = counts.getValue(char) + 1
        }
    }
    return counts.maxBy { it.value }.key
}

/**
 * RFC 4180-style quoted records; a partial final record is omitted, never presented as complete.
 */
internal fun parseCsv(
    text: String,
    delimiter: Char,
    clipped: Boolean = false,
    check: () -> Unit = {},
): CsvDocument {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var afterQuote = false
    var line = 1
    var cells = 0
    var columns = 0
    var recordStarted = false
    fun result(notice: String?) = CsvDocument(rows, columns, notice)
    fun cell(): Boolean {
        if (row.size >= 64 || cells >= 100_000) return false
        row.add(field.toString())
        field.setLength(0)
        afterQuote = false
        cells++
        return true
    }
    var at = 0
    while (at < text.length) {
        if (at % 1024 == 0) check()
        val c = text[at++]
        if (c == '\n') line++
        if (quoted) {
            if (c == '"') {
                if (text.getOrNull(at) == '"') {
                    field.append('"')
                    at++
                } else {
                    quoted = false
                    afterQuote = true
                }
            } else field.append(c)
        } else
            when {
                c == delimiter -> {
                    if (!cell())
                        return result(
                            "Table limited to 64 columns and 100,000 cells; the last record was omitted.",
                        )
                    recordStarted = true
                }
                c == '\n' -> {
                    if (!cell()) return result("Table limit reached; the last record was omitted.")
                    columns = maxOf(columns, row.size)
                    rows.add(row)
                    row = ArrayList()
                    recordStarted = false
                    if (rows.size >= 5000 && at < text.length)
                        return result("Table limited to the first 5,000 records.")
                }
                afterQuote ->
                    return result(
                        "Unexpected character after a closing quote near line $line. The last record was omitted.",
                    )
                c == '"' -> {
                    if (field.isNotEmpty())
                        return result(
                            "Unexpected quote near line $line. The last record was omitted.",
                        )
                    quoted = true
                    recordStarted = true
                }
                else -> {
                    field.append(c)
                    recordStarted = true
                }
            }
        if (field.length > 16_384)
            return result("A cell exceeds 16,384 characters; the last record was omitted.")
    }
    check()
    if (clipped) return result("Only complete records in the loaded text are shown.")
    if (quoted) return result("Unclosed quoted field near line $line. The last record was omitted.")
    if (recordStarted || afterQuote || field.isNotEmpty() || row.isNotEmpty()) {
        if (!cell()) return result("Table limit reached; the last record was omitted.")
        columns = maxOf(columns, row.size)
        rows.add(row)
    }
    return result(null)
}

/**
 * Streaming JSON validation/formatting preserves field order, duplicate keys and number spelling.
 */
internal fun formatJson(text: String, check: () -> Unit = {}): String {
    val output = StringWriter()
    JsonReader(StringReader(text)).use { reader ->
        reader.strictness = Strictness.STRICT
        reader.nestingLimit = 64
        val writer =
            JsonWriter(output).apply {
                strictness = Strictness.STRICT
                setIndent("  ")
            }
        var tokens = 0
        while (true) {
            check()
            if (++tokens > 100_000 || output.buffer.length > 4 * 1024 * 1024)
                error("JSON formatting limit reached. Use Source to read this file.")
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    writer.beginObject()
                }
                JsonToken.END_OBJECT -> {
                    reader.endObject()
                    writer.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    writer.beginArray()
                }
                JsonToken.END_ARRAY -> {
                    reader.endArray()
                    writer.endArray()
                }
                JsonToken.NAME -> writer.name(reader.nextName())
                JsonToken.STRING -> writer.value(reader.nextString())
                JsonToken.NUMBER -> writer.jsonValue(reader.nextString())
                JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                JsonToken.NULL -> {
                    reader.nextNull()
                    writer.nullValue()
                }
                JsonToken.END_DOCUMENT -> break
            }
        }
        require(tokens > 1) { "Empty JSON document" }
        writer.close()
    }
    return output.toString()
}
