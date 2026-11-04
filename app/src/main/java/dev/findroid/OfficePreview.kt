package dev.findroid

import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal enum class OfficeFormat(val label: String) {
    DOCX("Word text"), XLSX("Workbook values"), PPTX("Slide text")
}

internal fun officeFormat(extension: String, mime: String): OfficeFormat? {
    val type = mime.substringBefore(';').trim().lowercase()
    if (type == "application/pdf" || type.startsWith("image/") || extension.equals("pdf", true)) return null
    return when (type) {
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> OfficeFormat.DOCX
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> OfficeFormat.XLSX
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> OfficeFormat.PPTX
        else -> when (extension.lowercase()) {
            "docx" -> OfficeFormat.DOCX
            "xlsx" -> OfficeFormat.XLSX
            "pptx" -> OfficeFormat.PPTX
            else -> null
        }
    }
}

internal data class OfficeSection(val title: String, val text: String, val table: CsvDocument? = null)
internal data class OfficeDocument(val format: OfficeFormat, val sections: List<OfficeSection>, val limited: Boolean)

internal object OfficePolicy {
    const val maximumFileBytes = 64L * 1024 * 1024
    const val maximumEntries = 2048
    const val maximumXmlBytes = 4 * 1024 * 1024
    const val maximumReadBytes = 16 * 1024 * 1024
    const val maximumText = 200_000
    const val maximumSections = 64
    const val maximumRows = 1000
    const val maximumColumns = 32
    const val maximumCells = 10_000
    fun clip(value: String, maximum: Int): String {
        var end = minOf(value.length, maximum.coerceAtLeast(0))
        if (end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
    }
}

private val wordNamespaces = setOf("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "http://purl.oclc.org/ooxml/wordprocessingml/main")
private val sheetNamespaces = setOf("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "http://purl.oclc.org/ooxml/spreadsheetml/main")
private val slideNamespaces = setOf("http://schemas.openxmlformats.org/presentationml/2006/main", "http://purl.oclc.org/ooxml/presentationml/main")
private val drawingNamespaces = setOf("http://schemas.openxmlformats.org/drawingml/2006/main", "http://purl.oclc.org/ooxml/drawingml/main")
private val relationshipNamespaces = setOf("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "http://purl.oclc.org/ooxml/officeDocument/relationships")

/** Reads selected OOXML parts only. No extraction, link resolution, formula evaluation or macros. */
internal fun readOfficeDocument(file: File, format: OfficeFormat, check: () -> Unit = {}): OfficeDocument {
    val stamp = file.length() to file.lastModified()
    require(stamp.first in 1..OfficePolicy.maximumFileBytes) { "Office text previews support files up to 64 MiB. Open this document in another app." }
    file.inputStream().use { input ->
        val signature = ByteArray(4)
        require(input.read(signature) == 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte()) {
            "This isn't a supported unencrypted DOCX, XLSX or PPTX package. Password-protected and older Office formats need another app."
        }
    }
    val started = System.nanoTime()
    fun checkpoint() {
        check()
        require(System.nanoTime() - started < 10_000_000_000L) { "Office preview reached its ten-second parsing limit. Open the document in another app." }
    }
    ZipFile(file).use { zip ->
        val entries = HashMap<String, java.util.zip.ZipEntry>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            checkpoint()
            val entry = enumeration.nextElement()
            require(entries.size < OfficePolicy.maximumEntries) { "This Office package has too many parts for a bounded preview." }
            val name = entry.name
            require(name.length in 1..512 && !name.startsWith('/') && '\\' !in name && '\u0000' !in name && name.split('/').none { it == ".." || it == "." }) { "This Office package contains an unsafe part name." }
            require(entries.put(name, entry) == null) { "This Office package contains duplicate part names." }
        }
        require(entries.containsKey("[Content_Types].xml")) { "This file is not an Office Open XML package." }
        var readBytes = 0
        var limited = false
        // Reserve space for workbook row/column labels and separators in copied text.
        var textLeft = OfficePolicy.maximumText - if(format==OfficeFormat.XLSX) 60_000 else 0
        fun text(value: String): String {
            val clipped = OfficePolicy.clip(value, textLeft)
            if (clipped.length < value.length) limited = true
            textLeft -= clipped.length
            return clipped
        }
        fun xml(part: String, rootName: String, namespaces: Set<String>?, handler: DefaultHandler) {
            checkpoint()
            val entry = entries[part] ?: error("The Office package is missing a required part.")
            require(!entry.isDirectory && entry.method in setOf(0, 8) && entry.size in 0..OfficePolicy.maximumXmlBytes.toLong()) { "This Office XML part exceeds the preview limit or uses unsupported compression." }
            val bytes = zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    readBytes += count
                    require(output.size() + count <= OfficePolicy.maximumXmlBytes && readBytes <= OfficePolicy.maximumReadBytes) { "Office XML data exceeds the bounded preview limit." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) { "An Office package part failed its size or checksum check." }
            val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            require('\u0000' !in content && !content.contains("<!DOCTYPE", true) && !content.contains("<!ENTITY", true)) { "Office previews accept UTF-8 XML without document types or entity declarations." }
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                // Android's Expat and desktop JAXP expose different optional flags.
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            }
            var depth = 0
            var nodes = 0
            factory.newSAXParser().parse(InputSource(StringReader(content)), object : DefaultHandler() {
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External XML references are unavailable.")
                override fun startElement(uri: String, local: String, name: String, attributes: Attributes) {
                    checkpoint()
                    require(++depth <= 96 && ++nodes <= 100_000) { "Office XML structure exceeds the preview limit." }
                    if (depth == 1) require(local == rootName && (namespaces == null || uri in namespaces)) { "An Office part has an unexpected document type." }
                    handler.startElement(uri, local, name, attributes)
                }
                override fun endElement(uri: String, local: String, name: String) {
                    checkpoint(); handler.endElement(uri, local, name); depth--
                }
                override fun characters(chars: CharArray, start: Int, length: Int) {
                    checkpoint(); handler.characters(chars, start, length)
                }
                override fun error(error: org.xml.sax.SAXParseException) { throw error }
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
            })
        }
        fun relationships(part: String, directory: String): Map<String, String> {
            val result = HashMap<String, String>()
            xml(part, "Relationships", null, object : DefaultHandler() {
                override fun startElement(uri: String, local: String, name: String, a: Attributes) {
                    if (local != "Relationship") return
                    val id = a.getValue("Id") ?: error("An Office relationship has no identifier.")
                    if (a.getValue("TargetMode") == "External") return
                    val target = a.getValue("Target") ?: error("An Office relationship has no target.")
                    require(target.length <= 512 && ':' !in target && '\\' !in target && '?' !in target && '#' !in target && !target.startsWith("//")) { "An Office relationship points outside its package." }
                    val path = (if(target.startsWith('/')) java.nio.file.Paths.get(target.drop(1)) else java.nio.file.Paths.get(directory, target)).normalize().toString().replace('\\', '/')
                    require(path.startsWith(directory + "/") && !path.startsWith("../")) { "An Office relationship escapes its document folder." }
                    require(result.put(id, path) == null) { "Duplicate Office relationship identifiers are unsupported." }
                }
            })
            return result
        }
        fun attr(a: Attributes, local: String): String? = (0 until a.length).firstOrNull { a.getLocalName(it) == local }?.let(a::getValue)
        fun relationshipId(a: Attributes): String = relationshipNamespaces.firstNotNullOfOrNull { a.getValue(it, "id") } ?: error("An Office section has no relationship identifier.")
        val sections = ArrayList<OfficeSection>()
        fun paragraphPart(part: String, title: String, word: Boolean) {
            val body = StringBuilder()
            val node = StringBuilder()
            var reading = false
            var excluded = 0
            var hidden = false
            val root = if (word) "document" else "sld"
            xml(part, root, if (word) wordNamespaces else slideNamespaces, object : DefaultHandler() {
                override fun startElement(uri: String, local: String, name: String, a: Attributes) {
                    if (word && uri in wordNamespaces) {
                        if (local in setOf("del", "moveFrom")) excluded++
                        if (local == "r") hidden = false
                        if (local == "vanish" && attr(a, "val") !in setOf("0", "false", "off")) hidden = true
                        if (local == "t" && excluded == 0 && !hidden) { node.setLength(0); reading = true }
                        if (local == "tab" && excluded == 0 && !hidden) body.append(text("\t"))
                        if (local in setOf("br", "cr") && excluded == 0 && !hidden) body.append(text("\n"))
                    } else if (!word && uri in drawingNamespaces && local == "t") { node.setLength(0); reading = true }
                }
                override fun characters(chars: CharArray, start: Int, length: Int) {
                    if (reading) {
                        val count = minOf(length, 8192 - node.length)
                        node.append(chars, start, count)
                        if (count < length) limited = true
                    }
                }
                override fun endElement(uri: String, local: String, name: String) {
                    val valid = if (word) uri in wordNamespaces else uri in drawingNamespaces
                    if (valid && local == "t" && reading) { body.append(text(OfficePolicy.clip(node.toString(), 8192))); reading = false }
                    if (valid && local == "p" && (excluded == 0)) body.append(text("\n"))
                    if (word && uri in wordNamespaces) {
                        if (local == "tc" && excluded == 0) body.append(text("\t"))
                        if (local in setOf("del", "moveFrom")) excluded--
                        if (local == "r") hidden = false
                    }
                }
            })
            sections += OfficeSection(title, body.toString())
        }
        when (format) {
            OfficeFormat.DOCX -> paragraphPart("word/document.xml", "Document text", true)
            OfficeFormat.PPTX -> {
                val ids = ArrayList<String>()
                xml("ppt/presentation.xml", "presentation", slideNamespaces, object : DefaultHandler() {
                    override fun startElement(uri: String, local: String, name: String, a: Attributes) {
                        if (uri in slideNamespaces && local == "sldId") {
                            if (ids.size < OfficePolicy.maximumSections) ids += relationshipId(a) else limited = true
                        }
                    }
                })
                val links = relationships("ppt/_rels/presentation.xml.rels", "ppt")
                ids.forEachIndexed { index, id -> paragraphPart(links[id] ?: error("A slide points to an unavailable or external part."), "Slide ${index + 1}", false) }
            }
            OfficeFormat.XLSX -> {
                val shared = ArrayList<String>()
                var sharedChars = 0
                if (entries.containsKey("xl/sharedStrings.xml")) {
                    val value = StringBuilder();var reading = false;var phonetic = 0
                    xml("xl/sharedStrings.xml", "sst", sheetNamespaces, object : DefaultHandler() {
                        override fun startElement(uri: String, local: String, name: String, a: Attributes) {
                            if (uri !in sheetNamespaces) return
                            if (local == "si") value.setLength(0)
                            if (local == "rPh") phonetic++
                            if (local == "t" && phonetic == 0) reading = true
                        }
                        override fun characters(chars: CharArray, start: Int, length: Int) {
                            if (reading) { val count = minOf(length, 8192-value.length); value.append(chars, start, count);if(count<length) limited=true }
                        }
                        override fun endElement(uri: String, local: String, name: String) {
                            if (uri !in sheetNamespaces) return
                            if (local == "t") reading = false
                            if (local == "rPh") phonetic--
                            if (local == "si") {
                                val result = OfficePolicy.clip(value.toString(), minOf(8192, 1_000_000-sharedChars))
                                if (shared.size < 10_000) { shared += result;sharedChars += result.length } else limited=true
                                if (result.length < value.length) limited=true
                            }
                        }
                    })
                }
                val sheets = ArrayList<Pair<String,String>>()
                xml("xl/workbook.xml", "workbook", sheetNamespaces, object : DefaultHandler() {
                    override fun startElement(uri: String, local: String, name: String, a: Attributes) {
                        if (uri in sheetNamespaces && local == "sheet") {
                            if (sheets.size < OfficePolicy.maximumSections) sheets += (a.getValue("name")?.let { OfficePolicy.clip(it,128) } ?: "Sheet ${sheets.size+1}") to relationshipId(a) else limited=true
                        }
                    }
                })
                val links = relationships("xl/_rels/workbook.xml.rels", "xl")
                var cells = 0
                var gridCells = 0
                for ((title,id) in sheets) {
                    val rows = sortedMapOf<Int,MutableMap<Int,String>>()
                    var row = 0;var column = 0;var nextColumn = 0;var type = "";var capture = "";var formula = false
                    val value = StringBuilder()
                    xml(links[id] ?: error("A sheet points to an unavailable or external part."), "worksheet", sheetNamespaces, object : DefaultHandler() {
                        override fun startElement(uri: String, local: String, name: String, a: Attributes) {
                            if (uri !in sheetNamespaces) return
                            when (local) {
                                "row" -> { row = a.getValue("r")?.toIntOrNull() ?: (row+1);nextColumn=0 }
                                "c" -> {
                                    val reference = a.getValue("r")
                                    column = if (reference == null) nextColumn else {
                                        require(reference.matches(Regex("[A-Z]{1,3}[1-9][0-9]{0,6}"))) { "Invalid spreadsheet cell reference." }
                                        require(reference.dropWhile { it in 'A'..'Z' }.toIntOrNull() == row) { "Spreadsheet row and cell references disagree." }
                                        reference.takeWhile { it in 'A'..'Z' }.fold(0) { n,c -> n*26+(c-'A'+1) }-1
                                    }
                                    nextColumn=column+1;type=a.getValue("t").orEmpty();value.setLength(0);formula=false
                                }
                                "v", "t" -> capture=local
                                "f" -> formula=true
                            }
                        }
                        override fun characters(chars: CharArray, start: Int, length: Int) {
                            if (capture.isNotEmpty()) { val count=minOf(length,8192-value.length);value.append(chars,start,count);if(count<length)limited=true }
                        }
                        override fun endElement(uri: String, local: String, name: String) {
                            if (uri !in sheetNamespaces) return
                            if (local==capture)capture=""
                            if (local!="c")return
                            if (row !in 1..OfficePolicy.maximumRows || column !in 0 until OfficePolicy.maximumColumns || cells>=OfficePolicy.maximumCells || textLeft==0) { limited=true;return }
                            val raw=OfficePolicy.clip(value.toString(),8192)
                            val displayed=when(type) {
                                "s" -> raw.toIntOrNull()?.let(shared::getOrNull) ?: "[Shared text unavailable]".also { limited=true }
                                "b" -> if(raw=="1")"TRUE" else if(raw=="0")"FALSE" else raw
                                else -> if(raw.isEmpty() && formula) "[No cached formula result]" else raw
                            }
                            val values=rows.getOrPut(row){sortedMapOf()}
                            require(!values.containsKey(column)) { "Duplicate spreadsheet cell references are unsupported." }
                            values[column]=text(displayed);cells++
                        }
                    })
                    val columns = (rows.values.flatMap { it.keys }.maxOrNull() ?: -1)+1
                    fun letter(index: Int): String = if(index<26)('A'+index).toString() else "A"+('A'+index-26)
                    if(OfficePolicy.maximumCells-gridCells<columns+1) {
                        limited=true;sections+=OfficeSection(title,"");continue
                    }
                    val grid = ArrayList<List<String>>()
                    grid += listOf("Row")+(0 until columns).map(::letter)
                    val lastRow=rows.keys.maxOrNull() ?: 0
                    val visibleRows=minOf(lastRow,((OfficePolicy.maximumCells-gridCells)/(columns+1)-1).coerceAtLeast(0))
                    if(visibleRows<lastRow)limited=true
                    for (number in 1..visibleRows) grid += listOf(number.toString())+(0 until columns).map { rows[number]?.get(it).orEmpty() }
                    gridCells+=grid.size*(columns+1)
                    sections += OfficeSection(title,grid.joinToString("\n"){it.joinToString("\t")},CsvDocument(grid,columns+1))
                }
            }
        }
        checkpoint()
        require(stamp == (file.length() to file.lastModified())) { "The Office document changed while being read. Reload the preview." }
        return OfficeDocument(format,sections.ifEmpty { listOf(OfficeSection("Document text","")) },limited)
    }
}
