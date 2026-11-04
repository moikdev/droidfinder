package dev.findroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderSyncApplyNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owned = mutableListOf<File>()
    private inner class Fixture {
        val root = File(context.cacheDir, "sync-apply-native-" + UUID.randomUUID()).also { check(it.mkdir()); owned += it }
        val source = File(root, "Source").apply { mkdir() }
        val destination = File(root, "Destination").apply { mkdir() }
        val state = File(root, "State").apply { mkdir() }
        val journals = File(root, "Journals").apply { mkdir() }
        val storage = AndroidPartialCopyStorage()
        val files = AndroidUndoHistoryFiles(storage)
        val engine = FileEngine(state)
        init {
            File(source, "New/Nested").mkdirs(); File(source, "New/Nested/Keep.txt").writeText("new disclosed data")
            File(source, "Empty").mkdir(); File(source, "Update.txt").writeText("new reviewed content")
            File(destination, "Update.txt").writeText("original content")
            File(destination, "Only here").mkdir(); File(destination, "Only here/Keep.txt").writeText("destination original")
        }
        fun allowed(path: String) = File(path).canonicalPath.let { it == root.canonicalPath || it.startsWith(root.canonicalPath + "/") }
        fun inspect(): FolderSyncInspection {
            fun node(path: String): ComparisonNode {
                val stamp = storage.stat(path)!!
                return ComparisonNode(path, File(path).name, stamp.directory, stamp.size.takeIf { stamp.regular }, stamp.modifiedSeconds * 1000)
            }
            return inspectFolderSync(source.path, destination.path, FolderSyncDirection.PRIMARY_TO_SECONDARY,
                ::allowed, ::node, { File(it).listFiles()!!.map { f -> node(f.path) } },
                { path, stamp, control -> folderSyncDigest(path, stamp, control, storage) }, files)
        }
        fun prepare(selected: Set<String>? = null): PreparedFolderSync {
            val inspection = inspect()
            return prepareFolderSync(inspection, reviewFolderSync(inspection.preview, selected ?: inspection.preview.initialSelection,
                confirmUpdates = true, confirmTrash = true), ::allowed, files, storage)
        }
        fun apply(prepared: PreparedFolderSync, fs: UndoHistoryFiles = files, afterPublished: () -> Unit = {},
            token: TransferControl = TransferControl(), finish: () -> Unit = {}): FolderSyncApplyState =
            applyPreparedFolderSync(prepared, engine, journals.path, ::allowed, { _, _ -> }, { _, _ -> afterPublished() },
                finish, fs, storage, token)
    }
    @After fun cleanOnlyOwnedFixtures() {
        owned.forEach { check(it.parentFile == context.cacheDir && it.name.startsWith("sync-apply-native-") &&
            it.canonicalFile.parentFile == context.cacheDir.canonicalFile); check(it.deleteRecursively()) }
    }
    @Test fun completeDefaultPlanPublishesNestedAndEmptyFoldersWithoutChangingEitherOriginal() {
        val f = Fixture(); val prepared = f.prepare(); val before = prepared.inspection.primaryProof
        val result = f.apply(prepared)
        assertEquals(2, result.completed.size); assertEquals(2, result.publishedRoots.size)
        assertEquals(before, f.files.observe(f.source.path)); assertEquals("original content", File(f.destination, "Update.txt").readText())
        assertEquals("destination original", File(f.destination, "Only here/Keep.txt").readText())
        assertEquals("new disclosed data", File(f.destination, "New/Nested/Keep.txt").readText()); assertTrue(File(f.destination, "Empty").isDirectory)
    }
    @Test fun selectedReplacementAndDeletionKeepNativeOriginalsAndDurableMappings() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt", "Only here"))
        val original = prepared.inspection.secondaryProof.entries.getValue(File(f.destination, "Update.txt").path)
        val result = f.apply(prepared)
        assertEquals(2, result.completed.size); assertEquals(2, result.preservedOriginals.size)
        assertEquals("new reviewed content", File(f.destination, "Update.txt").readText()); assertFalse(File(f.destination, "Only here").exists())
        for (intent in result.preservedOriginals) assertEquals(intent.original, FileEngine(f.state).originalPath(File(intent.recovery)))
        val update = result.preservedOriginals.single { it.original.endsWith("Update.txt") }
        assertEquals(original.inode, f.storage.stat(update.recovery)!!.inode); assertEquals("original content", File(update.recovery).readText())
        val deletion = result.preservedOriginals.single { it.original.endsWith("Only here") }
        assertEquals("destination original", File(deletion.recovery, "Keep.txt").readText())
    }
    @Test fun actualKernelCollisionKeepsTheArrivalAndTheCompleteStagedTree() {
        val f = Fixture(); val prepared = f.prepare(setOf("New"))
        val files = object: UndoHistoryFiles by f.files {
            override fun moveNoReplace(from: File, to: File) { check(to.mkdir()); File(to, "Arrival.txt").writeText("keep arriving data"); f.files.moveNoReplace(from, to) }
        }
        try { f.apply(prepared, files); fail() } catch (error: FolderSyncApplyFailure) {
            assertTrue(error.state.renameAttempted); assertNotNull(error.state.publication); assertTrue(error.state.completed.isEmpty())
            assertEquals("keep arriving data", File(f.destination, "New/Arrival.txt").readText())
            assertEquals("new disclosed data", File(prepared.roots.single().path, "Nested/Keep.txt").readText())
        }
    }
    @Test fun metadataFailureRetainsVerifiedNativePublicationAndBlocksFurtherChanges() {
        val f = Fixture(); val prepared = f.prepare()
        try { f.apply(prepared, afterPublished = { error("Synthetic metadata failure") }); fail() }
        catch (error: FolderSyncApplyFailure) {
            assertEquals(1, error.state.publishedRoots.size); assertNotNull(error.state.publication)
            assertTrue(File(error.state.publishedRoots.single().destination).exists()); assertEquals(1, prepared.roots.count { File(it.path).exists() })
        }
    }
    @Test fun stopBetweenRootsKeepsThePublishedItemAndNeverStartsTheNextMove() {
        val f = Fixture(); val prepared = f.prepare(); val token = TransferControl()
        try { f.apply(prepared, afterPublished = token::cancel, token = token); fail() }
        catch (error: FolderSyncApplyCancelled) {
            assertEquals(1, error.state.publishedRoots.size); assertNotNull(error.state.publication)
            assertEquals(1, prepared.roots.count { File(it.path).exists() }); assertTrue(File(f.destination, "Empty").isDirectory)
        }
    }
    @Test fun finalMetadataFlushFailureKeepsEveryPublishedResultAndManualJournal() {
        val f = Fixture(); val prepared = f.prepare()
        try { f.apply(prepared, finish = { error("Synthetic durable flush failure") }); fail() }
        catch (error: FolderSyncApplyFailure) {
            assertEquals(2, error.state.completed.size); assertEquals(2, error.state.publishedRoots.size)
            assertTrue(File(error.state.journalDirectory!!).isDirectory)
            assertFalse(File(error.state.journalDirectory!!).listFiles()!!.any { it.readText().contains("\"event\":\"finished\"") })
        }
    }
    @Test fun staleDestinationStopsTheCompletePlanBeforeAnyNativeMove() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt", "Only here"))
        File(f.destination, "Arrival.txt").writeText("later original")
        try { f.apply(prepared); fail() } catch (error: FolderSyncApplyFailure) {
            assertFalse(error.state.renameAttempted); assertTrue(error.state.preservedOriginals.isEmpty())
            assertEquals("original content", File(f.destination, "Update.txt").readText())
            assertEquals("destination original", File(f.destination, "Only here/Keep.txt").readText())
            assertEquals("later original", File(f.destination, "Arrival.txt").readText())
        }
    }
    @Test fun nativeRunRecordSurvivesAReopenWithoutStartingAnyWork() {
        val f = Fixture(); val id = UUID.randomUUID().toString(); val directory = File(f.state, "Records")
        val record = FolderSyncRunRecord(id, f.source.path, f.destination.path, File(f.root, ".findroid-sync-$id").path,
            File(f.journals, "sync-$id").path, 2, System.currentTimeMillis())
        FolderSyncRunRecords(directory, f.journals).save(record, f.storage)
        assertEquals(listOf(record), FolderSyncRunRecords(directory, f.journals).load(f.storage))
        assertFalse(File(record.working).exists()); assertFalse(File(record.journal).exists())
        assertEquals("original content", File(f.destination, "Update.txt").readText())
    }
}
