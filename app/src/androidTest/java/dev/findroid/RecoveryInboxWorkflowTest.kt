package dev.findroid

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RecoveryInboxWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model.primaryPane
    private lateinit var fixture: File
    private lateinit var originalJobs: List<TransferJob>
    private var ownsMarkerFixture = false
    private var acknowledgedBefore: String? = null
    private var partialDirectory: File? = null
    private val marker get() = model.session.recoveryMarker
    private val notices get() = File(compose.activity.filesDir, ".recovery-notices")
    private val preferences get() = compose.activity.getSharedPreferences("finder", 0)
    private val freshStores = mutableListOf<ViewModelStore>()
    private fun settled() { compose.waitUntil(20_000) { model.busy == null && !model.loading }; compose.waitForIdle() }
    @Before fun setup() {
        settled()
        // The enclosing controller verifies/restores immutable original state. These two locations
        // were originally absent on both assigned tablets; a pre-existing record must never be replaced.
        check(!marker.exists() && !Files.isSymbolicLink(marker.toPath()))
        check(!notices.exists() && !Files.isSymbolicLink(notices.toPath()))
        ownsMarkerFixture = true
        acknowledgedBefore = preferences.getString("acknowledgedRecoveryMarker", null)
        fixture = File(model.workspace, "Recovery-review-" + UUID.randomUUID()).apply { check(mkdir()) }
        File(fixture, "Keep original.txt").writeText("synthetic original retained")
        compose.runOnIdle {
            originalJobs = model.transferJobs
            model.session.transferJobs.value = emptyList()
            resetOwnedUndoFixture(model)
            model.activatePane(); model.navigate(fixture.path); model.changeMode(ViewMode.LIST)
            model.updateQuery(""); model.showGestureHint(false)
        }
        settled()
    }
    @After fun cleanup() {
        if (!ownsMarkerFixture) return
        compose.runOnIdle {
            if (model.busy != null) model.cancelTransfer()
            model.closeRecoveryInbox(); model.cancelPartialCopyReview()
            model.session.interruptedTransfer.value = null; model.session.interruptedMarkerId.value = null
        }
        settled()
        compose.runOnIdle {
            model.session.transferJobs.value = originalJobs
            model.session.recoveryMarkerProblem.value = null
            freshStores.forEach { it.clear() }
            resetOwnedUndoFixture(model)
            model.navigate(model.workspace.path)
            if (model.dualPane) model.secondaryPane.navigate(model.workspace.path)
        }
        settled()
        val edit = preferences.edit()
        if (acknowledgedBefore == null) edit.remove("acknowledgedRecoveryMarker") else edit.putString("acknowledgedRecoveryMarker", acknowledgedBefore)
        check(edit.commit())
        // Only these test-owned, initially absent notice fixtures are removed. Documents/Trash stay intact.
        if (Files.isSymbolicLink(marker.toPath()) || marker.exists()) check(marker.delete())
        if (Files.isSymbolicLink(notices.toPath()) || notices.isFile) check(notices.delete())
        else if (notices.exists()) check(notices.deleteRecursively())
        partialDirectory?.let { check(it.deleteRecursively() || !it.exists()) }
        check(fixture.parentFile == model.workspace && fixture.name.startsWith("Recovery-review-")); check(fixture.deleteRecursively())
    }
    private fun seed(job: TransferJob) { compose.runOnIdle { model.session.transferJobs.value = listOf(job) } }
    private fun failedJob() = TransferJob(id = "synthetic-review", paths = listOf(File(fixture, "Keep original.txt").path),
        destination = fixture.path, move = false, state = TransferState.INTERRUPTED, inFlight = File(fixture, "Keep original.txt").path)
    private fun open() {
        compose.runOnIdle { model.showRecoveryInbox() }
        compose.waitUntil(15_000) { model.recoveryInbox?.checking == false }
        compose.onNodeWithTag("recovery-inbox").assertIsDisplayed()
    }
    private fun control(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("recovery-inbox-list").performScrollToNode(hasTestTag(tag))
        val control = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val node = control.fetchSemanticsNode(); val minimum = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("$tag needs a complete 48dp target", node.size.height >= minimum - 1)
        assertTrue("$tag must be completely visible", node.boundsInRoot.height >= node.size.height - 1 && node.boundsInRoot.width >= node.size.width - 1)
        return control
    }
    private fun showOwnedNotice(): RecoveryNotice {
        val text = "Copy synthetic project\n${fixture.path}\n123"
        marker.writeText(text)
        val notice = model.session.recoveryNoticeStore.current(marker)!!
        compose.runOnIdle { model.session.interruptedTransfer.value = text; model.session.interruptedMarkerId.value = notice.id }
        return notice
    }
    private fun newModel(): FinderModel {
        val store = ViewModelStore().also { freshStores += it }
        lateinit var fresh: FinderModel
        compose.runOnIdle {
            fresh = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return FinderModel(compose.activity.application, FinderSession(compose.activity.application)) as T
                }
            })[FinderModel::class.java]
        }
        return fresh
    }
    private fun capture(name: String) {
        val folder = File(compose.activity.cacheDir, "recovery-inbox-qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onNodeWithTag("recovery-inbox").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun namedMenuOpensAReadOnlyInboxWithoutStartingAnOperation() {
        seed(failedJob())
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Recovery inbox…").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.recoveryInbox?.checking == false }
        compose.onNodeWithTag("recovery-inbox").assertIsDisplayed()
        assertNull(model.busy); assertNull(TransferRuntime.label); assertFalse(marker.exists())
        assertEquals("synthetic original retained", File(fixture, "Keep original.txt").readText())
    }
    @Test fun secondaryPaneAndReversedLayoutKeepThePrimaryRecoveryOwner() {
        seed(failedJob())
        compose.runOnIdle {
            if (!model.dualPane) model.toggleDualPane()
            if (!model.panesReversed) model.swapPanes()
            model.secondaryPane.activatePane(); model.secondaryPane.showRecoveryInbox()
        }
        compose.waitUntil(15_000) { model.recoveryInbox?.checking == false }
        assertEquals("primary", model.paneId); assertTrue(model.recoveryInbox!!.items.any { it.id == "transfer:synthetic-review" })
        assertNull(model.busy)
        control("recovery-close").performClick(); assertNull(model.recoveryInbox)
    }
    @Test fun transferLinkOpensTheChosenExistingJobWithoutReplayingIt() {
        val job = failedJob(); seed(job); open()
        control("recovery-link:transfer:${job.id}:TRANSFERS").performClick()
        compose.onNodeWithTag("transfer-center").assertIsDisplayed()
        compose.onNodeWithText("Hide details").assertExists()
        assertEquals(listOf(job), model.transferJobs); assertNull(model.busy); assertFalse(marker.exists())
    }
    @Test fun staleTransferLinkKeepsTheInboxAndExplainsTheMissingRecord() {
        val job = failedJob(); seed(job); open()
        compose.runOnIdle { model.session.transferJobs.value = emptyList() }
        control("recovery-link:transfer:${job.id}:TRANSFERS").performClick()
        assertNotNull(model.recoveryInbox)
        assertTrue(model.recoveryInbox!!.notices.any { "no longer in the current list" in it }); assertNull(model.busy)
    }
    @Test fun recordedLocationsAreShownOnlyByAnExplicitNamedControl() {
        val job = failedJob(); seed(job); open()
        val card = "transfer:${job.id}"
        control("recovery-locations:$card").performClick()
        compose.onNodeWithText("Destination\n${fixture.path}").assertExists()
        control("recovery-locations:$card").performClick()
        compose.onNodeWithText("Destination\n${fixture.path}").assertDoesNotExist()
        assertEquals("synthetic original retained", File(fixture, "Keep original.txt").readText())
    }
    @Test fun acknowledgmentKeepsTheNoticeAvailableToAFreshModel() {
        val notice = showOwnedNotice(); val bytes = marker.readBytes()
        compose.onNodeWithTag("recovery-later").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.interruptedTransfer == null && !model.recoveryAcknowledging }
        assertArrayEquals(bytes, marker.readBytes())
        assertEquals(listOf(notice), model.session.recoveryNoticeStore.load().records)
        val fresh = newModel(); assertNull(fresh.interruptedTransfer)
        compose.runOnIdle { fresh.showRecoveryInbox() }
        compose.waitUntil(15_000) { fresh.recoveryInbox?.checking == false }
        assertTrue(fresh.recoveryInbox!!.items.any { it.id == "operation-notice:" + notice.id })
        compose.runOnIdle { fresh.closeRecoveryInbox() }
    }
    @Test fun aStaleAcknowledgmentDoesNotDismissOrDeleteANewerMarker() {
        val old = showOwnedNotice(); marker.writeText("Copy later synthetic project\n${fixture.path}\n456")
        val bytes = marker.readBytes(); var dismissed = false
        compose.runOnIdle { model.dismissRecovery { dismissed = true } }
        compose.waitUntil(10_000) { !model.recoveryAcknowledging }
        assertFalse(dismissed); assertEquals(old.text, model.interruptedTransfer); assertArrayEquals(bytes, marker.readBytes())
        assertNotNull(model.recoveryMarkerProblem); assertFalse(notices.exists())
    }
    @Test fun aNoticePreservationFailureStopsWorkAndAlwaysFinishesTheService() {
        marker.writeText("Copy earlier synthetic project\n${fixture.path}\n1"); val bytes = marker.readBytes()
        notices.writeText("synthetic blocked archive")
        compose.runOnIdle { model.createFolder("Must not appear") }; settled()
        assertFalse(File(fixture, "Must not appear").exists()); assertArrayEquals(bytes, marker.readBytes())
        assertEquals("synthetic blocked archive", notices.readText()); assertNull(TransferRuntime.label)
        assertTrue(WorkspaceMutationGate.beginApp()); WorkspaceMutationGate.endApp()
    }
    @Test fun aLaterSuccessfulOperationKeepsTheEarlierNoticeInTheInbox() {
        marker.writeText("Copy earlier synthetic project\n${fixture.path}\n1")
        val notice = model.session.recoveryNoticeStore.current(marker)!!
        compose.runOnIdle { model.createFolder("Reviewed new folder") }; settled()
        assertTrue(File(fixture, "Reviewed new folder").isDirectory); assertFalse(marker.exists()); assertNull(TransferRuntime.label)
        open(); assertTrue(model.recoveryInbox!!.items.any { it.id == "operation-notice:" + notice.id })
        assertEquals("synthetic original retained", File(fixture, "Keep original.txt").readText())
    }
    @Test fun failedOperationLeavesAReviewableNoticeAndKeepsTheExistingFile() {
        compose.runOnIdle { model.createFolder("Invalid/name") }; settled()
        assertNotNull(model.session.recoveryNoticeStore.currentForReview(marker))
        assertTrue(model.session.recoveryNoticeStore.load().records.isNotEmpty()); assertNull(TransferRuntime.label)
        assertEquals("synthetic original retained", File(fixture, "Keep original.txt").readText())
        open(); assertTrue(model.recoveryInbox!!.items.any { it.id.startsWith("operation-notice:") })
    }
    private fun partialJob(): TransferJob {
        val source = File(fixture, "Saved copy.bin").apply { writeBytes(ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }) }
        val output = File(fixture, "Destination").apply { check(mkdir()) }
        val privateRoot = File(compose.activity.filesDir, ".partial-copies").apply { mkdirs() }
        val storage = AndroidPartialCopyStorage(); val recovery = PartialCopyRecovery(storage, privateRoot.path)
        val empty = recovery.allocate(source.path, File(output, source.name).path, true)
        partialDirectory = File(empty.staging).parentFile
        val prefix = source.readBytes().copyOf(4 * 1024 * 1024)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(prefix).joinToString("") { "%02x".format(it) }
        val checkpoint = storage.open(source.path).use { input -> storage.open(empty.staging, writable = true).use { stage ->
            var written = 0
            while (written < prefix.size) written += stage.write(prefix, written, prefix.size - written)
            recovery.checkpoint(empty, input, stage, prefix.size.toLong(), hash)
        } }
        return failedJob().copy(partialCopy = checkpoint, paths = listOf(source.path), inFlight = source.path, destination = output.path)
    }
    @Test fun savedCopyLinkChecksTheExistingCheckpointWithoutResumingIt() {
        val job = partialJob(); seed(job); open()
        val partial = job.partialCopy!!; val source = File(partial.source).readBytes(); val stage = File(partial.staging).readBytes()
        control("recovery-link:transfer:${job.id}:SAVED_COPY").performClick()
        compose.waitUntil(20_000) { model.partialCopyReview?.checking == false }
        compose.onNodeWithTag("saved-copy-review").assertIsDisplayed()
        assertNull(model.partialCopyReview!!.error); assertNull(model.busy)
        assertArrayEquals(source, File(partial.source).readBytes()); assertArrayEquals(stage, File(partial.staging).readBytes())
        assertFalse(File(partial.target).exists()); assertEquals(listOf(job), model.transferJobs)
    }
    @Test fun changedSavedCopyLinkKeepsTheInboxAndRequestsAFreshReview() {
        val job = partialJob(); seed(job); open()
        compose.runOnIdle { model.session.transferJobs.value = listOf(job.copy(partialCopy = null)) }
        control("recovery-link:transfer:${job.id}:SAVED_COPY").performClick()
        assertNotNull(model.recoveryInbox); assertNull(model.partialCopyReview)
        assertTrue(model.recoveryInbox!!.notices.any { "refresh the saved-copy record" in it }); assertNull(model.busy)
    }
    @Test fun anUnlistedDestinationExplainsStorageAccessWithoutNavigating() {
        val notice = RecoveryNotice.fromText("Copy synthetic project\n/data/system/unlisted-destination\n123")
        val items = recoveryInboxItems(emptyList(), emptyList(), emptyList(), null, null, null, listOf(notice))
        compose.runOnIdle { model.session.recoveryInbox.value = RecoveryInboxState("synthetic-unlisted", items, checking = false) }
        val location = model.location
        control("recovery-link:operation-notice:${notice.id}:DESTINATION").performClick()
        assertEquals(location, model.location); assertNotNull(model.recoveryInbox)
        assertTrue(model.recoveryInbox!!.notices.any { "Reconnect the recorded destination" in it }); assertNull(model.busy)
    }
    @Test fun oneUnreadableSourceDoesNotHideIndependentTransferRecords() {
        val job = failedJob(); seed(job); notices.writeText("synthetic unavailable notice directory")
        open()
        assertTrue(model.recoveryInbox!!.items.any { it.id == "transfer:" + job.id })
        assertTrue(model.recoveryInbox!!.notices.any { "Preserved operation notices" in it })
        assertEquals("synthetic unavailable notice directory", notices.readText()); assertNull(model.busy)
    }
    @Test fun normalAndEnlargedTextKeepAllReviewControlsReachable() {
        val job = failedJob()
        val synthetic = recoveryInboxItems(listOf(job), listOf(FolderSyncRunRecord("synthetic-run", "/synthetic/source", "/synthetic/destination", "/synthetic/working", "/synthetic/journal", 3, 7)),
            listOf(RecoveryOriginal("/synthetic/Trash/Report.txt", "/synthetic/Project/Report.txt", 123)), null, null, "Synthetic saved Undo needs review")
        compose.runOnIdle { model.session.recoveryInbox.value = RecoveryInboxState("synthetic-layout", synthetic, checking = false) }
        compose.onNodeWithTag("recovery-inbox-list").performScrollToNode(hasText(job.title))
        val normal = compose.onNodeWithText(job.title).performScrollTo().fetchSemanticsNode().size.height
        capture("normal-review")
        control("recovery-link:transfer:${job.id}:TRANSFERS")
        compose.runOnIdle { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { RecoveryInboxDialog(model) {} }
            }
        } }
        compose.onNodeWithTag("recovery-inbox-list").performScrollToNode(hasText(job.title))
        val large = compose.onNodeWithText(job.title).performScrollTo().fetchSemanticsNode().size.height
        assertTrue("Actual Compose title layout must enlarge ($normal → $large)", large > normal)
        control("recovery-link:transfer:${job.id}:TRANSFERS")
        control("recovery-locations:transfer:${job.id}").performClick()
        control("recovery-link:synchronization:synthetic-run:SYNCHRONIZATION")
        control("recovery-link:original:/synthetic/Trash/Report.txt:TRASH")
        control("recovery-link:undo-journal:RECENT_ACTIONS")
        control("recovery-close"); capture("large-text-review")
    }
}
