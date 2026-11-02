package dev.findroid

/** Only consecutive, completed primary mouse clicks on the same item can activate it. */
internal class FileClickSequence {
    private data class Click(
        val target: String,
        val press: Long,
        val time: Long,
        val x: Float,
        val y: Float,
    )

    private var previous: Click? = null
    var press: Long = 0
        private set

    fun pointerDown() {
        press++
    }

    fun reset() {
        previous = null
    }

    fun mouseClick(
        target: String,
        gesture: Long,
        time: Long,
        x: Float,
        y: Float,
        minimumDelay: Long,
        maximumDelay: Long,
        slop: Float,
    ): Boolean {
        val old = previous
        val elapsed = if (old == null) -1 else time - old.time
        val double =
            old != null &&
                old.target == target &&
                old.press + 1 == gesture &&
                elapsed in minimumDelay..maximumDelay &&
                (x - old.x) * (x - old.x) + (y - old.y) * (y - old.y) <= slop * slop
        previous = if (double) null else Click(target, gesture, time, x, y)
        return double
    }
}
