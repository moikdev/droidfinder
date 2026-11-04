package dev.findroid

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderSyncRecoveryPreflightTest {
    @get:Rule val temporary = TemporaryFolder()
    private inner class Fixture(val available: Long? = Long.MAX_VALUE) {
        val base = temporary.newFolder()
        val state = File(base, "State").apply { mkdir() }
        val first = File(base, "First.txt").apply { writeText("first original") }
        val second = File(base, "Second.txt").apply { writeText("second original") }
        val storage = FolderSyncNioStorage()
        val files = FolderSyncNioProofs(storage)
        val engine = FileEngine(state) { available }
        val originals = listOf(files.observe(first.path), files.observe(second.path))
        fun review(items: Collection<UndoPathProof> = originals) = engine.reviewSyncTrash(items, files, storage)
    }
    private fun refused(work: () -> Unit) {
        try { work(); fail("Expected complete recovery preflight refusal") } catch (_: Exception) { }
    }
    @Test fun aCompletePlanPreflightLeavesAllOriginalsAndTrashUnchanged() {
        val f = Fixture(); val before = f.files.observe(f.state.path); val location = f.review()
        assertEquals(File(f.state, "Trash").path, location.path)
        assertEquals(before, f.files.observe(f.state.path)); assertTrue(f.engine.trashItems().isEmpty())
        assertEquals("first original", f.first.readText()); assertEquals("second original", f.second.readText())
    }
    @Test fun aLaterCrossFilesystemOriginalRejectsTheEntirePlanBeforeAnyBucketExists() {
        val f = Fixture(); val second = f.originals[1]
        val changed = second.copy(entries = second.entries.mapValues { it.value.copy(device = it.value.device + 1) })
        refused { f.review(listOf(f.originals[0], changed)) }
        assertTrue(f.engine.trashItems().isEmpty()); assertTrue(File(f.state, "Trash").listFiles()!!.isEmpty())
        assertTrue(f.first.exists()); assertTrue(f.second.exists())
    }
    @Test fun changedOriginalsAndDuplicateOrEmptyPlansAreRefusedWithoutSavingMappings() {
        val f = Fixture(); f.second.writeText("edited after review")
        refused { f.review() }; refused { f.review(emptyList()) }; refused { f.review(listOf(f.originals[0], f.originals[0])) }
        assertFalse(File(f.state, "trash.properties").exists()); assertEquals("edited after review", f.second.readText())
    }
    @Test fun unavailableOrInsufficientSpaceRejectsTheCompletePlan() {
        for (available in listOf<Long?>(null, 0L, 1024L)) {
            val f = Fixture(available); refused { f.review() }
            assertFalse(File(f.state, "trash.properties").exists()); assertTrue(f.first.exists()); assertTrue(f.second.exists())
        }
    }
    @Test fun overlappingOriginalSubtreesCannotBeScheduledTwice() {
        val f = Fixture(); val folder = File(f.base, "Folder").apply { mkdir(); File(this, "Child.txt").writeText("keep") }
        refused { f.review(listOf(f.files.observe(folder.path), f.files.observe(File(folder, "Child.txt").path))) }
        assertEquals("keep", File(folder, "Child.txt").readText()); assertTrue(f.engine.trashItems().isEmpty())
    }
    @Test fun neitherTrashItselfNorAnOriginalContainingTrashCanBeMovedIntoItsRecoveryLocation() {
        val f = Fixture(); val trash = File(f.state, "Trash")
        refused { f.review(listOf(f.files.observe(f.state.path))) }
        val inside = File(trash, "Original.txt").apply { writeText("keep recovery data") }
        refused { f.review(listOf(f.files.observe(inside.path))) }
        assertEquals("keep recovery data", inside.readText()); assertFalse(File(f.state, "trash.properties").exists())
    }
    @Test fun theProjectedCompleteMappingMustFitTheExistingTrashRecordLimit() {
        val f = Fixture(); val index = File(f.state, "trash.properties")
        index.writeText("existing=" + "x".repeat(16 * 1024 * 1024 - 64) + "\n")
        val original = index.readBytes(); val engine = FileEngine(f.state) { Long.MAX_VALUE }
        refused { engine.reviewSyncTrash(f.originals, f.files, f.storage) }
        assertArrayEquals(original, index.readBytes()); assertTrue(f.first.exists()); assertTrue(f.second.exists())
    }
    @Test fun aPreflightedTrashRootCannotBeReplacedBeforeMovingAnOriginal() {
        val f = Fixture(); val review = f.review(); val trash = File(review.path); val old = File(f.state, "OldTrash")
        assertTrue(trash.renameTo(old)); assertTrue(trash.mkdir())
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(f.first.readBytes()).joinToString("") { "%02x".format(it) }
        refused { f.engine.trashReviewedByRename(f.originals[0], mapOf(f.first.path to hash), f.files, f.storage, recovery = review) }
        assertEquals("first original", f.first.readText()); assertTrue(trash.listFiles()!!.isEmpty()); assertTrue(old.listFiles()!!.isEmpty())
    }
}
