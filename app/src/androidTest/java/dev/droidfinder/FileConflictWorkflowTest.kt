package dev.droidfinder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FileConflictWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun singlePaneLayout() {
        compose.runOnIdle {
            val root = compose.activity.model
            if (root.dualPane) root.toggleDualPane()
        }
    }

    private val model
        get() = compose.activity.model

    private lateinit var folder: File
    private lateinit var out: File

    @Before
    fun setup() {
        folder =
            File(compose.activity.filesDir, "Workspace/Conflicts-${System.nanoTime()}").apply {
                mkdirs()
            }
        out = File(folder, "Out").apply { mkdir() }
        compose.runOnIdle {
            model.changeConflictPolicy(ConflictPolicy.ASK)
            model.navigate(folder.path)
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(15_000) {
            model.busy == null && !model.loading && model.activeTransferId == null
        }
    }

    private fun conflict() {
        compose.waitUntil(10_000) { model.fileConflict != null }
        compose.onNodeWithTag("file-conflict").assertExists()
    }

    private fun choose(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    @After
    fun cleanup() {
        if (model.busy != null) {
            model.cancelTransfer()
            loaded()
        }
        compose.runOnIdle { model.navigate(model.workspace.path) }
        folder.deleteRecursively()
    }

    private fun replacement(move: Boolean) {
        val source = File(folder, "Same.txt").apply { writeText("incoming") }
        val target = File(out, "Same.txt").apply { writeText("original") }
        compose.runOnIdle { model.transferPaths(listOf(source.path), out.path, move) }
        conflict()
        compose.onNodeWithText(model.displayPath(source.path)).assertExists()
        compose.onNodeWithText(model.displayPath(target.path)).assertExists()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand("screencap -p /sdcard/droidfinder-file-conflict.png"),
            )
            .use { it.readBytes() }
        choose("Replace with recovery")
        loaded()
        assertEquals("incoming", target.readText())
        assertEquals(!move, source.exists())
        compose.runOnIdle { model.undo() }
        loaded()
        assertEquals("original", target.readText())
        assertEquals("incoming", source.readText())
        compose.runOnIdle { model.redo() }
        loaded()
        assertEquals("incoming", target.readText())
        assertEquals(!move, source.exists())
        target.writeText("external edit")
        compose.runOnIdle { model.undo() }
        loaded()
        assertEquals("external edit", target.readText())
        assertEquals(!move, source.exists())
    }

    @Test fun replaceCopyCanUndoRedoAndProtectLaterEdits() = replacement(false)

    @Test fun replaceMoveCanUndoRedoAndProtectLaterEdits() = replacement(true)

    @Test
    fun renameAndSkipRemainingKeepOriginalsAndPersistResults() {
        val paths =
            listOf("A.txt", "B.txt", "C.txt").map { name ->
                File(out, name).writeText("existing $name")
                File(folder, name).apply { writeText("incoming $name") }.path
            }
        lateinit var id: String
        compose.runOnIdle {
            model.transferPaths(paths, out.path, false)
            id = model.transferJobs.last().id
        }
        conflict()
        choose("Rename incoming…")
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed.txt")
        choose("Use new name")
        compose.waitUntil(10_000) { model.fileConflict?.source?.name == "B.txt" }
        compose.onNodeWithText("Apply this choice to remaining conflicts").performClick()
        choose("Skip this item")
        loaded()
        assertEquals("incoming A.txt", File(out, "Renamed.txt").readText())
        listOf("A.txt", "B.txt", "C.txt").forEach {
            assertEquals("existing $it", File(out, it).readText())
        }
        assertEquals(setOf("A.txt", "B.txt", "C.txt", "Renamed.txt"), out.list()!!.toSet())
        val job = SqliteTransferStore(compose.activity).use { it.load() }.first { it.id == id }
        assertEquals(2, job.results.count { it.skipped })
        assertTrue(job.remaining.isEmpty())
        assertEquals(TransferState.COMPLETED, job.state)
    }

    @Test
    fun changedDestinationIsNotReplacedAfterThePromptWasShown() {
        val source = File(folder, "Same.txt").apply { writeText("incoming") }
        val target = File(out, "Same.txt").apply { writeText("old") }
        compose.runOnIdle { model.transferPaths(listOf(source.path), out.path, false) }
        conflict()
        target.writeText("external edit while deciding")
        choose("Replace with recovery")
        loaded()
        assertEquals("external edit while deciding", target.readText())
        assertEquals("incoming", source.readText())
        assertEquals(TransferState.FAILED, model.transferJobs.last().state)
    }

    @Test
    fun descendantDestinationIsRejectedBeforeAnyConflictOrReplacement() {
        val source = File(folder, "Tree").apply { mkdir() }
        val descendant = File(source, "Child").apply { mkdir() }
        val existing = File(descendant, "Tree").apply { mkdir() }
        File(existing, "Original.txt").writeText("keep")
        compose.runOnIdle { model.transferPaths(listOf(source.path), descendant.path, true) }
        compose.waitUntil(10_000) { model.transferJobs.last().terminal && model.busy == null }
        assertNull(model.fileConflict)
        assertEquals("keep", File(existing, "Original.txt").readText())
        assertTrue(source.isDirectory)
        assertEquals(TransferState.FAILED, model.transferJobs.last().state)
    }

    @Test
    fun mergeDialogAndRetryKeepNestedDestinationsAndCompletedMembers() {
        val source = File(folder, "Project/Shared").apply { mkdirs() }
        File(source, "Same.txt").writeText("incoming")
        File(source, "Good.txt").writeText("good")
        val target = File(out, "Project/Shared").apply { mkdirs() }
        File(target, "Same.txt").writeText("original")
        File(target, "Blocked.txt").writeText("keep")
        lateinit var id: String
        compose.runOnIdle {
            model.clearTransferHistory()
            model.transferPaths(listOf(source.parentFile!!.path), out.path, false)
            id = model.transferJobs.last().id
        }
        conflict()
        choose("Merge folders")
        compose.waitUntil(10_000) { model.fileConflict?.source?.name == "Same.txt" }
        compose.onNodeWithText("Merge folders").assertDoesNotExist()
        choose("Rename incoming…")
        compose.onNode(hasSetTextAction()).performTextReplacement("Blocked.txt")
        choose("Use new name")
        loaded()
        val job = model.transferJobs.first { it.id == id }
        assertEquals(TransferState.FAILED, job.state)
        assertEquals(
            listOf(TransferRetryTarget(File(source, "Same.txt").path, target.path)),
            job.retryTargets,
        )
        assertEquals("original", File(target, "Same.txt").readText())
        assertEquals("keep", File(target, "Blocked.txt").readText())
        assertEquals("good", File(target, "Good.txt").readText())
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Transfers", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Folder merge", substring = true).assertExists()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand("screencap -p /sdcard/droidfinder-folder-merge.png"),
            )
            .use { it.readBytes() }
        compose.onNodeWithContentDescription("Close transfers").performClick()
        compose.runOnIdle { model.retryTransfer(id) }
        compose.waitUntil(15_000) {
            model.transferJobs.last().state == TransferState.COMPLETED && model.busy == null
        }
        loaded()
        assertEquals(TransferState.COMPLETED, model.transferJobs.last().state)
        assertEquals(
            setOf("Same.txt", "Same 2.txt", "Good.txt", "Blocked.txt"),
            target.list()!!.toSet(),
        )
        assertEquals("incoming", File(target, "Same 2.txt").readText())
        assertEquals(setOf("Project"), out.list()!!.toSet())
        assertEquals(model.transferJobs, SqliteTransferStore(compose.activity).use { it.load() })
    }
}
