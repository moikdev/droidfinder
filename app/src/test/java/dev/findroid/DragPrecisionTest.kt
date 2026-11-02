package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class DragPrecisionTest {
    @Test
    fun onePixelEventsAtTabletDensityAccumulateInBothDirections() {
        val motion = DragPrecision()
        assertEquals(200, (1..300).sumOf { motion.consume(1f / 1.5f) })
        assertEquals(-200, (1..300).sumOf { motion.consume(-1f / 1.5f) })
    }

    @Test
    fun reversingAndStartingAnotherGestureDoesNotLeakMotion() {
        val motion = DragPrecision()
        assertEquals(0, motion.consume(.6f))
        assertEquals(0, motion.consume(-.6f))
        assertEquals(-1, motion.consume(-1.2f))
        motion.reset()
        assertEquals(0, motion.consume(-.9f))
        assertEquals(-1, motion.consume(-.2f))
    }

    @Test
    fun edgesAccelerateAndStopOutsideTheViewportOrInTheCenter() {
        assertEquals(-1f, dragEdgeVelocity(100f, 100f, 500f, 48f))
        assertEquals(-.5f, dragEdgeVelocity(124f, 100f, 500f, 48f))
        assertEquals(0f, dragEdgeVelocity(300f, 100f, 500f, 48f))
        assertEquals(.5f, dragEdgeVelocity(476f, 100f, 500f, 48f))
        assertEquals(1f, dragEdgeVelocity(500f, 100f, 500f, 48f))
        assertEquals(0f, dragEdgeVelocity(501f, 100f, 500f, 48f))
        assertEquals(0f, dragEdgeVelocity(99f, 100f, 500f, 48f))
        assertEquals(0f, dragEdgeVelocity(100f, 100f, 100f, 48f))
        assertEquals(0f, dragEdgeVelocity(300f, 100f, 500f, 0f))
    }

    @Test
    fun smallViewportsKeepANeutralCenter() {
        assertEquals(0f, dragEdgeVelocity(30f, 0f, 60f, 48f))
        assertTrue(dragEdgeVelocity(10f, 0f, 60f, 48f) < 0)
        assertTrue(dragEdgeVelocity(50f, 0f, 60f, 48f) > 0)
    }
}
