package dev.findroid

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Private, bounded metadata cache. New generations never replace a usable one before commit. */
internal class SqliteLocalIndexStore(
    context: Context,
    internal val file: File = File(context.noBackupFilesDir, "local-search-index/metadata.sqlite"),
    private val limits: LocalIndexLimits = LocalIndexLimits(),
    private val owner: String = processOwner,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalIndexStore {
    private val context = context.applicationContext
    private val lock = Any()
    private var helper: SQLiteOpenHelper? = null
    private var opened = false
    private var blocked: Throwable? = null
    private var closed = false
    private val leases = mutableSetOf<Lease>()

    init { require(indexId(owner)) }

    private fun database(): SQLiteDatabase {
        check(!closed) { "The search index is closed." }
        blocked?.let { throw IllegalStateException("The search index could not be read. Its cache was kept. Delete the index cache to rebuild it.", it) }
        try {
            if (helper == null) {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "The private index folder could not be created." }
                helper = object : SQLiteOpenHelper(context, file.absolutePath, null, 1,
                    DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("The search index is damaged. Its cache was kept for explicit deletion.") }) {
                    override fun onConfigure(db: SQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                        db.execSQL("PRAGMA synchronous=FULL")
                    }
                    override fun onCreate(db: SQLiteDatabase) {
                        db.execSQL("CREATE TABLE roots (id TEXT PRIMARY KEY, path TEXT NOT NULL UNIQUE, generation TEXT, entries INTEGER NOT NULL, scanned_from INTEGER, refreshed INTEGER, state TEXT NOT NULL, attempt TEXT, owner TEXT, started INTEGER, attempted INTEGER NOT NULL, changed INTEGER, reason TEXT, filesystem_identity TEXT)")
                        db.execSQL("CREATE TABLE entries (root_id TEXT NOT NULL REFERENCES roots(id) ON DELETE CASCADE, generation TEXT NOT NULL, path TEXT NOT NULL, parent TEXT NOT NULL, name TEXT NOT NULL, folder INTEGER NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, value_bytes INTEGER NOT NULL, PRIMARY KEY(root_id,generation,path))")
                        db.execSQL("CREATE INDEX child_entries ON entries(root_id,generation,parent,path)")
                    }
                    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        throw SQLiteException("This search index version is unsupported. Its cache was kept.")
                    }
                    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        throw SQLiteException("A newer Findroid version created this search index. Its cache was kept.")
                    }
                }
            }
            val db = helper!!.writableDatabase
            if (!opened) {
                db.setMaximumSize(limits.databaseBytes)
                transaction(db) {
                    readRoots(db).filter { it.state == IndexRefreshState.BUILDING && it.attemptOwner != owner }.forEach { root ->
                        update(db, root.copy(state = IndexRefreshState.INTERRUPTED, attempt = null, attemptOwner = null,
                            reason = "Findroid stopped before this refresh finished. The previous complete snapshot was kept."))
                    }
                    cleanup(db)
                }
                opened = true
            }
            return db
        } catch (error: Exception) {
            blocked = error
            throw error
        }
    }

    private fun <T> transaction(db: SQLiteDatabase, work: () -> T): T {
        db.beginTransaction()
        try { return work().also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    private fun Cursor.nullLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.nullText(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.boundedCount(name: String, maximum: Int): Int = getLong(getColumnIndexOrThrow(name)).also {
        require(it in 0..maximum.toLong()) { "The search index contains an unsupported count." }
    }.toInt()
    private fun readRoot(row: Cursor) = LocalIndexRoot(
        row.getString(row.getColumnIndexOrThrow("id")), row.getString(row.getColumnIndexOrThrow("path")),
        row.nullText("generation"), row.boundedCount("entries", 100_000), row.nullLong("scanned_from"), row.nullLong("refreshed"),
        IndexRefreshState.valueOf(row.getString(row.getColumnIndexOrThrow("state"))), row.nullText("attempt"), row.nullText("owner"),
        row.nullLong("started"), row.boundedCount("attempted", 100_000), row.nullLong("changed"), row.nullText("reason"), row.nullText("filesystem_identity")).also {
            require(!it.hasSnapshot || it.filesystemIdentity != null) { "The search index has no supported folder identity." }
        }

    private fun readRoots(db: SQLiteDatabase): List<LocalIndexRoot> {
        try {
            return db.rawQuery("SELECT * FROM roots ORDER BY rowid", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        require(size < limits.roots) { "The search index contains too many folders." }
                        add(readRoot(cursor))
                    }
                }
            }
        } catch (error: Exception) { blocked = error; throw error }
    }

    private fun values(root: LocalIndexRoot) = ContentValues().apply {
        put("id", root.id); put("path", root.path); put("generation", root.generation); put("entries", root.entries)
        put("scanned_from", root.scannedFrom); put("refreshed", root.refreshedAt); put("state", root.state.name)
        put("attempt", root.attempt); put("owner", root.attemptOwner); put("started", root.attemptStarted)
        put("attempted", root.attemptedEntries); put("changed", root.changedAt); put("reason", root.reason); put("filesystem_identity", root.filesystemIdentity)
    }
    private fun update(db: SQLiteDatabase, root: LocalIndexRoot) {
        check(db.update("roots", values(root), "id=?", arrayOf(root.id)) == 1) { "The indexed folder changed." }
    }
    private fun tokenRoot(db: SQLiteDatabase, token: IndexBuildToken): LocalIndexRoot =
        readRoots(db).firstOrNull { it.id == token.rootId && it.path == token.path &&
            it.state == IndexRefreshState.BUILDING && it.attempt == token.generation && it.attemptOwner == owner && it.attemptStarted == token.started }
            ?: throw IndexSourceChanged("This index refresh was stopped or its folder was removed.")

    private fun cleanup(db: SQLiteDatabase) {
        for (root in readRoots(db)) {
            val keep = (listOfNotNull(root.generation, root.attempt) + leases.filter { it.valid.get() && it.root.id == root.id }.mapNotNull { it.root.generation }).distinct()
            if (keep.isEmpty()) db.delete("entries", "root_id=?", arrayOf(root.id))
            else db.delete("entries", "root_id=? AND generation NOT IN (${keep.joinToString(",") { "?" }})", (listOf(root.id) + keep).toTypedArray())
        }
    }

    override fun roots(): List<LocalIndexRoot> = synchronized(lock) {
        check(!closed)
        if (!file.exists() && helper == null) emptyList() else readRoots(database())
    }
    override fun add(path: String): LocalIndexRoot = synchronized(lock) {
        require(indexPath(path)) { "Choose an ordinary local folder with a supported path length." }
        val db = database()
        transaction(db) {
            val roots = readRoots(db)
            check(roots.none { it.path == path }) { "This folder is already in the search index." }
            check(roots.size < limits.roots) { "The search index can contain up to ${limits.roots} folders." }
            LocalIndexRoot(newIndexId(), path).also { check(db.insertOrThrow("roots", null, values(it)) != -1L) }
        }
    }
    override fun begin(rootId: String): IndexBuildToken = synchronized(lock) {
        if (!file.exists() && helper == null) throw IndexSourceChanged("This indexed folder was removed.")
        val db = database()
        transaction(db) {
            val root = readRoots(db).firstOrNull { it.id == rootId } ?: throw IndexSourceChanged("This indexed folder was removed.")
            check(root.state != IndexRefreshState.BUILDING) { "This folder is already refreshing." }
            val token = IndexBuildToken(root.id, root.path, newIndexId(), clock().coerceAtLeast(1), localIndexFileIdentity(root.path))
            update(db, root.copy(state = IndexRefreshState.BUILDING, attempt = token.generation, attemptOwner = owner,
                attemptStarted = token.started, attemptedEntries = 0, reason = null))
            cleanup(db)
            token
        }
    }
    override fun append(token: IndexBuildToken, records: List<IndexRecord>) = synchronized(lock) {
        require(records.size in 1..limits.batch)
        records.forEach { it.checked(token.path, limits) }
        if (!file.exists() && helper == null) throw IndexSourceChanged("This indexed folder was removed.")
        val db = database()
        try {
            transaction(db) {
                val root = tokenRoot(db, token)
                if (records.size > limits.entriesPerRoot - root.attemptedEntries) throw IndexBuildLimit("The per-folder entry limit was reached.")
                val total = db.rawQuery("SELECT count(*) FROM entries", null).use { it.moveToFirst(); it.getLong(0) }
                if (total + records.size > limits.storedEntries) throw IndexBuildLimit("The shared index entry limit was reached. Remove an unused indexed folder.")
                val bytes = db.rawQuery("SELECT coalesce(sum(value_bytes),0) FROM entries WHERE root_id=? AND generation=?", arrayOf(token.rootId, token.generation)).use { it.moveToFirst(); it.getLong(0) }
                if (records.sumOf { it.valueBytes } > limits.valueBytes - bytes) throw IndexBuildLimit("The saved-path budget was reached.")
                records.forEach { row ->
                    val value = ContentValues().apply {
                        put("root_id", token.rootId); put("generation", token.generation); put("path", row.path); put("parent", row.parent)
                        put("name", row.name); put("folder", if (row.folder) 1 else 0); put("size", row.size); put("modified", row.modified); put("value_bytes", row.valueBytes)
                    }
                    db.insertOrThrow("entries", null, value)
                }
                update(db, root.copy(attemptedEntries = root.attemptedEntries + records.size))
            }
        } catch (_: SQLiteFullException) { throw IndexBuildLimit("The index could not grow: storage is full or the 64 MB database limit was reached. Free storage or remove an unused indexed folder.") }
    }
    override fun complete(token: IndexBuildToken, finished: Long, entries: Int, checkActive: () -> Unit) = synchronized(lock) {
        checkActive()
        if (!file.exists() && helper == null) throw IndexSourceChanged("This indexed folder was removed.")
        val db = database()
        transaction(db) {
            val root = tokenRoot(db, token)
            val actual = db.rawQuery("SELECT count(*) FROM entries WHERE root_id=? AND generation=?", arrayOf(token.rootId, token.generation)).use { it.moveToFirst(); it.getLong(0) }
            check(actual == entries.toLong() && actual == root.attemptedEntries.toLong()) { "The new index generation is incomplete." }
            check(token.filesystemIdentity != null && token.filesystemIdentity == localIndexFileIdentity(token.path)) { "The indexed folder was replaced during refresh. Its previous snapshot was kept." }
            update(db, root.copy(generation = token.generation, entries = entries, scannedFrom = token.started,
                refreshedAt = finished.coerceAtLeast(token.started), state = IndexRefreshState.READY, attempt = null, attemptOwner = null,
                changedAt = root.changedAt?.takeIf { it >= token.started }, reason = null, filesystemIdentity = token.filesystemIdentity))
            cleanup(db)
            checkActive()
        }
    }
    override fun abandon(token: IndexBuildToken, state: IndexRefreshState, entries: Int, reason: String?) = synchronized(lock) {
        require(state in setOf(IndexRefreshState.PAUSED, IndexRefreshState.INTERRUPTED, IndexRefreshState.FAILED, IndexRefreshState.CAPPED))
        if (!file.exists() && helper == null) return@synchronized
        val db = database()
        transaction(db) {
            val root = readRoots(db).firstOrNull { it.id == token.rootId && it.attempt == token.generation && it.attemptOwner == owner }
            if (root != null) {
                db.delete("entries", "root_id=? AND generation=?", arrayOf(root.id, token.generation))
                update(db, root.copy(state = state, attempt = null, attemptOwner = null,
                    attemptedEntries = entries.coerceIn(0, limits.entriesPerRoot), reason = reason?.take(512)))
                cleanup(db)
            }
            Unit
        }
    }

    override fun acquire(anchor: String): LocalIndexLease? = synchronized(lock) {
        require(indexPath(anchor))
        if (!file.exists() && helper == null) return@synchronized null
        val db = database()
        val root = readRoots(db).filter { it.hasSnapshot && indexWithin(anchor, it.path) }.maxByOrNull { it.path.length } ?: return@synchronized null
        if (anchor != root.path) {
            val present = db.rawQuery("SELECT 1 FROM entries WHERE root_id=? AND generation=? AND path=? AND folder=1 LIMIT 1",
                arrayOf(root.id, root.generation, anchor)).use { it.moveToFirst() }
            if (!present) return@synchronized null
        }
        Lease(root).also { leases.add(it) }
    }

    private inner class Lease(override val root: LocalIndexRoot) : LocalIndexLease {
        val valid = AtomicBoolean(true)
        override fun check() { if (!valid.get()) throw IndexSourceChanged("The search index changed or was removed. Run the search again.") }
        override suspend fun visit(anchor: String, recursive: Boolean, control: ListingControl, each: suspend (IndexRecord) -> Boolean) {
            check()
            require(indexPath(anchor) && indexWithin(anchor, root.path))
            val db = synchronized(lock) { check(); database() }
            val sql = "SELECT path,parent,name,folder,size,modified,value_bytes FROM entries WHERE root_id=? AND generation=? AND " +
                (if (recursive) "instr(path,?)=1" else "parent=?") + " ORDER BY path COLLATE BINARY"
            val query = arrayOf(root.id, root.generation!!, if (recursive) anchor.trimEnd('/') + "/" else anchor)
            try {
                control.withSuspendingSignal { signal ->
                    db.rawQuery(sql, query, signal).use { rows ->
                        while (rows.moveToNext()) {
                            check(); control.check(); currentCoroutineContext().ensureActive()
                            val folder = rows.getLong(3); require(folder in 0L..1L)
                            val record = IndexRecord(rows.getString(0), rows.getString(1), rows.getString(2), folder == 1L, rows.getLong(4), rows.getLong(5)).checked(root.path, limits)
                            require(rows.getLong(6) == record.valueBytes)
                            if (!each(record)) break
                        }
                    }
                }
                check(); control.check()
            } catch (error: IllegalArgumentException) {
                synchronized(lock) { blocked = error }
                throw IllegalStateException("The search index contains unsupported data. Its cache was kept; delete it to rebuild.", error)
            }
        }
        override fun close() {
            synchronized(lock) {
                valid.set(false)
                if (!leases.remove(this) || closed || blocked != null) return
                transaction(database()) { cleanup(database()) }
            }
        }
    }

    override fun markChanged(paths: List<String>, now: Long) = synchronized(lock) {
        if (!file.exists() && helper == null) return@synchronized
        val db = database()
        transaction(db) {
            readRoots(db).filter { (it.hasSnapshot || it.state == IndexRefreshState.BUILDING) &&
                (paths.isEmpty() || paths.any { path -> indexWithin(path, it.path) || indexWithin(it.path, path) }) }.forEach {
                update(db, it.copy(changedAt = maxOf(it.changedAt ?: 1, now.coerceAtLeast(1))))
            }
        }
    }
    override fun remove(rootId: String) = synchronized(lock) {
        if (!file.exists() && helper == null) throw IndexSourceChanged("This indexed folder was removed.")
        val db = database()
        transaction(db) {
            check(readRoots(db).any { it.id == rootId }) { "This indexed folder was already removed." }
            leases.filter { it.root.id == rootId }.forEach { it.valid.set(false) }
            db.delete("roots", "id=?", arrayOf(rootId))
            Unit
        }
    }
    override fun deleteCache() = synchronized(lock) {
        check(!closed)
        leases.forEach { it.valid.set(false) }; leases.clear()
        helper?.close(); helper = null; opened = false
        if (file.exists()) check(SQLiteDatabase.deleteDatabase(file)) { "The index cache could not be deleted." }
        else listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val extra = File(file.path + suffix); check(!extra.exists() || extra.delete()) { "An index cache sidecar could not be deleted." }
        }
        blocked = null
    }
    override fun storageBytes(): Long = synchronized(lock) {
        listOf("", "-wal", "-shm", "-journal").sumOf { File(file.path + it).length() }
    }
    override fun close() = synchronized(lock) {
        leases.forEach { it.valid.set(false) }; leases.clear()
        helper?.close(); helper = null; closed = true
    }
    companion object { private val processOwner = newIndexId() }
}
