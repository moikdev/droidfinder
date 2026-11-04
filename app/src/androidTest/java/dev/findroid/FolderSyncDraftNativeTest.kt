package dev.findroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Draft engine checks only. These do not claim a complete sync UI or an accepted release. */
@RunWith(AndroidJUnit4::class)
class FolderSyncDraftNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedRoots = mutableListOf<File>()
    private fun newRoot() = File(context.cacheDir, "sync-native-" + UUID.randomUUID()).also {
        check(it.mkdir()); ownedRoots += it
    }
    @After fun keepUnrelatedFilesAndRemoveOnlyOwnedFixtures() {
        ownedRoots.forEach { root ->
            check(root.parentFile == context.cacheDir && root.name.startsWith("sync-native-") &&
                root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
    private inner class Fixture {
        val root = newRoot()
        val source = File(root, "Source").apply { mkdir() }
        val destination = File(root, "Destination").apply { mkdir() }
        val state = File(root, "State").apply { mkdir() }
        val storage = AndroidPartialCopyStorage()
        val files = AndroidUndoHistoryFiles(storage)
        val engine = FileEngine(state)
        init {
            File(source, "New/Sub").mkdirs(); File(source, "New/Sub/A.txt").writeText("reviewed nested addition")
            File(source, "Empty").mkdir(); File(source, "Update.txt").writeText("new reviewed bytes")
            File(destination, "Update.txt").writeText("original destination bytes")
            File(destination, "Keep.txt").writeText("keep unrelated destination")
        }
        fun allowed(path: String) = File(path).canonicalPath.let { it == root.canonicalPath || it.startsWith(root.canonicalPath + "/") }
        fun node(path: String): ComparisonNode {
            val stamp = storage.stat(path) ?: error("Missing fixture node")
            return ComparisonNode(path, File(path).name, stamp.directory, if (stamp.regular) stamp.size else null,
                stamp.modifiedSeconds * 1000 + stamp.modifiedNanos / 1_000_000, !stamp.directory && !stamp.regular)
        }
        fun inspect() = inspectFolderSync(source.path, destination.path, FolderSyncDirection.PRIMARY_TO_SECONDARY,
            ::allowed, ::node, { path -> File(path).listFiles()!!.map { node(it.path) } },
            { path, stamp, token -> folderSyncDigest(path, stamp, token, storage) }, files)
        fun prepare(inspection: FolderSyncInspection, selected: Set<String> = inspection.preview.initialSelection) =
            prepareFolderSync(inspection, reviewFolderSync(inspection.preview, selected, confirmUpdates = "Update.txt" in selected),
                ::allowed, files, storage)
        fun journal(prepared: PreparedFolderSync) = FolderSyncPublicationLog.create(state.path, prepared.directory, destination.path, storage)
        fun stagedHashes(inspection: FolderSyncInspection, item: FolderSyncStagedRoot, proof: UndoPathProof) =
            proof.entries.filterValues { it.regular }.keys.associateWith { path ->
                inspection.contents.getValue(item.operation.source!!.path + path.removePrefix(item.path))
            }
    }
    @Test fun disclosedSubtreesPublishWithNativeIdentitiesAndKeepTheSourceAndOtherDestinationFiles() {
        val f = Fixture(); val inspection = f.inspect(); val prepared = f.prepare(inspection)
        checkPreparedFolderSync(prepared, f::allowed, f.files, f.storage)
        val journal = f.journal(prepared)
        var destination = inspection.secondaryProof
        var working = prepared.proof
        for (item in prepared.roots) {
            val root = folderSyncSubtreeProof(working, item.path)
            val result = publishFolderSyncRoot(destination, working, root, item.operation.destinationPath,
                f.stagedHashes(inspection, item, root), f::allowed, journal::begin, f.files, f.storage)
            journal.complete(FolderSyncPublicationIntent(item.path, item.operation.destinationPath))
            assertEquals(root.entries.getValue(root.path).inode, result.published.entries.getValue(result.published.path).inode)
            destination = result.destination; working = result.working
        }
        journal.finish()
        assertEquals("reviewed nested addition", File(f.destination, "New/Sub/A.txt").readText())
        assertTrue(File(f.destination, "Empty").isDirectory)
        assertEquals("keep unrelated destination", File(f.destination, "Keep.txt").readText())
        assertEquals("original destination bytes", File(f.destination, "Update.txt").readText())
        assertEquals(inspection.primaryProof, f.files.observe(f.source.path))
    }
    @Test fun replacementKeepsTheNativeOriginalAndItsDurableMappingBeforePublishingPreparedBytes() {
        val f = Fixture(); val inspection = f.inspect(); val prepared = f.prepare(inspection, setOf("Update.txt"))
        checkPreparedFolderSync(prepared, f::allowed, f.files, f.storage)
        val target = File(f.destination, "Update.txt")
        val old = folderSyncSubtreeProof(inspection.secondaryProof, target.path)
        val location = f.engine.reviewSyncTrash(listOf(old), f.files, f.storage)
        val journal = f.journal(prepared); var intent: FolderSyncPreservationIntent? = null
        val saved = f.engine.trashReviewedByRename(old, mapOf(target.path to inspection.contents.getValue(target.path)), f.files,
            f.storage, recovery = location, recordIntent = { value -> journal.beginPreservation(value); intent = value })
        journal.completePreservation(intent!!)
        val after = f.files.observe(f.destination.path)
        checkFolderSyncTreeChange(inspection.secondaryProof, after, target.path)
        val item = prepared.roots.single(); val root = folderSyncSubtreeProof(prepared.proof, item.path)
        publishFolderSyncRoot(after, prepared.proof, root, target.path, f.stagedHashes(inspection, item, root),
            f::allowed, journal::begin, f.files, f.storage)
        journal.complete(FolderSyncPublicationIntent(item.path, target.path)); journal.finish()
        assertEquals("original destination bytes", saved.readText()); assertEquals("new reviewed bytes", target.readText())
        assertEquals(target.path, FileEngine(f.state).originalPath(saved))
        assertEquals(old.entries.getValue(old.path).inode, f.storage.stat(saved.path)!!.inode)
        assertEquals("keep unrelated destination", File(f.destination, "Keep.txt").readText())
        assertEquals(inspection.primaryProof, f.files.observe(f.source.path))
    }
    @Test fun anActualKernelNameCollisionKeepsTheArrivalAndTheEntirePreparedTree() {
        val f = Fixture(); val inspection = f.inspect(); val prepared = f.prepare(inspection, setOf("New"))
        val item = prepared.roots.single(); val root = folderSyncSubtreeProof(prepared.proof, item.path)
        val target = File(item.operation.destinationPath); val journal = f.journal(prepared)
        val colliding = object : UndoHistoryFiles by f.files {
            override fun moveNoReplace(from: File, to: File) {
                to.writeText("arrived immediately before native rename")
                f.files.moveNoReplace(from, to)
            }
        }
        val error = try {
            publishFolderSyncRoot(inspection.secondaryProof, prepared.proof, root, target.path,
                f.stagedHashes(inspection, item, root), f::allowed, journal::begin, colliding, f.storage)
            throw AssertionError("Expected the kernel collision to stop publication")
        } catch (error: FolderSyncPublicationFailure) { error }
        assertTrue(error.moveAttempted); assertFalse(error.moved)
        assertEquals("arrived immediately before native rename", target.readText())
        assertEquals("reviewed nested addition", File(item.path, "Sub/A.txt").readText())
        assertEquals(prepared.proof, f.files.observe(prepared.directory)); assertTrue(runCatching { journal.finish() }.isFailure)
        assertEquals(inspection.primaryProof, f.files.observe(f.source.path))
    }
    @Test fun anOriginalEditedDuringDurableIntentRecordingStaysInPlaceAndStopsTheNativeMove() {
        val f = Fixture(); val inspection = f.inspect(); val prepared = f.prepare(inspection, setOf("Update.txt"))
        val target = File(f.destination, "Update.txt"); val proof = folderSyncSubtreeProof(inspection.secondaryProof, target.path)
        val location = f.engine.reviewSyncTrash(listOf(proof), f.files, f.storage); val journal = f.journal(prepared)
        val error = try {
            f.engine.trashReviewedByRename(proof, mapOf(target.path to inspection.contents.getValue(target.path)), f.files,
                f.storage, recovery = location, recordIntent = { journal.beginPreservation(it); target.writeText("last edit after review") })
            throw AssertionError("Expected the edited original to stop the move")
        } catch (error: FolderSyncRecoveryFailure) { error }
        assertFalse(error.moveAttempted); assertNull(error.recovery)
        assertEquals("last edit after review", target.readText()); assertTrue(f.engine.trashItems().isEmpty())
        assertEquals("new reviewed bytes", File(prepared.roots.single().path).readText())
        assertTrue(runCatching { journal.finish() }.isFailure)
    }
}
