package dev.findroid

import android.provider.DocumentsContract
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CopyVerificationWorkflowTest {
    @org.junit.Before
    fun resetOwnedUndoSessionForFixture() {
        compose.runOnIdle { resetOwnedUndoFixture(compose.activity.model) }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var fixture: File
    private lateinit var providerOut: File
    private lateinit var local: File
    private lateinit var providerRoot: String
    private val jobs = mutableListOf<String>()
    private val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }

    private fun uri(file: File): String {
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture.name}")
        val relative = file.relativeTo(fixture).path.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
        return DocumentsContract.buildDocumentUriUsingTree(tree, "root/${fixture.name}$relative").toString()
    }

    @Before fun setup() {
        fixture = File(compose.activity.filesDir, "ProviderFixtures/Verify-${System.nanoTime()}").apply { mkdirs() }
        File(fixture, "Original.bin").writeBytes(bytes)
        providerOut = File(fixture, "Out").apply { mkdir() }
        local = File(compose.activity.filesDir, "Workspace/${fixture.name}").apply { mkdirs() }
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture.name}")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST); model.changeGrouping(GroupBy.NONE); model.clearSelection()
            model.connectLocation(tree, false)
        }
        compose.waitUntil(15_000) { !model.loading && model.location == uri(fixture) && model.entries.any { it.name == "Original.bin" } }
        providerRoot = model.location
    }

    @After fun cleanup() {
        FixtureDocumentsProvider.beforeRead = null
        FixtureDocumentsProvider.displayNameOverrides = emptyMap()
        FixtureDocumentsProvider.unknownSizePaths = emptySet()
        FixtureDocumentsProvider.readDelayMillis = emptyMap()
        compose.runOnIdle {
            model.dismissVerifiedCopy()
            model.transferJobs.filter { it.id in jobs && !it.terminal }.forEach { model.cancelQueuedTransfer(it.id) }
        }
        compose.waitUntil(20_000) { model.busy == null }
        compose.runOnIdle {
            model.navigate(model.workspace.path)
            if (::providerRoot.isInitialized)
                model.connectedLocations.filter { it.uri == providerRoot }.forEach { model.disconnectLocation(it.uri) }
        }
        fixture.deleteRecursively(); local.deleteRecursively()
    }

    private fun start(paths: List<String>, destination: String, verify: Boolean = true, policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH): String {
        lateinit var id: String
        compose.runOnIdle {
            model.transferPaths(paths, destination, false, policy = policy, verifyCopies = verify)
            id = model.transferJobs.last().id; jobs += id
        }
        return id
    }

    private fun finish(id: String, state: TransferState = TransferState.COMPLETED): TransferJob {
        compose.waitUntil(30_000) { model.transferJobs.first { it.id == id }.terminal && model.busy == null }
        val job = model.transferJobs.first { it.id == id }
        assertEquals(job.current, state, job.state)
        SqliteTransferStore(compose.activity).use { store ->
            val saved = store.load().first { it.id == id }
            assertEquals(job.verifyCopies, saved.verifyCopies); assertEquals(job.verifications, saved.verifications)
        }
        return job
    }

    private fun staging(file: File) = file.canonicalFile.toPath().startsWith(providerOut.canonicalFile.toPath()) &&
        file.canonicalFile.relativeTo(providerOut.canonicalFile).path.split('/').any { it.startsWith(".findroid-") }

    private fun assertClean() = assertFalse(providerOut.walkTopDown().any { it.name.startsWith(".findroid-") })

    private fun capture(name: String, tag: String) {
        val folder = File(compose.activity.cacheDir, "verification-qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }


    @Test fun knownProviderSourceVerifiesALocalCopy() {
        val source = File(fixture, "Original.bin")
        val job = finish(start(listOf(uri(source)), local.path))
        assertEquals(bytes.size.toLong(), job.bytes)
        assertArrayEquals(bytes, File(local, source.name).readBytes()); assertArrayEquals(bytes, source.readBytes())
        assertEquals(CopyVerificationStatus.VERIFIED, job.verifications.single().status)
        assertEquals(File(local, source.name).path, job.verifications.single().destination)
    }

    @Test fun completedProviderTransfersKeepFriendlyNamesInsteadOfDocumentIds() {
        val source = File(fixture, "document-17").apply { writeBytes(bytes) }
        val friendlyName = "Holiday notes.bin"
        FixtureDocumentsProvider.displayNameOverrides = mapOf(source.canonicalPath to friendlyName)
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(15_000) { !model.loading && model.entries.any { it.name == friendlyName } }
        for (verify in listOf(false, true)) {
            val output = File(local, if (verify) "Verified" else "Ordinary").apply { mkdir() }
            val id = start(listOf(uri(source)), output.path, verify = verify)
            finish(id)
            assertArrayEquals(bytes, File(output, friendlyName).readBytes())
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.onNodeWithText("Transfers").performScrollTo().assertIsDisplayed().performClick()
            val card = "transfer-job-$id"
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer-center").fetchSemanticsNodes().isNotEmpty() }
            var pages = 0
            while (compose.onAllNodesWithTag(card).fetchSemanticsNodes().isEmpty() && pages++ < 100) {
                compose.onNode(hasText("Show more transfers (", substring = true)).performScrollTo().performClick()
            }
            compose.onNodeWithTag(card).performScrollTo()
            compose.onNode(hasText(friendlyName) and hasAnyAncestor(hasTestTag(card))).assertIsDisplayed()
            compose.onNode(hasText(source.name) and hasAnyAncestor(hasTestTag(card))).assertDoesNotExist()
            compose.onNodeWithContentDescription("Close transfers").performClick()
        }
    }

    @Test fun unknownProviderSizeStillVerifiesActualStreamBytes() {
        val source = File(fixture, "Original.bin")
        FixtureDocumentsProvider.unknownSizePaths = setOf(source.canonicalPath)
        val job = finish(start(listOf(uri(source)), local.path))
        assertEquals(bytes.size.toLong(), job.bytes)
        assertEquals(bytes.size.toLong(), job.verifications.single().bytes)
        assertArrayEquals(bytes, File(local, source.name).readBytes())
        assertEquals(job.verifications.single().expectedSha256, job.verifications.single().actualSha256)
    }

    @Test fun localSourceIsReadBackFromTheRealProviderBeforePublication() {
        val source = File(local, "Important.bin").apply { writeBytes(bytes) }
        val reads = AtomicInteger()
        FixtureDocumentsProvider.beforeRead = { if (staging(it)) reads.incrementAndGet() }
        val job = finish(start(listOf(source.path), uri(providerOut)))
        assertEquals(1, reads.get()); assertEquals(bytes.size.toLong(), job.bytes)
        assertArrayEquals(bytes, File(providerOut, source.name).readBytes()); assertArrayEquals(bytes, source.readBytes())
        assertEquals(uri(File(providerOut, source.name)), job.verifications.single().destination)
        assertClean()
    }

    @Test fun ordinaryCopiesKeepTheirExistingBehaviorWithoutOptionalStagingReadback() {
        val source = File(local, "Ordinary.bin").apply { writeBytes(bytes) }
        val stagingReads = AtomicInteger()
        FixtureDocumentsProvider.beforeRead = { if (staging(it)) stagingReads.incrementAndGet() }
        val job = finish(start(listOf(source.path), uri(providerOut), verify = false))
        assertFalse(job.verifyCopies); assertTrue(job.verifications.isEmpty()); assertEquals(0, stagingReads.get())
        assertArrayEquals(bytes, File(providerOut, source.name).readBytes())
    }

    @Test fun sameLengthProviderCorruptionFailsAndCleansUnpublishedCopy() {
        val source = File(local, "Important.bin").apply { writeBytes(bytes) }
        val changed = AtomicBoolean()
        FixtureDocumentsProvider.beforeRead = { file ->
            if (staging(file) && changed.compareAndSet(false, true)) file.writeBytes(bytes.copyOf().apply { this[0] = (this[0] + 1).toByte() })
        }
        val job = finish(start(listOf(source.path), uri(providerOut)), TransferState.FAILED)
        assertTrue(changed.get()); assertEquals(CopyVerificationStatus.MISMATCH, job.verifications.single().status)
        assertArrayEquals(bytes, source.readBytes()); assertFalse(File(providerOut, source.name).exists()); assertClean()
    }

    @Test fun unreadableProviderReadbackIsReportedAndNeverPublished() {
        val source = File(local, "Important.bin").apply { writeBytes(bytes) }
        FixtureDocumentsProvider.beforeRead = { if (staging(it)) throw FileNotFoundException("Synthetic readback denied") }
        val job = finish(start(listOf(source.path), uri(providerOut)), TransferState.FAILED)
        assertEquals(CopyVerificationStatus.UNREADABLE, job.verifications.single().status)
        assertNull(job.verifications.single().destination)
        assertArrayEquals(bytes, source.readBytes()); assertFalse(File(providerOut, source.name).exists()); assertClean()
    }

    @Test fun failedVerificationRestoresAReviewedReplacement() {
        val source = File(local, "Important.bin").apply { writeBytes(bytes) }
        val original = File(providerOut, source.name).apply { writeText("Original destination") }
        val changed = AtomicBoolean()
        FixtureDocumentsProvider.beforeRead = { file ->
            if (staging(file) && changed.compareAndSet(false, true)) file.writeBytes(bytes.copyOf().apply { this[0] = 99 })
        }
        val job = finish(start(listOf(source.path), uri(providerOut), policy = ConflictPolicy.REPLACE), TransferState.FAILED)
        assertEquals(CopyVerificationStatus.MISMATCH, job.verifications.single().status)
        assertEquals("Original destination", original.readText()); assertArrayEquals(bytes, source.readBytes()); assertClean()
    }

    @Test fun recursiveProviderCopiesSaveAResultForEveryLeaf() {
        val source = File(local, "Bundle").apply { mkdir() }
        File(source, "A.txt").writeText("First important file")
        File(source, "Sub").mkdir(); File(source, "Sub/B.txt").writeText("Second important file")
        File(source, "Empty").mkdir()
        val job = finish(start(listOf(source.path), uri(providerOut)))
        assertEquals(2, job.verifications.size)
        assertTrue(job.verifications.all { it.status == CopyVerificationStatus.VERIFIED && it.destination != null })
        assertEquals("Second important file", File(providerOut, "Bundle/Sub/B.txt").readText())
        assertTrue(File(providerOut, "Bundle/Empty").isDirectory); assertClean()
    }

    @Test fun mergingFoldersVerifiesNewMembersAndKeepsExistingFiles() {
        val source = File(local, "Bundle").apply { mkdir() }
        File(source, "A.txt").writeText("A"); File(source, "Sub").mkdir(); File(source, "Sub/B.txt").writeText("B")
        val existing = File(providerOut, "Bundle").apply { mkdir() }; File(existing, "Keep.txt").writeText("Keep")
        val job = finish(start(listOf(source.path), uri(providerOut), policy = ConflictPolicy.MERGE))
        assertEquals(2, job.verifications.size)
        assertTrue(job.verifications.all { it.status == CopyVerificationStatus.VERIFIED && it.destination != null })
        assertEquals("Keep", File(existing, "Keep.txt").readText()); assertEquals("A", File(existing, "A.txt").readText())
        assertTrue(source.isDirectory); assertClean()
    }

    @Test fun skippedConflictsAreNotClaimedAsVerified() {
        val source = File(local, "Important.bin").apply { writeBytes(bytes) }
        File(providerOut, source.name).writeText("Existing")
        val job = finish(start(listOf(source.path), uri(providerOut), policy = ConflictPolicy.SKIP))
        assertTrue(job.results.single().skipped)
        assertEquals(CopyVerificationStatus.SKIPPED, job.verifications.single().status)
        assertEquals("Existing", File(providerOut, source.name).readText()); assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun retryRetainsVerificationAndDoesNotRecopyCompletedItems() {
        val source = File(local, "Good.bin").apply { writeBytes(bytes) }; val missing = File(local, "Missing.txt")
        val failed = finish(start(listOf(source.path, missing.path), uri(providerOut)), TransferState.FAILED)
        missing.writeText("Recovered source")
        lateinit var id: String
        compose.runOnIdle { model.retryTransfer(failed.id); id = model.transferJobs.last().id; jobs += id }
        val retried = finish(id)
        assertTrue(retried.verifyCopies); assertEquals(listOf(missing.path), retried.paths)
        assertEquals(CopyVerificationStatus.VERIFIED, retried.verifications.single().status)
        assertEquals(setOf(source.name, missing.name), providerOut.list()!!.toSet())
    }

    private fun slowReadback(): Pair<File, String> {
        val source = File(local, "Slow readback.bin").apply { writeBytes(ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }) }
        val opened = AtomicBoolean()
        FixtureDocumentsProvider.beforeRead = {
            if (staging(it)) {
                FixtureDocumentsProvider.readDelayMillis = mapOf(it.canonicalPath to 200L)
                opened.set(true)
            }
        }
        val id = start(listOf(source.path), uri(providerOut))
        compose.waitUntil(15_000) {
            opened.get() && model.activeTransferId == id && model.transferProgress.phase == "Verifying copy" &&
                model.transferProgress.phaseBytes >= 128 * 1024
        }
        return source to id
    }

    @Test fun pauseAndResumeKeepVerificationBytesSeparateFromCopiedBytes() {
        val (source, id) = slowReadback()
        compose.runOnIdle { model.pauseTransfer(id) }
        val count = model.transferProgress.phaseBytes
        assertEquals(TransferState.PAUSED, model.transferJobs.first { it.id == id }.state)
        assertEquals(source.length(), model.transferProgress.bytes)
        SqliteTransferStore(compose.activity).use { store ->
            val saved = store.load().first { it.id == id }
            assertTrue(saved.verifyCopies)
            assertEquals(CopyVerificationStatus.INCOMPLETE, saved.verifications.single().status)
            assertNotNull(saved.verifications.single().expectedSha256)
            assertNull(saved.verifications.single().destination)
        }
        compose.runOnIdle { model.resumeTransfer(id) }
        val job = finish(id)
        assertTrue(job.verifications.single().bytes >= count)
        assertEquals(source.length(), job.bytes); assertArrayEquals(source.readBytes(), File(providerOut, source.name).readBytes())
        assertClean()
    }

    @Test fun cancellationDuringRealProviderReadbackKeepsTheSourceAndRecordsIncomplete() {
        val (source, id) = slowReadback(); val original = source.readBytes()
        compose.runOnIdle { model.cancelQueuedTransfer(id) }
        val job = finish(id, TransferState.CANCELLED)
        assertEquals(CopyVerificationStatus.INCOMPLETE, job.verifications.single().status)
        assertNull(job.verifications.single().destination)
        assertArrayEquals(original, source.readBytes()); assertFalse(File(providerOut, source.name).exists()); assertClean()
    }

    private fun openPasteReview(): Int {
        val before = model.transferJobs.size
        compose.runOnIdle {
            model.select(model.entries.first { it.name == "Original.bin" })
            model.copySelection(false)
            model.navigate(local.path)
        }
        compose.waitUntil(10_000) { model.location == local.path && !model.loading }
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.runOnIdle {
            assertEquals(local.path, model.folderPath)
            assertNull(model.commandProblem("pasteVerified"))
        }
        compose.onNodeWithText("Paste and verify…").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { model.verifiedCopyReview != null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Review verified copy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review verified copy").assertExists()
        return before
    }

    @Test fun namedPasteReviewDoesNoWorkUntilConfirmedAndCanBeCancelled() {
        val before = openPasteReview()
        capture("review", "verified-copy-review")
        assertEquals(before, model.transferJobs.size); assertTrue(local.listFiles().isNullOrEmpty())
        compose.onNode(hasText("Cancel") and hasAnyAncestor(hasTestTag("verified-copy-review"))).performClick()
        assertNull(model.verifiedCopyReview); assertEquals(before, model.transferJobs.size)
        assertTrue(local.listFiles().isNullOrEmpty())
    }

    @Test fun namedPasteReviewQueuesVerifiedWorkAndShowsDurableResults() {
        openPasteReview()
        compose.onNodeWithText("Copy and verify").performClick()
        lateinit var id: String
        compose.runOnIdle { id = model.transferJobs.last().id; jobs += id }
        val job = finish(id); assertTrue(job.verifyCopies)
        compose.onNodeWithContentDescription("Actions").performClick(); compose.onNodeWithText("Transfers").performScrollTo().assertIsDisplayed().performClick()
        val card = "transfer-job-$id"
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer-center").fetchSemanticsNodes().isNotEmpty() }
        var pages = 0
        while (compose.onAllNodesWithTag(card).fetchSemanticsNodes().isEmpty() && pages++ < 100) {
            compose.onNode(hasText("Show more transfers (", substring = true)).performScrollTo().performClick()
        }
        compose.onNodeWithTag(card).performScrollTo()
        compose.onNode(hasText("Copy verification · 1 verified and copied") and hasAnyAncestor(hasTestTag(card))).assertExists()
        capture("results", card)
        compose.onNodeWithContentDescription("Close transfers").performClick()
        assertArrayEquals(bytes, File(local, "Original.bin").readBytes())
    }

    @Test fun namedDuplicateReviewRetainsOriginalAndCreatesVerifiedSibling() {
        compose.runOnIdle { model.select(model.entries.first { it.name == "Original.bin" }) }
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.runOnIdle { assertNull(model.commandProblem("duplicateVerified")) }
        compose.onNodeWithText("Duplicate and verify…").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { model.verifiedCopyReview != null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Review verified copy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review verified copy").assertExists()
        compose.onNodeWithText("Copy and verify").performClick()
        lateinit var id: String
        compose.runOnIdle { id = model.transferJobs.last().id; jobs += id }
        val job = finish(id); assertTrue(job.verifyCopies)
        assertEquals(CopyVerificationStatus.VERIFIED, job.verifications.single().status)
        assertArrayEquals(bytes, File(fixture, "Original 2.bin").readBytes())
        assertArrayEquals(bytes, File(fixture, "Original.bin").readBytes())
    }
}
