@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.droidfinder

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

class DragWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var base: File
    private lateinit var target: File
    private lateinit var note: File
    private var native: NativeDrag? = null

    @Before
    fun setup() {
        base =
            File(compose.activity.filesDir, "Workspace/Dragging-${System.nanoTime()}").apply {
                mkdirs()
            }
        target = File(base, "Destination").apply { mkdir() }
        note = File(base, "Source.txt").apply { writeText("original drag payload") }
        File(target, "Existing.txt").writeText("existing")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.tabs
                .filter { it.pinned }
                .map { it.id }
                .forEach { id -> model.togglePinTab(model.tabs.indexOfFirst { it.id == id }) }
            model.closeOtherTabs(model.activeTab)
            model.navigate(base.path)
            model.updateQuery("")
            model.changeMode(ViewMode.LIST)
            model.changeIconSize(120)
            model.changeGrouping(GroupBy.NONE)
            model.changeDetailColumns(DetailColumns(adaptive = false))
            if (model.sort != SortBy.NAME || !model.ascending) model.changeSort(SortBy.NAME)
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        native?.cancel()
        compose.waitForIdle()
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.folderViews.values.keys
                .filter { pathWithin(it, folderViewKey(base.path)!!) }
                .forEach(model::forgetFolderView)
            model.navigate(model.workspace.path)
            model.closeOtherTabs(model.activeTab)
        }
        loaded()
        base.deleteRecursively()
    }

    private fun file(name: String) =
        compose.onNode(
            hasText(name) and hasClickAction() and hasAnyAncestor(hasTestTag("file-browser")),
        )

    private fun point(name: String) = file(name).fetchSemanticsNode().boundsInWindow.center

    private fun start(
        mouse: Boolean = false,
        copy: Boolean = false,
        entry: File = note,
    ): NativeDrag {
        compose.runOnIdle { model.select(model.entries.first { it.path == entry.path }) }
        compose.waitForIdle()
        val start =
            if (mouse) {
                // The icon owns mouse drag recognition, the row owns selection/context gestures.
                compose
                    .onNodeWithTag("drag-icon-${entry.path}", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInWindow
                    .center
            } else
                compose
                    .onNodeWithContentDescription("Hold to drag selected files into a folder")
                    .fetchSemanticsNode()
                    .boundsInWindow
                    .center
        return NativeDrag(
                start,
                mouse,
                copy,
                { compose.waitForIdle() },
                { compose.mainClock.advanceTimeBy(700) },
            )
            .also {
                native = it
                it.start()
            }
    }

    @Test
    fun touchSpringFolderDropUsesOriginalPayloadAndCanUndo() {
        val drag = start()
        drag.move(point("Destination"))
        compose.waitUntil(5000) { model.location == target.path && !model.loading }
        compose.onNodeWithTag("drag-feedback").assertExists()
        val browser = compose.onNodeWithTag("file-browser").fetchSemanticsNode().boundsInWindow
        drag.move(Offset(browser.center.x, browser.bottom - 100f))
        drag.drop()
        compose.waitUntil(15_000) {
            !note.exists() && File(target, note.name).exists() && model.busy == null
        }
        assertEquals("original drag payload", File(target, note.name).readText())
        assertEquals(target.path, model.location)
        compose.runOnIdle { model.undo() }
        compose.waitUntil(15_000) {
            note.exists() && !File(target, note.name).exists() && model.busy == null
        }
    }

    @Test
    fun cancellingSpringFolderRestoresHistorySelectionAndView() {
        compose.runOnIdle {
            model.changeMode(ViewMode.ICONS)
            model.rememberFolderView(true)
        }
        val original = compose.runOnIdle { model.captureSpringNavigation() }
        val drag = start()
        drag.move(point("Destination"))
        compose.waitUntil(5000) { model.location == target.path && !model.loading }
        drag.cancel()
        compose.waitUntil(5000) { model.location == base.path && !model.loading }
        assertEquals(original.tabs.map { it.history }, model.tabs.map { it.history })
        assertEquals(setOf(note.path), model.selected)
        assertEquals(ViewMode.ICONS, model.mode)
        assertTrue(note.exists())
        assertFalse(File(target, note.name).exists())
        compose.runOnIdle { model.forgetFolderView(base.path) }
    }

    @Test
    fun hoveringTabSwitchesAndCancellationReturnsToOriginalTab() {
        compose.runOnIdle { model.newTab(target.path, activate = false) }
        val original = model.tabs[model.activeTab].id
        val destination = model.tabs.last().id
        val tabPoint =
            compose.onNodeWithTag("tab-$destination").fetchSemanticsNode().boundsInWindow.center
        val drag = start()
        drag.move(tabPoint)
        compose.waitUntil(5000) { model.tabs[model.activeTab].id == destination }
        drag.cancel()
        compose.waitUntil(5000) { model.tabs[model.activeTab].id == original && !model.loading }
        assertEquals(setOf(note.path), model.selected)
        assertTrue(note.exists())
    }

    @Test
    fun selfDropIsRejectedWithoutNavigationOrQueuedWork() {
        val jobs = model.transferJobs.size
        val drag = start(entry = target)
        drag.move(point("Destination"))
        compose.waitUntil(3000) {
            compose
                .onAllNodesWithText("A folder can't be put inside itself.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        SystemClock.sleep(1000)
        assertEquals(base.path, model.location)
        drag.drop()
        compose.waitForIdle()
        assertEquals(jobs, model.transferJobs.size)
        assertTrue(target.isDirectory)
    }

    @Test
    fun listEdgesScrollNewTargetsIntoViewAndStopInTheCenter() {
        repeat(36) { File(base, "Item%02d.txt".format(it)).writeText("$it") }
        compose.runOnIdle { model.refresh() }
        loaded()
        val drag = start()
        val bounds = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInWindow
        drag.move(Offset(bounds.center.x, bounds.bottom - 5f))
        compose.waitUntil(7000) {
            compose
                .onAllNodes(hasText("Item35.txt") and hasAnyAncestor(hasTestTag("file-browser")))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        drag.move(bounds.center)
        SystemClock.sleep(200)
        val before =
            compose
                .onNodeWithTag("browser-list")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                .value()
        SystemClock.sleep(350)
        val after =
            compose
                .onNodeWithTag("browser-list")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                .value()
        assertEquals(before, after, .01f)
        drag.cancel()
        assertTrue(note.exists())
    }

    @Test
    fun ctrlMouseDragCopiesAndDefaultMouseDragMoves() {
        var drag = start(mouse = true, copy = true)
        drag.move(point("Destination"))
        compose.waitUntil(5000) { model.location == target.path && !model.loading }
        var browser = compose.onNodeWithTag("file-browser").fetchSemanticsNode().boundsInWindow
        drag.move(Offset(browser.center.x, browser.bottom - 100f))
        drag.drop()
        compose.waitUntil(15_000) { model.busy == null && File(target, note.name).exists() }
        assertTrue("Ctrl must preserve the source", note.exists())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(15_000) { model.busy == null && !File(target, note.name).exists() }
        compose.runOnIdle { model.navigate(base.path) }
        loaded()
        drag = start(mouse = true)
        drag.move(point("Destination"))
        compose.waitUntil(5000) { model.location == target.path && !model.loading }
        browser = compose.onNodeWithTag("file-browser").fetchSemanticsNode().boundsInWindow
        drag.move(Offset(browser.center.x, browser.bottom - 100f))
        drag.drop()
        compose.waitUntil(15_000) {
            model.busy == null && File(target, note.name).exists() && !note.exists()
        }
        assertEquals("original drag payload", File(target, note.name).readText())
    }

    @Test
    fun explicitNavigationIsNotRewoundByAnOlderSpringSnapshot() {
        compose.runOnIdle {
            val original = model.captureSpringNavigation()
            model.navigate(target.path)
            val temporary = model.captureSpringNavigation()
            model.navigate(base.path)
            val explicit = model.tabs.toList()
            model.restoreSpringNavigation(original, temporary)
            assertEquals(explicit, model.tabs)
        }
    }

    @Test
    fun gridAndGalleryEdgesRevealOffscreenEntries() {
        repeat(48) { File(base, "Item%02d.txt".format(it)).writeText("$it") }
        compose.runOnIdle { model.refresh() }
        loaded()
        for (mode in listOf(ViewMode.ICONS, ViewMode.GALLERY)) {
            compose.runOnIdle { model.changeMode(mode) }
            loaded()
            val targetName = if (mode == ViewMode.ICONS) "Item47.txt" else "Item20.txt"
            compose
                .onNode(hasText(targetName) and hasAnyAncestor(hasTestTag("file-browser")))
                .assertDoesNotExist()
            val drag = start()
            val viewport =
                compose
                    .onNodeWithTag(if (mode == ViewMode.ICONS) "browser-grid" else "gallery-strip")
                    .fetchSemanticsNode()
                    .boundsInWindow
            val edge =
                if (mode == ViewMode.ICONS) Offset(viewport.center.x, viewport.bottom - 5f)
                else Offset(viewport.right - 5f, viewport.center.y)
            drag.move(edge)
            try {
                compose.waitUntil(12_000) {
                    compose
                        .onAllNodes(
                            hasText(targetName) and hasAnyAncestor(hasTestTag("file-browser")),
                        )
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            } catch (error: Throwable) {
                throw AssertionError(
                    "Edge scrolling in $mode at $edge, viewport=$viewport; ${compose.onRoot().printToString()}",
                    error,
                )
            }
            drag.cancel()
            compose.waitForIdle()
            assertTrue(note.exists())
        }
    }

    @Test
    fun aFolderRevealedByEdgeScrollingAcceptsTheSameDrag() {
        repeat(36) { File(base, "Folder%02d".format(it)).mkdir() }
        compose.runOnIdle { model.refresh() }
        loaded()
        val drag = start()
        val viewport = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInWindow
        drag.move(Offset(viewport.center.x, viewport.bottom - 5f))
        compose.waitUntil(7000) {
            file(note.name).isDisplayed() &&
                file(note.name).fetchSemanticsNode().boundsInWindow.bottom <= viewport.bottom
        }
        // Stop edge motion before resolving the row. Lazy composition also exposes
        // prefetched rows whose center is still outside the viewport.
        drag.moveDirect(viewport.center)
        SystemClock.sleep(100)
        drag.moveDirect(point("Folder35"))
        compose.waitUntil(5000) {
            compose
                .onAllNodesWithText("Move 1 item to Folder35")
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                (compose.onAllNodesWithText("Hold to open").fetchSemanticsNodes().isNotEmpty() ||
                    compose
                        .onAllNodesWithText("Release to move")
                        .fetchSemanticsNodes()
                        .isNotEmpty())
        }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "drag-edge-before-drop.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val feedback = compose.onNodeWithTag("drag-feedback").printToString()
        val before = model.transferJobs.size
        drag.drop()
        val moved = File(base, "Folder35/${note.name}")
        try {
            compose.waitUntil(15_000) { moved.exists() && !note.exists() && model.busy == null }
        } catch (error: Throwable) {
            throw AssertionError(
                "Drop failed: location=${model.location}, message=${model.message}, jobs=${model.transferJobs.drop(before).map { it.destination to it.state }}, payloadExists=${note.exists()}, populated=${base.listFiles()?.filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true }?.map { it.name }}; before drop: $feedback",
                error,
            )
        }
        assertEquals("original drag payload", moved.readText())
    }

    @Test
    fun crossPaneDropRestoresAnUnusedSpringLoadedOriginPane() {
        compose.runOnIdle {
            model.toggleDualPane()
            model.secondaryPane.navigate(target.path)
            model.secondaryPane.changeMode(ViewMode.LIST)
        }
        loaded()
        compose.waitUntil(15_000) { !model.secondaryPane.loading }
        val drag = start()
        val destination =
            compose
                .onNode(
                    hasText("Destination") and
                        hasClickAction() and
                        hasAnyAncestor(hasTestTag("pane-primary")) and
                        hasAnyAncestor(hasTestTag("file-browser")),
                )
                .fetchSemanticsNode()
                .boundsInWindow
                .center
        drag.move(destination)
        compose.waitUntil(5000) { model.location == target.path && !model.loading }
        val right =
            compose
                .onNode(hasTestTag("file-browser") and hasAnyAncestor(hasTestTag("pane-secondary")))
                .fetchSemanticsNode()
                .boundsInWindow
        drag.move(Offset(right.center.x, right.bottom - 100f))
        drag.drop()
        compose.waitUntil(15_000) {
            model.location == base.path &&
                File(target, note.name).exists() &&
                !note.exists() &&
                model.busy == null
        }
        assertEquals(target.path, model.secondaryPane.location)
        compose.runOnIdle { model.toggleDualPane() }
    }

    @Test
    fun fittedColumnResizeStartsAtTheDisplayedWidth() {
        compose.runOnIdle {
            model.changeDetailColumns(DetailColumns(dateWidth = 300, adaptive = false))
            model.fitColumnsToContents()
        }
        compose.waitForIdle()
        val date = compose.onNodeWithContentDescription("Resize Date modified column")
        val size = compose.onNodeWithContentDescription("Resize Size column")
        val displayed =
            (size.fetchSemanticsNode().boundsInWindow.center.x -
                date.fetchSemanticsNode().boundsInWindow.center.x) /
                compose.activity.resources.displayMetrics.density
        assertTrue(displayed < 250f)
        date.performTouchInput {
            down(center)
            moveBy(Offset(-90f, 0f))
            up()
        }
        assertTrue(
            "Start at the displayed $displayed dp, not the stored 300 dp: ${model.detailColumns.dateWidth}",
            model.detailColumns.dateWidth > displayed &&
                model.detailColumns.dateWidth < displayed + 61f,
        )
        assertFalse(model.fitColumns)
    }

    @Test
    fun singlePixelColumnMotionAccumulatesAcrossInputEvents() {
        compose.onNodeWithContentDescription("Resize Date modified column").performTouchInput {
            down(center)
            moveBy(Offset(-30f, 0f))
            repeat(120) { moveBy(Offset(-1f, 0f), 16) }
            up()
        }
        assertTrue(
            "Sub-dp event deltas must accumulate: ${model.detailColumns.dateWidth}",
            model.detailColumns.dateWidth >= 225,
        )
    }

    private class NativeDrag(
        private val origin: Offset,
        private val mouse: Boolean,
        private val copy: Boolean,
        private val settle: () -> Unit,
        private val longPress: () -> Unit,
    ) {
        private var at = origin
        private var down = 0L
        private var running = false
        private val instrumentation = InstrumentationRegistry.getInstrumentation()

        fun start() {
            down = SystemClock.uptimeMillis()
            running = true
            send(MotionEvent.ACTION_DOWN, origin)
            if (!mouse) {
                longPress()
                SystemClock.sleep(750)
                settle()
            }
            move(origin + Offset(25f, 0f))
        }

        fun move(end: Offset) {
            val from = at
            repeat(10) { i -> send(MotionEvent.ACTION_MOVE, from + (end - from) * ((i + 1) / 10f)) }
            at = end
        }

        fun moveDirect(end: Offset) {
            send(MotionEvent.ACTION_MOVE, end)
            at = end
        }

        fun drop() {
            if (!running) return
            send(MotionEvent.ACTION_UP, at)
            running = false
        }

        fun cancel() {
            if (!running) return
            send(MotionEvent.ACTION_CANCEL, at)
            running = false
        }

        private fun send(action: Int, point: Offset) {
            val event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    1,
                    arrayOf(
                        MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType =
                                if (mouse) MotionEvent.TOOL_TYPE_MOUSE
                                else MotionEvent.TOOL_TYPE_FINGER
                        },
                    ),
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = point.x
                            y = point.y
                            pressure =
                                if (
                                    action == MotionEvent.ACTION_UP ||
                                        action == MotionEvent.ACTION_CANCEL
                                )
                                    0f
                                else 1f
                            size = 1f
                        },
                    ),
                    if (copy) android.view.KeyEvent.META_CTRL_ON else 0,
                    if (
                        mouse &&
                            action != MotionEvent.ACTION_UP &&
                            action != MotionEvent.ACTION_CANCEL
                    )
                        MotionEvent.BUTTON_PRIMARY
                    else 0,
                    1f,
                    1f,
                    0,
                    0,
                    if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                instrumentation.waitForIdleSync()
                SystemClock.sleep(20)
                settle()
            } finally {
                event.recycle()
            }
        }
    }
}
