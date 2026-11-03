package dev.findroid

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PersistentUndoWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val model get()=compose.activity.model
    private lateinit var fixture:File
    private lateinit var originalTags:TagSnapshot
    private val journal get()=File(compose.activity.filesDir,".undo-history/history.json")
    @Before fun setup() {
        loaded();fixture=File(model.workspace,"Undo-"+UUID.randomUUID()).apply { check(mkdir()) }
        File(fixture,"Original.txt").writeText("Keep this original document")
        compose.runOnIdle {
            originalTags=model.session.tagStore.snapshot().tags
            model.session.history.resetSavedHistory();model.syncHistory()
            if(model.dualPane) model.toggleDualPane()
            model.activatePane();model.closeOtherTabs(model.activeTab);model.navigate(fixture.path)
            model.changeMode(ViewMode.LIST);model.changeGrouping(GroupBy.NONE);model.showGestureHint(false)
        }
        loaded()
    }
    @After fun cleanup() {
        if(!::fixture.isInitialized) return
        compose.runOnIdle { if(model.busy!=null) model.cancelTransfer() };loaded()
        compose.runOnIdle {
            model.session.history.resetSavedHistory();model.syncHistory()
            val current=model.session.tagStore.snapshot().tags
            if(current!=originalTags) model.session.tagStore.restoreTags(current,originalTags)
            model.syncLibrary();model.navigate(model.workspace.path)
            model.folderViews.values.keys.filter { pathWithin(it,folderViewKey(fixture.path)!!) }.forEach(model::forgetFolderView)
        }
        loaded();check(fixture.deleteRecursively())
    }
    private fun loaded() { compose.waitUntil(20_000) { model.busy==null&&!model.loading };compose.waitForIdle() }
    private fun create() { compose.runOnIdle { model.createFolder("Review folder") };loaded();assertNotNull(model.undoLabel) }
    private fun recent() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Recent actions…").performScrollTo().performClick()
        compose.onNodeWithTag("recent-actions").assertIsDisplayed()
    }
    private fun control(tag:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("recent-actions-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun show(tag:String) { compose.onNodeWithTag("recent-actions-list").performScrollToNode(hasTestTag(tag)) }
    private fun capture(name:String) {
        val folder=File(compose.activity.cacheDir,"persistent-undo-qa").apply { mkdirs() }
        File(folder,name+".png").outputStream().use { output ->
            compose.onNodeWithTag("recent-actions").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,output)
        }
    }
    @Test fun savedLocalFolderCanBeUndoneAndRedoneWithNamedControls() {
        create();recent();compose.onNodeWithText("Saved for restart").assertExists();capture("saved")
        control("recent-undo").performClick();loaded();assertFalse(File(fixture,"Review folder").exists())
        control("recent-redo").assertIsEnabled().performClick();loaded();assertTrue(File(fixture,"Review folder").isDirectory)
        assertEquals("Keep this original document",File(fixture,"Original.txt").readText())
        control("close-recent-actions").performClick()
    }
    @Test fun cancellingTheResetKeepsTheExactJournalAndDocuments() {
        create();val before=journal.readBytes();recent();control("reset-undo").performClick()
        compose.onNodeWithText("Reset Undo list?").assertExists();capture("reset-review")
        compose.onNodeWithText("Keep Undo list").performScrollTo().performClick()
        assertArrayEquals(before,journal.readBytes());assertTrue(File(fixture,"Review folder").isDirectory)
        control("close-recent-actions").performClick()
    }
    @Test fun confirmedResetPreservesTheOldRecordAndLeavesEveryDocument() {
        create();val before=journal.readBytes();recent();control("reset-undo").performClick()
        control("confirm-reset-undo").performClick();loaded()
        assertTrue(model.recentActions.isEmpty());assertNull(model.undoLabel);assertTrue(File(fixture,"Review folder").isDirectory)
        val preserved=compose.activity.filesDir.listFiles()!!.filter { it.name.startsWith(".undo-history-preserved-") }
        assertTrue(preserved.any { File(it,"history.json").takeIf(File::isFile)?.readBytes()?.contentEquals(before)==true })
        compose.onNodeWithText("No recent actions").assertExists();control("close-recent-actions").performClick()
    }
    @Test fun laterEditsStopUndoAndTheirReasonStaysAvailable() {
        compose.runOnIdle { model.createTextFile("Draft.txt") };loaded();val draft=File(fixture,"Draft.txt").apply { writeText("Keep my later edits") }
        recent();control("recent-undo").performClick();loaded();show("recent-action-failure")
        compose.onNodeWithTag("recent-action-failure").assertIsDisplayed();capture("changed-file")
        assertEquals("Keep my later edits",draft.readText());control("recent-redo").assertIsNotEnabled();control("close-recent-actions").performClick()
    }
    @Test fun incompleteMetadataSaveRequiresManualReviewAndReset() {
        create();compose.runOnIdle {
            model.session.history.beginOperation("Interrupted metadata save")
            model.session.history.finishOperation(false);model.syncHistory()
        }
        recent();show("saved-undo-review");capture("interrupted-review")
        compose.onNodeWithText("Saved Undo needs review").assertExists();control("recent-undo").assertIsNotEnabled();control("recent-redo").assertIsNotEnabled()
        control("reset-undo").performClick();compose.onNodeWithText("Keep Undo list").performScrollTo().performClick()
        assertNotNull(model.historyRecoveryProblem);assertTrue(File(fixture,"Review folder").isDirectory)
        control("reset-undo").performClick();control("confirm-reset-undo").performClick();loaded()
        assertNull(model.historyRecoveryProblem);assertTrue(File(fixture,"Review folder").isDirectory);control("close-recent-actions").performClick()
    }
    @Test fun realOrganizationCallbackExplainsTheSessionBoundary() {
        create();compose.runOnIdle { model.defineTag("Undo QA tag","Blue") };loaded()
        assertEquals(2,model.recentActions.size);assertTrue(model.recentActions.none { it.savedForRestart })
        recent();compose.onAllNodesWithText("This session only").assertCountEquals(2);capture("session-boundary")
        control("close-recent-actions").performClick();assertTrue(File(fixture,"Review folder").isDirectory)
    }
    @Test fun activityRecreationKeepsTheSharedTimelineAndExactSavedSnapshot() {
        create();val before=journal.readBytes();val actions=model.recentActions;compose.activityRule.scenario.recreate();loaded()
        assertEquals(actions,model.recentActions);assertArrayEquals(before,journal.readBytes());recent()
        compose.onNodeWithText("Saved for restart").assertExists();control("close-recent-actions").performClick()
    }
    @Test fun largeTextKeepsReviewConfirmationAndAllNamedControlsReachable() {
        create();compose.runOnIdle { compose.activity.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                Box(Modifier.width(600.dp)) { FinderApp(model,{},{},{},{},{}) }
            }
        } }
        recent();control("recent-undo").assertIsDisplayed();control("recent-redo").assertIsDisplayed()
        control("reset-undo").assertIsDisplayed().performClick();control("confirm-reset-undo").assertIsDisplayed();capture("large-text-reset")
        compose.onNodeWithText("Keep Undo list").performScrollTo().assertIsDisplayed().performClick();control("close-recent-actions").assertIsDisplayed().performClick()
        assertTrue(File(fixture,"Review folder").isDirectory)
    }
}
