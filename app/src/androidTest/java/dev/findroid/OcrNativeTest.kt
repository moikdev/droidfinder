package dev.findroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class OcrNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: OcrFixture
    private lateinit var directory: File
    @Before fun setup() {
        fixture = OcrFixture(context)
        directory = File(context.cacheDir,"ocr-native-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun cleanup() {
        runBlocking { withTimeout(8000) { while (ocrWorkerPid(context) != null) delay(50) } }
        fixture.close()
        check(directory.deleteTreeWithoutLinks())
    }
    private fun image(width: Int = 1000, height: Int = 400, dense: Boolean = false): File {
        val bitmap = ocrTestImage(width,height,dense)
        return File(directory,"raster-${System.nanoTime()}.png").also {
            try { it.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) } }
            finally { bitmap.recycle() }
        }
    }
    @Test fun realOfflineEngineReadsRasterAndKeepsSourceBytes() = runBlocking {
        fixture.install()
        val file = image();val before = file.readBytes();val explorerPid = android.os.Process.myPid()
        val result = recognizeOcrRaster(context,OcrPolicy.language("eng"),file)
        assertTrue(result.text.contains("Ocean light"));assertTrue(result.text.contains("Far shore"))
        assertFalse(result.limited);assertTrue(result.confidence in 1..100)
        assertArrayEquals(before,file.readBytes());assertEquals(explorerPid,android.os.Process.myPid())
    }
    @Test fun reviewedJapaneseModelRecognizesLocalPrintedText() = runBlocking {
        val language = OcrPolicy.language("jpn")
        fixture.install(language)
        val bitmap = Bitmap.createBitmap(1000,400,Bitmap.Config.ARGB_8888)
        val file = File(directory,"Japanese scan.png")
        try {
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f; typeface = Typeface.create(Typeface.SANS_SERIF,Typeface.NORMAL) }
            Canvas(bitmap).apply { drawText("日本語の文章",70f,130f,paint);drawText("東京の朝",70f,240f,paint) }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
        val before = file.readBytes()
        val result = recognizeOcrRaster(context,language,file)
        val compact = result.text.filterNot { it.isWhitespace() }
        assertTrue("Japanese model must recognize the synthetic printed passage",compact.contains("日本語"))
        assertTrue("Japanese model must recognize the second synthetic line",compact.contains("東京"))
        assertArrayEquals(before,file.readBytes())
    }
    @Test fun cancellationAfterNativeImageIsReadyStopsOnlyPrivateWorker() = runBlocking {
        fixture.install()
        val file = image(1920,2048,true);val before = file.readBytes();val explorerPid = android.os.Process.myPid()
        val started = CompletableDeferred<Unit>()
        val task = async { recognizeOcrRaster(context,OcrPolicy.language("eng"),file) { started.complete(Unit) } }
        withTimeout(15_000) { started.await() }
        assertTrue(task.isActive)
        val worker = ocrWorkerPid(context);assertNotNull(worker);assertNotEquals(explorerPid,worker)
        task.cancelAndJoin()
        withTimeout(5000) { while (ocrWorkerPid(context) != null) delay(25) }
        assertArrayEquals(before,file.readBytes());assertEquals(explorerPid,android.os.Process.myPid())
    }
    @Test fun workerRefusesUninstalledLanguageAndOverBudgetRaster() = runBlocking {
        val file = image()
        try { recognizeOcrRaster(context,OcrPolicy.language("eng"),file);fail("Missing language must be refused") }
        catch (expected: java.io.IOException) { assertTrue(expected.message.orEmpty().contains("language")) }
        withTimeout(5000) { while (ocrWorkerPid(context) != null) delay(25) }
        fixture.install()
        val wide = image(2500,400)
        try { recognizeOcrRaster(context,OcrPolicy.language("eng"),wide);fail("Oversized raster must be refused before recognition") }
        catch (expected: java.io.IOException) { assertTrue(expected.message.orEmpty().contains("pixel budget")) }
    }
    @Test fun pdfOcrKeepsExistingExtractionRestriction() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val file = File(directory,"Restricted.pdf")
        PDDocument().use { pdf ->
            pdf.addPage(PDPage())
            pdf.protect(StandardProtectionPolicy("synthetic-ocr-owner","",AccessPermission().apply { setCanExtractContent(false) }).apply { encryptionKeyLength = 128 })
            pdf.save(file)
        }
        val before = file.readBytes();val session = PdfReadingSession(context,Entry(file))
        try {
            try { session.ocrRaster(0).recycle();fail("OCR must respect extraction restrictions") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("does not allow text extraction")) }
            assertArrayEquals(before,file.readBytes())
        } finally { session.close() }
    }
}
