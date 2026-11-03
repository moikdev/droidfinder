package dev.findroid

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocalIndexStoreTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var fixture: File
    private val stores = mutableListOf<LocalIndexStore>()
    private val managers = mutableListOf<LocalSearchIndex>()
    @Before fun setup() { fixture = File(app.cacheDir, "local-index-qa-${System.nanoTime()}").canonicalFile.apply { mkdirs() } }
    @After fun cleanup() { managers.forEach { it.close() }; stores.forEach { runCatching { it.close() } }; fixture.deleteRecursively() }
    private fun folder(name: String) = File(fixture, name).apply { mkdirs() }
    private fun file(root: File, name: String, bytes: String = "Synthetic needle") = File(root, name).apply { parentFile!!.mkdirs(); writeText(bytes) }
    private fun record(file: File) = IndexRecord(file.path, file.parent!!, file.name, file.isDirectory, if (file.isDirectory) 0 else file.length(), file.lastModified())
    private fun store(name: String = "metadata.sqlite", limits: LocalIndexLimits = LocalIndexLimits(), owner: String = newIndexId()) =
        SqliteLocalIndexStore(app, File(fixture, name), limits, owner).also { stores += it }
    private fun publish(store: LocalIndexStore, root: LocalIndexRoot, rows: List<IndexRecord>): LocalIndexRoot {
        val token = store.begin(root.id)
        rows.chunked(128).forEach { store.append(token, it) }
        store.complete(token, System.currentTimeMillis(), rows.size)
        return store.roots().first { it.id == root.id }
    }
    private suspend fun rows(lease: LocalIndexLease, anchor: String = lease.root.path) = buildList {
        lease.visit(anchor, true, ListingControl()) { add(it); true }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun readingSourceAvailabilityNeverCreatesOrRegistersACache() {
        val store = store()
        assertTrue(store.roots().isEmpty()); assertNull(store.acquire(fixture.path)); store.markChanged(emptyList(), 1)
        assertEquals(0, store.storageBytes()); assertFalse(store.file.exists())
        val default = SqliteLocalIndexStore(app)
        assertTrue(default.file.path.startsWith(app.noBackupFilesDir.path + "/")); default.close()
    }

    @Test fun completeGenerationsSurviveFailedPausedAndCappedRefreshes(): Unit = runBlocking {
        val source = folder("documents"); val original = file(source, "Original.txt"); val changed = file(source, "Changed.txt")
        val store = store(); val root = publish(store, store.add(source.path), listOf(record(original)))
        for (state in listOf(IndexRefreshState.PAUSED, IndexRefreshState.FAILED, IndexRefreshState.CAPPED)) {
            val attempt = store.begin(root.id); store.append(attempt, listOf(record(changed)))
            store.acquire(source.path)!!.use { assertEquals(listOf(original.path), rows(it).map { it.path }) }
            store.abandon(attempt, state, 1, "Synthetic stopped refresh")
            val retained = store.roots().single()
            assertEquals(root.generation, retained.generation); assertEquals(state, retained.state); assertTrue(retained.retainedSnapshot)
            store.acquire(source.path)!!.use { assertEquals(listOf(original.path), rows(it).map { it.path }) }
        }
        assertEquals("Synthetic needle", original.readText()); assertEquals("Synthetic needle", changed.readText())
    }

    @Test fun cancellationAtPublicationBoundaryRollsBackTheNewGeneration() {
        val source = folder("documents"); val old = file(source, "Old.txt"); val newer = file(source, "New.txt")
        val store = store(); val root = publish(store, store.add(source.path), listOf(record(old)))
        val attempt = store.begin(root.id); store.append(attempt, listOf(record(newer)))
        var checks = 0
        assertThrows(CancellationException::class.java) { store.complete(attempt, System.currentTimeMillis(), 1) { if (++checks == 2) throw CancellationException("Synthetic pause before commit") } }
        assertEquals(2, checks); assertEquals(root.generation, store.roots().single().generation)
        assertEquals(IndexRefreshState.BUILDING, store.roots().single().state)
        store.abandon(attempt, IndexRefreshState.PAUSED, 1, "Paused")
    }

    @Test fun aDifferentProcessOwnerRecoversAnInterruptedBuildAndRetainsTheSnapshot(): Unit = runBlocking {
        val source = folder("documents"); val old = file(source, "Old.txt"); val newer = file(source, "New.txt")
        val first = store(); val root = publish(first, first.add(source.path), listOf(record(old)))
        val attempt = first.begin(root.id); first.append(attempt, listOf(record(newer))); first.close()
        val reopened = store()
        val recovered = reopened.roots().single()
        assertEquals(IndexRefreshState.INTERRUPTED, recovered.state); assertEquals(root.generation, recovered.generation)
        assertNull(recovered.attempt); assertTrue(recovered.reason!!.contains("stopped"))
        reopened.acquire(source.path)!!.use { assertEquals(listOf(old.path), rows(it).map { it.path }) }
        assertThrows(IndexSourceChanged::class.java) { reopened.complete(attempt, System.currentTimeMillis(), 1) }
    }

    @Test fun removingThenReaddingTheSamePathRejectsLateWritesFromItsPreviousIdentity() {
        val source = folder("documents"); val file = file(source, "Report.txt"); val store = store()
        val original = store.add(source.path); val old = store.begin(original.id)
        store.append(old, listOf(record(file))); store.remove(original.id)
        val replacement = store.add(source.path); assertNotEquals(original.id, replacement.id)
        assertThrows(IndexSourceChanged::class.java) { store.append(old, listOf(record(file))) }
        assertThrows(IndexSourceChanged::class.java) { store.complete(old, System.currentTimeMillis(), 1) }
        store.abandon(old, IndexRefreshState.FAILED, 1, "Late worker")
        assertEquals(replacement, store.roots().single()); assertTrue(file.exists())
    }

    @Test fun leasesPinTheirOwnGenerationAndRemovalInvalidatesThem(): Unit = runBlocking {
        val source = folder("documents"); val old = file(source, "Old.txt"); val newer = file(source, "New.txt")
        val store = store(); val root = publish(store, store.add(source.path), listOf(record(old)))
        val pinned = store.acquire(source.path)!!
        val replaced = publish(store, root, listOf(record(newer)))
        assertNotEquals(root.generation, replaced.generation)
        assertEquals(listOf(old.path), rows(pinned).map { it.path })
        store.acquire(source.path)!!.use { assertEquals(listOf(newer.path), rows(it).map { it.path }) }
        store.remove(root.id)
        assertThrows(IndexSourceChanged::class.java) { pinned.check() }; pinned.close()
    }

    @Test fun deletingWithASuspendedCursorCannotDamageANewCacheAtTheSamePath(): Unit = runBlocking {
        val source = folder("documents"); val original = file(source, "Old.txt"); val newer = file(source, "New.txt")
        val store = store(); val root = publish(store, store.add(source.path), listOf(record(original), record(newer)))
        val lease = store.acquire(source.path)!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reader = async(Dispatchers.IO) {
            try {
                lease.use { it.visit(source.path, true, ListingControl()) { entered.complete(Unit); release.await(); true } }
                false
            } catch (_: IndexSourceChanged) { true }
        }
        try {
            withTimeout(5000) { entered.await() }
            val deleting = async(Dispatchers.IO) { store.deleteCache() }
            withTimeout(5000) { deleting.await() }
            val replacement = publish(store, store.add(source.path), listOf(record(newer)))
            assertNotEquals(root.id, replacement.id)
            release.complete(Unit)
            assertTrue(withTimeout(5000) { reader.await() })
            store.acquire(source.path)!!.use { assertEquals(listOf(newer.path), rows(it).map { it.path }) }
            assertEquals(replacement, store.roots().single())
        } finally { release.complete(Unit); reader.await() }
    }

    @Test fun literalUnicodeWildcardAndNestedCoverageNeverBroadenTheChosenAnchor(): Unit = runBlocking {
        val source = folder("documents"); val child = folder("documents/Project_%İ字"); val file = file(child, "Report.txt")
        val other = file(source, "Project_other/Report.txt")
        val store = store(); publish(store, store.add(source.path), listOf(record(child), record(file), record(other.parentFile!!), record(other)))
        store.acquire(child.path)!!.use { assertEquals(listOf(file.path), rows(it, child.path).map { it.path }) }
        assertNull(store.acquire(File(source, "New unindexed folder").path)); assertNull(store.acquire(source.path + "-sibling"))
    }

    @Test fun invalidCountAndDuplicateRowsCannotPublishAnIncompleteSnapshot() {
        val source = folder("documents"); val file = file(source, "Report.txt"); val store = store(); val root = store.add(source.path)
        val token = store.begin(root.id); store.append(token, listOf(record(file)))
        assertThrows(IllegalStateException::class.java) { store.complete(token, System.currentTimeMillis(), 2) }
        assertThrows(Exception::class.java) { store.append(token, listOf(record(file))) }
        assertFalse(store.roots().single().hasSnapshot)
        store.complete(token, System.currentTimeMillis(), 1); assertEquals(1, store.roots().single().entries)
    }

    @Test fun sharedRowBudgetIncludesPreviousGenerationsAndRetainsThemWhenCapped(): Unit = runBlocking {
        val source = folder("documents"); val files = (1..3).map { file(source, "$it.txt") }
        val store = store(limits = LocalIndexLimits(entriesPerRoot = 2, storedEntries = 3))
        val root = publish(store, store.add(source.path), files.take(2).map(::record))
        val attempt = store.begin(root.id); store.append(attempt, listOf(record(files[0])))
        assertThrows(IndexBuildLimit::class.java) { store.append(attempt, listOf(record(files[2]))) }
        store.abandon(attempt, IndexRefreshState.CAPPED, 1, "Shared row budget")
        store.acquire(source.path)!!.use { assertEquals(files.take(2).map { it.path }.sorted(), rows(it).map { it.path }.sorted()) }
        assertEquals(root.generation, store.roots().single().generation)
    }

    @Test fun futureCorruptAndUnknownCatalogDataArePreservedUntilExplicitCacheDeletion() {
        val source = folder("documents"); val original = file(source, "Keep.txt")
        for (kind in listOf("future", "corrupt", "unknown")) {
            val file = File(fixture, "$kind.sqlite")
            if (kind == "corrupt") file.writeBytes("Synthetic damaged cache header".toByteArray())
            else if (kind == "future") SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("CREATE TABLE future_data (value TEXT)"); it.execSQL("INSERT INTO future_data VALUES ('keep')"); it.version = 99 }
            else {
                store(file.name).apply { add(source.path); close() }
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE roots SET state='FUTURE_STATE'") }
            }
            val before = hash(file); val cache = store(file.name)
            assertThrows(Exception::class.java) { cache.roots() }
            assertTrue(file.exists()); assertEquals(before, hash(file))
            cache.deleteCache(); assertEquals(0, cache.storageBytes()); assertTrue(cache.roots().isEmpty()); assertFalse(file.exists())
            assertEquals("Synthetic needle", original.readText()); assertNotNull(cache.add(source.path))
        }
    }

    @Test fun cacheDeletionInvalidatesOldTokensWithoutRecreatingTheDatabaseOrTouchingDocuments() {
        val source = folder("documents"); val original = file(source, "Keep.txt"); val store = store()
        val token = store.begin(store.add(source.path).id); store.append(token, listOf(record(original)))
        store.deleteCache(); assertEquals(0, store.storageBytes()); assertFalse(store.file.exists())
        assertThrows(IndexSourceChanged::class.java) { store.complete(token, System.currentTimeMillis(), 1) }
        assertThrows(IndexSourceChanged::class.java) { store.append(token, listOf(record(original))) }
        store.abandon(token, IndexRefreshState.PAUSED, 1, "Late pause")
        assertFalse(store.file.exists()); assertEquals("Synthetic needle", original.readText())
    }

    @Test fun changeHintsRespectPathBoundariesAndExactStorageBytesIncludeSidecars() {
        val source = folder("documents"); val original = file(source, "Keep.txt"); val store = store()
        val root = publish(store, store.add(source.path), listOf(record(original)))
        store.markChanged(listOf(source.path + "-sibling/Other.txt"), 1); assertNull(store.roots().single().changedAt)
        store.markChanged(listOf(original.path), 2); assertEquals(2L, store.roots().single().changedAt)
        assertEquals(listOf("", "-wal", "-shm", "-journal").sumOf { File(store.file.path + it).length() }, store.storageBytes())
        publish(store, root, listOf(record(original))); assertNull(store.roots().single().changedAt)
    }

    @Test fun descriptorValidationSupportsPrivateAndSharedLocalFilesAndRejectsSymlinkEscapes() {
        val source = folder("documents"); val original = file(source, "Keep.txt")
        assertEquals("Synthetic needle", indexedLocalInput(Entry(original), source.path, ListingControl()).bufferedReader().use { it.readText() })
        val outside = file(folder("outside"), "Secret.txt"); original.delete(); Files.createSymbolicLink(original.toPath(), outside.toPath())
        assertThrows(Exception::class.java) { indexedLocalInput(Entry(original), source.path, ListingControl()).close() }
        val linked = File(source, "Linked"); Files.createSymbolicLink(linked.toPath(), outside.parentFile!!.toPath())
        assertThrows(Exception::class.java) { indexedLocalInput(Entry(File(linked, outside.name)), source.path, ListingControl()).close() }
        if (Environment.isExternalStorageManager()) {
            val shared = File(Environment.getExternalStorageDirectory(), "Download/Findroid index QA-${System.nanoTime()}.txt").canonicalFile
            try { shared.writeText("Synthetic shared"); assertEquals("Synthetic shared", indexedLocalInput(Entry(shared), shared.parent!!, ListingControl()).bufferedReader().use { it.readText() }) }
            finally { shared.delete() }
        }
    }

    @Test fun folderReplacementAndChangesDuringBuildAreNeverReportedAsAFreshOriginalSnapshot() {
        val source = folder("documents"); val original = file(source, "Report.txt"); val store = store()
        val root = publish(store, store.add(source.path), listOf(record(original)))
        val attempt = store.begin(root.id); store.append(attempt, listOf(record(original)))
        store.markChanged(listOf(original.path), attempt.started + 1)
        store.complete(attempt, attempt.started + 2, 1)
        assertEquals(attempt.started + 1, store.roots().single().changedAt)
        val replacementAttempt = store.begin(root.id)
        val moved = File(fixture, "moved"); assertTrue(source.renameTo(moved)); assertTrue(source.mkdir())
        val replacement = file(source, "Report.txt", "New directory identity")
        store.append(replacementAttempt, listOf(record(replacement)))
        assertThrows(IllegalStateException::class.java) { store.complete(replacementAttempt, System.currentTimeMillis(), 1) }
        assertEquals(attempt.generation, store.roots().single().generation)
        assertNotEquals(store.roots().single().filesystemIdentity, localIndexFileIdentity(source.path))
        store.abandon(replacementAttempt, IndexRefreshState.FAILED, 1, "Folder replaced")
    }

    private class WaitingTree : LocalIndexTree {
        val entered = CompletableDeferred<Unit>()
        override fun validateRoot(path: String) {}
        override fun rootIdentity(path: String) = localIndexFileIdentity(path)
        override suspend fun children(path: String, root: String, control: ListingControl, each: suspend (IndexRecord) -> Unit) {
            entered.complete(Unit)
            while (true) { control.check(); delay(10) }
        }
    }
    @Test fun controllerPauseIsNotAnErrorAndRapidPauseCannotStrandTheActiveWorker(): Unit = runBlocking {
        val source = folder("documents"); val store = store(); val root = store.add(source.path); val tree = WaitingTree()
        val manager = LocalSearchIndex(app, store, tree = tree).also { managers += it }
        manager.refresh(root.id); withTimeout(5000) { tree.entered.await() }; manager.pause()
        val paused = withTimeout(5000) { manager.state.first { it.activeRoot == null && it.roots.singleOrNull()?.state == IndexRefreshState.PAUSED } }
        assertNull(paused.error)
        repeat(15) {
            manager.refresh(root.id); manager.pause()
            withTimeout(5000) { manager.state.first { it.activeRoot == null } }
        }
        assertFalse(store.roots().single().hasSnapshot)
    }

    @Test fun controllerProviderOnlyHintsDoNotMarkEveryLocalRootStale(): Unit = runBlocking {
        val source = folder("documents"); val original = file(source, "Keep.txt"); val store = store()
        publish(store, store.add(source.path), listOf(record(original)))
        val manager = LocalSearchIndex(app, store).also { managers += it }; manager.load()
        withTimeout(5000) { manager.state.first { it.roots.isNotEmpty() && !it.loading } }
        manager.noteChanged(listOf("content://provider/tree/root")); delay(100)
        assertNull(store.roots().single().changedAt)
        manager.noteChanged(listOf(original.path))
        withTimeout(5000) { manager.state.first { it.roots.singleOrNull()?.changedAt != null } }
        Unit
    }

    @Test fun closeWaitsForWorkerCleanupBeforeClosingItsStore(): Unit = runBlocking {
        val source = folder("documents"); val actual = store(); val root = actual.add(source.path); val finished = CountDownLatch(1)
        val wrapped = object : LocalIndexStore by actual {
            override fun close() { actual.close(); finished.countDown() }
        }
        val tree = WaitingTree(); val manager = LocalSearchIndex(app, wrapped, tree = tree)
        manager.refresh(root.id); withTimeout(5000) { tree.entered.await() }; manager.close()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        val reopened = store()
        assertEquals(IndexRefreshState.PAUSED, reopened.roots().single().state)
    }
}
