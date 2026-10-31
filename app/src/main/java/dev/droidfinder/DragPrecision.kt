package dev.droidfinder

/** Keeps sub-dp motion within one gesture, including when direction changes. */
internal class DragPrecision {
    private var remainder = 0f

    fun consume(delta: Float): Int {
        remainder += delta
        val whole = remainder.toInt()
        remainder -= whole
        return whole
    }

    fun reset() {
        remainder = 0f
    }
}

/** Signed pixels/second, proportional to proximity to a viewport's edge. */
internal fun dragEdgeVelocity(position: Float, start: Float, end: Float, band: Float): Float {
    if (end <= start || position < start || position > end || band <= 0) return 0f
    val edge = minOf(band, (end - start) / 3f)
    return when {
        position < start + edge -> -((start + edge - position) / edge).coerceIn(0f, 1f)
        position > end - edge -> ((position - end + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}
