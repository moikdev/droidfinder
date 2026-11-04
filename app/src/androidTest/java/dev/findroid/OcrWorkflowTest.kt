package dev.findroid

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.security.MessageDigest
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class OcrWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var fixture: OcrFixture
    private lateinit var folder: File
    private lateinit var hashes: Map<String,List<Byte>>
    @Before fun setup() {
        fixture = OcrFixture(compose.activity)
        folder = File(compose.activity.filesDir,"Workspace/OCR-${System.nanoTime()}").apply { mkdirs() }
        val bitmap = ocrTestImage()
        try {
            File(folder,"Scan.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            PDFBoxResourceLoader.init(compose.activity)
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle(1000f,400f));pdf.addPage(page)
                PDPageContentStream(pdf,page).use { it.drawImage(LosslessFactory.createFromImage(pdf,bitmap),0f,0f,1000f,400f) }
                pdf.save(File(folder,"Scanned page.pdf"))
            }
        } finally { bitmap.recycle() }
        hashes = currentHashes()
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST);model.changeGrouping(GroupBy.NONE);model.navigate(folder.path)
        }
        compose.waitUntil(15_000) { !model.loading && model.entries.size == 2 }
    }
    @After fun cleanup() {
        if (compose.onAllNodesWithText("Close OCR",substring=false).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Close OCR",substring=false).performClick()
        compose.waitUntil(8000) { ocrWorkerPid(compose.activity) == null }
        if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close Quick Look").performClick()
        assertEquals(hashes,currentHashes());fixture.close()
        compose.runOnIdle { model.navigate(model.workspace.path);model.clearSelection() }
        check(folder.deleteTreeWithoutLinks())
    }
    private fun currentHashes() = folder.listFiles()!!.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    private fun open(name: String, action: String) {
        compose.onNode(hasText(name) and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5000) { model.focused?.name == name }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(action,substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(action,substring=false).performClick()
        compose.onNodeWithTag("ocr-dialog").assertExists()
    }
    private fun waitForModel(name: String, predicate: () -> Boolean) {
        try { compose.waitUntil(10_000, predicate) }
        catch (error: Throwable) {
            val diagnostics = File(compose.activity.cacheDir,"ocr-qa-fail").apply { mkdirs() }
            runCatching { diagnostics.resolve("PRIVATE-$name-semantics.txt").writeText(compose.onNodeWithTag("ocr-dialog").printToString()) }
            runCatching { diagnostics.resolve("PRIVATE-$name-models.txt").writeText(OcrPolicy.languages.joinToString("\n") { fixture.store.state(it).toString() }) }
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -s FindroidOCR:D '*:S'").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        diagnostics.resolve("PRIVATE-$name-review.txt").writeBytes(input.readBytes())
                    }
                }
            }
            runCatching { capture("PRIVATE-$name", diagnostics) }
            throw error
        }
    }
    private fun recognize() {
        waitForModel("recognize-ready") { compose.onAllNodes(hasText("Recognize this image or page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Recognize this image or page",substring=false).performScrollTo().performClick()
        compose.waitUntil(40_000) { compose.onAllNodesWithTag("ocr-text-content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ocr-text-content").assertTextContains("Ocean light",substring=true)
        compose.onNodeWithTag("ocr-text-content").assertTextContains("Far shore",substring=true)
    }
    private fun capture(name: String, output: File = File(compose.activity.cacheDir,"ocr-qa").apply { mkdirs() }) {
        val image = compose.onNodeWithTag("ocr-dialog").captureToImage()
        val bitmap = Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(image.width*image.height);image.readPixels(pixels);bitmap.setPixels(pixels,0,image.width,0,0,image.width,image.height)
            output.resolve(name+".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { bitmap.recycle() }
    }
    @Test fun openingOcrOffersExplicitModelChoiceAndStartsNoRecognition() {
        open("Scan.png","Recognize image text…")
        waitForModel("missing-model") { compose.onAllNodesWithText("Download language",substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Recognize this image or page",substring=false).assertIsNotEnabled()
        assertNull(ocrWorkerPid(compose.activity));assertFalse(fixture.store.state(OcrPolicy.language("eng")).verified)
        capture("explicit-language-choice")
    }
    @Test fun imageRecognitionCopiesActualTextFindsMatchesAndClearsOnClose() {
        fixture.install();open("Scan.png","Recognize image text…");recognize()
        val recognized = compose.onNodeWithTag("ocr-text-content").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        compose.onNodeWithText("Copy recognized text",substring=false).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Copied recognized text",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(recognized,compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString()) }
        compose.onNodeWithTag("ocr-search").performScrollTo().performTextInput("shore")
        compose.waitUntil(5000) { compose.onAllNodesWithText("1 of 1 matches",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithTag("ocr-text-content").performScrollTo().assertIsDisplayed()
        capture("image-recognized-text")
        compose.onNodeWithText("Close OCR",substring=false).performClick()
        compose.onNodeWithText("Recognize image text…",substring=false).performClick()
        compose.onNodeWithTag("ocr-text-content").assertDoesNotExist()
        assertNull(ocrWorkerPid(compose.activity))
    }
    @Test fun scannedPdfWithoutTextLayerCanBeRecognizedReadOnly() {
        fixture.install();open("Scanned page.pdf","Recognize page text…");recognize()
        compose.onNodeWithTag("ocr-text-content").performScrollTo().assertIsDisplayed()
        capture("scanned-pdf-recognized-text")
        assertEquals(hashes,currentHashes())
    }
    @Test fun repeatedOpeningAndLanguageSwitchingUsesOnlyTheSelectedModel() {
        fixture.install();open("Scan.png","Recognize image text…")
        repeat(4) {
            waitForModel("recognize-ready") { compose.onAllNodes(hasText("Recognize this image or page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Japanese",substring=false).performClick()
            waitForModel("missing-model") { compose.onAllNodesWithText("Download language",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recognize this image or page",substring=false).assertIsNotEnabled()
            compose.onNodeWithText("English",substring=false).performClick()
            waitForModel("recognize-ready") { compose.onAllNodes(hasText("Recognize this image or page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Close OCR",substring=false).performClick()
            compose.onNodeWithText("Recognize image text…",substring=false).performClick()
        }
        assertNull(ocrWorkerPid(compose.activity))
        assertFalse(fixture.store.state(OcrPolicy.language("jpn")).verified)
    }
    @Test fun removingLanguageRequiresConfirmationAndDisablesRecognition() {
        fixture.install();open("Scan.png","Recognize image text…")
        waitForModel("recognize-ready") { compose.onAllNodesWithText("Remove language…",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove language…",substring=false).performClick()
        compose.onNodeWithText("Keep language",substring=false).performClick()
        assertTrue(fixture.store.state(OcrPolicy.language("eng")).verified)
        compose.onNodeWithText("Remove language…",substring=false).performClick()
        compose.onNodeWithText("Remove language",substring=false).performClick()
        waitForModel("missing-model") { compose.onAllNodesWithText("Download language",substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Recognize this image or page",substring=false).assertIsNotEnabled()
        assertFalse(fixture.store.state(OcrPolicy.language("eng")).verified)
    }
    @Test fun incompleteLanguageBytesAreReviewedBeforeDiscard() {
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.install(OcrPolicy.language("eng"), java.io.ByteArrayInputStream(byteArrayOf(1,2,3)))
        }
        val before = fixture.store.incomplete()
        open("Scan.png","Recognize image text…")
        waitForModel("missing-model") { compose.onAllNodesWithText("Review incomplete downloads (1)…",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review incomplete downloads (1)…",substring=false).performClick()
        compose.onNodeWithText("Keep incomplete downloads",substring=false).performClick()
        assertEquals(before,fixture.store.incomplete())
        compose.onNodeWithText("Review incomplete downloads (1)…",substring=false).performClick()
        compose.onNodeWithText("Discard incomplete downloads",substring=false).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Reviewed incomplete downloads discarded.",substring=false).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(fixture.store.incomplete().isEmpty())
    }
    @Test fun failedStorageReviewCanBeRetriedWithoutChangingTheModel() {
        fixture.install()
        fixture.store.incomplete()
        val unexpected = File(compose.activity.noBackupFilesDir,"ocr/incomplete/OCR-test-unexpected.txt").apply { writeText("Synthetic unexpected bytes") }
        try {
            open("Scan.png","Recognize image text…")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry language review",substring=false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recognize this image or page",substring=false).assertIsNotEnabled()
            assertEquals("Synthetic unexpected bytes",unexpected.readText())
            assertTrue(fixture.store.state(OcrPolicy.language("eng")).verified)
            check(unexpected.delete())
            compose.onNodeWithText("Retry language review",substring=false).performClick()
            waitForModel("recognize-ready") { compose.onAllNodes(hasText("Recognize this image or page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(fixture.store.state(OcrPolicy.language("eng")).verified)
        } finally { if (unexpected.exists()) check(unexpected.delete()) }
    }
}
