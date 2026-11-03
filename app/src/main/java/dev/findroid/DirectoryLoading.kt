package dev.findroid

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

    /** Keeps a lazy database cursor's cancellation signal registered through suspension. */
    suspend fun <T> withSuspendingSignal(work: suspend (CancellationSignal) -> T): T {
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

internal enum class ListingRootState { WAITING, SCANNING, COMPLETE, UNAVAILABLE, INCOMPLETE, EXCLUDED }

/** Transient scan state and snapshot freshness; never saved with a search. */
internal data class ListingRootProgress(
    val path: String,
    val state: ListingRootState = ListingRootState.WAITING,
    val issueCount: Int = 0,
    val providerPending: Boolean = false,
    val reason: String? = null,
    val index: IndexSearchSnapshot? = null,
)

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
    val roots: List<ListingRootProgress> = emptyList(),
    val indexed: Boolean = false,
    val indexRevalidated: Int = 0,
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

    fun indexedInput(entry: Entry, root: String, control: ListingControl): InputStream {
        currentIndexedFile(entry.path, root)
        val opened = input(entry, control)
        try { currentIndexedFile(entry.path, root); return opened }
        catch (failure: Exception) { opened.close(); throw failure }
    }

    fun searchable(entry: Entry): Boolean = true

    fun traversable(entry: Entry): Boolean

    fun searchIdentity(path: String): String = organizationKey(path)

    fun searchAncestry(path: String, ancestor: String, control: ListingControl): SearchAncestry =
        localSearchAncestry(path, ancestor)
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
    val calendar: SearchCalendar = SearchCalendar(),
    val index: LocalIndexStore? = null,
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
        class Root(val path: String) {
            var queued = 1
            var active = false
            var started = false
            var available = false
            var failed = false
            var pending = false
            var excluded = false
            var limited = false
            var issues = 0
            var reason: String? = null
            var index: IndexSearchSnapshot? = null
            fun progress() = ListingRootProgress(path, when {
                excluded && issues == 0 -> ListingRootState.EXCLUDED
                failed && !available && !pending -> ListingRootState.UNAVAILABLE
                active || queued > 0 -> if (started) ListingRootState.SCANNING else ListingRootState.WAITING
                issues > 0 || pending || limited -> ListingRootState.INCOMPLETE
                else -> ListingRootState.COMPLETE
            }, issues, pending, reason, index)
        }
        val roots = if (request.explicitPaths == null)
            request.roots.distinct().map(::Root) else emptyList()
        var currentRoot: Root? = null
        val matches = LinkedHashMap<String, Entry>()
        val issues = ArrayList<ListingIssue>()
        val notices = linkedSetOf<String>()
        var scanned = 0
        var indexRevalidated = 0
        var folders = 0
        var issueCount = 0
        var providerPending = false
        var resultLimit = false
        var scanLimit = false
        var lastPublished = clock()
        var first = true
        val exclusions = SearchExclusions(request.rules.excludedLocations, source::searchIdentity) { path, ancestor ->
            control.check()
            source.searchAncestry(path, ancestor, control)
        }
        val admissionRules = request.rules.copy(excludedLocations = emptyList())
        val unverified = mutableSetOf<Pair<String?, String>>()
        fun issue(path: String, reason: String) {
            issueCount++
            currentRoot?.let { root -> root.issues++; if (root.reason == null) root.reason = reason.take(240) }
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
                    roots = roots.map { it.progress() },
                    indexed = request.rules.source == SearchSource.LOCAL_INDEX,
                    indexRevalidated = indexRevalidated,
                ),
            )
        }
        suspend fun update(force: Boolean = false) {
            control.check()
            currentCoroutineContext().ensureActive()
            val now = clock()
            if (force || first && scanned >= 64 || now - lastPublished >= 120) {
                publish(snapshot())
                // Root-status and early timed updates must not consume the
                // first complete chunk. Publish 64 admitted checks even if an
                // earlier update was sent before a provider stalls on item 65.
                if (scanned >= 64) first = false
                // Sorting and main-thread publication can exceed the interval in large folders.
                // Start the next interval after that work, avoiding a full sort for every entry.
                lastPublished = clock()
            }
        }
        fun excluded(path: String, parent: String? = null): Boolean = when (exclusions.check(path, parent)) {
            SearchAncestry.INSIDE -> true
            SearchAncestry.OUTSIDE -> false
            SearchAncestry.UNKNOWN -> {
                if (unverified.add(currentRoot?.path to (parent ?: path))) issue(parent ?: path,
                    "Exact exclusions couldn't be verified for this location. Its items were skipped; reconnect storage or remove the exclusion.")
                true
            }
        }
        suspend fun accept(entry: Entry, parent: String? = null, countScanned: Boolean = true,
            indexedRoot: String? = null, validate: () -> Unit = {}): Boolean {
            control.check()
            currentCoroutineContext().ensureActive()
            if (countScanned) {
                if (scanned >= request.budget.scanned) {
                    scanLimit = true
                    return false
                }
                scanned++
            }
            if (
                (!request.hidden && entry.name.startsWith('.')) ||
                    isWorkingFileName(entry.name) ||
                    request.rules.excludesFolder(entry) || excluded(entry.path, parent)
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
                    request.rules.hasSizeFilter
            ) {
                issue(
                    entry.path,
                    "Unknown size: this item couldn't be checked against the size filter",
                )
                update()
                return true
            }
            val identity = source.searchIdentity(entry.path)
            // Repeated traversal still consumes the shared scan budget, while a
            // matched document is admitted and read for content only once.
            if (identity in matches) {
                update()
                return true
            }
            if (request.rules.content.isNotBlank()) update()
            val evidence = if (!request.recent || !entry.folder) admissionRules.evidence(
                entry,
                request.tags[entry.organizationPath].orEmpty(),
                now = request.calendar.now,
                read = { if (indexedRoot == null) source.input(it, control) else source.indexedInput(it, indexedRoot, control) },
                check = control::check,
                contentIssue = { issue(entry.path, it) },
                calendar = request.calendar,
            ) else null
            validate()
            if (evidence != null) {
                if (
                    !matches.containsKey(identity) &&
                        !request.recent &&
                        matches.size >= request.budget.results
                ) {
                    resultLimit = true
                    return false
                }
                matches[identity] = entry.copy(style = request.styles[entry.path], searchEvidence = evidence.takeIf { it.explained })
                if (request.recent && matches.size > 240) {
                    val keep =
                        matches.values
                            .sortedByDescending { it.modified }
                            .take(200)
                            .map { source.searchIdentity(it.path) }
                            .toSet()
                    matches.keys.retainAll(keep)
                }
            }
            update()
            return true
        }
        if (request.rules.source == SearchSource.LOCAL_INDEX) {
            if (request.explicitPaths != null) {
                issue(request.roots.firstOrNull().orEmpty(), "The local index supports folder searches. Use Live folders for collections, tags, or Trash.")
            } else {
                val checkedPaths = mutableSetOf<String>()
                val cachedRules = admissionRules.copy(content = "")
                update(force = true)
                for (root in roots) {
                    control.check()
                    currentCoroutineContext().ensureActive()
                    if (resultLimit || scanLimit) break
                    currentRoot = root
                    val matchesBeforeRoot = matches.keys.toSet()
                    root.queued = 0
                    root.started = true
                    root.active = true
                    try {
                        if (excluded(root.path)) { root.excluded = true; continue }
                        check(root.path.startsWith('/') && !isDocumentPath(root.path)) {
                            "Document providers are not indexed. Use Live folders for this location."
                        }
                        val anchor = java.io.File(root.path).canonicalPath
                        val lease = request.index?.acquire(anchor)
                            ?: throw IllegalStateException("No complete index snapshot covers this folder. Open Search index to refresh it, or choose Live folders.")
                        lease.use {
                            fun checkOwner() {
                                lease.check()
                                lease.root.filesystemIdentity?.let { expected ->
                                    if (runCatching { localIndexFileIdentity(lease.root.path) }.getOrNull() != expected)
                                        throw IndexSourceChanged("The indexed folder was moved, replaced or became unavailable. Refresh its snapshot or choose Live folders.")
                                }
                            }
                            fun checkPath(path: String) { checkOwner(); currentIndexedFile(path, lease.root.path) }
                            checkOwner()
                            val anchorEntry = source.read(root.path, control)
                            check(anchorEntry.folder) { "This search location is no longer a folder." }
                            currentIndexedFile(anchor, lease.root.path)
                            root.available = true
                            root.index = lease.root.searchSnapshot()
                            folders++
                            update(force = true)
                            lease.visit(anchor, request.rules.includeSubfolders, control) { record ->
                                control.check()
                                currentCoroutineContext().ensureActive()
                                if (scanned >= request.budget.scanned) { scanLimit = true; return@visit false }
                                scanned++
                                check(java.io.File(root.path).canonicalPath == anchor) { "The search folder changed location. Run the search again." }
                                if (!indexedScopeAdmits(record, anchor, request.rules, request.hidden) || excluded(record.path, record.parent)) {
                                    update(); return@visit true
                                }
                                val cached = record.entry()
                                if (cachedRules.evidence(cached, request.tags[cached.organizationPath].orEmpty(),
                                        now = request.calendar.now, check = control::check, calendar = request.calendar) == null) {
                                    update(); return@visit true
                                }
                                val identity = source.searchIdentity(record.path)
                                if (identity in checkedPaths) { update(); return@visit true }
                                val current = try {
                                    checkPath(record.path)
                                    indexRevalidated++
                                    source.read(record.path, control).also { entry ->
                                        checkPath(record.path)
                                        check(entry.path == record.path && entry.documentUri == null) { "This indexed item changed identity." }
                                    }
                                } catch (failure: Exception) {
                                    control.check()
                                    if (failure is CancellationException || failure is IndexSourceChanged) throw failure
                                    issue(record.path, failure.message ?: "An indexed item changed. Refresh the index.")
                                    update(); return@visit true
                                }
                                checkedPaths.add(identity)
                                try {
                                    accept(current, current.parentPath, countScanned = false, indexedRoot = lease.root.path) {
                                        checkPath(record.path)
                                    }
                                } catch (failure: Exception) {
                                    control.check()
                                    if (failure is CancellationException || failure is IndexSourceChanged) throw failure
                                    lease.check()
                                    issue(record.path, failure.message ?: "An indexed item changed. Refresh the index.")
                                    update(); true
                                }
                            }
                            checkOwner()
                        }
                    } catch (failure: Exception) {
                        control.check()
                        if (failure is CancellationException) throw failure
                        if (failure is IndexSourceChanged) matches.keys.retainAll(matchesBeforeRoot)
                        root.failed = true
                        issue(root.path, failure.message ?: "The index could not be searched. Choose Live folders or rebuild the cache.")
                    } finally {
                        root.active = false
                        if (resultLimit || scanLimit) root.limited = true
                        update(force = true)
                    }
                }
                currentRoot = null
            }
        } else if (request.explicitPaths != null) {
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
                if (!accept(entry, entry.parentPath)) break
            }
        } else {
            data class Task(val root: Int, val folder: String)
            val pending = ArrayDeque(roots.indices.map { Task(it, roots[it].path) })
            val scheduled = roots.map { mutableSetOf(source.searchIdentity(it.path)) }
            // Make every recursive root visible before any provider operation.
            if (request.recursive) update(force = true)
            while (pending.isNotEmpty() && !resultLimit && !scanLimit) {
                control.check()
                currentCoroutineContext().ensureActive()
                val task = pending.removeFirst()
                val root = roots[task.root]
                currentRoot = root
                root.queued--
                val folder = task.folder
                val rootFolder = folder == root.path
                if (excluded(folder)) {
                    if (rootFolder) root.excluded = true
                    update()
                    continue
                }
                root.started = true
                root.active = true
                folders++
                if (request.recursive && rootFolder) update(force = true)
                val before = scanned
                try {
                    val reply = source.children(folder, control) { entry ->
                        val proceed = accept(entry, folder)
                        if (proceed && request.recursive && request.rules.includeSubfolders &&
                            entry.folder && !request.rules.excludesFolder(entry) &&
                            !excluded(entry.path, folder) && (request.hidden || !entry.name.startsWith('.')) &&
                            !isWorkingFileName(entry.name) && source.traversable(entry) &&
                            scheduled[task.root].add(source.searchIdentity(entry.path))) {
                            pending.addLast(Task(task.root, entry.path))
                            root.queued++
                        }
                        proceed
                    }
                    if (rootFolder) {
                        root.available = scanned > before || (reply.error.isNullOrBlank() && !reply.loading)
                        root.failed = !reply.error.isNullOrBlank()
                    }
                    if (reply.loading) { providerPending = true; root.pending = true }
                    reply.error?.takeIf { it.isNotBlank() }?.let { issue(folder, it) }
                    reply.info?.takeIf { it.isNotBlank() }?.let { if (notices.size < 5) notices.add(it.take(240)) }
                } catch (error: Exception) {
                    control.check()
                    if (error is CancellationException) throw error
                    if (!request.recursive && roots.size == 1 && scanned == 0 && before == 0) throw error
                    if (rootFolder) { root.failed = true; root.available = scanned > before }
                    issue(folder, error.message ?: "Couldn't read this folder")
                } finally {
                    root.active = false
                    if (resultLimit || scanLimit) root.limited = true
                }
                update()
            }
            currentRoot = null
        }
        control.check()
        currentCoroutineContext().ensureActive()
        return snapshot().also { publish(it) }
    }
}
