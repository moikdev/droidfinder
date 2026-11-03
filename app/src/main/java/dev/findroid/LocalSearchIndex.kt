package dev.findroid

import android.app.Application
import android.os.Environment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class LocalIndexManagerState(
    val roots: List<LocalIndexRoot> = emptyList(),
    val storageBytes: Long = 0,
    val loading: Boolean = false,
    val activeRoot: String? = null,
    val progress: IndexBuildProgress = IndexBuildProgress(),
    val stopping: Boolean = false,
    val error: String? = null,
    val checkRevision: Long = 0,
)

/** Manual, process-owned work only. Reading a saved source choice never opts a folder in. */
internal class LocalSearchIndex(
    app: Application,
    internal val store: LocalIndexStore = SqliteLocalIndexStore(app),
    private val limits: LocalIndexLimits = LocalIndexLimits(),
    tree: LocalIndexTree = LocalFileIndexTree(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.IO)
    private val operations = Mutex()
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(LocalIndexManagerState())
    val state: StateFlow<LocalIndexManagerState> = mutableState.asStateFlow()
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = mutableChanges.asSharedFlow()
    private var loaded = false
    private class Build(val root: String, val control: ListingControl = ListingControl()) {
        lateinit var job: Job
        val removed = AtomicBoolean(false)
        var progress = IndexBuildProgress()
        var stopping = false
    }
    private var active: Build? = null
    private val scanner = LocalIndexScanner(store, tree, limits) { row ->
        !(row.name == "Android" && row.parent == Environment.getExternalStorageDirectory().absolutePath)
    }

    private suspend fun reload() {
        try {
            val roots = store.roots()
            val bytes = store.storageBytes()
            synchronized(lock) {
                mutableState.update { old -> old.copy(roots = roots, storageBytes = bytes, loading = false,
                    activeRoot = active?.root, progress = active?.progress ?: old.progress,
                    stopping = active?.stopping ?: false) }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.update { it.copy(loading = false, storageBytes = runCatching { store.storageBytes() }.getOrDefault(it.storageBytes),
                error = "The search index could not be read. Its cache was kept. Delete the index cache to rebuild it. ${failure.message.orEmpty()}".take(900)) }
        }
    }
    fun load(force: Boolean = false) {
        synchronized(lock) { if (closed.get() || loaded && !force) return; loaded = true }
        mutableState.update { it.copy(loading = true, checkRevision = it.checkRevision + 1) }
        scope.launch { operations.withLock { reload() } }
    }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    private fun error(message: String) { mutableState.update { it.copy(error = message.take(900)) } }

    fun add(path: String, allowed: (String) -> Boolean) {
        if (closed.get()) return
        scope.launch {
            operations.withLock {
                try {
                    require(path.startsWith('/') && !isDocumentPath(path)) { "The local index supports ordinary local folders. Use live search for document providers." }
                    val folder = File(path).canonicalFile
                    require(indexPath(folder.path) && allowed(folder.path) && folder.isDirectory && folder.canRead()) { "Choose an available local folder with storage access." }
                    val root = store.add(folder.path)
                    reload()
                    mutableChanges.emit(Unit)
                    refresh(root.id)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error(failure.message ?: "This folder could not be added to the search index.")
                }
            }
        }
    }

    fun refresh(rootId: String) {
        val build = synchronized(lock) {
            if (closed.get()) return
            if (active != null) { error("One indexed folder can refresh at a time. Pause the current refresh or wait for it to finish."); return }
            Build(rootId).also {
                it.job = scope.launch(start = CoroutineStart.LAZY) { runBuild(it) }
                active = it
                mutableState.update { old -> old.copy(activeRoot = rootId, progress = it.progress, stopping = false, error = null) }
            }
        }
        build.job.start()
    }

    private suspend fun runBuild(build: Build) {
        var token: IndexBuildToken? = null
        var progress = IndexBuildProgress()
        var cleanupError: String? = null
        try {
            build.control.check()
            val started = operations.withLock { build.control.check(); store.begin(build.root) }
            token = started
            operations.withLock { reload() }
            mutableChanges.emit(Unit)
            var lastUpdate = 0L
            progress = scanner.scan(started, build.control) { update ->
                progress = update
                val now = System.nanoTime() / 1_000_000
                if (now - lastUpdate >= 150) {
                    lastUpdate = now
                    val bytes = store.storageBytes()
                    synchronized(lock) {
                        build.progress = update
                        if (active === build) mutableState.update { it.copy(progress = update, storageBytes = bytes) }
                    }
                }
            }
            build.control.check()
            currentCoroutineContext().ensureActive()
            operations.withLock { store.complete(started, clock(), progress.entries, build.control::check) }
        } catch (failure: Exception) {
            val state = when (failure) {
                is CancellationException -> IndexRefreshState.PAUSED
                is IndexBuildLimit -> IndexRefreshState.CAPPED
                else -> IndexRefreshState.FAILED
            }
            val reason = when (state) {
                IndexRefreshState.PAUSED -> "Refresh paused. Resume restarts the scan; the previous complete snapshot was kept."
                else -> (failure.message ?: "The refresh could not finish. The previous complete snapshot was kept.").take(512)
            }
            withContext(NonCancellable) {
                val started = token
                if (started != null && !build.removed.get()) {
                    runCatching { operations.withLock { store.abandon(started, state, progress.entries, reason) } }
                        .onFailure { cleanupError = "The refresh stopped, but its status could not be saved. Reopen the index to recover it. ${it.message.orEmpty()}".take(900) }
                } else if (started == null && failure !is CancellationException && !build.removed.get()) cleanupError = reason
            }
        } finally {
            withContext(NonCancellable) {
                operations.withLock {
                    synchronized(lock) {
                        if (active === build) {
                            active = null
                            mutableState.update { it.copy(activeRoot = null, progress = progress, stopping = false) }
                        }
                    }
                    reload()
                    if (!build.removed.get()) cleanupError?.let(::error)
                }
                mutableChanges.emit(Unit)
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            val build = active ?: return
            build.stopping = true
            mutableState.update { it.copy(stopping = true) }
            build.control.cancel()
            // Let even a queued worker enter its cleanup; the control cancels streams and cursors.
            build.job.start()
        }
    }
    private fun stopRemoved(rootId: String? = null) {
        synchronized(lock) {
            val build = active?.takeIf { rootId == null || it.root == rootId } ?: return
            build.removed.set(true)
            build.control.cancel()
            build.job.start()
        }
    }
    fun remove(rootId: String) {
        if (closed.get()) return
        stopRemoved(rootId)
        scope.launch {
            operations.withLock {
                try { store.remove(rootId); reload(); mutableChanges.emit(Unit) }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error(failure.message ?: "This index could not be removed.") }
            }
        }
    }
    fun deleteCache() {
        if (closed.get()) return
        stopRemoved()
        scope.launch {
            operations.withLock {
                try {
                    store.deleteCache()
                    mutableState.update { it.copy(roots = emptyList(), storageBytes = store.storageBytes(), error = null, loading = false) }
                    mutableChanges.emit(Unit)
                } catch (failure: Exception) { if (failure is CancellationException) throw failure; error(failure.message ?: "The index cache could not be deleted.") }
            }
        }
    }
    /** null means a mutation with unknown scope; an empty/provider-only list means no local changes. */
    fun noteChanged(paths: List<String>? = null) {
        if (closed.get()) return
        val local = paths?.filter { it.startsWith('/') && !isDocumentPath(it) }
        if (local != null && local.isEmpty()) return
        scope.launch {
            operations.withLock {
                runCatching { store.markChanged(local.orEmpty(), clock()) }.onSuccess {
                    if (synchronized(lock) { loaded }) reload()
                    mutableChanges.emit(Unit)
                }
            }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pause()
        lifetime.cancel()
        // Joining workers before close keeps their final status writes off a closed database.
        CoroutineScope(Dispatchers.IO).launch { lifetime.join(); operations.withLock { store.close() } }
    }
}
