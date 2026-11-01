package dev.droidfinder

import org.junit.Assert.*
import org.junit.Test

class IncomingShareNameTest {
    @Test
    fun preservesOrdinaryNamesAndUnicode() {
        for (name in
            listOf(
                "Report.pdf",
                ".env",
                "旅行 + field notes.txt",
                "photo.HEIC",
                "No extension",
                "Résumé.csv",
            )) assertEquals(name, incomingFileName(name))
    }

    @Test
    fun removesTraversalControlAndWorkingFileNames() {
        for (name in
            listOf(
                "../escape.txt",
                "a/b.txt",
                "bad\u0000name",
                "hello\n.txt",
                "\u202ereversed.txt",
                ".",
                "..",
                "",
                ".droidfinder-internal",
            )) {
            val safe = incomingFileName(name)
            assertEquals(safe, validateFilename(safe))
            assertFalse(safe.startsWith(".droidfinder-"))
            assertFalse(safe.any { it.isISOControl() || it == '/' || it == '\u202e' })
        }
        assertEquals("_escape.txt", incomingFileName("../escape.txt"))
    }

    @Test
    fun longUnicodeNamesRespectByteBudgetWithoutSplittingSurrogatePairs() {
        val name = incomingFileName("📷".repeat(100) + ".png")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }
}
