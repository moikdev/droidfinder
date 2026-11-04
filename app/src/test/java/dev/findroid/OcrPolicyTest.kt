package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class OcrPolicyTest {
    @Test fun catalogHasOnlyPinnedExactLanguageFiles() {
        assertEquals(setOf("eng", "jpn"), OcrPolicy.languages.map { it.id }.toSet())
        OcrPolicy.languages.forEach {
            assertEquals(it, OcrPolicy.language(it.id))
            assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(it.downloadUrl.endsWith("/${it.id}.traineddata"))
            assertTrue(it.downloadUrl.contains("/65727574dfcd264acbb0c3e07860e4e9e9b22185/"))
        }
    }
    @Test fun unknownLanguageAndPathsAreRefused() {
        for (id in listOf("../eng", "eng+jpn", "ENG", "", "eng.traineddata")) {
            assertThrows(IllegalArgumentException::class.java) { OcrPolicy.language(id) }
        }
    }
    @Test fun rasterBoundsEvenHugeInputWithoutIntegerOverflow() {
        for ((w, h) in listOf(1 to 1, 1800 to 2400, 100000 to 100000, Int.MAX_VALUE to Int.MAX_VALUE, 1 to Int.MAX_VALUE)) {
            val (rw, rh) = OcrPolicy.raster(w, h)
            assertTrue(rw in 1..2400 && rh in 1..2400)
            assertTrue(rw.toLong() * rh <= OcrPolicy.maximumPixels)
            assertTrue(rw <= w && rh <= h)
        }
        assertEquals(800 to 600, OcrPolicy.raster(800, 600))
    }
    @Test fun invalidRasterDimensionsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { OcrPolicy.raster(0, 800) }
        assertThrows(IllegalArgumentException::class.java) { OcrPolicy.raster(800, -1) }
    }
    @Test fun copiedResultPreservesPassageAndNewlines() {
        val text = "Ocean light\nFar shore\n日本語\n"
        val result = OcrPolicy.result(text, 92)
        assertEquals(text, result.text)
        assertFalse(result.limited)
        assertEquals(92, result.confidence)
    }
    @Test fun limitedResultDoesNotCutSurrogatePair() {
        val before = "a".repeat(OcrPolicy.maximumText - 1)
        val result = OcrPolicy.result(before + "🌊tail", 200)
        assertEquals(before, result.text)
        assertTrue(result.limited)
        assertEquals(100, result.confidence)
    }
    @Test fun textWindowsRetainEveryCharacterAndBoundTallText() {
        val text = ("Ocean\n".repeat(2000) + "a".repeat(3999) + "🌊" + "shore\n".repeat(1200))
        val windows = OcrPolicy.windows(text)
        assertEquals(text, windows.joinToString("") { text.substring(it) })
        assertTrue(windows.all { it.count() <= 4000 && text.substring(it).count { c -> c == '\n' } <= 80 })
        assertTrue(windows.dropLast(1).all { !text[it.last].isHighSurrogate() })
        assertEquals(emptyList<IntRange>(), OcrPolicy.windows(""))
    }
}
