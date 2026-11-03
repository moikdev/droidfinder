package dev.findroid

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

    private fun restoreOldSchema(name: String, version: Int) {
        connection(name).use { db ->
            db.execSQL("DROP TABLE copy_verifications")
            if (version == 1) db.execSQL("DROP TABLE estimates")
            val columns = "id,position,destination,move,state,bytes,items,current,created,started,finished,in_flight,policy"
            db.execSQL("CREATE TABLE legacy_jobs (id TEXT PRIMARY KEY, position INTEGER NOT NULL, destination TEXT NOT NULL, move INTEGER NOT NULL, state TEXT NOT NULL, bytes INTEGER NOT NULL, items INTEGER NOT NULL, current TEXT NOT NULL, created INTEGER NOT NULL, started INTEGER NOT NULL, finished INTEGER NOT NULL, in_flight TEXT, policy TEXT NOT NULL)")
            db.execSQL("INSERT INTO legacy_jobs ($columns) SELECT $columns FROM jobs")
            db.execSQL("DROP TABLE jobs")
            db.execSQL("ALTER TABLE legacy_jobs RENAME TO jobs")
            db.execSQL("CREATE INDEX job_order ON jobs(position)")
            db.version = version
        }
    }

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

    @Test
    fun upgradesVersionOneWithoutChangingJobsAndRoundTripsNullableEstimates() =
        exercise { store, name, legacy ->
            val job =
                TransferJob(
                    paths = listOf("/source"),
                    destination = "/out",
                    move = false,
                    mergePlans =
                        listOf(
                            MergePlan(
                                "/source",
                                "/out/source",
                                "stamp",
                                listOf(MergeMember("/source/file", "/out/source")),
                            ),
                        ),
                )
            store.save(listOf(job))
            store.close()
            restoreOldSchema(name, 1)
            SqliteTransferStore(context, name, legacy).use { upgraded ->
                assertEquals(listOf(job), upgraded.load())
                val estimated =
                    job.copy(
                        estimate = TransferEstimate(20, 3, 1, unknownFiles = 1),
                        availableAtStart = 100,
                    )
                upgraded.save(listOf(estimated))
                assertEquals(listOf(estimated), upgraded.load())
                upgraded.save(listOf(estimated.copy(availableAtStart = null)))
                assertNull(upgraded.load().single().availableAtStart)
                upgraded.save(emptyList())
                connection(name).use { assertEquals(0, count(it, "estimates")) }
            }
        }
    @Test fun upgradesVersionTwoWithoutInventingVerificationForOldCopies() = exercise { store, name, legacy ->
        val old = TransferJob(paths = listOf("/old"), destination = "/out", move = false,
            state = TransferState.COMPLETED, results = listOf(TransferItemResult("/old", "/out/old")),
            estimate = TransferEstimate(3, 1, 0), availableAtStart = 500)
        store.save(listOf(old)); store.close(); restoreOldSchema(name, 2)
        SqliteTransferStore(context, name, legacy).use { upgraded ->
            assertEquals(listOf(old), upgraded.load())
            assertFalse(upgraded.load().single().verifyCopies)
            assertEquals("Not requested", copyVerificationSummary(upgraded.load().single()))
        }
        connection(name).use { assertEquals(3, it.version) }
    }

    @Test fun roundTripsVerificationChoiceAndIndividualResultsWithoutRewritingUnchangedRows() = exercise { store, name, legacy ->
        val digest = "a".repeat(64)
        val result = CopyVerificationResult("/root", "/root/a.txt", CopyVerificationStatus.VERIFIED, "/out/a.txt", 3, digest, digest)
        val job = TransferJob(paths = listOf("/root"), destination = "/out", move = false,
            verifyCopies = true, verifications = listOf(result))
        store.save(listOf(job))
        connection(name).use { db ->
            db.execSQL("CREATE TABLE verification_audit (writes INTEGER NOT NULL)")
            db.execSQL("INSERT INTO verification_audit VALUES (0)")
            db.execSQL("CREATE TRIGGER verification_writes AFTER UPDATE ON copy_verifications BEGIN UPDATE verification_audit SET writes=writes+1; END")
            store.save(listOf(job.copy(bytes = 99, state = TransferState.RUNNING)))
            db.rawQuery("SELECT writes FROM verification_audit", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        store.close()
        SqliteTransferStore(context, name, legacy).use { reopened ->
            val loaded = reopened.load().single()
            assertTrue(loaded.verifyCopies); assertEquals(listOf(result), loaded.verifications)
            assertTrue(loaded.afterRestart().verifyCopies)
            reopened.save(emptyList())
        }
        connection(name).use { assertEquals(0, count(it, "copy_verifications")) }
    }

    @Test fun verificationRowFailureRollsBackTheWholeCheckpoint() = exercise { store, name, _ ->
        val job = TransferJob(paths = listOf("/root"), destination = "/out", move = false, verifyCopies = true)
        store.save(listOf(job))
        connection(name).use { db ->
            db.execSQL("CREATE TRIGGER reject_verification BEFORE INSERT ON copy_verifications BEGIN SELECT RAISE(ABORT,'injected verification failure'); END")
            val changed = job.copy(bytes = 3, verifications = listOf(CopyVerificationResult("/root", "/root/a.txt")))
            assertTrue(runCatching { store.save(listOf(changed)) }.isFailure)
            assertEquals(listOf(job), store.load()); assertEquals(0, count(db, "copy_verifications"))
            db.execSQL("DROP TRIGGER reject_verification")
            store.save(listOf(changed)); assertEquals(listOf(changed), store.load())
        }
    }

    @Test fun importsVerifiedLegacyRecordsWithoutChangingTheLegacyBytes() = exercise { store, _, legacy ->
        val job = TransferJob(paths = listOf("/root"), destination = "/out", move = false, verifyCopies = true,
            verifications = listOf(CopyVerificationResult("/root", "/root/a.txt", CopyVerificationStatus.UNREADABLE,
                bytes = 3, error = "Readback denied")))
        TransferJournal(legacy).save(listOf(job)); val original = legacy.readBytes()
        assertEquals(listOf(job), store.load()); assertArrayEquals(original, legacy.readBytes())
    }

}
