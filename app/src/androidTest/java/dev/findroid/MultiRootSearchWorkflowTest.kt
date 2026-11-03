package dev.findroid

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.lifecycleScope
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class MultiRootSearchWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var base: File
    private lateinit var first: File
    private lateinit var second: File
    private lateinit var provider: File
    private lateinit var tree: Uri
    private lateinit var providerRoot: String
    private lateinit var smartIds: Set<String>
    private val localNames = setOf("Report Writing.txt", "Report Deep.txt")
    private val combinedNames = localNames + "Report Research.txt" + "Report Cloud.txt"

    @Before fun setup() {
        loaded()
        smartIds = model.smartFolders.map { it.id }.toSet()
        base = File(model.workspace, "Search locations QA-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        first = File(base, "Writing").apply { mkdirs() }
        second = File(base, "Research").apply { mkdirs() }
        File(first, "Report Writing.txt").writeText("Synthetic needle writing")
        File(first, "Nested/Report Deep.txt").apply { parentFile!!.mkdirs(); writeText("Synthetic needle deep") }
        File(second, "Report Research.txt").writeText("Synthetic needle research")
        provider = File(compose.activity.filesDir, "ProviderFixtures/readonly/${base.name}").canonicalFile.apply { mkdirs() }
        File(provider, "Report Cloud.txt").writeText("Synthetic needle cloud")
        tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/readonly/${base.name}")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane(); model.windowState.peekEntry.value = null
            model.changeMode(ViewMode.LIST); model.changeGrouping(GroupBy.NONE)
            model.connectLocation(tree, false)
        }
        compose.waitUntil(15_000) { model.location.startsWith("content://") && !model.loading }
        providerRoot = model.location
        compose.runOnIdle {
            model.navigate(first.path); model.updateSearchRules(SearchRules(name = "Report"))
            model.recentSearchHistory.clear(); model.recentSearchHistory.setEnabled(true); model.discardSearchDraft()
        }
        loaded()
        assertEquals(localNames, names())
    }
    @After fun cleanup() {
        FixtureDocumentsProvider.listingGate?.countDown()
        FixtureDocumentsProvider.listingDirectory = null; FixtureDocumentsProvider.listingGate = null
        FixtureDocumentsProvider.listingGateOpened = null; FixtureDocumentsProvider.listingFailure = null
        FixtureDocumentsProvider.listingExtras = null
        compose.onAllNodesWithContentDescription("Close Search locations").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            compose.onNodeWithContentDescription("Close Search locations").performClick()
        }
        compose.runOnIdle {
            model.listingDetailsRequested = false
            if (model.dualPane) model.toggleDualPane()
            model.smartFolders.filterNot { it.id in smartIds }.forEach { model.removeSmartFolder(it.id) }
            model.navigate(model.workspace.path); model.updateSearchRules(SearchRules())
            model.recentSearchHistory.clear(); model.discardSearchDraft()
            model.documents.disconnect(providerRoot); model.session.connectedLocations.value = model.documents.locations
        }
        loaded(); base.deleteRecursively(); provider.deleteRecursively()
    }
    private fun loaded() { compose.waitUntil(20_000) { !model.loading && model.busy == null }; compose.waitForIdle() }
    private fun names() = model.entries.map { it.name }.toSet()
    private fun choose(vararg paths: String) { compose.runOnIdle { model.chooseSearchLocations(paths.toList()) }; loaded() }
    private fun locationsDialog() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Search locations…").performScrollTo().performClick()
        compose.onNodeWithTag("search-locations").assertIsDisplayed()
    }
    private fun addProvider() {
        compose.onNodeWithTag("add-search-location").performScrollTo().performClick()
        compose.onNode(hasText(base.name) and hasAnyAncestor(hasTestTag("destination-picker"))).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Add folder") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add folder").assertIsEnabled().performClick()
        compose.onNodeWithTag("search-locations").assertIsDisplayed()
    }
    private fun use(item: RecentSearch, here: Boolean): String? {
        val task = compose.runOnIdle { compose.activity.lifecycleScope.async { model.reuseRecentSearch(item, here) } }
        return runBlocking { withTimeout(20_000) { task.await() } }
    }

    @Test fun actualReadOnlyFolderPickerKeepsTheDraftUntilSearchAndCancelPreservesScope() {
        assertFalse(model.documents.read(providerRoot).writable)
        locationsDialog(); addProvider()
        assertTrue(model.effectiveSearchRules.locations.isEmpty())
        compose.onNodeWithText("Cancel").performClick(); loaded()
        assertEquals(localNames, names()); assertTrue(model.effectiveSearchRules.locations.isEmpty())
        locationsDialog(); addProvider()
        compose.onNodeWithTag("apply-search-locations").performClick(); loaded()
        assertEquals(listOf(first.path, providerRoot), model.effectiveSearchRules.locations)
        assertEquals(localNames + "Report Cloud.txt", names())
        assertEquals(first.path, model.location)
    }

    @Test fun independentAndOverlappingRootsReturnEachDocumentOnce() {
        choose(first.path, File(first, "Nested").path, second.path, providerRoot)
        assertEquals(combinedNames, names()); assertEquals(combinedNames.size, model.entries.size)
        assertEquals(4, model.listingProgress.roots.size)
        assertTrue(model.listingProgress.roots.all { it.state == ListingRootState.COMPLETE })
    }

    @Test fun anUnavailableFirstAnchorDoesNotPreventTheOtherRootsFromLoading() {
        val missing = File(base, "Missing").path
        choose(missing, second.path, providerRoot)
        assertEquals(missing, model.location)
        assertEquals(setOf("Report Research.txt", "Report Cloud.txt"), names())
        assertEquals(listOf(ListingRootState.UNAVAILABLE, ListingRootState.COMPLETE, ListingRootState.COMPLETE), model.listingProgress.roots.map { it.state })
        assertNull(model.listingError); assertTrue(model.listingProgress.incomplete)
        compose.runOnIdle { model.listingDetailsRequested = true }
        compose.onNodeWithTag("search-root-$missing").assertIsDisplayed()
        compose.onNodeWithText("Unavailable", substring = false).assertIsDisplayed()
    }

    @Test fun aFailedProviderRetainsLocalResultsAndHasItsOwnStatus() {
        FixtureDocumentsProvider.listingFailure = provider.path
        choose(first.path, providerRoot, second.path)
        assertEquals(localNames + "Report Research.txt", names())
        assertEquals(ListingRootState.UNAVAILABLE, model.listingProgress.roots[1].state)
        assertTrue(model.listingProgress.incomplete)
    }

    @Test fun disconnectAndSameTreeReconnectRefreshASecondaryProviderWithoutNavigatingAway() {
        choose(first.path, providerRoot, second.path)
        val roots = model.effectiveSearchRules.locations
        compose.runOnIdle { model.disconnectLocation(providerRoot) }; loaded()
        assertEquals(first.path, model.location); assertEquals(roots, model.effectiveSearchRules.locations)
        assertEquals(localNames + "Report Research.txt", names())
        assertEquals(ListingRootState.UNAVAILABLE, model.listingProgress.roots[1].state)
        compose.runOnIdle { model.connectLocation(tree, false, replacing = providerRoot) }; loaded()
        assertEquals(first.path, model.location); assertEquals(roots, model.effectiveSearchRules.locations)
        assertEquals(combinedNames, names()); assertFalse(model.listingProgress.incomplete)
    }

    @Test fun tabRecreationRetainsFullRulesAndNormalNavigationClearsTheChosenOverride() {
        val rules = model.effectiveSearchRules.copy(content = "needle", includeSubfolders = false,
            excludedLocations = listOf(SearchExclusion(File(first, "Private").path)))
        compose.runOnIdle { model.updateSearchRules(rules) }; choose(first.path, providerRoot, second.path)
        val before = model.effectiveSearchRules
        compose.activityRule.scenario.recreate(); loaded()
        assertEquals(before, model.effectiveSearchRules)
        assertEquals(setOf("Report Writing.txt", "Report Cloud.txt", "Report Research.txt"), names())
        compose.runOnIdle { model.navigate(second.path) }; loaded()
        assertEquals(SearchRules(), model.effectiveSearchRules)
        assertEquals(setOf("Report Research.txt"), names())
    }

    @Test fun historySearchHereClearsChosenRootsAndOriginalRestoresAvailableRootsDespiteMissingAnchor() {
        val missing = File(base, "Offline original").path
        val rules = SearchRules(name = "Report", content = "needle", locations = listOf(missing, providerRoot))
        val item = compose.runOnIdle {
            assertTrue(model.recentSearchHistory.remember(missing, rules)); model.recentSearchHistory.state.searches.first()
        }
        compose.runOnIdle { model.navigate(second.path) }; loaded()
        assertNull(use(item, true)); loaded()
        assertTrue(model.effectiveSearchRules.locations.isEmpty()); assertEquals(setOf("Report Research.txt"), names())
        assertNull(use(item, false)); loaded()
        assertEquals(missing, model.location); assertEquals(rules, model.effectiveSearchRules)
        assertEquals(setOf("Report Cloud.txt"), names())
        assertEquals(ListingRootState.UNAVAILABLE, model.listingProgress.roots.first().state)
    }

    @Test fun smartFolderKeepsItsFirstRootConsistentAndHistoryReuseLeavesSavedRulesIndependent() {
        choose(first.path, providerRoot)
        compose.runOnIdle { model.saveSmartFolder("Scope reports") }; loaded()
        choose(second.path, providerRoot)
        val saved = model.currentSmartFolder!!
        assertEquals(second.path, saved.root); assertEquals(listOf(second.path, providerRoot), saved.rules.locations)
        val item = compose.runOnIdle { model.recentSearchHistory.state.searches.first() }
        assertNull(use(item, true)); loaded()
        assertNull(model.currentSmartFolder); assertEquals(saved.root, model.location)
        assertTrue(model.effectiveSearchRules.locations.isEmpty())
        assertEquals(saved, model.smartFolders.first { it.id == saved.id })
    }

    @Test fun aSecondaryLocalRootObserverRefreshesTheCombinedSearch() {
        choose(first.path, second.path)
        File(second, "Report New.txt").writeText("Synthetic new result")
        compose.waitUntil(10_000) { model.entries.any { it.name == "Report New.txt" } && !model.loading }
        assertEquals(localNames + setOf("Report Research.txt", "Report New.txt"), names())
    }

    @Test fun aSecondaryProviderNotificationRefreshesTheCombinedSearch() {
        choose(first.path, providerRoot)
        File(provider, "Report New cloud.txt").writeText("Synthetic new provider result")
        compose.activity.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri("dev.findroid.fixtures", DocumentsContract.getDocumentId(Uri.parse(providerRoot))), null)
        compose.waitUntil(10_000) { model.entries.any { it.name == "Report New cloud.txt" } && !model.loading }
        assertEquals(localNames + setOf("Report Cloud.txt", "Report New cloud.txt"), names())
    }

    @Test fun pendingProviderIsReportedSeparatelyFromCompletedLocalRoots() {
        FixtureDocumentsProvider.listingDirectory = provider.path
        FixtureDocumentsProvider.listingExtras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
        choose(first.path, providerRoot, second.path)
        assertEquals(combinedNames, names())
        assertEquals(ListingRootState.COMPLETE, model.listingProgress.roots[0].state)
        assertEquals(ListingRootState.INCOMPLETE, model.listingProgress.roots[1].state)
        assertTrue(model.listingProgress.roots[1].providerPending)
        assertTrue(model.listingProgress.incomplete)
    }

    @Test fun stoppingABlockedProviderKeepsPartialResultsAndDoesNotClaimLaterRootsWereScanned() {
        repeat(90) { File(provider, "Report Extra $it.txt").writeText("Synthetic extra report") }
        FixtureDocumentsProvider.listingDirectory = provider.path
        FixtureDocumentsProvider.listingGate = CountDownLatch(1)
        FixtureDocumentsProvider.listingGateOpened = CountDownLatch(1)
        compose.runOnIdle { model.chooseSearchLocations(listOf(first.path, providerRoot, second.path)) }
        assertTrue(FixtureDocumentsProvider.listingGateOpened!!.await(10, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { model.entries.isNotEmpty() && model.listingProgress.roots.size == 3 }
        val partial = model.entries
        compose.runOnIdle { model.stopLoading() }
        assertEquals(partial, model.entries); assertTrue(model.listingProgress.stopped)
        assertEquals(ListingRootState.WAITING, model.listingProgress.roots[2].state)
        FixtureDocumentsProvider.listingGate!!.countDown()
    }
    @Test fun resultDetailsCanEditChosenRootsAndAnEmptyDraftCannotBeApplied() {
        choose(first.path, providerRoot)
        compose.runOnIdle { model.listingDetailsRequested = true }
        compose.onNodeWithTag("edit-search-locations").performScrollTo().performClick()
        compose.onNodeWithTag("search-locations").assertIsDisplayed()
        for (root in listOf(first.path, providerRoot)) {
            compose.onNodeWithTag("search-locations-list").performScrollToNode(hasTestTag("remove-search-location-$root"))
            compose.onNodeWithTag("remove-search-location-$root").performClick()
        }
        compose.onNodeWithTag("apply-search-locations").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick(); loaded()
        assertEquals(listOf(first.path, providerRoot), model.effectiveSearchRules.locations)
        assertEquals(localNames + "Report Cloud.txt", names())
    }

    @Test fun tenUnavailableReferencesStayVisibleAndThePickerEnforcesTheLimit() {
        val roots = (1..10).map { File(base, "Offline $it").path }
        choose(*roots.toTypedArray())
        assertEquals(roots, model.listingProgress.roots.map { it.path })
        assertTrue(model.listingProgress.roots.all { it.state == ListingRootState.UNAVAILABLE })
        locationsDialog()
        compose.onNodeWithTag("search-locations-list").performScrollToNode(hasTestTag("add-search-location"))
        compose.onNodeWithTag("add-search-location").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(roots, model.effectiveSearchRules.locations)
    }

    @Test fun membershipViewsExplainHowToOpenAFolderBeforeChoosingOrdinaryRoots() {
        val tag = model.tagDefinitions.first().name
        compose.runOnIdle { model.navigate("@tag:$tag") }; loaded()
        locationsDialog()
        compose.onNodeWithText("Open Workspace").assertIsDisplayed()
        assertEquals("@tag:$tag", model.location)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("@tag:$tag", model.location)
    }

    @Test fun bothPanesKeepTheirOwnScopesWhenASecondaryProviderDisconnectsAndReconnects() {
        choose(first.path, providerRoot)
        compose.runOnIdle { model.toggleDualPane() }
        val other = model.secondaryPane
        compose.runOnIdle {
            other.navigate(second.path); other.updateSearchRules(SearchRules(name = "Report"))
            other.chooseSearchLocations(listOf(second.path, providerRoot))
        }
        compose.waitUntil(15_000) { !other.loading }
        compose.runOnIdle { model.disconnectLocation(providerRoot) }; loaded()
        compose.waitUntil(15_000) { !other.loading }
        assertEquals(localNames, names())
        assertEquals(setOf("Report Research.txt"), other.entries.map { it.name }.toSet())
        assertEquals(listOf(first.path, providerRoot), model.effectiveSearchRules.locations)
        assertEquals(listOf(second.path, providerRoot), other.effectiveSearchRules.locations)
        compose.runOnIdle { model.connectLocation(tree, false, replacing = providerRoot) }
        // Connecting starts with provider IO; neither pane is loading until that IO returns.
        // Wait for the actual reconnect result before accepting an idle pane as refreshed.
        compose.waitUntil(20_000) {
            model.connectedLocations.any { it.uri == providerRoot } &&
                !model.loading && !other.loading &&
                "Report Cloud.txt" in names() &&
                other.entries.any { it.name == "Report Cloud.txt" }
        }
        loaded()
        assertEquals(localNames + "Report Cloud.txt", names())
        assertEquals(setOf("Report Research.txt", "Report Cloud.txt"), other.entries.map { it.name }.toSet())
    }

    @Test fun aTypingSuggestionExplainsAndUsesTheCurrentFolderRatherThanItsSavedRoots() {
        choose(first.path, providerRoot)
        compose.runOnIdle { model.navigate(second.path) }; loaded()
        if (compose.onAllNodesWithContentDescription("Search files").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search files").performTextReplacement("Rep")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Search here: Report").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search here: Report").assertIsDisplayed().performClick(); loaded()
        assertEquals(second.path, model.location)
        assertTrue(model.effectiveSearchRules.locations.isEmpty())
        assertEquals(setOf("Report Research.txt"), names())
        assertTrue(model.recentSearchHistory.state.searches.any { it.rules.locations == listOf(first.path, providerRoot) })
    }

}
