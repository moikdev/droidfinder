package dev.findroid

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.*
import org.junit.Assert.*

class OfficePreviewTest {
    @get:Rule val temporary=org.junit.rules.TemporaryFolder()
    private val w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val s="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val p="http://schemas.openxmlformats.org/presentationml/2006/main"
    private val a="http://schemas.openxmlformats.org/drawingml/2006/main"
    private val r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val rel="http://schemas.openxmlformats.org/package/2006/relationships"
    private fun zip(vararg parts: Pair<String,String>): File = File(temporary.root,"document-${System.nanoTime()}.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            (listOf("[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")+parts).forEach { (name,text) ->
                out.putNextEntry(ZipEntry(name));out.write(text.toByteArray());out.closeEntry()
            }
        }
    }
    private fun word(content: String) = zip("word/document.xml" to "<w:document xmlns:w=\"$w\"><w:body>$content</w:body></w:document>")
    private fun sheet(xml: String, shared: String = "", target: String = "worksheets/sheet1.xml") = zip(
        "xl/workbook.xml" to "<workbook xmlns=\"$s\" xmlns:r=\"$r\"><sheets><sheet name=\"Field notes\" r:id=\"r2\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"$rel\"><Relationship Id=\"r2\" Target=\"$target\" Type=\"$r/worksheet\"/></Relationships>",
        "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"$s\"><sheetData>$xml</sheetData></worksheet>",
        "xl/sharedStrings.xml" to "<sst xmlns=\"$s\">$shared</sst>")

    @Test fun exactFormatSetAndProviderMimeAreRecognized() {
        assertEquals(OfficeFormat.DOCX,officeFormat("DOCX","application/octet-stream"))
        assertEquals(OfficeFormat.XLSX,officeFormat("","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals(OfficeFormat.PPTX,officeFormat("pptx",""))
        for(ext in listOf("doc","xls","ppt","xlsb","docm","odt"))assertNull(officeFormat(ext,""))
        assertNull(officeFormat("docx","application/pdf"))
    }
    @Test fun wordReadsUnicodeParagraphsTabsAndBreaksWithoutChangingSource() {
        val file=word("<w:p><w:r><w:t>Ocean light 日本語 🌊</w:t><w:tab/><w:t>Far shore</w:t><w:br/><w:t>Morning</w:t></w:r></w:p>")
        val before=file.readBytes();val result=readOfficeDocument(file,OfficeFormat.DOCX)
        assertEquals("Ocean light 日本語 🌊\tFar shore\nMorning\n",result.sections.single().text)
        assertFalse(result.limited);assertArrayEquals(before,file.readBytes())
    }
    @Test fun deletedAndExplicitlyHiddenWordRunsAreOmitted() {
        val file=word("<w:p><w:r><w:t>Kept</w:t></w:r><w:del><w:r><w:t>Deleted</w:t></w:r></w:del><w:r><w:rPr><w:vanish/></w:rPr><w:t>Hidden</w:t></w:r><w:r><w:t>Again</w:t></w:r></w:p>")
        assertEquals("KeptAgain\n",readOfficeDocument(file,OfficeFormat.DOCX).sections.single().text)
    }
    @Test fun spreadsheetUsesSharedAndInlineTextBooleansCachedValuesAndSparseCoordinates() {
        val file=sheet("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"inlineStr\"><is><t>Ocean</t><r><t> light</t></r></is></c></row><row r=\"3\"><c r=\"B3\"><f>1+2</f><v>3</v></c><c r=\"C3\" t=\"b\"><v>1</v></c></row><row r=\"4\"><c r=\"A4\"><f>NOW()</f></c></row>","<si><t>Far shore</t></si>")
        val result=readOfficeDocument(file,OfficeFormat.XLSX);val section=result.sections.single();val rows=section.table!!.rows
        assertEquals("Field notes",section.title)
        assertEquals(listOf("Row","A","B","C"),rows[0])
        assertEquals(listOf("1","Far shore","","Ocean light"),rows[1])
        assertEquals(listOf("2","","",""),rows[2])
        assertEquals(listOf("3","","3","TRUE"),rows[3])
        assertEquals("[No cached formula result]",rows[4][1]);assertFalse(result.limited)
    }
    @Test fun slideTextFollowsPresentationRelationshipsRatherThanZipEntryOrder() {
        val file=zip("ppt/presentation.xml" to "<p:presentation xmlns:p=\"$p\" xmlns:r=\"$r\"><p:sldIdLst><p:sldId r:id=\"second\"/><p:sldId r:id=\"first\"/></p:sldIdLst></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships xmlns=\"$rel\"><Relationship Id=\"first\" Target=\"slides/slide1.xml\"/><Relationship Id=\"second\" Target=\"/ppt/slides/slide2.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to "<p:sld xmlns:p=\"$p\" xmlns:a=\"$a\"><a:p><a:r><a:t>First</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide2.xml" to "<p:sld xmlns:p=\"$p\" xmlns:a=\"$a\"><a:p><a:r><a:t>Second</a:t></a:r></a:p></p:sld>")
        assertEquals(listOf("Second\n","First\n"),readOfficeDocument(file,OfficeFormat.PPTX).sections.map { it.text })
    }
    @Test fun dtdEntityAndUnsafePartNamesAreRefused() {
        val xml="<!DOCTYPE document [<!ENTITY secret SYSTEM 'file:///must-never-be-opened'>]><w:document xmlns:w=\"$w\"><w:body><w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:body></w:document>"
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(zip("word/document.xml" to xml),OfficeFormat.DOCX) }
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(zip("../escape" to "kept","word/document.xml" to ""),OfficeFormat.DOCX) }
    }
    @Test fun externalOrEscapingSheetTargetsAreNeverRead() {
        for(target in listOf("../outside.xml","https://example.invalid/worksheet.xml","/other/sheet.xml","//example.invalid/sheet.xml"))
            assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(sheet("",target=target),OfficeFormat.XLSX) }
    }
    @Test fun sparseAndWideSheetsAreBoundedAndMarkedPartial() {
        val file=sheet("<row r=\"1000\"><c r=\"AF1000\"><v>42</v></c></row><row r=\"1001\"><c r=\"A1001\"><v>Hidden by limit</v></c></row>")
        val result=readOfficeDocument(file,OfficeFormat.XLSX)
        assertTrue(result.limited);assertTrue(result.sections.sumOf { it.table!!.rows.sumOf(List<String>::size) }<=OfficePolicy.maximumCells)
        assertTrue(result.sections.sumOf { it.text.length }<=OfficePolicy.maximumText)
    }
    @Test fun cancellationClosesThePackageAndPreservesSource() {
        val file=word("<w:p><w:r><w:t>Ocean light</w:t></w:r></w:p>");val before=file.readBytes();var calls=0
        assertThrows(CancellationException::class.java) { readOfficeDocument(file,OfficeFormat.DOCX) { if(++calls==6)throw CancellationException() } }
        assertArrayEquals(before,file.readBytes());assertTrue(file.delete())
    }
    @Test fun duplicateZipPartNamesAreRefused() {
        val file=zip("word/documenU.xml" to "one","word/document.xml" to "two")
        val bytes=file.readBytes().toString(Charsets.ISO_8859_1).replace("word/documenU.xml","word/document.xml").toByteArray(Charsets.ISO_8859_1)
        file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(file,OfficeFormat.DOCX) }
    }
    @Test fun oversizedXmlAndExcessiveDepthAreRefused() {
        val huge=word("<w:p><w:r><w:t>"+"x".repeat(OfficePolicy.maximumXmlBytes)+"</w:t></w:r></w:p>")
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(huge,OfficeFormat.DOCX) }
        val deep=word("<w:p>".repeat(100)+"<w:r><w:t>Ocean</w:t></w:r>"+"</w:p>".repeat(100))
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(deep,OfficeFormat.DOCX) }
    }
    @Test fun longValuesAreMarkedPartialAndNeverSplitASurrogatePair() {
        val file=word("<w:p><w:r><w:t>"+"a".repeat(8191)+"🌊tail</w:t></w:r></w:p>")
        val result=readOfficeDocument(file,OfficeFormat.DOCX)
        assertTrue(result.limited);assertEquals("a".repeat(8191)+"\n",result.sections.single().text)
        assertEquals("a",OfficePolicy.clip("a🌊",2))
    }
    @Test fun inconsistentSpreadsheetCoordinatesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(sheet("<row r=\"1\"><c r=\"A2\"><v>3</v></c></row>"),OfficeFormat.XLSX) }
    }
    @Test fun unavailableSharedStringIsExplainedAndPartial() {
        val result=readOfficeDocument(sheet("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>888</v></c></row>"),OfficeFormat.XLSX)
        assertTrue(result.limited);assertEquals("[Shared text unavailable]",result.sections.single().table!!.rows[1][1])
    }
    @Test fun wrongDocumentTypeAndEncryptedBinaryContainerAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(zip("word/document.xml" to "<workbook xmlns=\"$s\"/>"),OfficeFormat.DOCX) }
        val encrypted=File(temporary.root,"encrypted.docx").apply { writeBytes(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0,0,0,0)) }
        assertThrows(IllegalArgumentException::class.java) { readOfficeDocument(encrypted,OfficeFormat.DOCX) }
    }
}
