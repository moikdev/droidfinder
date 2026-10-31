package dev.droidfinder

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var engine: FileEngine
    private lateinit var root: File
    private lateinit var state: File
    @Before fun setup() { root = temporary.newFolder("files"); state = temporary.newFolder("state"); engine = FileEngine(state) }
    private fun text(name: String, content: String = "original") = File(root, name).apply { writeText(content) }
    private fun fails(action: () -> Unit) { try { action(); fail("Expected operation to fail") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun createsFolderAndRejectsDuplicate() { val folder = engine.createFolder(root, "Projects"); assertTrue(folder.isDirectory); fails { engine.createFolder(root, "Projects") } }
    @Test fun rejectsTraversalNames() { listOf("", " ", ".", "..", "../outside", "a/b", "a\u0000b").forEach { name -> fails { engine.createFolder(root, name) } } }
    @Test fun renameDoesNotOverwrite() { val a = text("a.txt"); text("b.txt", "keep"); fails { engine.rename(a, "b.txt") }; assertEquals("original", a.readText()); assertEquals("keep", File(root, "b.txt").readText()) }
    @Test fun copyUsesUniqueNameAndKeepsExtension() { val source = text("notes.md"); val result = engine.copy(source, root); assertEquals("notes 2.md", result.name); assertEquals(source.readText(), result.readText()); assertTrue(source.exists()) }
    @Test fun copiesDirectoryTree() { val source = engine.createFolder(root, "Project"); File(source, "nested").mkdir(); File(source, "nested/test.txt").writeText("data"); val result = engine.copy(source, root); assertEquals("data", File(result, "nested/test.txt").readText()) }
    @Test fun rejectsCopyIntoOwnDescendant() { val source = engine.createFolder(root, "Project"); val child = engine.createFolder(source, "Child"); fails { engine.copy(source, child) }; assertTrue(source.exists()) }
    @Test fun moveKeepsExistingDestination() { val source = text("a.txt"); val target = engine.createFolder(root, "Destination"); File(target, "a.txt").writeText("keep"); val result = engine.copy(source, target, move = true); assertFalse(source.exists()); assertEquals("a 2.txt", result.name); assertEquals("keep", File(target, "a.txt").readText()) }
    @Test fun sameFolderMoveIsRejected() { val source = text("a.txt"); fails { engine.copy(source, root, move = true) }; assertTrue(source.exists()) }
    @Test fun symbolicLinkCopyIsRejected() { val file = text("real.txt"); val link = File(root, "link"); Files.createSymbolicLink(link.toPath(), file.toPath()); fails { engine.copy(link, root) }; assertTrue(file.exists()) }
    @Test fun failedRecursiveCopyCleansStagingAndKeepsSource() { val source = engine.createFolder(root, "Project"); val real = text("outside.txt"); Files.createSymbolicLink(File(source, "link").toPath(), real.toPath()); fails { engine.copy(source, root) }; assertTrue(source.exists()); assertFalse(File(root, "Project 2").exists()); assertFalse(root.listFiles()!!.any { it.name.startsWith(".droidfinder-") }) }
    @Test fun trashCanBeRestoredAfterRestart() { val source = text("notes.txt"); val item = engine.trash(source); assertFalse(source.exists()); assertEquals(source.absolutePath, engine.originalPath(item)); val restarted = FileEngine(state); assertEquals(1, restarted.trashItems().size); val restored = restarted.restore(item); assertEquals(source, restored); assertEquals("original", restored.readText()); assertTrue(restarted.trashItems().isEmpty()) }
    @Test fun restoreDoesNotOverwriteNewFileAtOriginalPath() { val source = text("notes.txt"); val item = engine.trash(source); text("notes.txt", "new"); val restored = engine.restore(item); assertEquals("notes 2.txt", restored.name); assertEquals("new", source.readText()); assertEquals("original", restored.readText()) }
    @Test fun deleteForeverRejectsNonTrashFile() { val source = text("notes.txt"); fails { engine.deleteForever(source) }; assertTrue(source.exists()) }
    @Test fun deleteForeverOnlyRemovesRequestedTrashItem() { val a = engine.trash(text("a.txt")); engine.trash(text("b.txt")); engine.deleteForever(a); assertEquals(listOf("b.txt"), engine.trashItems().map { it.name }) }
    @Test fun deletingTrashedFolderNeverFollowsSymbolicLinks() { val outside = temporary.newFolder("outside"); val keep = File(outside, "keep.txt").apply { writeText("keep") }; val folder = engine.createFolder(root, "Links"); Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath()); val item = engine.trash(folder); engine.deleteForever(item); assertEquals("keep", keep.readText()) }
    @Test fun zipRoundTripPreservesContents() { val folder = engine.createFolder(root, "Project"); File(folder, "notes.txt").writeText("Hello, tablet"); engine.createFolder(folder, "Empty"); val archive = engine.compress(listOf(folder), root); val extracted = engine.extract(archive, root); assertEquals("Hello, tablet", File(extracted, "Project/notes.txt").readText()); assertTrue(File(extracted, "Project/Empty").isDirectory) }
    @Test fun zipTraversalIsRejectedAndStagingCleaned() { val archive = text("evil.zip"); ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry("../escaped.txt")); it.write("bad".toByteArray()); it.closeEntry() }; fails { engine.extract(archive, root) }; assertFalse(File(root, "escaped.txt").exists()); assertFalse(root.listFiles()!!.any { it.name.startsWith(".droidfinder-") }); assertTrue(archive.exists()) }
    @Test fun duplicateCanonicalArchiveEntryIsRejected() { val archive = text("duplicate.zip"); ZipOutputStream(archive.outputStream()).use { zip -> listOf("notes.txt", "./notes.txt").forEach { zip.putNextEntry(ZipEntry(it)); zip.write("data".toByteArray()); zip.closeEntry() } }; fails { engine.extract(archive, root) }; assertFalse(File(root, "duplicate").exists()) }
}
