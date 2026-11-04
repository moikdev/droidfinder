package dev.findroid

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Offsets always refer to the extracted, unmodified page text, never screen coordinates. */
internal data class PdfTextRange(val start: Int, val end: Int) {
    init { require(start >= 0 && end > start) }
}

internal data class PdfSelectionRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun clipped(): PdfSelectionRect? {
        if (!listOf(left, top, right, bottom).all { it.isFinite() }) return null
        val result = PdfSelectionRect(max(0f, left), max(0f, top), min(1f, right), min(1f, bottom))
        return result.takeIf { it.left < it.right && it.top < it.bottom }
    }
}

internal data class PdfSelectionGlyph(val range: PdfTextRange, val bounds: PdfSelectionRect)

internal data class PdfPageSelection(val anchor: PdfTextRange, val focus: PdfTextRange = anchor) {
    val range: PdfTextRange get() = PdfTextRange(min(anchor.start, focus.start), max(anchor.end, focus.end))
}

/**
 * A bounded, page-local index. Both hit tests and highlights use the same normalized glyph boxes
 * as the rendered PDF search overlay. Grouped extraction spans stay indivisible: we never invent
 * individual character positions when the text engine supplied only a grouped mapping.
 */
internal class PdfSelectionMap(
    val text: String,
    input: Sequence<PdfSelectionGlyph>,
    val textLimited: Boolean = false,
    checkActive: () -> Unit = {},
) {
    private val characterBoundaries = run {
        checkActive()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(this@PdfSelectionMap.text) }
        val offsets = IntArray(text.length + 1)
        var count = 0
        var offset = iterator.first()
        while (offset != BreakIterator.DONE) {
            if (count % 256 == 0) checkActive()
            offsets[count++] = offset
            offset = iterator.next()
        }
        offsets.copyOf(count)
    }
    private val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(this@PdfSelectionMap.text) }
    val glyphs: List<PdfSelectionGlyph>
    val geometryLimited: Boolean
    private val mappingGroups: List<PdfTextRange>

    init {
        val result = ArrayList<PdfSelectionGlyph>()
        val iterator = input.iterator()
        var inspected = 0
        while (iterator.hasNext() && inspected < MAX_GLYPHS) {
            if (inspected++ % 256 == 0) checkActive()
            val item = iterator.next()
            if (item.range.start >= text.length || item.range.end > text.length) continue
            val box = item.bounds.clipped() ?: continue
            val start = boundaryBefore(item.range.start)
            val end = boundaryAfter(item.range.end)
            if (start < end) result += PdfSelectionGlyph(PdfTextRange(start, end), box)
        }
        geometryLimited = iterator.hasNext()
        glyphs = result.sortedWith(compareBy({ it.range.start }, { it.range.end }))
        val groups = ArrayList<PdfTextRange>()
        for ((index, glyph) in glyphs.withIndex()) {
            if (index % 256 == 0) checkActive()
            val previous = groups.lastOrNull()
            if (previous != null && glyph.range.start < previous.end)
                groups[groups.lastIndex] = PdfTextRange(previous.start, max(previous.end, glyph.range.end))
            else groups += glyph.range
        }
        mappingGroups = groups
        checkActive()
    }

    private fun boundaryBefore(offset: Int): Int {
        val found = characterBoundaries.binarySearch(offset)
        return characterBoundaries[if (found >= 0) found else (-found - 2).coerceAtLeast(0)]
    }

    private fun boundaryAfter(offset: Int): Int {
        val found = characterBoundaries.binarySearch(offset)
        return characterBoundaries[if (found >= 0) found else (-found - 1).coerceAtMost(characterBoundaries.lastIndex)]
    }

    private fun hit(x: Float, y: Float, toleranceX: Float, toleranceY: Float, nearest: Boolean): PdfSelectionGlyph? {
        if (!x.isFinite() || !y.isFinite() || !toleranceX.isFinite() || !toleranceY.isFinite() ||
            toleranceX <= 0f || toleranceY <= 0f || x !in 0f..1f || y !in 0f..1f) return null
        var best: PdfSelectionGlyph? = null
        var distance = Float.POSITIVE_INFINITY
        for (glyph in glyphs) {
            val box = glyph.bounds
            val dx = max(max(box.left - x, 0f), x - box.right) / toleranceX
            val dy = max(max(box.top - y, 0f), y - box.bottom) / toleranceY
            val candidate = dx * dx + dy * dy
            if (candidate < distance) { distance = candidate; best = glyph }
        }
        return best.takeIf { nearest || distance <= 1f }
    }

    fun wordAt(x: Float, y: Float, toleranceX: Float, toleranceY: Float): PdfPageSelection? {
        val glyph = hit(x, y, toleranceX, toleranceY, nearest = false) ?: return null
        val at = glyph.range.start
        var start = if (words.isBoundary(at)) at else words.preceding(at).coerceAtLeast(0)
        var end = words.following(at).let { if (it < 0) text.length else it }
        // A ligature or a grouped text run can cross a word boundary. Keep its real mapping whole.
        start = min(start, glyph.range.start)
        end = max(end, glyph.range.end)
        return PdfPageSelection(expandMappedRange(PdfTextRange(start, end)))
    }

    fun extend(selection: PdfPageSelection, x: Float, y: Float, toleranceX: Float, toleranceY: Float): PdfPageSelection {
        val glyph = hit(x, y, toleranceX, toleranceY, nearest = true) ?: return selection
        return selection.copy(focus = expandMappedRange(glyph.range))
    }

    /** The opposite handle remains the fixed anchor, so crossing a handle reverses the selection. */
    fun moveHandle(selection: PdfPageSelection, startHandle: Boolean, x: Float, y: Float, toleranceX: Float, toleranceY: Float): PdfPageSelection {
        val hit = hit(x, y, toleranceX, toleranceY, nearest = true) ?: return selection
        val fixed = (if (startHandle) glyphs.lastOrNull { it.range.end > selection.range.start && it.range.start < selection.range.end }
            else glyphs.firstOrNull { it.range.end > selection.range.start && it.range.start < selection.range.end })?.range ?: return selection
        return PdfPageSelection(expandMappedRange(fixed), expandMappedRange(hit.range))
    }

    fun stepHandle(selection: PdfPageSelection, startHandle: Boolean, delta: Int): PdfPageSelection {
        val first = glyphs.indexOfFirst { it.range.end > selection.range.start && it.range.start < selection.range.end }
        val last = glyphs.indexOfLast { it.range.end > selection.range.start && it.range.start < selection.range.end }
        if (first < 0 || last < 0) return selection
        val moving = if (startHandle) first else last
        val fixed = if (startHandle) last else first
        val next = (moving + delta).coerceIn(0, glyphs.lastIndex)
        return PdfPageSelection(expandMappedRange(glyphs[fixed].range), expandMappedRange(glyphs[next].range))
    }

    fun all(): PdfPageSelection? = if (text.isNotEmpty() && glyphs.isNotEmpty()) PdfPageSelection(PdfTextRange(0, text.length)) else null

    fun selectedText(selection: PdfPageSelection): String =
        text.substring(selection.range.start.coerceIn(0, text.length), selection.range.end.coerceIn(0, text.length))

    fun boxes(selection: PdfPageSelection): List<PdfSelectionRect> = glyphs.asSequence()
        .filter { it.range.start < selection.range.end && it.range.end > selection.range.start }
        .map { it.bounds }.distinct().toList()

    fun edge(selection: PdfPageSelection, startHandle: Boolean): PdfSelectionRect? =
        glyphs.let { if (startHandle) it.firstOrNull { glyph -> glyph.range.end > selection.range.start && glyph.range.start < selection.range.end }
        else it.lastOrNull { glyph -> glyph.range.end > selection.range.start && glyph.range.start < selection.range.end } }?.bounds

    private fun expandMappedRange(range: PdfTextRange): PdfTextRange {
        var start = boundaryBefore(range.start)
        var end = boundaryAfter(range.end)
        // Groups are disjoint logical intervals, computed in one pass at construction.
        // Expanding into one cannot jump backwards into another disjoint group.
        for (group in mappingGroups) if (group.start < end && group.end > start) {
            start = min(start, group.start)
            end = max(end, group.end)
        }
        return PdfTextRange(start, end)
    }

    private companion object { const val MAX_GLYPHS = 200_000 }
}
