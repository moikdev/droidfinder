package dev.findroid

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WindowLifecycleWorkflowTest {
    @Test fun replacingTheSameCustomWindowTaskKeepsAWorkingDisplayedPane() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FinderApplication
        val id = "lifecycle-task-${System.nanoTime()}"
        val folder = File(app.filesDir, "Workspace/$id").apply { mkdirs() }
        val original = File(folder, "Original.txt").apply { writeText("Window replacement must preserve this synthetic file.\n") }
        val bytes = original.readBytes()
        val intent = Intent(app, MainActivity::class.java)
            .putExtra("finderWindowId", id)
            .putExtra("finderLocation", folder.path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val scenarios = mutableListOf<ActivityScenario<MainActivity>>()
        try {
            repeat(5) {
                val scenario = ActivityScenario.launch<MainActivity>(intent)
                scenarios.add(scenario)
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.elapsedRealtime() + 8_000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = it.model.location == folder.path }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("The synthetic window must finish opening its folder", ready)
                // Allow a frame from the outgoing Activity to race with window cleanup.
                SystemClock.sleep(300)
                scenario.onActivity { activity ->
                    assertEquals(id, activity.finderWindowId)
                    assertEquals(folder.path, activity.model.location)
                    assertSame(activity.model, activity.model.primaryPane)
                    assertTrue(activity.model.paneActive)
                    assertTrue(activity.window.decorView.isAttachedToWindow)
                }
            }
            assertArrayEquals(bytes, original.readBytes())
        } finally {
            scenarios.asReversed().forEach { it.close() }
            instrumentation.runOnMainSync { app.releaseWindow(id) }
            folder.deleteRecursively()
        }
    }

    @Test fun anOverlappingActivityOwnerPreventsPrematureStoreAndPreferenceRelease() = owned { app, id, folder ->
        val first = Any()
        val second = Any()
        try {
            app.retainWindow(id, first)
            app.retainWindow(id, second)
            val pane = app.windowModel(id, folder.path)
            val prefs = app.getSharedPreferences("finder-window-$id-primary", 0)
            assertTrue(prefs.edit().putString("ownerSentinel", "keep").commit())
            app.releaseWindow(id, first, finishing = true)
            app.releaseWindow(id) // Unowned cleanup must also respect active Activity owners.
            assertSame(pane, app.windowModel(id, null))
            assertTrue(pane.paneActive)
            assertEquals("keep", prefs.getString("ownerSentinel", null))
            app.releaseWindow(id, second, finishing = true)
            assertTrue(pane.windowState.panes.isEmpty())
            assertTrue("A cleared primary must remain cleared", runCatching { pane.primaryPane }.exceptionOrNull() is NoSuchElementException)
            assertFalse(app.getSharedPreferences("finder-window-$id-primary", 0).contains("ownerSentinel"))
            assertNotSame(pane, app.windowModel(id, folder.path))
        } finally {
            app.releaseWindow(id, first, finishing = true)
            app.releaseWindow(id, second, finishing = true)
            app.releaseWindow(id)
        }
    }

    @Test fun aConfigurationReplacementRetainsTheStoreAndIgnoresAnObsoleteOwner() = owned { app, id, folder ->
        val first = Any()
        val replacement = Any()
        try {
            app.retainWindow(id, first)
            val pane = app.windowModel(id, folder.path)
            app.releaseWindow(id, first, finishing = false)
            app.retainWindow(id, replacement)
            app.releaseWindow(id, first, finishing = true)
            assertSame(pane, app.windowModel(id, null))
            assertTrue(pane.paneActive)
            app.releaseWindow(id, replacement, finishing = true)
            assertTrue(pane.windowState.panes.isEmpty())
        } finally {
            app.releaseWindow(id, first, finishing = true)
            app.releaseWindow(id, replacement, finishing = true)
            app.releaseWindow(id)
        }
    }

    @Test fun realActivityRecreationPreservesItsPaneUntilTheFinalClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FinderApplication
        val id = "lifecycle-recreate-${System.nanoTime()}"
        val folder = File(app.filesDir, "Workspace/$id").apply { mkdirs() }
        val intent = Intent(app, MainActivity::class.java)
            .putExtra("finderWindowId", id).putExtra("finderLocation", folder.path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        lateinit var pane: FinderModel
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { pane = it.model }
                repeat(3) {
                    scenario.recreate()
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertSame(pane, it.model)
                        assertSame(pane, it.model.primaryPane)
                        assertTrue(it.model.paneActive)
                    }
                }
            }
            instrumentation.runOnMainSync { assertTrue(pane.windowState.panes.isEmpty()) }
        } finally {
            instrumentation.runOnMainSync { app.releaseWindow(id) }
            folder.deleteRecursively()
        }
    }

    private fun owned(body: (FinderApplication, String, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FinderApplication
        val id = "lifecycle-owner-${System.nanoTime()}"
        val folder = File(app.filesDir, "Workspace/$id").apply { mkdirs() }
        try { instrumentation.runOnMainSync { body(app, id, folder) } }
        finally { folder.deleteRecursively() }
    }
}
