package dev.findroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SearchExclusionsTest {
    private fun local(rules: List<SearchExclusion>) = SearchExclusions(rules, ::organizationKey, ::localSearchAncestry)

    @Test fun exactFolderExclusionsKeepSameNamedSiblingsAndPrefixNeighbors() {
        val filter = local(listOf(SearchExclusion("/root/A/Cache"), SearchExclusion("/root/one.txt", folder = false)))
        for (path in listOf("/root/A/Cache", "/root/A/Cache/nested/note.txt", "/root/one.txt")) assertEquals(SearchAncestry.INSIDE, filter.check(path))
        for (path in listOf("/root/B/Cache/note.txt", "/root/A/Cache-copy/note.txt", "/root/one.txt/future")) assertEquals(SearchAncestry.OUTSIDE, filter.check(path))
        assertEquals(SearchAncestry.INSIDE, local(listOf(SearchExclusion("/"))).check("/root/note.txt"))
    }

    @Test fun actualLocalAliasesResolveParentsWithoutExcludingAFinalSymlinkTarget() {
        val root = Files.createTempDirectory("search-exclusion").toFile()
        try {
            val real = File(root, "Real").apply { mkdir() }
            val file = File(real, "note.txt").apply { writeText("Synthetic") }
            val alias = File(root, "Alias")
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            assertEquals(SearchAncestry.INSIDE, local(listOf(SearchExclusion(real.path))).check(File(alias, "note.txt").path))
            assertEquals(SearchAncestry.INSIDE, local(listOf(SearchExclusion(File(alias, "note.txt").path, false))).check(file.path))
            assertEquals(SearchAncestry.OUTSIDE, local(listOf(SearchExclusion(alias.path))).check(real.path))
            assertEquals(SearchAncestry.INSIDE, local(listOf(SearchExclusion(alias.path))).check(alias.path))
        } finally { root.deleteRecursively() }
    }

    @Test fun opaqueProviderIdsAreNeverComparedByPrefixAndUnknownAncestryStaysUnknown() {
        var queries = 0
        val root = "content://provider/tree/root/document/random-31"
        val child = "content://provider/tree/other/document/random-312"
        val alias = "content://provider/tree/other/document/random-31"
        fun identity(path: String) = if (path.startsWith("content:")) path.substringAfter("/document/") else path
        val filter = SearchExclusions(listOf(SearchExclusion(root)), ::identity) { _, _ -> queries++; SearchAncestry.UNKNOWN }
        assertEquals(SearchAncestry.INSIDE, filter.check(alias))
        assertEquals(0, queries)
        assertEquals(SearchAncestry.UNKNOWN, filter.check(child))
        assertEquals(1, queries)
        repeat(100) { assertEquals(SearchAncestry.UNKNOWN, filter.check("content://provider/document/file-$it", child)) }
        assertEquals(1, queries)
    }

    @Test fun knownParentChecksAreSharedAcrossFilesAndFileExclusionsNeverNeedAncestry() {
        var queries = 0
        val filter = SearchExclusions(listOf(SearchExclusion("content://provider/document/cache")), { it }) { path, _ ->
            queries++
            if (path == "content://provider/document/inside") SearchAncestry.INSIDE else SearchAncestry.OUTSIDE
        }
        repeat(200) { assertEquals(SearchAncestry.INSIDE, filter.check("content://provider/document/file-$it", "content://provider/document/inside")) }
        assertEquals(1, queries)
        repeat(200) { assertEquals(SearchAncestry.OUTSIDE, filter.check("content://provider/document/file-$it", "content://provider/document/outside")) }
        assertEquals(2, queries)
        val files = SearchExclusions(listOf(SearchExclusion("content://provider/document/one", false)), { it }) { _, _ -> error("File exclusions have no subtree") }
        assertEquals(SearchAncestry.OUTSIDE, files.check("content://provider/document/two"))
        assertEquals(SearchAncestry.INSIDE, files.check("content://provider/document/one"))
    }

    @Test fun remappingUsesExactProviderReferencesAndPreservesUnrelatedRules() {
        val a = "content://provider/document/a"
        val b = "$a%2Fb"
        val original = SearchRules(name = "Note", date = SearchDate.THIS_WEEK, size = SearchSize.OVER_1_GB,
            excludedLocations = listOf(SearchExclusion(a), SearchExclusion(b), SearchExclusion("/old/Cache")))
        val changed = original.remapRoutes(listOf(a to "content://replacement/document/new", "/old" to "/new"))
        assertEquals(listOf("content://replacement/document/new", b, "/new/Cache"), changed.excludedLocations.map { it.path })
        assertEquals(original.copy(excludedLocations = changed.excludedLocations), changed)
    }
    @Test fun convergingPortableReferencesRetainFolderScopeRegardlessOfOrder() {
        for (exclusions in listOf(listOf(SearchExclusion("/file", false), SearchExclusion("/folder")),
            listOf(SearchExclusion("/folder"), SearchExclusion("/file", false)))) {
            val mapped = SearchRules(excludedLocations = exclusions).remapLocations { "/replacement" }
            assertEquals(listOf(SearchExclusion("/replacement", true)), mapped.excludedLocations)
        }
    }

}
