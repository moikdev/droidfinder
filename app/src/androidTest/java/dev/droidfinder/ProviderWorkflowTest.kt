package dev.droidfinder

import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProviderWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: File
    private lateinit var local: File
    private lateinit var root: String

    @Before
    fun setup() {
        fixture =
            File(compose.activity.filesDir, "ProviderFixtures/Provider-${System.nanoTime()}")
                .apply { mkdirs() }
        File(fixture, "Notes.md").writeText("# Provider notes\nCloud-style files on this tablet.")
        File(fixture, "readonly").mkdir()
        File(fixture, "readonly/Readme.txt").writeText("Read-only provider content")
        local =
            File(compose.activity.filesDir, "Workspace/Provider-local-${System.nanoTime()}").apply {
                mkdirs()
            }
        val uri =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${fixture.name}",
            )
        compose.runOnIdle {
            val model = compose.activity.model
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.clearSelection()
            if (!model.previewVisible) model.togglePreview()
            model.connectLocation(uri, false)
        }
        compose.waitUntil(10_000) {
            val model = compose.activity.model
            model.location.contains(fixture.name) &&
                !model.loading &&
                model.entries.any { it.name == "Notes.md" && it.parentPath == model.location }
        }
        root = compose.activity.model.location
    }

    @After
    fun cleanup() {
        compose.runOnIdle {
            val model = compose.activity.model
            model.navigate(model.workspace.path)
            model.connectedLocations
                .filter { it.uri == root }
                .forEach { model.disconnectLocation(it.uri) }
        }
        fixture.deleteRecursively()
        local.deleteRecursively()
    }

    private fun loaded() {
        compose.waitUntil(10_000) {
            !compose.activity.model.loading && compose.activity.model.busy == null
        }
        compose.waitForIdle()
    }

    @Test
    fun providerQuickLookAndCreateRename() {
        compose.onNodeWithText("Notes.md").performClick()
        compose.waitUntil(5000) { compose.activity.model.focused?.name == "Notes.md" }
        compose.onNodeWithText("Quick Look", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText("# Provider notes\nCloud-style files on this tablet.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("New provider folder")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { File(fixture, "New provider folder").isDirectory }
        loaded()
        compose.runOnIdle {
            val model = compose.activity.model
            model.select(model.entries.first { it.name == "Notes.md" })
            model.rename("Renamed.md")
        }
        compose.waitUntil(10_000) { File(fixture, "Renamed.md").exists() }
        loaded()
    }

    @Test
    fun providerLocalTransfersUndoAndRedo() {
        val source = compose.activity.model.entries.first { it.name == "Notes.md" }.path
        compose.runOnIdle {
            compose.activity.model.transferPaths(listOf(source), local.path, false)
        }
        val copy = File(local, "Notes.md")
        compose.waitUntil(10_000) { copy.exists() }
        loaded()
        assertTrue(File(fixture, "Notes.md").exists())
        copy.renameTo(File(local, "Moved.md"))
        val moved = File(local, "Moved.md")
        compose.runOnIdle { compose.activity.model.transferPaths(listOf(moved.path), root, true) }
        compose.waitUntil(10_000) { !moved.exists() && File(fixture, "Moved.md").exists() }
        loaded()
        compose.runOnIdle { compose.activity.model.undo() }
        compose.waitUntil(10_000) { moved.exists() && !File(fixture, "Moved.md").exists() }
        loaded()
        compose.runOnIdle { compose.activity.model.redo() }
        compose.waitUntil(10_000) { !moved.exists() && File(fixture, "Moved.md").exists() }
        loaded()
    }

    @Test
    fun providerTrashAndRestorePreserveOriginalLocation() {
        compose.runOnIdle {
            val model = compose.activity.model
            model.select(model.entries.first { it.name == "Notes.md" })
            model.trash()
        }
        compose.waitUntil(10_000) { !File(fixture, "Notes.md").exists() }
        loaded()
        compose.runOnIdle { compose.activity.model.navigate("@trash") }
        loaded()
        compose.runOnIdle {
            val model = compose.activity.model
            model.select(
                model.entries.first {
                    model.engine.originalPath(it.file)?.startsWith("content://") == true
                },
            )
            model.restore()
        }
        compose.waitUntil(10_000) { File(fixture, "Notes.md").exists() }
        loaded()
        assertTrue(File(fixture, "Notes.md").readText().contains("Provider notes"))
    }

    @Test
    fun readOnlyProviderIsBrowsableAndCopyable() {
        val folder = compose.activity.model.entries.first { it.name == "readonly" }
        compose.runOnIdle { compose.activity.model.navigate(folder.path) }
        loaded()
        val entry = compose.activity.model.entries.single()
        assertFalse(entry.writable)
        assertFalse(entry.canDelete)
        compose.runOnIdle {
            compose.activity.model.transferPaths(listOf(entry.path), local.path, false)
        }
        compose.waitUntil(10_000) { File(local, "Readme.txt").exists() }
        loaded()
        compose.runOnIdle {
            compose.activity.model.transferPaths(listOf(entry.path), local.path, true)
        }
        loaded()
        assertTrue(File(fixture, "readonly/Readme.txt").exists())
    }
}
