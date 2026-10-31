package dev.droidfinder

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TransferQueueTest {
    @Test
    fun journalRoundTripsUnicodePathsResultsAndOrdering() {
        val dir = Files.createTempDirectory("queue").toFile()
        try {
            val store = TransferJournal(File(dir, "queue.properties"))
            val jobs =
                listOf(
                    TransferJob(
                        paths = listOf("/旅行/写真=1.jpg", "content://cloud/a:b"),
                        destination = "/USB/Target",
                        move = true,
                        state = TransferState.RUNNING,
                        results =
                            listOf(TransferItemResult("/旅行/写真=1.jpg", "/USB/Target/写真=1.jpg")),
                        bytes = 234,
                        items = 2,
                        inFlight = "content://cloud/a:b",
                    ),
                    TransferJob(
                        paths = listOf("/failed"),
                        destination = "/to",
                        move = false,
                        state = TransferState.FAILED,
                        results =
                            listOf(
                                TransferItemResult(
                                    "/failed",
                                    error = "Provider disconnected\nReconnect",
                                ),
                            ),
                    ),
                )
            store.save(jobs)
            assertEquals(jobs, store.load())
            assertEquals(listOf("content://cloud/a:b"), store.load().first().remaining)
            assertEquals(TransferState.INTERRUPTED, store.load().first().afterRestart().state)
            assertEquals(TransferState.FAILED, store.load().last().afterRestart().state)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptPrimaryFallsBackToLastValidJournalWithoutReplayingWork() {
        val dir = Files.createTempDirectory("queue-backup").toFile()
        try {
            val file = File(dir, "queue.properties")
            val store = TransferJournal(file)
            val job = TransferJob(paths = listOf("/source"), destination = "/target", move = false)
            store.save(listOf(job))
            store.save(listOf(job.copy(state = TransferState.RUNNING)))
            file.writeText("corrupt")
            assertEquals(TransferState.INTERRUPTED, store.load().single().afterRestart().state)
            File(file.path + ".bak").writeText("corrupt")
            assertTrue(runCatching { store.load() }.isFailure)
            assertEquals("corrupt", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun publishedButIncompleteMoveIsNotBlindlyRetried() {
        val job =
            TransferJob(
                paths = listOf("/partial", "/missing", "/done"),
                destination = "/out",
                move = true,
                state = TransferState.FAILED,
                results =
                    listOf(
                        TransferItemResult("/partial", "/out/partial", "Source removal failed"),
                        TransferItemResult("/done", "/out/done"),
                    ),
            )
        assertEquals(listOf("/missing"), job.remaining)
        assertEquals(1, job.successful.size)
    }

    @Test
    fun pauseStopsAtBoundaryResumeContinuesAndCancelWakesPausedWorker() {
        val control = TransferControl()
        control.pause()
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            control.check()
            done.countDown()
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(done.await(80, TimeUnit.MILLISECONDS))
        control.resume()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        worker.join(2000)
        val cancelled = CountDownLatch(1)
        control.pause()
        val blocked = Thread {
            try {
                control.check()
            } catch (_: CancellationException) {
                cancelled.countDown()
            }
        }
        blocked.start()
        control.cancel()
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        blocked.join(2000)
    }

    @Test
    fun damagedJournalResetPreservesEvidenceAndBackupRecoveryDoesNotOverwriteValidBackup() {
        val dir = Files.createTempDirectory("queue-recovery").toFile()
        try {
            val file = File(dir, "queue.properties")
            val store = TransferJournal(file)
            val job = TransferJob(paths = listOf("/source"), destination = "/out", move = false)
            store.save(listOf(job))
            store.save(listOf(job.copy(state = TransferState.RUNNING)))
            file.writeText("damaged")
            val recovered = store.load()
            store.save(recovered)
            assertEquals(recovered, store.load())
            file.writeText("damaged again")
            File(file.path + ".bak").writeText("bad backup")
            val copies = store.preserveDamaged()
            assertEquals(setOf("damaged again", "bad backup"), copies.map { it.readText() }.toSet())
            store.save(emptyList())
            assertTrue(store.load().isEmpty())
            assertTrue(copies.all { it.exists() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun explicitCopyNamesPreserveExistingFilesAndKeepMovesWithinFolderSafe() {
        val dir = Files.createTempDirectory("copy-names").toFile()
        try {
            val engine = FileEngine(File(dir, "state"))
            val folder = File(dir, "files").apply { mkdir() }
            val source = File(folder, "Original.txt").apply { writeText("content") }
            val copied = engine.copy(source, folder, requestedName = "Chosen.txt")
            assertEquals("Chosen.txt", copied.name)
            assertEquals("content", copied.readText())
            assertTrue(
                runCatching { engine.copy(source, folder, requestedName = "Chosen.txt") }.isFailure,
            )
            val moved = engine.copy(source, folder, move = true, requestedName = "Renamed.txt")
            assertFalse(source.exists())
            assertEquals("content", moved.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mergeRetriesKeepDestinationParentsAndExcludePublishedSkippedAndAmbiguousUnits() {
        val members =
            listOf(
                MergeMember("/root/done", "/out/root", destination = "/out/root/done"),
                MergeMember("/root/skipped", "/out/root", skipped = true),
                MergeMember(
                    "/root/shared/missing",
                    "/out/root/shared",
                    error = "Disconnected",
                    cleanup = SourceCleanup.KEPT,
                ),
                MergeMember("/root/ambiguous", "/out/root"),
            )
        val job =
            TransferJob(
                paths = listOf("/root", "/other"),
                destination = "/out",
                move = true,
                inFlight = "/root/ambiguous",
                mergePlans = listOf(MergePlan("/root", "/out/root", "stamp", members)),
            )
        assertEquals(
            listOf(
                TransferRetryTarget("/other", "/out"),
                TransferRetryTarget("/root/shared/missing", "/out/root/shared"),
            ),
            job.afterRestart().retryTargets,
        )
        assertTrue(
            job.copy(paths = listOf("/other"), mergePlans = emptyList(), inFlight = "/other")
                .retryTargets
                .isEmpty(),
        )
    }

    @Test
    fun cancelClosesAGuardedVerificationStreamToUnblockReads() {
        val control = TransferControl()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val stream =
            object : java.io.InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    check(closed.await(3, TimeUnit.SECONDS))
                    throw java.io.IOException("Closed")
                }

                override fun close() {
                    closed.countDown()
                }
            }
        val thread = Thread {
            try {
                control.guard(stream) { stream.read() }
            } catch (_: CancellationException) {
                done.countDown()
            }
        }
            .apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        control.cancel()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        thread.join(2000)
    }
}
