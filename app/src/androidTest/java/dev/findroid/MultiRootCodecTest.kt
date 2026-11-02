package dev.findroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MultiRootCodecTest {
    private val roots = listOf("/tablet/Project", "content://provider/tree/base/document/opaque-39", "/tablet/Project/Sub")
    private val rules = SearchRules(name = "Report", locations = roots, date = SearchDate.THIS_WEEK,
        excludedLocations = listOf(SearchExclusion("/tablet/Project/Private")))
    private fun pane(tab: FinderTab) = PaneSnapshot(listOf(tab), 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, true, false, 120, 240, 280)
    private fun archive(): SetupArchive {
        val tab = FinderTab(listOf(roots.first()), rules = rules, navigation = TabNavigation(budgets = mapOf(ListingScope(roots.first(), rules) to ListingBudget())))
        val workspace = SavedWorkspace(UUID.randomUUID().toString(), "Writing", pane(tab), pane(tab), true, false, .5f, "primary")
        val snapshot = SetupSnapshot(SidebarLayout(), OrganizationSnapshot(TagSnapshot(defaultTags, emptyMap()), emptyList()),
            listOf(SmartFolder(UUID.randomUUID().toString(), "Reports", roots.first(), rules)), listOf(workspace), workspace.copy(id = "open"),
            emptyMap(), emptyMap(), emptyList(), SetupPreferences())
        return SetupArchive(roots = listOf(SetupRoot("local", "Tablet", "/tablet", SetupRootKind.LOCAL), SetupRoot("provider", "Cloud", roots[1], SetupRootKind.PROVIDER)),
            references = snapshot.locations().sorted().map { path ->
                if (path.startsWith("content://")) SetupReference(path, "provider", null)
                else SetupReference(path, "local", path.removePrefix("/tablet/").split('/'))
            }, snapshot = snapshot)
    }
    @Test fun navigationOmitsDefaultScopeAndReadsEarlierRulesUnchanged() {
        val plain = SearchRules(name = "Report")
        assertFalse(NavigationJson.rules(plain).has("locations"))
        assertEquals(plain, NavigationJson.rules(NavigationJson.rules(plain)))
        assertEquals(rules, NavigationJson.rules(NavigationJson.rules(rules)))
    }
    @Test fun tabsAndScanBudgetsPreserveEveryChosenRootAndRule() {
        val tab = FinderTab(listOf(roots.first()), rules = rules, navigation = TabNavigation(budgets = mapOf(ListingScope(roots.first(), rules) to ListingBudget())))
        assertEquals(tab, NavigationJson.tabs(JSONArray().put(NavigationJson.tab(tab)), "/fallback").single())
    }
    @Test fun malformedAndUnboundedChosenRootArraysAreRejectedRatherThanTruncated() {
        for (value in listOf<Any>(JSONObject.NULL, "folder", JSONArray().put(12), JSONArray().put("@recent"),
            JSONArray().put(roots[0]).put(roots[0]), JSONArray((1..11).map { "/folder-$it" }))) {
            val json = NavigationJson.rules(SearchRules(name = "Report")).put("locations", value)
            assertThrows(Exception::class.java) { NavigationJson.rules(json) }
        }
    }
    @Test fun recentHistoryRetainsTheScopeAndRejectsMalformedScopeWithoutLosingCriteria() {
        val item = RecentSearch("one", roots.first(), rules, 1)
        assertEquals(listOf(item), RecentSearchJson.decode(RecentSearchJson.encode(listOf(item))))
        val json = JSONObject(RecentSearchJson.encode(listOf(item)))
        json.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("locations", JSONArray().put(true))
        assertThrows(Exception::class.java) { RecentSearchJson.decode(json.toString()) }
    }
    @Test fun setupBackupCarriesChosenReferencesAcrossAllSavedContainers() {
        val original = archive()
        assertEquals(original, SetupJson.decode(SetupJson.encode(original)))
        val mapped = original.snapshot.mapLocations(mapOf(roots[1] to "/tablet/Reconnected"))
        assertTrue(mapped.locations().contains("/tablet/Reconnected"))
        assertFalse(mapped.locations().contains(roots[1]))
        assertEquals(listOf(roots[0], "/tablet/Reconnected", roots[2]), mapped.searches.single().rules.locations)
        assertEquals(mapped.searches.single().rules, mapped.workspaces.single().primary.tabs.single().rules)
    }
    @Test fun setupCannotOmitAChosenRootFromItsReviewedReferenceInventory() {
        val json = JSONObject(SetupJson.encode(archive()))
        val array = json.getJSONArray("references")
        val index = (0 until array.length()).first { array.getJSONObject(it).getString("path") == roots[1] }
        array.remove(index)
        assertThrows(Exception::class.java) { SetupJson.decode(json.toString()) }
    }
}
