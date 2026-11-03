package dev.findroid

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SearchRuleGroupsTest {
    private val calendar = SearchCalendar(Instant.parse("2026-11-03T12:00:00Z").toEpochMilli(), ZoneId.of("UTC"), Locale.US)
    private val entry = Entry(File("/project/Report.txt"), folder = false, size = 2048, modified = calendar.now)
    private fun result(node: SearchRuleNode, candidate: Entry = entry, tags: Set<String> = emptySet()) = node.evaluate(candidate, tags, calendar) {}
    private fun group(join: SearchRuleJoin, vararg nodes: SearchRuleNode) = SearchRuleGroup(join, nodes.toList())

    @Test fun knownTagRulesFollowAllAnyAndNoneTruthTables() {
        for (join in SearchRuleJoin.entries) for (tags in listOf(emptySet(), setOf("Review"), setOf("Approved"), setOf("Review", "Approved"))) {
            val rule = group(join, SearchTagRule("Review"), SearchTagRule("Approved"))
            val expected = when (join) {
                SearchRuleJoin.ALL -> tags.size == 2
                SearchRuleJoin.ANY -> tags.isNotEmpty()
                SearchRuleJoin.NONE -> tags.isEmpty()
            }
            assertEquals("$join / $tags", expected, result(rule, tags = tags).outcome == SearchRuleOutcome.MATCH)
        }
    }

    @Test fun unknownSizesFollowTheCompleteThreeValuedTruthTable() {
        val nodes = mapOf(SearchRuleOutcome.MATCH to SearchTagRule("Match"),
            SearchRuleOutcome.NO_MATCH to SearchTagRule("Missing"), SearchRuleOutcome.UNKNOWN_SIZE to SearchSizeRule(minimumBytes = 1))
        for (join in SearchRuleJoin.entries) for (left in SearchRuleOutcome.entries) for (right in SearchRuleOutcome.entries) {
            val states = listOf(left, right)
            val expected = when (join) {
                SearchRuleJoin.ALL -> when {
                    SearchRuleOutcome.NO_MATCH in states -> SearchRuleOutcome.NO_MATCH
                    SearchRuleOutcome.UNKNOWN_SIZE in states -> SearchRuleOutcome.UNKNOWN_SIZE
                    else -> SearchRuleOutcome.MATCH
                }
                SearchRuleJoin.ANY -> when {
                    SearchRuleOutcome.MATCH in states -> SearchRuleOutcome.MATCH
                    SearchRuleOutcome.UNKNOWN_SIZE in states -> SearchRuleOutcome.UNKNOWN_SIZE
                    else -> SearchRuleOutcome.NO_MATCH
                }
                SearchRuleJoin.NONE -> when {
                    SearchRuleOutcome.MATCH in states -> SearchRuleOutcome.NO_MATCH
                    SearchRuleOutcome.UNKNOWN_SIZE in states -> SearchRuleOutcome.UNKNOWN_SIZE
                    else -> SearchRuleOutcome.MATCH
                }
            }
            assertEquals("$join / $left / $right", expected,
                result(group(join, nodes.getValue(left), nodes.getValue(right)), entry.copy(sizeKnown = false), setOf("Match")).outcome)
        }
    }

    @Test fun nestedKindAndTagAlternativesDoNotTurnIntoFlatTagRequirements() {
        val rules = group(SearchRuleJoin.ALL,
            group(SearchRuleJoin.ANY, SearchKindRule("PDF document"), SearchKindRule("Text document")),
            group(SearchRuleJoin.ANY, SearchTagRule("Review"), SearchTagRule("Approved")))
        for (tags in listOf(setOf("Review"), setOf("Approved"), setOf("Review", "Approved")))
            assertEquals(SearchRuleOutcome.MATCH, result(rules, tags = tags).outcome)
        assertEquals(SearchRuleOutcome.NO_MATCH, result(rules).outcome)
        assertEquals(SearchRuleOutcome.NO_MATCH, result(rules, entry.copy(name = "Report.jpg"), setOf("Review")).outcome)
        assertEquals(setOf("Review", "Approved"), rules.referencedTags)
    }

    @Test fun failedAlternativesDoNotContributeNameOrTagEvidence() {
        val rules = group(SearchRuleJoin.ANY,
            group(SearchRuleJoin.ALL, SearchNameRule("Report"), SearchTagRule("Missing")), SearchTagRule("Approved"))
        val evidence = result(rules, tags = setOf("Approved"))
        assertEquals(SearchRuleOutcome.MATCH, evidence.outcome)
        assertTrue(evidence.nameTerms.isEmpty())
        assertEquals(listOf("Tag: Approved"), evidence.reasons)
    }

    @Test fun noneDoesNotHighlightPositiveNamesFromRejectedChildren() {
        val rules = group(SearchRuleJoin.NONE,
            group(SearchRuleJoin.ALL, SearchNameRule("Report"), SearchTagRule("Missing")), SearchNameRule("Invoice"))
        val evidence = result(rules)
        assertEquals(SearchRuleOutcome.MATCH, evidence.outcome)
        assertTrue(evidence.nameTerms.isEmpty())
        assertEquals(listOf("Rule group: none of 2 rules matched"), evidence.reasons)
    }

    @Test fun everyGroupShortCircuitsDeterministicallyAndChecksCancellationPerNode() {
        for ((join, first) in listOf(SearchRuleJoin.ALL to SearchTagRule("Missing"),
            SearchRuleJoin.ANY to SearchTagRule("Match"), SearchRuleJoin.NONE to SearchTagRule("Match"))) {
            var visits = 0
            group(join, first, SearchNameRule("Report")).evaluate(entry, setOf("Match"), calendar) { visits++ }
            assertEquals(join.name, 2, visits)
        }
        var visits = 0
        assertThrows(CancellationException::class.java) {
            group(SearchRuleJoin.ALL, SearchNameRule("Report"), SearchKindRule("Text document"))
                .evaluate(entry, emptySet(), calendar) { if (++visits == 3) throw CancellationException() }
        }
        assertEquals(3, visits)
    }

    @Test fun allMergesOnlyItsPassingNameTermsAndReasonsWithoutDuplicates() {
        val evidence = result(group(SearchRuleJoin.ALL, SearchNameRule("Report"), SearchNameRule("Report"), SearchKindRule("Text document")))
        assertEquals(listOf("Report"), evidence.nameTerms)
        assertEquals(2, evidence.reasons.size)
    }

    @Test fun modifiedRulesShareOneCalendarAndSizeRulesRejectFolders() {
        assertEquals(SearchRuleOutcome.MATCH, result(SearchModifiedRule(SearchDate.TODAY)).outcome)
        assertEquals(SearchRuleOutcome.NO_MATCH, result(SearchModifiedRule(SearchDate.TODAY), entry.copy(modified = calendar.now - 86_400_000L)).outcome)
        assertEquals(SearchRuleOutcome.MATCH, result(SearchModifiedRule(SearchDate.ANY, 1), entry.copy(modified = calendar.now - 86_400_000L)).outcome)
        assertEquals(SearchRuleOutcome.NO_MATCH, result(SearchModifiedRule(SearchDate.ANY, 1), entry.copy(modified = calendar.now - 86_400_001L)).outcome)
        assertEquals(SearchRuleOutcome.MATCH, result(SearchSizeRule(2048, 2048)).outcome)
        assertEquals(SearchRuleOutcome.NO_MATCH, result(SearchSizeRule(2049)).outcome)
        assertEquals(SearchRuleOutcome.NO_MATCH, result(SearchSizeRule(maximumBytes = 4096), entry.copy(folder = true)).outcome)
    }

    @Test fun incomingAndExposedChildListsCannotMutateAnAppliedGroup() {
        val children = mutableListOf<SearchRuleNode>(SearchNameRule("Report"))
        val rules = SearchRuleGroup(SearchRuleJoin.ALL, children)
        children.clear()
        assertEquals(1, rules.children.size)
        assertThrows(UnsupportedOperationException::class.java) { (rules.children as MutableList<SearchRuleNode>).clear() }
        assertEquals(SearchRuleOutcome.MATCH, result(rules).outcome)
    }

    @Test fun constructorsBoundDepthNodesAndCombinedUtf8Text() {
        var nested = group(SearchRuleJoin.ALL, SearchNameRule("Report"))
        repeat(SEARCH_GROUP_MAX_DEPTH - 1) { nested = group(SearchRuleJoin.ALL, nested) }
        assertThrows(IllegalArgumentException::class.java) { group(SearchRuleJoin.ALL, nested) }
        assertEquals(SEARCH_GROUP_MAX_NODES - 1,
            SearchRuleGroup(SearchRuleJoin.ANY, List(SEARCH_GROUP_MAX_NODES - 1) { SearchNameRule("a") }).children.size)
        assertThrows(IllegalArgumentException::class.java) { SearchRuleGroup(SearchRuleJoin.ALL, List(SEARCH_GROUP_MAX_NODES) { SearchNameRule("a") }) }
        assertThrows(IllegalArgumentException::class.java) { SearchRuleGroup(SearchRuleJoin.ALL, emptyList()) }
        SearchRuleGroup(SearchRuleJoin.ALL, List(16) { SearchNameRule("a".repeat(512)) })
        assertThrows(IllegalArgumentException::class.java) { SearchRuleGroup(SearchRuleJoin.ALL, List(17) { SearchNameRule("a".repeat(512)) }) }
        assertThrows(IllegalArgumentException::class.java) { SearchRuleGroup(SearchRuleJoin.ALL, List(6) { SearchNameRule("字".repeat(512)) }) }
    }

    @Test fun invalidLeafValuesFailBeforeTheyCanBecomeSavedRules() {
        for (text in listOf("", " ", "a\nb", "\uD800", "a".repeat(513)))
            assertThrows(IllegalArgumentException::class.java) { SearchNameRule(text) }
        assertThrows(IllegalArgumentException::class.java) { SearchKindRule("Any") }
        assertThrows(IllegalArgumentException::class.java) { SearchSizeRule() }
        assertThrows(IllegalArgumentException::class.java) { SearchSizeRule(20, 10) }
        assertThrows(IllegalArgumentException::class.java) { SearchModifiedRule(SearchDate.ANY, 0) }
        assertThrows(IllegalArgumentException::class.java) { SearchModifiedRule(SearchDate.TODAY, 1) }
    }

    @Test fun nestedTagRenamingKeepsEveryBooleanBranchAndTheOriginalIndependent() {
        val before = group(SearchRuleJoin.NONE,
            group(SearchRuleJoin.ANY, SearchTagRule("Review"), SearchTagRule("Approved")), SearchNameRule("Draft"))
        val mapped = before.remapRuleTags(mapOf("Review" to "Reviewed")) as SearchRuleGroup
        assertEquals(setOf("Reviewed", "Approved"), mapped.referencedTags)
        assertEquals(setOf("Review", "Approved"), before.referencedTags)
        assertTrue(mapped.summary.contains("None of (Any of (Tag is “Reviewed”"))
        assertTrue(mapped.summary.contains("Name contains “Draft”"))
    }
}
