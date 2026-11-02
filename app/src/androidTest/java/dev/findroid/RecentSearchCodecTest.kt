package dev.findroid

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecentSearchCodecTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun entry(id: String = "one", rules: SearchRules = SearchRules(name = "Report")) =
        RecentSearch(id, "content://provider/tree/root/document/opaque", rules, 1_800_000_000_000)
    private fun preferences(check: (android.content.SharedPreferences, PreferencesRecentSearchPersistence) -> Unit) {
        val name = "recent-search-codec-${System.nanoTime()}"
        try { context.getSharedPreferences(name, Context.MODE_PRIVATE).let { check(it, PreferencesRecentSearchPersistence(it)) } }
        finally { context.deleteSharedPreferences(name) }
    }
    private fun rejects(text: String) { assertTrue("Damaged criteria accepted: ${text.take(100)}", runCatching { RecentSearchJson.decode(text) }.isFailure) }

    @Test fun allCriteriaRoundTripExactlyWithoutEntriesOrSnippets() {
        val rules = SearchRules(name = " Report 東京 ", kind = "Text document", tag = "Old", days = 1,
            minimumBytes = 1025, maximumBytes = 99999, content = " literal needle ", nameTerms = listOf(" 2026 ", ""),
            tags = setOf("Research", "Work"), tagMatch = TagMatch.NONE, includeSubfolders = false,
            excludedFolderNames = setOf("Cache", "Privé"), excludedLocations = listOf(SearchExclusion("/docs/private", false),
                SearchExclusion("content://provider/tree/root/document/nope")), date = SearchDate.TODAY, size = SearchSize.UNDER_1_MB)
        val rows = listOf(entry(rules = rules))
        val text = RecentSearchJson.encode(rows)
        assertEquals(rows, RecentSearchJson.decode(text))
        assertEquals(setOf("version", "searches"), JSONObject(text).keys().asSequence().toSet())
        assertEquals(setOf("id", "root", "usedAt", "rules"), JSONObject(text).getJSONArray("searches").getJSONObject(0).keys().asSequence().toSet())
        assertFalse(text.contains("snippet")); assertFalse(text.contains("entries"))
    }
    @Test fun futureOrUnknownFieldsAndCoercedTypesCannotSilentlyChangeRules() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) }, { it.put("version", "1") }, { it.put("future", true) },
            { it.getJSONArray("searches").getJSONObject(0).put("usedAt", "123") },
            { it.getJSONArray("searches").getJSONObject(0).put("usedAt", 1.25) },
            { it.getJSONArray("searches").getJSONObject(0).put("id", 12) },
            { it.getJSONArray("searches").getJSONObject(0).put("future", true) },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("includeSubfolders", "false") },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("min", "123") },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("days", 1.2) },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("tags", JSONArray().put(12)) },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("terms", JSONObject.NULL) },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("tagMatch", "FUTURE") },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("date", "FUTURE") },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("size", "FUTURE") },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("future", "unreadable criteria") },
            { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").remove("kind") },
        )
        for (mutation in mutations) rejects(JSONObject(RecentSearchJson.encode(listOf(entry()))).also(mutation).toString())
    }
    @Test fun corruptAndFuturePreferencesKeepTheirExactBytesUntilClear() = preferences { prefs, persistence ->
        for (text in listOf("{broken", "{\"version\":7,\"searches\":[]}", "{\"version\":1,\"searches\":null}")) {
            assertTrue(prefs.edit().putString(RECENT_SEARCH_KEY, text).commit())
            val history = RecentSearchHistory(persistence)
            assertNotNull(history.state.notice)
            assertFalse(history.remember("/docs", SearchRules(name = "new")))
            history.remap(listOf("/docs" to "/moved")); assertFalse(history.remove("any"))
            assertEquals(text, prefs.getString(RECENT_SEARCH_KEY, null))
            assertTrue(history.clear()); assertEquals(emptyList<RecentSearch>(), persistence.load())
        }
    }
    @Test fun malformedFlagCanBeExplicitlyRepairedWithoutEnablingRecording() = preferences { prefs, persistence ->
        assertTrue(prefs.edit().putString(REMEMBER_SEARCHES_KEY, "broken").commit())
        val history = RecentSearchHistory(persistence)
        assertFalse(history.state.enabled); assertNotNull(history.state.notice)
        assertTrue(history.clear()); assertFalse(persistence.enabled()); assertNull(history.state.notice)
        assertTrue(history.setEnabled(true)); assertTrue(persistence.enabled())
    }
    @Test fun durablePauseClearAndRemovalSurviveNewStoreInstances() = preferences { _, persistence ->
        val history = RecentSearchHistory(persistence)
        history.remember("/docs", SearchRules(name = "Report")); history.remember("/docs", SearchRules(name = "Notes"))
        assertTrue(history.remove(history.state.searches.last().id))
        assertTrue(history.setEnabled(false))
        val loaded = RecentSearchHistory(persistence)
        assertFalse(loaded.state.enabled); assertEquals("Notes", loaded.state.searches.single().rules.name)
        assertTrue(loaded.clear()); assertTrue(RecentSearchHistory(persistence).state.searches.isEmpty())
        assertFalse(RecentSearchHistory(persistence).state.enabled)
    }
    @Test fun utf8BudgetEvictsOldestSearchesAndRejectsOneOversizedEntry() = preferences { _, persistence ->
        val large = SearchRules(name = "東京", nameTerms = List(30) { "é".repeat(900) + it })
        val values = List(8) { entry("entry-$it", large.copy(name = "東京 $it")) }
        val saved = persistence.save(values)
        assertTrue(saved.size in 2..7); assertEquals(values.take(saved.size), saved)
        assertTrue(RecentSearchJson.encode(saved).toByteArray(Charsets.UTF_8).size <= RecentSearchJson.TOTAL_BYTES)
        assertEquals(saved, persistence.load())
        assertTrue(runCatching { persistence.save(listOf(entry("oversized", large.copy(nameTerms = List(40) { "é".repeat(1000) })))) }.isFailure)
        assertEquals(saved, persistence.load())
        rejects(" ".repeat(RecentSearchJson.TOTAL_BYTES + 1))
    }
    @Test fun duplicatesInvalidRootsBoundsAndUnboundedListsAreRejected() {
        rejects(RecentSearchJson.encode(listOf(entry(), entry())))
        val source = RecentSearchJson.encode(listOf(entry()))
        for ((key, value) in listOf("root" to "@smart:missing", "usedAt" to -1))
            rejects(JSONObject(source).also { it.getJSONArray("searches").getJSONObject(0).put(key, value) }.toString())
        for ((key, value) in listOf("min" to -1, "days" to Long.MAX_VALUE, "terms" to JSONArray(List(101) { "x" })))
            rejects(JSONObject(source).also { it.getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put(key, value) }.toString())
        rejects(JSONObject().put("version", 1).put("searches", JSONArray(List(21) { RecentSearchJson.entry(entry("$it")) })).toString())
    }
    @Test fun setupPreferenceAllowlistCannotExportOrRestoreSearchHistory() {
        assertFalse(SetupTransaction.KEYS.values.flatten().contains(RECENT_SEARCH_KEY))
        assertFalse(SetupTransaction.KEYS.values.flatten().contains(REMEMBER_SEARCHES_KEY))
    }
}
