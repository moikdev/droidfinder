package dev.findroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryNoticeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder() }
    private val directory by lazy { File(root, "Notices") }
    private val marker by lazy { File(root, "active-transfer.txt") }
    private val store by lazy { RecoveryNoticeStore(directory) }
    private val storage = FolderSyncNioStorage()
    private val payload = "Move project\n/recorded/destination\n123\n".toByteArray()
    private fun refuses(work: () -> Unit) {
        try { work(); fail("Expected a protected refusal") } catch (_: IllegalStateException) { }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
    }
    @Test fun missingRecordsAreReadOnlyAndCreateNothing() {
        assertNull(store.current(marker, storage)); assertNull(store.preserve(marker, storage))
        assertTrue(store.load(storage).records.isEmpty()); assertFalse(directory.exists())
    }
    @Test fun preservingKeepsExactMarkerAndSurvivesAReopenedStore() {
        marker.writeBytes(payload); val record = store.preserve(marker, storage)!!
        assertArrayEquals(payload, marker.readBytes())
        assertEquals(listOf(record), RecoveryNoticeStore(directory).load(storage).records)
        assertArrayEquals(payload, File(directory, record.id + ".txt").readBytes())
        assertEquals(123L, record.recordedAt)
        assertFalse(File("/recorded/destination").exists())
    }
    @Test fun repeatedAcknowledgmentDoesNotRewriteArchiveBytes() {
        marker.writeBytes(payload); val record = store.preserve(marker, storage)!!
        val file = File(directory, record.id + ".txt"); val stamp = storage.stat(file.path)
        assertEquals(record, store.preserve(marker, storage)); assertEquals(stamp, storage.stat(file.path))
        assertEquals(1, directory.listFiles()!!.size)
    }
    @Test fun aLaterMarkerCannotRemoveTheEarlierNotice() {
        marker.writeBytes(payload); val first = store.preserve(marker, storage)!!
        marker.writeText("Copy next\n/other\n456"); val second = store.preserve(marker, storage)!!
        assertEquals(listOf(second, first), store.load(storage).records)
        assertArrayEquals(payload, File(directory, first.id + ".txt").readBytes())
    }
    @Test fun corruptArchiveStopsAcknowledgmentAndKeepsBothCopies() {
        marker.writeBytes(payload); val record = store.preserve(marker, storage)!!
        val archive = File(directory, record.id + ".txt").apply { writeText("changed") }
        refuses { store.preserve(marker, storage) }
        assertArrayEquals(payload, marker.readBytes()); assertEquals("changed", archive.readText())
        assertTrue(store.load(storage).records.isEmpty()); assertTrue(store.load(storage).problems.isNotEmpty())
    }
    @Test fun redirectedMarkerIsNeverReadOrChanged() {
        val original = File(root, "Original").apply { writeBytes(payload) }
        Files.createSymbolicLink(marker.toPath(), original.toPath())
        refuses { store.current(marker, storage) }; refuses { store.preserve(marker, storage) }
        assertArrayEquals(payload, original.readBytes()); assertFalse(directory.exists())
    }
    @Test fun redirectedArchiveFolderIsNeverWritten() {
        val other = temporary.newFolder(); Files.createSymbolicLink(directory.toPath(), other.toPath())
        marker.writeBytes(payload); refuses { store.preserve(marker, storage) }; refuses { store.load(storage) }
        assertTrue(other.listFiles()!!.isEmpty()); assertArrayEquals(payload, marker.readBytes())
    }
    @Test fun oversizedMarkerIsKeptWithoutCreatingAnArchive() {
        marker.writeBytes(ByteArray(65_537) { 42 }); refuses { store.preserve(marker, storage) }
        assertEquals(65_537, marker.length().toInt()); assertFalse(directory.exists())
    }
    @Test fun malformedRecordsDoNotHideASeparateValidNotice() {
        marker.writeBytes(payload); val record = store.preserve(marker, storage)!!
        val damaged = File(directory, "Keep.txt").apply { writeText("manual") }
        val loaded = store.load(storage)
        assertEquals(listOf(record), loaded.records); assertTrue(loaded.problems.isNotEmpty())
        assertEquals("manual", damaged.readText())
    }
    @Test fun failedArchiveWriteKeepsTheCurrentMarkerAndPartialArchive() {
        marker.writeBytes(payload)
        storage.afterWrite = { File(it).writeText("incomplete") }
        refuses { store.preserve(marker, storage) }
        assertArrayEquals(payload, marker.readBytes()); assertEquals("incomplete", directory.listFiles()!!.single().readText())
    }
    @Test fun boundedListingReportsItsLimitAndKeepsEveryUnlistedRecord() {
        check(directory.mkdir())
        repeat(130) { index ->
            val text = "Synthetic record $index\n/recorded/destination\n123\n".padEnd(65_536, 'x')
            val notice = RecoveryNotice.fromText(text)
            File(directory, notice.id + ".txt").writeText(text)
        }
        val before = directory.listFiles()!!.associate { it.name to storage.stat(it.path) }
        val result = store.load(storage)
        assertEquals(128, result.records.size)
        assertTrue(result.problems.any { "8 MiB" in it })
        assertEquals(before, directory.listFiles()!!.associate { it.name to storage.stat(it.path) })
    }
    @Test fun cancellingAReadOnlyScanDoesNotChangeItsRecords() {
        marker.writeBytes(payload); val notice = store.preserve(marker, storage)!!
        val file = File(directory, notice.id + ".txt"); val stamp = storage.stat(file.path)
        try {
            store.load(storage) { throw java.util.concurrent.CancellationException("synthetic scan stop") }
            fail("Expected cancelled scan")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(stamp, storage.stat(file.path)); assertArrayEquals(payload, marker.readBytes())
    }

}
