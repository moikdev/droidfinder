package dev.findroid

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryLoaderTest {
    private fun file(
        name: String,
        parent: String = "/root",
        folder: Boolean = false,
        modified: Long = 0,
    ) = Entry(File("$parent/$name"), name, folder, 12, modified)

    private class Source(
        val tree: Map<String, List<Entry>>,
        val failures: Set<String> = emptySet(),
        val replies: Map<String, DirectoryReply> = emptyMap(),
    ) : ListingSource {
        var clock = 0L
        val opened = mutableListOf<String>()

        override suspend fun children(
            path: String,
            control: ListingControl,
            visit: suspend (Entry) -> Boolean,
        ): DirectoryReply {
            opened += path
            if (path in failures) error("Offline fixture")
            for (entry in tree[path].orEmpty()) {
                clock++
                if (!visit(entry)) break
            }
            return replies[path] ?: DirectoryReply()
        }

        override fun read(path: String, control: ListingControl) =
            tree.values.flatten().firstOrNull { it.path == path } ?: error("Missing item")

        override fun input(entry: Entry, control: ListingControl): InputStream =
            "text content".byteInputStream()

        override fun traversable(entry: Entry) = entry.name != "Protected"

        override fun searchable(entry: Entry) = entry.name != "Symbolic.txt"
    }

    private fun request(
        budget: ListingBudget = ListingBudget(),
        recursive: Boolean = false,
        rules: SearchRules = SearchRules(),
    ) =
        ListingRequest(
            listOf("/root"),
            recursive,
            false,
            rules,
            emptyMap(),
            emptyMap(),
            compareBy { it.name },
            budget,
        )

    @Test
    fun shallowSearchNeverOpensChildrenEvenWhenRecursiveWasRequested() = runBlocking {
        val nested = file("Nested", folder = true)
        val source =
            Source(mapOf("/root" to listOf(file("Report.txt"), nested)), setOf(nested.path))
        val result =
            DirectoryLoader(source).load(
                request(
                    recursive = true,
                    rules = SearchRules(name = "Report", includeSubfolders = false),
                ),
                ListingControl(),
            ) {}
        assertEquals(listOf("Report.txt"), result.entries.map { it.name })
        assertEquals(listOf("/root"), source.opened)
        assertFalse(result.progress.incomplete)
    }

    @Test
    fun exclusionsPruneEveryMatchingSubtreeWithoutMatchingPrefixesOrFiles() = runBlocking {
        val archive = file("Archive", folder = true)
        val active = file("Active", folder = true)
        val nestedArchive = file("Archive", active.path, folder = true)
        val kept = file("Archive-copy", active.path, folder = true)
        val source =
            Source(
                mapOf(
                    "/root" to listOf(archive, active),
                    active.path to listOf(nestedArchive, kept),
                    kept.path to listOf(file("Report.txt", kept.path), file("Archive", kept.path)),
                ),
                setOf(archive.path, nestedArchive.path),
            )
        val result =
            DirectoryLoader(source).load(
                request(
                    recursive = true,
                    rules = SearchRules(excludedFolderNames = setOf("Archive", "root")),
                ),
                ListingControl(),
            ) {}
        assertEquals(
            setOf("Active", "Archive-copy", "Archive", "Report.txt"),
            result.entries.map { it.name }.toSet(),
        )
        assertEquals(listOf("/root", active.path, kept.path), source.opened)
        assertFalse(result.progress.incomplete)
    }

    @Test
    fun shallowSearchStillReportsRootProviderFailuresAndPendingResults() = runBlocking {
        val result =
            DirectoryLoader(
                    Source(
                        mapOf("/root" to listOf(file("Report.txt"))),
                        replies = mapOf("/root" to DirectoryReply(true, "Offline", "Cached files")),
                    ),
                )
                .load(
                    request(recursive = true, rules = SearchRules(includeSubfolders = false)),
                    ListingControl(),
                ) {}
        assertEquals(1, result.entries.size)
        assertTrue(result.progress.providerPending)
        assertEquals("Offline", result.progress.issues.single().reason)
        assertTrue(result.progress.incomplete)
    }

    @Test
    fun explicitCollectionsStayNonrecursiveAndOnlyExcludeMatchingFolderEntries() = runBlocking {
        val archive = file("Archive", folder = true)
        val saved = file("Report.txt", archive.path)
        val source = Source(mapOf("/root" to listOf(archive), archive.path to listOf(saved)))
        val result =
            DirectoryLoader(source).load(
                request(
                        rules =
                            SearchRules(
                                includeSubfolders = false,
                                excludedFolderNames = setOf("Archive"),
                            ),
                    )
                    .copy(explicitPaths = listOf(archive.path, saved.path)),
                ListingControl(),
            ) {}
        assertEquals(listOf(saved), result.entries)
        assertTrue(source.opened.isEmpty())
    }

    @Test
    fun sevenThousandItemsArePublishedProgressivelyWithoutTheOldFiveThousandCap() = runBlocking {
        val source =
            Source(mapOf("/root" to (6999 downTo 0).map { file("Report-%05d.txt".format(it)) }))
        val batches = mutableListOf<ListingBatch>()
        val result =
            DirectoryLoader(source) { source.clock }
                .load(request(), ListingControl()) { batches += it }
        assertEquals(7000, result.entries.size)
        assertFalse(result.progress.incomplete)
        assertEquals(64, batches.first().entries.size)
        assertTrue(batches.size > 3)
        assertEquals("Report-00000.txt", result.entries.first().name)
        assertEquals("Report-06999.txt", result.entries.last().name)
    }

    @Test
    fun limitsAreExplicitAndAHigherBudgetCanReturnTheRemainingItems() = runBlocking {
        val source = Source(mapOf("/root" to (1..8).map { file("$it.txt") }))
        val limited =
            DirectoryLoader(source).load(request(ListingBudget(3, 100)), ListingControl()) {}
        assertEquals(3, limited.entries.size)
        assertTrue(limited.progress.resultLimit)
        val more =
            DirectoryLoader(source).load(request(ListingBudget(10, 100)), ListingControl()) {}
        assertEquals(8, more.entries.size)
        assertFalse(more.progress.incomplete)
    }

    @Test
    fun recursiveTraversalKeepsReadableSiblingsAndReportsOfflineLocations() = runBlocking {
        val good = file("Good", folder = true)
        val bad = file("Offline", folder = true)
        val source =
            Source(
                mapOf(
                    "/root" to listOf(good, bad),
                    good.path to listOf(file("Note.txt", good.path)),
                ),
                setOf(bad.path),
            )
        val result =
            DirectoryLoader(source).load(
                request(recursive = true, rules = SearchRules(name = "Note")),
                ListingControl(),
            ) {}
        assertEquals(listOf("Note.txt"), result.entries.map { it.name })
        assertEquals(1, result.progress.issueCount)
        assertEquals(bad.path, result.progress.issues.single().path)
        assertFalse(result.progress.scanLimit)
    }

    @Test
    fun unreadableDirectFolderIsAnErrorRatherThanAnEmptySuccess() = runBlocking {
        try {
            DirectoryLoader(Source(emptyMap(), setOf("/root"))).load(request(), ListingControl()) {}
            fail()
        } catch (error: IllegalStateException) {
            assertEquals("Offline fixture", error.message)
        }
    }

    @Test
    fun traversalBudgetAndHiddenSymlinkAndProtectedPoliciesAreIndependent() = runBlocking {
        val hidden = file(".Private", folder = true)
        val protected = file("Protected", folder = true)
        val folder = file("Visible", folder = true)
        val source =
            Source(
                mapOf(
                    "/root" to listOf(hidden, protected, file("Symbolic.txt"), folder),
                    hidden.path to listOf(file("Hidden.txt", hidden.path)),
                    protected.path to listOf(file("Secret.txt", protected.path)),
                    folder.path to listOf(file("Shown.txt", folder.path)),
                ),
            )
        val result = DirectoryLoader(source).load(request(recursive = true), ListingControl()) {}
        assertEquals(
            setOf("Protected", "Visible", "Shown.txt"),
            result.entries.map { it.name }.toSet(),
        )
        val limited =
            DirectoryLoader(source).load(
                request(ListingBudget(100, 3), recursive = true),
                ListingControl(),
            ) {}
        assertTrue(limited.progress.scanLimit)
        assertEquals(3, limited.progress.scanned)
    }

    @Test
    fun slowPublicationLeavesTimeToScanBeforeSortingAnotherBatch() = runBlocking {
        val source = Source(mapOf("/root" to (360 downTo 1).map { file("Photo $it.txt") }))
        val batches = mutableListOf<ListingBatch>()
        val result =
            DirectoryLoader(source) { source.clock }
                .load(request().copy(comparator = filenameEntryComparator()), ListingControl()) {
                    batches += it
                    // Simulate expensive sorting/rendering without relying on wall-clock timing.
                    source.clock += 200
                }
        assertTrue("Slow publication must not trigger a batch per entry", batches.size in 2..5)
        assertTrue(batches.first().entries.size < result.entries.size)
        assertEquals((1..360).map { "Photo $it.txt" }, result.entries.map { it.name })
        assertEquals(result, batches.last())
        assertFalse(result.progress.incomplete)
    }

    @Test
    fun cancellationStopsPublicationAndDoesNotBecomeAnUnreadableFileWarning() = runBlocking {
        val source = Source(mapOf("/root" to (1..1000).map { file("$it.txt") }))
        val control = ListingControl()
        val batches = mutableListOf<ListingBatch>()
        try {
            DirectoryLoader(source) { source.clock }
                .load(request(), control) { batch ->
                    batches += batch
                    control.cancel()
                }
            fail()
        } catch (_: CancellationException) {}
        assertEquals(1, batches.size)
        assertEquals(0, batches.single().progress.issueCount)
        assertTrue(source.clock < 1000)
    }

    @Test
    fun providerPendingErrorAndInformationKeepAvailableFilesVisible() = runBlocking {
        val source =
            Source(
                mapOf("/root" to listOf(file("Available.txt"))),
                replies = mapOf("/root" to DirectoryReply(true, "Connection lost", "Cached files")),
            )
        val result = DirectoryLoader(source).load(request(), ListingControl()) {}
        assertEquals(1, result.entries.size)
        assertTrue(result.progress.providerPending)
        assertEquals("Connection lost", result.progress.issues.single().reason)
        assertEquals(listOf("Cached files"), result.progress.providerInfo)
    }

    @Test
    fun issueExamplesAreBoundedAndUnknownSizesAreNotTreatedAsZero() = runBlocking {
        val folder = file("Nested", folder = true).copy(sizeKnown = false)
        val source =
            Source(
                mapOf(
                    "/root" to listOf(folder),
                    folder.path to
                        (1..40).map {
                            file("$it.txt", folder.path).copy(sizeKnown = false)
                        },
                ),
            )
        val result =
            DirectoryLoader(source).load(
                request(recursive = true, rules = SearchRules(maximumBytes = 100)),
                ListingControl(),
            ) {}
        assertTrue(result.entries.isEmpty())
        assertEquals(40, result.progress.issueCount)
        assertEquals(20, result.progress.issues.size)
    }

    @Test
    fun recentsKeepsTheNewestTwoHundredAcrossAllRoots() = runBlocking {
        val source =
            Source(mapOf("/root" to (1..400).map { file("$it.txt", modified = it.toLong()) }))
        val result =
            DirectoryLoader(source).load(
                request(recursive = true).copy(recent = true),
                ListingControl(),
            ) {}
        assertEquals(200, result.entries.size)
        assertEquals((201..400).map { "$it.txt" }.toSet(), result.entries.map { it.name }.toSet())
        assertFalse(result.progress.incomplete)
    }
    @Test fun exactExclusionsPruneRootsAndSubtreesAndKeepSiblingCacheFolders() = runBlocking {
        val cache = file("Cache", folder = true)
        val work = file("Work", folder = true)
        val otherCache = file("Cache", work.path, folder = true)
        val source = Source(mapOf("/root" to listOf(cache, work), work.path to listOf(otherCache),
            otherCache.path to listOf(file("Report.txt", otherCache.path))), failures = setOf(cache.path))
        val rules = SearchRules(name = "Report", excludedLocations = listOf(SearchExclusion(cache.path)))
        val result = DirectoryLoader(source).load(request(recursive = true, rules = rules), ListingControl()) {}
        assertEquals(listOf("Report.txt"), result.entries.map { it.name })
        assertFalse(source.opened.contains(cache.path))
        assertFalse(result.progress.incomplete)
        val beneath = DirectoryLoader(source).load(request(recursive = true, rules = rules).copy(roots = listOf(cache.path + "/Nested")), ListingControl()) {}
        assertTrue(beneath.entries.isEmpty())
        assertFalse(source.opened.contains(cache.path + "/Nested"))
        val explicit = DirectoryLoader(source).load(request(rules = rules).copy(explicitPaths = listOf(otherCache.path + "/Report.txt")), ListingControl()) {}
        assertEquals(listOf("Report.txt"), explicit.entries.map { it.name })
    }

    @Test fun oneCalendarReferenceAndOneContentReadSupplyAdmissionAndEvidence() = runBlocking {
        var reads = 0
        val source = object : ListingSource {
            override suspend fun children(path: String, control: ListingControl, visit: suspend (Entry) -> Boolean): DirectoryReply {
                visit(file("Report.txt", modified = java.time.Instant.parse("2026-11-03T00:01:00Z").toEpochMilli()))
                visit(file("Old.txt", modified = 0))
                visit(file(".Hidden.txt"))
                return DirectoryReply()
            }
            override fun read(path: String, control: ListingControl): Entry = error("Not needed")
            override fun input(entry: Entry, control: ListingControl): InputStream { reads++; return "literal needle snippet".byteInputStream() }
            override fun traversable(entry: Entry) = true
        }
        val calendar = SearchCalendar(java.time.Instant.parse("2026-11-03T23:59:59Z").toEpochMilli(), java.time.ZoneId.of("UTC"), java.util.Locale.US)
        val result = DirectoryLoader(source).load(request(rules = SearchRules(date = SearchDate.TODAY, content = "needle")).copy(calendar = calendar), ListingControl()) {}
        assertEquals(listOf("Report.txt"), result.entries.map { it.name })
        assertEquals(1, reads)
        assertTrue(result.entries.single().searchEvidence!!.snippet!!.text.contains("needle"))
    }

    @Test fun unverifiedExactProviderExclusionsSkipContentAndReportIncompleteResults() = runBlocking {
        var reads = 0
        val source = object : ListingSource {
            override suspend fun children(path: String, control: ListingControl, visit: suspend (Entry) -> Boolean): DirectoryReply = error("Unknown branch must not open")
            override fun read(path: String, control: ListingControl) = file("Report.txt").copy(documentUri = null)
            override fun input(entry: Entry, control: ListingControl): InputStream { reads++; error("Unknown branch must not read") }
            override fun traversable(entry: Entry) = true
            override fun searchIdentity(path: String) = path
            override fun searchAncestry(path: String, ancestor: String, control: ListingControl) = SearchAncestry.UNKNOWN
        }
        val result = DirectoryLoader(source).load(request(recursive = true, rules = SearchRules(content = "secret", excludedLocations = listOf(SearchExclusion("content://provider/document/cache"))))
            .copy(roots = listOf("content://provider/document/root")), ListingControl()) {}
        assertTrue(result.entries.isEmpty())
        assertTrue(result.progress.incomplete)
        assertTrue(result.progress.issues.single().reason.contains("exclusions couldn't be verified"))
        assertEquals(0, reads)
    }

    @Test fun sizePresetsReportUnknownSizesBeforeContentReads() = runBlocking {
        val item = file("Large.txt").copy(sizeKnown = false)
        val source = Source(mapOf("/root" to listOf(item)))
        val result = DirectoryLoader(source).load(request(rules = SearchRules(size = SearchSize.OVER_1_GB, content = "text")), ListingControl()) {}
        assertTrue(result.entries.isEmpty())
        assertTrue(result.progress.incomplete)
        assertTrue(result.progress.issues.single().reason.startsWith("Unknown size"))
    }

}
