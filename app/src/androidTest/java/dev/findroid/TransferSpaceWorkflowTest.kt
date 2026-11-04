package dev.findroid

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TransferSpaceWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model.primaryPane
    private lateinit var fixture: File
    private lateinit var source: File
    private lateinit var destination: File
    private lateinit var other: File
    private val jobs = mutableSetOf<String>()
    private val contents = "synthetic original retained during transfer-space review"
    @Before fun setup() {
        compose.waitUntil(20_000) { model.busy == null && !model.loading }
        fixture = File(model.workspace, "Space-review-${UUID.randomUUID()}").apply { check(mkdir()) }
        source = File(fixture, "Original.txt").apply { writeText(contents) }
        destination = File(fixture, "Destination").apply { check(mkdir()) }
        other = File(fixture, "Other").apply { check(mkdir()) }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane(); resetOwnedUndoFixture(model)
            model.changeMode(ViewMode.LIST); model.changeGrouping(GroupBy.NONE)
            model.updateQuery(""); model.showGestureHint(false); model.clearSelection(); model.navigate(fixture.path)
        }
        compose.waitUntil(20_000) { !model.loading && model.entries.any { it.path == source.path } }
        compose.runOnIdle { model.select(model.entries.first { it.path == source.path }) }
    }
    @After fun cleanup() {
        if (!::fixture.isInitialized) return
        compose.runOnIdle {
            model.dismissTransferSpace(); model.secondaryPane.dismissTransferSpace()
            model.transferJobs.filter { it.id in jobs && !it.terminal }.forEach { model.cancelQueuedTransfer(it.id) }
        }
        compose.waitUntil(20_000) { model.busy == null }
        compose.runOnIdle {
            resetOwnedUndoFixture(model); model.clearSelection(); model.navigate(model.workspace.path)
            if (model.dualPane) { model.secondaryPane.clearSelection(); model.secondaryPane.navigate(model.workspace.path); model.activatePane() }
        }
        compose.waitUntil(20_000) { !model.loading }
        check(fixture.parentFile == model.workspace && fixture.name.startsWith("Space-review-")); check(fixture.deleteRecursively())
    }
    private fun open(owner: FinderModel = model) {
        compose.runOnIdle { owner.reviewTransferSpace(listOf(source.path), destination.path, false) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Working copies and recovery").fetchSemanticsNodes().size == 1 }
    }
    private fun button(text: String): SemanticsNodeInteraction {
        val result = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("transfer-space-review")))
        result.performScrollTo()
        compose.waitUntil(10_000) { result.isDisplayed() }
        result.assertIsDisplayed()
        val node = result.fetchSemanticsNode(); val minimum = 48 * compose.activity.resources.displayMetrics.density
        assertTrue(node.size.height >= minimum - 1)
        assertTrue(node.boundsInRoot.height >= node.size.height - 1 && node.boundsInRoot.width >= node.size.width - 1)
        return result
    }
    private fun finish() {
        compose.waitUntil(30_000) { model.busy == null && jobs.all { id -> model.transferJobs.first { it.id == id }.terminal } }
        assertTrue(model.transferJobs.filter { it.id in jobs }.all { it.state == TransferState.COMPLETED })
    }
    private fun confirm() {
        val before = model.transferJobs.map { it.id }.toSet()
        button("Copy to destination").performClick()
        compose.runOnIdle { jobs += model.transferJobs.map { it.id }.filter { it !in before } }
        assertEquals(1, jobs.size); finish()
    }
    private fun originalsKept() { assertEquals(contents, source.readText()) }

    @Test fun copyDestinationOffersNamedSpaceReviewAndOpeningStartsNoTransfer() {
        val before = model.transferJobs
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Copy to…").performScrollTo().performClick()
        compose.onNodeWithText("Review space and alternatives…").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Working copies and recovery").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf(source.path), model.transferSpaceReview!!.paths)
        assertEquals(before, model.transferJobs); originalsKept()
        assertTrue(destination.listFiles()!!.isEmpty())
        button("Close").performClick(); assertNull(model.transferSpaceReview)
    }
    @Test fun sparseLogicalSizeShowsShortfallAndDisablesStartWithoutAllocatingPayload() {
        val sparse = File(fixture, "Sparse-review.bin")
        val capacity = fixture.totalSpace; assertTrue(capacity > 0 && capacity < Long.MAX_VALUE - 256L * 1024 * 1024)
        // Exceed the whole volume, so concurrent cache cleanup cannot turn the fixture into a fit.
        RandomAccessFile(sparse, "rw").use { it.setLength(capacity + 256L * 1024 * 1024) }
        val probe = model.documents.inspectTransferSpace(
            TransferSpaceRequest(listOf(sparse.path), destination.path, false, ConflictPolicy.ASK), TransferControl())
        assertTrue("The sparse logical size must exceed the entire volume capacity", probe.plan.knownShortfall)
        compose.runOnIdle { model.reviewTransferSpace(listOf(sparse.path), destination.path, false) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Working copies and recovery").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("start-reviewed-transfer").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Choose another destination or free space, then refresh the review before starting.").assertExists()
        originalsKept(); assertTrue(destination.listFiles()!!.isEmpty())
    }
    @Test fun alternativeDestinationRetainsTheReviewedSourcesAndStartsNoWork() {
        val before = model.transferJobs; open()
        button("Choose another destination…").performClick()
        compose.onNodeWithContentDescription("Parent destination folder").performClick()
        compose.onNode(hasText("Other") and hasClickAction() and hasAnyAncestor(hasTestTag("destination-picker")))
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Review this destination").performClick()
        compose.waitUntil(20_000) { model.transferSpaceReview?.destination == other.path && compose.onAllNodesWithText("Working copies and recovery").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf(source.path), model.transferSpaceReview!!.paths)
        assertEquals(before, model.transferJobs); originalsKept()
        assertTrue(destination.listFiles()!!.isEmpty() && other.listFiles()!!.isEmpty())
    }
    @Test fun explicitConfirmationCopiesTheReviewedSourceAndKeepsOriginalBytes() {
        open(); assertFalse(File(destination, source.name).exists()); confirm()
        originalsKept(); assertEquals(contents, File(destination, source.name).readText())
        assertNull(model.transferSpaceReview)
    }
    @Test fun aNewBackgroundSelectionIsKeptWhenTheReviewedCopyFinishes() {
        val newer = File(fixture, "Later.txt").apply { writeText("synthetic later selection") }
        open()
        compose.runOnIdle { model.clearSelection(); model.select(Entry(newer)) }
        confirm(); originalsKept()
        assertEquals(setOf(newer.path), model.selected)
        assertEquals(contents, File(destination, source.name).readText())
        assertFalse(File(destination, newer.name).exists())
    }
    @Test fun secondaryPaneReviewKeepsThePrimaryPaneLocationAndSelection() {
        compose.runOnIdle {
            model.toggleDualPane(); model.secondaryPane.activatePane(); model.secondaryPane.navigate(fixture.path)
        }
        compose.waitUntil(20_000) { !model.secondaryPane.loading }
        val location = model.location; val selection = model.selected
        open(model.secondaryPane)
        button("Copy to destination").performClick()
        compose.runOnIdle { jobs += model.transferJobs.last().id }
        finish(); originalsKept()
        assertEquals(location, model.location); assertEquals(selection, model.selected)
        assertEquals(contents, File(destination, source.name).readText())
    }
    @Test fun refreshRecomputesMatchingOriginalsWithoutCreatingRecoveryCopies() {
        open(); File(destination, source.name).writeText("synthetic destination original")
        button("Refresh space estimate").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Possible replacement recovery copies:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("synthetic destination original", File(destination, source.name).readText()); originalsKept()
        assertFalse(fixture.walkTopDown().any { it.name.startsWith(".findroid-") })
    }
    @Test fun aDismissedReviewCannotConfirmANewerReviewOfTheSamePaths() {
        open(); val stale = model.transferSpaceReview!!; val before = model.transferJobs
        button("Close").performClick(); open()
        assertNotEquals(stale.id, model.transferSpaceReview!!.id)
        compose.runOnIdle { model.confirmTransferSpace(stale) }
        assertEquals(before, model.transferJobs); assertNotNull(model.transferSpaceReview)
        originalsKept(); assertTrue(destination.listFiles()!!.isEmpty())
    }
    @Test fun scrollableReviewKeepsCompleteNamedControlsAndProducesASurfaceCapture() {
        open()
        for (label in listOf("Refresh space estimate", "Choose another destination…", "Close", "Copy to destination")) button(label)
        val output = File(compose.activity.cacheDir, "space-plan-qa").apply { mkdirs() }
        File(output, "normal-review.png").outputStream().use {
            compose.onNodeWithTag("transfer-space-review").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        originalsKept(); assertTrue(destination.listFiles()!!.isEmpty())
    }
}
