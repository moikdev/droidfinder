package dev.findroid

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import java.util.concurrent.CancellationException
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TextComparisonTest {
    private fun read(text: String) = readComparisonText(text.byteInputStream())
    private fun diff(a: String, b: String) = compareText(read(a), read(b))
    private fun fails(message: String, work: () -> Unit) {
        try { work(); fail("Expected refusal") } catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), message in error.message.orEmpty()) }
    }
    @Test fun emptyFilesHaveNoInventedLines() {
        val result = diff("", "")
        assertTrue(result.rows.isEmpty()); assertTrue(result.changes.isEmpty())
    }
    @Test fun trailingNewlineDoesNotInventAnotherLine() {
        assertEquals(listOf(ComparisonLine("a", "\n")), read("a\n").lines)
        assertEquals(listOf(ComparisonLine("", "\n")), read("\n").lines)
    }
    @Test fun identicalUnicodeAndWhitespaceRemainExact() {
        val result = diff("日本語 🌊\n\t title  \n", "日本語 🌊\n\t title  \n")
        assertTrue(result.changes.isEmpty()); assertEquals(2, result.rows.size)
    }
    @Test fun replacementsAreAlignedAndSeparatedByContext() {
        val result = diff("same\nold\nmiddle\nremoved\nend\n", "same\nnew\nmiddle\nadded\nend\n")
        assertEquals(listOf(1, 3), result.changes); assertEquals(2, result.removed); assertEquals(2, result.added)
        assertEquals(TextDifferenceRow(1, 1, true), result.rows[1])
    }
    @Test fun additionsAndRemovalsDoNotShiftUnchangedLines() {
        val result = diff("a\nc\n", "a\nb\nc\n")
        assertEquals(listOf(TextDifferenceRow(0,0,false), TextDifferenceRow(null,1,true), TextDifferenceRow(1,2,false)), result.rows)
        assertEquals(0, result.removed); assertEquals(1, result.added)
    }
    @Test fun emptySideCanBeAddedOrRemoved() {
        assertEquals(2, diff("", "a\nb\n").added)
        assertEquals(2, diff("a\nb\n", "").removed)
    }
    @Test fun missingFinalNewlineIsARealDifference() {
        val result = diff("last\n", "last")
        assertEquals(1, result.changes.size)
        assertTrue(result.report("a", "b").contains("No line ending"))
    }
    @Test fun crLfCrAndLfAreDistinct() {
        assertEquals(listOf("CRLF", "CR", "LF", "No line ending"), read("a\r\nb\rc\nd").lines.map { it.endingLabel })
        assertEquals(1, diff("a\r\n", "a\n").changes.size)
    }
    @Test fun caseTabsAndSpacesAreNotIgnored() {
        assertEquals(1, diff("Title\t \n", "title  \n").changes.size)
    }
    @Test fun utf8BomAndBothUtf16ByteOrdersAreLabelled() {
        val text = "日本語 🌊\r\n"
        val utf8 = readComparisonText(ByteArrayInputStream(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()) + text.toByteArray()))
        val little = readComparisonText(ByteArrayInputStream(byteArrayOf(0xff.toByte(),0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        val big = readComparisonText(ByteArrayInputStream(byteArrayOf(0xfe.toByte(),0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals("UTF-8 BOM", utf8.encoding); assertEquals("UTF-16LE", little.encoding); assertEquals("UTF-16BE", big.encoding)
        assertTrue(compareText(utf8,little).changes.isEmpty()); assertEquals(utf8.lines,big.lines)
    }
    @Test fun invalidEncodingIsNeverReplacedSilently() {
        try { readComparisonText(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28))); fail() }
        catch (_: CharacterCodingException) { }
    }
    @Test fun binaryAndUtf32AreRefused() {
        fails("binary") { read("a\u0000b") }
        try { readComparisonText(ByteArrayInputStream(byteArrayOf(-1,-2,0,0))); fail() }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("UTF-32")) }
    }
    @Test fun byteLimitReadsAnExtraByteInsteadOfTrustingMetadata() {
        val bytes = "line\n".repeat(FileComparisonPolicy.textBytes / 5 + 1).toByteArray()
        fails("512 KiB") { readComparisonText(ByteArrayInputStream(bytes)) }
    }
    @Test fun exactLineAndLengthLimitsAreAcceptedAndExcessRefused() {
        assertEquals(2000, read("a\n".repeat(2000)).lines.size)
        fails("2,000") { read("a\n".repeat(2001)) }
        assertEquals(8192, read("a".repeat(8192)).lines.single().text.length)
        fails("8,192") { read("a".repeat(8193)) }
    }
    @Test fun cancelledReadStopsBeforeTakingAnotherChunk() {
        var checks=0
        try { readComparisonText(ByteArrayInputStream(ByteArray(100_000){65})) { if(++checks==3)throw CancellationException() };fail() }
        catch (_: CancellationException) { assertEquals(3,checks) }
    }
    @Test fun worstCaseDiffIsBoundedAndCancellable() {
        val a=read("a\n".repeat(2000));val b=read("b\n".repeat(2000));var checks=0
        try { compareText(a,b) { if(++checks==40)throw CancellationException() };fail() }
        catch (_: CancellationException) { assertEquals(40,checks) }
        val result=compareText(a,b)
        assertEquals(2000,result.rows.size);assertEquals(1,result.changes.size)
    }
    @Test fun randomizedDiffReconstructsBothInputsAndHasMinimalEdits() {
        val random=Random(52)
        repeat(200) {
            val a=read(List(random.nextInt(0,30)){random.nextInt(5).toString()+"\n"}.joinToString(""))
            val b=read(List(random.nextInt(0,30)){random.nextInt(5).toString()+"\n"}.joinToString(""))
            val result=compareText(a,b)
            assertEquals(a.lines.indices.toList(),result.rows.mapNotNull { it.left })
            assertEquals(b.lines.indices.toList(),result.rows.mapNotNull { it.right })
            result.rows.filterNot { it.changed }.forEach { assertEquals(a.lines[it.left!!],b.lines[it.right!!]) }
            // Independent edit-distance oracle, using only insertion and deletion.
            val distance=Array(a.lines.size+1){IntArray(b.lines.size+1)}
            for(i in 0..a.lines.size)for(j in 0..b.lines.size)distance[i][j]=when {
                i==0 -> j; j==0 -> i
                a.lines[i-1]==b.lines[j-1] -> distance[i-1][j-1]
                else -> minOf(distance[i-1][j],distance[i][j-1])+1
            }
            assertEquals(distance[a.lines.size][b.lines.size],result.added+result.removed)
        }
    }
    @Test fun humanReportIncludesOnlyChangedLinesAndExplicitEndings() {
        val report=diff("context\nold\r\n", "context\nnew").report("before.txt","after.txt")
        assertTrue(report.contains("− L2 [CRLF] old"));assertTrue(report.contains("+ R2 [No line ending] new"))
        assertFalse(report.contains("[LF] context"));assertTrue(report.contains("before.txt"))
    }
}
