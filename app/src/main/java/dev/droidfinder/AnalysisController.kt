package dev.droidfinder

import android.app.Application
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.*
import java.io.InputStream
import kotlinx.coroutines.*

internal class DocumentAnalysisSource(
    private val documents: DocumentAccess,
    private val allowed: (String) -> Boolean,
) : AnalysisSource {
    internal fun node(entry: Entry): AnalysisNode {
        check(allowed(entry.path)) {
            "This item needs storage access or is outside the authorized locations."
        }
        val stat = if (entry.documentUri == null) Os.lstat(entry.path) else null
        val link = stat?.let { OsConstants.S_ISLNK(it.st_mode) } == true
        check(
            stat == null ||
                link ||
                OsConstants.S_ISREG(stat.st_mode) ||
                OsConstants.S_ISDIR(stat.st_mode),
        ) {
            "Special filesystem items are not analyzed."
        }
        val objectId = stat?.let { "${it.st_dev}:${it.st_ino}" } ?: entry.path
        val size = entry.size.takeIf { entry.sizeKnown && it >= 0 && !entry.folder }
        val revision =
            "$objectId:${entry.folder}:$size:${entry.modified}:${entry.name}:${entry.mime}"
        return AnalysisNode(
            entry.path,
            entry.name,
            entry.folder,
            size,
            entry.modified,
            objectId,
            revision,
            FileCategory.of(entry.name, entry.mime),
            link,
            entry.canDelete,
            mime = entry.mime,
        )
    }

    override fun read(path: String, control: ListingControl): AnalysisNode {
        control.check()
        check(allowed(path)) { "Reconnect this location before analyzing it." }
        return node(documents.read(path, control))
    }

    override suspend fun children(
        path: String,
        control: ListingControl,
        visit: suspend (AnalysisNode) -> Boolean,
    ): DirectoryReply =
        documents.visitChildren(path, control) { entry ->
            control.check()
            val result = runCatching {
                node(entry)
            }
                .getOrElse {
                    AnalysisNode(
                        entry.path,
                        entry.name,
                        entry.folder,
                        null,
                        entry.modified,
                        entry.path,
                        "unavailable",
                        problem = it.message ?: "Item unavailable",
                    )
                }
            visit(result)
        }

    override fun input(path: String, control: ListingControl): InputStream {
        check(allowed(path)) { "This file needs storage access." }
        return documents.input(path, control)
    }
}

internal enum class AnalysisView(val title: String) {
    OVERVIEW("Overview"),
    LARGE("Large files"),
    OLD("Old files"),
    DUPLICATES("Duplicates"),
    EMPTY("Empty files"),
}

internal class AnalysisController(
    app: Application,
    private val scope: CoroutineScope,
    val source: AnalysisSource,
) {
    private val prefs = app.getSharedPreferences("analysis", 0)
    private var job: Job? = null
    private var token = ListingControl()
    private var generation = 0
    private var lastProgress = 0L
    var visible by mutableStateOf(false)
        private set

    var root by mutableStateOf<String?>(null)
        private set

    var report by mutableStateOf<AnalysisReport?>(null)
        private set

    var duplicates by mutableStateOf<DuplicateReport?>(null)
        private set

    var running by mutableStateOf(false)
        private set

    var stopping by mutableStateOf(false)
        private set

    var progress by mutableStateOf(AnalysisProgress("Ready"))
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var view by mutableStateOf(AnalysisView.OVERVIEW)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var includeHidden by mutableStateOf(prefs.getBoolean("hidden", true))
        private set

    var limits by
        mutableStateOf(
            AnalysisLimits(
                    prefs.getInt("entries", 100_000),
                    prefs.getLong("hashBytes", 8L * 1024 * 1024 * 1024),
                )
                .normalized(),
        )
        private set

    var largeBytes by mutableStateOf(prefs.getLong("large", 100L * 1024 * 1024))
        private set

    var oldDays by mutableStateOf(prefs.getInt("oldDays", 365))
        private set

    val files: List<AnalyzedFile> by derivedStateOf {
        val files = report?.files.orEmpty()
        when (view) {
            AnalysisView.LARGE ->
                files
                    .filter { (it.node.bytes ?: -1) >= largeBytes }
                    .sortedByDescending { it.node.bytes }
            AnalysisView.OLD ->
                files
                    .filter {
                        it.node.modified in
                            1 until System.currentTimeMillis() - oldDays * 86_400_000L
                    }
                    .sortedBy { it.node.modified }
            AnalysisView.EMPTY -> files.filter { it.node.bytes == 0L }.sortedBy { it.relative }
            AnalysisView.DUPLICATES -> duplicates?.groups.orEmpty().flatMap { it.files }
            else -> emptyList()
        }
    }

    private fun update(id: Int, value: AnalysisProgress) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProgress < 100) return
        lastProgress = now
        scope.launch { if (generation == id) progress = value }
    }

    fun open(path: String) {
        visible = true
        if (root != path) view = AnalysisView.OVERVIEW
        root = path
        refresh()
    }

    fun refresh() {
        val path = root ?: return
        begin { id, control ->
            report = null
            duplicates = null
            selected = emptySet()
            val limits = limits
            val hidden = includeHidden
            val result =
                withContext(Dispatchers.IO) {
                    FolderAnalyzer(source).scan(path, hidden, limits, control) { update(id, it) }
                }
            if (generation == id) report = result
        }
    }

    fun checkDuplicates() {
        val snapshot = report ?: return
        selected = emptySet()
        view = AnalysisView.DUPLICATES
        begin { id, control ->
            duplicates = null
            val limits = limits
            val result =
                withContext(Dispatchers.IO) {
                    FolderAnalyzer(source).duplicates(snapshot, limits, control) { update(id, it) }
                }
            if (generation == id) {
                duplicates = result
                if (report === snapshot && result.observed.isNotEmpty()) {
                    val files =
                        snapshot.files.map { file ->
                            result.observed[file.node.path]?.let { sum ->
                                file.copy(
                                    node =
                                        file.node.copy(bytes = sum.bytes, observedHash = sum.hex),
                                )
                            } ?: file
                        }
                    val byFolder = files.groupBy { it.bucket }
                    report =
                        snapshot.copy(
                            files = files,
                            buckets =
                                snapshot.buckets.map { bucket ->
                                    val members = byFolder[bucket.path].orEmpty()
                                    bucket.copy(
                                        bytes =
                                            members.fold(0L) { a, f ->
                                                analysisAdd(a, f.node.bytes ?: 0)
                                            },
                                        unknown = members.count { it.node.bytes == null },
                                    )
                                },
                        )
                }
            }
        }
    }

    private fun begin(work: suspend (Int, ListingControl) -> Unit) {
        token.cancel()
        job?.cancel()
        val id = ++generation
        val control = ListingControl().also { token = it }
        running = true
        stopping = false
        error = null
        lastProgress = 0
        progress = AnalysisProgress("Starting…")
        job = scope.launch {
            try {
                work(id, control)
            } catch (e: CancellationException) {
                if (id == generation)
                    error = "Stopped before results were ready. Refresh to try again."
            } catch (e: Exception) {
                if (id == generation) error = e.message ?: "Analysis unavailable"
            } finally {
                if (id == generation) {
                    running = false
                    stopping = false
                }
            }
        }
    }

    fun stop() {
        stopping = true
        token.cancel()
    }

    fun close() {
        visible = false
        generation++
        token.cancel()
        job?.cancel()
        running = false
        stopping = false
    }

    fun configure(
        newLimits: AnalysisLimits = limits,
        hidden: Boolean = includeHidden,
        large: Long = largeBytes,
        days: Int = oldDays,
        rescan: Boolean = false,
    ) {
        limits = newLimits.normalized()
        includeHidden = hidden
        largeBytes = large
        oldDays = days
        prefs
            .edit()
            .putInt("entries", limits.entries)
            .putLong("hashBytes", limits.hashBytes)
            .putBoolean("hidden", hidden)
            .putLong("large", large)
            .putInt("oldDays", days)
            .apply()
        selected = emptySet()
        if (rescan) refresh()
    }

    fun selectExtras() {
        selected =
            duplicates
                ?.groups
                .orEmpty()
                .flatMap { group ->
                    val keep = group.files.firstOrNull { !it.node.canDelete } ?: group.files.first()
                    group.files
                        .filter { it.node.path != keep.node.path && it.node.canDelete }
                        .map { it.node.path }
                }
                .toSet()
    }

    fun removed(snapshot: AnalysisReport, paths: List<String>, failures: List<String>) {
        if (
            report?.root?.path != snapshot.root.path ||
                report?.root?.objectId != snapshot.root.objectId
        )
            return
        val set = paths.toSet()
        report = report?.let { r ->
            val files = r.files.filterNot { it.node.path in set }
            val byFolder = files.groupBy { it.bucket }
            r.copy(
                files = files,
                buckets =
                    r.buckets.map { bucket ->
                        val members = byFolder[bucket.path].orEmpty()
                        bucket.copy(
                            bytes = members.fold(0L) { a, b -> analysisAdd(a, b.node.bytes ?: 0) },
                            files = members.size,
                            unknown = members.count { it.node.bytes == null },
                        )
                    },
            )
        }
        duplicates = duplicates?.let { r ->
            r.copy(
                groups =
                    r.groups
                        .map { g -> g.copy(files = g.files.filterNot { it.node.path in set }) }
                        .filter { it.files.size > 1 },
            )
        }
        selected = emptySet()
        error =
            if (failures.isEmpty())
                "${paths.size} moved to Trash. Undo is available in the explorer."
            else
                "${paths.size} moved to Trash. ${failures.size} skipped: ${failures.take(3).joinToString(" · ")}"
    }
}

internal class ChecksumController(
    private val scope: CoroutineScope,
    private val source: AnalysisSource,
) {
    private var token = ListingControl()
    private var job: Job? = null
    private var generation = 0
    var visible by mutableStateOf(false)
        private set

    var path by mutableStateOf("")
        private set

    var algorithm by mutableStateOf(ChecksumAlgorithm.SHA256)
        private set

    var result by mutableStateOf<FileChecksum?>(null)
        private set

    var running by mutableStateOf(false)
        private set

    var bytes by mutableStateOf(0L)
        private set

    var error by mutableStateOf<String?>(null)
    var expected by mutableStateOf("")

    fun open(path: String) {
        this.path = path
        visible = true
        expected = ""
        calculate()
    }

    fun choose(algorithm: ChecksumAlgorithm) {
        this.algorithm = algorithm
        expected = ""
        calculate()
    }

    fun calculate() {
        token.cancel()
        job?.cancel()
        val id = ++generation
        val control = ListingControl().also { token = it }
        result = null
        bytes = 0
        running = true
        error = null
        val path = path
        val algorithm = algorithm
        job = scope.launch {
            try {
                var last = 0L
                val sum =
                    withContext(Dispatchers.IO) {
                        fileChecksum(source, source.read(path, control), algorithm, control) { count
                            ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - last > 100) {
                                last = now
                                scope.launch { if (generation == id) bytes = count }
                            }
                        }
                    }
                if (generation == id) {
                    result = sum
                    bytes = sum.bytes
                }
            } catch (e: CancellationException) {
                if (generation == id) error = "Checksum stopped. Run again to read the whole file."
            } catch (e: Exception) {
                if (generation == id)
                    error =
                        if (runCatching { control.check() }.isFailure)
                            "Checksum stopped. Run again to read the whole file."
                        else e.message ?: "Couldn't calculate checksum"
            } finally {
                if (generation == id) running = false
            }
        }
    }

    fun stop() {
        token.cancel()
    }

    fun close() {
        visible = false
        generation++
        token.cancel()
        job?.cancel()
        running = false
    }
}
