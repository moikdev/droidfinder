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
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FolderSyncWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model.primaryPane
    private lateinit var base: File
    private lateinit var source: File
    private lateinit var destination: File
    private lateinit var originalSidebar: SidebarLayout
    private lateinit var originalTags: TagSnapshot
    private lateinit var originalStyles: Map<String, FolderStyle>
    @Before fun setup() {
        loaded()
        base = File(model.workspace, "Sync-review-" + UUID.randomUUID()).apply { check(mkdir()) }
        source = File(base, "Source").apply { mkdir() }; destination = File(base, "Destination").apply { mkdir() }
        File(source, "New folder/Nested").mkdirs(); File(source, "New folder/Nested/Review.txt").writeText("new disclosed file")
        File(source, "Update.txt").writeText("new reviewed content"); File(destination, "Update.txt").writeText("original destination")
        File(destination, "Only here").mkdir(); File(destination, "Only here/Keep.txt").writeText("original destination-only folder")
        compose.runOnIdle {
            originalSidebar = model.session.sidebar.value; originalTags = model.session.tagStore.snapshot().tags
            originalStyles = model.session.styleStore.styles.toMap()
            resetOwnedUndoFixture(model)
            if (!model.dualPane) model.toggleDualPane()
            if (model.panesReversed) model.swapPanes()
            model.activatePane(); model.navigate(source.path); model.secondaryPane.navigate(destination.path)
            model.changeMode(ViewMode.LIST); model.secondaryPane.changeMode(ViewMode.LIST)
            model.updateQuery(""); model.secondaryPane.updateQuery(""); model.showGestureHint(false)
        }
        loaded()
    }
    private fun loaded() { compose.waitUntil(20_000) { model.busy == null && !model.loading }; compose.waitForIdle() }
    @After fun cleanup() {
        if (!::base.isInitialized) return
        compose.runOnIdle { if (model.busy != null) model.cancelTransfer(); model.session.folderSyncReviewControl?.cancel() }
        loaded()
        compose.runOnIdle {
            model.session.folderSyncReview.value = null; model.session.folderSyncRecords.value = null
            resetOwnedUndoFixture(model)
            check(model.session.saveSidebar(originalSidebar))
            val current = model.session.tagStore.snapshot().tags
            if (current != originalTags) model.session.tagStore.restoreTags(current, originalTags)
            model.session.styleStore.replace(originalStyles); model.session.folderStyles.value = originalStyles; model.syncLibrary()
            model.navigate(model.workspace.path); model.secondaryPane.navigate(model.workspace.path)
        }
        loaded(); check(base.parentFile == model.workspace && base.name.startsWith("Sync-review-")); check(base.deleteRecursively())
    }
    private fun open() {
        compose.runOnIdle { model.reviewFolderSynchronization(source.path, destination.path) }
        checked()
    }
    private fun checked() {
        compose.waitUntil(20_000) { model.folderSyncReview?.checking == false }
        assertNull(model.folderSyncReview?.error)
        compose.onNodeWithTag("folder-sync-review").assertIsDisplayed()
    }
    private fun control(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("folder-sync-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun clickText(text: String) {
        compose.onNodeWithTag("folder-sync-list").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }
    private fun finish() {
        compose.waitUntil(30_000) { model.folderSyncReview?.finished == true && model.busy == null }
        assertNull("Run failed: ${model.folderSyncReview?.error}", model.folderSyncReview?.error)
        assertNull(model.session.historyRecoveryProblem.value)
    }
    private fun capture(name: String) {
        val directory = File(compose.activity.cacheDir, "folder-sync-qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onNodeWithTag("folder-sync-review").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun namedCompareEntryOpensTheDefaultReviewAndAppliesOnlyAdditions() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Compare folders…").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("review-folder-sync").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() } }
        compose.onNodeWithTag("review-folder-sync").performScrollTo().performClick(); checked()
        assertEquals(setOf("New folder"), model.folderSyncReview!!.selected)
        control("sync-item:New folder").assertIsOn(); capture("default-choices")
        control("sync-review-choices").performClick(); control("sync-apply").assertIsEnabled().performClick(); finish()
        assertEquals("new disclosed file", File(destination, "New folder/Nested/Review.txt").readText())
        assertEquals("original destination", File(destination, "Update.txt").readText())
        assertEquals("original destination-only folder", File(destination, "Only here/Keep.txt").readText())
        control("sync-result"); capture("completed-addition")
        assertEquals("Synchronize folders", model.undoLabel)
        compose.runOnIdle { model.closeFolderSyncReview(); model.undo() }; loaded(); assertFalse(File(destination, "New folder").exists())
        compose.runOnIdle { model.redo() }; loaded(); assertTrue(File(destination, "New folder/Nested/Review.txt").isFile)
    }
    @Test fun replacementsAndTrashNeedIndependentConfirmationAndUndoRestoresBothOriginals() {
        compose.runOnIdle { model.favoritePath(File(destination, "Only here").path) }
        open(); control("sync-item:Update.txt").performClick(); control("sync-item:Only here").performClick()
        control("sync-review-choices").performClick(); control("sync-apply").assertIsNotEnabled()
        control("sync-confirm-updates").performClick(); control("sync-apply").assertIsNotEnabled()
        control("sync-confirm-trash").performClick(); control("sync-apply").assertIsEnabled(); capture("confirmed-replacement-and-trash")
        control("sync-apply").performClick(); finish()
        assertEquals("new reviewed content", File(destination, "Update.txt").readText()); assertFalse(File(destination, "Only here").exists())
        val outcome = model.folderSyncReview!!.applied!!; assertEquals(3, outcome.completed.size)
        val originalFolder = outcome.preservedOriginals.single { it.original.endsWith("Only here") }
        assertTrue(originalFolder.recovery in model.favorites)
        assertFalse(model.recentActions.last().savedForRestart)
        compose.runOnIdle { model.closeFolderSyncReview(); model.undo() }; loaded()
        assertEquals("original destination", File(destination, "Update.txt").readText())
        assertEquals("original destination-only folder", File(destination, "Only here/Keep.txt").readText())
        assertTrue(File(destination, "Only here").path in model.favorites); assertFalse(File(destination, "New folder").exists())
        compose.runOnIdle { model.redo() }; loaded(); assertEquals("new reviewed content", File(destination, "Update.txt").readText())
        assertFalse(File(destination, "Only here").exists())
    }
    @Test fun additionsCarrySourceTagsAndFolderAppearance() {
        val from = File(source, "New folder").path; val to = File(destination, "New folder").path
        compose.runOnIdle { model.defineTag("Sync Review QA", "Red"); model.selectPaths(setOf(from)); model.tag("Sync Review QA"); model.setFolderStyle(FolderStyle("Green", "star")) }
        loaded(); assertNotNull(model.tags[organizationKey(from)])
        assertEquals(FolderStyle("Green", "star"), model.folderStyles[from])
        open(); control("sync-review-choices").performClick(); control("sync-apply").performClick(); finish()
        assertEquals(model.tags[organizationKey(from)], model.tags[organizationKey(to)]); assertNotNull(model.tags[organizationKey(to)])
        assertEquals(FolderStyle("Green", "star"), model.folderStyles[to])
    }
    @Test fun staleReviewStopsBeforeChangingAnOriginalAndLeavesAVisibleManualResult() {
        open(); control("sync-item:Update.txt").performClick(); control("sync-review-choices").performClick()
        control("sync-confirm-updates").performClick(); File(destination, "Arrival.txt").writeText("keep later arrival")
        control("sync-apply").performClick()
        compose.waitUntil(20_000) { model.folderSyncReview?.finished == true && model.busy == null }
        assertNotNull(model.folderSyncReview!!.error); assertEquals("original destination", File(destination, "Update.txt").readText())
        assertEquals("keep later arrival", File(destination, "Arrival.txt").readText()); assertFalse(File(destination, "New folder").exists())
        control("sync-error"); capture("stale-review-stopped")
        compose.runOnIdle { model.showFolderSyncRecords() }
        compose.waitUntil(10_000) { model.folderSyncRecords?.checking == false }
        assertTrue(model.folderSyncRecords!!.records.any { it.destination == destination.path })
        assertEquals("keep later arrival", File(destination, "Arrival.txt").readText())
    }
    @Test fun staleSavedUndoIsCaughtBeforeAnySyncPreparationOrOriginalMove() {
        compose.runOnIdle { model.createFolder("Earlier action") }; loaded()
        File(source, "Earlier action/Later edit.txt").writeText("keep this later edit")
        open(); control("sync-review-choices").performClick(); control("sync-apply").performClick()
        compose.waitUntil(20_000) { model.folderSyncReview?.finished == true && model.busy == null }
        assertNotNull(model.folderSyncReview!!.error); assertNotNull(model.session.historyRecoveryProblem.value)
        assertFalse(File(destination, "New folder").exists()); assertFalse(File(destination, "Earlier action").exists())
        assertEquals("original destination", File(destination, "Update.txt").readText())
        assertEquals("keep this later edit", File(source, "Earlier action/Later edit.txt").readText())
        assertFalse(File(model.folderSyncReview!!.workingDirectory!!).exists())
    }

    @Test fun changingDirectionDiscardsChoicesAndUsesTheDisplayedSourceAndDestination() {
        open(); control("sync-item:Update.txt").performClick()
        control("sync-right-to-left").performClick(); checked()
        assertEquals(destination.path, model.folderSyncReview!!.inspection!!.preview.sourceRoot)
        assertEquals(setOf("Only here"), model.folderSyncReview!!.selected)
        control("sync-review-choices").performClick(); control("sync-apply").performClick(); finish()
        assertEquals("original destination-only folder", File(source, "Only here/Keep.txt").readText())
        assertEquals("new reviewed content", File(source, "Update.txt").readText())
        assertEquals("original destination", File(destination, "Update.txt").readText())
    }
    @Test fun largeTextKeepsChoicesConfirmationsAndApplyReachableInAScrollingReview() {
        val label = "Replace 1 destination item; preserve the original copies in Trash first."
        open(); control("sync-item:Update.txt").performClick(); control("sync-review-choices").performClick()
        control("sync-confirm-updates")
        val normalHeight = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { model.closeFolderSyncReview() }
        compose.runOnIdle { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { FolderSyncReviewDialog(model); FolderSyncRecordsDialog(model) }
            }
        } }
        open(); control("sync-item:Update.txt").performClick(); control("sync-item:Only here").performClick()
        control("sync-review-choices").performClick(); control("sync-confirm-updates").performClick()
        control("sync-confirm-trash").performClick(); control("sync-apply").assertIsEnabled()
        control("sync-confirm-updates")
        val enlargedHeight = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertTrue("The rendered label must actually enlarge", enlargedHeight >= normalHeight * 1.5f)
        val captures = File(compose.activity.cacheDir, "folder-sync-qa").apply { check(isDirectory || mkdir()) }
        File(captures, "font-layout.json").writeText(
            "{\"normalLabelHeightPx\":$normalHeight,\"enlargedLabelHeightPx\":$enlargedHeight,\"requestedCompositionFontScale\":2.0,\"actualSystemFontChangeClaimed\":false}")
        control("sync-apply"); capture("large-text-confirmation"); control("sync-close").performClick()
        assertFalse(model.folderSyncReview!!.visible); assertEquals("original destination", File(destination, "Update.txt").readText())
    }
    @Test fun secondaryPaneRequestsUseTheStrictPrimaryOwnerEvenAfterPanesSwap() {
        compose.runOnIdle { model.swapPanes(); model.secondaryPane.activatePane(); model.secondaryPane.reviewFolderSynchronization(destination.path, source.path) }
        checked()
        assertSame(model.folderSyncReview, model.secondaryPane.folderSyncReview)
        assertEquals(destination.path, model.folderSyncReview!!.left); assertSame(model, model.primaryPane)
        control("sync-close").performClick(); assertEquals("original destination", File(destination, "Update.txt").readText())
    }
}
