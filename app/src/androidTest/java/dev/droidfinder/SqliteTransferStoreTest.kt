package dev.droidfinder

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SqliteTransferStoreTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun exercise(block: (SqliteTransferStore, String, File) -> Unit) {
        val id = "transfer-test-${System.nanoTime()}"
        val name = "$id.db"
        val folder = File(context.filesDir, id).apply { mkdirs() }
        val legacy = File(folder, "legacy.properties")
        val store = SqliteTransferStore(context, name, legacy)
        try {
            block(store, name, legacy)
        } finally {
            store.close()
            context.deleteDatabase(name)
            folder.deleteRecursively()
        }
    }

    private fun connection(name: String) =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )

    private fun count(db: SQLiteDatabase, table: String, where: String = "1"): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun migratesLegacyOnceAndRetainsTheOriginalFile() = exercise { store, name, legacy ->
        val job =
            TransferJob(
                paths = listOf("/旅行/写真.jpg", "content://provider/item"),
                destination = "/out",
                move = true,
                state = TransferState.INTERRUPTED,
                results = listOf(TransferItemResult("/旅行/写真.jpg", "/out/写真.jpg")),
                bytes = 321,
                inFlight = "content://provider/item",
            )
        TransferJournal(legacy).save(listOf(job))
        val original = legacy.readBytes()
        assertEquals(listOf(job), store.load())
        assertArrayEquals(original, legacy.readBytes())
        val second = TransferJob(paths = listOf("/second"), destination = "/out", move = false)
        store.save(listOf(second, job.copy(current = "Restored")))
        store.close()
        TransferJournal(legacy).save(emptyList())
        SqliteTransferStore(context, name, legacy).use { reopened ->
            assertEquals(listOf(second, job.copy(current = "Restored")), reopened.load())
        }
        assertTrue(legacy.exists())
    }

    @Test
    fun incrementalCheckpointsKeepUnchangedSourcesMembersAndChildRows() =
        exercise { store, name, _ ->
            val plan =
                MergePlan(
                    "/root",
                    "/out/root",
                    "stamp",
                    listOf(
                        MergeMember("/root/A.txt", "/out/root"),
                        MergeMember("/root/B.txt", "/out/root/Sub"),
                    ),
                )
            val job =
                TransferJob(
                    paths = listOf("/root"),
                    destination = "/out",
                    move = false,
                    mergePlans = listOf(plan),
                )
            store.save(listOf(job))
            connection(name).use { db ->
                db.execSQL("CREATE TABLE audit (kind TEXT PRIMARY KEY, writes INTEGER NOT NULL)")
                db.execSQL("INSERT INTO audit VALUES ('sources',0),('members',0)")
                db.execSQL(
                    "CREATE TRIGGER sources_insert AFTER INSERT ON sources BEGIN UPDATE audit SET writes=writes+1 WHERE kind='sources'; END",
                )
                db.execSQL(
                    "CREATE TRIGGER sources_delete AFTER DELETE ON sources BEGIN UPDATE audit SET writes=writes+1 WHERE kind='sources'; END",
                )
                db.execSQL(
                    "CREATE TRIGGER members_update AFTER UPDATE ON merge_members BEGIN UPDATE audit SET writes=writes+1 WHERE kind='members'; END",
                )
                val changed =
                    job.copy(
                        state = TransferState.RUNNING,
                        bytes = 5,
                        mergePlans =
                            listOf(
                                plan.copy(
                                    members =
                                        listOf(
                                            plan.members
                                                .first()
                                                .copy(destination = "/out/root/A.txt"),
                                            plan.members.last(),
                                        ),
                                ),
                            ),
                    )
                store.save(listOf(changed))
                assertEquals(listOf(changed), store.load())
                assertEquals(2, count(db, "merge_members"))
                assertEquals(1, count(db, "sources"))
                db.rawQuery("SELECT writes FROM audit WHERE kind='sources'", null).use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
                db.rawQuery("SELECT writes FROM audit WHERE kind='members'", null).use {
                    it.moveToFirst()
                    assertEquals(1, it.getInt(0))
                }
                store.save(emptyList())
                assertEquals(0, count(db, "merge_members"))
                assertEquals(0, count(db, "merge_plans"))
                assertEquals(0, count(db, "sources"))
            }
        }

    @Test
    fun failedTransactionRollsBackMetadataAndInvalidatesTheSnapshotCache() =
        exercise { store, name, _ ->
            val job =
                TransferJob(paths = listOf("/ok", "/fail"), destination = "/out", move = false)
            store.save(listOf(job))
            connection(name).use { db ->
                db.execSQL(
                    "CREATE TRIGGER reject_result BEFORE INSERT ON results WHEN NEW.source='/fail' BEGIN SELECT RAISE(ABORT,'injected failure'); END",
                )
                val changed =
                    job.copy(
                        state = TransferState.RUNNING,
                        bytes = 123,
                        results = listOf(TransferItemResult("/fail", "/out/fail")),
                    )
                assertTrue(runCatching { store.save(listOf(changed)) }.isFailure)
                assertEquals(listOf(job), store.load())
                db.execSQL("DROP TRIGGER reject_result")
                store.save(listOf(changed))
                assertEquals(listOf(changed), store.load())
            }
        }

    @Test
    fun malformedLegacyJournalDoesNotCommitTheMigrationMarker() = exercise { store, name, legacy ->
        legacy.writeText("damaged legacy")
        assertTrue(runCatching { store.load() }.isFailure)
        connection(name).use { db ->
            assertEquals(0, count(db, "meta"))
            assertEquals(0, count(db, "jobs"))
        }
        val preserved = store.preserveDamaged()
        assertTrue(preserved.any { it.readBytes().contentEquals("damaged legacy".toByteArray()) })
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun corruptDatabaseIsPreservedInsteadOfAutomaticallyDeleted() = exercise { store, name, _ ->
        store.save(
            listOf(TransferJob(paths = listOf("/source"), destination = "/out", move = false)),
        )
        store.close()
        val path = context.getDatabasePath(name)
        val damaged = "This is not a SQLite database".toByteArray()
        path.writeBytes(damaged)
        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue(path.exists())
        assertArrayEquals(damaged, path.readBytes())
        val preserved = store.preserveDamaged()
        assertTrue(preserved.any { it.readBytes().contentEquals(damaged) })
        assertTrue(store.load().isEmpty())
    }
}
