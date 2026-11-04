package dev.findroid

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class OfficeWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val model get()=compose.activity.model
    private lateinit var folder: File
    private lateinit var hashes: Map<String,List<Byte>>
    @Before fun setup() {
        folder=File(compose.activity.filesDir,"Workspace/Office-${System.nanoTime()}").apply { check(mkdirs()) }
        OfficeFixtures.documents(folder);hashes=currentHashes()
        compose.runOnIdle {
            if(model.dualPane)model.toggleDualPane()
            model.changeMode(ViewMode.LIST);model.changeGrouping(GroupBy.NONE)
            model.clearSelection();model.navigate(folder.path)
        }
        compose.waitUntil(15_000) { !model.loading&&model.entries.size==3 }
    }
    @After fun cleanup() {
        if(compose.onAllNodesWithText("Close preview limits",substring=false).fetchSemanticsNodes().isNotEmpty())compose.onNodeWithText("Close preview limits",substring=false).performClick()
        close();assertEquals(hashes,currentHashes())
        compose.runOnIdle { model.navigate(model.workspace.path);model.clearSelection() }
        check(folder.deleteTreeWithoutLinks())
    }
    private fun currentHashes()=folder.listFiles()!!.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    private fun open(name: String, ready: Boolean=true) {
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5000) { model.focused?.name==name }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("office-preview").fetchSemanticsNodes().isNotEmpty() }
        if(ready)compose.waitUntil(15_000) { compose.onAllNodes(hasText("Copy loaded section") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun close() {
        if(compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Close Quick Look").performClick()
    }
    private fun hideKeyboard() {
        compose.runOnIdle {
            android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }?.let { view ->
                view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
            }
        }
        compose.waitUntil(5000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
                .none { it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true }
        }
    }
    private fun clipboard(): String {
        var value=""
        compose.runOnIdle { value=compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString() }
        return value
    }
    private fun copy() {
        compose.onNodeWithText("Copy loaded section",substring=false).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("office-status").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        val image=compose.onNodeWithTag("office-preview").captureToImage()
        val bitmap=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
        try {
            val pixels=IntArray(image.width*image.height);image.readPixels(pixels);bitmap.setPixels(pixels,0,image.width,0,0,image.width,image.height)
            val output=File(compose.activity.cacheDir,"office-qa").apply { mkdirs() }
            output.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { bitmap.recycle() }
    }
    @Test fun wordPreviewFindsCopiesUnicodeAndExplainsReadOnlyLimits() {
        open("Notes.docx")
        compose.onNodeWithText("Ocean light",substring=false).assertIsDisplayed()
        copy();assertEquals("Ocean light\nFar shore 日本語 🌊\n",clipboard())
        compose.onNodeWithTag("office-search").performScrollTo().performTextInput("shore");hideKeyboard()
        compose.onNodeWithText("1 of 1 matching rows",substring=false).performScrollTo().assertIsDisplayed()
        capture("word-find-and-copy")
        compose.onNodeWithText("Preview limits…",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Office preview limits",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Close preview limits",substring=false).performClick()
    }
    @Test fun workbookUsesStoredValuesAndCopiesOnlyTheSelectedWorksheet() {
        open("Budget.xlsx")
        compose.onNodeWithText("1 of 2 · Field notes",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stored values; formulas aren't recalculated.",substring=false).performScrollTo().assertIsDisplayed()
        copy();assertEquals("Row\tA\tB\n1\tOcean light\tFar shore\n2\t2\t3",clipboard())
        capture("workbook-stored-values")
        compose.onNodeWithText("Next section",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("2 of 2 · Morning",substring=false).performScrollTo().assertIsDisplayed()
        copy();assertEquals("Row\tA\n1\tMorning sea",clipboard())
        compose.onNodeWithText("Next section",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Previous section",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("1 of 2 · Field notes",substring=false).performScrollTo().assertIsDisplayed()
    }
    @Test fun slideNavigationUpdatesFindAndReopeningClearsTheQuery() {
        open("Journey.pptx")
        compose.onNodeWithTag("office-search").performScrollTo().performTextInput("shore");hideKeyboard()
        compose.onNodeWithText("No matching rows in loaded section",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Next section",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("2 of 2 · Slide 2",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 of 1 matching rows",substring=false).performScrollTo().assertIsDisplayed()
        copy();assertEquals("Far shore\n",clipboard());capture("slide-section-and-find")
        close();open("Journey.pptx")
        assertEquals("",compose.onNodeWithTag("office-search").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithText("1 of 2 · Slide 1",substring=false).performScrollTo().assertIsDisplayed()
    }
    @Test fun providerMimeOnlyDocumentAndBlockedPreparationCanStopAndRetry() {
        val provider=File(compose.activity.filesDir,"ProviderFixtures/Office-${System.nanoTime()}").apply { check(mkdirs()) }
        val file=File(folder,"Notes.docx").copyTo(File(provider,"No extension"))
        File(folder,"Notes.docx").copyTo(File(provider,"Gate.docx"))
        val before=file.readBytes()
        FixtureDocumentsProvider.mimeOverrides=mapOf(file.canonicalPath to "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val gate=CountDownLatch(1);val opened=CountDownLatch(1)
        FixtureDocumentsProvider.transferGate=gate;FixtureDocumentsProvider.gateOpened=opened
        val previewWasVisible=model.previewVisible
        compose.runOnIdle { if(model.previewVisible)model.togglePreview() }
        val uri=DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures","root/${provider.name}")
        try {
            compose.runOnIdle { model.connectLocation(uri,false) }
            compose.waitUntil(15_000) { !model.loading&&model.entries.size==2 }
            open("No extension");copy();assertTrue(clipboard().contains("日本語 🌊"));close()
            open("Gate.docx",false);assertTrue(opened.await(5,TimeUnit.SECONDS))
            val partial=File(model.focused!!.file.parentFile,"preview.tmp")
            compose.waitUntil(5000) { partial.exists() }
            compose.onNodeWithText("Stop Office preview",substring=false).performScrollTo().performClick()
            compose.onNodeWithText("Office preview stopped.",substring=false).performScrollTo().assertIsDisplayed()
            compose.waitUntil(5000) { !partial.exists() };capture("provider-stopped")
            gate.countDown()
            compose.onNodeWithText("Retry Office preview",substring=false).performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Copy loaded section") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            copy();assertTrue(clipboard().contains("Ocean light"));assertArrayEquals(before,file.readBytes())
        } finally {
            gate.countDown();FixtureDocumentsProvider.transferGate=null;FixtureDocumentsProvider.gateOpened=null
            FixtureDocumentsProvider.mimeOverrides=emptyMap();FixtureDocumentsProvider.streamCancellation=null;close()
            compose.runOnIdle {
                model.navigate(folder.path)
                model.connectedLocations.firstOrNull { it.name==provider.name }?.let { model.disconnectLocation(it.uri) }
                if(model.previewVisible!=previewWasVisible)model.togglePreview()
            }
            check(provider.deleteTreeWithoutLinks())
        }
    }
}
