package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class FileClickSequenceTest {
    private val sequence = FileClickSequence()

    private fun click(target: String = "file", time: Long, x: Float = 0f, y: Float = 0f): Boolean {
        sequence.pointerDown()
        return sequence.mouseClick(target, sequence.press, time, x, y, 40, 500, 20f)
    }

    @Test
    fun onlyTheSecondConsecutiveClickActivates() {
        assertFalse(click(time = 100))
        assertTrue(click(time = 250))
        assertFalse(click(time = 350))
    }

    @Test
    fun anotherFileOrPaneBreaksTheSequence() {
        assertFalse(click("left:A", 100))
        assertFalse(click("right:A", 200))
        assertFalse(click("left:B", 300))
        assertFalse(click("left:A", 400))
    }

    @Test
    fun InterveningBackgroundPressOrCancelledDragCannotOpenAnItem() {
        assertFalse(click(time = 100))
        sequence.pointerDown()
        assertFalse(click(time = 250))
        assertTrue(click(time = 400))
    }

    @Test
    fun touchOrModifierSelectionResetsTheSequence() {
        assertFalse(click(time = 100))
        sequence.reset()
        assertFalse(click(time = 200))
    }

    @Test
    fun platformTimingAndSpatialLimitsAreRespected() {
        assertFalse(click(time = 100))
        assertFalse(click(time = 120))
        assertFalse(click(time = 800))
        assertFalse(click(time = 950, x = 21f))
        assertTrue(click(time = 1100, x = 22f))
    }
}
