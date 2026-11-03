package dev.findroid.testing

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.findroid.validateFilename
import java.io.File
import java.io.FileNotFoundException

/** Isolated fixture provider: compiled only into the debug/test build, never preview/release. */
class FixtureDocumentsProvider : DocumentsProvider() {
    companion object {
        @Volatile var readByteLimits: Map<String, Int> = emptyMap()
        @Volatile var readDelayMillis: Map<String, Long> = emptyMap()
        @Volatile var mimeOverrides: Map<String, String> = emptyMap()
        @Volatile var unknownSizePaths: Set<String> = emptySet()
        @Volatile var transferGate: java.util.concurrent.CountDownLatch? = null
        @Volatile var gateOpened: java.util.concurrent.CountDownLatch? = null
        @Volatile var beforeDelete: ((String) -> Unit)? = null
        @Volatile var beforeRename: ((String, String) -> Unit)? = null
        @Volatile var listingDirectory: String? = null
        @Volatile var listingTitle: String? = null
        @Volatile var streamCancellation: java.util.concurrent.CountDownLatch? = null
        @Volatile var listingGate: java.util.concurrent.CountDownLatch? = null
        @Volatile var listingGateOpened: java.util.concurrent.CountDownLatch? = null
        @Volatile var listingExtras: android.os.Bundle? = null
        @Volatile var listingFailure: String? = null
        @Volatile var metadataDirectory: String? = null
        @Volatile var metadataGate: java.util.concurrent.CountDownLatch? = null
        @Volatile var metadataOpened: java.util.concurrent.CountDownLatch? = null
        @Volatile var metadataFailure: String? = null
        @Volatile var metadataDenied = false
        @Volatile var ancestryUnsupported = false
        val ancestryCalls = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
        val ancestryChecks = java.util.concurrent.atomic.AtomicInteger(0)
        val contentReads = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val listingClosed = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private val authority
        get() = "${context!!.packageName}.fixtures"

    private val root
        get() = File(context!!.filesDir, "ProviderFixtures").apply { mkdirs() }

    override fun onCreate() = true

    private fun file(id: String): File {
        val relative = if (id == "root") "" else id.removePrefix("root/")
        val file = File(root, relative).canonicalFile
        if (!file.toPath().startsWith(root.canonicalFile.toPath()))
            throw FileNotFoundException("Outside fixture root")
        return file
    }

    private fun id(file: File) =
        "root" +
            file.canonicalFile
                .relativeTo(root.canonicalFile)
                .path
                .takeIf { it.isNotEmpty() }
                ?.let { "/$it" }
                .orEmpty()

    private fun readOnly(file: File) =
        file.relativeTo(root).path.split('/').any { it == "readonly" }

    private val columns =
        arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(
                projection
                    ?: arrayOf(
                        Root.COLUMN_ROOT_ID,
                        Root.COLUMN_DOCUMENT_ID,
                        Root.COLUMN_TITLE,
                        Root.COLUMN_FLAGS,
                        Root.COLUMN_AVAILABLE_BYTES,
                    ),
            )
            .apply {
                val row = newRow()
                columnNames.forEach { column ->
                    row.add(
                        column,
                        when (column) {
                            Root.COLUMN_ROOT_ID -> "fixture"
                            Root.COLUMN_DOCUMENT_ID -> "root"
                            Root.COLUMN_TITLE -> "Findroid test storage"
                            Root.COLUMN_FLAGS ->
                                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
                            Root.COLUMN_AVAILABLE_BYTES -> root.usableSpace
                            else -> null
                        },
                    )
                }
            }

    private fun row(cursor: MatrixCursor, file: File) {
        val row = cursor.newRow()
        cursor.columnNames.forEach { column ->
            row.add(
                column,
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> id(file)
                    Document.COLUMN_DISPLAY_NAME ->
                        if (file.canonicalPath == listingDirectory && listingTitle != null)
                            listingTitle
                        else if (file == root) "Findroid test storage" else file.name
                    Document.COLUMN_MIME_TYPE ->
                        if (file.isDirectory) Document.MIME_TYPE_DIR
                        else
                            mimeOverrides[file.canonicalPath]
                                ?: MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(file.extension)
                                ?: "application/octet-stream"
                    Document.COLUMN_SIZE ->
                        if (file.name == "Unknown-size.bin" || file.canonicalPath in unknownSizePaths) null
                        else if (file.isDirectory) 0L else file.length()
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    Document.COLUMN_FLAGS ->
                        if (readOnly(file)) 0
                        else
                            Document.FLAG_SUPPORTS_DELETE or
                                Document.FLAG_SUPPORTS_RENAME or
                                if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE
                                else Document.FLAG_SUPPORTS_WRITE
                    else -> null
                },
            )
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).apply {
            val file = file(documentId)
            if (file.canonicalPath == metadataDirectory) {
                metadataOpened?.countDown()
                check(metadataGate?.await(15, java.util.concurrent.TimeUnit.SECONDS) != false) {
                    "Fixture metadata gate timed out"
                }
            }
            if (file.canonicalPath == metadataFailure) {
                if (metadataDenied) throw SecurityException("Fixture permission was revoked")
                throw FileNotFoundException("Fixture location is offline")
            }
            if (!file.exists()) throw FileNotFoundException(documentId)
            row(this, file)
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = file(parentDocumentId)
        if (parent.canonicalPath == listingFailure)
            throw FileNotFoundException("Fixture location is offline")
        val controlled = parent.canonicalPath == listingDirectory
        return object : MatrixCursor(projection ?: columns) {
                private var waited = false

                override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
                    if (controlled && newPosition >= 64 && !waited && listingGate != null) {
                        waited = true
                        listingGateOpened?.countDown()
                        listingGate?.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    return true
                }

                override fun getExtras(): android.os.Bundle =
                    if (controlled)
                        listingExtras?.let { android.os.Bundle(it) } ?: android.os.Bundle.EMPTY
                    else android.os.Bundle.EMPTY

                override fun close() {
                    super.close()
                    if (controlled) listingClosed.incrementAndGet()
                }
            }
            .apply {
                parent.listFiles()?.sortedBy { it.name }?.forEach { row(this, it) }
                setNotificationUri(
                    context!!.contentResolver,
                    DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
                )
            }
    }

    private fun changed(parent: File?) {
        if (parent != null)
            context!!
                .contentResolver
                .notifyChange(DocumentsContract.buildChildDocumentsUri(authority, id(parent)), null)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = file(documentId)
        if (mode == "r") contentReads.getOrPut(file.canonicalPath) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        if (mode.contains('w') && readOnly(file)) throw FileNotFoundException("Read only fixture")
        readByteLimits[file.canonicalPath]?.takeIf { mode == "r" }?.let { limit ->
            val pipe = ParcelFileDescriptor.createPipe()
            Thread({
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        file.inputStream().use { input -> output.write(input.readBytes().take(limit).toByteArray()) }
                    }
                }
            }, "fixture-short-stream").apply { isDaemon = true; start() }
            return pipe[0]
        }
        if (
            file.name in
                listOf("Slow.bin", "Gate.bin", "Slow.txt", "Gate.txt", "Gate.pdf", "Gate.jpg") &&
                mode == "r"
        ) {
            val pipe = ParcelFileDescriptor.createPipe()
            signal?.setOnCancelListener {
                streamCancellation?.countDown()
                runCatching { pipe[1].close() }
                runCatching { pipe[0].close() }
                if (file.name in listOf("Gate.txt", "Gate.pdf", "Gate.jpg"))
                    transferGate?.countDown()
            }
            Thread(
                    {
                        runCatching {
                            if (
                                file.name in listOf("Gate.bin", "Gate.txt", "Gate.pdf", "Gate.jpg")
                            ) {
                                gateOpened?.countDown()
                                transferGate?.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            }
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                                file.inputStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        output.write(buffer, 0, size)
                                        if (file.name == "Slow.bin" || file.name == "Slow.txt")
                                            Thread.sleep(readDelayMillis[file.canonicalPath] ?: 50)
                                    }
                                }
                            }
                        }
                    },
                    "fixture-slow-stream",
                )
                .apply {
                    isDaemon = true
                    start()
                }
            return pipe[0]
        }
        return if (mode.contains('w'))
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.parseMode(mode),
                Handler(context!!.mainLooper),
            ) {
                changed(file.parentFile)
            }
        else ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = file(parentDocumentId)
        if (readOnly(parent)) throw FileNotFoundException("Read only")
        val created = File(parent, validateFilename(displayName))
        if (created.exists()) throw FileNotFoundException("Collision")
        check(if (mimeType == Document.MIME_TYPE_DIR) created.mkdir() else created.createNewFile())
        changed(parent)
        return id(created)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        beforeRename?.invoke(documentId, displayName)
        val before = file(documentId)
        if (readOnly(before)) throw FileNotFoundException("Read only")
        val after = File(before.parentFile, validateFilename(displayName))
        if (after.exists()) throw FileNotFoundException("Collision")
        check(before.renameTo(after))
        changed(after.parentFile)
        return id(after)
    }

    override fun deleteDocument(documentId: String) {
        val file = file(documentId)
        if (file == root || readOnly(file)) throw FileNotFoundException("Read only")
        beforeDelete?.invoke(documentId)
        check(file.deleteRecursively())
        changed(file.parentFile)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        ancestryChecks.incrementAndGet()
        ancestryCalls.add(parentDocumentId to documentId)
        if (ancestryUnsupported) throw UnsupportedOperationException("Fixture provider cannot verify ancestry")
        return documentId.startsWith("$parentDocumentId/")
    }
}
