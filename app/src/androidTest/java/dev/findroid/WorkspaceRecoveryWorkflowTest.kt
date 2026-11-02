package dev.findroid

import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import org.junit.*
import org.junit.Assert.*

class WorkspaceRecoveryWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private val state get() = model.workspaceRecovery
    private lateinit var base: File
    private lateinit var working: File
    private lateinit var replacement: File
    private lateinit var missing: File
    private lateinit var originalTabs: SavedWorkspace
    private lateinit var library: List<SavedWorkspace>
    private lateinit var searches: List<SmartFolder>
    private var libraryBytes: ByteArray? = null
    private var provider: File? = null
    private var connected: String? = null
    private lateinit var saved: SavedWorkspace
    private fun pane(vararg paths: String) = PaneSnapshot(paths.map { FinderTab(listOf(it)) }, 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, false, false, 120, 240, 280)

    @Before fun setup() {
        compose.waitUntil(15_000) { model.busy == null && !model.loading && !model.workspaceSaving && !model.folderViews.saving }
        compose.runOnIdle {
            state.close(); model.windowState.peekEntry.value = null
            originalTabs = SavedWorkspace("before", "Before", model.primaryPane.snapshotPane(), model.secondaryPane.snapshotPane(), model.dualPane, model.panesReversed, model.paneRatio, model.windowState.activePane.value)
            library = model.savedWorkspaces
            searches = model.smartFolders
        }
        libraryBytes = File(compose.activity.filesDir, "workspaces.json").takeIf(File::isFile)?.readBytes()
        base = File(model.workspace, "Layout recovery ${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        working = File(base, "Working project").apply { mkdir() }
        replacement = File(base, "Replacement").apply { mkdir() }
        missing = File(base, "Old offline project")
        File(working, "Keep.txt").writeText("Working project bytes remain unchanged")
        File(replacement, "Notes.md").writeText("Replacement document stays in place")
        saved = SavedWorkspace("layout-recovery", "Writing layout", pane(working.path, missing.path).copy(active = 1), pane(working.path), true, true, .6f, "secondary")
        compose.runOnIdle {
            model.restoreWorkspaceSnapshot(SavedWorkspace("current", "Current", pane(base.path), pane(base.path), false, false, .5f, "primary"))
            seed(saved)
        }
        compose.waitUntil(15_000) { !model.loading }
    }
    private fun seed(value: SavedWorkspace) {
        saved = value
        model.session.savedWorkspaces.value = listOf(value.copy(id = "unaffected-layout", name = "Unchanged layout", primary = pane(working.path)), value)
        model.session.workspaceStore.write(model.savedWorkspaces)
    }
    @After fun cleanup() {
        FixtureDocumentsProvider.metadataDenied = false
        compose.waitUntil(15_000) { !state.working && !state.applying && model.busy == null && !model.workspaceSaving }
        compose.runOnIdle {
            state.close(); connected?.let(model.documents::disconnect)
            model.session.connectedLocations.value = model.documents.locations
            model.session.savedWorkspaces.value = library
            model.session.smartFolders.value = searches
            model.restoreWorkspaceSnapshot(originalTabs)
        }
        compose.waitUntil(15_000) { !model.primaryPane.loading && !model.secondaryPane.loading && !model.folderViews.saving }
        val file = File(compose.activity.filesDir, "workspaces.json")
        libraryBytes?.let(file::writeBytes) ?: file.delete()
        File(file.path + ".bak").delete(); File(file.path + ".new").delete()
        base.deleteRecursively(); provider?.deleteRecursively()
    }
    private fun settled() {
        compose.waitUntil(15_000) { !state.working && !state.applying }
        compose.waitForIdle()
    }
    private fun open() {
        compose.runOnIdle { model.openWorkspace(saved.id) }
        settled()
        assertTrue(state.visible); assertEquals(1, state.missing.size)
    }
    private fun reviewed(target: String = replacement.path) {
        open()
        compose.runOnIdle { state.replacement(missing.path, target); state.prepareReview() }
        settled(); assertNull(state.error); assertNotNull(state.review)
    }
    private fun apply(save: Boolean = true) {
        compose.runOnIdle { state.apply(save) }; settled()
    }
    private fun untouched() {
        assertEquals("Working project bytes remain unchanged", File(working, "Keep.txt").readText())
        assertEquals("Replacement document stays in place", File(replacement, "Notes.md").readText())
    }
    private fun providerRoot(): String {
        val parent = File(compose.activity.filesDir, "ProviderFixtures/WorkspaceRecovery-${System.nanoTime()}").apply { mkdirs() }
        provider = parent
        File(parent, "readonly").mkdir()
        File(parent, "readonly/Keep.txt").writeText("Read-only provider document")
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${parent.name}/readonly")
        val value = model.documents.connect(tree, persist = false)
        connected = value.uri
        compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations }
        return value.uri
    }

    @Test fun anAvailableLayoutOpensWithoutARecoveryConfirmation() {
        compose.runOnIdle { seed(saved.copy(primary = pane(working.path))); model.openWorkspace(saved.id) }
        settled()
        assertFalse(state.visible); assertTrue(model.dualPane)
        assertEquals(working.path, model.location)
        assertEquals(working.path, model.secondaryPane.location)
        untouched()
    }
    @Test fun unrelatedMissingBackHistoryDoesNotBlockAWorkingTab() {
        val tab = FinderTab(listOf(missing.path, working.path), pinned = true)
        compose.runOnIdle { seed(saved.copy(primary = pane(working.path).copy(tabs = listOf(tab)))); model.openWorkspace(saved.id) }
        settled()
        assertFalse(state.visible); assertEquals(working.path, model.location)
        assertEquals(tab.history, model.tabs.single().history)
        untouched()
    }
    @Test fun realFolderPickerReviewsAndSavesOnlyTheMissingDestination() {
        // Keep the repeatable optimized fixture entirely synthetic and independent of this tablet.
        File(compose.activity.getExternalFilesDir(null), "workspace-qa").apply { mkdirs() }
            .resolve("workspace-demo.json").writeText(WorkspaceJson.encode(model.savedWorkspaces.map { it.remap(listOf(base.path to "/storage/FINDROID-QA-LAYOUT")) }))
        val otherLayout = model.savedWorkspaces.first { it.id == "unaffected-layout" }
        // Exercise the same Workspaces row and destination picker used in the shipping app.
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Workspaces…").performScrollTo().performClick()
        compose.onNodeWithText("Writing layout").performClick()
        settled()
        compose.onNodeWithTag("workspace-missing-0").assertExists()
        compose.onNodeWithText("Choose replacement folder…").performScrollTo().performClick()
        compose.onNode(hasText("Replacement") and hasAnyAncestor(hasTestTag("destination-picker")), useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Use this folder") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Use this folder").performClick()
        compose.onNodeWithTag("workspace-review-repairs").performClick(); settled()
        compose.onNodeWithTag("workspace-reviewed-mapping").assertTextContains(replacement.path, substring = true)
        compose.onNodeWithTag("workspace-save-repairs").performClick(); settled()
        assertFalse(state.visible)
        val restored = model.savedWorkspaces.first { it.id == saved.id }
        assertEquals(saved.primary.tabs.first(), restored.primary.tabs.first())
        assertEquals(replacement.path, restored.primary.tabs[1].location)
        assertEquals(saved.secondary, restored.secondary)
        assertEquals(otherLayout, model.savedWorkspaces.first { it.id == "unaffected-layout" })
        assertEquals(restored, model.session.workspaceStore.load().first { it.id == saved.id })
        assertTrue(model.dualPane); assertTrue(model.panesReversed); assertEquals(.6f, model.paneRatio)
        assertEquals("secondary", model.windowState.activePane.value)
        assertEquals(replacement.path, model.primaryPane.location)
        assertEquals(working.path, model.secondaryPane.location)
        untouched()
    }
    @Test fun openOnceRepairsTheSessionAndKeepsTheSavedLayoutByteForByte() {
        reviewed()
        val bytes = File(compose.activity.filesDir, "workspaces.json").readBytes()
        apply(save = false)
        assertFalse(state.visible); assertEquals(replacement.path, model.location)
        assertEquals(saved, model.savedWorkspaces.first { it.id == saved.id })
        assertArrayEquals(bytes, File(compose.activity.filesDir, "workspaces.json").readBytes())
        untouched()
    }
    @Test fun keepingAMissingLocationRequiresAnExplicitChoice() {
        open(); compose.onNodeWithTag("workspace-review-repairs").performClick(); settled()
        compose.onNodeWithTag("workspace-open-once").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-keep-unavailable").performScrollTo().performClick()
        compose.onNodeWithTag("workspace-open-once").performClick(); settled()
        assertFalse(state.visible); assertEquals(missing.path, model.location)
        assertEquals(saved, model.savedWorkspaces.first { it.id == saved.id })
        untouched()
    }
    @Test fun closingACheckOrReviewKeepsCurrentPanesAndSavedLayout() {
        val before = model.location
        compose.runOnIdle { model.openWorkspace(saved.id); state.close() }; settled()
        assertEquals(before, model.location); assertFalse(state.visible)
        reviewed(); compose.onNodeWithText("Cancel").performClick()
        assertEquals(before, model.location); assertEquals(saved, model.savedWorkspaces.first { it.id == saved.id })
        untouched()
    }
    @Test fun aChangedSavedWorkspaceInvalidatesAnOlderReview() {
        reviewed()
        compose.runOnIdle { assertTrue(model.renameWorkspace(saved.id, "Revised layout")) }
        compose.waitUntil(10_000) { !model.workspaceSaving }
        val before = model.location
        apply()
        assertTrue(state.error.orEmpty().contains("workspace changed")); assertTrue(state.visible)
        assertEquals(before, model.location)
        assertEquals("Revised layout", model.savedWorkspaces.first { it.id == saved.id }.name)
        untouched()
    }
    @Test fun readOnlyProviderRootsAreValidReplacementDestinations() {
        val root = providerRoot()
        reviewed(root)
        assertFalse(model.documents.read(root).writable)
        apply()
        assertNull(state.error); assertFalse(state.visible)
        assertEquals(root, model.primaryPane.location)
        assertEquals(root, model.session.workspaceStore.load().first { it.id == saved.id }.primary.tabs[1].location)
        assertEquals("Read-only provider document", File(provider, "readonly/Keep.txt").readText())
        untouched()
    }
    @Test fun grantLossAfterReviewRequiresAFreshAvailabilityReview() {
        val root = providerRoot()
        reviewed(root)
        val before = model.location
        compose.runOnIdle { model.documents.disconnect(root); model.session.connectedLocations.value = model.documents.locations; connected = null }
        apply()
        assertTrue(state.error.orEmpty().contains("Storage availability changed"))
        assertTrue(state.visible); assertEquals(before, model.location)
        assertEquals(saved, model.savedWorkspaces.first { it.id == saved.id })
        untouched()
    }
    @Test fun reconnectingAnOriginalDestinationKeepsItAndDropsItsReplacementChoice() {
        open()
        compose.runOnIdle { state.replacement(missing.path, replacement.path) }
        missing.mkdir()
        compose.onNodeWithText("Check locations again").performScrollTo().performClick(); settled()
        assertFalse(state.visible); assertEquals(missing.path, model.location)
        assertEquals(saved, model.savedWorkspaces.first { it.id == saved.id })
        untouched()
    }
    @Test fun reviewSurvivesActivityRecreationAndActivePaneChanges() {
        reviewed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertNotNull(state.review)
        compose.onNodeWithTag("workspace-reviewed-mapping").assertTextContains(replacement.path, substring = true)
        apply(false)
        assertEquals(replacement.path, model.primaryPane.location)
        assertEquals("secondary", model.windowState.activePane.value)
        untouched()
    }
    @Test fun missingVirtualDestinationsCanBeReplacedWithoutChangingTheLibrary() {
        val path = "@collection:removed-workspace-library"
        compose.runOnIdle { seed(saved.copy(primary = pane(path))) }
        open()
        assertTrue(state.missing.single().problem.orEmpty().contains("no longer available"))
        compose.runOnIdle { state.replacement(path, replacement.path); state.prepareReview() }; settled()
        apply()
        assertNull(state.error); assertEquals(replacement.path, model.primaryPane.location)
        assertTrue(model.collections.none { it.id == "removed-workspace-library" })
        untouched()
    }
    @Test fun aSavedSearchWithAnUnavailableRootKeepsItsFiltersOnTheReplacementFolder() {
        val search = SmartFolder("workspace-offline-search", "Offline notes", missing.path, SearchRules(name = "Notes", minimumBytes = 2, includeSubfolders = false))
        compose.runOnIdle {
            model.session.smartFolders.value = searches + search
            seed(saved.copy(primary = pane("@smart:${search.id}").copy(tabs = listOf(FinderTab(listOf("@smart:${search.id}"), search = "draft")))))
        }
        open()
        assertTrue(state.missing.single().problem.orEmpty().contains("Saved search folder"))
        compose.runOnIdle { state.replacement("@smart:${search.id}", replacement.path); state.prepareReview() }; settled()
        apply()
        assertEquals(replacement.path, model.primaryPane.location)
        assertEquals("draft", model.primaryPane.query)
        assertTrue("Notes" in model.primaryPane.effectiveSearchRules.nameTerms)
        assertEquals(2L, model.primaryPane.effectiveSearchRules.minimumBytes)
        assertFalse(model.primaryPane.effectiveSearchRules.includeSubfolders)
        assertEquals(search, model.smartFolders.first { it.id == search.id })
        untouched()
    }
    @Test fun aChangedSmartFolderInvalidatesAReviewBeforeAnyRepairIsSaved() {
        val search = SmartFolder("workspace-offline-search", "Offline notes", missing.path, SearchRules(name = "Notes"))
        compose.runOnIdle { model.session.smartFolders.value = searches + search; seed(saved.copy(primary = pane("@smart:${search.id}"))) }
        open()
        compose.runOnIdle { state.replacement("@smart:${search.id}", replacement.path); state.prepareReview() }; settled()
        val before = model.location
        compose.runOnIdle { model.session.smartFolders.value = searches + search.copy(rules = SearchRules(name = "Revised")) }
        apply()
        assertTrue(state.error.orEmpty().contains("Storage availability changed"))
        assertEquals(before, model.location)
        assertEquals(saved, model.savedWorkspaces.first { it.id == saved.id })
        untouched()
    }

}
