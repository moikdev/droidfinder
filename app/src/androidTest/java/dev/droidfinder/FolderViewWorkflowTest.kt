@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.droidfinder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import org.junit.*
import org.junit.Assert.*

class FolderViewWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var base: File
    private lateinit var source: File
    private lateinit var target: File
    private lateinit var baseline: FolderView
    private val layouts = mutableListOf<String>()

    @Before
    fun setup() {
        base =
            File(compose.activity.filesDir, "Workspace/FolderViews-${System.nanoTime()}").apply {
                mkdirs()
            }
        source = File(base, "Photos").apply { mkdir() }
        target = File(base, "Reports").apply { mkdir() }
        File(source, "Notes.txt").writeText("notes")
        File(source, "Other.txt").writeText("other content")
        File(source, ".Hidden.txt").writeText("hidden")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.tabs
                .filter { it.pinned }
                .map { it.id }
                .forEach { id -> model.togglePinTab(model.tabs.indexOfFirst { it.id == id }) }
            model.closeOtherTabs(model.activeTab)
            model.navigate(base.path)
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME || !model.ascending) model.changeSort(SortBy.NAME)
            if (model.showHidden) model.toggleHidden()
            if (!model.foldersFirst) model.toggleFoldersFirst()
            model.changeDetailColumns(DetailColumns())
            model.changeIconSize(120)
            model.changeColumnWidth(240)
            model.changePreviewWidth(280)
            baseline = model.currentFolderView()
            model.navigate(source.path)
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun saved() {
        compose.waitUntil(10_000) { !model.folderViews.saving }
        assertNull(model.folderViews.error)
    }

    private fun go(file: File) {
        compose.runOnIdle { model.navigate(file.path) }
        loaded()
    }

    private fun select(name: String) {
        compose
            .onNode(
                hasText(name) and hasClickAction() and hasAnyAncestor(hasTestTag("file-browser")),
            )
            .performClick()
        compose.waitUntil(5000) { model.focused?.name == name }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        for (title in listOf("View settings", "Remembered folders")) {
            val close = compose.onAllNodesWithContentDescription("Close $title")
            if (close.fetchSemanticsNodes().isNotEmpty()) close[0].performClick()
        }
        compose.runOnIdle {
            model.transferJobs.filter { !it.terminal }.forEach { model.cancelQueuedTransfer(it.id) }
            if (model.busy != null) model.cancelTransfer()
            model.folderViews.values.keys
                .filter { pathWithin(it, folderViewKey(base.path)!!) }
                .forEach(model::forgetFolderView)
            layouts.forEach(model::removeWorkspace)
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.navigate(model.workspace.path)
            model.closeOtherTabs(model.activeTab)
        }
        loaded()
        saved()
        base.deleteRecursively()
    }

    @Test
    fun rememberedViewsRestoreThroughTabsAndOtherFoldersKeepPaneDefaults() {
        lateinit var expected: FolderView
        compose.runOnIdle {
            model.rememberFolderView(true)
            model.windowsView(2)
            model.changeSort(SortBy.SIZE)
            model.changeSort(SortBy.SIZE)
            model.changeGrouping(GroupBy.KIND)
            model.toggleHidden()
            model.changeColumnWidth(370)
            model.changePreviewWidth(350)
            model.changeDetailColumns(
                DetailColumns(size = false, dateWidth = 220, adaptive = false),
            )
            expected = model.currentFolderView()
        }
        loaded()
        saved()
        go(target)
        compose.runOnIdle {
            assertEquals(baseline, model.currentFolderView())
            model.newTab(source.path, atEnd = true)
        }
        loaded()
        assertEquals(expected, model.currentFolderView())
        compose.runOnIdle {
            model.closeTab(model.activeTab)
            model.reopenTab()
        }
        loaded()
        assertEquals(expected, model.currentFolderView())
        assertEquals(
            expected,
            FolderViewStore(File(compose.activity.filesDir, "folder-views.json"))
                .load()[folderViewKey(source.path)],
        )
        val store = ViewModelStore()
        lateinit var other: FinderModel
        val id = "folder-window-${System.nanoTime()}"
        try {
            compose.runOnIdle {
                other =
                    ViewModelProvider(
                        store,
                        object : ViewModelProvider.Factory {
                            override fun <T : androidx.lifecycle.ViewModel> create(
                                modelClass: Class<T>,
                            ): T {
                                @Suppress("UNCHECKED_CAST")
                                return FinderModel(
                                    compose.activity.application,
                                    model.session,
                                    "primary",
                                    FinderWindowState(compose.activity.application, id),
                                )
                                    as T
                            }
                        },
                    )[FinderModel::class.java]
                other.navigate(source.canonicalPath)
            }
            compose.waitUntil(10_000) { !other.loading }
            assertEquals(expected, other.currentFolderView())
            compose.runOnIdle { other.navigate(target.path) }
            compose.waitUntil(10_000) { !other.loading }
            assertEquals(ViewMode.LIST, other.mode)
            assertEquals(DetailColumns(), other.detailColumns)
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test
    fun sharedFolderChangesSynchronizePanesAndResetRestoresEachPanesDefaults() {
        val right = compose.runOnIdle {
            model.toggleDualPane()
            model.secondaryPane.apply {
                navigate(target.path)
                changeMode(ViewMode.LIST)
                changeGrouping(GroupBy.NONE)
                navigate(source.path)
            }
        }
        compose.waitUntil(10_000) { !right.loading }
        compose.runOnIdle {
            model.rememberFolderView(true)
            model.windowsView(2)
        }
        assertEquals(ViewMode.ICONS, right.mode)
        assertEquals(160, right.iconSize)
        compose.runOnIdle { right.changeGrouping(GroupBy.SIZE) }
        assertEquals(GroupBy.SIZE, model.grouping)
        go(target)
        compose.runOnIdle {
            right.changeDetailColumns(DetailColumns(kind = false, sizeWidth = 140))
        }
        assertEquals(DetailColumns(), model.detailColumns)
        go(source)
        assertFalse(model.detailColumns.kind)
        compose.runOnIdle { model.rememberFolderView(false) }
        assertEquals(baseline, model.currentFolderView())
        assertEquals(ViewMode.LIST, right.mode)
        assertEquals(GroupBy.NONE, right.grouping)
        saved()
    }

    @Test
    fun columnVisibilityAndNativeTouchResizePersistForTheFolder() {
        compose.runOnIdle {
            model.rememberFolderView(true)
            model.changeDetailColumns(DetailColumns(size = false, kind = false, adaptive = false))
        }
        compose
            .onNode(hasText("Size") and hasAnyAncestor(hasTestTag("file-browser")))
            .assertDoesNotExist()
        compose
            .onNode(hasText("Kind") and hasAnyAncestor(hasTestTag("file-browser")))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Resize Date modified column").performTouchInput {
            swipe(center, center + Offset(-120f, 0f), 500)
        }
        val width = model.detailColumns.dateWidth
        assertTrue("Dragging the leading separator left must widen the date column", width > 150)
        saved()
        go(target)
        assertEquals(DetailColumns(), model.detailColumns)
        go(source)
        assertEquals(width, model.detailColumns.dateWidth)
        assertFalse(model.detailColumns.size)
        assertFalse(model.detailColumns.kind)
    }

    @Test
    fun settingsControlsAndManagerResetOnlyTheChosenFolder() {
        go(target)
        compose.runOnIdle {
            model.rememberFolderView(true)
            model.changeMode(ViewMode.COLUMNS)
        }
        go(source)
        compose.runOnIdle { model.folderViewRequested = true }
        compose.onNodeWithTag("folder-view-settings").assertIsDisplayed()
        compose.onNodeWithText("Remember for this folder").performClick()
        compose.onNodeWithContentDescription("View: List").performClick()
        compose.onNodeWithText("Icons", useUnmergedTree = true).performClick()
        assertEquals(ViewMode.ICONS, model.mode)
        assertTrue(model.folderRemembered)
        compose.onNodeWithText("Use as pane defaults").performClick()
        compose.onNodeWithText("Saved folders (", substring = true).performClick()
        compose.onNodeWithTag("folder-view-manager").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(
                "Reset folder view ${model.displayPath(folderViewKey(source.path)!!)}",
            )
            .performClick()
        assertFalse(model.folderRemembered)
        assertEquals(ViewMode.ICONS, model.mode)
        compose.onNodeWithContentDescription("Close Remembered folders").performClick()
        go(target)
        assertEquals(ViewMode.COLUMNS, model.mode)
        assertTrue(model.folderRemembered)
        go(base)
        assertEquals(ViewMode.ICONS, model.mode)
        assertTrue(File(source, "Notes.txt").exists())
    }

    @Test
    fun renamedFolderProfileFollowsRenameUndoAndNewCopies() {
        compose.runOnIdle {
            model.rememberFolderView(true)
            model.windowsView(2)
        }
        saved()
        go(base)
        select(source.name)
        compose.runOnIdle { model.rename("Renamed") }
        loaded()
        saved()
        val renamed = File(base, "Renamed")
        assertTrue(
            "Rename failed: ${model.message}; focus=${model.focused}; profiles=${model.folderViews.values.keys}",
            renamed.exists(),
        )
        assertTrue(
            "Missing renamed profile: ${model.folderViews.values.keys}",
            folderViewKey(renamed.path) in model.folderViews.values,
        )
        assertFalse(folderViewKey(source.path) in model.folderViews.values)
        go(renamed)
        assertEquals(ViewMode.ICONS, model.mode)
        compose.runOnIdle { model.undo() }
        loaded()
        saved()
        assertEquals(source.canonicalPath, File(model.location).canonicalPath)
        assertTrue(folderViewKey(source.path) in model.folderViews.values)
        go(base)
        select(source.name)
        compose.runOnIdle { model.duplicate() }
        loaded()
        saved()
        val copy = base.listFiles()!!.single { it.isDirectory && it != source && it != target }
        assertEquals(
            model.folderViews.values[folderViewKey(source.path)],
            model.folderViews.values[folderViewKey(copy.path)],
        )
    }

    @Test
    fun workspaceKeepsPaneDefaultsSeparateAndCurrentFolderOverridesWin() {
        compose.runOnIdle {
            model.rememberFolderView(true)
            model.windowsView(2)
            val name = "Folder QA ${System.nanoTime()}"
            assertTrue(model.saveWorkspace(name))
            layouts += model.savedWorkspaces.single { it.name == name }.id
            model.changeMode(ViewMode.GALLERY)
            model.openWorkspace(layouts.single())
        }
        loaded()
        assertEquals(ViewMode.GALLERY, model.mode)
        go(target)
        assertEquals(baseline, model.currentFolderView())
    }

    @Test
    fun readOnlyProviderFoldersCanKeepViewsAndReconnectWithoutAffectingLocalDefaults() {
        val fixture =
            File(compose.activity.filesDir, "ProviderFixtures/FolderViews-${System.nanoTime()}")
                .apply { mkdirs() }
        File(fixture, "readonly").mkdir()
        File(fixture, "readonly/Readme.txt").writeText("read only")
        val tree =
            android.provider.DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${fixture.name}",
            )
        val path =
            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    "root/${fixture.name}/readonly",
                )
                .toString()
        try {
            compose.runOnIdle { model.connectLocation(tree, false) }
            compose.waitUntil(10_000) { model.location.contains(fixture.name) && !model.loading }
            compose.runOnIdle { model.navigate(path) }
            loaded()
            assertFalse(model.canCreate)
            compose.runOnIdle {
                model.rememberFolderView(true)
                model.changeMode(ViewMode.GALLERY)
            }
            saved()
            go(target)
            assertEquals(baseline, model.currentFolderView())
            compose.runOnIdle { model.newTab(path, atEnd = true) }
            loaded()
            assertEquals(ViewMode.GALLERY, model.mode)
            compose.runOnIdle { model.disconnectLocation(tree.toString()) }
            loaded()
            compose.runOnIdle { model.connectLocation(tree, false) }
            compose.waitUntil(10_000) { model.location.contains(fixture.name) && !model.loading }
            compose.runOnIdle { model.navigate(path) }
            loaded()
            assertEquals(ViewMode.GALLERY, model.mode)
            assertTrue(model.folderRemembered)
            assertEquals("read only", File(fixture, "readonly/Readme.txt").readText())
        } finally {
            compose.runOnIdle {
                model.forgetFolderView(path)
                model.disconnectLocation(tree.toString())
            }
            loaded()
            saved()
            fixture.deleteRecursively()
        }
    }

    @Test
    fun replacementRecoveryKeepsProfilesWithBothFoldersAcrossPanesAndUndo() {
        val destination = File(target, source.name).apply { mkdir() }
        File(destination, "Original.txt").writeText("original")
        compose.runOnIdle {
            model.rememberFolderView(true)
            model.changeMode(ViewMode.ICONS)
        }
        saved()
        val right = compose.runOnIdle {
            model.toggleDualPane()
            model.secondaryPane.apply { navigate(destination.path) }
        }
        compose.waitUntil(10_000) { !right.loading }
        compose.runOnIdle {
            right.rememberFolderView(true)
            right.changeMode(ViewMode.COLUMNS)
        }
        saved()
        go(base)
        compose.runOnIdle {
            model.activatePane()
            model.changeConflictPolicy(ConflictPolicy.ASK)
            model.transferPaths(listOf(source.path), target.path, true)
        }
        compose.waitUntil(10_000) { model.fileConflict != null }
        compose.runOnIdle { model.resolveConflict(ConflictDecision(ConflictAction.REPLACE)) }
        loaded()
        saved()
        // Open panes follow the original folder into recovery, matching existing tab behavior.
        assertEquals(
            "Pane ${right.location}; profiles=${model.folderViews.values}",
            ViewMode.COLUMNS,
            right.mode,
        )
        assertEquals("original", File(right.location, "Original.txt").readText())
        compose.runOnIdle { right.navigate(destination.path) }
        compose.waitUntil(10_000) { !right.loading }
        assertEquals(ViewMode.ICONS, right.mode)
        assertTrue(File(destination, "Notes.txt").exists())
        assertFalse(source.exists())
        compose.runOnIdle { model.undo() }
        loaded()
        saved()
        assertTrue(File(source, "Notes.txt").exists())
        assertEquals("original", File(destination, "Original.txt").readText())
        compose.waitUntil(10_000) { !right.loading }
        assertEquals(source.canonicalPath, File(right.location).canonicalPath)
        assertEquals(ViewMode.ICONS, right.mode)
        assertEquals(
            ViewMode.ICONS,
            model.folderViews.values.getValue(folderViewKey(source.path)!!).mode,
        )
        assertEquals(
            ViewMode.COLUMNS,
            model.folderViews.values.getValue(folderViewKey(destination.path)!!).mode,
        )
    }
}
