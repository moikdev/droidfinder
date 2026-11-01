package dev.droidfinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.io.StringWriter
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal val Entry.isPdfDocument: Boolean
    get() =
        !folder &&
            (extension == "pdf" ||
                mimeType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.equals("application/pdf", ignoreCase = true) == true)

internal data class PdfSpan(val start: Int, val end: Int, val bounds: List<RectF>)

internal data class PdfPageText(
    val text: String,
    val spans: List<PdfSpan>,
    val limited: Boolean = false,
)

internal data class PdfMatch(val page: Int, val start: Int, val end: Int, val snippet: String)

internal data class PdfOutline(val title: String, val page: Int, val depth: Int)

internal data class PdfContents(val items: List<PdfOutline>, val limited: Boolean)

internal data class PdfReadingPosition(val page: Int = 0, val bookmarks: Set<Int> = emptySet())

/** Search uses normalized whitespace while preserving offsets into the selectable original text. */
internal fun pdfMatches(
    page: Int,
    text: String,
    query: String,
    maximum: Int = 2000,
): List<PdfMatch> {
    val normalized = StringBuilder()
    val offsets = ArrayList<Int>()
    text.forEachIndexed { index, char ->
        if (!char.isWhitespace() || normalized.lastOrNull() != ' ') {
            normalized.append(if (char.isWhitespace()) ' ' else char)
            offsets += index
        }
    }
    val needle = query.trim().replace(Regex("\\s+"), " ")
    if (needle.isEmpty()) return emptyList()
    val haystack = normalized.toString()
    val results = ArrayList<PdfMatch>()
    var from = 0
    while (from < haystack.length && results.size < maximum) {
        val at = haystack.indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        val start = offsets[at]
        val end = offsets[at + needle.length - 1] + 1
        results +=
            PdfMatch(
                page,
                start,
                end,
                text
                    .substring((start - 36).coerceAtLeast(0), (end + 72).coerceAtMost(text.length))
                    .replace(Regex("\\s+"), " ")
                    .trim(),
            )
        from = at + needle.length
    }
    return results
}

internal fun pdfMatchBounds(content: PdfPageText, matches: List<PdfMatch>): List<RectF> {
    if (matches.isEmpty()) return emptyList()
    val result = ArrayList<RectF>()
    var cursor = 0
    var previousMatch = -1
    for (span in content.spans) {
        while (cursor < matches.size && matches[cursor].end <= span.start) cursor++
        if (cursor >= matches.size) break
        if (matches[cursor].start < span.end)
            for (rect in span.bounds) {
                val previous = result.lastOrNull()
                if (
                    previousMatch == cursor &&
                        previous != null &&
                        kotlin.math.abs(previous.top - rect.top) < .01f &&
                        kotlin.math.abs(previous.bottom - rect.bottom) < .01f &&
                        rect.left <= previous.right + .02f &&
                        rect.right >= previous.left - .02f
                )
                    previous.union(rect)
                else result.add(RectF(rect))
                previousMatch = cursor
            }
    }
    return result
}

/**
 * No names or raw paths are persisted. Entries are scoped to the document's current fingerprint.
 */
internal class PdfReadingStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("pdf-reading", Context.MODE_PRIVATE)

    private fun key(path: String, modified: Long, bytes: Long) =
        MessageDigest.getInstance("SHA-256")
            .digest("$path\n$modified\n$bytes".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun read(path: String, modified: Long, bytes: Long): PdfReadingPosition = runCatching {
        val value = JSONObject(prefs.getString(key(path, modified, bytes), "{}")!!)
        val a = value.optJSONArray("marks") ?: JSONArray()
        PdfReadingPosition(
            value.optInt("page").coerceAtLeast(0),
            (0 until minOf(a.length(), 500)).map { a.optInt(it, -1) }.filter { it >= 0 }.toSet(),
        )
    }
        .getOrDefault(PdfReadingPosition())

    fun save(path: String, modified: Long, bytes: Long, value: PdfReadingPosition) =
        synchronized(lock) {
            val key = key(path, modified, bytes)
            val editor = prefs.edit()
            if (key !in prefs.all && prefs.all.size >= 100) {
                prefs.all.entries
                    .sortedBy {
                        runCatching { JSONObject(it.value.toString()).optLong("time") }
                            .getOrDefault(0)
                    }
                    .take(prefs.all.size - 99)
                    .forEach { editor.remove(it.key) }
            }
            editor
                .putString(
                    key,
                    JSONObject()
                        .put("page", value.page)
                        .put("marks", JSONArray(value.bookmarks.sorted().take(500)))
                        .put("time", System.currentTimeMillis())
                        .toString(),
                )
                .apply()
        }

    private companion object {
        val lock = Any()
    }
}

/**
 * Native rendering and portable text extraction have separate locks and never run on the UI thread.
 */
internal class PdfReadingSession(private val context: Context, private val entry: Entry) :
    Closeable {
    private val scratchLease = CacheLeases.pin(File(context.cacheDir, "pdf-scratch"))
    private val closed = AtomicBoolean(false)
    private val renderLock = Mutex()
    private val textLock = Mutex()
    private var renderer: PdfRenderer? = null
    private var document: PDDocument? = null
    private var documentError: Exception? = null
    private val texts = LinkedHashMap<Int, PdfPageText>(8, .75f, true)
    private val thumbs = LinkedHashMap<Int, Bitmap>(24, .75f, true)

    private fun checkOpen() {
        check(!closed.get()) { "PDF preview closed" }
    }

    private fun native(): PdfRenderer {
        checkOpen()
        return renderer
            ?: ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY).let {
                descriptor ->
                try {
                    PdfRenderer(descriptor).also { renderer = it }
                } catch (error: Throwable) {
                    descriptor.close()
                    throw error
                }
            }
    }

    private fun textDocument(): PDDocument {
        checkOpen()
        documentError?.let { throw it }
        return document
            ?: try {
                synchronized(libraryLock) { PDFBoxResourceLoader.init(context.applicationContext) }
                val scratch = File(context.cacheDir, "pdf-scratch").apply { mkdirs() }
                PDDocument.load(
                        entry.file,
                        MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 128L * 1024 * 1024)
                            .setTempDir(scratch),
                    )
                    .also { document = it }
            } catch (error: Exception) {
                if (error !is CancellationException) documentError = error
                throw error
            }
    }

    suspend fun pageCount(): Int =
        withContext(Dispatchers.IO) {
            renderLock.withLock {
                native().pageCount.also { check(it > 0) { "This PDF has no pages." } }
            }
        }

    suspend fun render(page: Int, thumbnail: Boolean = false): Bitmap =
        withContext(Dispatchers.IO) {
            renderLock.withLock {
                checkOpen()
                if (thumbnail)
                    thumbs[page]?.let {
                        return@withLock it
                    }
                native().openPage(page).use { source ->
                    val scale =
                        minOf(
                            (if (thumbnail) 220f else 1800f) / source.width.coerceAtLeast(1),
                            (if (thumbnail) 300f else 2400f) / source.height.coerceAtLeast(1),
                        )
                    val bitmap =
                        Bitmap.createBitmap(
                            (source.width * scale).toInt().coerceAtLeast(1),
                            (source.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ensureActive()
                        checkOpen()
                        if (thumbnail) {
                            thumbs[page] = bitmap
                            while (thumbs.size > 18) thumbs.remove(thumbs.keys.first())
                        }
                        bitmap
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
        }

    suspend fun text(page: Int): PdfPageText =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            textLock.withLock {
                checkOpen()
                texts[page]?.let {
                    return@withLock it
                }
                val pdf = textDocument()
                check(pdf.currentAccessPermission.canExtractContent()) {
                    "This PDF does not allow text extraction."
                }
                val writer = StringWriter()
                val spans = ArrayList<PdfSpan>()
                var limited = false
                val stripper =
                    object : PDFTextStripper() {
                            override fun processTextPosition(text: TextPosition) {
                                job.ensureActive()
                                checkOpen()
                                if (
                                    writer.buffer.length + charactersByArticle.sumOf { it.size } >
                                        200_000
                                )
                                    throw PdfTextLimit()
                                super.processTextPosition(text)
                            }

                            override fun writeString(
                                text: String,
                                positions: MutableList<TextPosition>,
                            ) {
                                job.ensureActive()
                                checkOpen()
                                if (writer.buffer.length + text.length > 200_000)
                                    throw PdfTextLimit()
                                val start = writer.buffer.length
                                super.writeString(text, positions)
                                val page = pdf.getPage(page)
                                val width =
                                    if (page.rotation % 180 == 0) page.cropBox.width
                                    else page.cropBox.height
                                val height =
                                    if (page.rotation % 180 == 0) page.cropBox.height
                                    else page.cropBox.width
                                fun bounds(p: TextPosition): RectF {
                                    // Glyph width follows the text baseline, not the rotated page's
                                    // X axis.
                                    // Work in crop-box PDF coordinates, then rotate all four glyph
                                    // corners.
                                    val matrix = p.textMatrix
                                    val baselineLength =
                                        kotlin.math
                                            .hypot(matrix.scaleX, matrix.shearY)
                                            .coerceAtLeast(.001f)
                                    val ascentLength =
                                        kotlin.math
                                            .hypot(matrix.shearX, matrix.scaleY)
                                            .coerceAtLeast(.001f)
                                    val advanceX = matrix.scaleX / baselineLength * p.widthDirAdj
                                    val advanceY = matrix.shearY / baselineLength * p.widthDirAdj
                                    val metrics = p.font.fontDescriptor
                                    val ascent =
                                        maxOf(
                                            p.heightDir,
                                            (metrics?.ascent ?: 0f) * ascentLength / 1000f,
                                        )
                                    val descent =
                                        minOf(0f, (metrics?.descent ?: 0f) * ascentLength / 1000f)
                                    val upX = matrix.shearX / ascentLength
                                    val upY = matrix.scaleY / ascentLength
                                    val x = matrix.translateX
                                    val y = matrix.translateY
                                    val xs =
                                        floatArrayOf(
                                            x + upX * descent,
                                            x + advanceX + upX * descent,
                                            x + advanceX + upX * ascent,
                                            x + upX * ascent,
                                        )
                                    val ys =
                                        floatArrayOf(
                                            y + upY * descent,
                                            y + advanceY + upY * descent,
                                            y + advanceY + upY * ascent,
                                            y + upY * ascent,
                                        )
                                    var left = Float.POSITIVE_INFINITY
                                    var top = Float.POSITIVE_INFINITY
                                    var right = Float.NEGATIVE_INFINITY
                                    var bottom = Float.NEGATIVE_INFINITY
                                    for (i in 0..3) {
                                        val px: Float
                                        val py: Float
                                        when (page.rotation) {
                                            90 -> {
                                                px = ys[i]
                                                py = xs[i]
                                            }
                                            180 -> {
                                                px = page.cropBox.width - xs[i]
                                                py = ys[i]
                                            }
                                            270 -> {
                                                px = page.cropBox.height - ys[i]
                                                py = page.cropBox.width - xs[i]
                                            }
                                            else -> {
                                                px = xs[i]
                                                py = page.cropBox.height - ys[i]
                                            }
                                        }
                                        left = minOf(left, px)
                                        top = minOf(top, py)
                                        right = maxOf(right, px)
                                        bottom = maxOf(bottom, py)
                                    }
                                    return RectF(
                                        left / width,
                                        top / height,
                                        right / width,
                                        bottom / height,
                                    )
                                }
                                var cursor = 0
                                val glyphSpans = ArrayList<PdfSpan>()
                                var mapped = true
                                for (position in positions) {
                                    val unicode =
                                        java.text.Normalizer.normalize(
                                            position.unicode.orEmpty(),
                                            java.text.Normalizer.Form.NFKC,
                                        )
                                    if (unicode.isEmpty()) continue
                                    val at = text.indexOf(unicode, cursor)
                                    if (at < 0) {
                                        mapped = false
                                        break
                                    }
                                    glyphSpans +=
                                        PdfSpan(
                                            start + at,
                                            start + at + unicode.length,
                                            listOf(bounds(position)),
                                        )
                                    cursor = at + unicode.length
                                }
                                if (mapped) spans.addAll(glyphSpans)
                                else
                                    spans +=
                                        PdfSpan(
                                            start,
                                            writer.buffer.length,
                                            positions.map(::bounds),
                                        )
                            }
                        }
                        .apply {
                            startPage = page + 1
                            endPage = page + 1
                            sortByPosition = true
                        }
                try {
                    stripper.writeText(pdf, writer)
                } catch (_: PdfTextLimit) {
                    limited = true
                }
                job.ensureActive()
                checkOpen()
                PdfPageText(writer.toString(), spans, limited).also {
                    texts[page] = it
                    while (texts.size > 3 || texts.values.sumOf { it.text.length } > 250_000) texts
                        .remove(texts.keys.first())
                }
            }
        }

    suspend fun outline(): PdfContents =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            textLock.withLock {
                val pdf = textDocument()
                val results = ArrayList<PdfOutline>()
                val visited = mutableSetOf<Any>()
                val pending = ArrayDeque<Pair<PDOutlineItem, Int>>()
                var limited = false
                pdf.documentCatalog.documentOutline?.firstChild?.let { pending.add(it to 0) }
                while (pending.isNotEmpty()) {
                    job.ensureActive()
                    checkOpen()
                    if (results.size >= 500 || visited.size >= 2000) {
                        limited = true
                        break
                    }
                    val (item, depth) = pending.removeFirst()
                    if (!visited.add(item.cosObject)) {
                        limited = true
                        continue
                    }
                    val destination = runCatching { item.findDestinationPage(pdf) }.getOrNull()
                    val page = destination?.let { pdf.pages.indexOf(it) } ?: -1
                    if (page >= 0)
                        results +=
                            PdfOutline(
                                item.title.orEmpty().take(300).ifBlank { "Page ${page + 1}" },
                                page,
                                depth,
                            )
                    item.nextSibling?.let { pending.addFirst(it to depth) }
                    item.firstChild?.let {
                        if (depth < 12) pending.addFirst(it to depth + 1) else limited = true
                    }
                }
                PdfContents(results, limited)
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanup.launch {
            renderLock.withLock {
                runCatching { renderer?.close() }
                renderer = null
                thumbs.clear()
            }
        }
        cleanup.launch {
            textLock.withLock {
                try {
                    runCatching { document?.close() }
                    document = null
                    texts.clear()
                } finally {
                    scratchLease.close()
                }
            }
        }
    }

    private class PdfTextLimit : RuntimeException()

    private companion object {
        val libraryLock = Any()
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
