package dev.findroid

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CopyVerificationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abc = "abc".toByteArray()
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun failure(work: () -> Unit): Exception {
        try { work() } catch (error: Exception) { return error }
        throw AssertionError("The unsafe copy was accepted")
    }
    private fun ready(bytes: ByteArray = abc): CopyVerificationSession = CopyVerificationSession("root").also {
        it.begin("source").update(bytes)
    }
    private fun job(results: List<CopyVerificationResult> = emptyList()) = TransferJob(
        paths = listOf("source"), destination = "destination", move = false,
        verifyCopies = true, verifications = results,
    )

    @Test fun verifiesTheKnownSha256VectorWithoutInflatingCopiedBytes() {
        val control = TransferControl()
        control.bytes(abc.size)
        val session = ready()
        session.verify("source", 3, digest(abc), control) { ByteArrayInputStream(abc) }
        val record = session.results.single()
        assertEquals(CopyVerificationStatus.VERIFIED, record.status)
        assertEquals(abcHash, record.expectedSha256)
        assertEquals(abcHash, record.actualSha256)
        assertEquals(3L, control.snapshot.bytes)
        assertEquals(3L, control.snapshot.phaseBytes)
        assertEquals(TransferStage.VERIFYING, control.snapshot.stage)
        assertNull(record.destination)
        session.published(mapOf("source" to "published"))
        assertEquals("published", session.results.single().destination)
    }

    @Test fun detectsSameLengthCorruption() {
        val session = ready()
        assertTrue(failure { session.verify("source", 3, digest(abc), TransferControl()) { "abd".byteInputStream() } } is IOException)
        assertEquals(CopyVerificationStatus.MISMATCH, session.results.single().status)
        assertNotEquals(session.results.single().expectedSha256, session.results.single().actualSha256)
        assertNull(session.results.single().destination)
    }

    @Test fun detectsShortReadback() {
        val session = ready()
        failure { session.verify("source", 3, digest(abc), TransferControl()) { "ab".byteInputStream() } }
        assertEquals(CopyVerificationStatus.MISMATCH, session.results.single().status)
    }

    @Test fun boundsReadbackWhenTheDestinationKeepsGrowing() {
        var reads = 0
        val session = ready()
        failure { session.verify("source", 3, digest(abc), TransferControl()) {
            object : InputStream() {
                override fun read() = 1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++; java.util.Arrays.fill(buffer, offset, offset + length, 1.toByte()); return length
                }
            }
        } }
        assertEquals(1, reads)
        assertEquals(CopyVerificationStatus.MISMATCH, session.results.single().status)
    }

    @Test fun verifiesEmptyFiles() {
        val session = ready(byteArrayOf())
        session.verify("source", 0, digest(byteArrayOf()), TransferControl()) { byteArrayOf().inputStream() }
        assertEquals(CopyVerificationStatus.VERIFIED, session.results.single().status)
        assertEquals(0L, session.results.single().bytes)
    }

    @Test fun unreadableDestinationNeverClaimsVerification() {
        val session = ready()
        failure { session.verify("source", 3, digest(abc), TransferControl(), true) { throw IOException("read denied") } }
        assertEquals(CopyVerificationStatus.UNREADABLE, session.results.single().status)
        assertNull(session.results.single().actualSha256)
        assertNull(session.results.single().destination)
    }

    @Test fun partialReadFailureIsUnreadableEvenAfterMatchingPrefix() {
        val session = ready()
        failure { session.verify("source", 3, digest(abc), TransferControl()) {
            object : ByteArrayInputStream(abc) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (pos > 0) throw IOException("provider disconnected")
                    return super.read(buffer, offset, 1)
                }
            }
        } }
        assertEquals(CopyVerificationStatus.UNREADABLE, session.results.single().status)
    }

    @Test fun cancellationClosesAnActualBlockedReadbackStream() {
        val entered = CountDownLatch(1); val closed = CountDownLatch(1)
        val control = TransferControl(); val session = ready(); val error = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                session.verify("source", 3, digest(abc), control, true) {
                    object : InputStream() {
                        override fun read(): Int { entered.countDown(); assertTrue(closed.await(5, TimeUnit.SECONDS)); throw IOException("closed") }
                        override fun close() { closed.countDown() }
                    }
                }
            } catch (caught: Throwable) { error.set(caught) }
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); control.cancel(); thread.join(5000)
            assertFalse(thread.isAlive)
            assertTrue(error.get() is CancellationException)
            assertEquals(CopyVerificationStatus.INCOMPLETE, session.results.single().status)
            assertNull(session.results.single().destination)
        } finally { control.cancel(); closed.countDown(); thread.join(5000) }
    }

    @Test fun explicitPauseStopsReadbackUntilResumed() {
        val control = TransferControl(); control.pause()
        val session = ready(); val opened = CountDownLatch(1); val error = AtomicReference<Throwable>()
        val thread = Thread {
            try { session.verify("source", 3, digest(abc), control) { opened.countDown(); abc.inputStream() } }
            catch (caught: Throwable) { error.set(caught) }
        }
        thread.start()
        try {
            assertFalse(opened.await(100, TimeUnit.MILLISECONDS)); assertTrue(control.paused)
            control.resume(); thread.join(5000)
            assertFalse(thread.isAlive); assertNull(error.get())
            assertEquals(CopyVerificationStatus.VERIFIED, session.results.single().status)
        } finally { control.cancel(); thread.join(5000) }
    }

    @Test fun verifiedLocalCopyPublishesTheMatchingBytesAndFinalResultPath() {
        val source = temporary.newFile("important.txt").apply { writeBytes(abc) }
        val destination = temporary.newFolder("destination")
        val session = CopyVerificationSession(source.path); val control = TransferControl()
        val copied = FileEngine(temporary.newFolder("state")).copy(source, destination, control = control, verification = session)
        assertArrayEquals(abc, copied.readBytes()); assertArrayEquals(abc, source.readBytes())
        assertEquals(3L, control.snapshot.bytes)
        assertEquals(copied.absolutePath, session.results.single().destination)
        assertFalse(destination.listFiles()!!.any { it.name.startsWith(".findroid-") })
    }

    @Test fun recursivelyChecksEveryFileAndKeepsEmptyDirectories() {
        val source = temporary.newFolder("folder")
        File(source, "empty").mkdir()
        File(source, "nested").mkdir()
        File(source, "a.txt").writeText("abc")
        File(source, "nested/b.txt").writeText("def")
        val destination = temporary.newFolder("target"); val session = CopyVerificationSession(source.path)
        val copied = FileEngine(temporary.newFolder("state")).copy(source, destination, verification = session)
        assertTrue(File(copied, "empty").isDirectory)
        assertEquals(2, session.results.size)
        assertEquals(setOf(File(copied, "a.txt").path, File(copied, "nested/b.txt").path), session.results.map { it.destination }.toSet())
        assertTrue(session.results.all { it.status == CopyVerificationStatus.VERIFIED && it.expectedSha256 == it.actualSha256 })
    }

    private fun failedLocalCopy(change: (File, File, TransferControl) -> Unit): Triple<File, File, CopyVerificationSession> {
        val source = temporary.newFile("source.txt").apply { writeBytes(abc) }
        val destination = temporary.newFolder("destination"); val session = CopyVerificationSession(source.path)
        var changed = false
        lateinit var control: TransferControl
        control = TransferControl { progress ->
            if (!changed && progress.stage == TransferStage.VERIFYING) {
                changed = true; change(source, destination.listFiles()!!.single(), control)
            }
        }
        failure { FileEngine(temporary.newFolder("state")).copy(source, destination, control = control, verification = session) }
        assertTrue(changed); assertTrue(source.exists()); assertTrue(destination.listFiles().isNullOrEmpty())
        assertNull(session.results.single().destination)
        return Triple(source, destination, session)
    }

    @Test fun localSameLengthCorruptionIsRemovedBeforePublication() {
        val (source, _, session) = failedLocalCopy { _, staging, _ -> staging.writeText("abd") }
        assertArrayEquals(abc, source.readBytes()); assertEquals(CopyVerificationStatus.MISMATCH, session.results.single().status)
    }

    @Test fun localReadbackOpenFailureRemovesOnlyUnpublishedContents() {
        val (source, _, session) = failedLocalCopy { _, staging, _ -> assertTrue(staging.delete()) }
        assertArrayEquals(abc, source.readBytes()); assertEquals(CopyVerificationStatus.UNREADABLE, session.results.single().status)
    }

    @Test fun cancellationBeforeReadbackKeepsTheOriginalAndCleansStaging() {
        val (source, _, session) = failedLocalCopy { _, _, control -> control.cancel() }
        assertArrayEquals(abc, source.readBytes()); assertEquals(CopyVerificationStatus.INCOMPLETE, session.results.single().status)
    }

    @Test fun sourceChangesDuringVerificationPreventPublication() {
        val (source, _, session) = failedLocalCopy { original, _, _ ->
            original.writeText("new"); original.setLastModified(original.lastModified() + 2000)
        }
        assertEquals("new", source.readText())
        assertEquals(CopyVerificationStatus.VERIFIED, session.results.single().status)
        assertNull(session.results.single().destination)
    }

    @Test fun boundsResultsWithoutSilentlyDroppingFiles() {
        val session = CopyVerificationSession("root", 1)
        session.begin("one")
        assertTrue(failure { session.begin("two") }.message!!.contains("Split this selection"))
        assertEquals(listOf("one"), session.results.map { it.source })
    }

    @Test fun refusesRepeatedSourceIdentities() {
        val session = CopyVerificationSession("root"); session.begin("one")
        failure { session.begin("one") }
        assertEquals(1, session.results.size)
    }

    @Test fun legacyJournalRoundTripsTheChoiceAndPerFileResults() {
        val record = CopyVerificationResult("source", "source", CopyVerificationStatus.VERIFIED, "target", 3, abcHash, abcHash)
        val journal = TransferJournal(File(temporary.root, "history")); val original = job(listOf(record))
        journal.save(listOf(original)); assertEquals(listOf(original), journal.load())
    }

    @Test fun preVerificationLegacyRecordsDefaultToNotRequested() {
        val file = File(temporary.root, "history"); val journal = TransferJournal(file)
        val original = job().copy(verifyCopies = false)
        journal.save(listOf(original))
        val properties = Properties().apply { file.inputStream().use(::load) }
        properties.remove("0.verifyCopies"); properties.remove("0.verifications")
        file.outputStream().use { properties.store(it, null) }
        assertEquals(listOf(original), journal.load()); assertEquals("Not requested", copyVerificationSummary(journal.load().single()))
    }

    @Test fun restartRetainsVerificationChoiceButDoesNotReplayTheJob() {
        val restored = job().copy(state = TransferState.RUNNING).afterRestart()
        assertEquals(TransferState.INTERRUPTED, restored.state); assertTrue(restored.verifyCopies)
    }

    @Test fun invalidStoredVerifiedClaimsAreRejected() {
        val bad = CopyVerificationResult("source", "source", CopyVerificationStatus.VERIFIED, "target", 3, abcHash, "0".repeat(64))
        failure { TransferJournal(File(temporary.root, "history")).save(listOf(job(listOf(bad)))) }
        failure { validateCopyVerification(job().copy(move = true)) }
        failure { validateCopyVerification(job(listOf(bad)).copy(verifyCopies = false)) }
    }

    @Test fun summarySeparatesUnpublishedVerifiedBytesFromSuccessfulCopies() {
        val checked = CopyVerificationResult("source", "source", CopyVerificationStatus.VERIFIED, null, 3, abcHash, abcHash)
        val summary = copyVerificationSummary(job(listOf(checked)).copy(state = TransferState.FAILED))
        assertTrue(summary.contains("0 verified and copied")); assertTrue(summary.contains("1 verified but not published"))
    }
    @Test fun aFailedVerificationCheckpointPreventsPublicationAndKeepsTheSource() {
        val source = temporary.newFile("important.txt").apply { writeBytes(abc) }
        val destination = temporary.newFolder("destination")
        val session = CopyVerificationSession(source.path) { records ->
            if (records.any { it.status == CopyVerificationStatus.VERIFIED }) throw IOException("Journal unavailable")
        }
        val error = failure { FileEngine(temporary.newFolder("state")).copy(source, destination, verification = session) }
        assertEquals("Journal unavailable", error.message)
        assertArrayEquals(abc, source.readBytes()); assertTrue(destination.listFiles().isNullOrEmpty())
        assertEquals(CopyVerificationStatus.VERIFIED, session.results.single().status)
        assertNull(session.results.single().destination)
    }

    @Test fun completedReadbackIsCheckpointedBeforeAnyPublishedDestinationIsReported() {
        val source = temporary.newFile("important.txt").apply { writeBytes(abc) }
        val destination = temporary.newFolder("destination")
        val records = mutableListOf<List<CopyVerificationResult>>()
        val session = CopyVerificationSession(source.path) { records += it }
        val published = FileEngine(temporary.newFolder("state")).copy(source, destination, verification = session)
        assertTrue(records.isNotEmpty())
        assertTrue(records.flatten().all { it.destination == null })
        assertEquals(CopyVerificationStatus.VERIFIED, records.last().single().status)
        assertEquals(published.path, session.results.single().destination)
    }

}
