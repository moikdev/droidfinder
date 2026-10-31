package dev.droidfinder

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParityEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var engine: FileEngine
    private lateinit var history: FileHistory

    @Before
    fun setup() {
        root = temporary.newFolder("files")
        engine = FileEngine(temporary.newFolder("state"))
        history = FileHistory(engine)
    }

    private fun text(name: String, content: String = name) =
        File(root, name).apply { writeText(content) }

    private fun fails(work: () -> Unit) {
        try {
            work()
            fail("Expected rejection")
        } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
    }

    @Test
    fun batchRenameCanSwapNames() {
        val a = text("a.txt", "A")
        val b = text("b.txt", "B")
        engine.renameBatch(listOf(RenameProposal(a, b.name), RenameProposal(b, a.name)))
        assertEquals("B", a.readText())
        assertEquals("A", b.readText())
    }

    @Test
    fun batchCollisionLeavesEveryOriginalUntouched() {
        val a = text("a.txt")
        val b = text("b.txt")
        text("occupied.txt")
        fails {
            engine.renameBatch(
                listOf(RenameProposal(a, "new.txt"), RenameProposal(b, "occupied.txt")),
            )
        }
        assertTrue(a.exists())
        assertTrue(b.exists())
        assertFalse(File(root, "new.txt").exists())
    }

    @Test
    fun numberedRenamePreservesExtensionsAndFolders() {
        val a = text("a.tar.gz")
        val folder = File(root, "v1.0").apply { mkdir() }
        val rule = RenameRule(RenameMode.NUMBER, "Trip", start = 7)
        assertEquals(
            listOf("Trip 7.gz", "Trip 8"),
            planRename(listOf(a, folder), rule).map { it.name },
        )
    }

    @Test
    fun replacingTextRejectsDuplicateDestinations() {
        val a = text("a1.txt")
        val b = text("a2.txt")
        fails { planRename(listOf(a, b), RenameRule(RenameMode.NUMBER, "../bad")) }
    }

    @Test
    fun undoRedoCopyDoesNotDeleteEditedFiles() {
        val source = text("a.txt")
        val copy = engine.copy(source, root)
        history.record("Copy", listOf(FileStep.Created(copy)))
        copy.writeText("user edit")
        fails { history.undo() }
        assertEquals("user edit", copy.readText())
        assertEquals("Copy", history.undoLabel)
    }

    @Test
    fun undoRedoCreatedItemUsesRecoverableTrash() {
        val folder = engine.createFolder(root, "New")
        history.record("New folder", listOf(FileStep.Created(folder)))
        history.undo()
        assertFalse(folder.exists())
        history.redo()
        assertTrue(folder.isDirectory)
        history.undo()
        assertEquals(1, engine.trashItems().size)
    }

    @Test
    fun undoRedoMovedItemPreservesData() {
        val a = text("a.txt", "A")
        val folder = engine.createFolder(root, "Destination")
        val moved = engine.copy(a, folder, move = true)
        history.record("Move", listOf(FileStep.Moved(a, moved)))
        history.undo()
        assertEquals("A", a.readText())
        assertFalse(moved.exists())
        history.redo()
        assertFalse(a.exists())
        assertEquals("A", moved.readText())
    }

    @Test
    fun undoMoveRefusesCollision() {
        val a = text("a.txt")
        val folder = engine.createFolder(root, "Destination")
        val moved = engine.copy(a, folder, true)
        history.record("Move", listOf(FileStep.Moved(a, moved)))
        text("a.txt", "new data")
        fails { history.undo() }
        assertEquals("new data", a.readText())
        assertTrue(moved.exists())
    }

    @Test
    fun undoRedoBatchRenameHandlesCycles() {
        val a = text("a", "A")
        val b = text("b", "B")
        val pairs = engine.renameBatch(listOf(RenameProposal(a, "b"), RenameProposal(b, "a")))
        history.record("Rename", listOf(FileStep.Renamed(pairs)))
        history.undo()
        assertEquals("A", a.readText())
        history.redo()
        assertEquals("B", a.readText())
    }

    @Test
    fun undoRedoTrashMaintainsIndex() {
        val a = text("a")
        val item = engine.trash(a)
        history.record("Trash", listOf(FileStep.Trashed(a, item)))
        history.undo()
        assertTrue(a.exists())
        assertTrue(engine.trashItems().isEmpty())
        history.redo()
        assertFalse(a.exists())
        assertEquals(a.path, engine.originalPath(engine.trashItems().single()))
    }

    @Test
    fun undoPreflightProtectsAnEntireBatch() {
        val a = text("a")
        val b = text("b")
        history.record("Create", listOf(FileStep.Created(a), FileStep.Created(b)))
        a.writeText("changed")
        fails { history.undo() }
        assertTrue(b.exists())
    }

    @Test
    fun newOperationClearsRedo() {
        val a = text("a")
        history.record("A", listOf(FileStep.Created(a)))
        history.undo()
        val b = text("b")
        history.record("B", listOf(FileStep.Created(b)))
        assertNull(history.redoLabel)
    }

    @Test
    fun cancellationRemovesPartialCopy() {
        val source = text("large", "x".repeat(300_000))
        lateinit var control: TransferControl
        control = TransferControl { if (it.bytes > 0) control.cancel() }
        try {
            engine.copy(source, root, control = control)
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertTrue(source.exists())
        assertEquals(listOf("large"), root.listFiles()!!.map { it.name })
    }

    @Test
    fun customMultipleTagsSurviveRestartAndMove() {
        val file = File(temporary.root, "tags")
        val store = TagStore(file)
        store.define("Client", "Green")
        store.assign(listOf("/a"), "Client")
        store.assign(listOf("/a"), "Blue")
        store.relocate(listOf("/a" to "/b"))
        val restored = TagStore(file)
        assertEquals(setOf("Client", "Blue"), restored.assignments["/b"])
        assertFalse(restored.assignments.containsKey("/a"))
    }

    @Test
    fun tagSwapsAreAppliedSimultaneously() {
        val store = TagStore(File(temporary.root, "tags"))
        store.assign(listOf("/a"), "Red")
        store.assign(listOf("/b"), "Blue")
        store.relocate(listOf("/a" to "/b", "/b" to "/a"))
        assertEquals(setOf("Blue"), store.assignments["/a"])
        assertEquals(setOf("Red"), store.assignments["/b"])
    }

    @Test
    fun assigningAnExistingTagTogglesOnlyThatTag() {
        val store = TagStore(File(temporary.root, "tags"))
        store.assign(listOf("/a"), "Blue")
        store.assign(listOf("/a"), "Red")
        store.assign(listOf("/a"), "Blue")
        assertEquals(setOf("Red"), store.assignments["/a"])
    }

    @Test
    fun smartRulesCombineNameKindSizeAndContents() {
        val file = text("Notes.md", "Tablet ideas")
        val entry = Entry(file)
        assertTrue(
            SearchRules(
                    name = "note",
                    kind = "Text document",
                    tag = "Work",
                    minimumBytes = 3,
                    content = "tablet",
                )
                .matches(entry, setOf("Work")),
        )
        assertFalse(SearchRules(content = "missing").matches(entry, emptySet()))
        assertFalse(SearchRules(maximumBytes = 2).matches(entry, emptySet()))
    }

    @Test
    fun folderStatsSkipSymbolicLinks() {
        val file = text("notes", "12345")
        val child = File(root, "nested").apply { mkdir() }
        java.nio.file.Files.createSymbolicLink(File(child, "link").toPath(), root.toPath())
        val stats = folderStats(root)
        assertEquals(1, stats.files)
        assertEquals(1, stats.folders)
        assertEquals(file.length(), stats.bytes)
    }

    @Test
    fun deletingAllTagsDoesNotResurrectDefaults() {
        val file = File(temporary.root, "tags")
        val store = TagStore(file)
        store.definitions.toList().forEach { store.remove(it.name) }
        assertTrue(TagStore(file).definitions.isEmpty())
    }

    @Test
    fun providerMetadataMovesIncludeEncodedDescendantPaths() {
        val store = TagStore(File(temporary.root, "tags"))
        val before = "content://test/tree/root/document/root%2FA"
        val after = "content://test/tree/root/document/root%2FB"
        store.assign(listOf("$before%2Fchild.txt"), "Blue")
        store.relocate(listOf(before to after))
        assertEquals(setOf("Blue"), store.assignments["$after%2Fchild.txt"])
    }

    @Test
    fun shortcutsRoundTripAndRejectExecutableText() {
        val file = text("Link.finderlink")
        FinderShortcut.write(file, "/storage/emulated/0/Documents/Notes.md")
        assertEquals(
            "/storage/emulated/0/Documents/Notes.md",
            FinderShortcut.read(file.inputStream()),
        )
        file.writeText("https://evil.example/")
        fails { FinderShortcut.read(file.inputStream()) }
    }

    @Test
    fun cancellationUnblocksAWaitingStream() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val input =
            object : java.io.InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    released.await()
                    throw java.io.IOException("Closed")
                }

                override fun close() {
                    released.countDown()
                }
            }
        val control = TransferControl()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit { control.copy(input, java.io.ByteArrayOutputStream()) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            control.cancel()
            try {
                future.get(2, java.util.concurrent.TimeUnit.SECONDS)
                fail("Expected cancellation")
            } catch (error: java.util.concurrent.ExecutionException) {
                assertTrue(error.cause is CancellationException)
            }
        } finally {
            input.close()
            executor.shutdownNow()
        }
    }
}
