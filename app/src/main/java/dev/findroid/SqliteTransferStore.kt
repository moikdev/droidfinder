package dev.findroid

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Single-writer job repository. Transactions checkpoint only changed rows; legacy files are
 * retained.
 */
class SqliteTransferStore(
    context: Context,
    private val name: String = "transfer-jobs.db",
    private val legacyFile: File = File(context.filesDir, "transfer-jobs.properties"),
) : TransferStore {
    private val context = context.applicationContext
    private val legacy = TransferJournal(legacyFile)
    private var snapshot: List<TransferJob>? = null
    private val helper =
        object :
                SQLiteOpenHelper(
                    this.context,
                    name,
                    null,
                    4,
                    DatabaseErrorHandler {
                        // Android's default handler deletes corrupt databases. Preserve them for
                        // explicit recovery.
                        throw SQLiteDatabaseCorruptException(
                            "Transfer database could not be read. Files were kept for recovery.",
                        )
                    },
                ) {
                override fun onConfigure(db: SQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                    db.execSQL("PRAGMA synchronous=FULL")
                }

                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    db.execSQL(
                        "CREATE TABLE jobs (id TEXT PRIMARY KEY, position INTEGER NOT NULL, destination TEXT NOT NULL, move INTEGER NOT NULL, state TEXT NOT NULL, bytes INTEGER NOT NULL, items INTEGER NOT NULL, current TEXT NOT NULL, created INTEGER NOT NULL, started INTEGER NOT NULL, finished INTEGER NOT NULL, in_flight TEXT, policy TEXT NOT NULL, verify_copies INTEGER NOT NULL DEFAULT 0, partial_copy TEXT, reused_bytes INTEGER NOT NULL DEFAULT 0, partial_copy_notice TEXT)",
                    )
                    db.execSQL(
                        "CREATE TABLE sources (job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE, position INTEGER NOT NULL, path TEXT NOT NULL, PRIMARY KEY(job_id,position))",
                    )
                    db.execSQL(
                        "CREATE TABLE results (job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE, source TEXT NOT NULL, position INTEGER NOT NULL, destination TEXT, error TEXT, skipped INTEGER NOT NULL, PRIMARY KEY(job_id,source))",
                    )
                    db.execSQL(
                        "CREATE TABLE merge_plans (job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE, source TEXT NOT NULL, position INTEGER NOT NULL, destination TEXT NOT NULL, stamp TEXT NOT NULL, cleanup TEXT NOT NULL, recovery TEXT, error TEXT, PRIMARY KEY(job_id,source))",
                    )
                    db.execSQL(
                        "CREATE TABLE merge_members (job_id TEXT NOT NULL, root TEXT NOT NULL, source TEXT NOT NULL, position INTEGER NOT NULL, parent TEXT NOT NULL, destination TEXT, error TEXT, skipped INTEGER NOT NULL, cleanup TEXT NOT NULL, recovery TEXT, PRIMARY KEY(job_id,root,source), FOREIGN KEY(job_id,root) REFERENCES merge_plans(job_id,source) ON DELETE CASCADE)",
                    )
                    db.execSQL("CREATE INDEX job_order ON jobs(position)")
                    createEstimates(db)
                    createVerifications(db)
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    check(oldVersion in 1..3 && newVersion == 4) {
                        "Unsupported transfer database upgrade: $oldVersion to $newVersion. Database kept."
                    }
                    if (oldVersion < 2) createEstimates(db)
                    if (oldVersion < 3) {
                        db.execSQL("ALTER TABLE jobs ADD COLUMN verify_copies INTEGER NOT NULL DEFAULT 0")
                        createVerifications(db)
                    }
                    db.execSQL("ALTER TABLE jobs ADD COLUMN partial_copy TEXT")
                    db.execSQL("ALTER TABLE jobs ADD COLUMN reused_bytes INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE jobs ADD COLUMN partial_copy_notice TEXT")
                }
            }
            .apply { setWriteAheadLoggingEnabled(true) }

    private fun createVerifications(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE copy_verifications (job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE, root TEXT NOT NULL, source TEXT NOT NULL, position INTEGER NOT NULL, status TEXT NOT NULL, destination TEXT, bytes INTEGER NOT NULL, expected TEXT, actual TEXT, error TEXT, PRIMARY KEY(job_id,root,source))",
        )
    }

    private fun createEstimates(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE estimates (job_id TEXT PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE, known_bytes INTEGER NOT NULL, files INTEGER NOT NULL, folders INTEGER NOT NULL, unknown_files INTEGER NOT NULL, unreadable INTEGER NOT NULL, limited INTEGER NOT NULL, available INTEGER)",
        )
    }

    private fun <T> transaction(db: SQLiteDatabase, work: () -> T): T {
        db.beginTransactionNonExclusive()
        try {
            val result = work()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun validate(jobs: List<TransferJob>) {
        validatePartialCopyJobs(jobs)
        require(jobs.map { it.id }.distinct().size == jobs.size) { "Duplicate transfer ids." }
        jobs.forEach { job ->
            validateCopyVerification(job)
            require(job.paths.isNotEmpty() && job.paths.distinct().size == job.paths.size) {
                "Invalid transfer sources."
            }
            job.estimate?.let {
                require(
                    it.knownBytes >= 0 &&
                        it.files >= 0 &&
                        it.folders >= 0 &&
                        it.unknownFiles in 0..it.files &&
                        it.unreadable >= 0,
                ) {
                    "Invalid transfer estimate."
                }
            }
            require(job.availableAtStart == null || job.availableAtStart >= 0) {
                "Invalid available space."
            }
            require(job.results.map { it.source }.distinct().size == job.results.size) {
                "Duplicate transfer results."
            }
            require(job.mergePlans.map { it.source }.distinct().size == job.mergePlans.size) {
                "Duplicate merge roots."
            }
            job.mergePlans.forEach { plan ->
                require(plan.members.map { it.source }.distinct().size == plan.members.size) {
                    "Duplicate merge members."
                }
            }
        }
    }

    @Synchronized
    override fun load(): List<TransferJob> {
        try {
            val db = helper.writableDatabase
            val jobs =
                transaction(db) {
                    val migrated =
                        db.rawQuery(
                                "SELECT value FROM meta WHERE key=?",
                                arrayOf("legacy_migrated"),
                            )
                            .use { it.moveToFirst() }
                    if (!migrated) {
                        val previous = legacy.load()
                        validate(previous)
                        write(db, previous, emptyList())
                        db.insertOrThrow(
                            "meta",
                            null,
                            values("key" to "legacy_migrated", "value" to "1"),
                        )
                    }
                    read(db).also(::validate)
                }
            snapshot = jobs
            return jobs
        } catch (error: Exception) {
            snapshot = null
            throw error
        }
    }

    @Synchronized
    override fun save(jobs: List<TransferJob>) {
        validate(jobs)
        try {
            val before = snapshot ?: load()
            transaction(helper.writableDatabase) { write(helper.writableDatabase, jobs, before) }
            snapshot = jobs
        } catch (error: Exception) {
            snapshot = null
            throw error
        }
    }

    private fun values(vararg pairs: Pair<String, Any?>) =
        ContentValues().apply {
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is String -> put(key, value)
                    is Long -> put(key, value)
                    is Int -> put(key, value)
                    is Boolean -> put(key, if (value) 1 else 0)
                    else -> error("Unsupported database value")
                }
            }
        }

    private fun upsert(
        db: SQLiteDatabase,
        table: String,
        row: ContentValues,
        selection: String,
        args: Array<String>,
    ) {
        // REPLACE would delete the existing job/plan and cascade its children.
        if (db.update(table, row, selection, args) == 0) db.insertOrThrow(table, null, row)
    }

    private fun header(job: TransferJob, position: Int) =
        values(
            "id" to job.id,
            "position" to position,
            "destination" to job.destination,
            "move" to job.move,
            "state" to job.state.name,
            "bytes" to job.bytes,
            "items" to job.items,
            "current" to job.current,
            "created" to job.created,
            "started" to job.started,
            "finished" to job.finished,
            "in_flight" to job.inFlight,
            "policy" to job.conflictPolicy.name,
            "verify_copies" to job.verifyCopies,
            "partial_copy" to job.partialCopy?.let(::encodePartialCopy),
            "reused_bytes" to job.reusedBytes,
            "partial_copy_notice" to job.partialCopyNotice,
        )

    private fun write(db: SQLiteDatabase, jobs: List<TransferJob>, before: List<TransferJob>) {
        val old = before.associateBy { it.id }
        val oldPositions = before.mapIndexed { index, job -> job.id to index }.toMap()
        val ids = jobs.map { it.id }.toSet()
        before.filter { it.id !in ids }.forEach { db.delete("jobs", "id=?", arrayOf(it.id)) }
        jobs.forEachIndexed { position, job ->
            val previous = old[job.id]
            val row = header(job, position)
            val oldPosition = oldPositions[job.id] ?: -1
            if (previous == null || row != header(previous, oldPosition))
                upsert(db, "jobs", row, "id=?", arrayOf(job.id))
            if (
                previous == null ||
                    previous.estimate != job.estimate ||
                    previous.availableAtStart != job.availableAtStart
            ) {
                val estimate = job.estimate
                if (estimate == null) db.delete("estimates", "job_id=?", arrayOf(job.id))
                else
                    upsert(
                        db,
                        "estimates",
                        values(
                            "job_id" to job.id,
                            "known_bytes" to estimate.knownBytes,
                            "files" to estimate.files,
                            "folders" to estimate.folders,
                            "unknown_files" to estimate.unknownFiles,
                            "unreadable" to estimate.unreadable,
                            "limited" to estimate.limited,
                            "available" to job.availableAtStart,
                        ),
                        "job_id=?",
                        arrayOf(job.id),
                    )
            }
            if (previous == null || previous.paths !== job.paths && previous.paths != job.paths) {
                db.delete("sources", "job_id=?", arrayOf(job.id))
                job.paths.forEachIndexed { index, path ->
                    db.insertOrThrow(
                        "sources",
                        null,
                        values("job_id" to job.id, "position" to index, "path" to path),
                    )
                }
            }
            if (previous == null || previous.results !== job.results) {
                val oldResults = previous?.results.orEmpty().associateBy { it.source }
                val resultIds = job.results.map { it.source }.toSet()
                oldResults.keys
                    .filter { it !in resultIds }
                    .forEach { db.delete("results", "job_id=? AND source=?", arrayOf(job.id, it)) }
                job.results.forEachIndexed { index, result ->
                    if (
                        oldResults[result.source] != result ||
                            previous?.results?.getOrNull(index)?.source != result.source
                    )
                        upsert(
                            db,
                            "results",
                            values(
                                "job_id" to job.id,
                                "source" to result.source,
                                "position" to index,
                                "destination" to result.destination,
                                "error" to result.error,
                                "skipped" to result.skipped,
                            ),
                            "job_id=? AND source=?",
                            arrayOf(job.id, result.source),
                        )
                }
            }
            if (previous == null || previous.verifications !== job.verifications) {
                val prior = previous?.verifications.orEmpty().associateBy { it.root to it.source }
                val keys = job.verifications.map { it.root to it.source }.toSet()
                prior.keys.filter { it !in keys }.forEach { (root, source) ->
                    db.delete("copy_verifications", "job_id=? AND root=? AND source=?", arrayOf(job.id, root, source))
                }
                job.verifications.forEachIndexed { index, result ->
                    if (prior[result.root to result.source] != result ||
                        previous?.verifications?.getOrNull(index)?.let { it.root to it.source } != (result.root to result.source)) {
                        upsert(db, "copy_verifications", values(
                            "job_id" to job.id, "root" to result.root, "source" to result.source, "position" to index,
                            "status" to result.status.name, "destination" to result.destination, "bytes" to result.bytes,
                            "expected" to result.expectedSha256, "actual" to result.actualSha256, "error" to result.error,
                        ), "job_id=? AND root=? AND source=?", arrayOf(job.id, result.root, result.source))
                    }
                }
            }
            if (previous == null || previous.mergePlans !== job.mergePlans) {
                val oldPlans = previous?.mergePlans.orEmpty().associateBy { it.source }
                val planIds = job.mergePlans.map { it.source }.toSet()
                oldPlans.keys
                    .filter { it !in planIds }
                    .forEach {
                        db.delete("merge_plans", "job_id=? AND source=?", arrayOf(job.id, it))
                    }
                job.mergePlans.forEachIndexed { index, plan ->
                    val oldPlan = oldPlans[plan.source]
                    val planRow =
                        values(
                            "job_id" to job.id,
                            "source" to plan.source,
                            "position" to index,
                            "destination" to plan.destination,
                            "stamp" to plan.sourceStamp,
                            "cleanup" to plan.cleanup.name,
                            "recovery" to plan.recovery,
                            "error" to plan.error,
                        )
                    if (
                        oldPlan == null ||
                            oldPlan.copy(members = emptyList()) !=
                                plan.copy(members = emptyList()) ||
                            previous?.mergePlans?.getOrNull(index)?.source != plan.source
                    )
                        upsert(
                            db,
                            "merge_plans",
                            planRow,
                            "job_id=? AND source=?",
                            arrayOf(job.id, plan.source),
                        )
                    if (oldPlan == null || oldPlan.members !== plan.members) {
                        val oldMembers = oldPlan?.members.orEmpty().associateBy { it.source }
                        val memberIds = plan.members.map { it.source }.toSet()
                        oldMembers.keys
                            .filter { it !in memberIds }
                            .forEach {
                                db.delete(
                                    "merge_members",
                                    "job_id=? AND root=? AND source=?",
                                    arrayOf(job.id, plan.source, it),
                                )
                            }
                        plan.members.forEachIndexed { n, member ->
                            if (
                                oldMembers[member.source] != member ||
                                    oldPlan?.members?.getOrNull(n)?.source != member.source
                            )
                                upsert(
                                    db,
                                    "merge_members",
                                    values(
                                        "job_id" to job.id,
                                        "root" to plan.source,
                                        "source" to member.source,
                                        "position" to n,
                                        "parent" to member.parent,
                                        "destination" to member.destination,
                                        "error" to member.error,
                                        "skipped" to member.skipped,
                                        "cleanup" to member.cleanup.name,
                                        "recovery" to member.recovery,
                                    ),
                                    "job_id=? AND root=? AND source=?",
                                    arrayOf(job.id, plan.source, member.source),
                                )
                        }
                    }
                }
            }
        }
    }

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))

    private fun Cursor.optional(name: String): String? =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))

    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

    private fun <T> query(
        db: SQLiteDatabase,
        table: String,
        selection: String?,
        args: Array<String>?,
        transform: (Cursor) -> T,
    ): List<T> =
        db.query(table, null, selection, args, null, null, "position").use { cursor ->
            buildList { while (cursor.moveToNext()) add(transform(cursor)) }
        }

    private fun read(db: SQLiteDatabase): List<TransferJob> =
        query(db, "jobs", null, null) { row ->
            val id = row.string("id")
            val estimateRow =
                db.query("estimates", null, "job_id=?", arrayOf(id), null, null, null).use { e ->
                    if (!e.moveToFirst()) null
                    else
                        TransferEstimate(
                            e.long("known_bytes"),
                            e.int("files"),
                            e.int("folders"),
                            e.int("unknown_files"),
                            e.int("unreadable"),
                            e.int("limited") == 1,
                        ) to
                            e.getColumnIndexOrThrow("available").let {
                                if (e.isNull(it)) null else e.getLong(it)
                            }
                }
            TransferJob(
                id = id,
                estimate = estimateRow?.first,
                availableAtStart = estimateRow?.second,
                destination = row.string("destination"),
                move = row.int("move") == 1,
                state = TransferState.valueOf(row.string("state")),
                bytes = row.long("bytes"),
                items = row.int("items"),
                current = row.string("current"),
                created = row.long("created"),
                started = row.long("started"),
                finished = row.long("finished"),
                inFlight = row.optional("in_flight"),
                conflictPolicy = ConflictPolicy.valueOf(row.string("policy")),
                verifyCopies = row.int("verify_copies") == 1,
                partialCopy = row.optional("partial_copy")?.let(::decodePartialCopy),
                reusedBytes = row.long("reused_bytes"),
                partialCopyNotice = row.optional("partial_copy_notice"),
                verifications = query(db, "copy_verifications", "job_id=?", arrayOf(id)) {
                    CopyVerificationResult(
                        root = it.string("root"), source = it.string("source"),
                        status = CopyVerificationStatus.valueOf(it.string("status")),
                        destination = it.optional("destination"), bytes = it.long("bytes"),
                        expectedSha256 = it.optional("expected"), actualSha256 = it.optional("actual"), error = it.optional("error"),
                    )
                },
                paths = query(db, "sources", "job_id=?", arrayOf(id)) { it.string("path") },
                results =
                    query(db, "results", "job_id=?", arrayOf(id)) {
                        TransferItemResult(
                            it.string("source"),
                            it.optional("destination"),
                            it.optional("error"),
                            it.int("skipped") == 1,
                        )
                    },
                mergePlans =
                    query(db, "merge_plans", "job_id=?", arrayOf(id)) { plan ->
                        val root = plan.string("source")
                        MergePlan(
                            root,
                            plan.string("destination"),
                            plan.string("stamp"),
                            query(db, "merge_members", "job_id=? AND root=?", arrayOf(id, root)) {
                                member ->
                                MergeMember(
                                    member.string("source"),
                                    member.string("parent"),
                                    member.optional("destination"),
                                    member.optional("error"),
                                    member.int("skipped") == 1,
                                    SourceCleanup.valueOf(member.string("cleanup")),
                                    member.optional("recovery"),
                                )
                            },
                            SourceCleanup.valueOf(plan.string("cleanup")),
                            plan.optional("recovery"),
                            plan.optional("error"),
                        )
                    },
            )
        }

    @Synchronized
    override fun preserveDamaged(): List<File> {
        runCatching { helper.close() }
        val suffix = ".damaged-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val database = context.getDatabasePath(name)
        val preserved =
            listOf(
                    database,
                    File(database.path + "-wal"),
                    File(database.path + "-shm"),
                    File(database.path + "-journal"),
                )
                .filter { it.exists() }
                .map { file ->
                    File(file.path + suffix).also { Files.move(file.toPath(), it.toPath()) }
                }
        snapshot = null
        return preserved + legacy.preserveDamaged()
    }

    @Synchronized
    override fun close() {
        helper.close()
        snapshot = null
    }
}
