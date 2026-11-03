package dev.findroid

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** The source choice never opts a folder into indexing or starts a refresh. */
enum class SearchSource(val title: String) { LIVE("Live folders"), LOCAL_INDEX("Local index") }

internal data class LocalIndexLimits(
    val roots: Int = 10,
    val entriesPerRoot: Int = 100_000,
    val storedEntries: Int = 250_000,
    val folders: Int = 25_000,
    val depth: Int = 128,
    val pathBytes: Int = 4_096,
    val valueBytes: Long = 32L * 1024 * 1024,
    val batch: Int = 128,
    val databaseBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(roots in 1..10 && entriesPerRoot in 1..100_000 && storedEntries in entriesPerRoot..250_000)
        require(folders in 1..25_000 && depth in 1..128 && pathBytes in 64..4_096)
        require(valueBytes in 1..32L * 1024 * 1024 && batch in 1..128 && databaseBytes in 65_536..64L * 1024 * 1024)
    }
}

internal enum class IndexRefreshState(val title: String) {
    IDLE("Not built"), BUILDING("Refreshing"), READY("Snapshot available"), PAUSED("Paused"),
    INTERRUPTED("Refresh interrupted"), FAILED("Refresh failed"), CAPPED("Refresh limit reached"),
}

internal data class LocalIndexRoot(
    val id: String,
    val path: String,
    val generation: String? = null,
    val entries: Int = 0,
    val scannedFrom: Long? = null,
    val refreshedAt: Long? = null,
    val state: IndexRefreshState = IndexRefreshState.IDLE,
    val attempt: String? = null,
    val attemptOwner: String? = null,
    val attemptStarted: Long? = null,
    val attemptedEntries: Int = 0,
    val changedAt: Long? = null,
    val reason: String? = null,
    val filesystemIdentity: String? = null,
) {
    init {
        require(indexId(id) && indexPath(path)) { "Invalid indexed folder identity." }
        require(generation == null || indexId(generation))
        require(entries in 0..100_000 && attemptedEntries in 0..100_000)
        require(listOfNotNull(scannedFrom, refreshedAt, attemptStarted, changedAt).all { it > 0 })
        require((generation == null) == (refreshedAt == null) && (generation == null) == (scannedFrom == null))
        require(refreshedAt == null || refreshedAt >= requireNotNull(scannedFrom))
        require(generation != null || entries == 0)
        require(state != IndexRefreshState.READY || generation != null)
        require(state != IndexRefreshState.IDLE || generation == null && attemptStarted == null && attemptedEntries == 0)
        require((state == IndexRefreshState.BUILDING) == (attempt != null))
        require((attempt == null) == (attemptOwner == null))
        require(attempt == null || indexId(attempt) && indexId(requireNotNull(attemptOwner)))
        require(attempt == null || attemptStarted != null)
        require(reason == null || reason.length <= 512)
        require(filesystemIdentity == null || filesystemIdentity.isNotEmpty() && filesystemIdentity.length <= 512)
    }
    val hasSnapshot get() = generation != null
    val retainedSnapshot get() = hasSnapshot && state != IndexRefreshState.READY
}

internal data class IndexSearchSnapshot(
    val folder: String,
    val scannedFrom: Long,
    val refreshedAt: Long,
    val entries: Int,
    val state: IndexRefreshState,
    val changedAt: Long?,
)
internal fun LocalIndexRoot.searchSnapshot() = IndexSearchSnapshot(path, requireNotNull(scannedFrom),
    requireNotNull(refreshedAt), entries, state, changedAt)

internal data class IndexBuildToken(val rootId: String, val path: String, val generation: String, val started: Long, val filesystemIdentity: String? = null)

internal data class IndexRecord(
    val path: String,
    val parent: String,
    val name: String,
    val folder: Boolean,
    val size: Long,
    val modified: Long,
) {
    val valueBytes get() = path.toByteArray(Charsets.UTF_8).size.toLong() +
        parent.toByteArray(Charsets.UTF_8).size + name.toByteArray(Charsets.UTF_8).size

    fun checked(root: String, limits: LocalIndexLimits = LocalIndexLimits()): IndexRecord = apply {
        require(indexPath(path, limits.pathBytes) && indexPath(parent, limits.pathBytes)) { "An indexed path exceeds the supported length." }
        require(path != root && indexWithin(path, root) && File(path).parent == parent && File(path).name == name)
        require(name.isNotEmpty() && !name.contains('/') && size >= 0 && modified >= 0)
    }

    fun entry() = Entry(File(path), name, folder, size, modified, parentPath = parent,
        writable = false, canRename = false, canDelete = false)
}

internal data class IndexBuildProgress(val entries: Int = 0, val folders: Int = 0, val valueBytes: Long = 0)
internal class IndexBuildLimit(message: String) : Exception(message)
internal class IndexSourceChanged(message: String) : Exception(message)

internal fun indexId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun newIndexId(): String = UUID.randomUUID().toString()
internal fun indexPath(value: String, limit: Int = 4_096): Boolean =
    value.startsWith('/') && !value.contains('\u0000') && value.toByteArray(Charsets.UTF_8).size <= limit &&
        File(value).toPath().normalize().toString() == value && (value == "/" || !value.endsWith('/'))
internal fun indexWithin(path: String, root: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")

/** A stable directory identity detects deletion/recreation at the same cached path. */
internal fun localIndexFileIdentity(path: String): String {
    currentIndexedFile(path, path)
    return requireNotNull(Files.readAttributes(File(path).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS).fileKey()) { "This storage cannot report a stable local folder identity. Use Live folders." }.toString()
}

/** No provider references, symlinks, or paths which escape the registered canonical root. */
internal fun currentIndexedFile(path: String, root: String): File {
    require(indexPath(path) && indexPath(root) && indexWithin(path, root)) { "This cached path is outside its indexed folder." }
    val folder = File(root)
    check(folder.canonicalPath == root && folder.isDirectory && folder.canRead()) { "The indexed folder is unavailable or has changed location." }
    val file = File(path)
    check(!Files.isSymbolicLink(file.toPath()) && file.canonicalPath == path &&
        file.exists() && Files.isReadable(file.toPath()) &&
        (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS))) {
        "This indexed item was removed, moved, or replaced. Refresh the index."
    }
    return file
}

/** Flat index scans must honor the ancestors which live traversal would have skipped. */
internal fun indexedScopeAdmits(record: IndexRecord, anchor: String, rules: SearchRules, hidden: Boolean): Boolean {
    if (record.path == anchor || !indexWithin(record.path, anchor)) return false
    if (!rules.includeSubfolders && record.parent != anchor) return false
    if ((!hidden && record.name.startsWith('.')) || isWorkingFileName(record.name)) return false
    var parent = record.parent
    var depth = 0
    while (parent != anchor) {
        if (++depth > 128 || !indexWithin(parent, anchor)) return false
        val name = File(parent).name
        if ((!hidden && name.startsWith('.')) || name in rules.excludedFolderNames || isWorkingFileName(name)) return false
        parent = File(parent).parent ?: return false
    }
    return !record.folder || record.name !in rules.excludedFolderNames
}

internal interface LocalIndexLease : AutoCloseable {
    val root: LocalIndexRoot
    suspend fun visit(anchor: String, recursive: Boolean, control: ListingControl, each: suspend (IndexRecord) -> Boolean)
    fun check()
}

/** All methods run off the main thread. Cache rows never contain tags, content, or document bytes. */
internal interface LocalIndexStore : AutoCloseable {
    fun roots(): List<LocalIndexRoot>
    fun add(path: String): LocalIndexRoot
    fun begin(rootId: String): IndexBuildToken
    fun append(token: IndexBuildToken, records: List<IndexRecord>)
    fun complete(token: IndexBuildToken, finished: Long, entries: Int, checkActive: () -> Unit = {})
    fun abandon(token: IndexBuildToken, state: IndexRefreshState, entries: Int, reason: String?)
    fun acquire(anchor: String): LocalIndexLease?
    fun markChanged(paths: List<String>, now: Long)
    fun remove(rootId: String)
    fun deleteCache()
    fun storageBytes(): Long
}
