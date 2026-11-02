package dev.findroid

import java.io.File
import java.io.Reader
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SearchEvidenceTest {
    private val item = Entry(File("/fixture/Résumé İ Report.txt"), folder = false, size = 100, modified = 100)

    @Test fun oneReadExplainsAllMatchedCriteriaAndHighlightsOriginalUnicodeOffsets() {
        var reads = 0
        val rules = SearchRules(name = "i report", nameTerms = listOf("RÉSUMÉ"), kind = "Text document", tags = setOf("Review"), minimumBytes = 1, content = "NEEDLE")
        val evidence = rules.evidence(item, setOf("Review"), read = {
            reads++
            "A literal <b>needle</b> from the file.".byteInputStream()
        })!!
        assertEquals(1, reads)
        assertEquals(listOf("Résumé", "İ Report"), evidence.nameRanges.map { item.name.substring(it.start, it.end) })
        assertEquals(listOf("Name", "Tags: Review", "Kind: Text document", "Size: 100 bytes", "Text contents"), evidence.reasons)
        assertTrue(evidence.snippet!!.text.contains("<b>needle</b>"))
        assertEquals("needle", evidence.snippet.let { it.text.substring(it.ranges.single().start, it.ranges.single().end) })
    }

    @Test fun failingMetadataOrExclusionsNeverReadContentOrRetainEvidence() {
        val rules = SearchRules(content = "secret")
        for (r in listOf(rules.copy(name = "missing"), rules.copy(tags = setOf("Missing")), rules.copy(size = SearchSize.OVER_1_GB),
            rules.copy(excludedLocations = listOf(SearchExclusion("/fixture"))), rules.copy(kind = "Image"))) {
            assertNull(r.evidence(item, emptySet(), read = { error("Unexpected file read") }))
        }
    }

    @Test fun evidenceIsBoundedForLongPatternsAndRepeatedFilenameMatches() {
        val text = "x".repeat(20_000) + "Ω".repeat(10_000) + " trailing"
        val snippet = StreamingText("ω".repeat(10_000)).find(StringReader(text), {}, {})!!
        assertTrue(snippet.text.length <= 224)
        assertTrue(snippet.leading)
        assertTrue(snippet.ranges.all { it.start in 0..it.end && it.end <= snippet.text.length })
        assertTrue(snippet.text.substring(snippet.ranges.single().start, snippet.ranges.single().end).all { it == 'Ω' })
        assertTrue(matchRanges("a".repeat(500), listOf("a")).size <= 24)
        assertEquals(listOf(MatchRange(0, 6)), matchRanges("Résumé", listOf("rés", "sumé")))
    }

    @Test fun snippetsSurviveSingleCharacterChunksAndBinaryAndCancellationStayExplicit() {
        val source = "before Ωrésumé🙂 after"
        val reader = object : Reader() {
            var at = 0
            override fun close() {}
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (at == source.length) return -1
                cbuf[off] = source[at++]
                return 1
            }
        }
        val snippet = StreamingText("ωRÉSUMÉ🙂").find(reader, {}, {})!!
        assertEquals("Ωrésumé🙂", snippet.text.substring(snippet.ranges.single().start, snippet.ranges.single().end))
        var issues = 0
        assertNull(SearchRules(content = "needle").evidence(item, emptySet(), read = { "bad\u0000needle".byteInputStream() }, contentIssue = { issues++ }))
        assertEquals(1, issues)
        assertNull(SearchRules(content = "needle").evidence(item, emptySet(), read = { error("Offline") }, contentIssue = { issues++ }))
        assertEquals(2, issues)
        try {
            SearchRules(content = "needle").evidence(item, emptySet(), read = { throw CancellationException("Stopped") })
            fail()
        } catch (_: CancellationException) {}
    }
}
