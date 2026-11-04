package dev.findroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryMarkerLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder() }
    private val directory by lazy { File(root, "Notices") }
    private val marker by lazy { File(root, "active-transfer.txt") }
    private val store by lazy { RecoveryNoticeStore(directory) }
    private val native = FolderSyncNioStorage()
    private val storage = object : PartialCopyStorage by native {
        override fun remove(path: String) { Files.delete(File(path).toPath()) }
    }
    private fun begin(label: String = "Copy project", destination: String = "/recorded/destination") =
        store.begin(marker, label, destination, 123L, storage)
    private fun refuses(work: () -> Unit) {
        try { work(); fail("Expected a protected refusal") } catch (_: IllegalStateException) { }
    }

    @Test fun laterWorkPreservesTheExactEarlierMarkerBeforeReplacingIt() {
        val bytes = "Move old\n/old\n100".toByteArray(); marker.writeBytes(bytes)
        val old = store.current(marker, storage)!!
        val token = begin()
        assertArrayEquals(bytes, File(directory, old.id + ".txt").readBytes())
        assertEquals(token.notice, store.current(marker, storage))
        store.finish(marker, token, true, storage)
        assertFalse(marker.exists()); assertEquals(listOf(old), store.load(storage).records)
    }
    @Test fun failedWorkKeepsItsMarkerAndADurableReopenableNotice() {
        val token = begin(); val bytes = marker.readBytes()
        store.finish(marker, token, false, storage)
        assertArrayEquals(bytes, marker.readBytes())
        assertEquals(listOf(token.notice), RecoveryNoticeStore(directory).load(storage).records)
        assertEquals(token.notice, store.currentForReview(marker, storage))
    }
    @Test fun liveMarkerIsExcludedUntilTheProcessIsReopened() {
        val token = begin()
        assertNull(store.currentForReview(marker, storage))
        assertEquals(token.notice, RecoveryNoticeStore(directory).currentForReview(marker, storage))
    }
    @Test fun acknowledgmentKeepsTheCurrentMarkerAndIsBoundToItsDigest() {
        marker.writeText("Copy old\n/old\n1"); val old = store.current(marker, storage)!!
        val stamp = storage.stat(marker.path)
        assertEquals(old, store.acknowledge(marker, old.id, storage))
        assertEquals(stamp, storage.stat(marker.path))
        marker.writeText("Copy new\n/new\n2")
        refuses { store.acknowledge(marker, old.id, storage) }
        assertEquals("Copy new", store.current(marker, storage)!!.label)
        assertEquals(listOf(old), store.load(storage).records)
    }
    @Test fun anUnwritableArchiveStopsNewWorkWithoutDeletingTheOldMarker() {
        val bytes = "Move old\n/old\n100".toByteArray(); marker.writeBytes(bytes)
        native.afterWrite = { File(it).writeText("incomplete") }
        refuses { begin() }
        assertArrayEquals(bytes, marker.readBytes()); assertEquals("incomplete", directory.listFiles()!!.single().readText())
    }
    @Test fun failedNewMarkerReadbackLeavesTheDamagedMarkerForManualReview() {
        native.afterWrite = { File(it).writeText("partial") }
        refuses { begin() }
        assertEquals("partial", marker.readText()); assertFalse(directory.exists())
    }
    @Test fun completionCannotDeleteAChangedMarker() {
        val token = begin(); marker.writeText("Different operation\n/elsewhere\n456")
        refuses { store.finish(marker, token, true, storage) }
        assertEquals("Different operation", store.currentForReview(marker, storage)!!.label)
    }
    @Test fun completionCannotDeleteAnIdenticalReplacementObject() {
        val token = begin(); val bytes = marker.readBytes()
        val held = File(root, "held"); Files.move(marker.toPath(), held.toPath()); marker.writeBytes(bytes)
        refuses { store.finish(marker, token, true, storage) }
        assertArrayEquals(bytes, marker.readBytes()); assertArrayEquals(bytes, held.readBytes())
    }
    @Test fun completionRefusesARedirectedMarkerAndKeepsTheTarget() {
        val token = begin(); val original = File(root, "Original").apply { writeText("keep original") }
        Files.delete(marker.toPath()); Files.createSymbolicLink(marker.toPath(), original.toPath())
        refuses { store.finish(marker, token, true, storage) }
        assertEquals("keep original", original.readText()); assertTrue(Files.isSymbolicLink(marker.toPath()))
    }
    @Test fun hardLinkedPriorMarkerCannotBeRetired() {
        val original = File(root, "Original").apply { writeText("keep original") }
        Files.createLink(marker.toPath(), original.toPath())
        refuses { begin() }; assertEquals("keep original", original.readText()); assertFalse(directory.exists())
    }
    @Test fun failureToArchiveAnUnsuccessfulOperationKeepsItsMarkerReviewable() {
        val token = begin(); val bytes = marker.readBytes()
        native.afterWrite = { File(it).writeText("incomplete") }
        refuses { store.finish(marker, token, false, storage) }
        assertArrayEquals(bytes, marker.readBytes()); assertEquals(token.notice, store.currentForReview(marker, storage))
    }
    @Test fun aLaterSuccessfulOperationKeepsAllPriorFailedNotices() {
        val first = begin(); store.finish(marker, first, false, storage)
        val second = begin("Next"); store.finish(marker, second, false, storage)
        val third = begin("Last"); store.finish(marker, third, true, storage)
        assertEquals(setOf(first.notice, second.notice), store.load(storage).records.toSet()); assertFalse(marker.exists())
    }
    @Test fun versionedMarkersRetainNewlinesAndUnicodeInRecordedPaths() {
        val token = begin("Move 雪\nproject", "/recorded/雪\nsecond line/Report.txt")
        assertEquals("Move 雪\nproject", token.notice.label)
        assertEquals("/recorded/雪\nsecond line/Report.txt", token.notice.destination)
        assertEquals(123L, token.notice.recordedAt)
    }
    @Test fun malformedVersionedTextDoesNotInventADestination() {
        val notice = RecoveryNotice.fromText("findroid-operation-v1\n!!invalid\n!!invalid\nwrong date")
        assertNull(notice.label); assertNull(notice.destination); assertNull(notice.recordedAt)
    }
    @Test fun anOversizedNewNoticeLeavesThePriorMarkerUntouched() {
        marker.writeText("Copy old\n/old\n1"); val bytes = marker.readBytes()
        refuses { begin(destination = "x".repeat(65_536)) }
        assertArrayEquals(bytes, marker.readBytes()); assertFalse(directory.exists())
    }
    @Test fun twoOperationsWithTheSameTimeAndDestinationHaveDifferentAcknowledgmentIds() {
        val first = begin(); store.finish(marker, first, false, storage)
        val second = begin()
        assertNotEquals(first.notice.id, second.notice.id)
        refuses { store.acknowledge(marker, first.notice.id, storage) }
        assertEquals(second.notice, store.current(marker, storage))
    }
    @Test fun archiveSyncFailureRetainsTheOriginalAndAnUnchangedRetrySyncsTheCopy() {
        marker.writeText("Copy old\n/old\n1"); val bytes = marker.readBytes()
        var failSync = true; var archiveSyncs = 0
        val faulty = object : PartialCopyStorage by storage {
            override fun open(path: String, writable: Boolean, createNew: Boolean, directory: Boolean): PartialCopyHandle {
                val delegate = storage.open(path, writable, createNew, directory)
                return object : PartialCopyHandle by delegate {
                    override fun sync() {
                        if (File(path).parentFile == this@RecoveryMarkerLifecycleTest.directory && !directory) {
                            archiveSyncs++; check(!failSync) { "Synthetic archive sync fault" }
                        }
                        delegate.sync()
                    }
                }
            }
        }
        refuses { store.begin(marker, "Next", "/new", 2, faulty) }
        assertArrayEquals(bytes, marker.readBytes()); assertEquals(1, archiveSyncs)
        failSync = false; store.begin(marker, "Next", "/new", 2, faulty)
        assertEquals(2, archiveSyncs); assertArrayEquals(bytes, directory.listFiles()!!.single().readBytes())
    }
}
