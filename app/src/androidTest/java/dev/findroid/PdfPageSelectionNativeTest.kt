package dev.findroid

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfPageSelectionNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun file() = File(context.cacheDir, "pdf-page-selection-${System.nanoTime()}.pdf")
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun selectionBoxesFollowActualRenderedGlyphsAcrossPageAndTextRotationsAndCropBoxes() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val file = file()
        PDDocument().use { pdf ->
            for (cropped in listOf(false, true)) for (rotation in listOf(0, 90, 180, 270)) for (textRotation in listOf(0, 90, 180, 270)) {
                val page = PDPage(PDRectangle(600f, 800f)).apply {
                    this.rotation = rotation
                    if (cropped) cropBox = PDRectangle(20f, 30f, 560f, 720f)
                }
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 24f)
                    content.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(textRotation.toDouble()), 300f, 400f))
                    content.showText("Ocean light"); content.endText()
                }
            }
            pdf.save(file)
        }
        val original = hash(file)
        val session = PdfReadingSession(context, Entry(file))
        try {
            for (page in 0 until session.pageCount()) {
                val text = session.text(page)
                val index = pdfSelectionMap(text)
                val first = index.glyphs.first { index.text.substring(it.range.start, it.range.end).startsWith("O") }
                val box = first.bounds
                val selected = index.wordAt((box.left + box.right) / 2, (box.top + box.bottom) / 2, .001f, .001f)!!
                assertEquals("page $page", "Ocean", index.selectedText(selected))
                val bitmap = session.render(page, thumbnail = true)
                var selectedInk = 0
                for (rect in index.boxes(selected)) {
                    assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= 1 && rect.bottom <= 1)
                    val left = (rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val top = (rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val right = (rect.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                    val bottom = (rect.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                    for (y in top until bottom) for (x in left until right) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) < 180 && Color.green(pixel) < 180 && Color.blue(pixel) < 180) selectedInk++
                    }
                }
                assertTrue("page $page has rendered ink under its selected word", selectedInk > 20)
            }
            assertEquals(original, hash(file))
        } finally { session.close(); file.delete() }
    }

    @Test fun aPageWithoutATextLayerCannotCreateAnInventedSelection() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val file = file()
        PDDocument().use { pdf -> pdf.addPage(PDPage()); pdf.save(file) }
        val session = PdfReadingSession(context, Entry(file))
        try {
            val index = pdfSelectionMap(session.text(0))
            assertTrue(index.glyphs.isEmpty()); assertNull(index.all()); assertNull(index.wordAt(.5f,.5f,.05f,.05f))
        } finally { session.close(); file.delete() }
    }

    @Test fun selectionRespectsTheDocumentsExistingTextExtractionPermission() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val file = file()
        PDDocument().use { pdf ->
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf,page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 16f); stream.newLineAtOffset(60f,700f)
                stream.showText("Protected synthetic text"); stream.endText()
            }
            val permission = AccessPermission().apply { setCanExtractContent(false) }
            pdf.protect(StandardProtectionPolicy("synthetic-owner-only", "", permission).apply { encryptionKeyLength = 128 })
            pdf.save(file)
        }
        val original = hash(file)
        val session = PdfReadingSession(context, Entry(file))
        try {
            try { session.text(0); fail("The extraction restriction must remain enforced") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("does not allow text extraction")) }
            assertEquals(original, hash(file))
        } finally { session.close(); file.delete() }
    }
}
