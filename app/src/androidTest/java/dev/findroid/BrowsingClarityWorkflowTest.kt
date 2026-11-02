@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.*
import org.junit.Assert.*

class BrowsingClarityWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var base: File
    private lateinit var writing: File
    private lateinit var savedSidebar: SidebarLayout
    private var savedHidden = false
    private var savedHint = false

    @Before fun setup() {
        base = File(compose.activity.filesDir, "Workspace/Clarity-${System.nanoTime()}").apply { mkdirs() }
        writing = File(base, "Writing projects").apply { mkdir() }
        File(writing, "Brief.txt").writeText("Keep the original text")
        File(base, "Note.txt").writeText("ordinary note")
        compose.runOnIdle {
            savedSidebar = model.sidebar; savedHidden = model.showHidden; savedHint = model.gestureHintVisible
            if (model.dualPane) model.toggleDualPane()
            if (!model.sidebarVisible) model.toggleSidebar()
            model.activatePane(); model.closeOtherTabs(model.activeTab)
            model.navigate(base.path); model.changeMode(ViewMode.LIST)
            if (model.showHidden) model.toggleHidden()
            model.showGestureHint(false)
            model.editSidebar { SidebarLayout(listOf(FavoriteShortcut("writing", writing.path, "Writing projects")), hidden = SidebarBuiltin.entries.toSet()) }
        }
        loaded()
    }
    @After fun cleanup() {
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.navigate(model.workspace.path); model.closeOtherTabs(model.activeTab)
            model.editSidebar { savedSidebar }
            if (model.showHidden != savedHidden) model.toggleHidden()
            model.showGestureHint(savedHint)
            model.folderViews.values.keys.filter { pathWithin(it, folderViewKey(base.path)!!) }.forEach(model::forgetFolderView)
        }
        loaded(); base.deleteRecursively()
    }
    private fun loaded() { compose.waitUntil(15_000) { !model.loading && model.busy == null }; compose.waitForIdle() }
    private fun launch(folders: Boolean = false) {
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            if (!folders) keyDown(Key.ShiftLeft)
            pressKey(if (folders) Key.K else Key.P)
            if (!folders) keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        compose.onNodeWithTag("explorer-launcher").assertExists()
    }

    @Test fun searchableCommandRunsWithoutToolbarIcons() {
        launch()
        compose.onNodeWithTag("launcher-query").performTextInput("show hidden")
        compose.onNodeWithTag("action-hidden").performScrollTo().performClick()
        compose.onNodeWithTag("explorer-launcher").assertDoesNotExist()
        assertTrue(model.showHidden)
    }
    @Test fun unavailableActionExplainsItsReasonAndKeepsLauncherOpen() {
        compose.runOnIdle { model.clearSelection() }
        launch()
        compose.onNodeWithTag("launcher-query").performTextInput("rename")
        compose.onNodeWithTag("action-rename").performClick()
        compose.onNodeWithTag("launcher-error").assertTextContains("Select a file or folder first.")
        compose.onNodeWithTag("explorer-launcher").assertExists()
        compose.onNodeWithTag("launcher-query").performKeyInput { pressKey(Key.Escape) }
    }
    @Test fun quickFolderSearchSupportsKeyboardAndShowsFullDestination() {
        launch(true)
        compose.onNodeWithTag("launcher-query").performTextInput("Writing projects")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("folder-${writing.path}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-${writing.path}").assertTextContains(model.displayPath(writing.path))
        compose.onNodeWithTag("launcher-query").performKeyInput { pressKey(Key.Enter) }
        loaded()
        assertEquals(writing.path, model.location)
        assertEquals("Keep the original text", File(writing, "Brief.txt").readText())
    }
    @Test fun exactPathCanFindFolderOutsideListedFavorites() {
        val other = File(base, "Other destination").apply { mkdir() }
        compose.runOnIdle { model.navigate(writing.path) }; loaded()
        launch(true)
        compose.onNodeWithTag("launcher-query").performTextInput(other.path)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("folder-${other.path}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-${other.path}").performClick()
        loaded(); assertEquals(other.path, model.location)
    }
    @Test fun staleDestinationExplainsReconnectWithoutNavigatingAway() {
        val missing = File(base, "Missing destination")
        compose.runOnIdle { model.repairFavorite("writing", missing.path) }
        launch(true)
        compose.onNodeWithTag("launcher-query").performTextInput("Writing projects")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("folder-${missing.path}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-${missing.path}").performClick()
        compose.onNodeWithTag("launcher-error").assertExists()
        assertEquals(base.path, model.location)
        compose.onNodeWithTag("launcher-query").performKeyInput { pressKey(Key.Escape) }
    }
    @Test fun gestureTipsCanBeDismissedAndReplayedFromHelp() {
        compose.runOnIdle { model.showGestureHint(true) }
        compose.onNodeWithTag("file-gesture-hint").assertExists()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithTag("file-gesture-hint").assertDoesNotExist()
        assertTrue(compose.activity.getSharedPreferences("finder", 0).getBoolean("gestureHintDismissed", false))
        compose.onRoot().performKeyInput { pressKey(Key.F1) }
        compose.onNodeWithText("Show file gesture tips again").performClick()
        compose.onNodeWithContentDescription("Close shortcut guide").performClick()
        compose.onNodeWithTag("file-gesture-hint").assertExists()
    }
    @Test fun selectedScopeSurvivesViewChangesAndKeepsPaneIdentity() {
        compose.runOnIdle {
            model.select(model.entries.first { it.name == "Note.txt" })
            model.changeMode(ViewMode.ICONS)
        }
        compose.onNodeWithTag("selection-scope-primary").assertTextContains("1 selected")
        compose.runOnIdle { model.toggleDualPane(); model.otherPane.navigate(writing.path) }
        compose.waitUntil(15_000) { !model.secondaryPane.loading }
        compose.runOnIdle { model.secondaryPane.select(model.secondaryPane.entries.first()); model.secondaryPane.activatePane() }
        compose.onNodeWithTag("selection-scope-primary").assertTextContains("Left pane · 1 selected")
        compose.onNodeWithTag("selection-scope-secondary").assertTextContains("Right pane · 1 selected")
        assertEquals(1, model.selected.size)
    }
    @Test fun filteredEmptyFolderOffersShowHiddenAndThenRevealsFile() {
        val hidden = File(base, "Hidden-only").apply { mkdir() }
        File(hidden, ".private.txt").writeText("hidden synthetic file")
        compose.runOnIdle { model.navigate(hidden.path) }; loaded()
        compose.onNodeWithText("No visible files").assertExists()
        compose.onNodeWithText("Show hidden files").performClick()
        loaded(); assertTrue(model.entries.any { it.name == ".private.txt" })
    }
    @Test fun noSearchMatchesCanClearFiltersWithoutCreatingAnything() {
        compose.runOnIdle { model.updateQuery("no-such-file-92884") }; loaded()
        compose.onNodeWithText("No matching files").assertExists()
        compose.onNodeWithText("Clear search").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { model.query.isEmpty() }
        loaded(); assertEquals("", model.query)
        assertTrue(model.entries.any { it.name == "Note.txt" })
        assertEquals(setOf("Note.txt", "Writing projects"), base.listFiles()!!.map { it.name }.toSet())
    }
    @Test fun browsingPreferencesKeepExpertOptionsInAdvanced() {
        launch()
        compose.onNodeWithTag("launcher-query").performTextInput("Preferences")
        compose.onNodeWithTag("action-settings").performClick()
        compose.onNodeWithText("Interface density").assertExists()
        compose.onNodeWithText("Archive safety limits…").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("File conflicts").assertExists()
        compose.onNodeWithText("Archive safety limits…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Browsing").performScrollTo().performClick()
        compose.onNodeWithText("Interface density").assertExists()
        compose.onNodeWithText("Done").performClick()
    }

}
