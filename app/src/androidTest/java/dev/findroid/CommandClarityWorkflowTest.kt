@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

/** Real browser commands and bytes exercise the shared policy, beyond its pure decision tests. */
class CommandClarityWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var base: File
    private lateinit var note: File
    private var fixture: File? = null
    private var providerRoot: String? = null
    private var gate: CountDownLatch? = null
    private var sidebar = true

    @Before fun setup() {
        base = File(compose.activity.filesDir, "Workspace/Commands-${System.nanoTime()}").apply { mkdirs() }
        note = File(base, "Brief.txt").apply { writeText("Keep this original brief") }
        File(base, "Destination").mkdir()
        compose.runOnIdle {
            sidebar = model.sidebarVisible
            if (model.dualPane) model.toggleDualPane()
            model.activatePane(); model.closeOtherTabs(model.activeTab)
            model.navigate(base.path); model.updateQuery("")
            model.changeMode(ViewMode.LIST); model.changeGrouping(GroupBy.NONE)
            model.showGestureHint(false)
            model.session.history.resetSavedHistory(); model.session.undoLabel.value = null; model.session.redoLabel.value = null
            model.session.recentActions.value = emptyList(); model.session.historyFailure.value = null; model.session.historyRecoveryProblem.value = null; model.session.historyPersistenceNotice.value = null
            model.session.clipboard.value = null
        }
        loaded()
    }

    @After fun cleanup() {
        gate?.countDown()
        FixtureDocumentsProvider.transferGate = null; FixtureDocumentsProvider.gateOpened = null
        compose.runOnIdle {
            model.transferJobs.filter { !it.terminal }.forEach { model.cancelQueuedTransfer(it.id) }
            if (model.busy != null) model.cancelTransfer()
        }
        loaded()
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane(); model.navigate(model.workspace.path); model.closeOtherTabs(model.activeTab)
            providerRoot?.let(model::disconnectLocation)
            if (model.sidebarVisible != sidebar) model.toggleSidebar()
            model.session.history.resetSavedHistory(); model.session.undoLabel.value = null; model.session.redoLabel.value = null
            model.session.recentActions.value = emptyList(); model.session.historyFailure.value = null; model.session.historyRecoveryProblem.value = null; model.session.historyPersistenceNotice.value = null
            model.folderViews.values.keys.filter { pathWithin(it, folderViewKey(base.path)!!) }.forEach(model::forgetFolderView)
        }
        loaded(); base.deleteRecursively(); fixture?.deleteRecursively()
    }

    private fun loaded() { compose.waitUntil(20_000) { !model.loading && model.busy == null }; compose.waitForIdle() }
    private fun select(name: String = note.name) = compose.runOnIdle { model.select(model.entries.first { it.name == name }) }
    private fun key(key: Key, control: Boolean = false, shift: Boolean = false) = compose.onAllNodes(isRoot()).onLast().performKeyInput {
        if (control) keyDown(Key.CtrlLeft)
        if (shift) keyDown(Key.ShiftLeft)
        pressKey(key)
        if (shift) keyUp(Key.ShiftLeft)
        if (control) keyUp(Key.CtrlLeft)
    }
    private fun recent() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Recent actions…").performScrollTo().performClick()
        compose.onNodeWithTag("recent-actions").assertExists()
    }
    private fun recentControl(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("recent-actions-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun connect(readOnly: Boolean = true) {
        fixture = File(compose.activity.filesDir, "ProviderFixtures/Commands-${System.nanoTime()}").apply { mkdirs() }
        val parent = if (readOnly) File(fixture, "readonly").apply { mkdir() } else fixture!!
        File(parent, "Provider note.txt").writeText("Original provider text")
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture!!.name}")
        compose.runOnIdle { model.connectLocation(tree, false) }
        compose.waitUntil(15_000) { !model.loading && model.location.contains(fixture!!.name) &&
            model.entries.any { it.name == if (readOnly) "readonly" else "Provider note.txt" } }
        compose.waitForIdle(); providerRoot = model.location
        if (readOnly) { compose.runOnIdle { model.navigate(model.entries.single { it.name == "readonly" }.path) }; loaded() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(300)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "command-clarity-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun primaryNamesOrderAndBoundsStayFixedAcrossSelectionViewsSearchAndAddress() {
        fun bounds() = listOf("primary-search", "primary-new-folder", "primary-more").map {
            compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val initial = bounds()
        assertTrue(initial.zipWithNext().all { (a,b) -> a.right <= b.left })
        select()
        for (mode in ViewMode.entries) {
            compose.runOnIdle { model.changeMode(mode) }
            assertEquals(initial, bounds())
            compose.onNodeWithTag("selection-actions-primary", useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithTag("primary-search").performClick()
        compose.onNodeWithContentDescription("Close search").assertExists()
        assertEquals(initial, bounds())
        compose.onNodeWithTag("primary-search").performClick()
        key(Key.L, control = true)
        compose.onNodeWithContentDescription("Address bar").assertExists()
        assertEquals(initial, bounds())
        compose.onNodeWithContentDescription("Close address bar").performClick()
        screenshot("stable-primary")
    }

    @Test fun selectionActionsActivateTheirPaneAndLeaveOnlyOneNamedDragHandle() {
        compose.runOnIdle { model.toggleDualPane(); model.secondaryPane.navigate(base.path) }
        compose.waitUntil(15_000) { !model.secondaryPane.loading }
        compose.runOnIdle {
            model.select(model.entries.first { it.name == note.name })
            model.secondaryPane.select(model.secondaryPane.entries.first { it.name == "Destination" })
            model.activatePane()
        }
        compose.onAllNodesWithTag("drag-selection").assertCountEquals(1)
        compose.onNodeWithTag("selection-actions-secondary", useUnmergedTree = true).performClick()
        assertSame(model.secondaryPane, model.activePaneModel)
        compose.onNodeWithText("Open folder").assertExists()
        assertEquals(setOf(note.path), model.selected)
        compose.onAllNodesWithTag("drag-selection").assertCountEquals(1)
        key(Key.Escape)
    }

    @Test fun virtualFolderCreationExplainsScopeAndCanChooseARealDestination() {
        compose.runOnIdle { model.navigate("@recent") }; loaded()
        compose.onNodeWithTag("primary-new-folder").assertIsEnabled().performClick()
        compose.onNodeWithTag("command-explanation").assertExists()
        compose.onNodeWithText("This is a library or search view. Choose a real folder for new files.").assertExists()
        compose.onNodeWithText("Location: Recents").assertExists()
        compose.onNodeWithText("Choose a folder…").performClick()
        compose.onNodeWithTag("launcher-query").performTextInput(base.path)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("folder-${base.path}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-${base.path}").performClick(); loaded()
        assertEquals(base.path, model.location)
        compose.onNodeWithTag("primary-new-folder").performClick()
        compose.onNodeWithText("Create").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(setOf("Brief.txt", "Destination"), base.list()!!.toSet())
    }

    @Test fun readOnlyProviderRenameExplainsAndOffersACopyDestination() {
        connect(); select("Provider note.txt")
        compose.onNodeWithTag("selection-actions-primary", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Rename…").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("command-explanation").assertExists()
        compose.onNodeWithText("Rename is unavailable").assertExists()
        screenshot("readonly-rename")
        compose.onNodeWithText("Copy to another folder…").performClick()
        compose.onNodeWithTag("command-explanation").assertDoesNotExist()
        compose.onNodeWithTag("destination-picker").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Original provider text", File(fixture, "readonly/Provider note.txt").readText())
    }

    @Test fun readOnlyCreationAndKeyboardRenameUseTheSameRecoveryPolicy() {
        connect(); select("Provider note.txt")
        key(Key.F2)
        compose.onNodeWithText("Rename is unavailable").assertExists()
        compose.onNodeWithText("Back to files").performClick()
        compose.onNodeWithTag("primary-new-folder").performClick()
        compose.onNodeWithText("New folder is unavailable").assertExists()
        compose.onNodeWithTag("command-recovery").assertTextContains("Storage locations…").performClick()
        compose.onNodeWithText("Storage locations").assertExists()
        assertEquals("Original provider text", File(fixture, "readonly/Provider note.txt").readText())
    }

    @Test fun readOnlyProviderMoveOffersCopyWithoutChangingTheOriginal() {
        connect(); select("Provider note.txt")
        compose.onNodeWithTag("selection-actions-primary", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Move to…").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Move is unavailable").assertExists()
        compose.onNodeWithTag("command-recovery").assertTextContains("Copy to another folder…").performClick()
        compose.onNodeWithTag("destination-picker").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Original provider text", File(fixture, "readonly/Provider note.txt").readText())
    }

    @Test fun disconnectedFolderCreationExplainsStorageRecoveryAndPreservesItsScope() {
        val missing = File(base, "Disconnected location").apply { mkdir() }
        compose.runOnIdle { model.navigate(missing.path) }; loaded()
        assertTrue(missing.delete())
        compose.runOnIdle { model.refresh() }; loaded()
        compose.onNodeWithTag("primary-new-folder").performClick()
        compose.onNodeWithText("This storage location is unavailable. Reconnect it before continuing.").assertExists()
        compose.onNodeWithText("Location: ${model.displayPath(missing.path)}").assertExists()
        compose.onNodeWithTag("command-recovery").assertTextContains("Storage locations…")
        compose.onNodeWithText("Back to files").performClick()
        assertEquals(missing.path, model.location)
        assertEquals("Keep this original brief", note.readText())
    }

    @Test fun launcherKeepsTheReasonAndOffersTheSameCopyRecovery() {
        connect(); select("Provider note.txt")
        key(Key.P, control = true, shift = true)
        compose.onNodeWithTag("launcher-query").performTextInput("rename")
        compose.onNodeWithTag("action-rename").performClick()
        compose.onNodeWithTag("launcher-error").assertExists()
        compose.onNodeWithTag("explorer-launcher").assertExists()
        compose.onNodeWithTag("launcher-recovery").assertTextContains("Copy to another folder…").performClick()
        compose.onNodeWithTag("destination-picker").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun emptyClipboardExplanationDoesNotChangeFilesOrSelection() {
        select()
        key(Key.V, control = true)
        compose.onNodeWithText("Paste is unavailable").assertExists()
        compose.onNodeWithText("Copy or cut files first, then choose where to paste them.").assertExists()
        compose.onNodeWithText("Back to files").performClick()
        assertEquals(setOf(note.path), model.selected)
        assertEquals("Keep this original brief", note.readText())
    }

    @Test fun anActiveProviderTransferExplainsRenameButStillAllowsQueuedCopyChoices() {
        connect(readOnly = false)
        File(fixture, "Gate.bin").writeBytes(ByteArray(1024 * 1024) { 41 })
        compose.runOnIdle { model.refresh() }; loaded()
        val held = CountDownLatch(1); gate = held
        FixtureDocumentsProvider.transferGate = held; FixtureDocumentsProvider.gateOpened = CountDownLatch(1)
        compose.runOnIdle {
            model.select(model.entries.first { it.name == "Gate.bin" }); model.copySelection(false)
            model.navigate(base.path)
        }
        loaded()
        compose.runOnIdle { model.paste() }
        assertTrue(FixtureDocumentsProvider.gateOpened!!.await(8, TimeUnit.SECONDS))
        select()
        key(Key.F2)
        compose.onNodeWithText("Rename is unavailable").assertExists()
        compose.onNodeWithTag("command-recovery").assertTextContains("View transfers").performClick()
        compose.onNodeWithTag("transfer-center").assertExists()
        compose.onNodeWithContentDescription("Close transfers").performClick()
        key(Key.P, control = true, shift = true)
        compose.onNodeWithTag("launcher-query").performTextInput("copy to folder")
        compose.onNodeWithTag("action-copyTo").performClick()
        compose.onNodeWithTag("destination-picker").assertExists()
        assertNotNull("Copy choices must remain usable while the original transfer is busy", model.busy)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Keep this original brief", note.readText())
        held.countDown(); loaded()
        assertEquals(1024L * 1024, File(base, "Gate.bin").length())
    }

    @Test fun recentActionsExplainsAnEmptySessionThroughTheNamedMenu() {
        recent()
        compose.onNodeWithText("No recent actions").assertExists()
        compose.onNodeWithTag("recent-undo").assertIsNotEnabled()
        compose.onNodeWithTag("recent-redo").assertIsNotEnabled()
        recentControl("close-recent-actions").performClick()
    }

    @Test fun recentActionsUndoAndRedoShowTheAffectedFolderWithoutLosingOriginals() {
        compose.onNodeWithTag("primary-new-folder").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Review folder")
        compose.onNodeWithText("Create").performClick(); loaded()
        val created = File(base, "Review folder")
        assertTrue(created.isDirectory)
        recent()
        compose.onNodeWithText(created.path, substring = true).assertExists()
        screenshot("recent-created")
        recentControl("recent-undo").performClick(); loaded()
        assertFalse(created.exists())
        compose.onNodeWithText("Undone · Next to redo").assertExists()
        recentControl("recent-redo").performClick(); loaded()
        assertTrue(created.isDirectory)
        compose.onNodeWithText("Completed · Next to undo").assertExists()
        assertEquals("Keep this original brief", note.readText())
        recentControl("close-recent-actions").performClick()
    }

    @Test fun editedFileStopsUndoAndShowsThePersistentReasonWithoutOverwritingBytes() {
        compose.runOnIdle { model.createTextFile("Draft.txt") }; loaded()
        val draft = File(base, "Draft.txt").apply { writeText("Later edits must survive") }
        recent()
        recentControl("recent-undo").performClick(); loaded()
        compose.onNodeWithTag("recent-actions-list").performScrollToNode(hasTestTag("recent-action-failure"))
        compose.onNodeWithTag("recent-action-failure").assertExists()
        compose.onNode(hasText("Draft.txt or its folder changed after this action. Saved Undo stopped to protect the current files.") and hasAnyAncestor(hasTestTag("recent-action-failure"))).assertExists()
        assertEquals("Later edits must survive", draft.readText())
        compose.onNodeWithTag("recent-redo").assertIsNotEnabled()
        screenshot("guarded-undo")
        recentControl("close-recent-actions").performClick(); recent()
        compose.onNodeWithTag("recent-actions-list").performScrollToNode(hasTestTag("recent-action-failure"))
        compose.onNodeWithTag("recent-action-failure").assertExists()
        assertEquals("Later edits must survive", draft.readText())
        recentControl("close-recent-actions").performClick()
    }

    @Test fun providerHistoryShowsTheActualNameAndGuardsLaterEdits() {
        connect(readOnly = false)
        compose.runOnIdle { model.createTextFile("Provider draft.txt") }; loaded()
        val created = File(fixture, "Provider draft.txt").apply { writeText("Provider edits must survive") }
        recent()
        compose.onNode(hasText("Provider draft.txt", substring = true) and hasAnyAncestor(hasTestTag("recent-actions"))).assertExists()
        recentControl("recent-undo").performClick(); loaded()
        compose.onNodeWithTag("recent-actions-list").performScrollToNode(hasTestTag("recent-action-failure"))
        compose.onNodeWithTag("recent-action-failure").assertExists()
        assertEquals("Provider edits must survive", created.readText())
        recentControl("close-recent-actions").performClick()
    }

    @Test fun recentActionsAreSharedAcrossPanesWindowsAndActivityRecreation() {
        compose.runOnIdle { model.createFolder("Shared action") }; loaded()
        val before = model.recentActions
        compose.runOnIdle { model.toggleDualPane(); model.secondaryPane.navigate(base.path); model.secondaryPane.activatePane() }
        compose.waitUntil(15_000) { !model.secondaryPane.loading }
        assertEquals(before, model.secondaryPane.recentActions)
        val application = compose.activity.application as FinderApplication
        val windowId = "commands-${System.nanoTime()}"
        val other = compose.runOnIdle { application.windowModel(windowId, base.path) }
        try {
            assertEquals(before, other.recentActions)
        } finally { compose.runOnIdle { application.releaseWindow(windowId) } }
        compose.activityRule.scenario.recreate(); loaded()
        assertEquals(before, model.recentActions)
        recent(); recentControl("recent-undo").performClick(); loaded()
        assertFalse(File(base, "Shared action").exists())
        assertEquals(model.recentActions, model.secondaryPane.recentActions)
        recentControl("close-recent-actions").performClick()
    }

    @Test fun narrowLargeTextKeepsPrimaryActionsAndRecoveryControlsReachable() {
        compose.runOnIdle {
            if (model.sidebarVisible) model.toggleSidebar()
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    Box(Modifier.width(600.dp)) { FinderApp(model, {}, {}, {}, {}, {}) }
                }
            }
        }
        select()
        listOf("primary-search", "primary-new-folder", "primary-more", "selection-actions-primary").forEach {
            compose.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed()
        }
        screenshot("600-large-text")
        compose.runOnIdle { model.createTextFile("Large text.txt") }; loaded()
        File(base, "Large text.txt").writeText("Keep later changes")
        recent(); recentControl("recent-undo").performClick(); loaded()
        compose.onNodeWithTag("recent-action-failure").performScrollTo().assertIsDisplayed()
        recentControl("recent-undo").assertIsDisplayed()
        recentControl("close-recent-actions").assertIsDisplayed().performClick()
    }
}
