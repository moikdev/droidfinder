package dev.findroid

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderSyncPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private inner class Fixture(folder: Boolean = false) {
        val base = temporary.newFolder()
        val destination = File(base, "Destination").apply { mkdir(); File(this, "Keep.txt").writeText("destination original") }
        val working = File(base, "Working").apply { mkdir(); File(this, "review.json").writeText("immutable review") }
        val source = File(working, "copy-00000").apply {
            if (folder) { mkdir(); File(this, "Nested").mkdir(); File(this, "Nested/A.txt").writeText("prepared bytes") }
            else writeText("prepared bytes")
        }
        val target = File(destination, "New")
        val storage = FolderSyncNioStorage()
        val files = FolderSyncNioProofs(storage)
        val destinationProof = files.observe(destination.path)
        val workingProof = files.observe(working.path)
        val root = folderSyncSubtreeProof(workingProof, source.path)
        val hashes = root.entries.filterValues { it.regular }.keys.associateWith { path ->
            MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }
        }
        val intents = mutableListOf<FolderSyncPublicationIntent>()
        fun publish(record: (FolderSyncPublicationIntent) -> Unit = { intents += it }, allowed: (String) -> Boolean = { true },
            control: TransferControl = TransferControl()) = publishFolderSyncRoot(destinationProof, workingProof, root, target.path,
                hashes, allowed, record, files, storage, control)
    }
    private fun stopped(work: () -> Unit): FolderSyncPublicationFailure {
        try { work(); fail("Expected publication refusal") } catch (error: FolderSyncPublicationFailure) { return error }
        error("Unreachable")
    }
    @Test fun aPreparedFileIsRenamedOnlyAfterItsIntentIsRecorded() {
        val f = Fixture()
        f.files.beforeRename = { _, _ -> assertEquals(listOf(FolderSyncPublicationIntent(f.source.path, f.target.path)), f.intents) }
        val result = f.publish()
        assertFalse(f.source.exists()); assertEquals("prepared bytes", f.target.readText())
        assertEquals(f.root.entries.getValue(f.source.path).inode, result.published.entries.getValue(f.target.path).inode)
        assertEquals(f.files.observe(f.destination.path), result.destination); assertEquals(f.files.observe(f.working.path), result.working)
        assertEquals("destination original", File(f.destination, "Keep.txt").readText())
        assertEquals("immutable review", File(f.working, "review.json").readText())
    }
    @Test fun aCompletePreparedSubtreeIsRenamedWithItsOriginalIdentities() {
        val f = Fixture(true); val result = f.publish()
        assertEquals("prepared bytes", File(f.target, "Nested/A.txt").readText())
        assertEquals(f.root.entries.getValue(f.source.path + "/Nested/A.txt").inode,
            result.published.entries.getValue(f.target.path + "/Nested/A.txt").inode)
    }
    @Test fun anOccupiedNameIsKeptBeforeAnyIntentOrMove() {
        val f = Fixture(); f.target.writeText("keep occupied")
        val error = stopped { f.publish() }
        assertFalse(error.moveAttempted); assertTrue(f.intents.isEmpty()); assertEquals("keep occupied", f.target.readText())
        assertEquals("prepared bytes", f.source.readText())
    }
    @Test fun aKernelCollisionKeepsTheNewArrivalAndTheWorkingFile() {
        val f = Fixture(); f.files.beforeRename = { _, to -> to.writeText("latest arrival") }
        val error = stopped { f.publish() }
        assertTrue(error.moveAttempted); assertFalse(error.moved)
        assertEquals("latest arrival", f.target.readText()); assertEquals("prepared bytes", f.source.readText())
    }
    @Test fun anUnrelatedDestinationEditCannotBecomeTheNextBaseline() {
        val f = Fixture(); File(f.destination, "Keep.txt").writeText("latest edit")
        val error = stopped { f.publish() }
        assertFalse(error.moveAttempted); assertFalse(f.target.exists()); assertEquals("latest edit", File(f.destination, "Keep.txt").readText())
    }
    @Test fun anUnreviewedWorkingFileStopsPublication() {
        val f = Fixture(); File(f.working, "Unreviewed.txt").writeText("keep")
        val error = stopped { f.publish() }
        assertFalse(error.moveAttempted); assertEquals("keep", File(f.working, "Unreviewed.txt").readText())
    }
    @Test fun aRewrittenPreparedFileCannotBePublished() {
        val f = Fixture(); f.source.writeText("changed bytes")
        val error = stopped { f.publish() }
        assertFalse(error.moveAttempted); assertFalse(f.target.exists()); assertEquals("changed bytes", f.source.readText())
    }
    @Test fun aFailedDurableIntentWritePreventsTheRename() {
        val f = Fixture(); val error = stopped { f.publish(record = { error("disk full while recording intent") }) }
        assertFalse(error.moveAttempted); assertTrue(f.source.exists()); assertFalse(f.target.exists())
    }
    @Test fun aChangeDuringIntentRecordingIsRecheckedBeforeTheRename() {
        val f = Fixture(); val error = stopped { f.publish(record = { f.intents += it; File(f.destination, "Arrival.txt").writeText("keep") }) }
        assertFalse(error.moveAttempted); assertTrue(f.source.exists()); assertEquals("keep", File(f.destination, "Arrival.txt").readText())
    }
    @Test fun aPostMoveSyncFaultKeepsTheCompletedPublicationForManualReview() {
        val f = Fixture(); f.files.failFinalSync = true
        val error = stopped { f.publish() }
        assertTrue(error.moved); assertEquals(f.target.path, error.intent.destination)
        assertEquals("prepared bytes", f.target.readText()); assertFalse(f.source.exists())
    }
    @Test fun anUnrelatedLastMomentEditStopsFurtherWorkAfterKeepingThePublishedFile() {
        val f = Fixture(); f.files.beforeRename = { _, _ -> File(f.destination, "Keep.txt").writeText("last moment edit") }
        val error = stopped { f.publish() }
        assertTrue(error.moved); assertEquals("prepared bytes", f.target.readText())
        assertEquals("last moment edit", File(f.destination, "Keep.txt").readText())
    }
    @Test fun cancellationAndLostAccessBothStopBeforePublication() {
        val f = Fixture(); assertFalse(stopped { f.publish(control = TransferControl().apply { cancel() }) }.moveAttempted)
        assertFalse(stopped { f.publish(allowed = { it != f.destination.path }) }.moveAttempted)
        assertTrue(f.source.exists()); assertTrue(f.intents.isEmpty()); assertFalse(f.target.exists())
    }
}
