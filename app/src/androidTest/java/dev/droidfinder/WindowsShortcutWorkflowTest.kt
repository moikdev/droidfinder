@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.droidfinder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent as AndroidKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.*
import org.junit.Assert.*

class WindowsShortcutWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var fixture: File
    private lateinit var source: File
    private lateinit var destination: File
    private val longName =
        "Quarterly planning, presentation and implementation review notes for the complete tablet workspace.txt"

    @Before
    fun setup() {
        fixture =
            File(compose.activity.filesDir, "Workspace/Keyboard-${System.nanoTime()}").apply {
                mkdirs()
            }
        source = File(fixture, "Source").apply { mkdir() }
        destination = File(fixture, "Destination").apply { mkdir() }
        repeat(40) {
            File(source, "File${(it+1).toString().padStart(2,'0')}.txt")
                .writeText("Content ${it+1}")
        }
        File(source, longName).writeText("Long filename")
        File(source, "Project/Subfolder").mkdirs()
        File(source, "Project/Child.txt").writeText("child")
        File(source, "Project/Subfolder/Deep.txt").writeText("deep")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.closeOtherTabs(model.activeTab)
            model.hideNavigationTree()
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (!model.foldersFirst) model.toggleFoldersFirst()
            if (model.sort != SortBy.NAME) model.changeSort(SortBy.NAME)
            if (!model.ascending) model.changeSort(SortBy.NAME)
            model.navigate(source.path)
            model.clearSelection()
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(12_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun key(key: Key, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
        compose.onRoot().performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            if (alt) keyDown(Key.AltLeft)
            if (shift) keyDown(Key.ShiftLeft)
            pressKey(key)
            if (shift) keyUp(Key.ShiftLeft)
            if (alt) keyUp(Key.AltLeft)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
    }

    private fun file(name: String) =
        compose.onNode(
            hasText(name) and hasClickAction() and hasAnyAncestor(hasTestTag("file-browser")),
        )

    private fun select(name: String) {
        file(name).performScrollTo().performClick()
        compose.waitUntil(5000) { model.focused?.name == name }
    }

    @After
    fun cleanup() {
        compose.runOnIdle {
            model.transferJobs.filter { !it.terminal }.forEach { model.cancelQueuedTransfer(it.id) }
            if (model.busy != null) model.cancelTransfer()
        }
        compose.waitUntil(15_000) { model.busy == null }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.hideNavigationTree()
            model.changeMode(ViewMode.LIST)
            if (model.previewDetailsOnly) model.toggleInspector(false)
            if (model.previewVisible) model.togglePreview()
            model.togglePreview()
            model.navigate(model.workspace.path)
            model.closeOtherTabs(model.activeTab)
        }
        fixture.deleteRecursively()
    }

    @Test
    fun newTabButtonStaysNextToTabsAndReachableDuringOverflow() {
        val density = compose.activity.resources.displayMetrics.density
        val tab =
            compose.onNodeWithTag("tab-${model.tabs.single().id}").fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithContentDescription("New tab").fetchSemanticsNode().boundsInRoot
        assertTrue(plus.left >= tab.right)
        assertTrue("New tab should follow the last tab", (plus.left - tab.right) / density <= 12)
        compose.onNodeWithContentDescription("New tab").performClick()
        compose.waitUntil(5000) { model.tabs.size == 2 }
        compose.runOnIdle { model.switchTab(0) }
        compose.onNodeWithContentDescription("New tab").performClick()
        compose.waitUntil(5000) { model.tabs.size == 3 && model.activeTab == 2 }
        compose.runOnIdle { repeat(12) { model.newTab(atEnd = true) } }
        compose.onNodeWithContentDescription("New tab").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { model.tabs.size == 16 }
        compose.onNodeWithTag("tab-${model.tabs.last().id}").assertIsDisplayed()
    }

    @Test
    fun addressAliasesSelectThePathAndTextEditingDoesNotTrashFiles() {
        for ((key, ctrl, alt) in
            listOf(
                Triple(Key.D, false, true),
                Triple(Key.L, true, false),
                Triple(Key.F4, false, false),
            )) {
            key(key, ctrl = ctrl, alt = alt)
            val field = compose.onNode(hasContentDescription("Address bar") and hasSetTextAction())
            field.assertExists()
            if (key == Key.D) {
                field.assertIsFocused()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.waitForIdle(500, 5000)
                instrumentation.sendStringSync(destination.path)
                instrumentation.waitForIdleSync()
                field.assertTextEquals(destination.path)
            } else field.performTextReplacement(destination.path)
            key(Key.A, ctrl = true)
            assertTrue(File(source, "File01.txt").exists())
            key(Key.Enter)
            compose.waitUntil(10_000) {
                File(model.location).canonicalPath == destination.canonicalPath && !model.loading
            }
            compose.runOnIdle { model.navigate(source.path) }
            loaded()
        }
    }

    @Test
    fun windowsNumberedTabsAndViewStylesDoNotConflict() {
        compose.runOnIdle {
            model.newTab(destination.path, atEnd = true)
            model.newTab(source.path, atEnd = true)
        }
        key(Key.Two, ctrl = true)
        compose.runOnIdle { assertEquals(1, model.activeTab) }
        key(Key.Nine, ctrl = true)
        compose.runOnIdle { assertEquals(2, model.activeTab) }
        val digits =
            listOf(
                Key.One,
                Key.Two,
                Key.Three,
                Key.Four,
                Key.Five,
                Key.Six,
                Key.Seven,
                Key.Eight,
                Key.Nine,
            )
        digits.forEachIndexed { i, k ->
            key(k, ctrl = true, shift = true)
            compose.runOnIdle { assertEquals(i + 1, model.windowsLayout) }
        }
        key(Key.Six, ctrl = true, shift = true)
        key(Key.NumPadAdd, ctrl = true)
        compose.runOnIdle {
            assertTrue(model.fitColumns)
            assertEquals(ViewMode.LIST, model.mode)
        }
        key(Key.MoveEnd)
        compose.waitUntil(5000) { model.focused?.name == longName }
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNode(
                hasText(longName) and hasAnyAncestor(hasTestTag("file-browser")),
                useUnmergedTree = true,
            )
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(
            "Auto-fit must leave the filename untruncated",
            layouts.single().didOverflowWidth,
        )
    }

    @Test
    fun keyboardFocusSelectionScrollingAndTreeExpansionWork() {
        key(Key.MoveEnd)
        try {
            compose.waitUntil(5000) { model.focused?.name == longName }
        } catch (error: Throwable) {
            throw AssertionError(
                "End key: focus=${model.focused?.name} entries=${model.entries.size} mode=${model.mode} query=${model.query} ascending=${model.ascending} selection=${model.selected}",
                error,
            )
        }
        file(longName).assertIsDisplayed()
        key(Key.MoveHome)
        compose.waitUntil(5000) { model.focused?.name == "Project" }
        file("Project").assertIsDisplayed()
        for (view in listOf(ViewMode.ICONS, ViewMode.COLUMNS, ViewMode.GALLERY)) {
            compose.runOnIdle { model.changeMode(view) }
            key(Key.MoveEnd)
            file(longName).assertIsDisplayed()
            key(Key.MoveHome)
            file("Project").assertIsDisplayed()
        }
        compose.runOnIdle { model.changeMode(ViewMode.LIST) }
        val selected = model.selected
        key(Key.DirectionDown, ctrl = true)
        compose.runOnIdle {
            assertEquals(selected, model.selected)
            assertEquals("File01.txt", model.focused?.name)
        }
        key(Key.Spacebar, ctrl = true)
        compose.runOnIdle { assertEquals(2, model.selected.size) }
        key(Key.DirectionDown, ctrl = true, shift = true)
        compose.runOnIdle { assertEquals(3, model.selected.size) }
        key(Key.DirectionUp, ctrl = true, shift = true)
        compose.runOnIdle { assertEquals(2, model.selected.size) }
        key(Key.MoveHome)
        key(Key.NumPadAdd)
        compose.waitUntil(5000) { File(source, "Project").path in model.expandedFolders }
        file("Child.txt").assertIsDisplayed()
        key(Key.NumPadSubtract)
        compose.runOnIdle { assertFalse(File(source, "Project").path in model.expandedFolders) }
        key(Key.NumPadMultiply)
        compose.waitUntil(8000) { model.visibleEntries.any { it.name == "Deep.txt" } }
        key(Key.E, ctrl = true, shift = true)
        compose.waitUntil(8000) {
            model.navigationTreeVisible &&
                model.navigationRows().any { it.first.path == source.path }
        }
        compose
            .onNode(hasText("Source") and hasAnyAncestor(hasTestTag("navigation-tree")))
            .assertIsDisplayed()
    }

    @Test
    fun ctrlDUsesTrashAndShiftDeleteRequiresConfirmation() {
        select("File01.txt")
        key(Key.D, ctrl = true)
        compose.waitUntil(10_000) { model.busy == null && !File(source, "File01.txt").exists() }
        key(Key.Z, ctrl = true)
        compose.waitUntil(10_000) { model.busy == null && File(source, "File01.txt").exists() }
        select("File02.txt")
        key(Key.Delete, shift = true)
        compose.onNodeWithText("Delete permanently?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(File(source, "File02.txt").exists())
        key(Key.Delete, shift = true)
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(10_000) { model.busy == null && !File(source, "File02.txt").exists() }
        assertFalse(
            model.engine.trashItems().any {
                model.engine.originalPath(it) == File(source, "File02.txt").path
            },
        )
    }

    @Test
    fun previewDetailsAndSearchHelpAliasesWork() {
        select("File03.txt")
        if (!model.previewVisible) compose.runOnIdle { model.togglePreview() }
        key(Key.P, alt = true)
        compose.runOnIdle { assertFalse(model.previewVisible) }
        key(Key.P, alt = true, shift = true)
        compose.runOnIdle {
            assertTrue(model.previewVisible)
            assertTrue(model.previewDetailsOnly)
        }
        compose.onNodeWithText("Details").assertExists()
        key(Key.P, alt = true)
        compose.runOnIdle { assertFalse(model.previewDetailsOnly) }
        key(Key.Enter, alt = true)
        compose.onNodeWithText("Full path").assertExists()
        compose.onNodeWithText("Done").performClick()
        key(Key.F1)
        compose.onNodeWithTag("shortcut-guide").assertExists()
        compose.onNodeWithContentDescription("Close shortcut guide").performClick()
        key(Key.F3)
        compose.onNode(hasSetTextAction()).performTextInput("File04")
        compose.waitUntil(10_000) { !model.loading && model.entries.size == 1 }
        file("File04.txt").performClick()
        compose.waitUntil(5000) { model.focused?.name == "File04.txt" }
        key(Key.E, ctrl = true)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        key(Key.Escape)
        compose.waitUntil(10_000) { model.query.isEmpty() && !model.loading }
    }

    @Test
    fun ctrlNCreatesAnIndependentWindowWithSharedFileOperations() {
        val original = compose.activity
        val created = AtomicReference<MainActivity>()
        val app = original.application
        val callback =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is MainActivity && activity !== original) created.set(activity)
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) {}

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {}
            }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            key(Key.N, ctrl = true)
            compose.waitUntil(12_000) {
                created.get()?.model?.location?.let { File(it).canonicalPath } ==
                    source.canonicalPath
            }
            val window = created.get()
            compose.waitUntil(8000) {
                !window.model.loading && window.model.entries.any { it.name == "File01.txt" }
            }
            compose.runOnIdle {
                assertEquals(
                    File(source, "File01.txt").path,
                    window.model.entries.first { it.name == "File01.txt" }.path,
                )
                assertNotEquals(original.taskId, window.taskId)
                assertNotSame(model, window.model)
                assertSame(model.engine, window.model.engine)
                assertSame(model.documents, window.model.documents)
                window.model.navigate(destination.path)
                assertEquals(source.path, model.location)
                assertEquals(destination.path, window.model.location)
                window.model.toggleDualPane()
                assertFalse(model.dualPane)
                assertTrue(window.model.dualPane)
            }
            compose.waitUntil(5000) {
                compose.runOnIdle { window.window.decorView.hasWindowFocus() }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val now = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(
                AndroidKey(
                    now,
                    now,
                    AndroidKey.ACTION_DOWN,
                    AndroidKey.KEYCODE_F4,
                    0,
                    AndroidKey.META_ALT_ON,
                ),
            )
            instrumentation.sendKeySync(
                AndroidKey(
                    now,
                    SystemClock.uptimeMillis(),
                    AndroidKey.ACTION_UP,
                    AndroidKey.KEYCODE_F4,
                    0,
                    AndroidKey.META_ALT_ON,
                ),
            )
            compose.waitUntil(8000) { window.isDestroyed }
            compose.waitForIdle()
            assertEquals(source.path, model.location)
        } finally {
            created.get()?.let { window ->
                if (!window.isDestroyed)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { window.finish() }
            }
            app.unregisterActivityLifecycleCallbacks(callback)
        }
    }

    @Test
    fun hardwareRenameCreationAndMouseZoomUseWindowsCommands() {
        select("File05.txt")
        key(Key.F2)
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed with keys.txt")
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(10_000) {
            model.busy == null && File(source, "Renamed with keys.txt").exists()
        }
        key(Key.N, ctrl = true, shift = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("Created with keys")
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(10_000) {
            model.busy == null && File(source, "Created with keys").isDirectory
        }
        loaded()
        val initial = model.iconSize
        compose.onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        try {
            compose.onNodeWithTag("file-browser").performMouseInput {
                moveTo(center)
                scroll(1f)
            }
        } finally {
            compose.onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        }
        compose.runOnIdle {
            assertEquals(ViewMode.ICONS, model.mode)
            assertEquals((initial - 20).coerceIn(90, 200), model.iconSize)
        }
    }

    @Test
    fun closingAWindowKeepsItsQueuedTransferAndSharedUndoAlive() {
        val app = compose.activity.application as FinderApplication
        val id = "window-${java.util.UUID.randomUUID()}"
        val other = compose.runOnIdle { app.windowModel(id, source.path) }
        compose.runOnIdle {
            for (path in listOf("@recent", "@trash", "@tag:${model.tagDefinitions.first().name}")) {
                val virtualId = "virtual-${java.util.UUID.randomUUID()}"
                try {
                    assertEquals(path, app.windowModel(virtualId, path).location)
                } finally {
                    app.releaseWindow(virtualId)
                }
            }
        }
        val fixtureRoot =
            File(compose.activity.filesDir, "ProviderFixtures/ClosedWindow-${System.nanoTime()}")
                .apply { mkdirs() }
        val bytes = ByteArray(256 * 1024) { 17 }
        File(fixtureRoot, "Gate.bin").writeBytes(bytes)
        val gate = java.util.concurrent.CountDownLatch(1)
        dev.droidfinder.testing.FixtureDocumentsProvider.transferGate = gate
        dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened =
            java.util.concurrent.CountDownLatch(1)
        val tree =
            android.provider.DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${fixtureRoot.name}",
            )
        try {
            compose.runOnIdle {
                val request = listOf(Entry(File(source, "File01.txt")))
                other.shareRequested = request
                assertNull("Sharing targets only its requesting window", model.shareRequested)
                assertSame(request, other.secondaryPane.shareRequested)
                other.shareRequested = null
                model.connectLocation(tree, false)
            }
            compose.waitUntil(8000) { model.connectedLocations.any { it.grant == tree.toString() } }
            val path =
                android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        "root/${fixtureRoot.name}/Gate.bin",
                    )
                    .toString()
            compose.runOnIdle { other.transferPaths(listOf(path), destination.path, false) }
            assertTrue(
                dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened!!.await(
                    8,
                    java.util.concurrent.TimeUnit.SECONDS,
                ),
            )
            compose.runOnIdle { app.releaseWindow(id) }
            gate.countDown()
            compose.waitUntil(15_000) {
                model.busy == null && File(destination, "Gate.bin").exists()
            }
            assertArrayEquals(bytes, File(destination, "Gate.bin").readBytes())
            compose.runOnIdle { model.undo() }
            compose.waitUntil(10_000) {
                model.busy == null && !File(destination, "Gate.bin").exists()
            }
            assertTrue(File(fixtureRoot, "Gate.bin").exists())
        } finally {
            gate.countDown()
            compose.runOnIdle {
                if (model.busy != null) model.cancelTransfer()
                app.releaseWindow(id)
            }
            compose.waitUntil(15_000) { model.busy == null }
            compose.runOnIdle { model.disconnectLocation(tree.toString()) }
            dev.droidfinder.testing.FixtureDocumentsProvider.transferGate = null
            dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened = null
            fixtureRoot.deleteRecursively()
        }
    }
}
