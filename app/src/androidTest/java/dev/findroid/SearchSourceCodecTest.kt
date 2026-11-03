package dev.findroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchSourceCodecTest {
    private val rules = SearchRules(name = "Report", tags = setOf("Review"), locations = listOf("/tablet/Project"),
        excludedLocations = listOf(SearchExclusion("/tablet/Project/Cache")),
        ruleGroup = SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchKindRule("PDF document"), SearchKindRule("Text document"))),
        source = SearchSource.LOCAL_INDEX)
    private fun pane(tab: FinderTab) = PaneSnapshot(listOf(tab), 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, true, false, 120, 240, 280)
    private fun archive(): SetupArchive {
        val tab = FinderTab(listOf("/tablet/Project"), rules = rules,
            navigation = TabNavigation(budgets = mapOf(ListingScope("/tablet/Project", rules) to ListingBudget())))
        val workspace = SavedWorkspace(newIndexId(), "Writing", pane(tab), pane(tab), true, false, .5f, "primary")
        val snapshot = SetupSnapshot(SidebarLayout(), OrganizationSnapshot(TagSnapshot(defaultTags, emptyMap()), emptyList()),
            listOf(SmartFolder(newIndexId(), "Reports", "/tablet/Project", rules)), listOf(workspace), workspace.copy(id = "open"),
            emptyMap(), emptyMap(), emptyList(), SetupPreferences())
        return SetupArchive(roots = listOf(SetupRoot("local", "Tablet", "/tablet", SetupRootKind.LOCAL)),
            references = snapshot.locations().sorted().map { SetupReference(it, "local", it.removePrefix("/tablet/").split('/')) }, snapshot = snapshot)
    }

    @Test fun legacySourcesRemainLiveAndExplicitIndexedSourcesRoundTripEveryContainer() {
        val live = rules.copy(source = SearchSource.LIVE)
        assertFalse(NavigationJson.rules(live).has("source")); assertEquals(live, NavigationJson.rules(NavigationJson.rules(live)))
        assertEquals(rules, NavigationJson.rules(JSONObject(NavigationJson.rules(rules).toString())))
        val tab = FinderTab(listOf("/tablet/Project"), rules = rules)
        assertEquals(tab, NavigationJson.tabs(JSONArray().put(NavigationJson.tab(tab)), "/fallback").single())
        val recent = listOf(RecentSearch("search", "/tablet/Project", rules, 1))
        assertEquals(recent, RecentSearchJson.decode(RecentSearchJson.encode(recent)))
        val archive = archive(); assertEquals(archive, SetupJson.decode(SetupJson.encode(archive)))
        for (workspace in archive.snapshot.workspaces + listOfNotNull(archive.snapshot.openTabs))
            for (pane in listOf(workspace.primary, workspace.secondary)) assertEquals(SearchSource.LOCAL_INDEX, pane.tabs.single().rules.source)
    }

    @Test fun presentButUnknownOrWrongTypeSourcesAreRejectedByRulesHistoryTabsAndSetup() {
        for (source in listOf<Any>("FUTURE", "", "live", 1, true, JSONObject.NULL, JSONArray(), JSONObject())) {
            val damaged = NavigationJson.rules(rules).put("source", source)
            assertThrows(Exception::class.java) { NavigationJson.rules(damaged) }
            val recent = JSONObject(RecentSearchJson.encode(listOf(RecentSearch("search", "/tablet/Project", rules, 1))))
            recent.getJSONArray("searches").getJSONObject(0).put("rules", damaged)
            assertThrows(Exception::class.java) { RecentSearchJson.decode(recent.toString()) }
            val tab = NavigationJson.tab(FinderTab(listOf("/tablet/Project"), rules = rules)).put("rules", damaged)
            assertEquals("/fallback", NavigationJson.tabs(JSONArray().put(tab), "/fallback").single().location)
            val setup = JSONObject(SetupJson.encode(archive()))
            setup.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("source", source)
            assertThrows(Exception::class.java) { SetupJson.decode(setup.toString()) }
        }
    }

    @Test fun portableSavedSourceContainsNoCacheCatalogGenerationsRowsOrFreshnessState() {
        val encoded = SetupJson.encode(archive())
        for (forbidden in listOf("generation", "attemptOwner", "refreshedAt", "metadata.sqlite", "local-search-index", "storageBytes", "scannedFrom", "changedAt")) assertFalse(encoded.contains(forbidden))
        val mapped = archive().snapshot.mapLocations(mapOf("/tablet/Project" to "/reconnected/Writing"))
        assertEquals(SearchSource.LOCAL_INDEX, mapped.searches.single().rules.source)
        assertEquals(listOf("/reconnected/Writing"), mapped.searches.single().rules.locations)
    }
}
