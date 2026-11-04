package dev.findroid

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object OfficeFixtures {
    const val word="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val sheet="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val presentation="http://schemas.openxmlformats.org/presentationml/2006/main"
    const val drawing="http://schemas.openxmlformats.org/drawingml/2006/main"
    const val rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val packageRel="http://schemas.openxmlformats.org/package/2006/relationships"
    fun write(file: File, parts: Map<String,String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            (mapOf("[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/></Types>")+parts).forEach { (name,text) ->
                output.putNextEntry(ZipEntry(name));output.write(text.toByteArray());output.closeEntry()
            }
        }
    }
    fun documents(folder: File): List<File> {
        val docx=File(folder,"Notes.docx")
        write(docx,mapOf("word/document.xml" to "<w:document xmlns:w=\"$word\"><w:body><w:p><w:r><w:t>Ocean light</w:t></w:r></w:p><w:p><w:r><w:t>Far shore 日本語 🌊</w:t></w:r></w:p></w:body></w:document>"))
        val xlsx=File(folder,"Budget.xlsx")
        write(xlsx,mapOf("xl/workbook.xml" to "<workbook xmlns=\"$sheet\" xmlns:r=\"$rel\"><sheets><sheet name=\"Field notes\" r:id=\"first\"/><sheet name=\"Morning\" r:id=\"second\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"$packageRel\"><Relationship Id=\"first\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"second\" Target=\"worksheets/sheet2.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"$sheet\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Ocean light</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>Far shore</t></is></c></row><row r=\"2\"><c r=\"A2\"><v>2</v></c><c r=\"B2\"><f>A2+1</f><v>3</v></c></row></sheetData></worksheet>",
            "xl/worksheets/sheet2.xml" to "<worksheet xmlns=\"$sheet\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Morning sea</t></is></c></row></sheetData></worksheet>"))
        val pptx=File(folder,"Journey.pptx")
        write(pptx,mapOf("ppt/presentation.xml" to "<p:presentation xmlns:p=\"$presentation\" xmlns:r=\"$rel\"><p:sldIdLst><p:sldId id=\"256\" r:id=\"first\"/><p:sldId id=\"257\" r:id=\"second\"/></p:sldIdLst></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships xmlns=\"$packageRel\"><Relationship Id=\"first\" Target=\"slides/slide1.xml\"/><Relationship Id=\"second\" Target=\"slides/slide2.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to "<p:sld xmlns:p=\"$presentation\" xmlns:a=\"$drawing\"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Ocean light</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>",
            "ppt/slides/slide2.xml" to "<p:sld xmlns:p=\"$presentation\" xmlns:a=\"$drawing\"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Far shore</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"))
        return listOf(docx,xlsx,pptx)
    }
}
