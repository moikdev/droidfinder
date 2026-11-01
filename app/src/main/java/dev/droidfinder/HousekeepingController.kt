package dev.droidfinder

import android.app.Application
import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal enum class HousekeepingView(val title: String) {
    TRASH("Trash"),
    CACHE("Cache"),
}

internal data class HousekeepingReport(
    val trash: List<TrashSpaceItem>,
    val cache: List<CacheSpaceItem>,
    val thumbnails: ThumbnailStats,
    val issues: List<String>,
    val at: Long,
    val trashIncomplete: Boolean,
    val cacheIncomplete: Boolean,
    val issueCount: Int,
)

internal class HousekeepingController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val engine: FileEngine,
) {
    private val prefs = app.getSharedPreferences("housekeeping", 0)
    internal val cache = CacheMaintenance(app.cacheDir)
    private var token = ListingControl()
    private var job: Job? = null
    private var generation = 0
    var visible by mutableStateOf(false)
        private set

    var running by mutableStateOf(false)
        private set

    var report by mutableStateOf<HousekeepingReport?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var outcome by mutableStateOf<String?>(null)
        internal set

    var view by mutableStateOf(HousekeepingView.TRASH)
        private set

    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    var trashDays by
        mutableIntStateOf(prefs.getInt("trashDays", 30).takeIf { it in listOf(0, 7, 30, 90) } ?: 30)
        private set

    var cacheDays by
        mutableIntStateOf(prefs.getInt("cacheDays", 7).takeIf { it in listOf(1, 7, 30) } ?: 7)
        private set

    val selectedTrash
        get() = report?.trash.orEmpty().filter { it.file.path in selected && it.disk.complete }

    val selectedCache
        get() =
            report?.cache.orEmpty().filter {
                it.file.path in selected && it.eligible(System.currentTimeMillis())
            }

    val selectedBytes
        get() =
            if (view == HousekeepingView.TRASH) selectedTrash.logicalBytes { it.disk.bytes }
            else selectedCache.logicalBytes { it.disk.bytes }

    val selectedCount
        get() = if (view == HousekeepingView.TRASH) selectedTrash.size else selectedCache.size

    fun open() {
        trashDays = prefs.getInt("trashDays", 30).takeIf { it in listOf(0, 7, 30, 90) } ?: 30
        cacheDays = prefs.getInt("cacheDays", 7).takeIf { it in listOf(1, 7, 30) } ?: 7
        visible = true
        refresh()
    }

    fun close() {
        visible = false
        stop()
    }

    fun stop() {
        generation++
        token.cancel()
        job?.cancel()
        running = false
    }

    fun changeView(value: HousekeepingView) {
        view = value
        selected = emptySet()
    }

    fun changeTrashDays(days: Int) {
        require(days in listOf(0, 7, 30, 90))
        trashDays = days
        prefs.edit().putInt("trashDays", days).apply()
        selected = emptySet()
    }

    fun changeCacheDays(days: Int) {
        require(days in listOf(1, 7, 30))
        cacheDays = days
        prefs.edit().putInt("cacheDays", days).apply()
        selected = emptySet()
    }

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun selectTrash(expiredOnly: Boolean) {
        val now = System.currentTimeMillis()
        selected =
            report
                ?.trash
                .orEmpty()
                .filter { it.disk.complete && (!expiredOnly || it.expired(trashDays, now)) }
                .map { it.file.path }
                .toSet()
    }

    fun selectCache(expiredOnly: Boolean) {
        val now = System.currentTimeMillis()
        selected =
            report
                ?.cache
                .orEmpty()
                .filter { it.eligible(now, if (expiredOnly) cacheDays else 0) }
                .map { it.file.path }
                .toSet()
    }

    fun toggleCacheKind(kind: CacheKind) {
        val now = System.currentTimeMillis()
        val paths =
            report
                ?.cache
                .orEmpty()
                .filter { it.kind == kind && it.eligible(now) }
                .map { it.file.path }
                .toSet()
        selected = if (paths.all { it in selected }) selected - paths else selected + paths
    }

    fun refresh() {
        stop()
        val request = generation
        token = ListingControl()
        val control = token
        running = true
        error = null
        selected = emptySet()
        job = scope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        val issues = mutableListOf<String>()
                        val (inventory, trashIssues) = engine.trashInventory(control::check)
                        issues += trashIssues
                        val trash =
                            inventory.sortedWith(
                                compareByDescending<java.io.File> {
                                        engine.trashedAt(it) ?: Long.MIN_VALUE
                                    }
                                    .thenBy { it.name.lowercase() },
                            )
                        var remaining = 100_000
                        val rows =
                            trash.take(10_000).map { file ->
                                control.check()
                                val disk =
                                    inspectDisk(file, control::check, remaining.coerceAtLeast(0))
                                remaining =
                                    (remaining - disk.files - disk.folders - disk.links)
                                        .coerceAtLeast(0)
                                TrashSpaceItem(
                                    file,
                                    engine.originalPath(file),
                                    engine.trashedAt(file),
                                    disk,
                                )
                            }
                        val (cached, cacheIssues) = cache.inspect(control::check)
                        issues += cacheIssues
                        rows.mapNotNullTo(issues) {
                            it.disk.issue?.let { problem -> "${it.file.name}: $problem" }
                        }
                        cached.mapNotNullTo(issues) {
                            it.disk.issue?.let { problem -> "${it.kind.title}: $problem" }
                        }
                        val thumbnails = (app as FinderApplication).thumbnails.stats()
                        HousekeepingReport(
                            rows,
                            cached,
                            thumbnails,
                            issues.take(100),
                            System.currentTimeMillis(),
                            trashIssues.isNotEmpty() || rows.any { !it.disk.complete },
                            cacheIssues.isNotEmpty() || cached.any { !it.disk.complete },
                            issues.size,
                        )
                    }
                if (request == generation) report = result
            } catch (failure: Exception) {
                if (failure !is CancellationException && request == generation)
                    error = failure.message ?: "Couldn't measure storage."
            } finally {
                if (request == generation) running = false
            }
        }
    }

    fun clearThumbnails() {
        scope.launch {
            (app as FinderApplication).thumbnails.clear()
            outcome = "Thumbnail memory cleared. Visible images may refill the cache."
            refresh()
        }
    }
}
