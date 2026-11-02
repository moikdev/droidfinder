package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class RecentSearchesTest {
    private class Memory : RecentSearchPersistence {
        var enabledValue = true
        var values = emptyList<RecentSearch>()
        var brokenLoad = false
        var brokenFlag = false
        var failWrites = false
        var writes = 0
        override fun enabled(): Boolean { check(!brokenFlag); return enabledValue }
        override fun load(): List<RecentSearch> { check(!brokenLoad); return values }
        override fun setEnabled(enabled: Boolean) { check(!failWrites); enabledValue = enabled; writes++ }
        override fun save(searches: List<RecentSearch>): List<RecentSearch> {
            check(!failWrites)
            writes++
            values = searches
            return values
        }
    }
    private fun item(id: String, root: String = "/docs", rules: SearchRules = SearchRules(name = id)) = RecentSearch(id, root, rules, 10)
    private fun store(memory: Memory) = RecentSearchHistory(memory, clock = { 42 }, nextId = { "new-${memory.writes}" })

    @Test fun repeatedSearchMovesToFrontWithStableIdentityAndNewUseTime() {
        val previous = listOf(item("first"), item("second"))
        val result = recentSearchesAfterUse(previous, "/docs", SearchRules(name = "second"), 99) { error("Duplicate got a new identity") }
        assertEquals(listOf("second", "first"), result.map { it.id })
        assertEquals(99, result.first().usedAt)
        assertEquals(10, previous.last().usedAt)
    }
    @Test fun sameQueryAtDifferentRootsOrWithDifferentFiltersStaysDistinct() {
        val memory = Memory(); val history = store(memory)
        history.remember("/docs", SearchRules(name = "report"))
        history.remember("/elsewhere", SearchRules(name = "report"))
        history.remember("/docs", SearchRules(name = "report", includeSubfolders = false))
        assertEquals(3, history.state.searches.size)
        assertEquals("/docs", history.state.searches.first().root)
    }
    @Test fun historyKeepsOnlyTwentyMostRecentlyUsedSearches() {
        val memory = Memory(); val history = store(memory)
        repeat(25) { assertTrue(history.remember("/docs", SearchRules(name = "$it"))) }
        assertEquals((24 downTo 5).map(Int::toString), history.state.searches.map { it.rules.name })
        assertEquals(20, Memory().let { m -> m.values = memory.values; store(m).state.searches.size })
    }
    @Test fun storedRulesOwnTheirCollectionSnapshotsAndPreserveLiteralWhitespace() {
        val terms = mutableListOf(" draft ", "東京")
        val tags = mutableSetOf(" Research ")
        val names = mutableSetOf("Cache")
        val exclusions = mutableListOf(SearchExclusion("/docs/private", false))
        val rules = SearchRules(name = " Report ", nameTerms = terms, tags = tags, excludedFolderNames = names, excludedLocations = exclusions)
        val memory = Memory(); val history = store(memory)
        history.remember("/docs", rules)
        terms.clear(); tags.clear(); names.clear(); exclusions.clear()
        val saved = history.state.searches.single().rules
        assertEquals(listOf(" draft ", "東京"), saved.nameTerms)
        assertEquals(setOf(" Research "), saved.tags)
        assertEquals(setOf("Cache"), saved.excludedFolderNames)
        assertEquals(listOf(SearchExclusion("/docs/private", false)), saved.excludedLocations)
        assertEquals(" Report ", saved.name)
    }
    @Test fun emptyOrUnsupportedSearchDoesNotWriteAnything() {
        val memory = Memory(); val history = store(memory)
        for (root in listOf("/docs", "@smart:dynamic", "@archive:inside", "relative", "https://example.test"))
            assertFalse(history.remember(root, if (root == "/docs") SearchRules() else SearchRules(name = "report")))
        assertEquals(0, memory.writes)
    }
    @Test fun supportedRootsIncludeOnlyRealFoldersAndKnownLibraryKinds() {
        for (root in listOf("/", "/docs/東京", "content://provider/tree/opaque", "@recent", "@trash", "@tag:Work", "@collection:stable-id"))
            assertTrue(root, recentSearchRootSupported(root))
        for (root in listOf("", " ", "@tag:", "@collection:", "@smart:abc", "content:///missing", "/docs\nprivate", "relative"))
            assertFalse(root, recentSearchRootSupported(root))
    }
    @Test fun pauseStopsNewWritesButKeepsExistingSearchesAndInvalidatesOldDrafts() {
        val memory = Memory(); val history = store(memory)
        history.remember("/docs", SearchRules(name = "report"))
        val original = history.state.searches; val epoch = history.generation
        assertTrue(history.setEnabled(false)); assertTrue(history.generation > epoch)
        assertFalse(history.remember("/docs", SearchRules(name = "private")))
        assertEquals(original, history.state.searches)
        val paused = history.generation
        assertTrue(history.setEnabled(true)); assertTrue(history.generation > paused)
        assertEquals(original, store(memory).state.searches)
    }
    @Test fun settingTheSameRecordingPreferenceDoesNotInvalidatePendingDrafts() {
        val memory = Memory(); val history = store(memory)
        assertTrue(history.setEnabled(true))
        assertEquals(0, memory.writes)
        assertEquals(0L, history.generation)
    }
    @Test fun clearAndRemovePersistImmediatelyAndInvalidateStaleDrafts() {
        val memory = Memory(); val history = store(memory)
        history.remember("/docs", SearchRules(name = "report")); val id = history.state.searches.single().id
        val epoch = history.generation
        assertTrue(history.remove(id)); assertTrue(history.generation > epoch)
        assertTrue(store(memory).state.searches.isEmpty())
        history.remember("/docs", SearchRules(name = "notes")); val next = history.generation
        assertTrue(history.clear()); assertTrue(history.generation > next)
        assertTrue(store(memory).state.searches.isEmpty())
        val final = history.generation
        assertFalse(history.remove("absent")); assertEquals(final, history.generation)
    }
    @Test fun failedWritesRetainTheAcceptedStateAndExplainTheFailure() {
        val memory = Memory(); val history = store(memory)
        history.remember("/docs", SearchRules(name = "report"))
        val original = history.state; val epoch = history.generation
        memory.failWrites = true
        assertFalse(history.remove(original.searches.single().id))
        assertFalse(history.clear()); assertFalse(history.setEnabled(false))
        assertFalse(history.remember("/docs", SearchRules(name = "notes")))
        assertEquals(original.searches, history.state.searches)
        assertEquals(original.enabled, history.state.enabled)
        assertEquals(epoch, history.generation)
        assertNotNull(history.state.notice)
        memory.failWrites = false
        assertTrue(history.clear()); assertNull(history.state.notice)
    }
    @Test fun damagedHistoryIsPreservedUntilAnExplicitSuccessfulClear() {
        val memory = Memory().apply { brokenLoad = true; values = listOf(item("unreadable-original")) }
        val history = store(memory)
        assertTrue(history.state.searches.isEmpty()); assertNotNull(history.state.notice)
        assertFalse(history.remember("/docs", SearchRules(name = "new")))
        assertFalse(history.remove("unreadable-original")); history.remap(listOf("/docs" to "/new"))
        assertEquals("unreadable-original", memory.values.single().id)
        assertTrue(history.setEnabled(false)); assertNotNull(history.state.notice)
        assertTrue(history.clear()); assertFalse(history.state.enabled); assertNull(history.state.notice)
        assertTrue(memory.values.isEmpty())
        assertTrue(history.setEnabled(true)); assertTrue(history.remember("/docs", SearchRules(name = "new")))
    }
    @Test fun corruptRecordingFlagDoesNotSilentlyEnableRecording() {
        val memory = Memory().apply { brokenFlag = true }
        val history = store(memory)
        assertFalse(history.state.enabled); assertNotNull(history.state.notice)
        assertFalse(history.remember("/docs", SearchRules(name = "private")))
        assertTrue(history.clear()); assertFalse(memory.enabledValue)
        assertTrue(history.setEnabled(true)); assertTrue(history.remember("/docs", SearchRules(name = "chosen")))
    }
    @Test fun invalidAndDuplicateIdentitiesRejectTheWholeLoadedListWithoutWriting() {
        for (rows in listOf(listOf(item("duplicate"), item("duplicate", "/elsewhere")), listOf(item("bad").copy(usedAt = -1)),
                listOf(item("bad", rules = SearchRules(name = "report", minimumBytes = 20, maximumBytes = 10))))) {
            val memory = Memory().apply { values = rows }; val history = store(memory)
            assertTrue(history.state.searches.isEmpty()); assertNotNull(history.state.notice)
            assertFalse(history.remember("/docs", SearchRules(name = "replacement")))
            assertEquals(rows, memory.values); assertEquals(0, memory.writes)
        }
    }
    @Test fun suggestionsMatchUnicodeAndAllWordsAndDeduplicateRulesAcrossRoots() {
        val report = SearchRules(name = "東京 Report", tags = setOf("Work"))
        val searches = listOf(item("a", rules = report), item("b", "/other", report), item("c", rules = SearchRules(name = "東京 Notes")),
            item("d", rules = SearchRules(name = "東京 Plan")), item("e", rules = SearchRules(name = "東京 Budget")))
        assertEquals(listOf("a"), recentSearchSuggestions(searches, "東京 work").map { it.id })
        assertEquals(listOf("a", "c", "d"), recentSearchSuggestions(searches, "東京").map { it.id })
        assertTrue(recentSearchSuggestions(searches, " ").isEmpty())
        assertTrue(recentSearchSuggestions(searches, "unmatched").isEmpty())
    }
    @Test fun fullHistoryFilteringIncludesLocationWhileInlineSuggestionsDoNot() {
        val search = item("report", "/OnlyLocationMarker")
        assertTrue(recentSearchMatches(search, "OnlyLocationMarker"))
        assertTrue(recentSearchSuggestions(listOf(search), "OnlyLocationMarker").isEmpty())
    }
    @Test fun localRelocationHonorsBoundariesAndRemapsRulesAndTags() {
        val source = item("a", "/docs/Work", SearchRules(name = "report", tag = "Old", tags = setOf("Old", "Keep"),
            excludedLocations = listOf(SearchExclusion("/docs/Cache"), SearchExclusion("/docs-too/Keep"))))
        val result = remapRecentSearches(listOf(source), listOf("/docs" to "/moved", "@tag:Old" to "@tag:New")).single()
        assertEquals("/moved/Work", result.root)
        assertEquals(setOf("New", "Keep"), result.rules.filterTags)
        assertEquals(listOf("/moved/Cache", "/docs-too/Keep"), result.rules.excludedLocations.map { it.path })
        val memory = Memory().apply { values = listOf(source) }; val history = store(memory)
        history.remap(listOf("/docs" to "/moved")); assertEquals(1, memory.writes); assertEquals(1L, history.generation)
    }
    @Test fun opaqueProviderRelocationUsesExactRoutesAndConvergingSearchesKeepNewestIdentity() {
        val root = "content://provider/tree/root/document/opaque"
        val child = "$root%2Fchild"
        val replacement = "content://other/tree/base/document/new"
        val first = item("newer", root, SearchRules(name = "report", excludedLocations = listOf(SearchExclusion(child))))
        val same = first.copy(id = "older", root = replacement)
        val mapped = remapRecentSearches(listOf(first, same, item("child", child)), listOf(root to replacement))
        assertEquals(listOf("newer", "child"), mapped.map { it.id })
        assertEquals(replacement, mapped.first().root)
        assertEquals(child, mapped.first().rules.excludedLocations.single().path)
        assertEquals(child, mapped.last().root)
    }
    @Test fun anOversizedQueryReportsFailureWithoutLosingEarlierEntries() {
        val memory = Memory(); val history = store(memory)
        history.remember("/docs", SearchRules(name = "kept"))
        assertFalse(history.remember("/docs", SearchRules(name = "x".repeat(4097))))
        assertEquals("kept", history.state.searches.single().rules.name); assertNotNull(history.state.notice)
    }
}
