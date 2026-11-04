package dev.findroid

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class FileComparisonWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val model get()=compose.activity.model
    private lateinit var folder: File
    private lateinit var hashes: Map<String,List<Byte>>
    @Before fun setup() {
        folder=File(model.workspace,"CompareQA-${System.nanoTime()}").apply { check(mkdirs()) }
        File(folder,"Before.txt").writeText("Coastal field notes\nOld route\nShared horizon 日本語 🌊\nPack a light\nEnd\n")
        File(folder,"After.txt").writeText("Coastal field notes\nNew route\nShared horizon 日本語 🌊\nPack a camera\nEnd\n")
        photo("Morning.png",false);photo("Evening.png",true);hashes=currentHashes()
        compose.runOnIdle {
            if(model.dualPane)model.toggleDualPane()
            if(model.previewVisible)model.togglePreview()
            model.changeMode(ViewMode.LIST);model.changeGrouping(GroupBy.NONE)
            model.clearSelection();model.navigate(folder.path)
        }
        compose.waitUntil(15_000){!model.loading&&model.entries.size==4}
    }
    private fun photo(name: String, evening: Boolean) {
        val bitmap=Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val paint=Paint()
        canvas.drawColor(if(evening)Color.rgb(235,181,151) else Color.rgb(168,212,226))
        paint.color=if(evening)Color.rgb(45,93,108) else Color.rgb(38,127,150);canvas.drawRect(0f,280f,900f,600f,paint)
        paint.color=Color.rgb(245,218,150);canvas.drawCircle(if(evening)650f else 250f,180f,55f,paint)
        paint.color=Color.rgb(36,63,71);canvas.drawPath(android.graphics.Path().apply { moveTo(0f,520f);lineTo(360f,310f);lineTo(540f,600f);lineTo(0f,600f);close() },paint)
        File(folder,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun currentHashes()=folder.listFiles()!!.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    @After fun cleanup() {
        close();assertEquals(hashes,currentHashes())
        compose.runOnIdle { model.navigate(model.workspace.path);model.clearSelection() }
        check(folder.deleteTreeWithoutLinks())
    }
    private fun select(vararg names: String) = compose.runOnIdle {
        model.clearSelection();names.forEach { name -> model.select(model.entries.first { it.name==name },multiple=true) }
    }
    private fun openMenu() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Compare files…",substring=false).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("file-comparison").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun ready(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun close() {
        if(compose.onAllNodesWithText("Close comparison limits",substring=false).fetchSemanticsNodes().isNotEmpty())compose.onNodeWithText("Close comparison limits",substring=false).performClick()
        if(compose.onAllNodesWithContentDescription("Close file comparison").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Close file comparison").performClick()
    }
    private fun click(text: String)=compose.onNodeWithText(text,substring=false).performScrollTo().performClick()
    private fun capture(name: String) {
        val output=File(compose.activity.cacheDir,"file-comparison-qa").apply { mkdirs() }
        File(output,"$name.png").outputStream().use { compose.onNodeWithTag("file-comparison").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun selectionMenuNavigatesDifferencesCopiesActualReportAndSwaps() {
        select("Before.txt","After.txt");openMenu();ready("text-differences")
        compose.onNodeWithText("2 difference groups · 2 removed · 2 added",substring=false).performScrollTo().assertIsDisplayed()
        click("Next difference");compose.onNodeWithText("2 of 2",substring=false).performScrollTo().assertIsDisplayed()
        click("Previous difference");click("Copy differences")
        compose.waitUntil(5000){compose.onAllNodesWithText("Copied comparison report",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {
            val text=compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString()
            assertTrue(text.contains("Old route")&&text.contains("New route"));assertFalse(text.contains("Shared horizon"))
        }
        capture("text-differences")
        click("Swap sides");ready("text-differences")
        compose.onNodeWithText("Left · Before.txt",substring=false).performScrollTo().assertIsDisplayed()
        click("Comparison limits…");compose.onNodeWithText("File comparison limits",substring=false).assertIsDisplayed()
    }
    @Test fun imageReviewLinksZoomPanFitAndSwapWithBothImagesVisible() {
        select("Morning.png","Evening.png");openMenu();ready("image-comparison")
        compose.onNodeWithContentDescription("Left image").assertIsDisplayed();compose.onNodeWithContentDescription("Right image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom both images in").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Pan both images right").performScrollTo().performClick()
        compose.onNodeWithText("Linked zoom · 2× · Drag either image to pan both",substring=false).performScrollTo().assertIsDisplayed()
        capture("linked-images")
        click("Fit both images")
        repeat(3) { compose.onNodeWithContentDescription("Zoom both images in").performScrollTo().performClick() }
        val pane=compose.onNodeWithTag("image-comparison")
        val before=pane.captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false)
        pane.performTouchInput {
            val start=Offset(width*.25f-32f,height*.60f)
            swipe(start,start+Offset(64f,0f),300)
        }
        val after=pane.captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false)
        try {
            fun shift(side: Int): Int {
                assertEquals(before.width,after.width);assertEquals(before.height,after.height)
                val half=before.width/2;var best=0;var bestScore=Double.MAX_VALUE
                for(delta in -180..180) {
                    var error=0L;var count=0
                    for(fraction in listOf(.65,.80,.95)) {
                        val y=(before.height*fraction).toInt().coerceAtMost(before.height-1)
                        for(x in 16 until half-16 step 3)if(x+delta in 16 until half-16) {
                            val a=before.getPixel(side*half+x,y);val b=after.getPixel(side*half+x+delta,y)
                            error+=kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b));count++
                        }
                    }
                    val score=error.toDouble()/count
                    if(score<bestScore) { bestScore=score;best=delta }
                }
                return best
            }
            val left=shift(0);val right=shift(1)
            assertTrue("64 px drag must track the finger at 4×; measured $left px",left in 20..80)
            assertTrue("Both previews must follow the same drag; measured $left / $right px",right in 20..80&&kotlin.math.abs(left-right)<=3)
            File(compose.activity.cacheDir,"file-comparison-qa/gesture-proof.json").writeText("{\"zoom\":4,\"dragPixels\":64,\"leftRasterShiftPixels\":$left,\"rightRasterShiftPixels\":$right,\"bothTrackFinger\":true}\n")
        } finally { before.recycle();after.recycle() }
        click("Fit both images");click("Swap sides");ready("image-comparison")
        compose.onNodeWithText("Linked zoom · 1× · Drag either image to pan both",substring=false).performScrollTo().assertIsDisplayed()
    }
    @Test fun folderComparisonOpensNestedFilePairReadOnly() {
        val left=File(folder,"Left/Nested").apply { mkdirs() };val right=File(folder,"Right/Nested").apply { mkdirs() }
        File(left,"Route.txt").writeText("old\n");File(right,"Route.txt").writeText("new\n")
        try {
            compose.runOnIdle { model.navigate(left.parent!!);model.toggleDualPane();model.secondaryPane.navigate(right.parent!!) }
            compose.waitUntil(15_000){!model.loading&&!model.secondaryPane.loading}
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.onNodeWithText("Compare folders…",substring=false).performScrollTo().performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithText("Nested/Route.txt",substring=false).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Nested/Route.txt",substring=false).performScrollTo().performClick()
            compose.onNodeWithText("Compare selected files…",substring=false).performScrollTo().performClick();ready("text-differences")
            compose.onNodeWithText("1 difference group · 1 removed · 1 added",substring=false).performScrollTo().assertIsDisplayed()
            capture("nested-file-comparison");assertEquals("old\n",File(left,"Route.txt").readText());assertEquals("new\n",File(right,"Route.txt").readText())
        } finally { close();check(File(folder,"Left").deleteTreeWithoutLinks());check(File(folder,"Right").deleteTreeWithoutLinks()) }
    }
    @Test fun unsupportedPairExplainsAndDoesNotShowFalseResults() {
        select("Morning.png","Before.txt");openMenu()
        compose.waitUntil(15_000){compose.onAllNodesWithText("Choose two plain text/code files",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("text-differences").assertDoesNotExist();compose.onNodeWithTag("image-comparison").assertDoesNotExist()
        capture("unsupported-pair")
    }
    @Test fun providerReadCanStopRetryAndReopenFreshBytes() {
        val provider=File(compose.activity.filesDir,"ProviderFixtures/Compare-${System.nanoTime()}").apply { mkdirs() }
        val gateFile=File(provider,"Gate.txt").apply { writeText("new\n") };File(provider,"Before.txt").writeText("old\n")
        val gate=CountDownLatch(1);val opened=CountDownLatch(1)
        FixtureDocumentsProvider.transferGate=gate;FixtureDocumentsProvider.gateOpened=opened
        val uri=DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures","root/${provider.name}")
        try {
            compose.runOnIdle { model.connectLocation(uri,false) }
            compose.waitUntil(15_000){!model.loading&&model.entries.size==2}
            select("Before.txt","Gate.txt");openMenu();assertTrue(opened.await(5,TimeUnit.SECONDS))
            click("Stop comparison")
            compose.onNodeWithText("Comparison stopped. Retry with Compare again.",substring=false).performScrollTo().assertIsDisplayed();capture("provider-stopped")
            gate.countDown();click("Compare again");ready("text-differences")
            gateFile.writeText("old\n");click("Compare again");ready("text-differences")
            compose.onNodeWithText("No text differences",substring=false).performScrollTo().assertIsDisplayed()
        } finally {
            gate.countDown();FixtureDocumentsProvider.transferGate=null;FixtureDocumentsProvider.gateOpened=null;close()
            compose.runOnIdle { model.navigate(folder.path);model.connectedLocations.firstOrNull{it.name==provider.name}?.let{model.disconnectLocation(it.uri)} }
            check(provider.deleteTreeWithoutLinks())
        }
    }
}
