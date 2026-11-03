package dev.findroid

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchRuleGroupCodecTest {
    private val group = SearchRuleGroup(SearchRuleJoin.ALL, listOf(
        SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchKindRule("PDF document"), SearchKindRule("Text document"))),
        SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchTagRule("Review"), SearchTagRule("Approved"))),
        SearchRuleGroup(SearchRuleJoin.NONE, listOf(SearchNameRule("Draft"))),
        SearchModifiedRule(SearchDate.THIS_WEEK), SearchModifiedRule(SearchDate.ANY, 14), SearchSizeRule(1025, 8192),
    ))
    private val rules = SearchRules(name = "Report", tags = setOf("Work"), tagMatch = TagMatch.NONE,
        locations = listOf("/tablet/Project", "content://provider/tree/root/document/opaque"),
        excludedLocations = listOf(SearchExclusion("/tablet/Project/Cache")), excludedFolderNames = setOf("Scratch"), ruleGroup = group)
    private fun pane(tab: FinderTab) = PaneSnapshot(listOf(tab), 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, true, false, 120, 240, 280)
    private fun archive(): SetupArchive {
        val tab = FinderTab(listOf(rules.locations.first()), rules = rules,
            navigation = TabNavigation(budgets = mapOf(ListingScope(rules.locations.first(), rules) to ListingBudget())))
        val workspace = SavedWorkspace(UUID.randomUUID().toString(), "Writing", pane(tab), pane(tab), true, false, .5f, "primary")
        val snapshot = SetupSnapshot(SidebarLayout(), OrganizationSnapshot(TagSnapshot(defaultTags, emptyMap()), emptyList()),
            listOf(SmartFolder(UUID.randomUUID().toString(), "Reports", rules.locations.first(), rules)), listOf(workspace), workspace.copy(id = "open"),
            emptyMap(), emptyMap(), emptyList(), SetupPreferences())
        return SetupArchive(roots = listOf(SetupRoot("local", "Tablet", "/tablet", SetupRootKind.LOCAL),
            SetupRoot("provider", "Provider", rules.locations[1], SetupRootKind.PROVIDER)),
            references = snapshot.locations().sorted().map { path ->
                if (path.startsWith("content://")) SetupReference(path, "provider", null)
                else SetupReference(path, "local", path.removePrefix("/tablet/").split('/'))
            }, snapshot = snapshot)
    }

    @Test fun legacyRulesOmitGroupsAndKeepTheirExactBehavior() {
        val legacy = SearchRules(name = "Report", tags = setOf("Review"), includeSubfolders = false)
        val value = NavigationJson.rules(legacy)
        assertFalse(value.has("ruleGroup"))
        assertEquals(legacy, NavigationJson.rules(value))
        assertEquals(legacy, NavigationJson.rules(JSONObject(value.toString())))
    }

    @Test fun allNodeTypesRoundTripThroughRulesTabsAndScanBudgets() {
        assertEquals(group, SearchRuleGroupJson.decode(JSONObject(SearchRuleGroupJson.encode(group).toString())))
        assertEquals(rules, NavigationJson.rules(JSONObject(NavigationJson.rules(rules).toString())))
        val tab = FinderTab(listOf(rules.locations.first()), rules = rules,
            navigation = TabNavigation(budgets = mapOf(ListingScope(rules.locations.first(), rules) to ListingBudget())))
        assertEquals(tab, NavigationJson.tabs(JSONArray().put(NavigationJson.tab(tab)), "/fallback").single())
        assertFalse(NavigationJson.rules(rules).toString().contains("snippet"))
        assertFalse(NavigationJson.rules(rules).toString().contains("nameRanges"))
    }

    @Test fun strictGroupCodecRejectsUnknownFieldsTypesAndInvalidLeaves() {
        val invalid = listOf<Any>(JSONObject.NULL, true, "group", JSONArray(),
            JSONObject().put("type", "future").put("value", "x"),
            JSONObject().put("type", "group").put("match", "FUTURE").put("rules", JSONArray().put(SearchRuleGroupJson.encode(group))),
            JSONObject().put("type", "group").put("match", "ANY").put("rules", JSONArray()),
            SearchRuleGroupJson.encode(group).put("snippet", "saved bytes"),
            JSONObject().put("type", "name").put("value", "Report"))
        for (value in invalid) assertThrows(Exception::class.java) { SearchRuleGroupJson.decode(value) }
        val leaves = listOf(
            JSONObject().put("type", "name").put("value", 12), JSONObject().put("type", "name").put("value", ""),
            JSONObject().put("type", "tag").put("value", "x".repeat(129)), JSONObject().put("type", "kind").put("value", "Any"),
            JSONObject().put("type", "name").put("value", "a").put("extra", true),
            JSONObject().put("type", "modified").put("date", "FUTURE").put("days", 0),
            JSONObject().put("type", "modified").put("date", "ANY").put("days", Int.MAX_VALUE.toLong() + 1),
            JSONObject().put("type", "modified").put("date", "ANY").put("days", "7"),
            JSONObject().put("type", "size").put("min", 0).put("max", 0),
            JSONObject().put("type", "size").put("min", 100).put("max", 20),
            JSONObject().put("type", "size").put("min", 1.25).put("max", 0),
        )
        for (leaf in leaves) {
            val value = JSONObject().put("type", "group").put("match", "ANY").put("rules", JSONArray().put(leaf))
            assertThrows(Exception::class.java) { SearchRuleGroupJson.decode(value) }
        }
    }

    @Test fun everyDecoderRejectsPresentButDamagedGroupsInsteadOfTreatingThemAsAbsent() {
        for (value in listOf<Any>(JSONObject.NULL, true, "unknown", JSONObject().put("type", "future"))) {
            val changed = NavigationJson.rules(rules).put("ruleGroup", value)
            assertThrows(Exception::class.java) { NavigationJson.rules(changed) }
            val history = JSONObject(RecentSearchJson.encode(listOf(RecentSearch("one", "/tablet/Project", rules, 1))))
            history.getJSONArray("searches").getJSONObject(0).put("rules", changed)
            assertThrows(Exception::class.java) { RecentSearchJson.decode(history.toString()) }
            val setup = JSONObject(SetupJson.encode(archive()))
            setup.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("ruleGroup", value)
            assertThrows(Exception::class.java) { SetupJson.decode(setup.toString()) }
            val tab = NavigationJson.tab(FinderTab(listOf("/tablet/Project"), rules = rules)).put("rules", changed)
            assertEquals("/fallback", NavigationJson.tabs(JSONArray().put(tab), "/fallback").single().location)
        }
    }

    @Test fun boundedDecodingRejectsExcessiveDepthTotalNodesAndUtf8Text() {
        fun encodedGroup(children: JSONArray) = JSONObject().put("type", "group").put("match", "ALL").put("rules", children)
        fun leaves(count: Int, value: String = "a") = JSONArray(List(count) { JSONObject().put("type", "name").put("value", value) })
        var deep = encodedGroup(leaves(1))
        repeat(100) { deep = encodedGroup(JSONArray().put(deep)) }
        assertThrows(Exception::class.java) { SearchRuleGroupJson.decode(deep) }
        val boundary = encodedGroup(JSONArray().put(encodedGroup(leaves(23))).put(encodedGroup(leaves(22))))
        SearchRuleGroupJson.decode(boundary)
        assertThrows(Exception::class.java) { SearchRuleGroupJson.decode(encodedGroup(JSONArray().put(encodedGroup(leaves(23))).put(encodedGroup(leaves(23))))) }
        assertThrows(Exception::class.java) { SearchRuleGroupJson.decode(encodedGroup(leaves(17, "a".repeat(512)))) }
        assertThrows(Exception::class.java) { SearchRuleGroupJson.decode(encodedGroup(leaves(6, "字".repeat(512)))) }
    }

    @Test fun setupCarriesGroupsThroughEveryContainerAndReviewedTagRemapping() {
        val original = archive()
        assertEquals(original, SetupJson.decode(SetupJson.encode(original)))
        val mapped = original.snapshot.mapLocations(mapOf("/tablet/Project" to "/tablet/Reconnected"), mapOf("Review" to "Reviewed"))
        val expected = rules.remapTags(mapOf("Review" to "Reviewed")).remapLocations { if (it == "/tablet/Project") "/tablet/Reconnected" else it }
        assertEquals(expected, mapped.searches.single().rules)
        for (workspace in mapped.workspaces + listOfNotNull(mapped.openTabs)) for (pane in listOf(workspace.primary, workspace.secondary)) {
            assertEquals(expected, pane.tabs.single().rules)
            assertEquals(expected, pane.tabs.single().navigation.budgets.keys.single().rules)
        }
        assertEquals(setOf("Work", "Review", "Approved"), original.snapshot.searches.single().rules.referencedTags)
        assertEquals(setOf("Work", "Reviewed", "Approved"), expected.referencedTags)
        assertEquals(rules.locations, original.snapshot.searches.single().rules.locations)
    }

    @Test fun strictRecentHistoryRoundTripsGroupsAndPreservesUnknownTreesUntilExplicitClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "group-codec-${System.nanoTime()}"
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val persistence = PreferencesRecentSearchPersistence(prefs)
            val item = RecentSearch("one", "/tablet/Project", rules, 1)
            assertEquals(listOf(item), RecentSearchJson.decode(RecentSearchJson.encode(listOf(item))))
            val unknown = JSONObject(RecentSearchJson.encode(listOf(item)))
            unknown.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").getJSONObject("ruleGroup").put("match", "FUTURE")
            val text = unknown.toString()
            assertTrue(prefs.edit().putString(RECENT_SEARCH_KEY, text).commit())
            val history = RecentSearchHistory(persistence)
            assertNotNull(history.state.notice)
            assertFalse(history.remember("/other", SearchRules(name = "new")))
            history.remap(listOf("@tag:Review" to "@tag:Reviewed"))
            assertEquals(text, prefs.getString(RECENT_SEARCH_KEY, null))
            assertTrue(history.clear())
            assertEquals(emptyList<RecentSearch>(), persistence.load())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun recentHistoryRejectsRepeatedKeysAndExcessiveRawJsonDepthBeforeAndroidParsing() {
        assertThrows(Exception::class.java) { RecentSearchJson.decode("{\"version\":1,\"version\":1,\"searches\":[]}") }
        val text = "{\"version\":1,\"searches\":[],\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        assertThrows(Exception::class.java) { RecentSearchJson.decode(text) }
    }
    @Test fun unreadableSmartFoldersCannotBeOverwrittenBySavingAnotherSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "smart-group-codec-${System.nanoTime()}"
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val store = PreferencesSmartFolderPersistence(prefs)
            val saved = archive().snapshot.searches
            store.save(saved)
            assertEquals(saved, store.load())
            val raw = JSONArray(prefs.getString("smartFolders", "[]"))
            raw.getJSONObject(0).getJSONObject("rules").getJSONObject("ruleGroup").put("match", "FUTURE")
            val text = raw.toString()
            assertTrue(prefs.edit().putString("smartFolders", text).commit())
            assertThrows(Exception::class.java) { store.load() }
            assertThrows(Exception::class.java) { store.save(emptyList()) }
            assertThrows(Exception::class.java) { store.save(listOf(saved.single().copy(id = "new", name = "New"))) }
            assertEquals(text, prefs.getString("smartFolders", null))
        } finally { context.deleteSharedPreferences(name) }
    }

}
