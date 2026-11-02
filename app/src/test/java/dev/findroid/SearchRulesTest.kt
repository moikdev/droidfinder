package dev.findroid

import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SearchRulesTest {
    private val entry = Entry(File("/notes/Project.txt"), folder = false, size = 20)

    @Test
    fun scopeSettingsAreActiveAndFolderNamesAreLiteralAndCaseSensitive() {
        assertFalse(SearchRules().scoped)
        assertTrue(SearchRules(includeSubfolders = false).active)
        val rules = SearchRules(excludedFolderNames = setOf("Archive", "要確認", "Client, Inc."))
        assertTrue(rules.active)
        for (name in rules.excludedFolderNames) {
            assertFalse(rules.matches(Entry(File("/root/$name"), folder = true), emptySet()))
            assertTrue(rules.matches(Entry(File("/root/$name"), folder = false), emptySet()))
        }
        assertTrue(rules.matches(Entry(File("/root/archive"), folder = true), emptySet()))
        assertTrue(rules.matches(Entry(File("/root/Archive-copy"), folder = true), emptySet()))
        assertEquals(rules, rules.remapTags(mapOf("Archive" to "Renamed")))
    }

    @Test
    fun allAnyAndNoneFollowTheCompleteTagTruthTable() {
        val selected = setOf("Research", "Approved")
        val candidates = listOf(emptySet(), setOf("Research"), setOf("Approved"), selected)
        for (mode in TagMatch.entries) {
            val rules = SearchRules(tags = selected, tagMatch = mode)
            for (assigned in candidates) {
                val expected =
                    when (mode) {
                        TagMatch.ALL -> assigned == selected
                        TagMatch.ANY -> assigned.isNotEmpty()
                        TagMatch.NONE -> assigned.isEmpty()
                    }
                assertEquals("$mode with $assigned", expected, rules.matches(entry, assigned))
                assertEquals(expected, rules.matches(entry, assigned + "Unrelated"))
            }
        }
    }

    @Test
    fun noSelectedTagsDisablesTheFilterInEveryMode() {
        for (mode in TagMatch.entries) {
            val rules = SearchRules(tagMatch = mode)
            assertFalse(rules.active)
            assertTrue(rules.matches(entry, emptySet()))
            assertTrue(rules.matches(entry, setOf("Research")))
        }
    }

    @Test
    fun legacySingleTagKeepsItsExactMatchBehavior() {
        val rules = SearchRules(tag = "Research")
        assertTrue(rules.active)
        assertEquals(setOf("Research"), rules.filterTags)
        assertTrue(rules.matches(entry, setOf("Research", "Other")))
        assertFalse(rules.matches(entry, emptySet()))
        assertFalse(rules.matches(entry, setOf("research")))
    }

    @Test
    fun legacyAndNewSelectionsAreCombinedWithoutDuplicates() {
        val rules = SearchRules(tag = "Research", tags = setOf("Research", "Approved"))
        assertEquals(setOf("Research", "Approved"), rules.filterTags)
        assertFalse(rules.matches(entry, setOf("Research")))
        assertTrue(rules.matches(entry, setOf("Research", "Approved")))
    }

    @Test
    fun everyTagModeStillRequiresOtherCriteria() {
        for (mode in TagMatch.entries) {
            val assigned = if (mode == TagMatch.NONE) emptySet() else setOf("Research")
            val rules = SearchRules(name = "Project", tags = setOf("Research"), tagMatch = mode)
            assertTrue(rules.matches(entry, assigned))
            assertFalse(rules.copy(name = "Missing").matches(entry, assigned))
            assertFalse(rules.copy(kind = "Image").matches(entry, assigned))
            assertFalse(rules.copy(minimumBytes = 30).matches(entry, assigned))
        }
    }

    @Test
    fun failingTagsNeverOpenFileContents() {
        val rules = SearchRules(tags = setOf("Research"), content = "secret")
        assertFalse(rules.matches(entry, emptySet(), read = { error("Unexpected read") }))
        assertTrue(
            rules
                .copy(tagMatch = TagMatch.NONE)
                .matches(
                    entry,
                    emptySet(),
                    read = { "contains secret".byteInputStream() },
                ),
        )
    }

    @Test(expected = CancellationException::class)
    fun tagFiltersDoNotSwallowCancellation() {
        SearchRules(tags = setOf("Research"))
            .matches(
                entry,
                emptySet(),
                check = {
                    throw CancellationException("Stopped")
                },
            )
    }

    @Test
    fun selectedTagsPreserveUnicodeSpacesAndPunctuation() {
        val tags = setOf("要確認", "Client, Inc.", "Project A")
        val rules = SearchRules(tags = tags)
        assertTrue(rules.matches(entry, tags))
        assertFalse(rules.matches(entry, setOf("要確認", "Client", "Inc.", "Project A")))
    }

    @Test
    fun remappingIsSimultaneousAndKeepsTheMode() {
        val rules =
            SearchRules(
                tag = "Research",
                tags = setOf("Research", "Approved"),
                tagMatch = TagMatch.NONE,
            )
        val changed = rules.remapTags(mapOf("Research" to "Approved", "Approved" to "Research"))
        assertEquals("Approved", changed.tag)
        assertEquals(setOf("Research", "Approved"), changed.tags)
        assertEquals(TagMatch.NONE, changed.tagMatch)
        assertEquals(
            rules,
            changed.remapTags(mapOf("Research" to "Approved", "Approved" to "Research")),
        )
    }
}
