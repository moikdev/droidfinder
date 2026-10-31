package dev.droidfinder

import java.io.Reader
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StreamingTextTest {
    private fun contains(text: String, needle: String) =
        StreamingText(needle).contains(StringReader(text), {}, {})

    @Test
    fun matchesCrossReadBoundariesAndSupportsCaseAndUnicode() {
        assertTrue(contains("x".repeat(8190) + "Résumé ΩΣ🙂", "résumé ωσ🙂"))
        assertTrue(contains("a".repeat(8191) + "needle", "ANeedle"))
        assertFalse(contains("a".repeat(20000), "aaaaab"))
        assertTrue(contains("İıIİ", "iiii"))
    }

    @Test
    fun searchMatchesBeyondTheOldOneMiBLimit() {
        val text = "x".repeat(2 * 1024 * 1024) + "unique answer"
        assertTrue(contains(text, "UNIQUE ANSWER"))
    }

    @Test
    fun decoderAndTinyChunksDoNotLosePartialMatches() {
        val text = "start Ωrésumé🙂 end"
        val reader =
            object : Reader() {
                var index = 0

                override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                    if (index == text.length) return -1
                    cbuf[off] = text[index++]
                    return 1
                }

                override fun close() {}
            }
        assertTrue(StreamingText("ωRÉSUMÉ🙂").contains(reader, {}, {}))
    }

    @Test
    fun binaryDetectionIsExplicitAndCancellationIsNotSwallowed() {
        var binary = false
        assertFalse(
            StreamingText("needle")
                .contains(StringReader("text\u0000needle"), {}, { binary = true }),
        )
        assertTrue(binary)
        var checks = 0
        try {
            StreamingText("missing")
                .contains(
                    StringReader("x".repeat(20000)),
                    { if (++checks == 3) throw CancellationException("stop") },
                    {},
                )
            fail()
        } catch (_: CancellationException) {}
        assertEquals(3, checks)
    }
}
