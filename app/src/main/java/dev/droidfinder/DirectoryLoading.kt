package dev.droidfinder

import android.os.CancellationSignal
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Cancellation marks the request synchronously; provider/stream cleanup never blocks the UI. */
internal class ListingControl {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val cleanup = mutableSetOf<() -> Unit>()

    fun check() {
        if (cancelled.get()) throw CancellationException("Listing stopped")
    }

    private fun add(action: () -> Unit) {
        synchronized(lock) {
            check()
            cleanup.add(action)
        }
    }

    private fun remove(action: () -> Unit) {
        synchronized(lock) { cleanup.remove(action) }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val actions = synchronized(lock) { cleanup.toList().also { cleanup.clear() } }
        ListingCleanup.scope.launch { actions.forEach { runCatching(it) } }
    }

    fun <T> withSignal(work: (CancellationSignal) -> T): T {
        val signal = CancellationSignal()
        val action = { signal.cancel() }
        add(action)
        try {
            check()
            return work(signal)
        } finally {
            remove(action)
        }
    }

    fun stream(open: (CancellationSignal) -> InputStream): InputStream {
        val signal = CancellationSignal()
        val input = java.util.concurrent.atomic.AtomicReference<InputStream?>()
        val action = {
            try {
                signal.cancel()
            } finally {
                input.get()?.close()
            }
            Unit
        }
        add(action)
        try {
            val opened = open(signal)
            input.set(opened)
            check()
            return object : FilterInputStream(opened) {
                override fun read(): Int {
                    check()
                    return super.read().also { check() }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    check()
                    return super.read(b, off, len).also { check() }
                }

                override fun close() {
                    remove(action)
                    try {
                        super.close()
                    } finally {
                        runCatching { signal.cancel() }
                    }
                }
            }
        } catch (error: Exception) {
            remove(action)
            runCatching { input.get()?.close() }
            runCatching { signal.cancel() }
            throw error
        }
    }
}

private object ListingCleanup {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

internal data class DirectoryReply(
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

internal data class ListingIssue(val path: String, val reason: String)

data class ListingBudget(val results: Int = 20_000, val scanned: Int = 50_000) {
    fun normalized() =
        copy(
            results = results.coerceIn(20_000, 50_000),
            scanned = scanned.coerceIn(50_000, 2_000_000),
        )

    fun more() =
        ListingBudget((results * 2).coerceAtMost(50_000), (scanned * 2).coerceAtMost(2_000_000))
}

internal data class ListingProgress(
    val scanned: Int = 0,
    val folders: Int = 0,
    val matches: Int = 0,
    val issues: List<ListingIssue> = emptyList(),
    val issueCount: Int = 0,
    val providerPending: Boolean = false,
    val providerInfo: List<String> = emptyList(),
    val resultLimit: Boolean = false,
    val scanLimit: Boolean = false,
    val stopped: Boolean = false,
) {
    val incomplete
        get() = issueCount > 0 || providerPending || resultLimit || scanLimit || stopped
}

internal data class ListingBatch(val entries: List<Entry>, val progress: ListingProgress)

/** Storage adapters stream one child at a time. Nothing materializes an entire recursive tree. */
internal interface ListingSource {
    suspend fun children(
        path: String,
        control: ListingControl,
        visit: suspend (Entry) -> Boolean,
    ): DirectoryReply

    fun read(path: String, control: ListingControl): Entry

    fun input(entry: Entry, control: ListingControl): InputStream

    fun searchable(entry: Entry): Boolean = true

    fun traversable(entry: Entry): Boolean
}

internal data class ListingRequest(
    val roots: List<String>,
    val recursive: Boolean,
    val hidden: Boolean,
    val rules: SearchRules,
    val tags: Map<String, Set<String>>,
    val styles: Map<String, FolderStyle>,
    val comparator: Comparator<Entry>,
    val budget: ListingBudget = ListingBudget(),
    val recent: Boolean = false,
    val explicitPaths: List<String>? = null,
)

internal class DirectoryLoader(
    private val source: ListingSource,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun load(
        request: ListingRequest,
        control: ListingControl,
        publish: suspend (ListingBatch) -> Unit,
    ): ListingBatch {
        val matches = LinkedHashMap<String, Entry>()
        val issues = ArrayList<ListingIssue>()
        val notices = linkedSetOf<String>()
        var scanned = 0
        var folders = 0
        var issueCount = 0
        var providerPending = false
        var resultLimit = false
        var scanLimit = false
        var lastPublished = clock()
        var first = true
        fun issue(path: String, reason: String) {
            issueCount++
            if (issues.size < 20) issues += ListingIssue(path, reason.take(240))
        }
        fun snapshot(): ListingBatch {
            val values =
                if (request.recent) matches.values.sortedByDescending { it.modified }.take(200)
                else matches.values.toList()
            return ListingBatch(
                values.sortedWith(request.comparator),
                ListingProgress(
                    scanned,
                    folders,
                    values.size,
                    issues.toList(),
                    issueCount,
                    providerPending,
                    notices.toList(),
                    resultLimit,
                    scanLimit,
                ),
            )
        }
        suspend fun update(force: Boolean = false) {
            control.check()
            currentCoroutineContext().ensureActive()
            val now = clock()
            if (force || first && scanned >= 64 || now - lastPublished >= 120) {
                publish(snapshot())
                first = false
                lastPublished = now
            }
        }
        suspend fun accept(entry: Entry): Boolean {
            control.check()
            currentCoroutineContext().ensureActive()
            if (scanned >= request.budget.scanned) {
                scanLimit = true
                return false
            }
            scanned++
            if (
                (!request.hidden && entry.name.startsWith('.')) ||
                    entry.name.startsWith(".droidfinder-")
            ) {
                update()
                return true
            }
            if (request.recursive && !source.searchable(entry)) {
                update()
                return true
            }
            if (
                !entry.folder &&
                    !entry.sizeKnown &&
                    (request.rules.minimumBytes > 0 || request.rules.maximumBytes > 0)
            ) {
                issue(
                    entry.path,
                    "Unknown size: this item couldn't be checked against the size filter",
                )
                update()
                return true
            }
            if (request.rules.content.isNotBlank()) update()
            val eligible = !request.recent || !entry.folder
            if (
                eligible &&
                    request.rules.matches(
                        entry,
                        request.tags[entry.path].orEmpty(),
                        read = { source.input(it, control) },
                        check = control::check,
                        contentIssue = { issue(entry.path, it) },
                    )
            ) {
                if (
                    !matches.containsKey(entry.path) &&
                        !request.recent &&
                        matches.size >= request.budget.results
                ) {
                    resultLimit = true
                    return false
                }
                matches[entry.path] = entry.copy(style = request.styles[entry.path])
                if (request.recent && matches.size > 240) {
                    val keep =
                        matches.values
                            .sortedByDescending { it.modified }
                            .take(200)
                            .map { it.path }
                            .toSet()
                    matches.keys.retainAll(keep)
                }
            }
            update()
            return true
        }
        if (request.explicitPaths != null) {
            for (path in request.explicitPaths) {
                control.check()
                val entry =
                    try {
                        source.read(path, control)
                    } catch (error: Exception) {
                        control.check()
                        if (error is CancellationException) throw error
                        issue(path, error.message ?: "Couldn't read this item")
                        continue
                    }
                if (!accept(entry)) break
            }
        } else {
            val pending = ArrayDeque(request.roots.distinct())
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty() && !resultLimit && !scanLimit) {
                control.check()
                currentCoroutineContext().ensureActive()
                val folder = pending.removeFirst()
                if (!visited.add(folder)) continue
                folders++
                val before = scanned
                try {
                    val reply =
                        source.children(folder, control) { entry ->
                            val proceed = accept(entry)
                            if (
                                proceed &&
                                    request.recursive &&
                                    entry.folder &&
                                    (request.hidden || !entry.name.startsWith('.')) &&
                                    !entry.name.startsWith(".droidfinder-") &&
                                    source.traversable(entry)
                            )
                                pending.addLast(entry.path)
                            proceed
                        }
                    if (reply.loading) providerPending = true
                    reply.error?.takeIf { it.isNotBlank() }?.let { issue(folder, it) }
                    reply.info
                        ?.takeIf { it.isNotBlank() }
                        ?.let { if (notices.size < 5) notices.add(it.take(240)) }
                } catch (error: Exception) {
                    control.check()
                    if (error is CancellationException) throw error
                    if (!request.recursive && scanned == 0 && before == 0) throw error
                    issue(folder, error.message ?: "Couldn't read this folder")
                }
                update()
            }
        }
        control.check()
        currentCoroutineContext().ensureActive()
        return snapshot().also { publish(it) }
    }
}
