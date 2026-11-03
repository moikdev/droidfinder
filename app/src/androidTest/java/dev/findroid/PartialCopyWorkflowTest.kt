package dev.findroid

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PartialCopyWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var fixture: File
    private lateinit var source: File
    private lateinit var output: File
    private lateinit var record: PartialCopyRecord
    private lateinit var originalJobs: List<TransferJob>
    private lateinit var job: TransferJob
    private val bytes = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Before fun setup() {
        compose.waitUntil(20_000) { model.busy == null }
        fixture = File(model.workspace, "Partial-${UUID.randomUUID()}").apply { check(mkdirs()) }
        source = File(fixture, "Original.bin").apply { writeBytes(bytes) }
        output = File(fixture, "Out").apply { check(mkdir()) }
        val privateRoot = File(compose.activity.filesDir, ".partial-copies").apply { mkdirs() }
        val storage = AndroidPartialCopyStorage()
        val recovery = PartialCopyRecovery(storage, privateRoot.path)
        val empty = recovery.allocate(source.path, File(output, source.name).path, true)
        val prefix = bytes.copyOf(4 * 1024 * 1024)
        storage.open(empty.source).use { input -> storage.open(empty.staging, writable = true).use { stage ->
            var written = 0
            while (written < prefix.size) written += stage.write(prefix, written, prefix.size - written)
            record = recovery.checkpoint(empty, input, stage, prefix.size.toLong(), hash(prefix))
        } }
        job = TransferJob(paths = listOf(source.path), destination = output.path, move = false,
            state = TransferState.INTERRUPTED, inFlight = source.path, verifyCopies = true, partialCopy = record)
        compose.runOnIdle {
            originalJobs = model.transferJobs
            if (model.dualPane) model.toggleDualPane()
            model.clearSelection(); model.changeMode(ViewMode.LIST); model.navigate(fixture.path)
            model.session.transferJournal.save(originalJobs + job)
            model.session.transferJobs.value = originalJobs + job
        }
        compose.waitUntil(15_000) { !model.loading }
    }

    @After fun cleanup() {
        if (::job.isInitialized) {
            compose.runOnIdle {
                model.cancelPartialCopyReview()
                model.transferJobs.filter { it.id !in originalJobs.map { it.id } && !it.terminal }.forEach { model.cancelQueuedTransfer(it.id) }
            }
            compose.waitUntil(20_000) { model.busy == null }
            compose.runOnIdle {
                model.session.partialCopyApprovals.clear()
                model.session.transferJobs.value = originalJobs
                model.session.transferJournal.save(originalJobs)
                model.navigate(model.workspace.path)
            }
        }
        if (::record.isInitialized) check(File(record.staging).parentFile!!.deleteRecursively() || !File(record.staging).parentFile!!.exists())
        if (::fixture.isInitialized) check(fixture.deleteRecursively())
    }

    private fun openReview() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Transfers").performScrollTo().performClick()
        compose.onNodeWithTag("review-saved-copy-${job.id}").performScrollTo().performClick()
        compose.waitUntil(20_000) { model.partialCopyReview?.checking == false }
        compose.onNodeWithTag("saved-copy-review").assertIsDisplayed()
    }
    private fun capture(name: String) {
        val folder = File(compose.activity.cacheDir, "partial-copy-qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onNodeWithTag("saved-copy-review").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun namedReviewResumesExactBytesAndKeepsDurableSeparateHistory() {
        openReview(); capture("ready")
        compose.onNodeWithTag("resume-saved-copy").performScrollTo().performClick()
        compose.waitUntil(30_000) { model.transferJobs.size == originalJobs.size + 2 && model.busy == null && model.transferJobs.last().terminal }
        val resumed = model.transferJobs.last()
        assertEquals(resumed.current, TransferState.COMPLETED, resumed.state)
        assertEquals(record.offset, resumed.reusedBytes); assertEquals(bytes.size - record.offset, resumed.bytes)
        assertNull(resumed.partialCopy); assertTrue(resumed.verifyCopies)
        assertEquals(CopyVerificationStatus.VERIFIED, resumed.verifications.single().status)
        assertEquals(hash(bytes), resumed.verifications.single().actualSha256)
        assertArrayEquals(bytes, File(record.target).readBytes()); assertArrayEquals(bytes, source.readBytes())
        val old = model.transferJobs.first { it.id == job.id }; assertNull(old.partialCopy); assertTrue(old.retryTargets.isEmpty())
        SqliteTransferStore(compose.activity).use { assertEquals(resumed, it.load().first { it.id == resumed.id }) }
    }

    @Test fun cancellingAReviewedDialogStartsNoCopyAndKeepsCheckpointBytes() {
        openReview(); compose.onNodeWithText("Close", useUnmergedTree = true).performScrollTo().performClick()
        assertNull(model.partialCopyReview); assertEquals(originalJobs.size + 1, model.transferJobs.size)
        assertTrue(File(record.staging).exists()); assertFalse(File(record.target).exists())
        assertEquals(record, model.transferJobs.last().partialCopy)
    }

    @Test fun clearFinishedKeepsSavedCopiesAvailableForReview() {
        compose.runOnIdle { model.clearTransferHistory() }
        compose.waitUntil(5_000) { model.transferJobs.any { it.id == job.id } }
        assertEquals(record, model.transferJobs.first { it.id == job.id }.partialCopy)
        assertTrue(File(record.staging).exists())
    }

    @Test fun manualDiscardKeepsSourceAndOtherDestinationItems() {
        File(output, "Keep.txt").writeText("Keep this destination")
        openReview(); compose.onNodeWithTag("discard-saved-copy").performScrollTo().performClick()
        compose.onNodeWithText("Discard saved bytes", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { model.partialCopyReview == null }
        assertNull(model.transferJobs.first { it.id == job.id }.partialCopy)
        assertFalse(File(record.staging).exists()); assertArrayEquals(bytes, source.readBytes())
        assertEquals("Keep this destination", File(output, "Keep.txt").readText())
    }

    @Test fun changedSourceBlocksResumeAndOffersOwnedDiscard() {
        source.writeBytes(bytes.copyOf().also { it[0] = 3 })
        openReview(); capture("changed-source")
        assertNull(model.partialCopyReview!!.resume); assertNotNull(model.partialCopyReview!!.cleanup)
        compose.onNodeWithTag("resume-saved-copy").assertDoesNotExist()
        compose.onNodeWithTag("discard-saved-copy").performScrollTo().assertIsDisplayed()
        assertTrue(File(record.staging).exists()); assertFalse(File(record.target).exists())
    }

    @Test fun aDestinationCreatedAfterReviewCannotBeReplacedByResume() {
        openReview(); File(record.target).writeText("Keep new destination")
        compose.onNodeWithTag("resume-saved-copy").performScrollTo().performClick()
        compose.waitUntil(20_000) { model.transferJobs.size == originalJobs.size + 2 && model.busy == null && model.transferJobs.last().terminal }
        val resumed = model.transferJobs.last()
        assertEquals(TransferState.FAILED, resumed.state); assertEquals(record, resumed.partialCopy)
        assertEquals("Keep new destination", File(record.target).readText()); assertTrue(File(record.staging).exists())
    }

    @Test fun uncheckedTailIsDisclosedAndNeverCountedAsReusedBytes() {
        File(record.staging).appendBytes(ByteArray(123) { 9 })
        openReview(); assertEquals(123L, model.partialCopyReview!!.resume!!.discardedTailBytes); capture("unchecked-tail")
        compose.onNodeWithTag("resume-saved-copy").performScrollTo().performClick()
        compose.waitUntil(30_000) { model.transferJobs.size == originalJobs.size + 2 && model.busy == null && model.transferJobs.last().terminal }
        assertEquals(record.offset, model.transferJobs.last().reusedBytes)
        assertArrayEquals(bytes, File(record.target).readBytes())
    }

    @Test fun finishingRecoveryHasCleanupControlsAndKeepsThePublishedCopy() {
        val storage = AndroidPartialCopyStorage()
        File(record.staging).writeBytes(bytes)
        storage.open(record.source).use { input -> storage.open(record.staging, writable = true).use { stage ->
            val recovery = PartialCopyRecovery(storage, File(compose.activity.filesDir, ".partial-copies").path)
            record = recovery.checkpoint(record, input, stage, bytes.size.toLong(), hash(bytes)).copy(publishing = true)
        } }
        NativeFilePublication.moveNoReplace(record.staging, record.target)
        compose.runOnIdle {
            job = job.copy(partialCopy = record)
            model.session.transferJobs.value = originalJobs + job
            model.session.transferJournal.save(model.transferJobs)
        }
        openReview(); capture("publication-review")
        assertNull(model.partialCopyReview!!.resume); assertTrue(model.partialCopyReview!!.cleanup!!.destinationMatches)
        compose.onNodeWithTag("discard-saved-copy").performScrollTo().performClick()
        compose.onNodeWithText("Discard saved bytes", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { model.partialCopyReview == null }
        assertArrayEquals(bytes, File(record.target).readBytes()); assertArrayEquals(bytes, source.readBytes())
    }
}
