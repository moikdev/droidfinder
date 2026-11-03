package dev.findroid

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalIndexScannerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = temporary.newFolder().canonicalFile
    private fun file(root: File, path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("synthetic") }
    private fun row(path: String, folder: Boolean = false) = IndexRecord(path, File(path).parent!!, File(path).name, folder, 10, 1)

    @Test fun streamingScanStoresHiddenMetadataButNeverContentsSymlinksOrWorkingTrees() = runBlocking {
        val root = root(); val outside = root()
        file(root, "Report.txt"); file(root, ".hidden/Report.txt"); file(root, ".findroid-copy/Secret.txt")
        file(outside, "Secret.txt")
        Files.createSymbolicLink(File(root, "Linked").toPath(), outside.toPath())
        val store = LocalIndexTestStore(); val token = store.begin(store.add(root.path).id)
        val progress = LocalIndexScanner(store).scan(token, ListingControl())
        val paths = store.generations.getValue(token.generation).map { it.path.removePrefix(root.path + "/") }.toSet()
        assertEquals(setOf("Report.txt", ".hidden", ".hidden/Report.txt"), paths)
        assertEquals(3, progress.entries); assertEquals(2, progress.folders)
        assertFalse(store.roots().single().hasSnapshot)
        store.complete(token, 2, progress.entries)
        assertTrue(store.roots().single().hasSnapshot)
    }

    @Test fun entryValuePathDepthAndFolderBudgetsStopWithoutPublishingPartialGenerations() = runBlocking {
        suspend fun capped(limits: LocalIndexLimits, children: Map<String, List<IndexRecord>>) {
            val store = LocalIndexTestStore(); val root = store.add("/root"); val token = store.begin(root.id)
            val tree = object : LocalIndexTree {
                override fun validateRoot(path: String) {}
                override suspend fun children(path: String, root: String, control: ListingControl, each: suspend (IndexRecord) -> Unit) {
                    for (row in children[path].orEmpty()) each(row)
                }
            }
            try { LocalIndexScanner(store, tree, limits).scan(token, ListingControl()); fail("Expected a bounded scan") }
            catch (_: IndexBuildLimit) {} catch (_: IllegalArgumentException) {}
            assertFalse(store.roots().single().hasSnapshot)
            assertTrue(store.batches.all { it in 1..limits.batch })
        }
        capped(LocalIndexLimits(entriesPerRoot = 2, storedEntries = 4, batch = 1), mapOf("/root" to (1..3).map { row("/root/$it.txt") }))
        capped(LocalIndexLimits(valueBytes = 1), mapOf("/root" to listOf(row("/root/a"))))
        capped(LocalIndexLimits(pathBytes = 64), mapOf("/root" to listOf(row("/root/" + "a".repeat(70)))))
        val nested = mapOf("/root" to listOf(row("/root/One", true)), "/root/One" to listOf(row("/root/One/Two", true)))
        capped(LocalIndexLimits(depth = 1), nested)
        capped(LocalIndexLimits(folders = 1), nested)
    }

    @Test fun rowBoundaryCancellationAndTreeFailureKeepPreviousSnapshotUnchanged() = runBlocking {
        val store = LocalIndexTestStore(); val root = store.add("/root"); val initial = store.begin(root.id)
        store.append(initial, listOf(row("/root/Original.txt"))); store.complete(initial, 2, 1)
        val generation = store.roots().single().generation
        for (cancel in listOf(true, false)) {
            val token = store.begin(root.id); val control = ListingControl()
            val tree = object : LocalIndexTree {
                override fun validateRoot(path: String) {}
                override suspend fun children(path: String, root: String, control: ListingControl, each: suspend (IndexRecord) -> Unit) {
                    each(row("/root/New.txt"))
                    if (cancel) control.cancel() else throw java.io.IOException("Synthetic drive removal")
                    each(row("/root/Late.txt"))
                }
            }
            try { LocalIndexScanner(store, tree, LocalIndexLimits(batch = 1)).scan(token, control); fail("Expected interrupted scan") }
            catch (error: Exception) { assertTrue(error is CancellationException || error is java.io.IOException) }
            assertEquals(generation, store.roots().single().generation)
            assertEquals(listOf("/root/New.txt"), store.generations.getValue(token.generation).map { it.path })
            store.abandon(token, if (cancel) IndexRefreshState.PAUSED else IndexRefreshState.FAILED, 1, "Synthetic")
        }
    }

    @Test fun changingRootIdentityDuringScanRejectsTheNewSnapshot() = runBlocking {
        val store = LocalIndexTestStore(); val token = store.begin(store.add("/root").id)
        var identity = "original"
        val tree = object : LocalIndexTree {
            override fun validateRoot(path: String) {}
            override fun rootIdentity(path: String) = identity
            override suspend fun children(path: String, root: String, control: ListingControl, each: suspend (IndexRecord) -> Unit) {
                each(row("/root/Report.txt")); identity = "replacement"
            }
        }
        try { LocalIndexScanner(store, tree).scan(token, ListingControl()); fail("Root replacement was ignored") }
        catch (_: IllegalStateException) {}
        assertFalse(store.roots().single().hasSnapshot)
    }

    @Test fun flatScopesHonorDepthHiddenWorkingAndExcludedAncestorsWithoutPruningAnchor() {
        val rules = SearchRules(excludedFolderNames = setOf("Cache"))
        assertFalse(indexedScopeAdmits(row("/root/Cache/Report.txt"), "/root", rules, false))
        assertFalse(indexedScopeAdmits(row("/root/.private/Report.txt"), "/root", rules, false))
        assertTrue(indexedScopeAdmits(row("/root/.private/Report.txt"), "/root", rules, true))
        assertFalse(indexedScopeAdmits(row("/root/.findroid-work/Report.txt"), "/root", rules, true))
        assertFalse(indexedScopeAdmits(row("/root/Child/Report.txt"), "/root", rules.copy(includeSubfolders = false), true))
        assertTrue(indexedScopeAdmits(row("/root/Cache/Report.txt"), "/root/Cache", rules, false))
        assertFalse(indexedScopeAdmits(row("/root-other/Report.txt"), "/root", rules, true))
        assertFalse(indexedScopeAdmits(row("/root/Cache", true), "/root", rules, true))
    }

    @Test fun canonicalContainmentRejectsMissingPathsAndLeafOrAncestorSymlinkReplacement() {
        val root = root(); val outside = root(); val original = file(root, "Report.txt")
        val secret = file(outside, "Secret.txt")
        assertEquals(original, currentIndexedFile(original.path, root.path))
        assertThrows(IllegalArgumentException::class.java) { currentIndexedFile(secret.path, root.path) }
        assertTrue(original.delete()); Files.createSymbolicLink(original.toPath(), secret.toPath())
        assertThrows(IllegalStateException::class.java) { currentIndexedFile(original.path, root.path) }
        val link = File(root, "Linked"); Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { currentIndexedFile(File(link, secret.name).path, root.path) }
        assertThrows(IllegalStateException::class.java) { currentIndexedFile(File(root, "Missing").path, root.path) }
        assertThrows(IllegalArgumentException::class.java) { IndexRecord("/root/a", "/other", "a", false, 0, 0).checked("/root") }
        assertThrows(IllegalArgumentException::class.java) { LocalIndexLimits(roots = 11) }
    }
}
