package dev.findroid

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationCodecTest {
    @Test
    fun scopeRoundTripsInTabsAndBudgetsAndLegacyDefaultsToRecursive() {
        val rules =
            SearchRules(
                name = "Report",
                includeSubfolders = false,
                excludedFolderNames = setOf("Archive", "要確認", "Client, Inc.", " padded "),
            )
        assertEquals(rules, NavigationJson.rules(NavigationJson.rules(rules)))
        val tab =
            FinderTab(
                listOf("/root"),
                rules = rules,
                navigation =
                    TabNavigation(budgets = mapOf(ListingScope("/root", rules) to ListingBudget())),
            )
        assertEquals(
            tab,
            NavigationJson.tabs(JSONArray().put(NavigationJson.tab(tab)), "/fallback").single(),
        )
        assertEquals(SearchRules(), NavigationJson.rules(JSONObject()))
    }

    @Test
    fun multiTagRulesRoundTripWithEveryModeAndLegacySingleTag() {
        val legacy = JSONObject().put("tag", "Research")
        assertEquals(SearchRules(tag = "Research"), NavigationJson.rules(legacy))
        for (mode in TagMatch.entries) {
            val rules =
                SearchRules(
                    name = "invoice",
                    tag = "Legacy",
                    nameTerms = listOf("2026"),
                    tags = setOf("Research", "要確認", "Client, Inc."),
                    tagMatch = mode,
                )
            assertEquals(rules, NavigationJson.rules(NavigationJson.rules(rules)))
            val tab =
                FinderTab(
                    listOf("/folder"),
                    rules = rules,
                    navigation =
                        TabNavigation(
                            budgets = mapOf(ListingScope("/folder", rules) to ListingBudget()),
                        ),
                )
            assertEquals(
                tab,
                NavigationJson.tabs(JSONArray().put(NavigationJson.tab(tab)), "/fallback").single(),
            )
        }
        val futureMode =
            JSONObject().put("tags", JSONArray(listOf("Research"))).put("tagMatch", "UNKNOWN")
        assertEquals(TagMatch.ALL, NavigationJson.rules(futureMode).tagMatch)
        assertEquals(setOf("Research"), NavigationJson.rules(futureMode).filterTags)
    }

    @Test
    fun legacyAndCurrentTabsRoundTripWithoutLosingFocusOrPositions() {
        assertEquals(
            "/legacy",
            NavigationJson.tabs(JSONArray().put("/legacy"), "/fallback").single().location,
        )
        val old =
            JSONObject()
                .put("history", JSONArray(listOf("/A", "/B")))
                .put("index", 1)
                .put("query", "invoice")
        assertEquals(
            "invoice",
            NavigationJson.tabs(JSONArray().put(old), "/fallback").single().search,
        )
        val tab =
            FinderTab(
                listOf("content://provider/tree/root/document/Folder"),
                pinned = true,
                navigation =
                    TabNavigation(
                        setOf("content://provider/selected"),
                        "content://provider/focused",
                        "content://provider/selected",
                        setOf("content://provider/Folder"),
                        true,
                        mapOf(
                            ViewportKey("content://provider/Folder", "icons", "query") to
                                ViewportAnchor("content://provider/focused", 85, 27),
                        ),
                    ),
            )
        assertEquals(
            tab,
            NavigationJson.tabs(JSONArray().put(NavigationJson.tab(tab)), "/fallback").single(),
        )
    }

    @Test
    fun malformedTabDoesNotDiscardOtherTabsAndOffsetsAreBounded() {
        val tab =
            FinderTab(
                listOf("/good"),
                navigation =
                    TabNavigation(
                        positions =
                            mapOf(
                                ViewportKey("/good", "list") to
                                    ViewportAnchor(index = -9, offset = Int.MAX_VALUE),
                            ),
                    ),
            )
        val result =
            NavigationJson.tabs(
                JSONArray().put(JSONObject().put("history", "bad")).put(NavigationJson.tab(tab)),
                "/fallback",
            )
        assertTrue(result.any { it.location == "/good" })
        assertEquals(
            ViewportAnchor(index = 0, offset = 100_000),
            result.last().navigation.positions.values.single(),
        )
    }

    @Test
    fun savedLayoutsUseAtomicStorageAndPreserveAnUnrecognizedFile() {
        val root =
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "WorkspaceCodec-${System.nanoTime()}",
                )
                .apply { mkdirs() }
        try {
            val file = File(root, "layouts.json")
            val p =
                PaneSnapshot(
                    listOf(
                        FinderTab(
                            listOf("/folder"),
                            pinned = true,
                            rules =
                                SearchRules(
                                    includeSubfolders = false,
                                    excludedFolderNames = setOf("Archive"),
                                ),
                        ),
                    ),
                    0,
                    ViewMode.LIST,
                    6,
                    SortBy.NAME,
                    true,
                    GroupBy.NONE,
                    false,
                    true,
                    true,
                    false,
                    120,
                    240,
                    280,
                )
            val layout = SavedWorkspace("id", "Studio", p, p, true, false, .5f, "primary")
            SavedWorkspaceStore(file).write(listOf(layout))
            assertEquals(listOf(layout), SavedWorkspaceStore(file).load())
            val unsupported = "{\"version\":99,\"workspaces\":[]}"
            file.writeText(unsupported)
            val damaged = SavedWorkspaceStore(file)
            assertTrue(damaged.load().isEmpty())
            assertNotNull(damaged.error)
            assertTrue(runCatching { damaged.write(listOf(layout)) }.isFailure)
            assertEquals(unsupported, file.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
