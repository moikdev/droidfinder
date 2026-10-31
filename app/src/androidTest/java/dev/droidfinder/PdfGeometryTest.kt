package dev.droidfinder

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfGeometryTest {
    @Test
    fun extractedTextBoundsCoverRenderedGlyphsAtEveryPageRotationAndCrop() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "pdf-geometry-${System.nanoTime()}.pdf")
        val cases = mutableListOf<Triple<Int, Int, Boolean>>()
        PDDocument().use { pdf ->
            for (cropped in listOf(false, true)) for (rotation in
                listOf(0, 90, 180, 270)) for (textRotation in listOf(0, 90, 180, 270)) {
                cases += Triple(rotation, textRotation, cropped)
                val page =
                    PDPage(PDRectangle(600f, 800f)).apply {
                        this.rotation = rotation
                        if (cropped) cropBox = PDRectangle(20f, 30f, 560f, 720f)
                    }
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 24f)
                    content.setTextMatrix(
                        com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                            Math.toRadians(textRotation.toDouble()),
                            300f,
                            400f,
                        ),
                    )
                    content.showText("Ocean light")
                    content.endText()
                }
            }
            pdf.save(file)
        }
        val session = PdfReadingSession(context, Entry(file))
        try {
            for (page in cases.indices) {
                val text = session.text(page)
                val matches = pdfMatches(page, text.text, "ocean light")
                assertEquals("page $page: ${text.text}", 1, matches.size)
                val bounds = pdfMatchBounds(text, matches)
                val image = session.render(page, thumbnail = true)
                var ink = 0
                for (rect in bounds) {
                    val left = (rect.left * image.width).toInt().coerceIn(0, image.width - 1)
                    val top = (rect.top * image.height).toInt().coerceIn(0, image.height - 1)
                    val right = (rect.right * image.width).toInt().coerceIn(left + 1, image.width)
                    val bottom =
                        (rect.bottom * image.height).toInt().coerceIn(top + 1, image.height)
                    for (y in top until bottom) for (x in left until right) {
                        val pixel = image.getPixel(x, y)
                        if (
                            Color.red(pixel) < 180 &&
                                Color.green(pixel) < 180 &&
                                Color.blue(pixel) < 180
                        )
                            ink++
                    }
                }
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                val totalInk = pixels.count {
                    Color.red(it) < 180 && Color.green(it) < 180 && Color.blue(it) < 180
                }
                assertTrue(
                    "Page $page (geometry=${cases[page]}): $ink of $totalInk ink pixels inside $bounds",
                    totalInk > 20 && ink > totalInk * .55f,
                )
            }
        } finally {
            session.close()
            file.delete()
        }
    }
}
