package dev.findroid

import android.content.ClipDescription
import android.net.Uri
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OutgoingDragTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val files = mutableListOf<File>()

    private fun file(name: String) = File(compose.activity.filesDir, "Workspace/Drag-${System.nanoTime()}-$name")
        .apply { writeText("drag payload"); files += this }

    @After fun cleanup() { files.forEach { it.deleteRecursively() } }

    @Test fun filesHaveContentUrisAndReadOnlyGlobalGrants() {
        val first = file("Note.txt")
        val second = file("Photo.png")
        val data = finderDragData(compose.activity, compose.activity.model,
            listOf(Entry(first), Entry(second)), FinderDragMode.MOVE)
        assertEquals(View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ, data.flags)
        assertEquals(2, data.clipData.itemCount)
        assertTrue(data.clipData.description.hasMimeType("text/plain"))
        assertTrue(data.clipData.description.hasMimeType("image/png"))
        assertTrue(data.clipData.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST))
        for (i in 0 until data.clipData.itemCount) {
            val uri = data.clipData.getItemAt(i).uri
            assertEquals("content", uri.scheme)
            assertEquals("${compose.activity.packageName}.files", uri.authority)
        }
        val local = data.localState as FinderDrag
        assertTrue(local.externalFiles)
        assertEquals(listOf(first.path, second.path), local.paths)
        assertTrue(first.exists() && second.exists())
    }

    @Test fun connectedDocumentUriAndItsMimeArePreserved() {
        val uri = Uri.parse("content://${compose.activity.packageName}.fixtures/document/root%2FPreview.pdf")
        val data = finderDragData(compose.activity, compose.activity.model,
            listOf(Entry(File("Preview.pdf"), folder = false, documentUri = uri, mimeType = "application/pdf")),
            FinderDragMode.COPY)
        assertEquals(uri, data.clipData.getItemAt(0).uri)
        assertTrue(data.clipData.description.hasMimeType("application/pdf"))
        assertEquals(View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ, data.flags)
    }

    @Test fun mixedFolderSelectionKeepsLocalDragWithoutLeakingPathsToOtherApps() {
        val item = file("Note.txt")
        val folder = File(item.parentFile, "Drag-folder-${System.nanoTime()}").apply { mkdir(); files += this }
        val data = finderDragData(compose.activity, compose.activity.model,
            listOf(Entry(item), Entry(folder)), FinderDragMode.MOVE)
        assertEquals(0, data.flags)
        assertFalse((data.localState as FinderDrag).externalFiles)
    }

    @Test fun tabletSelectionExposesLabeledDragControlAndInstructions() {
        val item = file("Note.txt")
        compose.runOnIdle {
            val model = compose.activity.model
            model.navigate(item.parentFile!!.path)
            model.updateQuery("")
        }
        compose.waitUntil(10_000) { !compose.activity.model.loading && compose.activity.model.entries.any { it.path == item.path } }
        compose.runOnIdle { compose.activity.model.select(compose.activity.model.entries.first { it.path == item.path }) }
        compose.onNodeWithTag("drag-selection").assertExists()
        compose.onNodeWithContentDescription("Hold to drag selected files").performClick()
        compose.runOnIdle { assertTrue(compose.activity.model.message!!.contains("another app")) }
        compose.runOnIdle { compose.activity.model.clearSelection() }
    }
}
