package dev.findroid

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SearchRuleIntegrationTest {
    private val entry = Entry(File("/project/Report.txt"), folder = false, size = 200, modified = 100)
    private val alternatives = SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchTagRule("Review"), SearchTagRule("Approved")))

    @Test fun groupsIntersectEveryExistingMetadataCriterionAndExactExclusion() {
        val rules = SearchRules(ruleGroup = alternatives)
        assertTrue(rules.active)
        assertTrue(rules.matches(entry, setOf("Review")))
        for (changed in listOf(rules.copy(name = "Missing"), rules.copy(kind = "Image"), rules.copy(tags = setOf("Work")),
            rules.copy(minimumBytes = 201), rules.copy(maximumBytes = 199), rules.copy(size = SearchSize.OVER_1_GB),
            rules.copy(excludedLocations = listOf(SearchExclusion("/project")))))
            assertFalse(changed.matches(entry, setOf("Review")))
        assertFalse(rules.copy(excludedFolderNames = setOf("Private")).matches(entry.copy(name = "Private", folder = true), setOf("Review")))
    }

    @Test fun nestedTagsAreReferencedForRefreshAndRemappingButNeverBecomeFlatAndRequirements() {
        val rules = SearchRules(tags = setOf("Work"), ruleGroup = alternatives)
        assertEquals(setOf("Work"), rules.filterTags)
        assertEquals(setOf("Work", "Review", "Approved"), rules.referencedTags)
        assertTrue(rules.matches(entry, setOf("Work", "Approved")))
        assertFalse(rules.matches(entry, setOf("Approved")))
        assertEquals(setOf("Work", "Reviewed", "Approved"), rules.remapTags(mapOf("Review" to "Reviewed")).referencedTags)
        assertEquals(setOf("Work", "Review", "Approved"), rules.referencedTags)
    }

    @Test fun aCompleteGroupChecksContentOnceAndMetadataFailureNeverOpensIt() {
        val rules = SearchRules(content = "needle", ruleGroup = SearchRuleGroup(SearchRuleJoin.ALL,
            listOf(alternatives, SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchKindRule("PDF document"), SearchKindRule("Text document"))))))
        var reads = 0
        assertTrue(rules.matches(entry, setOf("Review"), read = { reads++; "one needle here".byteInputStream() }))
        assertEquals(1, reads)
        assertFalse(rules.matches(entry, emptySet(), read = { error("Metadata failure read content") }))
        assertFalse(rules.matches(entry, setOf("Review"), read = { "different contents".byteInputStream() }))
    }

    @Test fun unknownSizeAlternativesCanMatchButUnresolvedNoneReportsAnIssueWithoutReading() {
        val unknown = entry.copy(sizeKnown = false)
        val size = SearchSizeRule(minimumBytes = 1)
        val any = SearchRules(content = "needle", ruleGroup = SearchRuleGroup(SearchRuleJoin.ANY, listOf(size, SearchNameRule("Report"))))
        val issues = mutableListOf<String>()
        assertTrue(any.matches(unknown, emptySet(), read = { "needle".byteInputStream() }, contentIssue = { issues += it }))
        assertTrue(issues.isEmpty())
        assertFalse(any.copy(ruleGroup = SearchRuleGroup(SearchRuleJoin.NONE, listOf(size)))
            .matches(unknown, emptySet(), read = { error("Unresolved size read contents") }, contentIssue = { issues += it }))
        assertEquals(1, issues.size)
        assertTrue(issues.single().startsWith("Unknown size:"))
        assertFalse(any.copy(minimumBytes = 1).matches(unknown, emptySet(), read = { error("Group bypassed flat size limit") }))
    }

    @Test fun finalNameHighlightsOnlyUsePositiveEvidenceFromPassingBranches() {
        val rules = SearchRules(ruleGroup = SearchRuleGroup(SearchRuleJoin.ALL, listOf(
            SearchRuleGroup(SearchRuleJoin.ANY, listOf(
                SearchRuleGroup(SearchRuleJoin.ALL, listOf(SearchNameRule("port"), SearchTagRule("Missing"))), SearchNameRule("Report"))),
            SearchRuleGroup(SearchRuleJoin.NONE, listOf(SearchNameRule("Draft"))))))
        val evidence = rules.evidence(entry, emptySet())!!
        assertEquals(listOf(MatchRange(0, 6)), evidence.nameRanges)
        assertFalse(evidence.reasons.any { it.contains("Missing") || it.contains("Draft") || it.contains("“port”") })
    }

    @Test fun tabAndScanBudgetRemappingPreservesGlobalLocationsAndExclusions() {
        val rules = SearchRules(locations = listOf("/project", "/other"), excludedLocations = listOf(SearchExclusion("/project/Cache")), ruleGroup = alternatives)
        val tab = FinderTab(listOf("/project"), rules = rules, navigation = TabNavigation(budgets = mapOf(ListingScope("/project", rules) to ListingBudget())))
        val mapped = tab.remap(listOf("/project" to "/moved", "@tag:Review" to "@tag:Reviewed"))
        assertEquals(listOf("/moved", "/other"), mapped.rules.locations)
        assertEquals(listOf(SearchExclusion("/moved/Cache")), mapped.rules.excludedLocations)
        assertEquals(setOf("Reviewed", "Approved"), mapped.rules.referencedTags)
        assertEquals(mapped.rules, mapped.navigation.budgets.keys.single().rules)
        assertEquals(alternatives, rules.ruleGroup)
    }

    @Test fun historyDraftEditingIsIndependentAndContainsOnlyRules() {
        val rules = SearchRules(ruleGroup = alternatives, locations = listOf("/project", "/other"))
        val history = recentSearchesAfterUse(emptyList(), "/project", rules, 1) { "one" }.single()
        val draft = rules.ruleGroup!!.draft().copy(join = SearchRuleJoin.NONE)
        val changed = rules.copy(ruleGroup = draft.applied())
        assertNotEquals(changed, history.rules)
        assertEquals(SearchRuleJoin.ANY, history.rules.ruleGroup!!.join)
        assertEquals(SearchRuleJoin.NONE, changed.ruleGroup!!.join)
        assertTrue(history.summary.contains("Any of (Tag is “Review”; Tag is “Approved”)"))
        assertTrue(recentSearchMatches(history, "Approved"))
    }

    @Test fun typingSuggestionsHaveBoundedUnicodeSafeCurrentFolderDescriptions() {
        val group = SearchRuleGroup(SearchRuleJoin.ALL, listOf(SearchNameRule("🙂".repeat(200)), SearchNameRule("🙂".repeat(200)), SearchTagRule("Review")))
        val item = RecentSearch("one", "/private/original", SearchRules(ruleGroup = group, locations = listOf("/private/original", "/other")), 1)
        val summary = item.suggestionSummary
        assertTrue(summary.codePointCount(0, summary.length) <= 360)
        assertTrue(summary.endsWith("…"))
        assertTrue(Charsets.UTF_8.newEncoder().canEncode(summary))
        assertFalse(summary.contains("chosen folders"))
        assertFalse(summary.contains("/private"))
        assertEquals(2, item.rules.locations.size)
    }

    @Test fun emptyDraftGroupsRemainEditableButCannotBeAppliedEvenInsideAny() {
        val draft = SearchGroupDraft(SearchRuleJoin.ANY, listOf(SearchConditionDraft(SearchNameRule("Report")), SearchGroupDraft()))
        draft.checkedDraft()
        assertThrows(IllegalArgumentException::class.java) { draft.applied() }
        val edited = draft.replaceGroup(listOf(1), SearchGroupDraft(SearchRuleJoin.NONE, listOf(SearchConditionDraft(SearchTagRule("Archived")))))
        assertEquals(SearchRuleJoin.NONE, edited.groupAt(listOf(1)).join)
        assertEquals(SearchRuleJoin.ANY, edited.applied().join)
        assertTrue(draft.groupAt(listOf(1)).children.isEmpty())
    }
}
