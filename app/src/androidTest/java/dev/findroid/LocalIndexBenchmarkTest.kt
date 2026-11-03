package dev.findroid

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Repeatable synthetic-library measurements, explicitly separate from real-tablet benchmarks. */
class LocalIndexBenchmarkTest {
    @Test fun metadataSnapshotMatchesLiveResultsAndRecordsGrowthMemoryAndCancellation(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "index-benchmark-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        val folder = File(fixture, "Library").apply { mkdir() }
        val store = SqliteLocalIndexStore(context, File(fixture, "metadata.sqlite"))
        try {
            repeat(200) { section ->
                val parent = File(folder, "Section $section").apply { mkdir() }
                repeat(30) { item -> File(parent, (if (item % 6 == 0) "Report" else "Note") + " $section-$item.txt").writeText("Synthetic metadata benchmark document") }
            }
            val source = object : ListingSource {
                override suspend fun children(path: String, control: ListingControl, visit: suspend (Entry) -> Boolean): DirectoryReply {
                    java.nio.file.Files.newDirectoryStream(File(path).toPath()).use { stream -> for (file in stream) { control.check(); if (!visit(Entry(file.toFile()))) break } }
                    return DirectoryReply()
                }
                override fun read(path: String, control: ListingControl) = Entry(File(path))
                override fun input(entry: Entry, control: ListingControl): java.io.InputStream = error("Metadata benchmark must not read contents")
                override fun traversable(entry: Entry) = entry.folder
            }
            val rules = SearchRules(name = "Report", kind = "Text document")
            fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val root = store.add(folder.path); val token = store.begin(root.id); val started = SystemClock.elapsedRealtimeNanos()
            val progress = LocalIndexScanner(store).scan(token, ListingControl())
            store.complete(token, System.currentTimeMillis(), progress.entries)
            val buildMs = elapsed(started); val cacheBytes = store.storageBytes()
            suspend fun measured(indexed: Boolean): Pair<ListingBatch, JSONObject> {
                val start = SystemClock.elapsedRealtimeNanos(); var first: Double? = null
                var peakPssKb = Debug.getPss(); val baselinePssKb = peakPssKb
                val request = ListingRequest(listOf(folder.path), true, false, rules.copy(source = if (indexed) SearchSource.LOCAL_INDEX else SearchSource.LIVE),
                    emptyMap(), emptyMap(), compareBy { it.name }, index = if (indexed) store else null)
                val result = DirectoryLoader(source).load(request, ListingControl()) { batch ->
                    if (first == null && batch.entries.isNotEmpty()) first = elapsed(start)
                    peakPssKb = maxOf(peakPssKb, Debug.getPss())
                }
                return result to JSONObject().put("firstUsableResultsMs", first).put("completeMs", elapsed(start))
                    .put("results", result.entries.size).put("candidatesExamined", result.progress.scanned)
                    .put("currentCandidatesRechecked", result.progress.indexRevalidated)
                    .put("baselineProcessPssKb", baselinePssKb).put("sampledPeakProcessPssKb", peakPssKb)
            }
            val live = measured(false); val indexed = measured(true)
            assertEquals(live.first.entries.map { it.path }.toSet(), indexed.first.entries.map { it.path }.toSet())
            assertEquals(1000, indexed.first.entries.size); assertEquals(6200, progress.entries)
            val attempt = store.begin(root.id); val control = ListingControl(); var canceledAt = 0L; var canceledEntries = 0
            try {
                LocalIndexScanner(store).scan(attempt, control) { update -> if (update.entries >= 512 && canceledAt == 0L) { canceledAt = SystemClock.elapsedRealtimeNanos(); canceledEntries = update.entries; control.cancel() } }
                fail("Benchmark scan ignored cancellation")
            } catch (_: CancellationException) {}
            val cancelMs = elapsed(canceledAt); store.abandon(attempt, IndexRefreshState.PAUSED, canceledEntries, "Synthetic benchmark pause")
            assertTrue(canceledAt > 0); assertTrue("Cancellation latency $cancelMs ms", cancelMs < 1000)
            assertEquals(token.generation, store.roots().single().generation)
            val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("fixtureFiles", 6000).put("fixtureFolders", 200)
                .put("scope", "Debug instrumentation on an emulator; warm library search, no content reads, no real-tablet battery or startup claim")
                .put("snapshotBuildMs", buildMs).put("cacheBytesAfterBuild", cacheBytes).put("live", live.second).put("indexed", indexed.second)
                .put("entriesSavedBeforePause", canceledEntries).put("pauseLatencyMs", cancelMs).put("previousSnapshotRetained", true)
            File(context.filesDir, "local-index-qa").apply { mkdirs() }.resolve("benchmark.json").writeText(report.toString(2))
        } finally { store.close(); fixture.deleteRecursively() }
    }
}
