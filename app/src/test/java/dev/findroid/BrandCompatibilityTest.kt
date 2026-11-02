package dev.findroid

import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class BrandCompatibilityTest {
    @Test
    fun newShortcutsUseFindroidAndRetainTheirTarget() {
        val file = Files.createTempFile("findroid-shortcut", ".finderlink").toFile()
        try {
            val target = "content://storage.example/tree/documents/document/Trip%20notes"
            FinderShortcut.write(file, target)
            assertTrue(file.readText().startsWith("Findroid shortcut v1\n"))
            assertEquals(target, FinderShortcut.read(file.inputStream()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun existingShortcutsStillResolveWithoutRewritingTheFile() {
        val target = "/storage/emulated/0/Documents/旅行 notes.txt"
        val legacy =
            "DroidFinder shortcut v1\n" +
                Base64.getEncoder().encodeToString(target.toByteArray(Charsets.UTF_8))
        assertEquals(target, FinderShortcut.read(legacy.byteInputStream()))
        assertThrows(IllegalArgumentException::class.java) {
            FinderShortcut.read("Other app shortcut v1\n$target".byteInputStream())
        }
    }

    @Test
    fun incomingSharesCannotUseEitherGenerationOfWorkingFileNames() {
        listOf(
                ".findroid-stage",
                ".findroid-rename-123",
                ".droidfinder-stage",
                ".droidfinder-rollback-123",
            )
            .forEach { assertEquals(it, "Shared file", incomingFileName(it)) }
        listOf("Findroid notes.txt", ".findroid", "findroid-stage", "Notes.txt").forEach {
            assertEquals(it, it, incomingFileName(it))
        }
    }
}
