@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*

class NavigationWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var base: File
    private lateinit var source: File
    private lateinit var target: File
    private val layouts = mutableListOf<String>()

    @Before
    fun setup() {
        base =
            File(compose.activity.filesDir, "Workspace/Navigation-${System.nanoTime()}").apply {
                mkdirs()
            }
        source = File(base, "Source").apply { mkdir() }
        target = File(base, "Destination").apply { mkdir() }
        repeat(200) {
            File(source, "File${it.toString().padStart(3,'0')}.txt").writeText("File $it")
        }
        File(source, "Project/Subfolder").mkdirs()
        File(source, "Project/Subfolder/Deep.txt").writeText("deep")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            for (pane in listOf(model, model.secondaryPane)) {
                pane.tabs
                    .filter { it.pinned }
                    .map { it.id }
                    .forEach { id -> pane.togglePinTab(pane.tabs.indexOfFirst { it.id == id }) }
                if (pane.previewVisible) pane.togglePreview()
            }
            model.activatePane()
            model.closeOtherTabs(model.activeTab)
            model.changeMode(ViewMode.LIST)
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME) model.changeSort(SortBy.NAME)
            if (!model.ascending) model.changeSort(SortBy.NAME)
            if (!model.foldersFirst) model.toggleFoldersFirst()
            model.navigate(source.path)
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun file(name: String) =
        compose.onNode(
            hasText(name) and hasClickAction() and hasAnyAncestor(hasTestTag("file-browser")),
        )

    private fun key(k: Key, ctrl: Boolean = false) {
        compose.onRoot().performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            pressKey(k)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
    }

    private fun recreateModel(): Pair<FinderModel, ViewModelStore> {
        val store = ViewModelStore()
        val restored = compose.runOnIdle {
            ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(
                        modelClass: Class<T>,
                    ): T {
                        @Suppress("UNCHECKED_CAST")
                        return FinderModel(compose.activity.application, model.session) as T
                    }
                },
            )[FinderModel::class.java]
        }
        compose.waitUntil(10_000) { !restored.loading }
        return restored to store
    }

    @After
    fun cleanup() {
        compose.runOnIdle {
            model.transferJobs.filter { !it.terminal }.forEach { model.cancelQueuedTransfer(it.id) }
            if (model.busy != null) model.cancelTransfer()
        }
        compose.waitUntil(15_000) { model.busy == null }
        compose.runOnIdle {
            layouts.forEach(model::removeWorkspace)
            for (pane in listOf(model, model.secondaryPane)) {
                pane.tabs
                    .filter { it.pinned }
                    .map { it.id }
                    .forEach { id -> pane.togglePinTab(pane.tabs.indexOfFirst { it.id == id }) }
                pane.navigate(model.workspace.path)
                pane.closeOtherTabs(pane.activeTab)
            }
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.changeColumnWidth(240)
            if (model.sort != SortBy.NAME) model.changeSort(SortBy.NAME)
            if (!model.ascending) model.changeSort(SortBy.NAME)
            if (!model.previewVisible) model.togglePreview()
            model.changePaneRatio(.5f)
            if (model.panesReversed) model.swapPanes()
            model.flushNavigationState()
        }
        // Drain writes before removing the private test directories.
        runBlocking { model.session.workspaceMutex.withLock {} }
        base.deleteRecursively()
    }

    @Test
    fun switchingTabsRestoresSelectionIndependentFocusAndTheActualListViewport() {
        val selected = model.entries[83]
        compose.runOnIdle {
            model.select(selected)
            model.moveKeyboardFocus(1)
        }
        compose.waitForIdle()
        val anchor = model.entries[120].name
        compose.onNodeWithTag("browser-list").performScrollToIndex(120)
        compose.waitForIdle()
        val before = file(anchor).fetchSemanticsNode().boundsInRoot.top
        val lead = model.focused!!.path
        compose.runOnIdle { model.newTab(target.path) }
        loaded()
        compose.runOnIdle { model.switchTab(0) }
        loaded()
        assertEquals(setOf(selected.path), model.selected)
        assertEquals(lead, model.focused!!.path)
        file(anchor).assertIsDisplayed()
        assertEquals(before, file(anchor).fetchSemanticsNode().boundsInRoot.top, 2f)
        val point = compose.runOnIdle {
            model.savedViewport(model.tabs[0].id, model.viewportKey("list"))
        }
        assertEquals(model.entries[120].path, point!!.path)
    }

    @Test
    fun iconGalleryAndColumnViewportsAreIndependentPerTab() {
        for ((mode, tag) in
            listOf(
                ViewMode.ICONS to "browser-grid",
                ViewMode.GALLERY to "gallery-strip",
                ViewMode.COLUMNS to "browser-column-Source",
            )) {
            compose.runOnIdle { model.changeMode(mode) }
            compose.waitForIdle()
            val name = model.entries[120].name
            compose.onNodeWithTag(tag).performScrollToIndex(120)
            compose.waitForIdle()
            val node = compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(tag)))
            val before = node.fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { model.newTab(target.path) }
            loaded()
            compose.runOnIdle { model.switchTab(0) }
            loaded()
            try {
                node.assertIsDisplayed()
            } catch (error: Throwable) {
                throw AssertionError(
                    "Restored $mode viewport: before=$before after=${node.fetchSemanticsNode().boundsInRoot}; state=${model.tabs[0].navigation.positions}; location=${model.location} focus=${model.focused?.path}",
                    error,
                )
            }
            val after = node.fetchSemanticsNode().boundsInRoot
            assertEquals(before.top, after.top, 2f)
            assertEquals(before.left, after.left, 2f)
            compose.runOnIdle { model.closeTab(1) }
        }
    }

    @Test
    fun selectionExpansionAndViewportSurviveAViewModelReload() {
        val project = model.entries.first { it.name == "Project" }
        compose.runOnIdle { model.toggleExpanded(project) }
        compose.waitUntil(8000) { model.visibleEntries.any { it.name == "Subfolder" } }
        val sub = model.visibleEntries.first { it.name == "Subfolder" }
        compose.runOnIdle { model.toggleExpanded(sub) }
        compose.waitUntil(8000) { model.visibleEntries.any { it.name == "Deep.txt" } }
        compose.runOnIdle { model.select(model.visibleEntries.first { it.name == "Deep.txt" }) }
        compose.onNodeWithTag("browser-list").performScrollToIndex(100)
        compose.waitForIdle()
        compose.runOnIdle { model.flushNavigationState() }
        val selected = model.selected
        val expanded = model.expandedFolders
        val (restored, store) = recreateModel()
        try {
            assertEquals(selected, restored.selected)
            assertEquals(expanded, restored.expandedFolders)
            assertEquals("Deep.txt", restored.focused!!.name)
            assertNotNull(
                restored.savedViewport(
                    restored.tabs[restored.activeTab].id,
                    restored.viewportKey("list"),
                ),
            )
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test
    fun movedClosedTabsAndSavedLayoutsFollowMoveAndUndo() {
        val folder = File(source, "Project")
        val sub = File(folder, "Subfolder")
        val deep = File(sub, "Deep.txt")
        compose.runOnIdle { model.navigate(folder.path) }
        loaded()
        compose.runOnIdle { model.toggleExpanded(model.entries.first { it.folder }) }
        compose.waitUntil(8000) { model.visibleEntries.any { it.path == deep.path } }
        compose.runOnIdle {
            model.select(model.visibleEntries.first { it.path == deep.path })
            assertTrue(model.saveWorkspace("QA Move ${System.nanoTime()}"))
            layouts += model.savedWorkspaces.first { it.name.startsWith("QA Move") }.id
            model.newTab(target.path)
            model.closeTab(0)
            model.transferPaths(listOf(folder.path), target.path, true)
        }
        val moved = File(target, "Project/Subfolder/Deep.txt")
        compose.waitUntil(15_000) { model.busy == null && moved.exists() }
        compose.runOnIdle { model.reopenTab() }
        loaded()
        assertEquals(moved.parentFile!!.parent, model.location)
        assertEquals(setOf(moved.path), model.selected)
        assertTrue(moved.parent!! in model.expandedFolders)
        assertEquals(
            model.location,
            model.savedWorkspaces.first { it.id == layouts.last() }.primary.tabs.last().location,
        )
        compose.runOnIdle { model.undo() }
        compose.waitUntil(15_000) { model.busy == null && deep.exists() && !model.loading }
        assertEquals(folder.path, model.location)
        assertEquals(setOf(deep.path), model.selected)
    }

    @Test
    fun pinsPersistBulkCloseKeepsThemAndOverviewSupportsTouchReorder() {
        compose.runOnIdle {
            model.togglePinTab(0)
            model.newTab(base.path)
            model.newTab(target.path)
        }
        loaded()
        val activeId = model.tabs[model.activeTab].id
        val middleId = model.tabs[1].id
        compose.onNodeWithContentDescription("Tab overview").performClick()
        val scale = compose.activity.resources.displayMetrics.density
        compose.onNodeWithContentDescription("Reorder ${base.name} tab").performTouchInput {
            down(center)
            moveBy(Offset(0f, 100f * scale))
            up()
        }
        compose.runOnIdle {
            assertEquals(middleId, model.tabs.last().id)
            assertEquals(activeId, model.tabs[model.activeTab].id)
        }
        compose.onNodeWithContentDescription("Close Tabs").performClick()
        compose.runOnIdle {
            model.closeTab(0)
            assertEquals(3, model.tabs.size)
            model.closeOtherTabs(model.activeTab)
            assertEquals(2, model.tabs.size)
            model.flushNavigationState()
        }
        val (restored, store) = recreateModel()
        try {
            assertTrue(restored.tabs.first().pinned)
            assertEquals(source.path, restored.tabs.first().location)
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test
    fun namedWorkspacesRestoreBothPanesAndSettingsAndSupportRenameUpdateRemove() {
        compose.runOnIdle {
            model.toggleDualPane()
            model.secondaryPane.navigate(target.path)
            model.newTab(File(source, "Project").path)
            model.togglePinTab(0)
        }
        compose.waitUntil(10_000) { !model.loading && !model.secondaryPane.loading }
        compose.runOnIdle {
            model.changeMode(ViewMode.COLUMNS)
            model.changeColumnWidth(310)
            model.changeGrouping(GroupBy.KIND)
            model.secondaryPane.windowsView(7)
            model.changePaneRatio(.62f)
            model.swapPanes()
            model.secondaryPane.activatePane()
            assertTrue(model.saveWorkspace("QA Studio ${System.nanoTime()}"))
            layouts += model.savedWorkspaces.first { it.name.startsWith("QA Studio") }.id
        }
        val saved = model.savedWorkspaces.first { it.id == layouts.last() }
        val disk = runBlocking {
            model.session.workspaceMutex.withLock {
                SavedWorkspaceStore(File(compose.activity.filesDir, "workspaces.json")).load()
            }
        }
        assertTrue(disk.any { it == saved })
        compose.runOnIdle {
            model.navigate(base.path)
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.secondaryPane.navigate(base.path)
            model.changePaneRatio(.5f)
            model.openWorkspace(saved.id)
        }
        compose.waitUntil(10_000) { !model.loading && !model.secondaryPane.loading }
        assertEquals(saved.primary.tabs.last().location, model.location)
        assertEquals(target.path, model.secondaryPane.location)
        assertEquals(ViewMode.COLUMNS, model.mode)
        assertEquals(310, model.columnWidth)
        assertEquals(GroupBy.KIND, model.grouping)
        assertEquals(7, model.secondaryPane.windowsLayout)
        assertTrue(model.panesReversed)
        assertEquals(.62f, model.paneRatio, .001f)
        assertSame(model.secondaryPane, model.activePaneModel)
        assertNotEquals(saved.primary.tabs.last().id, model.tabs.last().id)
        compose.runOnIdle {
            assertFalse(model.saveWorkspace(saved.name.lowercase()))
            assertTrue(model.renameWorkspace(saved.id, "QA Renamed ${System.nanoTime()}"))
            assertTrue(model.saveWorkspace("QA Second ${System.nanoTime()}"))
            layouts += model.savedWorkspaces.first { it.name.startsWith("QA Second") }.id
            assertFalse(
                model.renameWorkspace(
                    saved.id,
                    model.savedWorkspaces.first { it.id == layouts.last() }.name,
                ),
            )
            model.navigate(base.path)
            assertTrue(
                model.saveWorkspace(
                    model.savedWorkspaces.first { it.id == saved.id }.name,
                    saved.id,
                ),
            )
            assertEquals(
                base.path,
                model.savedWorkspaces.first { it.id == saved.id }.primary.tabs.last().location,
            )
            model.removeWorkspace(layouts.last())
            assertFalse(model.savedWorkspaces.any { it.id == layouts.last() })
        }
    }

    @Test
    fun openingASavedWorkspaceCannotRetargetOrCancelARunningTransfer() {
        val fixture =
            File(compose.activity.filesDir, "ProviderFixtures/WorkspaceQueue-${System.nanoTime()}")
                .apply { mkdirs() }
        File(fixture, "Gate.bin").writeBytes(ByteArray(256 * 1024) { 7 })
        val gate = java.util.concurrent.CountDownLatch(1)
        dev.findroid.testing.FixtureDocumentsProvider.transferGate = gate
        dev.findroid.testing.FixtureDocumentsProvider.gateOpened =
            java.util.concurrent.CountDownLatch(1)
        val tree =
            android.provider.DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${fixture.name}",
            )
        try {
            compose.runOnIdle {
                model.select(model.entries[70])
                model.secondaryPane.navigate(base.path)
                assertTrue(model.saveWorkspace("QA Queue ${System.nanoTime()}"))
                layouts += model.savedWorkspaces.first { it.name.startsWith("QA Queue") }.id
                model.connectLocation(tree, false)
            }
            compose.waitUntil(8000) { model.connectedLocations.any { it.grant == tree.toString() } }
            val path =
                android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        "root/${fixture.name}/Gate.bin",
                    )
                    .toString()
            compose.runOnIdle { model.transferPaths(listOf(path), target.path, false) }
            assertTrue(
                dev.findroid.testing.FixtureDocumentsProvider.gateOpened!!.await(
                    8,
                    java.util.concurrent.TimeUnit.SECONDS,
                ),
            )
            compose.runOnIdle {
                model.openWorkspace(layouts.last())
                assertNotNull(model.busy)
            }
            gate.countDown()
            compose.waitUntil(15_000) { model.busy == null && !model.loading }
            assertEquals(256 * 1024, File(target, "Gate.bin").length().toInt())
            assertFalse(File(base, "Gate.bin").exists())
            assertEquals(1, model.selected.size)
        } finally {
            gate.countDown()
            compose.runOnIdle { if (model.busy != null) model.cancelTransfer() }
            compose.waitUntil(15_000) { model.busy == null }
            compose.runOnIdle { model.disconnectLocation(tree.toString()) }
            dev.findroid.testing.FixtureDocumentsProvider.transferGate = null
            dev.findroid.testing.FixtureDocumentsProvider.gateOpened = null
            fixture.deleteRecursively()
        }
    }
}
