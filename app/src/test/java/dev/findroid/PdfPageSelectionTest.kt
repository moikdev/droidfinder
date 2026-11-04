package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class PdfPageSelectionTest {
    private fun map(text: String = "Ocean light\nFar shore", limited: Boolean = false): PdfSelectionMap = PdfSelectionMap(text,
        text.indices.asSequence().filter { !text[it].isWhitespace() }.map { at ->
            val firstLineEnd = text.indexOf('\n').takeIf { it >= 0 } ?: text.length
            val line = if (at < firstLineEnd) 0 else 1
            val column = if (line == 0) at else at - text.indexOf('\n') - 1
            PdfSelectionGlyph(PdfTextRange(at, at + 1), PdfSelectionRect(.05f + column * .04f, .1f + line * .15f, .08f + column * .04f, .14f + line * .15f))
        }, limited)

    @Test fun startsAtTheRenderedWordAndKeepsItsOriginalOffsets() {
        val index = map(); val selection = index.wordAt(.15f, .12f, .01f, .01f)!!
        assertEquals("Ocean", index.selectedText(selection)); assertEquals(PdfTextRange(0, 5), selection.range)
        assertEquals(5, index.boxes(selection).size)
    }

    @Test fun aDragAcrossLinesIncludesRealWhitespaceOnlyOnce() {
        val index = map(); val selection = index.extend(index.wordAt(.1f, .12f, .01f, .01f)!!, .13f, .27f, .01f, .01f)
        assertEquals("Ocean light\nFar", index.selectedText(selection))
    }

    @Test fun draggingBackwardsKeepsLogicalReadingOrder() {
        val index = map(); val selection = index.extend(index.wordAt(.25f, .27f, .01f, .01f)!!, .05f, .12f, .01f, .01f)
        assertEquals("Ocean light\nFar shore", index.selectedText(selection))
    }

    @Test fun blankPageSpaceDoesNotPickADistantWord() {
        val index = map()
        assertNull(index.wordAt(.9f, .9f, .01f, .01f))
        assertNull(index.wordAt(-.1f, .1f, .01f, .01f))
        assertNull(index.wordAt(Float.NaN, .1f, .01f, .01f))
        assertNull(index.wordAt(.1f, .1f, 0f, .01f))
    }

    @Test fun theHitToleranceFollowsTheRenderedScale() {
        val index = map()
        assertNotNull(index.wordAt(.04f, .12f, .02f, .01f))
        assertNull(index.wordAt(.04f, .12f, .002f, .01f))
    }

    @Test fun aGroupedExtractionSpanRemainsIndivisible() {
        val text = "One two three"
        val index = PdfSelectionMap(text, sequenceOf(PdfSelectionGlyph(PdfTextRange(0, text.length), PdfSelectionRect(.1f,.1f,.8f,.2f))))
        assertEquals(text,index.selectedText(index.wordAt(.5f,.15f,.01f,.01f)!!))
    }

    @Test fun invalidAndOffPageGeometryCannotCreateASelection() {
        val range = PdfTextRange(0,1)
        val index = PdfSelectionMap("a",sequenceOf(
            PdfSelectionGlyph(range,PdfSelectionRect(Float.NaN,0f,1f,1f)),
            PdfSelectionGlyph(range,PdfSelectionRect(2f,2f,3f,3f)),
            PdfSelectionGlyph(PdfTextRange(2,3),PdfSelectionRect(0f,0f,1f,1f))))
        assertTrue(index.glyphs.isEmpty()); assertNull(index.all())
    }

    @Test fun partlyCroppedGlyphsUseOnlyTheirVisibleBox() {
        val index = PdfSelectionMap("a",sequenceOf(PdfSelectionGlyph(PdfTextRange(0,1),PdfSelectionRect(-.1f,.1f,.2f,1.2f))))
        assertEquals(PdfSelectionRect(0f,.1f,.2f,1f),index.glyphs.single().bounds)
    }

    @Test fun selectAllKeepsNewlinesAndTheLimitedExtractionStatus() {
        val index = map(limited=true)
        assertTrue(index.textLimited); assertEquals(index.text,index.selectedText(index.all()!!))
    }

    @Test fun movingEitherHandlePreservesTheOppositeEdge() {
        val index = map(); val original = index.wordAt(.1f,.12f,.01f,.01f)!!
        val extended = index.moveHandle(original,false,.13f,.27f,.01f,.01f)
        assertEquals("Ocean light\nFar", index.selectedText(extended))
        val reduced = index.moveHandle(extended,true,.29f,.12f,.01f,.01f)
        assertEquals("light\nFar", index.selectedText(reduced))
    }

    @Test fun aCombiningCharacterCannotBeCopiedWithoutItsBase() {
        val index = PdfSelectionMap("e\u0301",sequenceOf(PdfSelectionGlyph(PdfTextRange(1,2),PdfSelectionRect(.1f,.1f,.2f,.2f))))
        assertEquals("e\u0301",index.selectedText(index.wordAt(.15f,.15f,.01f,.01f)!!))
    }

    @Test fun aSurrogatePairCannotBeSplitByAProviderSpan() {
        val index = PdfSelectionMap("\uD83D\uDE80",sequenceOf(PdfSelectionGlyph(PdfTextRange(1,2),PdfSelectionRect(.1f,.1f,.2f,.2f))))
        assertEquals("\uD83D\uDE80",index.selectedText(index.wordAt(.15f,.15f,.01f,.01f)!!))
    }

    @Test fun indexConstructionHonorsCancellationBeforeDoingPageWork() {
        val failure = IllegalStateException("stopped")
        try { PdfSelectionMap("a",sequenceOf(PdfSelectionGlyph(PdfTextRange(0,1),PdfSelectionRect(.1f,.1f,.2f,.2f))),checkActive={throw failure}); fail("Expected cancellation") }
        catch (error: IllegalStateException) { assertSame(failure,error) }
    }

    @Test fun rectanglesBelongToTheSelectedGlyphsAcrossRotatedLayouts() {
        val top = PdfSelectionRect(.8f,.1f,.9f,.2f); val bottom = PdfSelectionRect(.8f,.3f,.9f,.4f)
        val index = PdfSelectionMap("AB",sequenceOf(PdfSelectionGlyph(PdfTextRange(0,1),top),PdfSelectionGlyph(PdfTextRange(1,2),bottom)))
        val selected = index.extend(index.wordAt(.85f,.15f,.01f,.01f)!!,.85f,.35f,.01f,.01f)
        assertEquals(listOf(top,bottom),index.boxes(selected));assertEquals("AB",index.selectedText(selected))
    }
    @Test fun extendingIntoAnOverlappingGroupedRunKeepsThatWholeMapping() {
        val index = PdfSelectionMap("Hello group",sequenceOf(
            PdfSelectionGlyph(PdfTextRange(0,1),PdfSelectionRect(.1f,.1f,.2f,.2f)),
            PdfSelectionGlyph(PdfTextRange(6,11),PdfSelectionRect(.4f,.1f,.5f,.2f)),
            PdfSelectionGlyph(PdfTextRange(7,8),PdfSelectionRect(.8f,.1f,.9f,.2f))))
        val selected = index.extend(index.wordAt(.15f,.15f,.01f,.01f)!!,.85f,.15f,.01f,.01f)
        assertEquals("Hello group",index.selectedText(selected))
    }

    @Test fun movingAHandleAcrossTheOtherEdgeReversesItsRangeSafely() {
        val index = map();val original = index.wordAt(.1f,.12f,.01f,.01f)!!
        val crossed = index.moveHandle(original,true,.13f,.27f,.01f,.01f)
        assertEquals("n light\nFar",index.selectedText(crossed))
    }

    @Test fun aLongCombiningRunUsesOnePrecomputedCharacterBoundaryIndex() {
        val text = "e" + "\u0301".repeat(20_000)
        val index = PdfSelectionMap(text,sequenceOf(PdfSelectionGlyph(PdfTextRange(10_000,10_001),PdfSelectionRect(.1f,.1f,.2f,.2f))))
        assertEquals(text,index.selectedText(index.wordAt(.15f,.15f,.01f,.01f)!!))
    }

    @Test fun geometryInputsAreBoundedEvenWhenTheSourceSequenceNeverEnds() {
        val glyph = PdfSelectionGlyph(PdfTextRange(0,1),PdfSelectionRect(.1f,.1f,.2f,.2f))
        val index = PdfSelectionMap("a",generateSequence { glyph })
        assertTrue(index.geometryLimited);assertEquals(200_000,index.glyphs.size)
    }

}
