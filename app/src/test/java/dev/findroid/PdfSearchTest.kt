package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class PdfSearchTest {
    @Test
    fun whitespaceAndCaseMatchesKeepOffsetsIntoTheOriginalText() {
        val text = "Preface\nOcean  \n light is here. OCEAN\tLIGHT returns."
        val found = pdfMatches(3, text, "ocean light")
        assertEquals(2, found.size)
        assertEquals(
            listOf("Ocean  \n light", "OCEAN\tLIGHT"),
            found.map { text.substring(it.start, it.end) },
        )
        assertTrue(found.all { it.page == 3 })
        assertFalse(found.first().snippet.contains('\n'))
    }

    @Test
    fun unicodeLiteralAndRepeatedMatchesDoNotUseRegexSyntax() {
        val text = "Résumé [x]. résumé [x]."
        val found = pdfMatches(0, text, "RÉSUMÉ [x]")
        assertEquals(2, found.size)
        assertEquals("Résumé [x]", text.substring(found[0].start, found[0].end))
        assertTrue(pdfMatches(0, text, "").isEmpty())
    }

    @Test
    fun largeResultsAreBoundedAndSnippetsKeepContext() {
        val found = pdfMatches(1, "hit ".repeat(5000), "hit", 2000)
        assertEquals(2000, found.size)
        assertTrue(found.all { it.snippet.length <= 111 })
        assertTrue(found.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test
    fun providerPdfMimeDoesNotRequireAFilenameExtension() {
        assertTrue(
            Entry(java.io.File("/no-extension"), folder = false, mimeType = "application/pdf")
                .isPdfDocument,
        )
        assertTrue(
            Entry(
                    java.io.File("/misnamed.jpg"),
                    folder = false,
                    mimeType = "Application/PDF; charset=binary",
                )
                .isPdfDocument,
        )
        assertTrue(Entry(java.io.File("/local.pdf"), folder = false).isPdfDocument)
        assertFalse(Entry(java.io.File("/folder.pdf"), folder = true).isPdfDocument)
    }
}
