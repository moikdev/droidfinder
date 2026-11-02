package dev.findroid

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var engine: FileEngine
    private lateinit var root: File
    private lateinit var state: File

    @Before
    fun setup() {
        root = temporary.newFolder("files")
        state = temporary.newFolder("state")
        engine = FileEngine(state)
    }

    private fun text(name: String, content: String = "original") =
        File(root, name).apply { writeText(content) }

    private fun fails(action: () -> Unit) {
        try {
            action()
            fail("Expected operation to fail")
        } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test
    fun createsFolderAndRejectsDuplicate() {
        val folder = engine.createFolder(root, "Projects")
        assertTrue(folder.isDirectory)
        fails { engine.createFolder(root, "Projects") }
    }

    @Test
    fun rejectsTraversalNames() {
        listOf("", " ", ".", "..", "../outside", "a/b", "a\u0000b").forEach { name ->
            fails { engine.createFolder(root, name) }
        }
    }

    @Test
    fun renameDoesNotOverwrite() {
        val a = text("a.txt")
        text("b.txt", "keep")
        fails { engine.rename(a, "b.txt") }
        assertEquals("original", a.readText())
        assertEquals("keep", File(root, "b.txt").readText())
    }

    @Test
    fun copyUsesUniqueNameAndKeepsExtension() {
        val source = text("notes.md")
        val result = engine.copy(source, root)
        assertEquals("notes 2.md", result.name)
        assertEquals(source.readText(), result.readText())
        assertTrue(source.exists())
    }

    @Test
    fun copiesDirectoryTree() {
        val source = engine.createFolder(root, "Project")
        File(source, "nested").mkdir()
        File(source, "nested/test.txt").writeText("data")
        val result = engine.copy(source, root)
        assertEquals("data", File(result, "nested/test.txt").readText())
    }

    @Test
    fun rejectsCopyIntoOwnDescendant() {
        val source = engine.createFolder(root, "Project")
        val child = engine.createFolder(source, "Child")
        fails { engine.copy(source, child) }
        assertTrue(source.exists())
    }

    @Test
    fun moveKeepsExistingDestination() {
        val source = text("a.txt")
        val target = engine.createFolder(root, "Destination")
        File(target, "a.txt").writeText("keep")
        val result = engine.copy(source, target, move = true)
        assertFalse(source.exists())
        assertEquals("a 2.txt", result.name)
        assertEquals("keep", File(target, "a.txt").readText())
    }

    @Test
    fun moveReservesDanglingLinkNamesInsteadOfReplacingThem() {
        val source = text("a.txt")
        val parent = engine.createFolder(root, "Destination")
        val link = File(parent, source.name)
        val missing = File(root, "missing.txt").toPath()
        Files.createSymbolicLink(link.toPath(), missing)
        val result = engine.copy(source, parent, move = true)
        assertEquals("a 2.txt", result.name)
        assertEquals("original", result.readText())
        assertEquals(missing, Files.readSymbolicLink(link.toPath()))
    }

    @Test
    fun exactCopyAndRelocationKeepDanglingDestinationLinks() {
        val source = text("a.txt")
        val parent = engine.createFolder(root, "Destination")
        val target = File(parent, source.name)
        val missing = File(root, "missing.txt").toPath()
        Files.createSymbolicLink(target.toPath(), missing)
        assertTrue(runCatching {
            engine.copy(source, parent, move = true, requestedName = source.name)
        }.isFailure)
        assertTrue(runCatching { engine.relocate(source, target) }.isFailure)
        assertEquals("original", source.readText())
        assertEquals(missing, Files.readSymbolicLink(target.toPath()))
    }

    @Test
    fun destinationCreatedImmediatelyBeforeMoveIsKept() {
        val source = text("a.txt")
        val parent = engine.createFolder(root, "Destination")
        val target = File(parent, source.name)
        var injected = false
        val control = TransferControl(progress = {
            if (!injected && it.phase == "Moving") {
                injected = true
                target.writeText("arrived while preparing the move")
            }
        })
        assertTrue(runCatching { engine.copy(source, parent, move = true, control = control) }.isFailure)
        assertTrue(injected)
        assertEquals("original", source.readText())
        assertEquals("arrived while preparing the move", target.readText())
    }

    @Test
    fun failedRenameCycleRestoresDanglingLinksWithoutRecoveryAliases() {
        val first = File(root, "a")
        val second = File(root, "b")
        val firstTarget = File(root, "missing-a").toPath()
        val secondTarget = File(root, "missing-b").toPath()
        Files.createSymbolicLink(first.toPath(), firstTarget)
        Files.createSymbolicLink(second.toPath(), secondTarget)
        val trigger = text("trigger")
        val control = TransferControl(progress = {
            if (it.current == "trigger") File(root, "Blocked").writeText("new arrival")
        })
        assertTrue(runCatching {
            engine.renameBatch(listOf(RenameProposal(first, "b"), RenameProposal(second, "a"),
                RenameProposal(trigger, "Blocked")), control)
        }.isFailure)
        assertEquals(firstTarget, Files.readSymbolicLink(first.toPath()))
        assertEquals(secondTarget, Files.readSymbolicLink(second.toPath()))
        assertEquals("original", trigger.readText())
        assertEquals("new arrival", File(root, "Blocked").readText())
        assertFalse(root.list()!!.any { it.startsWith(".findroid-") || it.startsWith("Recovered ") })
    }

    @Test
    fun fileAddedDuringFolderCopyCannotBeSilentlyOmitted() {
        val source = engine.createFolder(root, "Changing")
        File(source, "first.txt").writeText("first")
        var added = false
        val control = TransferControl(progress = {
            if (!added && it.bytes > 0) {
                added = true
                File(source, "arrived.txt").writeText("arrived")
            }
        })
        fails { engine.copy(source, root, control = control) }
        assertTrue(added)
        assertEquals("arrived", File(source, "arrived.txt").readText())
        assertFalse(File(root, "Changing 2").exists())
        assertFalse(root.list()!!.any { it.startsWith(".findroid-") })
    }

    @Test
    fun sameFolderMoveIsRejected() {
        val source = text("a.txt")
        fails { engine.copy(source, root, move = true) }
        assertTrue(source.exists())
    }

    @Test
    fun symbolicLinkCopyIsRejected() {
        val file = text("real.txt")
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        fails { engine.copy(link, root) }
        assertTrue(file.exists())
    }

    @Test
    fun failedRecursiveCopyCleansStagingAndKeepsSource() {
        val source = engine.createFolder(root, "Project")
        val real = text("outside.txt")
        Files.createSymbolicLink(File(source, "link").toPath(), real.toPath())
        fails { engine.copy(source, root) }
        assertTrue(source.exists())
        assertFalse(File(root, "Project 2").exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".findroid-") })
    }

    @Test
    fun trashCanBeRestoredAfterRestart() {
        val source = text("notes.txt")
        val item = engine.trash(source)
        assertFalse(source.exists())
        assertEquals(source.absolutePath, engine.originalPath(item))
        val restarted = FileEngine(state)
        assertEquals(1, restarted.trashItems().size)
        val restored = restarted.restore(item)
        assertEquals(source, restored)
        assertEquals("original", restored.readText())
        assertTrue(restarted.trashItems().isEmpty())
    }

    @Test
    fun damagedTrashRecordsCannotPreventStartupOrDestroyRecoveryFiles() {
        val item = engine.trash(text("keep.txt"))
        val index = File(state, "trash.properties")
        val damaged = "bad=\\uZZZZ\n"
        index.writeText(damaged)
        val recovered = FileEngine(state)
        assertNotNull(recovered.trashIndexNotice)
        assertEquals(listOf(item), recovered.trashItems())
        assertEquals("original", item.readText())
        assertNull(recovered.originalPath(item))
        assertTrue(state.listFiles()!!.any {
            it.name.startsWith("trash.properties.damaged-") && it.readText() == damaged
        })
        val another = recovered.trash(text("another.txt", "another"))
        assertEquals("another", FileEngine(state).restore(another).readText())
        assertEquals("original", item.readText())
    }

    @Test
    fun damagedPrimaryTrashIndexUsesValidBackupAndPreservesTheBadBytes() {
        val original = text("keep.txt")
        val item = engine.trash(original)
        val index = File(state, "trash.properties")
        index.copyTo(File(state, "trash.properties.bak"))
        index.writeText("bad=\\uZZZZ\n")
        val recovered = FileEngine(state)
        assertNotNull(recovered.trashIndexNotice)
        assertEquals(original.path, recovered.originalPath(item))
        assertEquals("original", recovered.restore(item).readText())
        assertTrue(recovered.trashItems().isEmpty())
        assertTrue(state.listFiles()!!.any { it.name.startsWith("trash.properties.damaged-") })
    }

    @Test
    fun restoreDoesNotOverwriteNewFileAtOriginalPath() {
        val source = text("notes.txt")
        val item = engine.trash(source)
        text("notes.txt", "new")
        val restored = engine.restore(item)
        assertEquals("notes 2.txt", restored.name)
        assertEquals("new", source.readText())
        assertEquals("original", restored.readText())
    }

    @Test
    fun deleteForeverRejectsNonTrashFile() {
        val source = text("notes.txt")
        fails { engine.deleteForever(source) }
        assertTrue(source.exists())
    }

    @Test
    fun deleteForeverOnlyRemovesRequestedTrashItem() {
        val a = engine.trash(text("a.txt"))
        engine.trash(text("b.txt"))
        engine.deleteForever(a)
        assertEquals(listOf("b.txt"), engine.trashItems().map { it.name })
    }

    @Test
    fun deletingTrashedFolderNeverFollowsSymbolicLinks() {
        val outside = temporary.newFolder("outside")
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val folder = engine.createFolder(root, "Links")
        Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())
        val item = engine.trash(folder)
        engine.deleteForever(item)
        assertEquals("keep", keep.readText())
    }

    @Test
    fun compressionRejectsDestinationInsideSelectedFolderBeforeWriting() {
        val folder = engine.createFolder(root, "Project")
        File(folder, "notes.txt").writeText("keep")
        val output = engine.createFolder(folder, "Output")
        fails { engine.compress(listOf(folder), output) }
        assertTrue(output.list()!!.isEmpty())
        assertEquals("keep", File(folder, "notes.txt").readText())
    }

    @Test
    fun deepCopyStopsWithSourceKeptAndStagingRemoved() {
        val folder = engine.createFolder(root, "Deep")
        var cursor = folder
        repeat(129) { cursor = File(cursor, "d").apply { mkdir() } }
        File(cursor, "keep.txt").writeText("keep")
        fails { engine.copy(folder, root) }
        assertEquals("keep", File(cursor, "keep.txt").readText())
        assertFalse(File(root, "Deep 2").exists())
        assertFalse(root.list()!!.any { it.startsWith(".findroid-") })
    }

    @Test
    fun sameSizeSourceEditDuringCopyIsDetectedBeforePublication() {
        val source = text("edit.txt", "original")
        var edited = false
        val modified = source.lastModified()
        val control = TransferControl(progress = {
            if (!edited && it.bytes > 0) {
                edited = true
                source.writeText("modified")
                assertTrue(source.setLastModified(modified + 10_000))
            }
        })
        fails { engine.copy(source, root, control = control) }
        assertTrue(edited)
        assertEquals("modified", source.readText())
        assertFalse(File(root, "edit 2.txt").exists())
        assertFalse(root.list()!!.any { it.startsWith(".findroid-") })
    }

    @Test
    fun zipRoundTripPreservesContents() {
        val folder = engine.createFolder(root, "Project")
        File(folder, "notes.txt").writeText("Hello, tablet")
        engine.createFolder(folder, "Empty")
        val archive = engine.compress(listOf(folder), root)
        val extracted = engine.extract(archive, root)
        assertEquals("Hello, tablet", File(extracted, "Project/notes.txt").readText())
        assertTrue(File(extracted, "Project/Empty").isDirectory)
    }

    @Test
    fun zipTraversalIsRejectedAndStagingCleaned() {
        val archive = text("evil.zip")
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("../escaped.txt"))
            it.write("bad".toByteArray())
            it.closeEntry()
        }
        fails { engine.extract(archive, root) }
        assertFalse(File(root, "escaped.txt").exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".findroid-") })
        assertTrue(archive.exists())
    }

    @Test
    fun duplicateCanonicalArchiveEntryIsRejected() {
        val archive = text("duplicate.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("notes.txt", "./notes.txt").forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.write("data".toByteArray())
                zip.closeEntry()
            }
        }
        fails { engine.extract(archive, root) }
        assertFalse(File(root, "duplicate").exists())
    }
}
