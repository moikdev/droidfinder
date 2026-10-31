package dev.droidfinder

import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Move out of the app and back so Android supplies window hover-exit/enter transitions. */
internal object NativeMouse {
    fun secondaryClick(point: Offset, settle: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val properties =
            arrayOf(
                android.view.MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
                },
            )
        val coordinates =
            arrayOf(
                android.view.MotionEvent.PointerCoords().apply {
                    x = point.x
                    y = point.y
                    pressure = 1f
                    size = 1f
                },
            )
        var downTime = android.os.SystemClock.uptimeMillis()
        for ((index, step) in
            listOf(
                    android.view.MotionEvent.ACTION_HOVER_MOVE to 0,
                    android.view.MotionEvent.ACTION_HOVER_MOVE to 0,
                    android.view.MotionEvent.ACTION_DOWN to
                        android.view.MotionEvent.BUTTON_SECONDARY,
                    android.view.MotionEvent.ACTION_UP to 0,
                )
                .withIndex()) {
            val (action, buttons) = step
            coordinates[0].x = if (index == 0) 1f else point.x
            coordinates[0].y = if (index == 0) 1f else point.y
            if (action == android.view.MotionEvent.ACTION_DOWN)
                downTime = android.os.SystemClock.uptimeMillis()
            coordinates[0].pressure = if (action == android.view.MotionEvent.ACTION_DOWN) 1f else 0f
            val event =
                android.view.MotionEvent.obtain(
                    downTime,
                    android.os.SystemClock.uptimeMillis(),
                    action,
                    1,
                    properties,
                    coordinates,
                    0,
                    buttons,
                    1f,
                    1f,
                    0,
                    0,
                    android.view.InputDevice.SOURCE_MOUSE,
                    0,
                )
            try {
                assertTrue(
                    "Native mouse action $action at ${coordinates[0].x},${coordinates[0].y}",
                    instrumentation.uiAutomation.injectInputEvent(event, true),
                )
                instrumentation.waitForIdleSync()
                if (action != android.view.MotionEvent.ACTION_UP) {
                    // Let Android commit hover/press state before the next native event.
                    // Compose's test clock does not advance Choreographer's input frames.
                    val frame = java.util.concurrent.CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        android.view.Choreographer.getInstance().postFrameCallback {
                            android.view.Choreographer.getInstance().postFrameCallback {
                                frame.countDown()
                            }
                        }
                    }
                    assertTrue(frame.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    settle()
                }
            } finally {
                event.recycle()
            }
        }
    }
}
