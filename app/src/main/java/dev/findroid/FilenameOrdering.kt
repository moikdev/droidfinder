package dev.findroid

/**
 * Case-insensitive filename order with decimal runs compared by value, without parsing numbers or
 * allocating substrings. Fewer leading zeroes sort first when values match. Unicode decimal digits
 * use the same numeric order as ASCII digits; raw spelling breaks otherwise equal names.
 *
 * This order is independent of the device locale so restored views and progressive listings agree.
 */
internal val NaturalFilenameOrder: Comparator<String> = Comparator { left, right ->
    var x = 0
    var y = 0
    while (x < left.length && y < right.length) {
        val a = left.codePointAt(x)
        val b = right.codePointAt(y)
        val digitA = Character.digit(a, 10)
        val digitB = Character.digit(b, 10)
        if (digitA >= 0 && digitB >= 0) {
            var endX = x
            var endY = y
            var significantX = x
            var significantY = y
            var digitsX = 0
            var digitsY = 0
            var zeroesX = 0
            var zeroesY = 0
            while (endX < left.length) {
                val point = left.codePointAt(endX)
                val digit = Character.digit(point, 10)
                if (digit < 0) break
                endX += Character.charCount(point)
                if (digit == 0 && digitsX == zeroesX) {
                    zeroesX++
                    significantX = endX
                }
                digitsX++
            }
            while (endY < right.length) {
                val point = right.codePointAt(endY)
                val digit = Character.digit(point, 10)
                if (digit < 0) break
                endY += Character.charCount(point)
                if (digit == 0 && digitsY == zeroesY) {
                    zeroesY++
                    significantY = endY
                }
                digitsY++
            }
            val length = (digitsX - zeroesX).compareTo(digitsY - zeroesY)
            if (length != 0) return@Comparator length
            while (significantX < endX && significantY < endY) {
                val pointX = left.codePointAt(significantX)
                val pointY = right.codePointAt(significantY)
                val digit = Character.digit(pointX, 10).compareTo(Character.digit(pointY, 10))
                if (digit != 0) return@Comparator digit
                significantX += Character.charCount(pointX)
                significantY += Character.charCount(pointY)
            }
            val padding = digitsX.compareTo(digitsY)
            if (padding != 0) return@Comparator padding
            x = endX
            y = endY
        } else {
            // Normalize a lone digit too: mixing numeral scripts must not create ordering cycles.
            val foldedA = if (digitA >= 0) '0'.code + digitA else foldFilenamePoint(a)
            val foldedB = if (digitB >= 0) '0'.code + digitB else foldFilenamePoint(b)
            val comparison = foldedA.compareTo(foldedB)
            if (comparison != 0) return@Comparator comparison
            x += Character.charCount(a)
            y += Character.charCount(b)
        }
    }
    val remaining = (left.length - x).compareTo(right.length - y)
    if (remaining != 0) remaining else left.compareTo(right)
}

private fun foldFilenamePoint(point: Int) = Character.toLowerCase(Character.toUpperCase(point))

/** Folder placement is independent of direction; identical display names use their stable identity. */
internal fun filenameEntryComparator(
    order: SortBy = SortBy.NAME,
    ascending: Boolean = true,
    foldersFirst: Boolean = true,
): Comparator<Entry> {
    val names = compareBy<Entry, String>(NaturalFilenameOrder) { it.name }.thenBy { it.path }
    val comparator = when (order) {
        SortBy.NAME -> names
        SortBy.MODIFIED -> compareBy<Entry> { it.modified }.then(names)
        SortBy.SIZE -> compareBy<Entry> { it.size }.then(names)
        SortBy.KIND -> compareBy<Entry> { it.kind }.then(names)
    }.let { if (ascending) it else it.reversed() }
    return if (foldersFirst) compareByDescending<Entry> { it.folder }.then(comparator)
    else comparator
}
