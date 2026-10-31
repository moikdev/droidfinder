package dev.droidfinder

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StructuredTextTest {
    private fun text(bytes: ByteArray, limit: Int = 1024, encoding: String? = null) =
        readTextDocument(ByteArrayInputStream(bytes), limit, encoding) {}

    @Test
    fun encodingsBomAndTruncatedCodePoints() {
        assertEquals("Ocean 🐳", text("\uFEFFOcean 🐳".toByteArray()).text)
        assertEquals(
            "Ocean 🐳",
            text(byteArrayOf(-1, -2) + "Ocean 🐳".toByteArray(Charsets.UTF_16LE)).text,
        )
        assertEquals("café", text(byteArrayOf(99, 97, 102, -23), encoding = "windows-1252").text)
        val prefix = text("ab🐳cd".toByteArray(), 4)
        assertEquals("ab", prefix.text)
        assertTrue(prefix.clipped)
        assertFalse(text("abc".toByteArray(), 3).clipped)
        assertThrows(CharacterCodingException::class.java) { text(byteArrayOf(-1, 0)) }
        assertThrows(IllegalArgumentException::class.java) { text(byteArrayOf(0, 1)) }
    }

    @Test
    fun quotedMultilineCsvAndTrailingFields() {
        val parsed =
            parseCsv(
                "Name,Notes,Empty\n\"Ocean, light\",\"line one\nline \"\"two\"\"\",\nLast,plain,",
                ',',
            )
        assertNull(parsed.notice)
        assertEquals(listOf("Ocean, light", "line one\nline \"two\"", ""), parsed.rows[1])
        assertEquals(listOf("Last", "plain", ""), parsed.rows[2])
        assertEquals(';', csvDelimiter("Name;\"lots,of,commas\";Value\nA;B;C"))
        assertEquals('\t', csvDelimiter("Name\tSize\tKind"))
    }

    @Test
    fun incompleteAndMalformedCsvDoNotInventCompleteRows() {
        assertEquals(1, parseCsv("a,b\ncut,part", ',', clipped = true).rows.size)
        val bad = parseCsv("a,b\n\"unclosed", ',')
        assertNotNull(bad.notice)
        assertEquals(1, bad.rows.size)
        assertNotNull(parseCsv("\"closed\"x,value", ',').notice)
        val cols = parseCsv((0..65).joinToString(","), ',')
        assertTrue(cols.rows.isEmpty())
        assertNotNull(cols.notice)
        val huge = parseCsv("x".repeat(16_385), ',')
        assertTrue(huge.rows.isEmpty())
        assertNotNull(huge.notice)
        assertTrue(parseCsv("", ',').rows.isEmpty())
    }

    @Test
    fun strictJsonPreservesDuplicateKeysAndNumberSpelling() {
        val formatted = formatJson("{\"x\":1e+09,\"x\":null,\"list\":[true,\"a\\nb\"]}")
        assertTrue(formatted.contains("1e+09"))
        assertEquals(2, Regex("\"x\"").findAll(formatted).count())
        for (invalid in
            listOf(
                "{a:1}",
                "[1,]",
                "true false",
                "01",
                "/* x */ {}",
                "",
                "[".repeat(66) + "0" + "]".repeat(66),
            )) assertThrows(Exception::class.java) { formatJson(invalid) }
    }

    @Test
    fun markdownSupportsNestedListsTablesTasksAndLiteralHtml() {
        val doc =
            markdownDocument(
                "# Ocean light\n\n**Bold** and *italic*, `code`, [link](https://example.com).\n\n3. First\n   - Nested\n\n- [x] Done\n\nA | B\n--- | ---\n1 | 2\n\n<script>alert('x')</script>",
            )
        assertFalse(doc.limited)
        assertEquals("heading", doc.blocks.first().kind)
        assertTrue(doc.blocks.flatMap { it.runs }.any { it.bold && it.text == "Bold" })
        assertTrue(doc.blocks.any { it.marker == "3." })
        assertTrue(doc.blocks.any { it.kind == "table" && it.table.size == 2 })
        assertTrue(
            doc.blocks.any { it.kind == "html" && it.runs.first().text.contains("<script>") },
        )
        assertTrue(doc.blocks.flatMap { it.runs }.any { "☑" in it.text })
    }

    @Test
    fun pathologicalMarkdownRemainsBoundedAndSignalsItsLimit() {
        val doc = markdownDocument("**word** ".repeat(12_000))
        assertTrue(doc.limited)
        assertTrue(doc.blocks.flatMap { it.runs }.sumOf { it.text.length } <= 16_384)
        assertTrue(doc.blocks.flatMap { it.runs }.none { it.text.isEmpty() })
    }

    @Test
    fun oversizedMarkdownTableOmitsTheRecordInsteadOfPublishingPartialCells() {
        val doc = markdownDocument("A|B\n---|---\n" + "x".repeat(17_000) + "|value\n")
        assertTrue(doc.limited)
        val table = doc.blocks.first { it.kind == "table" }.table
        assertEquals(1, table.size)
        assertEquals("A", table.first().first().joinToString("") { it.text })
    }

    @Test
    fun processingHonorsCancellationCheckpoints() {
        val stop = { throw CancellationException("stop") }
        assertThrows(CancellationException::class.java) {
            readTextDocument(ByteArrayInputStream(ByteArray(20)), 10, null, stop)
        }
        assertThrows(CancellationException::class.java) { parseCsv("a,b", ',', check = stop) }
        assertThrows(CancellationException::class.java) { formatJson("{}", stop) }
        assertThrows(CancellationException::class.java) { markdownDocument("# Test", stop) }
    }
}
