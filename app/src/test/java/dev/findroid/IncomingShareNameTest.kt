package dev.findroid

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
                ".findroid-internal",
            )) {
            val safe = incomingFileName(name)
            assertEquals(safe, validateFilename(safe))
            assertFalse(safe.startsWith(".findroid-"))
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
    @Test(timeout = 10_000)
    fun oversizedProviderNamesAreNormalizedWithinABoundedBudget() {
        val name = incomingFileName("📷".repeat(250_000) + "/bad")
        assertEquals("📷".repeat(60), name)
        assertEquals(240, name.toByteArray(Charsets.UTF_8).size)
        assertEquals("_file.txt", incomingFileName(".".repeat(500_000) + "/file.txt"))
        assertEquals("Shared file", incomingFileName(" ".repeat(500_000)))
    }

}
