package dev.droidfinder

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.provider.DocumentsContract
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PeekWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: File

    @Before
    fun setup() {
        fixture =
            File(compose.activity.filesDir, "Workspace/Peek-${System.nanoTime()}").apply {
                mkdirs()
            }
        val image =
            Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(95, 148, 165))
            }
        File(fixture, "A-photo.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 500, index + 1).create())
                page.canvas.drawText(
                    "Peek PDF page ${index + 1}",
                    30f,
                    60f,
                    Paint().apply { textSize = 24f },
                )
                pdf.finishPage(page)
            }
            File(fixture, "B-guide.pdf").outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("peek-sample.mp4")
            .use { input ->
                File(fixture, "C-film.mp4").outputStream().use(input::copyTo)
            }
        File(fixture, "D-notes.md").writeText("Notes shown inside Peek")
        compose.runOnIdle {
            val model = compose.activity.model
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME) model.changeSort(SortBy.NAME)
            if (!model.ascending) model.changeSort(SortBy.NAME)
            model.clearSelection()
            model.navigate(fixture.path)
        }
        compose.waitUntil(10_000) {
            !compose.activity.model.loading && compose.activity.model.entries.size == 4
        }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.runOnIdle {
            compose.activity.model.updateQuery("")
            compose.activity.model.navigate(compose.activity.model.workspace.path)
            compose.activity.model.clearSelection()
        }
        fixture.deleteRecursively()
    }

    private fun select(name: String) {
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(5000) { compose.activity.model.focused?.name == name }
        compose.waitForIdle()
    }

    private fun hardwareKey(code: Int) {
        // Real window dispatch is essential: native video surfaces sit outside Compose's key tree.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun openWithSpace() {
        hardwareKey(AndroidKeyEvent.KEYCODE_SPACE)
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun spaceOpensPhotoPeekAndClosesItFromTheDialogWindow() {
        select("A-photo.png")
        openWithSpace()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithContentDescription("Preview of A-photo.png")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Zoom in").assertExists()
        hardwareKey(AndroidKeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
        compose.runOnIdle { assertEquals("A-photo.png", compose.activity.model.focused?.name) }
    }

    @Test
    fun arrowsBrowsePhotoPdfVideoAndTextWithoutOpeningOtherApps() {
        select("A-photo.png")
        openWithSpace()
        hardwareKey(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Page 1 of 2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Next PDF page").performClick()
        compose.onNodeWithText("Page 2 of 2").assertExists()
        hardwareKey(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasContentDescription("Play video") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.onNodeWithContentDescription("Pause video").assertExists().performClick()
        hardwareKey(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Notes shown inside Peek").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("video-preview").assertDoesNotExist()
        hardwareKey(AndroidKeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
        compose.runOnIdle { assertEquals("D-notes.md", compose.activity.model.focused?.name) }
    }

    @Test
    fun selectingFilesKeepsTheListStationaryAndTouchCanOpenPeek() {
        val before = compose.onNodeWithText("Name").fetchSemanticsNode().boundsInRoot.top
        select("A-photo.png")
        val after = compose.onNodeWithText("Name").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Selection must not insert another toolbar row", before, after, .5f)
        compose.onNodeWithContentDescription("Peek selected file").performClick()
        compose.onNodeWithTag("peek-window").assertExists()
    }

    @Test
    fun connectedProviderVideoPlaysDirectlyAndSpaceClosesAfterPlayback() {
        val provider =
            File(compose.activity.filesDir, "ProviderFixtures/Peek-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(fixture, "C-film.mp4").copyTo(File(provider, "Provider-film.mp4"))
        val uri =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${provider.name}",
            )
        try {
            compose.runOnIdle { compose.activity.model.connectLocation(uri, false) }
            compose.waitUntil(10_000) {
                !compose.activity.model.loading &&
                    compose.activity.model.entries.any { it.name == "Provider-film.mp4" }
            }
            select("Provider-film.mp4")
            openWithSpace()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasContentDescription("Play video") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription("Play video").performClick()
            compose.onNodeWithContentDescription("Pause video").assertExists()
            hardwareKey(AndroidKeyEvent.KEYCODE_SPACE)
            compose.onNodeWithTag("peek-window").assertDoesNotExist()
        } finally {
            if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription("Close Quick Look").performClick()
            compose.runOnIdle {
                val model = compose.activity.model
                model.navigate(fixture.path)
                model.connectedLocations
                    .firstOrNull { it.name == provider.name }
                    ?.let { model.disconnectLocation(it.uri) }
            }
            provider.deleteRecursively()
        }
    }

    @Test
    fun selectingASearchResultReturnsSpaceToPeek() {
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("A-photo")
        compose.waitUntil(10_000) {
            !compose.activity.model.loading && compose.activity.model.entries.size == 1
        }
        compose.onNodeWithText("A-photo.png").performTouchInput { click() }
        compose.waitUntil(5000) { compose.activity.model.focused?.name == "A-photo.png" }
        openWithSpace()
        compose.onNodeWithTag("peek-window").assertExists()
        hardwareKey(AndroidKeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
        compose.runOnIdle { assertEquals("A-photo", compose.activity.model.query) }
    }

    @Test
    fun spaceInSearchRemainsTextInput() {
        select("A-photo.png")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("A")
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
        compose.runOnIdle { assertEquals("A ", compose.activity.model.query) }
    }
}
