package dev.droidfinder

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DensityWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun singlePaneLayout() {
        compose.runOnIdle {
            val root = compose.activity.model
            if (root.dualPane) root.toggleDualPane()
            root.changeDetailColumns(DetailColumns())
        }
    }

    private val model
        get() = compose.activity.model

    @Test
    fun comfortableControlsHaveRoomAndCompactDensityPersistsThroughRecreation() {
        val folder =
            File(compose.activity.filesDir, "Workspace/Density-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(folder, "Notes.txt").writeText("density")
        try {
            compose.runOnIdle {
                model.changeDensity(FinderDensity.COMFORTABLE)
                model.changeMode(ViewMode.LIST)
                model.navigate(folder.path)
                model.clearSelection()
            }
            compose.waitUntil(10_000) {
                !model.loading && model.entries.any { it.name == "Notes.txt" }
            }
            compose
                .onNodeWithContentDescription("Actions")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            compose
                .onNodeWithContentDescription("List view")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            compose
                .onNodeWithContentDescription("Actions for Notes.txt")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("Notes.txt").performClick()
            compose.waitUntil(5000) { model.focused?.name == "Notes.txt" }
            compose
                .onNodeWithContentDescription("Copy")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("Cut").assertIsDisplayed()
            compose.onNodeWithContentDescription("Rename").assertIsDisplayed()
            compose.onNodeWithContentDescription("Move to Trash").assertIsDisplayed()
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.onNodeWithText("Preferences…").performScrollTo().performClick()
            compose.onNodeWithText("Compact", substring = false).performClick()
            compose.runOnIdle { assertEquals(FinderDensity.COMPACT, model.density) }
            compose.onNodeWithText("Done").performClick()
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                assertEquals(FinderDensity.COMPACT, model.density)
                assertEquals(
                    "COMPACT",
                    compose.activity.getSharedPreferences("finder", 0).getString("density", null),
                )
                model.changeDensity(FinderDensity.COMFORTABLE)
                model.navigate(model.workspace.path)
            }
        } finally {
            compose.runOnIdle {
                model.changeDensity(FinderDensity.COMFORTABLE)
                model.navigate(model.workspace.path)
            }
            folder.deleteRecursively()
        }
    }

    @Test
    fun narrowComfortableListKeepsRoomForFilenamesBeforeDateMetadata() {
        val folder =
            File(compose.activity.filesDir, "Workspace/Narrow-${System.nanoTime()}").apply {
                mkdirs()
            }
        val name = "Quarterly project presentation.txt"
        File(folder, name).writeText("narrow layout")
        val extensions = model.showExtensions
        try {
            compose.runOnIdle {
                model.changeDensity(FinderDensity.COMFORTABLE)
                model.changeMode(ViewMode.LIST)
                if (!model.showExtensions) model.toggleExtensions()
                model.navigate(folder.path)
                model.clearSelection()
            }
            compose.waitUntil(10_000) { !model.loading && model.entries.any { it.name == name } }
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.width(442.dp).height(680.dp)) {
                            ListView(model, {}, {})
                        }
                    }
                }
            }
            compose.onNodeWithText("Date modified").assertDoesNotExist()
            compose.onNodeWithText("Kind").assertDoesNotExist()
            compose.onNodeWithText("Size").assertExists()
            val bounds =
                compose
                    .onNodeWithText(name, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(
                "Filenames need useful width in a narrow tablet pane",
                bounds.width / compose.activity.resources.displayMetrics.density >= 150f,
            )
        } finally {
            compose.runOnIdle {
                if (model.showExtensions != extensions) model.toggleExtensions()
                model.navigate(model.workspace.path)
            }
            folder.deleteRecursively()
        }
    }
}
