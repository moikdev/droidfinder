package dev.findroid

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderSyncRunRecordsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base by lazy { temporary.newFolder() }
    private val records by lazy { File(base, "Records") }
    private val journals by lazy { File(base, "Journals") }
    private val store by lazy { FolderSyncRunRecords(records, journals) }
    private val storage = FolderSyncNioStorage()
    private fun record(time: Long = 123L): FolderSyncRunRecord {
        val id = UUID.randomUUID().toString()
        return FolderSyncRunRecord(id, File(base, "Source").path, File(base, "Destination").path,
            File(base, ".findroid-sync-$id").path, File(journals, "sync-$id").path, 3, time)
    }
    private fun refuses(work: () -> Unit) {
        try { work(); fail("Expected protected refusal") } catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
    }
    @Test fun missingFolderIsReadOnlyAndDoesNotCreateARecord() {
        assertTrue(store.load(storage).isEmpty()); assertFalse(records.exists())
    }
    @Test fun intentSurvivesReopenWithoutCreatingWorkingDataOrInferringCompletion() {
        val record = record(); store.save(record, storage)
        assertEquals(listOf(record), FolderSyncRunRecords(records, journals).load(storage))
        assertFalse(File(record.working).exists()); assertFalse(File(record.journal).exists())
        assertFalse(File(record.destination).exists()); assertEquals(1, records.listFiles()!!.size)
    }
    @Test fun recordsAreOrderedByTimeAndOldBytesAreNeverRewritten() {
        val first = record(1); store.save(first, storage)
        val before = File(records, first.id + ".json").readBytes()
        val second = record(2); store.save(second, storage)
        assertEquals(listOf(second, first), store.load(storage))
        assertArrayEquals(before, File(records, first.id + ".json").readBytes())
        refuses { store.save(first, storage) }; assertArrayEquals(before, File(records, first.id + ".json").readBytes())
    }
    @Test fun cancellationPreservesAnAlreadySavedIntentAndCreatesNoWorkingFiles() {
        val record = record(); store.save(record, storage)
        val token = TransferControl().apply { cancel() }
        try { store.save(record(), storage, token); fail() } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(listOf(record), store.load(storage)); assertFalse(File(record.working).exists())
    }
    @Test fun redirectedRecordDirectoryIsRefusedWithoutWritingTheTarget() {
        val other = temporary.newFolder(); Files.createSymbolicLink(records.toPath(), other.toPath())
        refuses { store.save(record(), storage) }; refuses { store.load(storage) }
        assertTrue(other.listFiles()!!.isEmpty())
    }
    @Test fun linkedRecordIsNotReadAndItsTargetIsKept() {
        val record = record(); store.save(record, storage)
        val path = File(records, record.id + ".json"); val target = File(base, "Keep.json")
        Files.move(path.toPath(), target.toPath()); val original = target.readBytes()
        Files.createSymbolicLink(path.toPath(), target.toPath())
        refuses { store.load(storage) }; assertArrayEquals(original, target.readBytes())
    }
    @Test fun oversizedAndUnexpectedRecordsStopListingAndKeepTheirBytes() {
        store.save(record(), storage)
        val path = File(records, "keep.txt").apply { writeText("x".repeat(65_537)) }; val original = path.readBytes()
        refuses { store.load(storage) }; assertArrayEquals(original, path.readBytes())
    }
    @Test fun invalidBoundPathsAndIdentifiersCannotCreateRecords() {
        val record = record()
        for (invalid in listOf(record.copy(id = "invalid"), record.copy(working = File(base, "Unrelated").path),
            record.copy(journal = File(base, "Unrelated").path), record.copy(source = "relative"),
            record.copy(operations = 0), record.copy(createdAt = -1))) refuses { store.save(invalid, storage) }
        assertFalse(records.exists())
    }
    @Test fun changedSavedPathsAreRefusedWithoutTouchingAnyReferencedFile() {
        val record = record(); store.save(record, storage)
        val path = File(records, record.id + ".json")
        path.writeText(path.readText().replace(record.working, File(base, "Keep").path)); val changed = path.readBytes()
        refuses { store.load(storage) }; assertArrayEquals(changed, path.readBytes()); assertFalse(File(base, "Keep").exists())
    }
    @Test fun rewritingSavedBytesDuringDurableWriteStopsBeforePreparation() {
        val record = record()
        storage.afterWrite = { File(it).writeText("changed") }
        refuses { store.save(record, storage) }
        assertFalse(File(record.working).exists()); assertFalse(File(record.destination).exists())
        assertTrue(File(records, record.id + ".json").exists())
    }
}
