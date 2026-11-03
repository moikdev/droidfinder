package dev.findroid

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real descriptors, identities, no-replace renames and fsync; every fixture is privately owned. */
class PartialCopyRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = AndroidPartialCopyStorage()
    private lateinit var root: File
    private lateinit var privateRoot: File
    private lateinit var source: File
    private lateinit var destination: File
    private lateinit var recovery: PartialCopyRecovery
    private val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun failure(work: () -> Unit): Exception {
        try { work() } catch (error: Exception) { return error }
        throw AssertionError("An unsafe saved copy was accepted")
    }

    @Before fun setup() {
        root = File(context.cacheDir, "partial-native-${UUID.randomUUID()}").canonicalFile.apply { check(mkdirs()) }
        privateRoot = File(root, "private").apply { check(mkdir()) }
        source = File(root, "Original.bin").apply { writeBytes(bytes) }
        destination = File(root, "Out").apply { check(mkdir()) }
        recovery = PartialCopyRecovery(storage, privateRoot.path)
    }
    @After fun cleanup() { if (::root.isInitialized) check(root.deleteRecursively()) }

    private fun saved(verify: Boolean = false, complete: Boolean = false): PartialCopyRecord {
        val record = recovery.allocate(source.path, File(destination, source.name).path, verify)
        val prefix = if (complete) bytes else bytes.copyOf(96 * 1024)
        storage.open(record.source).use { input -> storage.open(record.staging, writable = true).use { output ->
            var written = 0
            while (written < prefix.size) written += output.write(prefix, written, prefix.size - written)
            return recovery.checkpoint(record, input, output, prefix.size.toLong(), hash(prefix))
        } }
    }

    @Test fun opensRealRegularDescriptorsAndSyncsPrivateDirectories() {
        assertEquals("source resolved path", source.path, storage.canonical(source.path))
        assertEquals("destination resolved path", destination.path, storage.canonical(destination.path))
        assertEquals("private resolved path", privateRoot.path, storage.canonical(privateRoot.path))
        val record = saved()
        assertTrue(record.sourceStamp.regular); assertTrue(record.destinationParentStamp.directory)
        assertTrue(record.sourceStamp.inode > 0); assertEquals(1L, record.stagingStamp.links)
        assertTrue(File(record.staging).isFile); assertFalse(File(record.target).exists())
        assertEquals(record.offset, record.stagingStamp.size)
        storage.open(record.staging).use { assertEquals(record.stagingStamp, it.stamp()) }
    }

    @Test fun aFinalSymlinkCannotBeOpenedAsASourceOrWorkingFile() {
        val link = File(root, "link"); Os.symlink(source.path, link.path)
        failure { storage.open(link.path) }
        failure { recovery.allocate(link.path, File(destination, "link").path, false) }
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun directoryAndFifoTypesAreRejectedWithoutBlocking() {
        failure { storage.open(destination.path) }
        val fifo = File(root, "pipe"); Os.mkfifo(fifo.path, 0b110000000)
        val started = android.os.SystemClock.elapsedRealtime()
        failure { storage.open(fifo.path) }
        assertTrue(android.os.SystemClock.elapsedRealtime() - started < 2_000)
    }

    @Test fun createNewNeverTruncatesACollidingFile() {
        failure { storage.open(source.path, writable = true, createNew = true) }
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun descriptorIdentityDoesNotFollowAReplacedPath() {
        storage.open(source.path).use { opened ->
            val original = opened.stamp()
            val held = File(root, "Held.bin"); assertTrue(source.renameTo(held)); source.writeBytes(bytes)
            assertTrue(original.sameObject(opened.stamp()))
            assertFalse(original.sameObject(storage.stat(source.path)!!))
        }
    }

    @Test fun realResumeReadsBackAndPublishesOnlyTheRemainingBytes() {
        val record = saved(); val approval = recovery.review(record); var latest: PartialCopyRecord? = record; var reused = 0L
        val control = TransferControl()
        val session = PartialCopySession(recovery, storage, approval) { checkpoint, _, count -> latest = checkpoint; reused = count }
        assertEquals(record.target, session.copy(source, File(record.target), control, null))
        assertTrue(session.completed); assertTrue(latest!!.publishing)
        assertEquals(record.offset, reused); assertEquals(bytes.size - record.offset, control.snapshot.bytes)
        assertArrayEquals(bytes, File(record.target).readBytes()); assertArrayEquals(bytes, source.readBytes())
        assertFalse(File(record.staging).exists()); assertTrue(privateRoot.listFiles()!!.isEmpty())
    }

    @Test fun resumedOptionalVerificationRecordsTheFullPublishedCopy() {
        val record = saved(verify = true); val checks = CopyVerificationSession(record.source)
        val session = PartialCopySession(recovery, storage, recovery.review(record)) { _, _, _ -> }
        session.copy(source, File(record.target), TransferControl(), checks)
        assertEquals(CopyVerificationStatus.VERIFIED, checks.results.single().status)
        assertEquals(bytes.size.toLong(), checks.results.single().bytes)
        assertEquals(record.target, checks.results.single().destination)
        assertEquals(hash(bytes), checks.results.single().actualSha256)
    }

    @Test fun aRealUncheckpointedTailIsReviewedThenRecopied() {
        val record = saved(); File(record.staging).appendBytes(ByteArray(17) { 3 })
        val approval = recovery.review(record); assertEquals(17L, approval.discardedTailBytes)
        PartialCopySession(recovery, storage, approval) { _, _, _ -> }.copy(source, File(record.target), TransferControl(), null)
        assertArrayEquals(bytes, File(record.target).readBytes())
    }

    @Test fun changedSourceCannotReuseAnOldReview() {
        val record = saved(); val approval = recovery.review(record)
        source.writeBytes(bytes.copyOf().also { it[0] = 91 })
        failure { recovery.recheck(approval, TransferControl()) }
        assertTrue(File(record.staging).exists()); assertFalse(File(record.target).exists())
    }

    @Test fun replacedSourceWithTheSameBytesCannotResume() {
        val record = saved(); assertTrue(source.renameTo(File(root, "Held.bin"))); source.writeBytes(bytes)
        failure { recovery.review(record) }; assertTrue(File(record.staging).exists())
    }

    @Test fun corruptedPrefixCannotResumeEvenAtTheSameLength() {
        val record = saved(); java.io.RandomAccessFile(record.staging, "rw").use { it.write(7) }
        failure { recovery.review(record) }; assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun kernelNoReplaceRenameKeepsBothFilesOnCollision() {
        val keep = File(destination, "Keep.bin").apply { writeText("Keep destination") }
        failure { NativeFilePublication.moveNoReplace(source.path, keep.path) }
        assertArrayEquals(bytes, source.readBytes()); assertEquals("Keep destination", keep.readText())
    }

    @Test fun aNewDestinationCannotBeOverwrittenByAnApprovedResume() {
        val record = saved(); val approval = recovery.review(record); val keep = "Keep destination".toByteArray()
        File(record.target).writeBytes(keep)
        failure { PartialCopySession(recovery, storage, approval) { _, _, _ -> }.copy(source, File(record.target), TransferControl(), null) }
        assertArrayEquals(keep, File(record.target).readBytes()); assertTrue(File(record.staging).exists())
    }

    @Test fun cancellingAReviewKeepsAllPrivateBytes() {
        val record = saved(); val before = File(record.staging).readBytes(); val control = TransferControl().apply { cancel() }
        failure { recovery.review(record, control) }
        assertArrayEquals(before, File(record.staging).readBytes()); assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun reviewedPublicationCleanupKeepsTheRenamedDestination() {
        val record = saved(complete = true).copy(publishing = true); NativeFilePublication.moveNoReplace(record.staging, record.target)
        val review = recovery.reviewCleanup(record); assertTrue(review.destinationMatches)
        failure { recovery.review(record) }; recovery.discardReviewed(review)
        assertFalse(File(record.staging).exists()); assertArrayEquals(bytes, File(record.target).readBytes())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun staleCleanupApprovalCannotDeleteAReplacedPrivateFile() {
        val record = saved(); val approval = recovery.reviewCleanup(record)
        val held = File(root, "Saved.bin"); assertTrue(File(record.staging).renameTo(held))
        File(record.staging).writeText("Keep replacement")
        failure { recovery.discardReviewed(approval) }
        assertEquals("Keep replacement", File(record.staging).readText()); assertTrue(held.isFile)
    }

    @Test fun noReplacePublicationPreservesAnExistingDestination() {
        val record = saved(complete = true)
        storage.open(record.source).use { input -> storage.open(record.staging, writable = true).use { output ->
            val publishing = recovery.beforePublication(record, input, output)
            File(record.target).writeText("Keep collision")
            failure { recovery.publish(publishing, input, output) }
        } }
        assertEquals("Keep collision", File(record.target).readText()); assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun nativePublicationPreservesNonBmpUnicodeFilenames() {
        val target = File(destination, "旅行 Café 📁.bin")
        val record = recovery.allocate(source.path, target.path, false)
        storage.open(record.source).use { input -> storage.open(record.staging, writable = true).use { stage ->
            var written = 0
            while (written < bytes.size) written += stage.write(bytes, written, bytes.size - written)
            val complete = recovery.checkpoint(record, input, stage, bytes.size.toLong(), hash(bytes))
            assertEquals(target.path, recovery.publish(recovery.beforePublication(complete, input, stage), input, stage))
        } }
        assertArrayEquals(bytes, target.readBytes()); assertArrayEquals(bytes, source.readBytes())
    }

}
