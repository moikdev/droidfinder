@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Native shortcut gestures, contextual alternatives and the persisted shared sidebar. */
class SidebarWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var base: File
    private lateinit var alpha: File
    private lateinit var beta: File
    private lateinit var gamma: File
    private lateinit var saved: SidebarLayout
    private var drag: DragWorkflowTest.NativeDrag? = null

    @Before fun setup() {
        base = File(compose.activity.filesDir, "Workspace/Sidebar-${System.nanoTime()}").apply { mkdirs() }
        alpha = File(base, "Alpha").apply { mkdir() }
        beta = File(base, "Beta").apply { mkdir() }
        gamma = File(base, "Gamma").apply { mkdir() }
        listOf(alpha, beta, gamma).forEach { File(it, "Keep.txt").writeText("original ${it.name} bytes") }
        compose.runOnIdle {
            saved = model.sidebar
            if (model.dualPane) model.toggleDualPane()
            if (!model.sidebarVisible) model.toggleSidebar()
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.activatePane()
            model.closeOtherTabs(model.activeTab)
            model.navigate(base.path)
            model.changeMode(ViewMode.LIST)
            model.editSidebar { SidebarLayout(
                favorites = listOf(FavoriteShortcut("alpha", alpha.path), FavoriteShortcut("beta", beta.path), FavoriteShortcut("gamma", gamma.path)),
                hidden = SidebarBuiltin.entries.toSet(),
            ) }
        }
        loaded()
    }

    @After fun cleanup() {
        drag?.cancel()
        compose.waitForIdle()
        compose.runOnIdle {
            model.editSidebar { saved }
            if (model.dualPane) model.toggleDualPane()
            model.navigate(model.workspace.path)
            model.closeOtherTabs(model.activeTab)
            model.folderViews.values.keys.filter { pathWithin(it, folderViewKey(base.path)!!) }.forEach(model::forgetFolderView)
        }
        loaded()
        base.deleteRecursively()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }
    private fun persisted() = SidebarJson.decode(compose.activity.getSharedPreferences("finder", 0).getString(SidebarJson.KEY, null)!!)
    private fun options(id: String) {
        compose.onNodeWithTag("favorite-menu-$id").performScrollTo().performClick()
        compose.onNodeWithTag("favorite-options").assertExists()
    }
    private fun lift(id: String, mouse: Boolean = false): DragWorkflowTest.NativeDrag {
        compose.onNodeWithTag("favorite-drag-$id", useUnmergedTree = true).performScrollTo()
        val point = compose.onNodeWithTag("favorite-drag-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.center
        return DragWorkflowTest.NativeDrag(point, mouse, false, { compose.waitForIdle() }, { compose.mainClock.advanceTimeBy(700) })
            .also { drag = it; it.start() }
    }
    private fun dropBefore(native: DragWorkflowTest.NativeDrag, before: String?, group: String? = null) {
        val tag = "favorite-insert-${before ?: "end"}-${group ?: "none"}"
        compose.onNodeWithTag(tag).performScrollTo()
        native.moveDirect(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.center)
        compose.waitForIdle()
        compose.onNodeWithText("Reorder Favorites").assertExists()
        native.drop()
        compose.waitUntil(5000) { persisted() == model.sidebar }
        compose.waitForIdle()
    }
    private fun unchanged() {
        listOf(alpha, beta, gamma).forEach { assertEquals("original ${it.name} bytes", File(it, "Keep.txt").readText()) }
        assertEquals(setOf("Alpha", "Beta", "Gamma"), base.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun touchReorderPersistsAndNeverMovesFolderBytes() {
        dropBefore(lift("gamma"), "alpha")
        compose.waitUntil(5000) { model.sidebar.favorites.first().id == "gamma" }
        assertEquals(listOf("gamma", "alpha", "beta"), persisted().favorites.map { it.id })
        unchanged()
    }

    @Test fun mouseCanAppendShortcutWithNativeInsertionLine() {
        dropBefore(lift("alpha", mouse = true), null)
        compose.waitUntil(5000) { model.sidebar.favorites.last().id == "alpha" }
        assertEquals(listOf("beta", "gamma", "alpha"), persisted().favorites.map { it.id })
        unchanged()
    }

    @Test fun cancellationLeavesOrderAndFilesIntact() {
        val before = model.sidebar
        val native = lift("gamma")
        native.moveDirect(compose.onNodeWithTag("favorite-insert-alpha-none").fetchSemanticsNode().boundsInWindow.center)
        native.cancel()
        compose.waitForIdle()
        assertEquals(before, model.sidebar)
        assertEquals(before, persisted())
        unchanged()
    }

    @Test fun labelMenuChangesOnlyShortcutAndDestinationLabels() {
        options("alpha")
        compose.onNodeWithText("Rename sidebar label…").performClick()
        compose.onNodeWithTag("sidebar-name").performTextReplacement("Writing projects")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("favorite-${alpha.path}").assertTextContains("Writing projects")
        assertEquals("Writing projects", model.favoriteTitle(alpha.path))
        assertEquals(model.sidebar, persisted())
        unchanged()
    }

    @Test fun moveCommandsProvideAccessibleReorderAlternative() {
        options("beta")
        compose.onNodeWithText("Move up").performClick()
        assertEquals(listOf("beta", "alpha", "gamma"), model.sidebar.favorites.map { it.id })
        options("beta")
        compose.onNodeWithText("Move down").performClick()
        assertEquals(listOf("alpha", "beta", "gamma"), persisted().favorites.map { it.id })
    }

    @Test fun builtinVisibilityOrderingAndRestoreKeepCustomShortcuts() {
        compose.onNodeWithTag("edit-sidebar").performClick()
        compose.onNodeWithContentDescription("Show Documents").performClick()
        compose.onNodeWithContentDescription("Move Documents up").performClick()
        assertFalse(SidebarBuiltin.DOCUMENTS in model.sidebar.hidden)
        assertEquals(SidebarBuiltin.DOCUMENTS, model.sidebar.builtins[1])
        compose.onNodeWithText("Restore built-in defaults").performScrollTo().performClick()
        assertEquals(SidebarBuiltin.entries.toList(), model.sidebar.builtins)
        assertTrue(model.sidebar.hidden.isEmpty())
        assertEquals(listOf("alpha", "beta", "gamma"), model.sidebar.favorites.map { it.id })
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("builtin-DOCUMENTS").assertExists()
    }

    @Test fun createMoveCollapseAndRemoveGroupKeepsAllReferences() {
        compose.onNodeWithTag("edit-sidebar").performClick()
        compose.onNodeWithTag("favorite-group-name").performScrollTo().performTextInput("Work")
        // Let the keyboard close before scrolling to a control near the dialog's footer.
        compose.runOnIdle {
            val input = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                input.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            var hidden = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                hidden = android.view.inspector.WindowInspector.getGlobalWindowViews().none {
                    androidx.core.view.ViewCompat.getRootWindowInsets(it)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
            }
            hidden
        }
        compose.onNodeWithText("Create group").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { model.sidebar.groups.size == 1 }
        compose.onNodeWithText("Done").performClick()
        val group = model.sidebar.groups.single()
        options("alpha")
        compose.onNodeWithText("Move to Work").performScrollTo().performClick()
        assertEquals(group.id, persisted().favorites.first { it.id == "alpha" }.group)
        compose.onNodeWithText("Work", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("favorite-${alpha.path}").assertDoesNotExist()
        assertTrue(persisted().groups.single().collapsed)
        compose.onNodeWithContentDescription("Options for group Work").performScrollTo().performClick()
        compose.onNodeWithText("Remove group; keep shortcuts").performClick()
        assertTrue(model.sidebar.groups.isEmpty())
        assertTrue(model.sidebar.favorites.all { it.group == null })
        compose.onNodeWithTag("favorite-${alpha.path}").assertExists()
        unchanged()
    }

    @Test fun unavailableShortcutOpensRecoveryInsteadOfAnEmptyFolder() {
        val missing = File(base, "Removed folder")
        compose.runOnIdle { model.repairFavorite("alpha", missing.path) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Unavailable · options to repair").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("favorite-${missing.path}").performClick()
        compose.onNodeWithText("Folder unavailable").assertExists()
        assertEquals(base.path, model.location)
        compose.onNodeWithText("Repair shortcut…").performClick()
        compose.onNode(hasText("Workspace") and hasClickAction() and hasAnyAncestor(hasTestTag("destination-picker"))).performClick()
        compose.onNodeWithText("Use this folder").performClick()
        assertEquals(model.workspace.path, persisted().favorites.first { it.id == "alpha" }.path)
        unchanged()
    }

    @Test fun duplicateNamesShowBothParentPaths() {
        val a = File(alpha, "Projects").apply { mkdir() }
        val b = File(beta, "Projects").apply { mkdir() }
        compose.runOnIdle { model.editSidebar { it.copy(favorites = listOf(
            FavoriteShortcut("one", a.path), FavoriteShortcut("two", b.path),
        )) } }
        compose.onNodeWithTag("favorite-${a.path}").assertTextContains(model.displayPath(a.path))
        compose.onNodeWithTag("favorite-${b.path}").assertTextContains(model.displayPath(b.path))
    }

    @Test fun revealAndNewPaneUseOriginalPathAndSharedOrder() {
        options("alpha")
        compose.onNodeWithText("Reveal original folder").performClick()
        loaded()
        assertEquals(base.path, model.location)
        compose.waitUntil(5000) { alpha.path in model.selected }
        options("beta")
        compose.onNodeWithText("Open in other pane").performClick()
        compose.waitUntil(15_000) { model.dualPane && !model.secondaryPane.loading }
        assertEquals(beta.path, model.secondaryPane.location)
        compose.runOnIdle { model.secondaryPane.placeFavorite("gamma", "alpha", null) }
        assertEquals(listOf("gamma", "alpha", "beta"), model.sidebar.favorites.map { it.id })
        assertEquals(model.sidebar, model.secondaryPane.sidebar)
        assertEquals(model.sidebar, persisted())
    }
    @Test fun missingFavoriteCanBeReorderedWithoutReadingItsStorage() {
        val missing = File(base, "Disconnected storage")
        compose.runOnIdle { model.repairFavorite("gamma", missing.path) }
        dropBefore(lift("gamma"), "alpha")
        compose.waitUntil(5000) { model.sidebar.favorites.first().id == "gamma" }
        assertEquals(missing.path, persisted().favorites.first().path)
        assertFalse(missing.exists())
    }

    @Test fun shortcutDragToFolderIsRejectedWithoutMovingOriginal() {
        val before = model.sidebar
        val native = lift("alpha")
        native.moveDirect(compose.onNodeWithTag("favorite-${beta.path}").fetchSemanticsNode().boundsInWindow.center)
        compose.waitUntil(5000) { compose.onAllNodesWithText("Drop between Favorites to change their order").fetchSemanticsNodes().isNotEmpty() }
        native.drop()
        compose.waitForIdle()
        assertEquals(before, persisted())
        unchanged()
    }

}
