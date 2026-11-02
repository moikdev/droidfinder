package dev.findroid

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Behavioral cases for independently reported roots and shared admission budgets. */
class MultiRootLoaderTest {
    private fun item(path: String, folder: Boolean = false) =
        Entry(File(path), File(path).name, folder, 12, 0)

    private class Source(
        val tree: Map<String, List<Entry>>,
        val replies: Map<String, DirectoryReply> = emptyMap(),
        val identities: Map<String, String> = emptyMap(),
        val failAfterChildren: Set<String> = emptySet(),
    ) : ListingSource {
        val opened = mutableListOf<String>()
        var contentReads = 0
        var clock = 0L
        var clockStep = 1L
        var beforeFinish: (() -> Unit)? = null
        override suspend fun children(path: String, control: ListingControl, visit: suspend (Entry) -> Boolean): DirectoryReply {
            opened += path
            val entries = tree[path] ?: error("Unavailable test folder")
            for (entry in entries) {
                clock += clockStep
                if (!visit(entry)) break
            }
            beforeFinish?.invoke()
            if (path in failAfterChildren) error("Provider disconnected during listing")
            return replies[path] ?: DirectoryReply()
        }
        override fun read(path: String, control: ListingControl): Entry = error("Explicit items are not requested")
        override fun input(entry: Entry, control: ListingControl): InputStream {
            contentReads++
            return "needle".byteInputStream()
        }
        override fun traversable(entry: Entry) = true
        override fun searchIdentity(path: String) = identities[path] ?: organizationKey(path)
    }

    private fun request(vararg roots: String, rules: SearchRules = SearchRules(name = "Report"), budget: ListingBudget = ListingBudget()) =
        ListingRequest(roots.toList(), true, false, rules, emptyMap(), emptyMap(), compareBy { it.name }, budget)

    @Test fun independentRootsCompleteAndTheInitialPublicationPrecedesProviderIo() = runBlocking {
        val source = Source(mapOf("/one" to listOf(item("/one/Report one.txt")), "/two" to listOf(item("/two/Report two.txt"))))
        var initial: ListingBatch? = null
        val result = DirectoryLoader(source).load(request("/one", "/two"), ListingControl()) {
            if (initial == null) { initial = it; assertTrue(source.opened.isEmpty()) }
        }
        assertEquals(listOf("/one", "/two"), initial!!.progress.roots.map { it.path })
        assertTrue(initial!!.progress.roots.all { it.state == ListingRootState.WAITING })
        assertEquals(2, result.entries.size)
        assertTrue(result.progress.roots.all { it.state == ListingRootState.COMPLETE })
        assertFalse(result.progress.incomplete)
    }

    @Test fun unavailableFirstRootKeepsResultsFromTheOtherRoot() = runBlocking {
        val source = Source(mapOf("/good" to listOf(item("/good/Report.txt"))))
        val result = DirectoryLoader(source).load(request("/missing", "/good"), ListingControl()) {}
        assertEquals(listOf("/good/Report.txt"), result.entries.map { it.path })
        assertEquals(listOf(ListingRootState.UNAVAILABLE, ListingRootState.COMPLETE), result.progress.roots.map { it.state })
        assertEquals(1, result.progress.roots[0].issueCount)
        assertTrue(result.progress.incomplete)
    }

    @Test fun allUnavailableRootsRemainDistinctAndDoNotClaimACompletedEmptySearch() = runBlocking {
        val result = DirectoryLoader(Source(emptyMap())).load(request("/missing-one", "/missing-two"), ListingControl()) {}
        assertTrue(result.entries.isEmpty())
        assertEquals(2, result.progress.roots.size)
        assertTrue(result.progress.roots.all { it.state == ListingRootState.UNAVAILABLE })
        assertEquals(2, result.progress.issueCount)
        assertTrue(result.progress.incomplete)
    }

    @Test fun descendantFailureOnlyMarksItsOwnRootIncomplete() = runBlocking {
        val source = Source(mapOf("/one" to listOf(item("/one/Missing", true)), "/two" to listOf(item("/two/Report.txt"))))
        val result = DirectoryLoader(source).load(request("/one", "/two"), ListingControl()) {}
        assertEquals(listOf("/two/Report.txt"), result.entries.map { it.path })
        assertEquals(listOf(ListingRootState.INCOMPLETE, ListingRootState.COMPLETE), result.progress.roots.map { it.state })
        assertEquals("Unavailable test folder", result.progress.roots.first().reason)
    }

    @Test fun pendingProviderAndEmptyAvailableRootHaveDifferentStates() = runBlocking {
        val source = Source(mapOf("/pending" to emptyList(), "/empty" to emptyList()), mapOf("/pending" to DirectoryReply(loading = true)))
        val result = DirectoryLoader(source).load(request("/pending", "/empty"), ListingControl()) {}
        assertTrue(result.entries.isEmpty())
        assertTrue(result.progress.providerPending)
        assertEquals(listOf(ListingRootState.INCOMPLETE, ListingRootState.COMPLETE), result.progress.roots.map { it.state })
        assertTrue(result.progress.roots.first().providerPending)
        assertFalse(result.progress.roots.last().providerPending)
    }

    @Test fun overlappingRootsVisitIndependentlyAndAdmitOneCopyOfEachFile() = runBlocking {
        val nested = item("/root/Nested", true)
        val report = item("/root/Nested/Report.txt")
        val source = Source(mapOf("/root" to listOf(nested), nested.path to listOf(report)))
        val result = DirectoryLoader(source).load(request("/root", nested.path), ListingControl()) {}
        assertEquals(listOf(report.path), result.entries.map { it.path })
        assertEquals(2, source.opened.count { it == nested.path })
        assertTrue(result.progress.roots.all { it.state == ListingRootState.COMPLETE })
        assertEquals(3, result.progress.scanned)
    }

    @Test fun providerAliasesShareAdmissionAndDoNotRepeatAContentRead() = runBlocking {
        val first = item("/first/Report.txt")
        val second = item("/second/Report.txt")
        val source = Source(mapOf("/first" to listOf(first), "/second" to listOf(second)),
            identities = mapOf(first.path to "same-opaque-document", second.path to "same-opaque-document"))
        val result = DirectoryLoader(source).load(request("/first", "/second", rules = SearchRules(content = "needle")), ListingControl()) {}
        assertEquals(listOf(first.path), result.entries.map { it.path })
        assertEquals(1, source.contentReads)
        assertEquals(2, result.progress.scanned)
        assertTrue(result.progress.roots.all { it.state == ListingRootState.COMPLETE })
    }

    @Test fun shallowRulesApplySeparatelyToEveryChosenRoot() = runBlocking {
        val source = Source(mapOf("/one" to listOf(item("/one/Nested", true), item("/one/Report.txt")),
            "/two" to listOf(item("/two/Nested", true), item("/two/Report.txt"))))
        val result = DirectoryLoader(source).load(request("/one", "/two", rules = SearchRules(name = "Report", includeSubfolders = false)), ListingControl()) {}
        assertEquals(listOf("/one", "/two"), source.opened)
        assertEquals(2, result.entries.size)
        assertTrue(result.progress.roots.all { it.state == ListingRootState.COMPLETE })
    }

    @Test fun exactExcludedRootIsDeliberateAndDoesNotOpenTheProvider() = runBlocking {
        val source = Source(mapOf("/good" to listOf(item("/good/Report.txt"))))
        val result = DirectoryLoader(source).load(request("/excluded", "/good", rules = SearchRules(name = "Report",
            excludedLocations = listOf(SearchExclusion("/excluded")))), ListingControl()) {}
        assertEquals(listOf("/good"), source.opened)
        assertEquals(listOf(ListingRootState.EXCLUDED, ListingRootState.COMPLETE), result.progress.roots.map { it.state })
        assertFalse(result.progress.incomplete)
    }

    @Test fun resultCapMarksCurrentRootIncompleteAndLaterRootNotScanned() = runBlocking {
        val source = Source(mapOf("/large" to listOf(item("/large/Report one.txt"), item("/large/Report two.txt")), "/later" to emptyList()))
        val result = DirectoryLoader(source).load(request("/large", "/later", budget = ListingBudget(1, 100)), ListingControl()) {}
        assertEquals(1, result.entries.size)
        assertTrue(result.progress.resultLimit)
        assertEquals(listOf(ListingRootState.INCOMPLETE, ListingRootState.WAITING), result.progress.roots.map { it.state })
        assertEquals(listOf("/large"), source.opened)
    }

    @Test fun scanCapDoesNotMarkUnvisitedRootsComplete() = runBlocking {
        val source = Source(mapOf("/large" to listOf(item("/large/Other one.txt"), item("/large/Other two.txt")), "/later" to emptyList()))
        val result = DirectoryLoader(source).load(request("/large", "/later", budget = ListingBudget(100, 1)), ListingControl()) {}
        assertTrue(result.progress.scanLimit)
        assertEquals(listOf(ListingRootState.INCOMPLETE, ListingRootState.WAITING), result.progress.roots.map { it.state })
        assertTrue(result.entries.isEmpty())
    }

    @Test fun partialRootDisconnectPreservesItsFilesAndReportsIncompleteInsteadOfUnavailable() = runBlocking {
        val source = Source(mapOf("/partial" to listOf(item("/partial/Report.txt")), "/other" to emptyList()), failAfterChildren = setOf("/partial"))
        val result = DirectoryLoader(source).load(request("/partial", "/other"), ListingControl()) {}
        assertEquals(1, result.entries.size)
        assertEquals(listOf(ListingRootState.INCOMPLETE, ListingRootState.COMPLETE), result.progress.roots.map { it.state })
        assertTrue(result.progress.incomplete)
    }

    @Test fun cancellationAfterInitialPublicationNeverOpensAnyRoot() = runBlocking {
        val source = Source(mapOf("/one" to emptyList(), "/two" to emptyList()))
        val control = ListingControl()
        var initial: ListingBatch? = null
        try {
            DirectoryLoader(source).load(request("/one", "/two"), control) { initial = it; control.cancel() }
            fail("Cancellation was ignored")
        } catch (_: CancellationException) {
            assertTrue(source.opened.isEmpty())
            assertEquals(2, initial!!.progress.roots.size)
            assertTrue(initial!!.progress.roots.all { it.state == ListingRootState.WAITING })
        }
    }

    @Test fun unavailableGrantAliasDoesNotSuppressAnAccessibleReferenceToTheSameFolder() = runBlocking {
        val source = Source(mapOf("/available-alias" to listOf(item("/available-alias/Report.txt"))),
            identities = mapOf("/unavailable-alias" to "same-folder-id", "/available-alias" to "same-folder-id"))
        val result = DirectoryLoader(source).load(request("/unavailable-alias", "/available-alias"), ListingControl()) {}
        assertEquals(1, result.entries.size)
        assertEquals(listOf(ListingRootState.UNAVAILABLE, ListingRootState.COMPLETE), result.progress.roots.map { it.state })
        assertEquals(listOf("/unavailable-alias", "/available-alias"), source.opened)
    }

    @Test fun perRootSchedulingDeduplicatesCyclesBeforeTheyCanGrowTheQueue() = runBlocking {
        val source = Source(mapOf("/one" to listOf(item("/one", true), item("/one/Child", true), item("/one/ChildAlias", true)),
            "/one/Child" to listOf(item("/one/Report.txt")), "/two" to emptyList()),
            identities = mapOf("/one/Child" to "same-child-id", "/one/ChildAlias" to "same-child-id"))
        val result = DirectoryLoader(source).load(request("/one", "/two"), ListingControl()) {}
        assertEquals(listOf("/one", "/two", "/one/Child"), source.opened)
        assertEquals(1, result.entries.size)
        assertTrue(result.progress.roots.all { it.state == ListingRootState.COMPLETE })
    }

    @Test fun anExplicitNestedRootStillSearchesItsOwnChildrenWithShallowRules() = runBlocking {
        val source = Source(mapOf("/one" to listOf(item("/one/Nested", true)), "/one/Nested" to listOf(item("/one/Nested/Report.txt"))))
        val result = DirectoryLoader(source).load(request("/one", "/one/Nested", rules = SearchRules(name = "Report", includeSubfolders = false)), ListingControl()) {}
        assertEquals(listOf("/one", "/one/Nested"), source.opened)
        assertEquals(listOf("/one/Nested/Report.txt"), result.entries.map { it.path })
        assertTrue(result.progress.roots.all { it.state == ListingRootState.COMPLETE })
    }

    @Test fun unavailableExclusionAncestryIsIncompleteRatherThanAnIntentionalExclusion() = runBlocking {
        val source = Source(emptyMap())
        val result = DirectoryLoader(source).load(request("content://fixture/document/one", "content://fixture/document/two",
            rules = SearchRules(name = "Report", excludedLocations = listOf(SearchExclusion("content://fixture/document/excluded")))), ListingControl()) {}
        assertTrue(source.opened.isEmpty())
        assertEquals(2, result.progress.issueCount)
        assertTrue(result.progress.roots.all { it.state == ListingRootState.INCOMPLETE && it.issueCount == 1 })
        assertTrue(result.progress.roots.all { it.reason!!.contains("couldn't be verified") })
    }

    @Test fun anEarlyTimedPublicationDoesNotConsumeTheFirstFullChunkBeforeAProviderStall() = runBlocking {
        val source = Source(mapOf("/one" to (1..64).map { item("/one/Report-$it.txt") }))
        source.clockStep = 3
        val published = mutableListOf<ListingBatch>()
        source.beforeFinish = {
            // The next cursor read could block indefinitely. The completed first
            // chunk must have reached the UI before that read can happen.
            assertEquals(64, published.last().entries.size)
            assertTrue(published.any { it.entries.size in 1..63 })
        }
        val result = DirectoryLoader(source) { source.clock }.load(request("/one"), ListingControl()) { published += it }
        assertEquals(64, result.entries.size)
        assertEquals(ListingRootState.COMPLETE, result.progress.roots.single().state)
    }
}
