@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.droidfinder

import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.droidfinder.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val left
        get() = compose.activity.model

    private val right
        get() = left.secondaryPane

    private lateinit var base: File
    private lateinit var source: File
    private lateinit var target: File

    private fun setup() {
        base =
            File(compose.activity.filesDir, "Workspace/Panes-${System.nanoTime()}").apply {
                mkdirs()
            }
        source = File(base, "Source").apply { mkdir() }
        target = File(base, "Destination").apply { mkdir() }
        File(source, "Left.txt").writeText("left content")
        File(target, "Right.txt").writeText("right content")
        compose.runOnIdle {
            if (!left.dualPane) left.toggleDualPane()
            left.activatePane()
            left.navigate(source.path)
            right.navigate(target.path)
            left.changeDensity(FinderDensity.COMFORTABLE)
            left.changeGrouping(GroupBy.NONE)
            right.changeGrouping(GroupBy.NONE)
            left.changeMode(ViewMode.LIST)
            right.changeMode(ViewMode.LIST)
            for (pane in listOf(left, right)) {
                if (pane.inspectorRequested) {
                    if (pane.previewVisible) pane.togglePreview()
                    pane.togglePreview()
                }
            }
            left.updateQuery("")
            right.updateQuery("")
        }
        try {
            compose.waitUntil(10_000) {
                !left.loading &&
                    !right.loading &&
                    left.entries.any { it.path == File(source, "Left.txt").path } &&
                    right.entries.any { it.path == File(target, "Right.txt").path }
            }
        } catch (error: Throwable) {
            throw AssertionError(
                "Left: ${left.location} loading=${left.loading} error=${left.listingError} rules=${left.effectiveSearchRules} entries=${left.entries.map { it.path }}; Right: ${right.location} loading=${right.loading} error=${right.listingError} rules=${right.effectiveSearchRules} entries=${right.entries.map { it.path }}",
                error,
            )
        }
    }

    private fun cleanup() {
        compose.runOnIdle {
            left.transferJobs.filter { !it.terminal }.forEach { left.cancelQueuedTransfer(it.id) }
            if (left.busy != null) left.cancelTransfer()
        }
        compose.waitUntil(15_000) { left.busy == null }
        compose.runOnIdle {
            if (left.dualPane) left.toggleDualPane()
            left.activatePane()
            left.navigate(left.workspace.path)
            right.navigate(left.workspace.path)
            left.changePaneRatio(.5f)
            if (left.panesReversed) left.swapPanes()
        }
        base.deleteRecursively()
    }

    private fun file(pane: String, name: String) =
        compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("pane-$pane")))

    @Test
    fun panesShareOperationsButNavigateSearchAndSelectIndependently() {
        setup()
        try {
            assertSame(left.engine, right.engine)
            assertSame(left.documents, right.documents)
            file("primary", "Left.txt").performClick()
            compose.waitUntil(5000) { File(source, "Left.txt").path in left.selected }
            assertSame(left, left.activePaneModel)
            file("secondary", "Right.txt").performClick()
            compose.waitUntil(5000) { File(target, "Right.txt").path in right.selected }
            compose.runOnIdle {
                assertSame(right, left.activePaneModel)
                assertEquals(setOf(File(source, "Left.txt").path), left.selected)
                assertEquals(setOf(File(target, "Right.txt").path), right.selected)
                left.updateQuery("Left")
                right.updateQuery("Right")
                right.newTab(base.path)
                assertEquals("Left", left.query)
                assertEquals("", right.query)
                assertEquals(source.path, left.location)
                assertEquals(base.path, right.location)
                right.closeTab(right.activeTab)
            }
            compose.waitUntil(10_000) { right.query == "Right" && !right.loading }
            assertEquals("Left", left.query)
            assertEquals(target.path, right.location)
        } finally {
            cleanup()
        }
    }

    @Test
    fun keyboardClipboardAndSharedUndoFollowTheActivePane() {
        setup()
        try {
            file("secondary", "Right.txt").performClick()
            compose.waitUntil(5000) { File(target, "Right.txt").path in right.selected }
            compose.onRoot().performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.C)
                keyUp(Key.CtrlLeft)
            }
            compose.runOnIdle {
                assertEquals(listOf(File(target, "Right.txt").path), left.clipboard!!.paths)
            }
            compose.onRoot().performKeyInput { pressKey(Key.F6) }
            compose.runOnIdle { assertSame(left, left.activePaneModel) }
            compose.onRoot().performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.V)
                keyUp(Key.CtrlLeft)
            }
            compose.waitUntil(15_000) { left.busy == null && File(source, "Right.txt").exists() }
            assertEquals("right content", File(source, "Right.txt").readText())
            compose.runOnIdle { right.undo() }
            compose.waitUntil(10_000) { right.busy == null && !File(source, "Right.txt").exists() }
            assertTrue(File(target, "Right.txt").exists())
        } finally {
            cleanup()
        }
    }

    @Test
    fun queuedCrossPaneCopyKeepsOriginalDestinationAndSourceSelection() {
        setup()
        val fixture =
            File(compose.activity.filesDir, "ProviderFixtures/Panes-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(fixture, "Gate.bin").writeBytes(ByteArray(256 * 1024) { 9 })
        val gate = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = CountDownLatch(1)
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${fixture.name}",
            )
        try {
            compose.runOnIdle { left.connectLocation(tree, false) }
            compose.waitUntil(10_000) {
                left.connectedLocations.any { it.grant == tree.toString() }
            }
            val path =
                DocumentsContract.buildDocumentUriUsingTree(tree, "root/${fixture.name}/Gate.bin")
                    .toString()
            compose.runOnIdle {
                left.navigate(source.path)
                left.transferPaths(listOf(path), target.path, false)
            }
            assertTrue(FixtureDocumentsProvider.gateOpened!!.await(8, TimeUnit.SECONDS))
            compose.waitUntil(10_000) {
                !left.loading && left.entries.any { it.name == "Left.txt" }
            }
            compose.runOnIdle {
                left.select(left.entries.first { it.name == "Left.txt" })
                right.select(right.entries.first { it.name == "Right.txt" })
                left.transferToOtherPane(false)
                right.createFolder("Must-not-race")
                assertNotNull(left.busy)
                assertEquals(left.busy, right.busy)
                assertSame(left.transferJobs, right.transferJobs)
                right.navigate(base.path)
                right.activatePane()
            }
            gate.countDown()
            compose.waitUntil(15_000) {
                left.busy == null && left.transferJobs.none { !it.terminal }
            }
            assertEquals("left content", File(target, "Left.txt").readText())
            assertFalse(File(base, "Left.txt").exists())
            assertFalse(File(target, "Must-not-race").exists())
            assertTrue(left.selected.isEmpty())
            assertEquals(base.path, right.location)
        } finally {
            gate.countDown()
            FixtureDocumentsProvider.transferGate = null
            FixtureDocumentsProvider.gateOpened = null
            compose.runOnIdle { left.disconnectLocation(tree.toString()) }
            cleanup()
            fixture.deleteRecursively()
        }
    }

    @Test
    fun moveAndUndoRemapTheOtherPanesNavigationAndMetadata() {
        setup()
        val folder = File(source, "Project").apply { mkdir() }
        val note = File(folder, "Notes.txt").apply { writeText("unchanged") }
        try {
            compose.runOnIdle {
                left.refresh()
                right.navigate(folder.path)
            }
            try {
                compose.waitUntil(10_000) {
                    !left.loading && !right.loading && right.entries.any { it.path == note.path }
                }
            } catch (error: Throwable) {
                throw AssertionError(
                    "Project listing: exists=${note.exists()} left=${left.location} ${left.loading} ${left.listingError} right=${right.location} ${right.loading} ${right.listingError} rules=${right.effectiveSearchRules} entries=${right.entries.map { it.path }}",
                    error,
                )
            }
            compose.runOnIdle {
                right.select(right.entries.first { it.path == note.path })
                right.tag("Red")
                left.transferPaths(listOf(folder.path), target.path, true)
            }
            val moved = File(target, "Project/Notes.txt")
            compose.waitUntil(15_000) {
                left.busy == null && moved.exists() && right.location == moved.parent
            }
            assertEquals(setOf(moved.path), right.selected)
            assertTrue("Red" in left.tagAssignments[moved.path].orEmpty())
            compose.runOnIdle { right.undo() }
            compose.waitUntil(15_000) {
                right.busy == null && note.exists() && right.location == folder.path
            }
            assertTrue("Red" in right.tagAssignments[note.path].orEmpty())
            assertFalse(moved.exists())
        } finally {
            cleanup()
        }
    }

    @Test
    fun narrowWorkspaceSwitchesPanesAndKeepsResizeSwapAndNavigation() {
        setup()
        try {
            compose.runOnIdle {
                left.changePaneRatio(.63f)
                left.swapPanes()
                compose.activity.setContent {
                    Box(Modifier.width(640.dp)) {
                        FinderApp(left, {}, {}, {}, {}, {})
                    }
                }
            }
            compose.onNodeWithTag("pane-primary").assertExists()
            compose.onNodeWithTag("pane-secondary").assertDoesNotExist()
            compose.onNodeWithTag("pane-switch-secondary").performClick()
            compose.onNodeWithTag("pane-secondary").assertExists()
            compose.onNodeWithTag("pane-primary").assertDoesNotExist()
            file("secondary", "Right.txt").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                assertTrue(left.dualPane)
                assertTrue(left.panesReversed)
                assertEquals(.63f, left.paneRatio, .001f)
                assertSame(right, left.activePaneModel)
                assertEquals(source.path, left.location)
                assertEquals(target.path, right.location)
                assertTrue(
                    compose.activity
                        .getSharedPreferences("finder", 0)
                        .getBoolean("dualPane", false),
                )
                assertTrue(
                    compose.activity
                        .getSharedPreferences("finder-pane-secondary", 0)
                        .getString("tabs", "")!!
                        .contains("Destination"),
                )
            }
        } finally {
            cleanup()
        }
    }

    @Test
    fun comparisonDistinguishesUnverifiedAndEqualContentsAndUsesNormalCopyQueue() {
        setup()
        try {
            File(source, "Same.txt").writeText("same")
            File(target, "Same.txt").writeText("same")
            File(source, "Changed.txt").writeText("old!")
            File(target, "Changed.txt").writeText("new!")
            compose.runOnIdle {
                left.refresh()
                right.refresh()
                left.activatePane()
            }
            compose.waitUntil(10_000) { !left.loading && !right.loading }
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.onNodeWithText("Compare folders…").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Contents not checked").fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithText("Verified identical").assertCountEquals(0)
            compose.onNodeWithTag("verify-comparison").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Verified identical").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText("Different contents").fetchSemanticsNodes().size == 1
            }
            compose
                .onNode(hasText("Left.txt") and hasAnyAncestor(hasTestTag("folder-comparison")))
                .performClick()
            compose.onNodeWithText("Copy left → right").performClick()
            compose.waitUntil(15_000) { left.busy == null && File(target, "Left.txt").exists() }
            assertEquals("left content", File(target, "Left.txt").readText())
            assertTrue(File(source, "Left.txt").exists())
            assertEquals(TransferState.COMPLETED, left.transferJobs.last().state)
        } finally {
            cleanup()
        }
    }

    @Test
    fun rightClickInPassivePaneActivatesItAcrossAllViews() {
        setup()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val window = compose.activity.window
        val callback = window.callback
        compose.runOnIdle {
            window.callback =
                object : android.view.Window.Callback by callback {
                    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                        events +=
                            "touch ${event.actionMasked} ${event.x},${event.y} buttons=${event.buttonState}"
                        return callback.dispatchTouchEvent(event)
                    }

                    override fun dispatchGenericMotionEvent(
                        event: android.view.MotionEvent,
                    ): Boolean {
                        events +=
                            "generic ${event.actionMasked} ${event.x},${event.y} buttons=${event.buttonState}"
                        return callback.dispatchGenericMotionEvent(event)
                    }
                }
        }
        try {
            // Repeat dialog-to-browser transitions, including the bottom gallery strip.
            for (view in List(3) { ViewMode.entries }.flatten()) {
                compose.runOnIdle {
                    left.activatePane()
                    right.clearSelection()
                    right.changeMode(view)
                }
                compose.waitForIdle()
                compose.waitUntil(5000) {
                    compose.runOnIdle { compose.activity.window.decorView.hasWindowFocus() }
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .waitForIdle(500, 5000)
                compose.waitForIdle()
                val point =
                    compose
                        .onNode(
                            hasText("Right.txt") and
                                hasAnyAncestor(hasTestTag("pane-secondary")) and
                                hasAnyAncestor(hasClickAction()),
                            useUnmergedTree = true,
                        )
                        .fetchSemanticsNode()
                        .boundsInWindow
                        .center
                events.clear()
                NativeMouse.secondaryClick(point) { compose.waitForIdle() }
                try {
                    compose.waitUntil(5000) {
                        left.activePaneModel === right && right.focused?.name == "Right.txt"
                    }
                } catch (error: Throwable) {
                    val automation =
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                            .uiAutomation
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            automation.executeShellCommand(
                                "screencap -p /sdcard/droidfinder-passive-context-failure.png",
                            ),
                        )
                        .use { it.readBytes() }
                    val input =
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                                automation.executeShellCommand("dumpsys input"),
                            )
                            .use { it.readBytes().toString(Charsets.UTF_8) }
                    throw AssertionError(
                        "Passive-pane right click in $view: active=${left.activePaneModel.paneId}, selected=${right.selected}, point=$point; events=$events; input=${input.lines().filter { "Taskbar" in it || "name=" in it || "TouchStates" in it }}; ${compose.onRoot().printToString()}",
                        error,
                    )
                }
                compose.onNodeWithTag("file-context-menu").assertExists()
                compose.onNodeWithText("Get info").performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.OnClick,
                ) {
                    it()
                }
                compose.onNodeWithText("Done").performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.OnClick,
                ) {
                    it()
                }
            }
        } finally {
            compose.runOnIdle { window.callback = callback }
            cleanup()
        }
    }

    @Test
    fun twoPaneWindowProtectsFilenameSpaceAndDividerResizesWithinMinimums() {
        setup()
        try {
            compose.runOnIdle {
                left.changePaneRatio(.3f)
                compose.activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalFinderDensity provides FinderDensity.COMFORTABLE,
                    ) {
                        androidx.compose.material3.MaterialTheme {
                            Box(Modifier.width(820.dp).height(600.dp)) {
                                FinderWorkspace(left, { _, _ -> }, {}, {}, {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
            val scale = compose.activity.resources.displayMetrics.density
            val before =
                compose.onNodeWithTag("pane-primary").fetchSemanticsNode().boundsInRoot.width /
                    scale
            val other =
                compose.onNodeWithTag("pane-secondary").fetchSemanticsNode().boundsInRoot.width /
                    scale
            assertTrue("Primary pane must retain usable filename width", before >= 359f)
            assertTrue("Secondary pane must retain usable filename width", other >= 359f)
            compose.onNodeWithContentDescription("Resize panes").performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(60f * scale, 0f))
                up()
            }
            val after =
                compose.onNodeWithTag("pane-primary").fetchSemanticsNode().boundsInRoot.width /
                    scale
            assertTrue("Dragging the divider resizes the primary browser", after > before + 20f)
            assertTrue(
                compose.onNodeWithTag("pane-secondary").fetchSemanticsNode().boundsInRoot.width /
                    scale >= 359f,
            )
        } finally {
            cleanup()
        }
    }
}
