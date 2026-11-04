package dev.findroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ConnectedLocation(val uri: String, val grant: String, val name: String)

fun isDocumentPath(path: String) = path.startsWith("content://")

/**
 * Storage Access Framework gateway. Cloud/network authentication stays in the installed provider.
 */
private val documentParentCacheLock = Any()
private val documentPreviewLocks = Array(16) { java.util.concurrent.locks.ReentrantLock() }

private inline fun <T> withDocumentPreviewLock(
    path: String,
    control: ListingControl?,
    block: () -> T,
): T {
    val lock = documentPreviewLocks[(path.hashCode() and Int.MAX_VALUE) % documentPreviewLocks.size]
    control?.check()
    if (control == null) lock.lock()
    else while (!lock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)) control.check()
    try {
        control?.check()
        return block()
    } finally {
        lock.unlock()
    }
}

class PreviewSizeLimitException(message: String) : IllegalStateException(message)

private fun <T> TransferControl.documentCall(path: String, work: () -> T): T =
    storage(if (isDocumentPath(path)) TransferIo.PROVIDER else TransferIo.STORAGE, work)

class DocumentAccess(
    private val context: Context,
    private val engine: FileEngine = FileEngine(context.filesDir),
    private val spaceProbe: ((String) -> Long?)? = null,
) {
    private val resolver = context.contentResolver
    private val partialStorage = AndroidPartialCopyStorage()
    private val partialRecovery by lazy {
        val root = File(context.filesDir, ".partial-copies").absolutePath
        if (partialStorage.stat(root) == null) partialStorage.createDirectory(root)
        check(partialStorage.stat(root)?.directory == true) {
            "The private saved-copy folder needs review."
        }
        PartialCopyRecovery(partialStorage, root)
    }

    internal fun partialCopySession(approval: PartialCopyReview?, changed: (PartialCopyRecord?, String?, Long) -> Unit) =
        PartialCopySession(partialRecovery, partialStorage, approval, changed)

    internal fun reviewPartialCopy(record: PartialCopyRecord, control: TransferControl) = partialRecovery.review(record, control)
    internal fun reviewPartialCopyCleanup(record: PartialCopyRecord) = partialRecovery.reviewCleanup(record)
    internal fun discardPartialCopy(approval: PartialCopyCleanupReview) = partialRecovery.discardReviewed(approval)

    private val prefs = context.getSharedPreferences("locations", 0)
    private val parentFile = File(context.filesDir, "document-parents.properties")
    private val parents =
        Properties().apply {
            synchronized(documentParentCacheLock) {
                runCatching { android.util.AtomicFile(parentFile).openRead().use(::load) }
            }
        }
    private val pendingParents = mutableMapOf<String, String>()
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val notifications = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    internal fun cachedTitle(path: String) =
        titles[path] ?: Uri.parse(path).lastPathSegment ?: "Document"

    fun notificationUri(path: String) = notifications[path] ?: Uri.parse(path)

    var lastCopyMappings: List<Pair<String, String>> = emptyList()
        private set

    var lastRestoreMappings: List<Pair<String, String>> = emptyList()
        private set

    @Volatile
    var locations: List<ConnectedLocation> = runCatching {
        val array = JSONArray(prefs.getString("roots", "[]"))
        (0 until array.length()).map {
            val item = array.getJSONObject(it)
            ConnectedLocation(
                item.getString("uri"),
                item.getString("grant"),
                item.getString("name"),
            )
        }
    }
        .getOrDefault(emptyList())
        private set

    init {
        locations.forEach { titles[it.uri] = it.name }
    }

    @Volatile
    private var savedGrants: Map<Pair<String, String>, Pair<Boolean, Boolean>> = emptyMap()

    internal fun refreshGrantState() {
        savedGrants =
            runCatching {
                    resolver.persistedUriPermissions
                        .mapNotNull { permission ->
                            documentTreeKey(permission.uri.toString())?.let {
                                it to (permission.isReadPermission to permission.isWritePermission)
                            }
                        }
                        .toMap()
                }
                .getOrDefault(emptyMap())
    }

    internal fun grantState(path: String) = documentTreeKey(path)?.let { savedGrants[it] }

    internal fun sameTree(first: String, second: String): Boolean =
        documentTreeKey(first)?.let { it == documentTreeKey(second) } ?: false

    internal fun reportedAvailableBytes(path: String, control: ListingControl): Long? =
        runCatching {
            val document = Uri.parse(path)
            val id = DocumentsContract.getDocumentId(document)
            control.withSignal { signal ->
                resolver
                    .query(
                        DocumentsContract.buildRootsUri(document.authority!!),
                        arrayOf(
                            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                            DocumentsContract.Root.COLUMN_FLAGS,
                        ),
                        null,
                        null,
                        null,
                        signal,
                    )
                    ?.use { cursor ->
                        var result: Long? = null
                        var rows = 0
                        while (cursor.moveToNext()) {
                            if (++rows > 64) return@use null
                            control.check()
                            val rootId =
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow(
                                        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                                    ),
                                ) ?: continue
                            val availableIndex =
                                cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                            if (availableIndex < 0 || cursor.isNull(availableIndex)) continue
                            val available = cursor.getLong(availableIndex)
                            if (available < 0) continue
                            val flagsIndex =
                                cursor.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)
                            val supportsChild =
                                flagsIndex >= 0 &&
                                    cursor.getInt(flagsIndex) and
                                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD != 0
                            val matches =
                                rootId == id ||
                                    supportsChild &&
                                        runCatching {
                                                DocumentsContract.isChildDocument(
                                                    resolver,
                                                    DocumentsContract.buildDocumentUri(
                                                        document.authority!!,
                                                        rootId,
                                                    ),
                                                    document,
                                                )
                                            }
                                            .getOrDefault(false)
                            if (matches) {
                                if (result != null) return@use null
                                result = available
                            }
                        }
                        result
                    }
            }
        }
        .getOrNull()
        .also { control.check() }

    fun connect(tree: Uri, persist: Boolean = true): ConnectedLocation {
        if (persist) {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching {
                resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        val uri =
            DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
                .toString()
        refreshGrantState()
        val entry = read(uri)
        require(entry.folder) { "Choose a folder rather than a single file." }
        val root = ConnectedLocation(uri, tree.toString(), entry.name)
        locations = locations.filterNot { sameTree(it.grant, root.grant) } + root
        saveLocations()
        return root
    }

    fun disconnect(uri: String) {
        val root = locations.firstOrNull { it.uri == uri || it.grant == uri } ?: return
        locations = locations.filterNot { it.uri == root.uri }
        saveLocations()
        listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .forEach { flag ->
                runCatching {
                    resolver.releasePersistableUriPermission(Uri.parse(root.grant), flag)
                }
            }
        refreshGrantState()
    }

    private fun saveLocations() {
        prefs
            .edit()
            .putString(
                "roots",
                JSONArray(
                        locations.map {
                            JSONObject()
                                .put("uri", it.uri)
                                .put("grant", it.grant)
                                .put("name", it.name)
                        },
                    )
                    .toString(),
            )
            .apply()
    }

    fun allowed(path: String): Boolean = runCatching {
        val uri = Uri.parse(path)
        val tree = DocumentsContract.getTreeDocumentId(uri)
        locations.any {
            val grant = Uri.parse(it.grant)
            grant.authority == uri.authority && DocumentsContract.getTreeDocumentId(grant) == tree
        }
    }
        .getOrDefault(false)

    /** Tree grants may use different URIs for the same opaque document ID. */
    internal fun searchIdentity(path: String): String {
        if (!isDocumentPath(path)) return organizationKey(path)
        return runCatching {
            val uri = Uri.parse(path)
            val id = runCatching { DocumentsContract.getDocumentId(uri) }
                .getOrElse { DocumentsContract.getTreeDocumentId(uri) }
            // Length framing avoids collisions even when an ID contains punctuation.
            "document:${uri.authority?.length}:${uri.authority}:${id.length}:$id"
        }.getOrDefault(path)
    }

    internal fun searchAncestry(path: String, ancestor: String, control: ListingControl): SearchAncestry {
        control.check()
        if (!isDocumentPath(path) || !isDocumentPath(ancestor)) return localSearchAncestry(path, ancestor)
        val child = Uri.parse(path)
        val parent = Uri.parse(ancestor)
        if (child.authority != parent.authority) return SearchAncestry.OUTSIDE
        val target = searchIdentity(ancestor)
        var cursor: String? = path
        val visited = mutableSetOf<String>()
        while (cursor != null && visited.size < 128 && visited.add(searchIdentity(cursor))) {
            control.check()
            if (searchIdentity(cursor) == target) return SearchAncestry.INSIDE
            cursor = this.parent(cursor)
        }
        return try {
            val inside = DocumentsContract.isChildDocument(resolver, parent, child)
            control.check()
            if (inside) SearchAncestry.INSIDE else SearchAncestry.OUTSIDE
        } catch (error: Exception) {
            control.check()
            if (error is kotlinx.coroutines.CancellationException) throw error
            SearchAncestry.UNKNOWN
        }
    }

    internal fun knownTitle(path: String): String? =
        titles[path] ?: locations.firstOrNull { it.uri == path }?.name

    fun title(path: String) = knownTitle(path) ?: "Connected folder"

    fun parent(path: String): String? =
        if (isDocumentPath(path)) parents.getProperty(path) else File(path).parent

    private fun recordParent(path: String, parent: String) =
        synchronized(documentParentCacheLock) {
            if (parents.getProperty(path) != parent) {
                parents.setProperty(path, parent)
                pendingParents[path] = parent
            }
        }

    private fun saveParents() =
        synchronized(documentParentCacheLock) {
            if (pendingParents.isEmpty()) return@synchronized
            val latest = Properties()
            val atomic = android.util.AtomicFile(parentFile)
            var stream: java.io.FileOutputStream? = null
            try {
                runCatching { atomic.openRead().use(latest::load) }
                pendingParents.forEach { (path, parent) -> latest.setProperty(path, parent) }
                stream = atomic.startWrite()
                latest.store(stream, "Known document parents")
                atomic.finishWrite(stream)
                parents.putAll(latest)
                pendingParents.clear()
            } catch (_: Exception) {
                if (stream != null) atomic.failWrite(stream)
                // Navigation can still use the in-memory cache when storage is full.
            }
        }

    private val projection =
        arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

    fun read(path: String): Entry {
        if (!isDocumentPath(path)) return Entry(File(path))
        val uri = Uri.parse(path)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "The document is no longer available." }
            fromCursor(uri, cursor, parents.getProperty(path))
        } ?: error("Couldn't read this document provider.")
    }

    internal fun read(path: String, control: ListingControl): Entry = control.withSignal { signal ->
        control.check()
        if (!isDocumentPath(path)) {
            val file = File(path)
            check(file.exists()) { "This item is no longer available." }
            Entry(file)
        } else {
            val uri = Uri.parse(path)
            resolver.query(uri, projection, null, null, null, signal)?.use { cursor ->
                control.check()
                check(cursor.moveToFirst()) { "The document is no longer available." }
                control.check()
                fromCursor(uri, cursor, parents.getProperty(path))
            } ?: error("Couldn't read this document provider.")
        }
    }

    internal suspend fun visitChildren(
        path: String,
        control: ListingControl,
        visit: suspend (Entry) -> Boolean,
    ): DirectoryReply {
        control.check()
        if (!isDocumentPath(path)) {
            java.nio.file.Files.newDirectoryStream(File(path).toPath()).use { children ->
                for (child in children) {
                    control.check()
                    if (!visit(Entry(child.toFile()))) break
                }
            }
            return DirectoryReply()
        }
        val uri = Uri.parse(path)
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                uri,
                DocumentsContract.getDocumentId(uri),
            )
        val cursor =
            control.withSignal { signal ->
                resolver.query(children, projection, null, null, null, signal)
            } ?: error("Couldn't list this folder. The provider may be offline.")
        try {
            cursor.use {
                cursor.notificationUri?.let { notifications[path] = it }
                while (true) {
                    control.check()
                    if (!cursor.moveToNext()) break
                    control.check()
                    if (!visit(fromCursor(uri, cursor, path))) break
                }
                val extras = cursor.extras
                return DirectoryReply(
                    extras.getBoolean(DocumentsContract.EXTRA_LOADING, false),
                    extras.getString(DocumentsContract.EXTRA_ERROR),
                    extras.getString(DocumentsContract.EXTRA_INFO),
                )
            }
        } finally {
            saveParents()
        }
    }

    internal fun input(path: String, control: ListingControl): InputStream =
        control.stream { signal ->
            if (!isDocumentPath(path)) File(path).inputStream()
            else
                DocumentPreviewInput(
                    resolver.openAssetFileDescriptor(Uri.parse(path), "r", signal)
                        ?: error("Couldn't open this document"),
                    control::check,
                )
        }

    private fun fromCursor(tree: Uri, cursor: android.database.Cursor, parent: String?): Entry {
        fun string(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
        fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
        val id = string(Document.COLUMN_DOCUMENT_ID)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val name = string(Document.COLUMN_DISPLAY_NAME)
        val mime = string(Document.COLUMN_MIME_TYPE)
        val flagsIndex = cursor.getColumnIndex(Document.COLUMN_FLAGS)
        val flags = if (flagsIndex < 0) null else runCatching {
            if (cursor.getType(flagsIndex) != android.database.Cursor.FIELD_TYPE_INTEGER) null
            else cursor.getLong(flagsIndex).takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
        }.getOrNull()
        val bits = flags ?: 0
        val grants = grantState(uri.toString())
        val mayWrite = grants?.second != false
        val folder = mime == Document.MIME_TYPE_DIR
        val file = cacheFile(uri.toString(), name)
        titles[uri.toString()] = name
        if (parent != null) recordParent(uri.toString(), parent)
        return Entry(
            file,
            name,
            folder,
            number(Document.COLUMN_SIZE),
            number(Document.COLUMN_LAST_MODIFIED),
            uri,
            parent,
            mayWrite &&
                (if (folder) bits and Document.FLAG_DIR_SUPPORTS_CREATE != 0
                else bits and Document.FLAG_SUPPORTS_WRITE != 0),
            mayWrite && (bits and Document.FLAG_SUPPORTS_RENAME != 0),
            mayWrite && (bits and Document.FLAG_SUPPORTS_DELETE != 0),
            mime,
            sizeKnown =
                !cursor.isNull(cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)) &&
                    number(Document.COLUMN_SIZE) >= 0,
            readable = null,
            providerAccess = ProviderAccessEvidence(uri.toString(), flags, grants?.first, grants?.second),
        )
    }

    fun list(path: String): List<Entry> {
        if (!isDocumentPath(path))
            return (File(path).listFiles() ?: error("Couldn't read this folder.")).map(::Entry)
        val uri = Uri.parse(path)
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                uri,
                DocumentsContract.getDocumentId(uri),
            )
        val result =
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                cursor.notificationUri?.let { notifications[path] = it }
                val entries = buildList<Entry> {
                    while (cursor.moveToNext()) add(fromCursor(uri, cursor, path))
                }
                val extras = cursor.extras
                check(!extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    "The provider is still loading this folder. Wait for it to finish and retry."
                }
                val error = extras.getString(DocumentsContract.EXTRA_ERROR)
                check(error.isNullOrBlank()) { "The provider couldn't fully list this folder: $error" }
                entries
            } ?: error("Couldn't list this folder. The provider may be offline.")
        saveParents()
        return result
    }

    private fun cacheFile(path: String, name: String): File {
        val key =
            MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        return File(context.cacheDir, "documents/$key/${name.replace('/','_')}")
    }

    fun materialize(entry: Entry, limit: Long = 128L * 1024 * 1024): File =
        materialize(entry, limit, null)

    internal fun materialize(
        entry: Entry,
        control: ListingControl,
        limit: Long = 128L * 1024 * 1024,
    ): File = materialize(entry, limit, control)

    private fun materialize(entry: Entry, limit: Long, control: ListingControl?): File =
        withDocumentPreviewLock(entry.path, control) {
            CacheLeases.pin(entry.file).use {
                control?.check()
                if (entry.documentUri == null) return@withDocumentPreviewLock entry.file
                check(!entry.folder)
                if (entry.size > limit)
                    throw PreviewSizeLimitException(
                        "This file is too large for the preview cache (128 MB limit).",
                    )
                val file = entry.file
                if (
                    file.isFile &&
                        file.length() == entry.size &&
                        file.lastModified() >= entry.modified
                ) {
                    file.parentFile?.setLastModified(System.currentTimeMillis())
                    return@withDocumentPreviewLock file
                }
                file.parentFile!!.mkdirs()
                val staging = File(file.parentFile, "preview.tmp")
                try {
                    (control?.let { input(entry.path, it) } ?: input(entry.path)).use { source ->
                        staging.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            while (true) {
                                control?.check()
                                val n = source.read(buffer)
                                if (n < 0) break
                                bytes += n
                                if (bytes > limit)
                                    throw PreviewSizeLimitException(
                                        "Preview exceeds the 128 MB cache limit.",
                                    )
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    control?.check()
                    Files.move(
                        staging.toPath(),
                        file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (error: Exception) {
                    staging.delete()
                    throw error
                }
                file.parentFile?.setLastModified(System.currentTimeMillis())
                file
            }
        }

    fun input(path: String): InputStream =
        if (isDocumentPath(path))
            resolver.openInputStream(Uri.parse(path))
                ?: error("The provider couldn't open this file.")
        else File(path).inputStream()

    internal fun output(path: String): OutputStream =
        if (isDocumentPath(path))
            resolver.openOutputStream(Uri.parse(path), "wt")
                ?: error("The provider couldn't write this file.")
        else File(path).outputStream()

    fun create(
        parent: String,
        name: String,
        folder: Boolean,
        mime: String = "application/octet-stream",
    ): String {
        validateFilename(name)
        if (!isDocumentPath(parent)) {
            return if (folder) engine.createFolder(File(parent), name).absolutePath
            else
                File(parent, name)
                    .also { check(it.createNewFile()) { "${it.name} already exists." } }
                    .absolutePath
        }
        check(read(parent).writable) { "This provider folder is read only." }
        check(list(parent).none { it.name == name }) { "$name already exists." }
        val uri =
            DocumentsContract.createDocument(
                resolver,
                Uri.parse(parent),
                if (folder) Document.MIME_TYPE_DIR else mime,
                name,
            ) ?: error("The provider couldn't create $name.")
        recordParent(uri.toString(), parent)
        titles[uri.toString()] = name
        saveParents()
        return uri.toString()
    }

    fun rename(path: String, name: String): String {
        validateFilename(name)
        if (!isDocumentPath(path)) return engine.rename(File(path), name).absolutePath
        val before = read(path)
        if (before.name == name) return path
        check(before.canRename) { "This provider doesn't allow renaming this item." }
        val parent = parent(path) ?: error("The parent folder is unavailable.")
        check(list(parent).none { it.name == name && it.path != path }) { "$name already exists." }
        val result =
            DocumentsContract.renameDocument(resolver, Uri.parse(path), name)
                ?: error("The provider couldn't rename this item.")
        recordParent(result.toString(), parent)
        titles[result.toString()] = name
        saveParents()
        return result.toString()
    }

    fun delete(path: String) {
        if (!isDocumentPath(path)) {
            check(File(path).deleteTreeWithoutLinks()) {
                "Couldn't completely remove the original after copying. The destination copy was kept."
            }
            return
        }
        check(locations.none { it.uri == path }) { "A connected root can't be deleted." }
        check(read(path).canDelete) { "The provider doesn't permit deleting this item." }
        check(DocumentsContract.deleteDocument(resolver, Uri.parse(path))) {
            "Couldn't completely remove the original. The destination copy was kept."
        }
    }

    internal fun uniqueName(parent: String, name: String): String {
        val names = list(parent).map { it.name }.toSet()
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var result = name
        var number = 2
        while (result in names) {
            result = "${name.take(dot)} ${number++}${name.drop(dot)}"
        }
        return result
    }

    private fun checkDestination(source: String, parent: String) {
        require(source != parent) { "A folder can't be placed inside itself." }
        var cursor: String? = parent
        val seen = mutableSetOf<String>()
        while (cursor != null && seen.add(cursor)) {
            require(cursor != source) { "A folder can't be placed inside itself." }
            cursor = this.parent(cursor)
        }
        if (isDocumentPath(source) && isDocumentPath(parent)) {
            val a = Uri.parse(source)
            val b = Uri.parse(parent)
            if (a.authority == b.authority)
                require(
                    !runCatching { DocumentsContract.isChildDocument(resolver, a, b) }
                        .getOrDefault(false),
                ) {
                    "A folder can't be placed inside itself."
                }
        }
    }

    fun estimate(paths: List<String>, control: TransferControl): TransferEstimate {
        fun node(entry: Entry) =
            EstimateNode(
                entry.path,
                entry.folder,
                entry.size.takeIf { entry.sizeKnown },
                entry.documentUri == null && Files.isSymbolicLink(entry.file.toPath()),
            )
        return estimateTransfer(
            paths,
            { path ->
                if (!isDocumentPath(path))
                    check(File(path).exists()) { "This item is unavailable." }
                node(control.documentCall(path) { read(path) })
            },
            { path -> control.documentCall(path) { list(path) }.asSequence().map(::node).asIterable() },
            control,
        )
    }

    internal fun inspectTransferSpace(request: TransferSpaceRequest, control: TransferControl): TransferSpaceInspection {
        val listing = ListingControl()
        return control.guard(java.io.Closeable { listing.cancel() }) {
            fun metadata(path: String) = control.documentCall(path) { read(path, listing) }
            fun children(path: String): List<Entry> = control.documentCall(path) {
                val items = mutableListOf<Entry>()
                val reply = kotlinx.coroutines.runBlocking {
                    visitChildren(path, listing) { entry ->
                        control.check()
                        check(items.size < 100_000) { "This folder exceeds the space review inventory limit." }
                        items.add(entry)
                        true
                    }
                }
                check(!reply.loading && reply.error == null) { reply.error ?: "This provider is still loading its folder." }
                items
            }
            fun node(entry: Entry) = EstimateNode(entry.path, entry.folder, entry.size.takeIf { entry.sizeKnown },
                entry.documentUri == null && Files.isSymbolicLink(entry.file.toPath()))
            inspectTransferSpace(request, read = ::metadata, list = ::children,
                estimate = { paths -> estimateTransfer(paths, { node(metadata(it)) }, { children(it).map(::node) }, control) },
                location = { path ->
                    val available = control.documentCall(path) { availableSpace(path, listing) }
                    // Emulated shared storage can have a different st_dev while consuming the
                    // same allocation pool as app-private data. Only a storage-volume UUID is proof.
                    val volume = if (isDocumentPath(path)) null else runCatching {
                        context.getSystemService(android.os.storage.StorageManager::class.java)
                            .getUuidForPath(File(path)).toString()
                    }.getOrNull()
                    TransferSpaceLocation(path, available, volume)
                }, recoveryPath = context.filesDir.absolutePath, control = control)
        }
    }

    /** Match an actual provider root; never apply one volume's capacity to another volume. */
    fun availableSpace(path: String): Long? = availableSpace(path, null)

    private fun availableSpace(path: String, control: ListingControl?): Long? {
        control?.check()
        if (spaceProbe != null) return spaceProbe.invoke(path)?.takeIf { it >= 0 }
        if (!isDocumentPath(path)) return runCatching { File(path).usableSpace }.getOrNull()
        return runCatching {
            val uri = Uri.parse(path)
            val authority = uri.authority ?: return@runCatching null
            val columns =
                arrayOf(
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                )
            val roots = DocumentsContract.buildRootsUri(authority)
            val cursor = if (control == null) resolver.query(roots, columns, null, null, null)
                else control.withSignal { signal -> resolver.query(roots, columns, null, null, null, signal) }
            cursor?.use { cursor ->
                    while (cursor.moveToNext()) {
                        control?.check()
                        val id = cursor.getString(0) ?: continue
                        val root = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                        val matches =
                            id == DocumentsContract.getDocumentId(uri) ||
                                runCatching {
                                        DocumentsContract.isChildDocument(resolver, root, uri)
                                    }
                                    .getOrDefault(false)
                        if (matches && !cursor.isNull(1))
                            return@use cursor.getLong(1).takeIf { it >= 0 }
                    }
                    null
                }
        }
            .getOrNull()
    }

    fun validateReplacement(source: String, existing: String) {
        if (!isDocumentPath(source) && !isDocumentPath(existing)) {
            require(
                !File(source)
                    .canonicalFile
                    .toPath()
                    .startsWith(File(existing).canonicalFile.toPath()),
            ) {
                "A folder containing the source cannot be replaced."
            }
        } else checkDestination(existing, source)
    }

    fun validateTransfer(
        source: String,
        parent: String,
        move: Boolean,
        allowRename: Boolean = false,
    ) {
        if (!isDocumentPath(source) && !isDocumentPath(parent))
            engine.checkDestination(File(source), File(parent))
        else checkDestination(source, parent)
        val before = read(source)
        val destination = read(parent)
        check(destination.folder && destination.writable) {
            "The destination folder is not writable."
        }
        if (move) {
            check(before.canDelete) { "The source is read only. Copy it instead." }
            require(before.parentPath != parent || allowRename) {
                "This item is already in that folder."
            }
        }
    }

    fun copy(
        source: String,
        parent: String,
        move: Boolean,
        control: TransferControl = TransferControl(),
        requestedName: String? = null,
        phaseTitle: String = if (move) "Moving" else "Copying",
        verification: CopyVerificationSession? = null,
        partial: PartialCopySession? = null,
    ): String {
        require(!move || verification == null) { "Optional readback verification is available for copies." }
        lastCopyMappings = emptyList()
        control.phase("Checking files", stage = TransferStage.PREPARING)
        control.documentCall(if (isDocumentPath(source)) source else parent) {
            validateTransfer(source, parent, move, requestedName != null)
        }
        if (!isDocumentPath(source) && !isDocumentPath(parent)) {
            if (!move && partial != null) {
                val target = requestedName?.let { File(parent, validateFilename(it)) } ?: engine.uniqueFile(File(parent), File(source).name)
                requireTransferSpace(partial.remainingBytes(File(source)), availableSpace(parent))
                partial.copy(File(source), target, control, verification)?.let { return it }
            }
            return engine.copy(File(source), File(parent), move, control, requestedName, phaseTitle, verification).absolutePath
        }
        control.documentCall(if (isDocumentPath(source)) source else parent) { checkDestination(source, parent) }
        val entry = control.documentCall(source) { read(source) }
        if (move) {
            check(entry.canDelete) { "The source is read only. Copy it instead." }
            require(this.parent(source) != parent || requestedName != null) {
                "This item is already in that folder."
            }
        }
        val name =
            requestedName?.also {
                validateFilename(it)
                check(control.documentCall(parent) { list(parent) }.none { child -> child.name == it }) { "$it already exists." }
            } ?: control.documentCall(parent) { uniqueName(parent, entry.name) }
        control.phase("Checking files", stage = TransferStage.PREPARING)
        val estimate = estimate(listOf(source), control)
        requireTransferSpace(estimate.knownBytes, control.documentCall(parent) { availableSpace(parent) })
        control.phase(phaseTitle, estimate.totalBytes, TransferStage.COPYING)
        val staging = control.documentCall(parent) { create(parent, ".findroid-${UUID.randomUUID()}", entry.folder, entry.mime) }
        val mappings = mutableListOf<Pair<String, String>>()
        val traversal = TransferTraversal()
        fun copyContents(from: Entry, to: String, depth: Int = 0) {
            control.item(from.name)
            traversal.visit(from.path, depth)
            mappings += from.path to to
            if (from.documentUri == null)
                check(!Files.isSymbolicLink(from.file.toPath())) { "Symbolic links aren't copied." }
            if (from.folder) {
                val children = control.documentCall(from.path) { list(from.path) }
                children.forEach { child ->
                    copyContents(child, control.documentCall(to) { create(to, child.name, child.folder, child.mime) }, depth + 1)
                }
                check(control.documentCall(from.path) { list(from.path) }.map { it.path }.toSet() == children.map { it.path }.toSet()) {
                    "The source folder changed while copying. The source was kept."
                }
            } else {
                val digest = verification?.begin(from.path)
                if (verification != null) control.phase("Copying", from.size.takeIf { from.sizeKnown }, TransferStage.COPYING)
                val copied = control.documentCall(from.path) { input(from.path) }.use { input ->
                    control.documentCall(to) { output(to) }.use { output ->
                        control.copy(if (digest == null) input else java.security.DigestInputStream(input, digest), output,
                            from.size.takeIf { from.sizeKnown }, sourceIsProvider = from.documentUri != null,
                            destinationIsProvider = isDocumentPath(to))
                    }
                }
                fun unchanged(): Boolean {
                    val after = control.documentCall(from.path) { read(from.path) }
                    return (!from.sizeKnown || copied == from.size) &&
                        (!from.sizeKnown || after.sizeKnown && after.size == from.size) &&
                        after.folder == from.folder && after.modified == from.modified
                }
                check(unchanged()) {
                    "The source changed or the provider returned an incomplete file. The source was kept."
                }
                if (verification != null) {
                    verification.verify(from.path, copied, digest!!.digest(), control, isDocumentPath(to)) {
                        control.documentCall(to) { input(to) }
                    }
                    check(unchanged()) { "The source changed while verifying the copy. The source was kept." }
                }
            }
        }
        val completed =
            try {
                copyContents(entry, staging)
                control.check()
                control.phase("Publishing copy", stage = TransferStage.FINISHING)
                control.documentCall(staging) { rename(staging, name) }
            } catch (error: Exception) {
                runCatching { delete(staging) }
                throw error
            }
        lastCopyMappings = mappings.map {
            it.first to remapPath(it.second, listOf(staging to completed))
        }
        verification?.published(lastCopyMappings.toMap())
        if (move)
            try {
                control.phase("Removing original after copy", stage = TransferStage.FINISHING)
                control.documentCall(source) { delete(source) }
            } catch (error: Exception) {
                throw TransferPublicationException(
                    completed,
                    "The destination copy was kept, but removing the source failed: ${error.message}",
                    error,
                )
            }
        return completed
    }

    internal fun sharedMetadata(uri: Uri, control: ListingControl): Pair<String, Long?> =
        control.withSignal { signal ->
            val columns = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
            resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use "Imported file" to null
                val nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val name = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) cursor.getString(nameColumn) else "Imported file"
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn).takeIf { it >= 0 } else null
                name to size
            } ?: ("Imported file" to null)
        }

    fun importUri(uri: Uri, parent: String, control: TransferControl): String {
        val readControl = ListingControl()
        return control.guard(java.io.Closeable { readControl.cancel() }) {
            control.phase("Checking shared file", stage = TransferStage.PREPARING)
            val (name, expected) = control.storage(TransferIo.PROVIDER) { sharedMetadata(uri, readControl) }
            validateFilename(name)
            val desired = control.documentCall(parent) { uniqueName(parent, name) }
            control.phase("Copying shared file", expected, TransferStage.COPYING)
            val staging = control.documentCall(parent) { create(parent, ".findroid-${UUID.randomUUID()}", false,
                resolver.getType(uri) ?: "application/octet-stream") }
            try {
                control.item(name)
                val copied = control.storage(TransferIo.PROVIDER) { input(uri.toString(), readControl) }.use { input ->
                    control.documentCall(staging) { output(staging) }.use { output ->
                        control.copy(input, output, expected, sourceIsProvider = true,
                            destinationIsProvider = isDocumentPath(staging))
                    }
                }
                check(expected == null || copied == expected) {
                    "The provider returned an incomplete file or the source changed. Share it again."
                }
                control.check()
                control.phase("Publishing shared file", stage = TransferStage.FINISHING)
                control.documentCall(staging) { rename(staging, desired) }
            } catch (error: Exception) {
                runCatching { delete(staging) }
                throw error
            }
        }
    }

    fun trash(path: String, control: TransferControl = TransferControl()): File {
        if (!isDocumentPath(path)) return engine.trash(File(path), control)
        val entry = read(path)
        check(entry.canDelete) { "This provider item is read only." }
        val parent = parent(path) ?: error("The original parent is unavailable.")
        val staging =
            File(context.filesDir, "provider-staging/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val copy =
                copy(
                    path,
                    staging.absolutePath,
                    false,
                    control,
                    phaseTitle = "Saving recovery copy",
                )
            val trashed = engine.trash(File(copy), control)
            engine.rememberOriginal(trashed, path, parent)
            engine.rememberMappings(
                trashed,
                lastCopyMappings.map {
                    it.first to remapPath(it.second, listOf(copy to trashed.absolutePath))
                },
            )
            try {
                control.check()
                delete(path)
            } catch (error: Exception) {
                throw TransferPublicationException(
                    trashed.path,
                    "A recovery copy was kept in Trash, but removing the source failed: ${error.message}",
                    error,
                )
            }
            return trashed
        } finally {
            staging.deleteTreeWithoutLinks()
        }
    }

    fun restore(item: File, control: TransferControl = TransferControl()): String {
        lastRestoreMappings = emptyList()
        val original = engine.originalPath(item) ?: error("The original location is unavailable.")
        if (!isDocumentPath(original)) return engine.restore(item, control).absolutePath
        val parent =
            engine.originalParent(item) ?: error("The original provider folder is unavailable.")
        val originals = engine.originalMappings(item)
        val result = copy(item.absolutePath, parent, false, control)
        val newPaths = lastCopyMappings.toMap()
        lastRestoreMappings =
            originals
                .mapNotNull { (source, local) -> newPaths[local]?.let { source to it } }
                .ifEmpty { listOf(original to result) }
        engine.deleteForever(item)
        return result
    }

    fun digest(path: String, control: TransferControl? = null): String {
        val reads = if (control != null) ListingControl() else null
        fun calculate(): String {
            fun content(entry: Entry): Pair<ByteArray, Long> {
                control?.check()
                require(entry.documentUri != null || !Files.isSymbolicLink(entry.file.toPath())) {
                    "Symbolic links are not verified implicitly."
                }
                val hash = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                (if (control == null) input(entry.path)
                 else control.documentCall(entry.path) { input(entry.path, reads!!) }).use { source ->
                    fun read() {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            control?.check()
                            val n = if (control == null) source.read(buffer)
                                else control.storage(if (entry.documentUri != null) TransferIo.PROVIDER else TransferIo.SOURCE) {
                                    source.read(buffer)
                                }
                            if (n < 0) break
                            bytes += n
                            hash.update(buffer, 0, n)
                            control?.verifiedBytes(n)
                        }
                    }
                    if (control != null) control.guard(source, ::read) else read()
                }
                return hash.digest() to bytes
            }
            val root = if (control == null) read(path)
                else control.documentCall(path) { read(path, reads!!) }
            if (!root.folder) return content(root).first.joinToString("") { "%02x".format(it) }
            val digest = MessageDigest.getInstance("SHA-256")
            val seen = mutableSetOf<String>()
            var visited = 0
            fun add(entry: Entry, relative: String, depth: Int) {
                control?.check()
                require(++visited <= 100_000 && depth <= 128) {
                    "This verification exceeds the safe traversal limit."
                }
                require(entry.documentUri != null || !Files.isSymbolicLink(entry.file.toPath())) {
                    "Symbolic links are not verified implicitly."
                }
                digest.update(if (entry.folder) 0.toByte() else 1.toByte())
                val name = relative.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.size).array())
                digest.update(name)
                if (entry.folder) {
                    require(seen.add(entry.path)) { "A provider folder cycle was found." }
                    val children =
                        if (reads == null) list(entry.path)
                        else
                            kotlinx.coroutines.runBlocking {
                                val children = mutableListOf<Entry>()
                                val reply =
                                    visitChildren(entry.path, reads) {
                                        require(children.size + visited < 100_000) {
                                            "Undo verification exceeds the safe traversal limit."
                                        }
                                        children += it
                                        true
                                    }
                                check(!reply.loading && reply.error == null) {
                                    reply.error ?: "This folder listing is incomplete."
                                }
                                children
                            }
                    children
                        .sortedBy { it.name }
                        .forEach { add(it, "$relative/${it.name}", depth + 1) }
                    digest.update(2.toByte())
                } else {
                    val (hash, bytes) = content(entry)
                    digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes).array())
                    digest.update(hash)
                }
            }
            add(root, "", 0)
            return "tree-sha256-v1:" + digest.digest().joinToString("") { "%02x".format(it) }
        }
        return if (control != null)
            control.guard(java.io.Closeable { reads!!.cancel() }, ::calculate)
        else calculate()
    }

    fun createdStep(path: String): FileStep {
        if (!isDocumentPath(path)) return FileStep.Created(File(path))
        var current = path
        val entry = read(path)
        val parent = parent(path)!!
        val stamp = digest(path)
        var stash: File? = null
        var stashStamp: String? = null
        return FileStep.Callback(
            description = "${entry.name}\n$path",
            check = { undo ->
                if (undo)
                    check(digest(current) == stamp) {
                        "${entry.name} changed; Undo stopped to protect it."
                    }
                else {
                    check(list(parent).none { it.name == entry.name }) {
                        "${entry.name} already exists."
                    }
                    check(stash?.exists() == true && fingerprint(stash!!) == stashStamp) {
                        "The recovery copy is unavailable or changed."
                    }
                }
            },
            perform = { undo ->
                if (undo) {
                    stash = trash(current)
                    stashStamp = fingerprint(stash!!)
                    emptyList()
                } else {
                    val before = current
                    current = restore(stash!!)
                    stash = null
                    lastRestoreMappings.ifEmpty { listOf(before to current) }
                }
            },
        )
    }

    fun movedStep(before: Entry, after: String): FileStep {
        if (before.documentUri == null && !isDocumentPath(after))
            return FileStep.Moved(before.file, File(after))
        var current = after
        val beforeParent = before.parentPath ?: error("Source parent unavailable")
        val afterEntry = read(after)
        val afterParent = parent(after)!!
        val stamp = digest(after)
        return FileStep.Callback(
            description = "${before.name} → ${afterEntry.name}\n${before.path} → $after",
            check = { undo ->
                check(digest(current) == stamp) {
                    "The item changed; Undo/Redo stopped to protect it."
                }
                val parent = if (undo) beforeParent else afterParent
                val name = if (undo) before.name else afterEntry.name
                check(list(parent).none { it.name == name }) { "$name already exists." }
            },
            perform = { undo ->
                val from = current
                current =
                    copy(
                        current,
                        if (undo) beforeParent else afterParent,
                        true,
                        requestedName = if (undo) before.name else afterEntry.name,
                    )
                lastCopyMappings.ifEmpty { listOf(from to current) }
            },
        )
    }

    fun renamedStep(before: Entry, after: String): FileStep {
        if (before.documentUri == null) return FileStep.Moved(before.file, File(after))
        var current = after
        val afterName = read(after).name
        val stamp = digest(after)
        val parent = before.parentPath!!
        return FileStep.Callback(
            description = "${before.name} → $afterName\n${before.path}",
            check = { undo ->
                check(digest(current) == stamp) { "The item changed after renaming." }
                val name = if (undo) before.name else afterName
                check(list(parent).none { it.name == name && it.path != current }) {
                    "$name already exists."
                }
            },
            perform = { undo ->
                val from = current
                current = rename(current, if (undo) before.name else afterName)
                listOf(from to current)
            },
        )
    }

    fun renameBatch(
        items: List<Pair<Entry, String>>,
        control: TransferControl? = null,
    ): List<Pair<String, String>> {
        control?.check()
        if (items.all { it.first.documentUri == null })
            return engine
                .renameBatch(items.map { RenameProposal(it.first.file, it.second) }, control)
                .map {
                    it.first.absolutePath to it.second.absolutePath
                }
        val targets = items.map { (entry, name) ->
            validateFilename(name)
            (entry.parentPath ?: error("Parent folder unavailable")) to name
        }
        require(targets.distinct().size == targets.size) { "Two items would have the same name." }
        val changed = items.filter { it.first.name != it.second }
        if (changed.isEmpty()) return emptyList()
        val sources = changed.map { it.first.path }.toSet()
        val parents = changed.map { it.first.parentPath!! }.distinct().associateWith(::list)
        changed.forEach { (entry, name) ->
            control?.check()
            check(entry.canRename) { "${entry.name} can't be renamed by this provider." }
            check(
                parents.getValue(entry.parentPath!!).none {
                    it.name == name && it.path !in sources
                },
            ) {
                "$name already exists."
            }
        }
        val positions = changed.associate { it.first.path to it.first.path }.toMutableMap()
        try {
            changed.forEach { (entry, _) ->
                control?.item(entry.name)
                positions[entry.path] =
                    rename(entry.path, ".findroid-rename-${UUID.randomUUID()}")
            }
            changed.forEach { (entry, name) ->
                control?.check()
                positions[entry.path] = rename(positions.getValue(entry.path), name)
                check(read(positions.getValue(entry.path)).name == name) {
                    "The provider adjusted the requested name. Original names will be restored."
                }
            }
        } catch (error: Exception) {
            positions.toMap().forEach { (original, current) ->
                if (current != original)
                    runCatching {
                        positions[original] =
                            rename(current, ".findroid-rollback-${UUID.randomUUID()}")
                    }
            }
            changed.forEach { (entry, _) ->
                val current = positions.getValue(entry.path)
                if (current != entry.path)
                    runCatching {
                        positions[entry.path] = rename(current, entry.name)
                    }
            }
            changed.forEach { (entry, _) ->
                val current = positions.getValue(entry.path)
                if (current != entry.path)
                    runCatching {
                        if (read(current).name != entry.name) {
                            positions[entry.path] =
                                rename(
                                    current,
                                    uniqueName(entry.parentPath!!, recoveredRenameName(entry.name)),
                                )
                        }
                    }
            }
            val recovery = positions.toList().filter { it.first != it.second }
            if (recovery.isNotEmpty()) throw RenameRecoveryException(recovery, error)
            throw error
        }
        return positions.toList()
    }

    fun renamedBatchStep(
        before: List<Entry>,
        pairs: List<Pair<String, String>>,
        checkedStamps: Map<String, String>? = null,
        requestedNames: Map<String, String>? = null,
    ): FileStep {
        if (before.all { it.documentUri == null })
            return FileStep.Renamed(
                pairs.map { File(it.first) to File(it.second) },
                checkedStamps?.let { stamps -> pairs.map { stamps.getValue(it.first) } },
            )
        var current = pairs.map { it.second }
        val original = before.associateBy { it.path }
        val names = pairs.map { requestedNames?.get(it.first) ?: read(it.second).name }
        val stamps =
            checkedStamps?.let { values -> pairs.map { values.getValue(it.first) } }
                ?: current.map(::digest)
        return FileStep.Callback(
            description = before.take(3).joinToString("\n") { "${it.name} · ${it.path}" },
            check = { undo ->
                current.forEachIndexed { index, path ->
                    check(digest(path) == stamps[index]) { "An item changed after renaming." }
                    val entry = read(path)
                    val name =
                        if (undo) original.getValue(pairs[index].first).name else names[index]
                    check(
                        list(entry.parentPath!!).none { it.name == name && it.path !in current },
                    ) {
                        "$name already exists."
                    }
                }
            },
            perform = { undo ->
                val result =
                    renameBatch(
                        current.mapIndexed { index, path ->
                            read(path) to
                                if (undo) original.getValue(pairs[index].first).name
                                else names[index]
                        },
                    )
                current = result.map { it.second }
                result
            },
        )
    }

    fun trashMappings(item: File): List<Pair<String, String>> =
        engine.originalMappings(item).ifEmpty {
            engine.originalPath(item)?.let { listOf(it to item.path) }.orEmpty()
        }

    fun trashedStep(before: Entry, item: File, relocateMetadata: Boolean = false): FileStep {
        if (before.documentUri == null && !relocateMetadata)
            return FileStep.Trashed(before.file, item)
        var stash = item
        var restored: String? = null
        var stamp = fingerprint(item)
        var restoredStamp: String? = null
        val parent = before.parentPath!!
        return FileStep.Callback(
            description = "${before.name}\n${before.path}",
            check = { undo ->
                if (undo) {
                    check(fingerprint(stash) == stamp) { "The recovery copy changed." }
                    check(list(parent).none { it.name == before.name }) {
                        "${before.name} already exists."
                    }
                } else
                    check(digest(restored!!) == restoredStamp) {
                        "The restored file changed; Redo stopped to protect it."
                    }
            },
            perform = { undo ->
                if (undo) {
                    val old = before.path
                    val recoveryRoutes = if (relocateMetadata) trashMappings(stash) else emptyList()
                    restored = restore(stash)
                    restoredStamp = digest(restored!!)
                    val routes = lastRestoreMappings.ifEmpty { listOf(old to restored!!) }
                    val restoredPaths = routes.toMap()
                    routes +
                        recoveryRoutes.mapNotNull { (original, recovery) ->
                            restoredPaths[original]?.let { recovery to it }
                        }
                } else {
                    stash = trash(restored!!)
                    stamp = fingerprint(stash)
                    if (relocateMetadata) trashMappings(stash) else emptyList()
                }
            },
        )
    }
}
