@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class LocalIndexWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private val index get() = model.session.searchIndex
    private lateinit var folder: File
    private lateinit var savedSidebar: SidebarLayout
    private lateinit var savedSearches: List<SmartFolder>
    @Before fun setup() {
        loaded()
        folder = File(model.workspace, "Index workflow QA-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        listOf("Report.txt", "Work/Report nested.txt", "Cache/Report excluded.txt").forEach { File(folder, it).apply { parentFile!!.mkdirs(); writeText("Synthetic needle content") } }
        savedSidebar = model.sidebar; savedSearches = model.smartFolders
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane(); model.closeOtherTabs(model.activeTab)
            if (model.sidebarVisible) model.toggleSidebar()
            if (model.previewVisible) model.togglePreview()
            model.windowState.peekEntry.value = null
            model.changeMode(ViewMode.LIST); model.changeGrouping(GroupBy.NONE)
            model.navigate(folder.path); model.updateSearchRules(SearchRules())
        }
        loaded(); index.load(force = true)
        compose.waitUntil(10_000) { !index.state.value.loading }
    }
    @After fun cleanup() {
        index.state.value.roots.filter { indexWithin(it.path, folder.path) }.forEach { index.remove(it.id) }
        compose.waitUntil(10_000) { index.state.value.roots.none { indexWithin(it.path, folder.path) } && index.state.value.activeRoot == null }
        compose.runOnIdle {
            model.listingDetailsRequested = false
            model.smartFolders.filterNot { current -> savedSearches.any { it.id == current.id } }.forEach { model.removeSmartFolder(it.id) }
            model.editSidebar { savedSidebar }
            if (model.dualPane) model.toggleDualPane()
            model.navigate(model.workspace.path); model.updateSearchRules(SearchRules()); model.closeOtherTabs(model.activeTab)
        }
        loaded(); folder.deleteRecursively()
    }
    private fun loaded() { compose.waitUntil(20_000) { !model.loading && model.busy == null }; compose.waitForIdle() }
    private fun filters() {
        if (compose.onAllNodesWithContentDescription("Search filters").fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search filters").performClick(); compose.onNodeWithTag("search-criteria").assertExists()
    }
    private fun manager() {
        compose.onNodeWithTag("primary-more", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Search index…").performScrollTo().performClick()
        compose.onNodeWithTag("local-index").assertExists()
    }
    private fun buildThroughPicker(): LocalIndexRoot {
        compose.onNodeWithTag("add-local-index").performClick()
        compose.onNodeWithTag("local-index-picker").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reading folders…").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-index-folder").assertIsEnabled().performClick()
        compose.waitUntil(20_000) { index.state.value.activeRoot == null && index.state.value.roots.any { it.path == folder.path && it.state == IndexRefreshState.READY } }
        compose.waitForIdle()
        return index.state.value.roots.single { it.path == folder.path }
    }
    private fun root(): LocalIndexRoot = index.state.value.roots.single { it.path == folder.path }
    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5000)
        val directory = File(compose.activity.filesDir, "local-index-qa").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun choosingIndexedSourceDoesNotRegisterOrScanAndLiveActionPreservesOtherCriteria() {
        val before = index.state.value.roots
        compose.runOnIdle { model.updateSearchRules(SearchRules(name = "Report", includeSubfolders = false, excludedFolderNames = setOf("Cache"))) }
        loaded(); filters()
        compose.onNodeWithTag("search-source-LOCAL_INDEX").performClick()
        compose.onNodeWithTag("apply-search-criteria").performClick(); loaded()
        assertEquals(before, runBlocking(kotlinx.coroutines.Dispatchers.IO) { index.store.roots() })
        assertEquals(SearchSource.LOCAL_INDEX, model.effectiveSearchRules.source); assertTrue(model.entries.isEmpty())
        assertEquals(ListingRootState.UNAVAILABLE, model.listingProgress.roots.single().state)
        compose.runOnIdle { model.listingDetailsRequested = true }
        compose.onNodeWithTag("index-freshness-explanation").performScrollTo().assertIsDisplayed()
        capture("unindexed-location-details")
        compose.onNodeWithTag("result-use-live").performScrollTo().performClick(); loaded()
        assertEquals(SearchSource.LIVE, model.effectiveSearchRules.source); assertEquals("Report", model.effectiveSearchRules.name)
        assertFalse(model.effectiveSearchRules.includeSubfolders); assertEquals(setOf("Cache"), model.effectiveSearchRules.excludedFolderNames)
        assertEquals(listOf("Report.txt"), model.entries.map { it.name })
    }

    @Test fun managementKeepsTheSearchDraftAndOnlyTheConfirmedLocalFolderOptsIn() {
        filters(); compose.onNodeWithTag("search-source-LOCAL_INDEX").performClick()
        compose.onNodeWithTag("manage-search-index").performScrollTo().performClick()
        compose.onNodeWithTag("local-index").assertExists()
        val root = buildThroughPicker()
        compose.onNodeWithTag("local-index-root-${root.id}").performScrollTo().assertIsDisplayed()
        capture("complete-snapshot-management")
        compose.onNodeWithTag("close-local-index").performClick()
        compose.onNodeWithTag("search-source-LOCAL_INDEX").performScrollTo().assertIsSelected()
        assertEquals(SearchSource.LIVE, model.effectiveSearchRules.source)
        compose.onNodeWithTag("cancel-search-criteria").performClick(); assertEquals(SearchSource.LIVE, model.effectiveSearchRules.source)
        filters(); compose.onNodeWithTag("search-source-LOCAL_INDEX").performClick(); compose.onNodeWithTag("apply-search-criteria").performClick(); loaded()
        assertEquals(SearchSource.LOCAL_INDEX, model.effectiveSearchRules.source)
        assertEquals(root.entries, model.entries.size); assertEquals(root.refreshedAt, model.listingProgress.roots.single().index!!.refreshedAt)
    }

    @Test fun manualRefreshUpdatesCandidatesAndRemovingTheIndexKeepsFilesAndSavedRules() {
        manager(); val first = buildThroughPicker(); compose.onNodeWithTag("close-local-index").performClick()
        val rules = SearchRules(name = "Report", kind = "Text document", content = "needle", excludedFolderNames = setOf("Cache"), source = SearchSource.LOCAL_INDEX)
        compose.runOnIdle { model.updateSearchRules(rules); model.saveSmartFolder("Indexed QA reports") }; loaded()
        assertEquals(setOf("Report.txt", "Report nested.txt"), model.entries.map { it.name }.toSet())
        val newFile = File(folder, "Report new.txt").apply { writeText("New synthetic needle") }
        index.noteChanged(listOf(newFile.path)); compose.waitUntil(10_000) { root().changedAt != null }; loaded()
        assertFalse(model.entries.any { it.path == newFile.path })
        manager(); compose.onNodeWithTag("refresh-local-index-${first.id}").performScrollTo().performClick()
        compose.waitUntil(20_000) { index.state.value.activeRoot == null && root().generation != first.generation }; loaded()
        compose.onNodeWithTag("local-index-root-${first.id}").performScrollTo(); capture("refreshed-management")
        compose.onNodeWithTag("close-local-index").performClick(); loaded()
        assertTrue(model.entries.any { it.path == newFile.path })
        compose.runOnIdle { model.listingDetailsRequested = true }
        compose.onNodeWithTag("index-freshness-explanation").performScrollTo().assertIsDisplayed(); capture("indexed-result-details")
        compose.onNodeWithContentDescription("Close Result details").performClick()
        manager(); compose.onNodeWithTag("remove-local-index-${first.id}").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-remove-local-index").performClick()
        compose.waitUntil(10_000) { index.state.value.roots.none { it.id == first.id } }; compose.onNodeWithTag("close-local-index").performClick(); loaded()
        assertEquals(rules, model.effectiveSearchRules); assertTrue(newFile.exists()); assertEquals("New synthetic needle", newFile.readText())
        assertEquals("Synthetic needle content", File(folder, "Report.txt").readText())
        assertEquals(ListingRootState.UNAVAILABLE, model.listingProgress.roots.single().state)
    }

    @Test fun independentTabsRestoreTheirOwnSourceAndSmartFoldersNeverRegisterMissingRoots() {
        compose.runOnIdle { model.updateSearchRules(SearchRules(name = "Report", source = SearchSource.LOCAL_INDEX)) }; loaded()
        val tab = model.activeTab; val before = runBlocking(kotlinx.coroutines.Dispatchers.IO) { index.store.roots() }
        compose.runOnIdle { model.newTab(folder.path); model.updateSearchRules(SearchRules(name = "Report", source = SearchSource.LIVE)) }; loaded()
        assertEquals(SearchSource.LIVE, model.effectiveSearchRules.source); assertFalse(model.entries.isEmpty())
        compose.runOnIdle { model.switchTab(tab) }; loaded(); assertEquals(SearchSource.LOCAL_INDEX, model.effectiveSearchRules.source); assertTrue(model.entries.isEmpty())
        compose.runOnIdle { model.saveSmartFolder("Missing indexed QA") }; loaded()
        assertEquals(SearchSource.LOCAL_INDEX, model.effectiveSearchRules.source); assertTrue(model.entries.isEmpty())
        assertEquals(before, runBlocking(kotlinx.coroutines.Dispatchers.IO) { index.store.roots() })
        val encoded = compose.activity.getSharedPreferences("finder", 0).getString("tabs", "")!!
        assertTrue(encoded.contains("LOCAL_INDEX")); assertFalse(encoded.contains("generation"))
    }

    @Test fun deleteCacheExplainsItsScopeAndCancelKeepsSnapshotsAndDocuments() {
        manager(); val root = buildThroughPicker()
        compose.onNodeWithTag("delete-local-index").performClick()
        compose.onNodeWithText("Delete index cache?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick(); assertEquals(root.generation, root().generation)
        compose.onNodeWithTag("close-local-index").performClick()
        assertEquals("Synthetic needle content", File(folder, "Report.txt").readText())
    }

    @Test fun sixHundredDpLargeTextKeepsSourcePickerAndManagementActionsReachable() {
        compose.runOnIdle {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    Box(Modifier.width(600.dp)) { FinderApp(model, {}, {}, {}, {}, {}) }
                }
            }
        }
        filters(); compose.onNodeWithTag("search-source-LOCAL_INDEX").performClick(); capture("600-large-text-source")
        compose.onNodeWithTag("manage-search-index").performScrollTo().performClick()
        buildThroughPicker(); capture("600-large-text-management")
        listOf("add-local-index", "delete-local-index", "close-local-index").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed(); val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("48 dp target: $tag", bounds.bottom.value - bounds.top.value >= 48f)
        }
        compose.onNodeWithTag("close-local-index").performClick(); compose.onNodeWithTag("cancel-search-criteria").performClick()
    }
}
