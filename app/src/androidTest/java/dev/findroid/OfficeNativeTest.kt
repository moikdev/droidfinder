package dev.findroid

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.*
import org.junit.Assert.*

class OfficeNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    @Before fun setup() { folder=File(context.cacheDir,"office-native-${System.nanoTime()}").apply { check(mkdirs()) } }
    @After fun cleanup() { check(folder.deleteTreeWithoutLinks()) }
    @Test fun platformXmlParserReadsAllThreeFormatsAndKeepsSourceBytes() {
        val files=OfficeFixtures.documents(folder)
        val formats=listOf(OfficeFormat.DOCX,OfficeFormat.XLSX,OfficeFormat.PPTX)
        for((file,format) in files.zip(formats)) {
            val before=file.readBytes();val result=readOfficeDocument(file,format)
            assertTrue(result.sections.first().text.contains("Ocean light"));assertFalse(result.limited)
            assertArrayEquals(before,file.readBytes())
        }
        val word=readOfficeDocument(files[0],OfficeFormat.DOCX)
        assertTrue(word.sections.single().text.contains("日本語 🌊"))
        assertEquals("Morning",readOfficeDocument(files[1],OfficeFormat.XLSX).sections[1].title)
        assertEquals("Far shore\n",readOfficeDocument(files[2],OfficeFormat.PPTX).sections[1].text)
    }
    @Test fun platformRefusesExternalEntitiesBeforeReadingTheirTargets() {
        val sentinel=File(folder,"Original.txt").apply { writeText("Synthetic original unchanged") }
        val doc=File(folder,"Forbidden.docx")
        OfficeFixtures.write(doc,mapOf("word/document.xml" to "<!DOCTYPE document [<!ENTITY secret SYSTEM '${sentinel.toURI()}'>]><w:document xmlns:w=\"${OfficeFixtures.word}\"><w:body><w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:body></w:document>"))
        val before=doc.readBytes()
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(doc,OfficeFormat.DOCX) }
        assertEquals("Synthetic original unchanged",sentinel.readText());assertArrayEquals(before,doc.readBytes())
    }
    @Test fun platformCancellationClosesTheReadWithoutChangingThePackage() {
        val file=OfficeFixtures.documents(folder).first();val before=file.readBytes();var calls=0
        assertThrows(CancellationException::class.java) { readOfficeDocument(file,OfficeFormat.DOCX) { if(++calls==6)throw CancellationException() } }
        assertArrayEquals(before,file.readBytes());assertTrue(file.delete())
    }
    @Test fun independentlyGeneratedWordWorkbookAndSlidesUseRealPackageStructures() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String,format: OfficeFormat): OfficeDocument {
            val file=File(folder,name)
            assets.open("office/$name").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val before=file.readBytes();val document=readOfficeDocument(file,format)
            assertArrayEquals(before,file.readBytes());assertFalse(document.limited);return document
        }
        val word=read("LibraryNotes.docx",OfficeFormat.DOCX).sections.single().text
        assertTrue(word.contains("Ocean light"));assertTrue(word.contains("日本語 🌊"));assertTrue(word.contains("Morning"))
        val workbook=read("LibraryBudget.xlsx",OfficeFormat.XLSX)
        assertEquals(listOf("Field notes","Morning"),workbook.sections.map { it.title })
        assertTrue(workbook.sections[0].text.contains("[No cached formula result]"));assertTrue(workbook.sections[0].text.contains("TRUE"))
        val slides=read("LibraryJourney.pptx",OfficeFormat.PPTX)
        assertEquals(listOf("Far shore 日本語 🌊\n","Ocean light\n"),slides.sections.map { it.text })
    }
}
