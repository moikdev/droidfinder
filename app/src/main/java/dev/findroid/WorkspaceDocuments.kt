package dev.findroid

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One process owns app operations and provider mutations. Read descriptors remain ordinary OS FDs.
 */
internal object WorkspaceMutationGate {
    private val lock = ReentrantLock()
    @Volatile
    var appBusy = false
        private set

    fun beginApp(): Boolean {
        if (!lock.tryLock()) return false
        return try {
            if (appBusy) false
            else {
                appBusy = true
                true
            }
        } finally {
            lock.unlock()
        }
    }

    fun endApp() = lock.withLock { appBusy = false }

    fun <T> read(work: () -> T): T = lock.withLock(work)

    fun <T> change(work: () -> T): T = lock.withLock {
        if (appBusy)
            throw FileNotFoundException(
                "Findroid is completing a file operation. Try again shortly.",
            )
        work()
    }
}

/** The provider exposes only existing regular Workspace files, never aliases or staging files. */
internal fun workspaceProviderFile(root: File, requested: File): File {
    val rawBase = root.absoluteFile.toPath().normalize()
    val base = root.canonicalFile.toPath()
    val rawPath = requested.absoluteFile.toPath().normalize()
    val path =
        if (rawPath.startsWith(rawBase)) base.resolve(rawBase.relativize(rawPath)) else rawPath
    if (!path.startsWith(base)) throw FileNotFoundException("Outside Workspace")
    var current = base
    if (Files.isSymbolicLink(root.toPath()))
        throw FileNotFoundException("Workspace is a symbolic link")
    for (part in base.relativize(path)) {
        if (isWorkingFileName(part.toString()))
            throw FileNotFoundException("Internal working file")
        current = current.resolve(part)
        if (Files.isSymbolicLink(current))
            throw FileNotFoundException("Symbolic links aren't exported")
    }
    val file = path.toFile()
    if (!file.exists() || (!file.isDirectory && !file.isFile))
        throw FileNotFoundException("Document is no longer available")
    val canonical = file.canonicalFile
    if (!canonical.toPath().startsWith(root.canonicalFile.toPath()))
        throw FileNotFoundException("Outside Workspace")
    return canonical
}

internal class WorkspaceDocumentIds(context: Context, val root: File) :
    SQLiteOpenHelper(context, "workspace-document-ids.db", null, 2) {
    companion object {
        const val ROOT = "workspace"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE documents (id TEXT PRIMARY KEY, path TEXT UNIQUE NOT NULL, identity TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX document_identity ON documents(identity)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2)
            db.execSQL("CREATE INDEX IF NOT EXISTS document_identity ON documents(identity)")
    }

    private fun identity(file: File): String =
        Os.lstat(file.path).let { "${it.st_dev}:${it.st_ino}" }

    private fun identityFor(id: String): String =
        readableDatabase
            .query("documents", arrayOf("identity"), "id=?", arrayOf(id), null, null, null)
            .use { c ->
                if (!c.moveToFirst()) throw FileNotFoundException("Unknown Workspace document")
                c.getString(0)
            }

    @Synchronized
    fun matches(id: String, file: File): Boolean = runCatching {
        if (id == ROOT) file == root.canonicalFile else identity(file) == identityFor(id)
    }
        .getOrDefault(false)

    @Synchronized
    fun verifyDescriptor(id: String, descriptor: ParcelFileDescriptor) {
        val actual = Os.fstat(descriptor.fileDescriptor).let { "${it.st_dev}:${it.st_ino}" }
        if (actual != identityFor(id))
            throw FileNotFoundException("The document changed while it was opening. Try again.")
    }

    private fun relative(file: File) = file.relativeTo(root.canonicalFile).path

    private data class Row(val id: String, val path: String, val identity: String)

    private fun rows(): List<Row> =
        readableDatabase
            .query("documents", arrayOf("id", "path", "identity"), null, null, null, null, null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getString(2)))
                }
            }

    @Synchronized
    fun id(file: File): String {
        val resolved = workspaceProviderFile(root, file)
        if (resolved == root.canonicalFile) return ROOT
        val path = relative(resolved)
        val key = identity(resolved)
        readableDatabase
            .query(
                "documents",
                arrayOf("id", "identity"),
                "path=?",
                arrayOf(path),
                null,
                null,
                null,
            )
            .use { c ->
                if (c.moveToFirst()) {
                    if (c.getString(1) == key) return c.getString(0)
                    if (WorkspaceMutationGate.appBusy)
                        throw FileNotFoundException("Workspace is changing. Try again shortly.")
                }
            }
        val id = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("documents", "path=?", arrayOf(path))
            db.insertOrThrow(
                "documents",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("path", path)
                    put("identity", key)
                },
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    @Synchronized
    fun file(id: String): File {
        if (id == ROOT) return workspaceProviderFile(root, root)
        val row =
            readableDatabase
                .query(
                    "documents",
                    arrayOf("path", "identity"),
                    "id=?",
                    arrayOf(id),
                    null,
                    null,
                    null,
                )
                .use { c ->
                    if (!c.moveToFirst()) throw FileNotFoundException("Unknown Workspace document")
                    c.getString(0) to c.getString(1)
                }
        return try {
            workspaceProviderFile(root, File(root, row.first)).also {
                if (identity(it) != row.second)
                    throw FileNotFoundException("The original document was replaced")
            }
        } catch (error: Exception) {
            if (!WorkspaceMutationGate.appBusy)
                writableDatabase.delete("documents", "id=?", arrayOf(id))
            throw FileNotFoundException(error.message ?: "Document unavailable").apply {
                initCause(error)
            }
        }
    }

    @Synchronized
    fun forget(paths: List<String>) {
        val roots = paths.filterNot(::isDocumentPath).map { File(it).canonicalFile.toPath() }
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows()
                .filter { row ->
                    roots.any { File(root.canonicalFile, row.path).toPath().startsWith(it) }
                }
                .forEach { db.delete("documents", "id=?", arrayOf(it.id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A move keeps the document identity; a copy gets a fresh identity when queried. */
    @Synchronized
    fun relocate(routes: List<Pair<String, String>>) {
        val normalizedRoutes = routes.map { (a, b) ->
            fun path(value: String) =
                if (value.startsWith("@") || isDocumentPath(value)) value
                else File(value).canonicalPath
            path(a) to path(b)
        }
        val changes =
            rows().mapNotNull { row ->
                val before = File(root.canonicalFile, row.path).path
                val after = remapPath(before, normalizedRoutes)
                if (before == after) null
                else row to runCatching { workspaceProviderFile(root, File(after)) }.getOrNull()
            }
        if (changes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            changes.forEach { (row, _) ->
                db.update(
                    "documents",
                    ContentValues().apply { put("path", ".retired/${row.id}") },
                    "id=?",
                    arrayOf(row.id),
                )
            }
            changes.forEach { (row, file) ->
                if (file == null || file == root.canonicalFile)
                    db.delete("documents", "id=?", arrayOf(row.id))
                else {
                    val path = relative(file)
                    db.delete("documents", "path=?", arrayOf(path))
                    db.update(
                        "documents",
                        ContentValues().apply {
                            put("path", path)
                            put("identity", identity(file))
                        },
                        "id=?",
                        arrayOf(row.id),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

internal class WorkspaceDocuments(private val context: Context) {
    val root = File(context.filesDir, "Workspace").apply { mkdirs() }
    val ids = WorkspaceDocumentIds(context, root)
    val authority
        get() = "${context.packageName}.documents"

    val changesUri: Uri
        get() = DocumentsContract.buildRootsUri(authority)

    private val prefs = context.getSharedPreferences("android-integration", 0)
    val enabled
        get() = prefs.getBoolean("workspace-in-picker", true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("workspace-in-picker", value).apply()
        changed()
    }

    fun requireEnabled() {
        if (!enabled)
            throw SecurityException("Workspace access through the Android picker is turned off")
    }

    fun changed() {
        runCatching { context.contentResolver.notifyChange(changesUri, null) }
    }

    fun relocated(routes: List<Pair<String, String>>) {
        ids.relocate(routes)
        changed()
    }
}
