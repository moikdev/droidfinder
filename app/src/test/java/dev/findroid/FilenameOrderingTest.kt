package dev.findroid

import java.io.File
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class FilenameOrderingTest {
    private fun ordered(vararg names: String) = names.toList().sortedWith(NaturalFilenameOrder)

    private fun entry(name: String, folder: Boolean = false, parent: String = "/root") =
        Entry(File(parent, name), name, folder, size = 12, modified = 100)

    @Test
    fun numbersSortByValueIncludingEveryRunAndExtensions() {
        assertEquals(
            listOf("Photo 1.jpg", "Photo 2.jpg", "Photo 10.jpg", "Photo 100.jpg"),
            ordered("Photo 100.jpg", "Photo 10.jpg", "Photo 2.jpg", "Photo 1.jpg"),
        )
        assertEquals(
            listOf("v2-part2.2", "v2-part2.10", "v2-part12.1", "v10-part1.1"),
            ordered("v10-part1.1", "v2-part12.1", "v2-part2.10", "v2-part2.2"),
        )
    }

    @Test
    fun fewerLeadingZeroesBreakEqualNumericValues() {
        assertEquals(
            listOf("File 0", "File 00", "File 000", "File 2", "File 02", "File 002", "File 10"),
            ordered("File 002", "File 00", "File 10", "File 0", "File 02", "File 000", "File 2"),
        )
    }

    @Test
    fun arbitraryLengthNumbersNeverOverflowOrLosePrecision() {
        val short = "9".repeat(2048)
        val long = "1" + "0".repeat(2048)
        val close = "9".repeat(2047) + "8"
        assertEquals(listOf(close, short, long), ordered(long, short, close))
        assertEquals(listOf("0".repeat(2048), "1"), ordered("1", "0".repeat(2048)))
    }

    @Test
    fun unicodeDecimalScriptsAndSupplementaryDigitsUseNumericValues() {
        // Arabic-Indic, fullwidth and supplementary-plane mathematical double-struck digits.
        assertEquals(
            listOf("写真١", "写真𝟚", "写真３", "写真10", "写真１２"),
            ordered("写真１２", "写真３", "写真10", "写真𝟚", "写真١"),
        )
        assertEquals(listOf("2", "٢", "𝟚", "２"), ordered("𝟚", "２", "٢", "2"))
        assertEquals(listOf("00", "٠٠", "𝟘𝟘", "００"), ordered("𝟘𝟘", "００", "٠٠", "00"))
    }

    @Test
    fun caseEquivalentSpellingsHaveDeterministicRawTieBreaks() {
        assertEquals(
            listOf("ALBUM 2", "Album 2", "album 2", "Album 10"),
            ordered("Album 10", "album 2", "ALBUM 2", "Album 2"),
        )
        assertEquals(listOf("", ".hidden", "A", "a", "a0", "a00", "a1"),
            ordered("a1", "a00", "a0", "a", "A", ".hidden", ""))
    }

    @Test
    fun orderingDoesNotDependOnDefaultLocale() {
        val original = Locale.getDefault()
        try {
            val names = listOf("I 10", "i 2", "İ 3", "ı 4", "É 2", "é 10")
            Locale.setDefault(Locale.US)
            val expected = names.sortedWith(NaturalFilenameOrder)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(expected, names.sortedWith(NaturalFilenameOrder))
            Locale.setDefault(Locale.JAPAN)
            assertEquals(expected, names.sortedWith(NaturalFilenameOrder))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun mixedScriptsPunctuationAndPaddingRespectComparatorContract() {
        val random = Random(27)
        val tokens = listOf("a", "A", "I", "ı", "İ", ".", ":", "-", "0", "2", "9", "٠", "١", "２", "𝟚", "😀")
        val values = List(180) { buildString { repeat(random.nextInt(1, 7)) { append(tokens.random(random)) } } }
        for (a in values) for (b in values) {
            val one = NaturalFilenameOrder.compare(a, b).compareTo(0)
            val two = NaturalFilenameOrder.compare(b, a).compareTo(0)
            assertEquals("Antisymmetry: $a, $b", one, -two)
            assertEquals(a == b, one == 0)
        }
        val sorted = values.sortedWith(NaturalFilenameOrder)
        for (i in sorted.indices) for (j in i until sorted.size) {
            assertTrue("Transitivity: ${sorted[i]}, ${sorted[j]}", NaturalFilenameOrder.compare(sorted[i], sorted[j]) <= 0)
        }
        assertEquals(sorted, values.shuffled(random).sortedWith(NaturalFilenameOrder))
    }

    @Test
    fun folderPlacementIsIndependentOfSortDirection() {
        val folder = entry("Album 10", true)
        val other = entry("Album 2", true)
        val a = entry("Photo 10.txt")
        val b = entry("Photo 2.txt")
        val values = listOf(a, folder, b, other)
        assertEquals(listOf(other, folder, b, a), values.sortedWith(filenameEntryComparator()))
        assertEquals(listOf(folder, other, a, b), values.sortedWith(filenameEntryComparator(ascending = false)))
        assertEquals(listOf(a, b, folder, other), values.sortedWith(filenameEntryComparator(ascending = false, foldersFirst = false)))
    }

    @Test
    fun metadataTiesUseNaturalNamesThenStablePathsInBothDirections() {
        val two = entry("Photo 2.txt")
        val ten = entry("Photo 10.txt")
        val duplicate = entry("Photo 2.txt", parent = "/elsewhere")
        val values = listOf(ten, two, duplicate)
        for (order in SortBy.entries) {
            val ascending = filenameEntryComparator(order)
            assertEquals(listOf(duplicate, two, ten), values.sortedWith(ascending))
            assertEquals(listOf(ten, two, duplicate), values.sortedWith(filenameEntryComparator(order, false)))
        }
        assertEquals(listOf(ten.copy(size = 1), two), listOf(two, ten.copy(size = 1)).sortedWith(filenameEntryComparator(SortBy.SIZE)))
        assertEquals(listOf(ten.copy(modified = 1), two), listOf(two, ten.copy(modified = 1)).sortedWith(filenameEntryComparator(SortBy.MODIFIED)))
    }

    @Test
    fun fiftyThousandNamesProduceTheExpectedNumericSequence() {
        val names = (1..50_000).map { "Photo $it.jpg" }
        assertEquals(names, names.shuffled(Random(27)).sortedWith(NaturalFilenameOrder))
    }
}
