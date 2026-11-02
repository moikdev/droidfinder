@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real global drags into another UID in Android split screen, never a fabricated DragEvent. */
class ExternalDragWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Volatile private var currentActivity: MainActivity? = null
    private val activity get() = requireNotNull(currentActivity)
    private val model get() = activity.model
    private val lifecycle = ActivityLifecycleCallback { value, stage ->
        if (value is MainActivity && stage == Stage.RESUMED) currentActivity = value
    }
    private lateinit var directory: File
    private var connection: String? = null
    private var drag: DragWorkflowTest.NativeDrag? = null
    private var serviceFlags = 0

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    @Before fun setup() {
        Assume.assumeTrue("Install the separately built interop-client first", shell("pm path dev.findroid.interop").contains("package:"))
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle) }
        shell("am force-stop dev.findroid.interop")
        // Start cold in the desired mode. Reparenting an already full-screen task
        // into Android 11's legacy split roots leaves its override mode unchanged.
        val launch = shell("am start -W -f 0x10008000 --windowingMode ${if (Build.VERSION.SDK_INT == 30) 3 else 1} -n dev.findroid/.MainActivity")
        check(launch.contains("Status: ok")) { "Could not launch the source activity: $launch" }
        compose.waitUntil(15_000) { currentActivity != null }
        directory = File(activity.filesDir, "Workspace/External-drag-${System.nanoTime()}").apply { mkdirs() }
        serviceFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.navigate(directory.path)
            model.updateQuery("")
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
        }
    }

    @After fun cleanup() {
        drag?.cancel()
        if (currentActivity != null) {
            compose.runOnIdle {
                model.navigate(model.workspace.path)
                connection?.let(model::disconnectLocation)
                model.clearSelection()
                activity.finish()
            }
        }
        shell("am force-stop dev.findroid.interop")
        if (::directory.isInitialized) directory.deleteRecursively()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = serviceFlags }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle) }
    }

    @Test fun touchIconCopiesOneFileToAnotherApp() = runDrop(mouse = false, handle = false, provider = false, multiple = false)
    @Test fun touchSelectedIconCopiesEntireSelection() = runDrop(mouse = false, handle = false, provider = false, multiple = true)
    @Test fun touchHandleCopiesMixedTypesToAnotherApp() = runDrop(mouse = false, handle = true, provider = false, multiple = true)
    @Test fun mouseIconCopiesWithoutMovingTheSource() = runDrop(mouse = true, handle = false, provider = false, multiple = false)
    @Test fun connectedProviderFileCanBeDroppedIntoAnotherApp() = runDrop(mouse = false, handle = false, provider = true, multiple = false)

    private fun runDrop(mouse: Boolean, handle: Boolean, provider: Boolean, multiple: Boolean) {
        try {
            performDrop(mouse, handle, provider, multiple)
        } catch (error: Throwable) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(activity.filesDir, "external-drop-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            File(activity.filesDir, "external-drop-failure.txt").writeText(
                "mouse=$mouse handle=$handle provider=$provider multiple=$multiple\n" +
                    shell("run-as dev.findroid.interop cat files/drop-trace.json") + "\n" +
                    shell("dumpsys activity activities"))
            throw error
        }
    }

    private fun performDrop(mouse: Boolean, handle: Boolean, provider: Boolean, multiple: Boolean) {
        if (provider) {
            directory.deleteRecursively()
            directory = File(activity.filesDir, "ProviderFixtures/External-drag-${System.nanoTime()}").apply { mkdirs() }
        }
        val note = File(directory, "Project notes.txt").apply { writeText("A file copied through Android's real global drag.") }
        val image = File(directory, "Diagram.png").apply {
            val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val hidden = File(directory, "Private.txt").apply { writeText("Never selected or granted") }
        compose.runOnIdle {
            if (provider) {
                model.connectLocation(DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${directory.name}"), false)
            } else model.navigate(directory.path)
        }
        compose.waitUntil(15_000) { !model.loading && model.entries.any { it.name == note.name } }
        if (provider) connection = model.location
        val entries = model.entries.filter { it.name == note.name || multiple && it.name == image.name }
        val probe = entries.first { it.name == note.name }.let { it.documentUri ?: FileProvider.getUriForFile(activity, "dev.findroid.files", it.file) }
        val protectedUri = model.entries.first { it.name == hidden.name }.let { it.documentUri ?: FileProvider.getUriForFile(activity, "dev.findroid.files", it.file) }
        shell("am force-stop dev.findroid.interop")
        shell("run-as dev.findroid.interop rm -f files/drop-report.json files/drop-trace.json")
        shell("am start -W --windowingMode ${if (Build.VERSION.SDK_INT == 30) 4 else 1} -n dev.findroid.interop/.DropReceiverActivity --es probeUri '$probe' --es protectedUri '$protectedUri'")
        if (Build.VERSION.SDK_INT >= 31) {
            val dumps = shell("dumpsys activity activities")
            val task = Regex("#(\\d+)[^\\n]*A=\\d+:dev\\.findroid\\.interop\\.drop\\b").find(dumps)?.groupValues?.get(1)
                ?: error("Receiver task not found")
            shell("cmd window shell splitscreen moveToSideStage $task 1")
        }
        try {
            compose.waitUntil(15_000) { dropBounds() != null && activity.resources.configuration.screenWidthDp < 1000 }
        } catch (error: Exception) {
            File(activity.filesDir, "external-split-debug.txt").writeText(
                "sourceWidthDp=${activity.resources.configuration.screenWidthDp} targetBounds=${dropBounds()} windows=${instrumentation.uiAutomation.windows}\n" +
                    shell("dumpsys activity activities"))
            throw error
        }
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodesWithTag("file-browser").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        compose.waitForIdle()
        // Android may still show the split-screen starting surface after Compose
        // exposes its semantics. Commit a real frame before injecting the pointer.
        val rendered = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.runOnIdle {
            activity.window.decorView.apply {
                viewTreeObserver.registerFrameCommitCallback { rendered.set(true) }
                invalidate()
            }
        }
        compose.waitUntil(15_000) { rendered.get() }
        compose.runOnIdle { model.select(model.entries.first { it.name == note.name }); if (multiple) model.select(model.entries.first { it.name == image.name }, multiple = true) }
        val node = if (handle) compose.onNodeWithTag("drag-selection")
            else compose.onNodeWithTag("drag-icon-${entries.first { it.name == note.name }.path}", useUnmergedTree = true)
        val inWindow = node.fetchSemanticsNode().boundsInWindow.center
        val screen = IntArray(2); val window = IntArray(2)
        activity.window.decorView.getLocationOnScreen(screen)
        activity.window.decorView.getLocationInWindow(window)
        val start = inWindow + Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        drag = DragWorkflowTest.NativeDrag(start, mouse, false, { compose.waitForIdle() }, { compose.mainClock.advanceTimeBy(700) })
        val selectedBefore = model.selected.toSet()
        drag!!.start()
        compose.onNodeWithTag("drag-feedback").assertExists()
        assertEquals("Holding an icon must preserve the visible selection", selectedBefore, model.selected.toSet())
        val target = requireNotNull(dropBounds())
        drag!!.move(Offset(target.exactCenterX(), target.exactCenterY()))
        drag!!.drop()
        var report: JSONObject? = null
        compose.waitUntil(20_000) {
            report = runCatching { JSONObject(shell("run-as dev.findroid.interop cat files/drop-report.json")) }.getOrNull()
            report != null
        }
        val result = requireNotNull(report)
        File(activity.filesDir, "external-drop-${if (provider) "provider" else if (multiple) "multiple" else if (mouse) "mouse" else "touch"}.json").writeText(result.toString(2))
        assertTrue(result.toString(2), result.optBoolean("success"))
        assertNotEquals(activity.applicationInfo.uid, result.getInt("receiverUid"))
        for (key in listOf("readDeniedBeforeDrop", "unselectedReadDeniedBeforeDrop", "unselectedReadDeniedDuringDrop", "readDeniedAfterRelease", "sourceLocalStateHidden"))
            assertTrue("$key: $result", result.getBoolean(key))
        val received = result.getJSONArray("files")
        assertEquals(if (multiple) 2 else 1, received.length())
        for (i in 0 until received.length()) {
            val item = received.getJSONObject(i)
            val original = File(directory, item.getString("name"))
            assertTrue("Source must remain after an external drop", original.exists())
            assertEquals(original.length().toInt(), item.getInt("bytes"))
            assertEquals(MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") { "%02x".format(it) }, item.getString("sha256"))
            assertTrue(item.getBoolean("writeDenied"))
        }
        if (multiple) {
            val values = result.getJSONArray("mimeTypes")
            val types = (0 until values.length()).map(values::getString).toSet()
            assertTrue(types.toString(), "text/plain" in types && "image/png" in types)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(activity.filesDir, "external-drop-${if (multiple) "multiple" else "single"}.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun dropBounds(): Rect? {
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if (node == null) return null
            if (node.contentDescription?.toString() == "Drop files here") return Rect().also(node::getBoundsInScreen)
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.windows.firstNotNullOfOrNull { find(it.root) }
    }
}
