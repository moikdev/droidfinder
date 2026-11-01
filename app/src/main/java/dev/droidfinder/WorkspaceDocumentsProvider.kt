package dev.droidfinder

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale

/** Android grants selected documents/trees; MANAGE_DOCUMENTS protects ungranted entry points. */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    private val app
        get() = context!!.applicationContext as FinderApplication

    private val access
        get() = app.workspaceDocuments

    private val ids
        get() = access.ids

    private val root
        get() = access.root

    private val documentColumns =
        arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    private val rootColumns =
        arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_ICON,
        )

    override fun onCreate() = true

    private fun file(id: String): File {
        access.requireEnabled()
        return ids.file(id)
    }

    private fun type(file: File) =
        if (file.isDirectory) Document.MIME_TYPE_DIR
        else
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
                ?: "application/octet-stream"

    private fun cursor(projection: Array<out String>?) =
        MatrixCursor(projection ?: documentColumns).also {
            it.setNotificationUri(context!!.contentResolver, access.changesUri)
        }

    private fun include(cursor: MatrixCursor, file: File, requestedId: String? = null) {
        val id = requestedId ?: ids.id(file)
        val mime = type(file)
        val mutable = !WorkspaceMutationGate.appBusy
        val flags =
            (if (file.isDirectory && file.canWrite() && mutable) Document.FLAG_DIR_SUPPORTS_CREATE
            else 0) or
                (if (file.isFile && file.canWrite() && mutable) Document.FLAG_SUPPORTS_WRITE
                else 0) or
                (if (
                    id != WorkspaceDocumentIds.ROOT &&
                        file.parentFile?.canWrite() == true &&
                        mutable
                )
                    Document.FLAG_SUPPORTS_RENAME or
                        Document.FLAG_SUPPORTS_DELETE or
                        Document.FLAG_SUPPORTS_MOVE or
                        Document.FLAG_SUPPORTS_COPY
                else 0) or
                (if (mime.startsWith("image/")) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
        val values =
            cursor.columnNames.map { c ->
                when (c) {
                    Document.COLUMN_DOCUMENT_ID -> id
                    Document.COLUMN_DISPLAY_NAME ->
                        if (id == WorkspaceDocumentIds.ROOT) "Workspace" else file.name
                    Document.COLUMN_MIME_TYPE -> mime
                    Document.COLUMN_FLAGS -> flags
                    Document.COLUMN_SIZE -> if (file.isFile) file.length() else null
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    else -> null
                }
            }
        if (!ids.matches(id, file)) throw FileNotFoundException("The document changed. Try again.")
        cursor.addRow(values)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: rootColumns).apply {
            setNotificationUri(context!!.contentResolver, access.changesUri)
            if (!access.enabled) return@apply
            val row = newRow()
            columnNames.forEach { c ->
                row.add(
                    c,
                    when (c) {
                        Root.COLUMN_ROOT_ID,
                        Root.COLUMN_DOCUMENT_ID -> WorkspaceDocumentIds.ROOT
                        Root.COLUMN_TITLE -> "DroidFinder"
                        Root.COLUMN_SUMMARY -> "Workspace"
                        Root.COLUMN_FLAGS ->
                            Root.FLAG_SUPPORTS_CREATE or
                                Root.FLAG_SUPPORTS_SEARCH or
                                Root.FLAG_SUPPORTS_RECENTS or
                                Root.FLAG_SUPPORTS_IS_CHILD
                        Root.COLUMN_MIME_TYPES -> "*/*"
                        Root.COLUMN_AVAILABLE_BYTES -> root.usableSpace
                        Root.COLUMN_ICON -> R.drawable.ic_launcher
                        else -> null
                    },
                )
            }
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        WorkspaceMutationGate.read {
            cursor(projection).apply { include(this, file(documentId), documentId) }
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = WorkspaceMutationGate.read {
        cursor(projection).apply {
            val parent = file(parentDocumentId)
            if (!parent.isDirectory) throw FileNotFoundException("Not a folder")
            var count = 0
            Files.newDirectoryStream(parent.toPath()).use { children ->
                for (path in children) {
                    val child =
                        runCatching { workspaceProviderFile(root, path.toFile()) }.getOrNull()
                            ?: continue
                    if (++count > 20_000) {
                        extras =
                            Bundle().apply {
                                putString(
                                    DocumentsContract.EXTRA_INFO,
                                    "Showing the first 20,000 items. Browse the full folder in DroidFinder.",
                                )
                            }
                        break
                    }
                    include(this, child)
                    if (!ids.matches(parentDocumentId, parent))
                        throw FileNotFoundException("The folder changed. Try again.")
                }
            }
            if (!ids.matches(parentDocumentId, parent))
                throw FileNotFoundException("The folder changed. Try again.")
        }
    }

    private fun scan(
        projection: Array<out String>?,
        query: String?,
        signal: CancellationSignal? = null,
    ): Cursor = WorkspaceMutationGate.read {
        cursor(projection).apply {
            access.requireEnabled()
            val pending = ArrayDeque<File>().apply { add(root) }
            val recent = mutableListOf<File>()
            var visited = 0
            var limited = false
            outer@ while (pending.isNotEmpty()) {
                signal?.throwIfCanceled()
                val directory = pending.removeFirst()
                Files.newDirectoryStream(directory.toPath()).use { children ->
                    for (path in children) {
                        signal?.throwIfCanceled()
                        if (++visited > 20_000) {
                            limited = true
                            break@outer
                        }
                        val child =
                            runCatching { workspaceProviderFile(root, path.toFile()) }.getOrNull()
                                ?: continue
                        if (child.isDirectory) pending.add(child)
                        if (query != null && child.name.contains(query, true)) {
                            include(this, child)
                            if (count >= 200) {
                                limited = true
                                break@outer
                            }
                        } else if (query == null && child.isFile) {
                            recent.add(child)
                            if (recent.size > 100) {
                                recent.sortByDescending { it.lastModified() }
                                recent.subList(64, recent.size).clear()
                            }
                        }
                    }
                }
            }
            if (query == null)
                recent
                    .sortedByDescending { it.lastModified() }
                    .take(64)
                    .forEach { include(this, it) }
            if (limited)
                extras =
                    Bundle().apply {
                        putString(
                            DocumentsContract.EXTRA_INFO,
                            "Results are limited. Search inside DroidFinder for more files.",
                        )
                    }
        }
    }

    private fun checkRoot(rootId: String) {
        if (rootId != WorkspaceDocumentIds.ROOT) throw FileNotFoundException("Unknown root")
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        checkRoot(rootId)
        return scan(projection, query)
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        checkRoot(rootId)
        return scan(projection, null)
    }

    override fun queryRecentDocuments(
        rootId: String,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        signal: CancellationSignal?,
    ): Cursor {
        checkRoot(rootId)
        return scan(projection, null, signal)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        WorkspaceMutationGate.read {
            runCatching {
                    val parent = file(parentDocumentId).toPath()
                    val child = file(documentId).toPath()
                    child != parent && child.startsWith(parent)
                }
                .getOrDefault(false)
        }

    override fun findDocumentPath(
        parentDocumentId: String?,
        childDocumentId: String,
    ): DocumentsContract.Path = WorkspaceMutationGate.read {
        val child = file(childDocumentId)
        val parent = parentDocumentId?.let(::file) ?: root.canonicalFile
        if (!child.toPath().startsWith(parent.toPath()))
            throw FileNotFoundException("Outside the selected folder")
        val chain =
            generateSequence(child) { if (it == parent) null else it.parentFile }
                .toList()
                .asReversed()
                .map(ids::id)
        DocumentsContract.Path(
            if (parentDocumentId == null) WorkspaceDocumentIds.ROOT else null,
            chain,
        )
    }

    private fun changed(routes: List<Pair<String, String>> = emptyList()) {
        if (routes.isNotEmpty()) access.relocated(routes) else access.changed()
        app.workspaceChanged(routes)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val flags = ParcelFileDescriptor.parseMode(mode)
        fun open(): ParcelFileDescriptor {
            signal?.throwIfCanceled()
            val target = file(documentId)
            if (!target.isFile) throw FileNotFoundException("Not a regular file")
            val descriptor =
                if (flags and ParcelFileDescriptor.MODE_WRITE_ONLY == 0)
                    ParcelFileDescriptor.open(target, flags)
                else
                    ParcelFileDescriptor.open(target, flags, Handler(Looper.getMainLooper())) {
                        changed()
                    }
            try {
                ids.verifyDescriptor(documentId, descriptor)
                signal?.throwIfCanceled()
                return descriptor
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
        }
        return if (flags and ParcelFileDescriptor.MODE_WRITE_ONLY == 0)
            WorkspaceMutationGate.read { open() }
        else WorkspaceMutationGate.change { open() }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor =
        AssetFileDescriptor(
            openDocument(documentId, "r", signal),
            0L,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String = WorkspaceMutationGate.change {
        val parent = file(parentDocumentId)
        val name = validateFilename(displayName)
        if (name.startsWith(".droidfinder-"))
            throw FileNotFoundException("Reserved working-file name")
        val target = app.localEngine.uniqueFile(parent, name)
        if (!parent.isDirectory || !parent.canWrite())
            throw FileNotFoundException("Folder is read only")
        if (mimeType == Document.MIME_TYPE_DIR) check(target.mkdir()) { "Couldn't create folder" }
        else check(target.createNewFile()) { "Couldn't create file" }
        ids.id(target).also { changed() }
    }

    override fun renameDocument(documentId: String, displayName: String): String =
        WorkspaceMutationGate.change {
            if (documentId == WorkspaceDocumentIds.ROOT)
                throw FileNotFoundException("Workspace cannot be renamed")
            val source = file(documentId)
            val name = validateFilename(displayName)
            if (name.startsWith(".droidfinder-"))
                throw FileNotFoundException("Reserved working-file name")
            val target = app.localEngine.rename(source, name)
            changed(listOf(source.path to target.path))
            documentId
        }

    override fun deleteDocument(documentId: String) = WorkspaceMutationGate.change {
        if (documentId == WorkspaceDocumentIds.ROOT)
            throw FileNotFoundException("Workspace cannot be deleted")
        val source = file(documentId)
        ids.forget(listOf(source.path))
        app.localEngine.trash(source)
        revokeDocumentPermission(documentId)
        changed()
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String =
        WorkspaceMutationGate.change {
            val target = app.localEngine.copy(file(sourceDocumentId), file(targetParentDocumentId))
            ids.id(target).also { changed() }
        }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String = WorkspaceMutationGate.change {
        if (
            sourceDocumentId == WorkspaceDocumentIds.ROOT ||
                !isChildDocument(sourceParentDocumentId, sourceDocumentId)
        )
            throw FileNotFoundException("Invalid move")
        val source = file(sourceDocumentId)
        if (source.parentFile != file(sourceParentDocumentId))
            throw FileNotFoundException("The original parent changed")
        val target = app.localEngine.copy(source, file(targetParentDocumentId), move = true)
        changed(listOf(source.path to target.path))
        sourceDocumentId
    }
}
