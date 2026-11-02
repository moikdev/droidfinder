package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class WorkspaceRecoveryTest {
    private fun pane(vararg paths: String) = PaneSnapshot(paths.map { FinderTab(listOf(it)) }, 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, true, false, 120, 240, 280)
    private fun layout() = SavedWorkspace("writing", "Writing", pane("/missing", "/working"), pane("/other"), true, false, .6f, "secondary")

    @Test fun currentTabDestinationsExcludeUnrelatedHistoryAndHiddenSecondaryPane() {
        val value = layout().copy(primary = pane("/working").copy(tabs = listOf(FinderTab(listOf("/old-missing", "/working")))), dual = false)
        assertEquals(listOf(WorkspaceDestination("/working", listOf("Tab 1"))), value.openDestinations())
    }
    @Test fun sharedDestinationsAreReviewedOnceWithBothVisiblePaneUses() {
        val value = layout().copy(secondary = pane("/missing"), reversed = true)
        assertEquals(listOf("Right pane · tab 1", "Left pane · tab 1"), value.openDestinations().first().uses)
        assertEquals(2, value.openDestinations().size)
    }
    @Test fun aWorkingTabWithTheMissingPathInItsHistoryRemainsExactlyUnchanged() {
        val value = layout().let { it.copy(primary = it.primary.copy(tabs = listOf(it.primary.tabs.first(), FinderTab(listOf("/missing", "/working"), pinned = true)))) }
        val repaired = value.repairDestinations(setOf("/missing"), mapOf("/missing" to "/replacement"))
        assertEquals("/replacement", repaired.primary.tabs.first().location)
        assertEquals(value.primary.tabs[1], repaired.primary.tabs[1])
        assertEquals(value.secondary, repaired.secondary)
    }
    @Test fun repairKeepsLayoutGeometryPinnedStateSearchAndTabIdentity() {
        val value = layout().let { it.copy(primary = it.primary.copy(tabs = listOf(it.primary.tabs.first().copy(search = "invoice", pinned = true, rules = SearchRules(kind = "Documents"))))) }
        val repaired = value.repairDestinations(setOf("/missing"), mapOf("/missing" to "/replacement"))
        val changed = repaired.primary.tabs.single()
        assertEquals(value.primary.tabs.single().copy(history = listOf("/replacement")), changed)
        assertEquals(value.copy(primary = repaired.primary), repaired)
    }
    @Test fun replacingAFolderDiscardsItsSelectionAndScrollStateButKeepsOtherHistoryState() {
        val old = ViewportKey("/missing", "LIST")
        val other = ViewportKey("/older", "GRID")
        val navigation = TabNavigation(setOf("/missing/File"), "/missing/File", "/missing/File", setOf("/missing/Sub"), true,
            mapOf(old to ViewportAnchor("/missing/File", 5), other to ViewportAnchor("/older/Keep", 2)),
            mapOf(ListingScope("/missing") to ListingBudget(), ListingScope("/older") to ListingBudget()))
        val value = layout().let { it.copy(primary = it.primary.copy(tabs = listOf(FinderTab(listOf("/older", "/missing"), navigation = navigation)))) }
        val repaired = value.repairDestinations(setOf("/missing"), mapOf("/missing" to "/replacement"))
        val actual = repaired.primary.tabs.single()
        assertEquals(listOf("/older", "/replacement"), actual.history)
        assertEquals(TabNavigation(positions = mapOf(other to navigation.positions.getValue(other)), budgets = mapOf(ListingScope("/older") to ListingBudget())), actual.navigation)
    }
    @Test fun opaqueProviderIdsAreReplacedExactlyWithoutSuffixGuessing() {
        val old = "content://provider/tree/root/document/opaque-18"
        val child = "content://provider/tree/root/document/opaque-92"
        val replacement = "content://other/tree/base/document/opaque-73"
        val value = layout().copy(primary = pane(old).copy(tabs = listOf(FinderTab(listOf(child, old)))))
        val actual = value.repairDestinations(setOf(old), mapOf(old to replacement)).primary.tabs.single()
        assertEquals(listOf(child, replacement), actual.history)
    }
    @Test fun repeatedMissingDestinationIsRepairedInBothPanes() {
        val value = layout().copy(secondary = pane("/missing"))
        val actual = value.repairDestinations(setOf("/missing"), mapOf("/missing" to "/new"))
        assertEquals("/new", actual.primary.tabs.first().location)
        assertEquals("/new", actual.secondary.tabs.first().location)
    }
    @Test fun aHiddenSecondaryLayoutIsPreservedEvenWhenItSharesTheRepairedPath() {
        val value = layout().copy(secondary = pane("/missing"), dual = false)
        assertEquals(value.secondary, value.repairDestinations(setOf("/missing"), mapOf("/missing" to "/new")).secondary)
    }
    @Test fun keepingUnavailableLocationsDoesNotChangeTheSavedLayout() {
        val value = layout()
        assertEquals(value, value.repairDestinations(setOf("/missing"), emptyMap()))
    }
    @Test fun availableOrUnopenedLocationsCannotBeChangedThroughRepairChoices() {
        for ((missing, replacements) in listOf(emptySet<String>() to mapOf("/working" to "/new"), setOf("/old-history") to mapOf("/old-history" to "/new"))) {
            try { layout().repairDestinations(missing, replacements); fail("Unreviewed replacement accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun aVirtualOrBlankDestinationCannotBePassedAsAReplacementFolder() {
        for (target in listOf("", "@recent", "@tag:Missing")) {
            try { layout().repairDestinations(setOf("/missing"), mapOf("/missing" to target)); fail("Invalid folder accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun aRepairedSmartFolderKeepsItsIntrinsicFilterAndTheTabsExtraQuery() {
        val smart = SearchRules(name = "Notes", nameTerms = listOf("2026"), tags = setOf("Red"), includeSubfolders = false, excludedFolderNames = setOf("Cache"))
        val tab = FinderTab(listOf("@smart:offline"), search = "draft")
        val value = layout().copy(primary = pane("@smart:offline").copy(tabs = listOf(tab)))
        val repaired = value.repairDestinations(setOf("@smart:offline"), mapOf("@smart:offline" to "/new"), mapOf("@smart:offline" to smart)).primary.tabs.single()
        val before = smart.copy(nameTerms = smart.nameTerms + tab.search)
        val after = repaired.rules.copy(name = repaired.search)
        for (name in listOf("Notes-2026-draft.md", "Notes-2026.md", "Draft-2026.md", "NOTES-2026-DRAFT.md")) {
            val entry = Entry(java.io.File("/test/$name"), size = 100)
            assertEquals(name, before.matches(entry, setOf("Red")), after.matches(entry, setOf("Red")))
        }
        assertEquals(smart.tags, after.tags)
        assertEquals(smart.includeSubfolders, after.includeSubfolders)
        assertEquals(smart.excludedFolderNames, after.excludedFolderNames)
        assertEquals(tab.search, repaired.search)
    }
    @Test fun workingSmartFolderTabsDoNotMaterializeOrChangeTheirFilters() {
        val value = layout().copy(secondary = pane("@smart:working"))
        val actual = value.repairDestinations(setOf("/missing"), mapOf("/missing" to "/new"), mapOf("@smart:working" to SearchRules(name = "Notes")))
        assertEquals(value.secondary, actual.secondary)
    }

}
