package dev.droidfinder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class FinderWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: File

    @Before fun setup() {
        fixture = File(compose.activity.filesDir, "Workspace/Workflow-${System.nanoTime()}").apply { mkdirs() }
        File(fixture, "Notes.md").writeText("# Tablet notes\nA real text preview.")
        File(fixture, "Destination").mkdir()
        File(fixture, "Destination/Nested.txt").writeText("Find me in a subfolder")
        compose.runOnIdle { compose.activity.model.changeMode(ViewMode.LIST); compose.activity.model.clearSelection(); if (!compose.activity.model.previewVisible) compose.activity.model.togglePreview(); compose.activity.model.navigate(fixture.absolutePath) }
        loaded()
    }
    @After fun cleanup() {
        compose.runOnIdle { val model = compose.activity.model; model.updateQuery(""); model.navigate(model.workspace.absolutePath); model.clearSelection() }
        fixture.deleteRecursively()
    }
    private fun loaded() { compose.waitUntil(10_000) { !compose.activity.model.loading && compose.activity.model.busy == null }; compose.waitForIdle() }

    @Test fun createRenameCopyTrashRestoreWorkflow() {
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Created from tablet")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { File(fixture, "Created from tablet").isDirectory }; loaded()

        compose.onNodeWithText("Notes.md").performClick()
        compose.waitUntil(5_000) { compose.activity.model.focused?.name == "Notes.md" }
        compose.runOnIdle { assertEquals("Notes.md", compose.activity.model.focused?.name) }
        compose.onNodeWithContentDescription("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed.md")
        compose.onNodeWithText("Rename", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { File(fixture, "Renamed.md").exists() }; loaded()
        compose.onNodeWithText("Renamed.md").performClick()
        compose.waitUntil(5_000) { compose.activity.model.focused?.name == "Renamed.md" }
        compose.onNodeWithContentDescription("Copy", useUnmergedTree = true).performClick()
        compose.runOnIdle { compose.activity.model.navigate(File(fixture, "Destination").absolutePath) }; loaded()
        compose.onNodeWithContentDescription("Paste 1 items").performClick()
        val copied = File(fixture, "Destination/Renamed.md")
        compose.waitUntil(10_000) { copied.exists() }; loaded()
        assertEquals(File(fixture, "Renamed.md").readText(), copied.readText())

        compose.onNodeWithText("Renamed.md").performClick()
        compose.waitUntil(5_000) { compose.activity.model.focused?.name == "Renamed.md" }
        compose.onNodeWithContentDescription("Move to Trash").performClick()
        compose.waitUntil(10_000) { !copied.exists() }; loaded()
        compose.runOnIdle { compose.activity.model.navigate("@trash") }; loaded()
        compose.onNodeWithText("Renamed.md").performClick()
        compose.waitUntil(5_000) { compose.activity.model.focused?.name == "Renamed.md" }
        compose.onNodeWithText("Restore", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { copied.exists() }; loaded()
        assertEquals("# Tablet notes\nA real text preview.", copied.readText())
    }

    @Test fun allViewsRenderAndQuickLookShowsText() {
        listOf("Icons view", "Columns view", "Gallery view", "List view").forEach { description -> compose.onNodeWithContentDescription(description).performClick(); compose.waitForIdle() }
        compose.onNodeWithText("Notes.md").performClick()
        compose.waitUntil(5_000) { compose.activity.model.focused?.name == "Notes.md" }
        compose.onNodeWithText("Quick Look", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Close Quick Look").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("# Tablet notes\nA real text preview.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
    }

    @Test fun searchIncludesNestedFiles() {
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Nested")
        compose.waitUntil(10_000) { compose.activity.model.entries.any { it.name == "Nested.txt" } }; loaded()
        compose.onNodeWithText("Nested.txt").assertExists()
        assertEquals(1, compose.activity.model.entries.size)
    }

    @Test fun longPressEnablesMultipleSelectionAndTagging() {
        compose.onNodeWithText("Notes.md").performTouchInput { longClick() }
        compose.runOnIdle { assertTrue("Long press should enable multiple selection: ${compose.activity.model.selected}", compose.activity.model.multiSelecting); assertEquals(1, compose.activity.model.selected.size) }
        compose.onNodeWithText("Destination").performClick()
        compose.waitUntil(5_000) { compose.activity.model.selected.size == 2 }
        compose.runOnIdle { assertEquals(2, compose.activity.model.selected.size); compose.activity.model.tag("Blue") }
        compose.runOnIdle { compose.activity.model.navigate("@tag:Blue") }; loaded()
        assertEquals(setOf("Notes.md", "Destination"), compose.activity.model.entries.filter { it.path.startsWith(fixture.path) }.map { it.name }.toSet())
    }

    @Test fun tabsKeepIndependentHistory() {
        compose.onNodeWithContentDescription("New tab").performClick()
        compose.runOnIdle { compose.activity.model.navigate(File(fixture, "Destination").absolutePath) }; loaded()
        compose.onNodeWithText("Nested.txt").assertExists()
        compose.onNodeWithContentDescription("Back").performClick(); loaded()
        assertEquals(fixture.absolutePath, compose.activity.model.location)
        compose.runOnIdle { val model = compose.activity.model; model.closeTab(model.activeTab) }; loaded()
        assertEquals(fixture.absolutePath, compose.activity.model.location)
    }

    @Test fun snackbarUndoRestoresTrashedFile() {
        val file = File(fixture, "Notes.md")
        compose.onNodeWithText("Notes.md").performClick()
        compose.waitUntil(5_000) { compose.activity.model.focused?.name == "Notes.md" }
        compose.onNodeWithContentDescription("Move to Trash").performClick()
        compose.waitUntil(10_000) { !file.exists() && compose.activity.model.canUndoTrash }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { file.exists() }; loaded()
        assertEquals("# Tablet notes\nA real text preview.", file.readText())
    }

    @Test fun keyboardSelectAllAndSearch() {
        compose.onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft) }
        compose.waitUntil(5_000) { compose.activity.model.selected.size == 2 }
        compose.onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
        compose.onNode(hasSetTextAction()).performTextInput("Notes")
        compose.waitUntil(10_000) { compose.activity.model.entries.size == 1 && compose.activity.model.entries.first().name == "Notes.md" }
        compose.onNodeWithContentDescription("Close search").performClick(); loaded()
    }
}
