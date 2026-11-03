package dev.findroid

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexedDirectoryLoaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = temporary.newFolder().canonicalFile
    private fun file(root: File, path: String, bytes: String = "needle content") = File(root, path).apply { parentFile!!.mkdirs(); writeText(bytes) }
    private fun record(file: File) = IndexRecord(file.path, file.parent!!, file.name, file.isDirectory, if (file.isDirectory) 0 else file.length(), file.lastModified())
    private fun snapshot(store: LocalIndexTestStore, root: File, rows: List<IndexRecord>): LocalIndexRoot {
        val token = store.begin(store.add(root.path).id)
        if (rows.isNotEmpty()) store.append(token, rows)
        store.complete(token, 2, rows.size)
        return store.catalog.getValue(token.rootId)
    }
    private class Source : ListingSource {
        val reads = mutableListOf<String>()
        val contents = mutableListOf<String>()
        var onRead: (String) -> Unit = {}
        var onInput: (Entry) -> InputStream = { it.file.inputStream() }
        override suspend fun children(path: String, control: ListingControl, visit: suspend (Entry) -> Boolean): DirectoryReply {
            for (file in File(path).listFiles().orEmpty()) if (!visit(Entry(file))) break
            return DirectoryReply()
        }
        override fun read(path: String, control: ListingControl): Entry { reads += path; onRead(path); return Entry(File(path)) }
        override fun input(entry: Entry, control: ListingControl): InputStream { contents += entry.path; return onInput(entry) }
        override fun traversable(entry: Entry) = entry.folder && !Files.isSymbolicLink(entry.file.toPath())
    }
    private suspend fun load(roots: List<String>, store: LocalIndexStore?, source: Source = Source(), rules: SearchRules = SearchRules(),
        tags: Map<String, Set<String>> = emptyMap(), hidden: Boolean = false, budget: ListingBudget = ListingBudget(),
        explicit: List<String>? = null, control: ListingControl = ListingControl(), publish: suspend (ListingBatch) -> Unit = {}): ListingBatch =
        DirectoryLoader(source).load(ListingRequest(roots, true, hidden, rules.copy(source = SearchSource.LOCAL_INDEX), tags, emptyMap(),
            compareBy { it.name }, budget, explicitPaths = explicit, index = store), control, publish)

    @Test fun currentMetadataReplacesCachedValuesAndRejectsStalePositiveMatches() = runBlocking {
        val root = root(); val file = file(root, "Report.txt", "small"); val store = LocalIndexTestStore()
        snapshot(store, root, listOf(record(file)))
        file.writeText("A much larger current needle document"); file.setLastModified(System.currentTimeMillis() - 30_000)
        val result = load(listOf(root.path), store, rules = SearchRules(name = "Report"))
        assertEquals(file.length(), result.entries.single().size); assertEquals(file.lastModified(), result.entries.single().modified)
        assertTrue(result.entries.single().writable); assertEquals(1, result.progress.indexRevalidated)
        assertTrue(result.progress.indexed); assertEquals(2L, result.progress.roots.single().index!!.refreshedAt)
        assertTrue(load(listOf(root.path), store, rules = SearchRules(maximumBytes = 10)).entries.isEmpty())
    }

    @Test fun currentTagsNestedGroupsAndOneGlobalContentQueryRunOnceAcrossOverlaps() = runBlocking {
        val root = root(); val file = file(root, "Project/Report.txt"); val child = file.parentFile!!
        val store = LocalIndexTestStore()
        snapshot(store, root, listOf(record(child), record(file)))
        snapshot(store, child, listOf(record(file)))
        val rules = SearchRules(content = "needle", ruleGroup = SearchRuleGroup(SearchRuleJoin.ALL, listOf(
            SearchKindRule("Text document"), SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchTagRule("Review"), SearchTagRule("Approved"))))))
        val source = Source()
        val result = load(listOf(root.path, child.path), store, source, rules, mapOf(organizationKey(file.path) to setOf("Approved")))
        assertEquals(listOf(file.path), result.entries.map { it.path }); assertEquals(listOf(file.path), source.contents)
        assertEquals(1, source.reads.count { it == file.path }); assertEquals(1, result.progress.indexRevalidated)
        val noTag = Source(); assertTrue(load(listOf(root.path), store, noTag, rules).entries.isEmpty()); assertTrue(noTag.contents.isEmpty())
    }

    @Test fun rejectingOldSnapshotDoesNotHideACandidateAdmittedByAnotherNewerRoot() = runBlocking {
        val root = root(); val file = file(root, "Project/Report.txt"); val child = file.parentFile!!; val store = LocalIndexTestStore()
        snapshot(store, root, listOf(record(child), record(file).copy(size = 1)))
        snapshot(store, child, listOf(record(file)))
        val source = Source()
        val result = load(listOf(root.path, child.path), store, source, SearchRules(minimumBytes = 5, kind = "Text document", content = "needle"))
        assertEquals(listOf(file.path), result.entries.map { it.path }); assertEquals(1, source.contents.size)
    }

    @Test fun missingAndSymlinkReplacedCandidatesNeverBecomeActionableOrOpenContent() = runBlocking {
        val root = root(); val outside = root(); val original = file(root, "Report.txt"); val missing = file(root, "Gone.txt")
        val secret = file(outside, "Secret.txt"); val store = LocalIndexTestStore(); snapshot(store, root, listOf(record(original), record(missing)))
        original.delete(); missing.delete(); Files.createSymbolicLink(original.toPath(), secret.toPath())
        val source = Source(); val result = load(listOf(root.path), store, source, SearchRules(content = "needle"))
        assertTrue(result.entries.isEmpty()); assertTrue(source.contents.isEmpty()); assertEquals(2, result.progress.issueCount)
        assertEquals(ListingRootState.INCOMPLETE, result.progress.roots.single().state)
    }

    @Test fun replacementDuringMetadataReadIsCaughtBeforeAnyContentRead() = runBlocking {
        val root = root(); val original = file(root, "Report.txt"); val outside = file(root(), "Outside.txt")
        val store = LocalIndexTestStore(); snapshot(store, root, listOf(record(original)))
        val source = Source().apply { onRead = { path -> if (path == original.path) { original.delete(); Files.createSymbolicLink(original.toPath(), outside.toPath()) } } }
        val result = load(listOf(root.path), store, source, SearchRules(content = "needle"))
        assertTrue(result.entries.isEmpty()); assertTrue(source.contents.isEmpty()); assertEquals(1, result.progress.issueCount)
    }

    @Test fun rootReplacementDuringMetadataReadRejectsTheSnapshotBeforePublishingOrReadingContent() = runBlocking {
        val root = root(); val original = file(root, "Report.txt"); val store = LocalIndexTestStore()
        val snapshot = snapshot(store, root, listOf(record(original)))
        store.catalog[snapshot.id] = snapshot.copy(filesystemIdentity = localIndexFileIdentity(root.path))
        val moved = File(root.parentFile, root.name + "-moved")
        val source = Source().apply { onRead = { path -> if (path == original.path) {
            assertTrue(root.renameTo(moved)); assertTrue(root.mkdir()); file(root, "Report.txt", "replacement needle")
        } } }
        try {
            val result = load(listOf(root.path), store, source, SearchRules(content = "needle"))
            assertTrue(result.entries.isEmpty()); assertTrue(source.contents.isEmpty()); assertTrue(result.progress.incomplete)
            assertTrue(result.progress.issues.any { it.reason.contains("replaced") })
        } finally { moved.deleteRecursively() }
    }

    @Test fun deletionDuringContentReadIsCaughtBeforePublishingTheCandidate() = runBlocking {
        val root = root(); val original = file(root, "Report.txt"); val store = LocalIndexTestStore(); snapshot(store, root, listOf(record(original)))
        val source = Source().apply { onInput = { entry -> object : java.io.ByteArrayInputStream("needle".toByteArray()) {
            override fun close() { super.close(); entry.file.delete() }
        } } }
        val result = load(listOf(root.path), store, source, SearchRules(content = "needle"))
        assertTrue(result.entries.isEmpty()); assertEquals(1, result.progress.issueCount)
    }

    @Test fun flatCursorPreservesExactAncestorExclusionsDepthHiddenAndWorkingRules() = runBlocking {
        val root = root(); val store = LocalIndexTestStore()
        val files = listOf("Report.txt", "Work/Report.txt", "Cache/Report.txt", ".hidden/Report.txt", ".findroid-copy/Report.txt", "Keep/Report.txt").map { file(root, it) }
        snapshot(store, root, files.map(::record))
        val rules = SearchRules(kind = "Text document", excludedFolderNames = setOf("Cache"), excludedLocations = listOf(SearchExclusion(File(root, "Keep").path)))
        assertEquals(setOf(files[0].path, files[1].path), load(listOf(root.path), store, rules = rules).entries.map { it.path }.toSet())
        assertEquals(listOf(files[0].path), load(listOf(root.path), store, rules = rules.copy(includeSubfolders = false)).entries.map { it.path })
        assertEquals(setOf(files[0].path, files[1].path, files[3].path), load(listOf(root.path), store, rules = rules, hidden = true).entries.map { it.path }.toSet())
    }

    @Test fun unindexedProviderAndCollectionRootsStayExplicitWithoutSilentLiveFallback() = runBlocking {
        val root = root(); val file = file(root, "Report.txt"); val store = LocalIndexTestStore(); val source = Source()
        snapshot(store, root, listOf(record(file))); val unindexed = root()
        val result = load(listOf(root.path, unindexed.path, "content://provider/tree/root"), store, source)
        assertEquals(listOf(file.path), result.entries.map { it.path })
        assertEquals(listOf(ListingRootState.COMPLETE, ListingRootState.UNAVAILABLE, ListingRootState.UNAVAILABLE), result.progress.roots.map { it.state })
        assertTrue(load(listOf(root.path), store, explicit = listOf(file.path)).entries.isEmpty())
        assertTrue(load(listOf(root.path), null).progress.incomplete)
    }

    @Test fun newAndRenamedPathsAreDiscoverableByLiveSearchUntilTheSnapshotIsRefreshed() = runBlocking {
        val root = root(); val original = file(root, "Old.txt"); val store = LocalIndexTestStore(); snapshot(store, root, listOf(record(original)))
        val renamed = File(root, "Report renamed.txt"); assertTrue(original.renameTo(renamed)); val created = file(root, "Report new.txt")
        assertTrue(load(listOf(root.path), store, rules = SearchRules(name = "Report")).entries.isEmpty())
        val live = DirectoryLoader(Source()).load(ListingRequest(listOf(root.path), true, false, SearchRules(name = "Report"),
            emptyMap(), emptyMap(), compareBy { it.name }), ListingControl()) {}
        assertEquals(setOf(renamed.path, created.path), live.entries.map { it.path }.toSet()); assertFalse(live.progress.indexed)
    }

    @Test fun cachedCandidateBudgetsAndSynchronousCancellationBoundWork() = runBlocking {
        val root = root(); val files = (1..5).map { file(root, "Report$it.txt") }; val store = LocalIndexTestStore(); snapshot(store, root, files.map(::record))
        val result = load(listOf(root.path), store, budget = ListingBudget(results = 1, scanned = 2))
        assertTrue(result.entries.size <= 1); assertTrue(result.progress.resultLimit || result.progress.scanLimit)
        val control = ListingControl(); var published = false
        try { load(listOf(root.path), store, control = control) { published = true; control.cancel() }; fail("Cancellation was ignored") }
        catch (_: CancellationException) {}
        assertTrue(published)
    }
}
