package dev.findroid

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AdvancedWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: File

    @Before
    fun setup() {
        fixture =
            File(compose.activity.filesDir, "Workspace/Advanced-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(fixture, "Notes.md").writeText("A file to inspect")
        File(fixture, "Nested").mkdir()
        File(fixture, "Nested/Child.txt").writeText("Nested content")
        val pdf = PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 500, index + 1).create())
                page.canvas.drawText(
                    "PDF page ${index+1}",
                    30f,
                    50f,
                    Paint().apply { textSize = 20f },
                )
                pdf.finishPage(page)
            }
            File(fixture, "Guide.pdf").outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        val samples = 44100 * 6
        val bytes = samples * 2
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray())
            .putInt(36 + bytes)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(44100)
            .putInt(88200)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray())
            .putInt(bytes)
        File(fixture, "Audio.wav").writeBytes(wav.array())
        compose.runOnIdle {
            val model = compose.activity.model
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.clearSelection()
            if (!model.previewVisible) model.togglePreview()
            model.navigate(fixture.path)
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(10_000) {
            !compose.activity.model.loading && compose.activity.model.busy == null
        }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        compose.runOnIdle {
            val model = compose.activity.model
            if (model.darkTheme) model.toggleTheme()
            model.navigate(model.workspace.path)
            model.clearSelection()
        }
        fixture.deleteRecursively()
    }

    private fun select(name: String) {
        compose.onNodeWithText(name).performMouseInput { click() }
        compose.waitUntil(5000) { compose.activity.model.focused?.name == name }
        compose.waitForIdle()
    }

    @Test
    fun expandedListCanRenameAChildAndUndo() {
        compose.onNodeWithContentDescription("Expand Nested").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Child.txt").fetchSemanticsNodes().isNotEmpty()
        }
        select("Child.txt")
        compose.runOnIdle { compose.activity.model.rename("Renamed child.txt") }
        compose.waitUntil(10_000) { File(fixture, "Nested/Renamed child.txt").exists() }
        loaded()
        compose.runOnIdle { compose.activity.model.undo() }
        compose.waitUntil(10_000) { File(fixture, "Nested/Child.txt").exists() }
        loaded()
    }

    @Test
    fun pdfQuickLookNavigatesAllPages() {
        select("Guide.pdf")
        compose.waitUntil(5000) {
            compose
                .onAllNodesWithText("Quick Look", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Quick Look", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Page 1 of 3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Next PDF page").performClick()
        compose.onNodeWithText("Page 2 of 3").assertExists()
        compose.onNodeWithContentDescription("Last PDF page").performClick()
        compose.onNodeWithText("Page 3 of 3").assertExists()
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
    }

    @Test
    fun audioQuickLookPreparesAndPlays() {
        select("Audio.wav")
        compose.onNodeWithText("Quick Look", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasContentDescription("Play audio") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play audio").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Pause audio").assertExists().performClick()
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
    }

    @Test
    fun iconViewSupportsMouseContextActions() {
        compose.onNodeWithContentDescription("Change view").performClick()
        compose.onNodeWithContentDescription("Icons view").performClick()
        compose.onNodeWithText("Notes.md").performMouseInput {
            click(button = MouseButton.Secondary)
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Get info").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Get info").performClick()
        compose.onNodeWithText("Full path").assertExists()
        compose.onNodeWithText("Done").performClick()
    }

    @Test
    fun typeToSelectAndAppShortcutsResolveTargets() {
        compose.onRoot().performKeyInput { pressKey(Key.N) }
        compose.waitUntil(5000) { compose.activity.model.focused?.name?.startsWith("N") == true }
        compose.runOnIdle {
            val model = compose.activity.model
            model.clearSelection()
            model.select(model.entries.first { it.name == "Nested" })
            model.makeShortcut()
        }
        compose.waitUntil(10_000) { File(fixture, "Nested.finderlink").exists() }
        loaded()
        compose.waitUntil(10_000) {
            compose.activity.model.entries.any {
                it.path == File(fixture, "Nested.finderlink").path
            }
        }
        compose.runOnIdle {
            val model = compose.activity.model
            model.followShortcut(model.entries.first { it.name == "Nested.finderlink" }) {
                model.navigate(it.path)
            }
        }
        compose.waitUntil(10_000) {
            compose.activity.model.location == File(fixture, "Nested").path
        }
        loaded()
        compose.onNodeWithText("Child.txt").assertExists()
    }

    @Test
    fun shiftSelectionCanGrowAndShrink() {
        select("Notes.md")
        compose.onRoot().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.DirectionUp)
            keyUp(Key.ShiftLeft)
        }
        compose.runOnIdle { assertEquals(2, compose.activity.model.selected.size) }
        compose.onRoot().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.DirectionDown)
            keyUp(Key.ShiftLeft)
        }
        compose.runOnIdle { assertEquals(1, compose.activity.model.selected.size) }
    }

    @Test
    fun mouseRectangleSelectsFilesFromEmptySpace() {
        val first = compose.onNodeWithText("Nested").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("Notes.md").fetchSemanticsNode().boundsInRoot
        val start = androidx.compose.ui.geometry.Offset(first.left + 10, last.bottom + 80)
        val end = androidx.compose.ui.geometry.Offset(first.right - 10, first.top + 3)
        compose.onRoot().performMouseInput {
            moveTo(start)
            press(MouseButton.Primary)
            moveTo(end)
            release(MouseButton.Primary)
        }
        compose.waitUntil(5000) {
            compose.activity.model.selected.size == compose.activity.model.entries.size
        }
    }

    @Test
    fun customTagRenameCanBeUndoneAndRedone() {
        val suffix = fixture.name.takeLast(6)
        val before = "Client-$suffix"
        val after = "Work-$suffix"
        val path = File(fixture, "Notes.md").path
        compose.runOnIdle {
            val model = compose.activity.model
            model.defineTag(before, "Green")
            model.select(model.entries.first { it.name == "Notes.md" })
            model.tag(before)
            model.renameTag(before, after, "Purple")
            model.undo()
        }
        compose.waitUntil(10_000) {
            before in compose.activity.model.tagAssignments[organizationKey(path)].orEmpty()
        }
        loaded()
        compose.runOnIdle { compose.activity.model.redo() }
        compose.waitUntil(10_000) {
            after in compose.activity.model.tagAssignments[organizationKey(path)].orEmpty()
        }
        loaded()
    }

    @Test
    fun sharingAFolderCreatesReadableZipWithoutChangingOriginal() {
        val archive = java.util.concurrent.atomic.AtomicReference<File?>()
        compose.runOnIdle {
            val model = compose.activity.model
            model.prepareFolderShare(listOf(model.entries.first { it.name == "Nested" })) {
                archive.set(it.single().file)
            }
        }
        compose.waitUntil(10_000) { archive.get() != null && compose.activity.model.busy == null }
        loaded()
        val zip = archive.get()!!
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                names += entry.name
            }
        }
        assertTrue("Nested/Child.txt" in names)
        assertTrue(File(fixture, "Nested/Child.txt").exists())
        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                compose.activity,
                "dev.findroid.files",
                zip,
            )
        compose.activity.contentResolver.openInputStream(uri)!!.use {
            assertEquals('P'.code, it.read())
            assertEquals('K'.code, it.read())
        }
        zip.parentFile!!.deleteRecursively()
    }
}
