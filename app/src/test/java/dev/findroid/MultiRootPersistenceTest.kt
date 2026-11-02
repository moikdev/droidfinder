package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class MultiRootPersistenceTest {
    private val roots = listOf("/projects/Writing", "content://provider/tree/old/document/opaque", "/projects/Writing/Sub")
    private val rules = SearchRules(name = "Report", locations = roots, includeSubfolders = false,
        excludedLocations = listOf(SearchExclusion("/projects/Writing/Private")))
    private fun pane(tab: FinderTab) = PaneSnapshot(listOf(tab), 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, true, false, 120, 240, 280)
    private fun workspace(tab: FinderTab) = SavedWorkspace("w", "Writing", pane(tab), pane(FinderTab(listOf("/other"))), true, false, .5f, "primary")

    @Test fun choosingOnlyFoldersCreatesAnActiveRecursiveSearchAndHistoryCopiesTheScope() {
        val scope = SearchRules(locations = roots)
        assertTrue(scope.active)
        assertTrue(scope.scoped)
        assertEquals(roots, scope.historyCopy().locations)
        assertTrue(SearchRules().locations.isEmpty())
    }

    @Test fun renamedLocalRootsAndExactProviderReplacementsFollowTabsAndBudgetScopes() {
        val tab = FinderTab(listOf(roots.first()), rules = rules, navigation = TabNavigation(budgets = mapOf(ListingScope(roots.first(), rules) to ListingBudget())))
        val moved = tab.remap(listOf("/projects" to "/archive", roots[1] to "content://other/tree/new/document/opaque2"))
        val expected = listOf("/archive/Writing", "content://other/tree/new/document/opaque2", "/archive/Writing/Sub")
        assertEquals(expected, moved.rules.locations)
        assertEquals(expected, moved.navigation.budgets.keys.single().rules.locations)
        assertEquals("/archive/Writing/Private", moved.rules.excludedLocations.single().path)
        assertEquals(roots, tab.rules.locations)
    }

    @Test fun workspaceRecoveryInventoriesChosenRootsWithoutScanningHiddenLayoutsOrOldHistory() {
        val tab = FinderTab(listOf("/old", roots.first()), rules = rules)
        val layout = workspace(tab).copy(dual = false)
        assertEquals(roots, layout.openDestinations().map { it.path })
        assertEquals(layout.secondary, layout.repairDestinations(setOf(roots[1]), mapOf(roots[1] to "/replacement")).secondary)
    }

    @Test fun repairingOnlyASecondaryRootPreservesTheWorkingAnchorAndLiteralDescendantExclusion() {
        val tab = FinderTab(listOf(roots.first()), rules = rules, search = "draft", pinned = true)
        val layout = workspace(tab)
        val changed = layout.repairDestinations(setOf(roots[1]), mapOf(roots[1] to "/replacement")).primary.tabs.single()
        assertEquals(tab.location, changed.location)
        assertEquals(listOf(roots[0], "/replacement", roots[2]), changed.rules.locations)
        assertEquals(tab.rules.excludedLocations, changed.rules.excludedLocations)
        assertEquals(tab.id, changed.id)
        assertEquals("draft", changed.search)
        assertTrue(changed.pinned)
    }

    @Test fun smartFolderSecondaryRepairMaterializesIndependentRulesWithoutChangingTheSavedSearch() {
        val original = rules.copy(nameTerms = listOf("2026"))
        val tab = FinderTab(listOf("@smart:saved"), search = "draft")
        val layout = workspace(tab)
        assertEquals(listOf("@smart:saved", roots[1], roots[2], "/other"), layout.openDestinations(mapOf(tab.location to original)).map { it.path })
        val changed = layout.repairDestinations(setOf(roots[1]), mapOf(roots[1] to "/replacement"), mapOf(tab.location to original)).primary.tabs.single()
        assertEquals(roots.first(), changed.location)
        assertEquals(listOf(roots[0], "/replacement", roots[2]), changed.rules.locations)
        assertEquals(listOf("2026", "Report"), changed.rules.nameTerms)
        assertEquals("draft", changed.search)
        assertEquals(roots, original.locations)
    }

    @Test fun suggestionsDeduplicateAfterRemovingTheOriginalScopeAndHistoryFiltersAllChosenFolders() {
        val first = RecentSearch("one", roots.first(), rules, 2)
        val second = RecentSearch("two", "/elsewhere", rules.copy(locations = listOf("/elsewhere")), 1)
        assertEquals(listOf(first), recentSearchSuggestions(listOf(first, second), "Report"))
        assertTrue(recentSearchMatches(first, "opaque"))
        assertTrue(recentSearchSuggestions(listOf(first), "3").isEmpty())
        val remapped = remapRecentSearches(listOf(first), listOf(roots[1] to "/replacement")).single()
        assertEquals(listOf(roots[0], "/replacement", roots[2]), remapped.rules.locations)
    }
}
