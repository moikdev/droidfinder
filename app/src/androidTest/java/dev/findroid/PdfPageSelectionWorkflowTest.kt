package dev.findroid

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.security.MessageDigest
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class PdfPageSelectionWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var folder: File
    private lateinit var pdf: File
    private lateinit var originalHash: List<Byte>

    @Before fun setup() {
        PDFBoxResourceLoader.init(compose.activity)
        folder = File(compose.activity.filesDir, "Workspace/Page-selection-${System.nanoTime()}").apply { mkdirs() }
        pdf = File(folder,"Field guide.pdf")
        PDDocument().use { document ->
            for (index in 0..2) {
                val page = PDPage(PDRectangle(600f,800f)); document.addPage(page)
                if (index < 2) PDPageContentStream(document,page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA,24f)
                    stream.newLineAtOffset(60f,720f);stream.showText(if (index == 0) "Ocean light" else "Second page")
                    stream.newLineAtOffset(0f,-60f);stream.showText("Far shore");stream.endText()
                }
            }
            document.save(pdf)
        }
        originalHash = hash()
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST);model.changeGrouping(GroupBy.NONE);model.navigate(folder.path)
        }
        compose.waitUntil(15_000) { !model.loading && model.entries.any { it.path == pdf.path } }
    }

    @After fun cleanup() {
        if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
        assertEquals(originalHash,hash())
        compose.runOnIdle { model.navigate(model.workspace.path);model.clearSelection() }
        folder.deleteRecursively()
    }

    private fun hash() = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes()).toList()
    private fun open() {
        compose.onNode(hasText("Field guide.pdf") and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5_000) { model.focused?.path == pdf.path }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pdf-page-selection-layer").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun ready() {
        compose.onNodeWithText("Select page text",substring=false).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Reading this page's text…",substring=false).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Pan and zoom",substring=false).assertExists()
    }
    private fun selected() = compose.onNodeWithTag("pdf-page-selection-layer").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
    private fun tapOcean() {
        compose.onNodeWithTag("pdf-page-selection-layer").performTouchInput { click(Offset(width * .15f,height * .09f)) }
        compose.waitUntil(5_000) { selected() == "Ocean" }
    }
    private fun copy(): String {
        compose.onNodeWithText("Copy selection",substring=false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Copied selected text",substring=false).fetchSemanticsNodes().isNotEmpty() }
        var result = ""
        compose.runOnIdle { result = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString() }
        return result
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("pdf-preview").captureToImage()
        val bitmap = android.graphics.Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels);bitmap.setPixels(pixels,0,image.width,0,0,image.width,image.height)
        File(compose.activity.cacheDir,"pdf-page-selection-qa").apply { mkdirs() }.resolve(name+".png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }

    @Test fun touchingAWordOnTheRenderedPageCopiesExactlyItsTextAndShowsNamedHandles() {
        open();ready();tapOcean()
        compose.onNodeWithContentDescription("Start of PDF text selection").assertIsDisplayed()
        compose.onNodeWithContentDescription("End of PDF text selection").assertIsDisplayed()
        assertEquals("Ocean",copy());capture("word-and-handles")
    }

    @Test fun holdingTheRenderedWordOpensSelectionWithoutASeparateModeTap() {
        open()
        compose.onNodeWithTag("pdf-page-selection-layer").performTouchInput { longClick(Offset(width * .15f,height * .09f)) }
        compose.waitUntil(15_000) { selected() == "Ocean" }
        assertEquals("Ocean",copy())
    }

    @Test fun draggingAcrossTwoRenderedLinesKeepsTheirActualLineBreak() {
        open();ready()
        compose.onNodeWithTag("pdf-page-selection-layer").performTouchInput {
            down(Offset(width * .15f,height * .09f))
            moveTo(Offset(width * .15f,height * .165f),delayMillis=200)
            up()
        }
        compose.waitUntil(5_000) { selected().contains("Far") }
        assertEquals("Ocean light\nFar",copy().trim())
        capture("two-line-selection")
    }

    @Test fun draggingTheEndHandleExtendsTheActualCopiedRange() {
        open();ready();tapOcean()
        val layer = compose.onNodeWithTag("pdf-page-selection-layer").fetchSemanticsNode().boundsInRoot
        val edge = kotlinx.coroutines.runBlocking {
            val session = PdfReadingSession(compose.activity, Entry(pdf))
            try {
                val map = pdfSelectionMap(session.text(0))
                map.edge(map.wordAt(.15f,.09f,.001f,.001f)!!, false)!!
            } finally { session.close() }
        }
        compose.onNodeWithContentDescription("End of PDF text selection").performTouchInput {
            down(center)
            moveBy(Offset((.15f-edge.right)*layer.width, (.165f-edge.bottom)*layer.height), delayMillis=250)
            up()
        }
        compose.waitUntil(5_000) { selected().contains("Far") }
        assertEquals("Ocean light\nFar",copy().trim());capture("direct-handle-drag")
    }

    @Test fun theFirstLongPressCanContinueIntoADragAfterLoadingTheTextLayer() {
        open()
        compose.onNodeWithTag("pdf-page-selection-layer").performTouchInput {
            down(Offset(width * .15f,height * .09f))
        }
        compose.waitUntil(15_000) { selected() == "Ocean" }
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-page-selection-layer").performTouchInput {
            moveTo(Offset(width * .15f,height * .165f), delayMillis=250)
            up()
        }
        compose.waitUntil(5_000) { selected().contains("Far") }
        assertEquals("Ocean light\nFar",copy().trim());capture("first-long-press-drag")
    }

    @Test fun keyboardSelectAllCopyAndEscapeOperateOnTheCurrentPdfPage() {
        open();ready();tapOcean()
        fun key(code: Int, meta: Int = 0) {
            val now = android.os.SystemClock.uptimeMillis()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta))
            instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_UP,code,0,meta))
        }
        key(KeyEvent.KEYCODE_A,KeyEvent.META_CTRL_ON)
        compose.waitUntil(5_000) { selected().contains("shore") }
        key(KeyEvent.KEYCODE_C,KeyEvent.META_CTRL_ON)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Copied selected text",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val text = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
                .getItemAt(0).coerceToText(compose.activity).toString()
            assertEquals("Ocean light\nFar shore",text.trim())
        }
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithText("Select page text",substring=false).assertExists()
        compose.onNodeWithContentDescription("End of PDF text selection").assertDoesNotExist()
    }

    @Test fun accessibilityActionsCanExtendAHandleAndClearTheSelection() {
        open();ready();tapOcean()
        val actions = compose.onNodeWithContentDescription("End of PDF text selection").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Move later" }.action()) }
        compose.waitUntil(5_000) { selected() != "Ocean" }
        compose.onNodeWithText("Clear selection",substring=false).performClick()
        compose.onNodeWithText("Copy selection",substring=false).assertIsNotEnabled()
        compose.onNodeWithContentDescription("End of PDF text selection").assertDoesNotExist()
    }

    @Test fun zoomKeepsTheSelectionAnchoredToThePageAndUsesTheSameCopyText() {
        open();ready();tapOcean()
        val before = compose.onNodeWithContentDescription("End of PDF text selection").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitForIdle()
        val after = compose.onNodeWithContentDescription("End of PDF text selection").fetchSemanticsNode().boundsInRoot
        assertTrue("The page and its handle share the zoom transform",after.width > before.width)
        compose.onNodeWithText("Pan and zoom",substring=false).performClick()
        compose.onNodeWithText("Select page text",substring=false).assertExists()
        compose.onNodeWithContentDescription("End of PDF text selection").assertDoesNotExist()
        assertEquals("Ocean",selected())
        val beforePan = compose.onNodeWithTag("pdf-page-selection-layer").fetchSemanticsNode().positionInRoot
        compose.onNodeWithTag("bitmap-viewport").performTouchInput {
            swipe(Offset(center.x,height * .5f),Offset(center.x,height * .85f),durationMillis=350)
        }
        val afterPan = compose.onNodeWithTag("pdf-page-selection-layer").fetchSemanticsNode().positionInRoot
        assertTrue("Dragging the zoomed page must move it while keeping the selection",afterPan.y > beforePan.y)
        assertEquals("Ocean",copy())
        compose.onNodeWithText("Select page text",substring=false).performClick()
        compose.onNodeWithContentDescription("End of PDF text selection").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("bitmap-viewport").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithContentDescription("End of PDF text selection").fetchSemanticsNode().boundsInRoot
        assertTrue("The selected word's handle is fully inside the viewport after panning",handle.top >= viewport.top && handle.bottom <= viewport.bottom)
        assertEquals("Ocean",copy());capture("zoomed-selection")
    }

    @Test fun switchingToReadingTextClearsTheRetainedRenderedPageSelection() {
        open();ready();tapOcean()
        compose.onNodeWithText("Pan and zoom",substring=false).performClick()
        assertEquals("Ocean",selected())
        compose.onNodeWithContentDescription("Read PDF text").performClick()
        compose.onNodeWithText("Copy selection",substring=false).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show PDF page").performClick()
        compose.onNodeWithText("Select page text",substring=false).assertExists()
        assertEquals("No page text selected",selected())
        compose.onNodeWithContentDescription("End of PDF text selection").assertDoesNotExist()
    }

    @Test fun changingPagesAndReopeningThePreviewDoNotReuseThePriorSelection() {
        open();ready();tapOcean()
        compose.onNodeWithContentDescription("Next PDF page").performClick()
        compose.onNodeWithText("Select page text",substring=false).assertExists()
        compose.onNodeWithContentDescription("Start of PDF text selection").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close Quick Look").performClick();open()
        compose.onNodeWithText("Select page text",substring=false).assertExists()
        compose.onNodeWithText("Copy selection",substring=false).assertDoesNotExist()
    }

    @Test fun anEmptyTextLayerExplainsWhyThereIsNothingToCopy() {
        open();compose.onNodeWithContentDescription("Last PDF page").performClick();ready()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("This page has no text layer to select.",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Copy selection",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Select all page text",substring=false).assertIsNotEnabled()
    }
}
