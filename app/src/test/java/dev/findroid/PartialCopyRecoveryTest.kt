package dev.findroid

import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class PartialCopyRecoveryTest {
    private val original = "abcdefgh".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun failure(work: () -> Unit): Exception {
        try { work() } catch (error: Exception) { return error }
        throw AssertionError("An unsafe saved copy was accepted")
    }

    private class MemoryStorage : PartialCopyStorage {
        data class Node(val inode: Long, val directory: Boolean, var bytes: ByteArray = byteArrayOf(), var modified: Long = 1, var changed: Long = 1)
        private var nextInode = 1L
        val nodes = linkedMapOf<String, Node>()
        val calls = mutableListOf<String>()
        var failLink = false
        var failSync: String? = null
        var failRemove: String? = null
        var onWrite: ((String) -> Unit)? = null
        var onLink: ((String) -> Unit)? = null
        var onRead: ((String) -> Unit)? = null
        var aliases = emptyMap<String, String>()
        init { folder("/private/partial"); folder("/source"); folder("/target") }
        fun folder(path: String) { nodes[path] = Node(nextInode++, true) }
        fun file(path: String, bytes: ByteArray) { nodes[path] = Node(nextInode++, false, bytes.copyOf()) }
        fun mutate(path: String, bytes: ByteArray, preserveTimes: Boolean = false) {
            val node = nodes.getValue(path); node.bytes = bytes.copyOf()
            if (!preserveTimes) { node.modified++; node.changed++ }
        }
        private fun stamp(node: Node) = PartialFileStamp(7, node.inode, node.bytes.size.toLong(), 1, node.modified,
            1, node.changed, !node.directory, node.directory, nodes.values.count { it === node }.toLong())
        override fun canonical(path: String) = aliases.entries
            .filter { path == it.key || path.startsWith(it.key + "/") }
            .maxByOrNull { it.key.length }?.let { it.value + path.removePrefix(it.key) } ?: path
        override fun stat(path: String) = nodes[path]?.let(::stamp)
        override fun createDirectory(path: String) { check(path !in nodes); calls += "mkdir:$path"; folder(path) }
        fun link(existing: String, target: String) {
            if (failLink) throw IOException("No safe hard links")
            check(target !in nodes)
            calls += "link:$target"; val node = nodes.getValue(existing); nodes[target] = node; node.changed++; onLink?.invoke(target)
        }
        override fun moveNoReplace(existing: String, target: String) {
            if (failLink) throw IOException("No safe no-replace rename")
            check(target !in nodes)
            val node = nodes.remove(existing) ?: error("Missing source")
            calls += "move:$target"; nodes[target] = node; node.changed++; onLink?.invoke(target)
        }
        override fun remove(path: String) {
            if (failRemove == path) throw IOException("Owned cleanup failed")
            val node = nodes.getValue(path)
            check(!node.directory || nodes.keys.none { it.startsWith("$path/") })
            calls += "remove:$path"; nodes.remove(path); node.changed++
        }
        override fun open(path: String, writable: Boolean, createNew: Boolean, directory: Boolean): PartialCopyHandle {
            if (createNew) { check(path !in nodes); file(path, byteArrayOf()) }
            val node = nodes[path] ?: throw IOException("Missing file")
            check(node.directory == directory)
            return object : PartialCopyHandle {
                var position = 0
                var closed = false
                override fun stamp(): PartialFileStamp { check(!closed); return this@MemoryStorage.stamp(node) }
                override fun seek(offset: Long) { check(!closed); position = Math.toIntExact(offset) }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    check(!closed && !node.directory)
                    if (position >= node.bytes.size) return -1
                    val n = minOf(count, node.bytes.size - position)
                    node.bytes.copyInto(buffer, offset, position, position + n); position += n
                    onRead?.invoke(path)
                    return n
                }
                override fun write(buffer: ByteArray, offset: Int, count: Int): Int {
                    check(!closed && writable && !node.directory)
                    node.bytes = node.bytes.copyOf(maxOf(node.bytes.size, position + count))
                    buffer.copyInto(node.bytes, position, offset, offset + count); position += count
                    node.modified++; node.changed++; onWrite?.invoke(path); return count
                }
                override fun truncate(size: Long) { check(writable); node.bytes = node.bytes.copyOf(Math.toIntExact(size)); node.modified++; node.changed++ }
                override fun sync() { check(!closed); calls += "sync:$path"; if (failSync == path) throw IOException("Sync failed") }
                override fun close() { closed = true }
            }
        }
    }

    private class Fixture {
        val storage = MemoryStorage().also { it.file("/source/input.bin", "abcdefgh".toByteArray()) }
        val recovery = PartialCopyRecovery(storage, "/private/partial")
        val empty = recovery.allocate("/source/input.bin", "/target/copy.bin", true)
        fun save(bytes: ByteArray = "abc".toByteArray()): PartialCopyRecord {
            storage.open(empty.source).use { source ->
                storage.open(empty.staging, writable = true).use { stage ->
                    stage.write(bytes, 0, bytes.size)
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    return recovery.checkpoint(empty, source, stage, bytes.size.toLong(), digest)
                }
            }
        }
    }

    @Test fun ownsPrivateStagingOutsideTheDestinationAndSyncsItsDirectories() {
        val f = Fixture()
        assertTrue(f.empty.staging.startsWith("/private/partial/"))
        assertFalse(f.storage.nodes.containsKey(f.empty.target))
        assertArrayEquals(original, f.storage.nodes.getValue(f.empty.source).bytes)
        assertEquals(1L, f.empty.stagingStamp.links)
        assertTrue(f.storage.calls.contains("sync:/target"))
        assertTrue(f.storage.calls.contains("sync:/private/partial"))
        assertFalse(f.storage.nodes.keys.any { it.contains("partial-probe") })
    }

    @Test fun unsupportedPublicationLeavesTheOriginalAndNoOwnedProbe() {
        val s = MemoryStorage().also { it.file("/source/input.bin", original); it.failLink = true }
        assertTrue(failure { PartialCopyRecovery(s, "/private/partial").allocate("/source/input.bin", "/target/copy.bin", false) } is PartialCopyUnavailable)
        assertEquals(setOf("/private/partial", "/source", "/target", "/source/input.bin"), s.nodes.keys)
        assertArrayEquals(original, s.nodes.getValue("/source/input.bin").bytes)
    }

    @Test fun anExistingDestinationIsNeverDeletedByPreparation() {
        val s = MemoryStorage().also { it.file("/source/input.bin", original); it.file("/target/copy.bin", "keep".toByteArray()) }
        failure { PartialCopyRecovery(s, "/private/partial").allocate("/source/input.bin", "/target/copy.bin", false) }
        assertArrayEquals("keep".toByteArray(), s.nodes.getValue("/target/copy.bin").bytes)
        assertTrue(s.calls.none { it.startsWith("remove:") })
    }

    @Test fun checkpointIsReturnedOnlyAfterItsFileWasSynced() {
        val f = Fixture(); f.storage.calls.clear()
        val saved = f.save()
        assertEquals(3L, saved.offset)
        assertEquals(hash("abc".toByteArray()), saved.prefixSha256)
        assertEquals(listOf("sync:${saved.staging}"), f.storage.calls)
    }

    @Test fun failedSyncCannotAdvanceTheDurableOffset() {
        val f = Fixture(); f.storage.failSync = f.empty.staging
        assertTrue(failure { f.save() } is IOException)
        assertEquals(0L, f.empty.offset)
        assertArrayEquals(original, f.storage.nodes.getValue(f.empty.source).bytes)
    }

    @Test fun readsTheFullSourceAndReusesOnlyTheDurablePrefix() {
        val f = Fixture(); val saved = f.save()
        f.storage.mutate(saved.staging, "abcZZ".toByteArray())
        val control = TransferControl(); val reviewed = f.recovery.review(saved, control)
        assertEquals(hash(original), reviewed.sourceSha256)
        assertEquals(3L, reviewed.savedBytes)
        assertEquals(2L, reviewed.discardedTailBytes)
        assertEquals(0L, control.snapshot.bytes)
        assertArrayEquals("abcZZ".toByteArray(), f.storage.nodes.getValue(saved.staging).bytes)
    }

    @Test fun rejectsSameLengthSavedPrefixCorruption() {
        val f = Fixture(); val saved = f.save(); f.storage.mutate(saved.staging, "abX".toByteArray())
        assertTrue(failure { f.recovery.review(saved) }.message!!.contains("no longer match"))
        assertArrayEquals(original, f.storage.nodes.getValue(saved.source).bytes)
    }

    @Test fun nanosecondChangeInformationRejectsASameLengthSourceEdit() {
        val f = Fixture(); val saved = f.save(); val node = f.storage.nodes.getValue(saved.source)
        val mtime = node.modified; f.storage.mutate(saved.source, "abcXYZgh".toByteArray()); node.modified = mtime
        assertTrue(failure { f.recovery.review(saved) }.message!!.contains("source file changed"))
    }

    @Test fun replacingTheSourceWithIdenticalBytesIsStillDetected() {
        val f = Fixture(); val saved = f.save(); f.storage.file(saved.source, original)
        assertTrue(failure { f.recovery.review(saved) }.message!!.contains("source file changed"))
    }

    @Test fun replacingStagingWithIdenticalBytesDoesNotTransferOwnership() {
        val f = Fixture(); val saved = f.save(); f.storage.file(saved.staging, "abc".toByteArray())
        failure { f.recovery.review(saved) }; failure { f.recovery.discard(saved) }
        assertTrue(f.storage.nodes.containsKey(saved.staging))
    }

    @Test fun externallyLinkedStagingIsNeverResumedOrDiscarded() {
        val f = Fixture(); val saved = f.save(); f.storage.link(saved.staging, "/target/another.bin")
        failure { f.recovery.review(saved) }; failure { f.recovery.discard(saved) }
        assertArrayEquals("abc".toByteArray(), f.storage.nodes.getValue("/target/another.bin").bytes)
        assertTrue(f.storage.nodes.containsKey(saved.staging))
    }

    @Test fun aShortenedSavedFileIsKeptForReview() {
        val f = Fixture(); val saved = f.save(); f.storage.mutate(saved.staging, "ab".toByteArray())
        failure { f.recovery.review(saved) }; assertTrue(f.storage.nodes.containsKey(saved.staging))
    }

    @Test fun stagingLargerThanTheSourceCannotBeReused() {
        val f = Fixture(); val saved = f.save(); f.storage.mutate(saved.staging, ByteArray(9))
        failure { f.recovery.review(saved) }
    }

    @Test fun aNewDestinationIsPreservedAndBlocksResume() {
        val f = Fixture(); val saved = f.save(); f.storage.file(saved.target, "keep".toByteArray())
        failure { f.recovery.review(saved) }; assertArrayEquals("keep".toByteArray(), f.storage.nodes.getValue(saved.target).bytes)
    }

    @Test fun aReplacedDestinationFolderBlocksResume() {
        val f = Fixture(); val saved = f.save(); f.storage.folder("/target")
        assertTrue(failure { f.recovery.review(saved) }.message!!.contains("destination folder changed"))
    }

    @Test fun aReplacedPrivateFolderBlocksBothResumeAndCleanup() {
        val f = Fixture(); val saved = f.save(); f.storage.folder(java.io.File(saved.staging).parent!!)
        failure { f.recovery.review(saved) }; failure { f.recovery.discard(saved) }
        assertTrue(f.storage.nodes.containsKey(saved.staging))
    }

    @Test fun approvalRechecksTheWholeSourceEvenIfReportedTimesWerePreserved() {
        val f = Fixture(); val saved = f.save(); val approved = f.recovery.review(saved)
        f.storage.mutate(saved.source, "abcXXXXX".toByteArray(), preserveTimes = true)
        assertTrue(failure { f.recovery.recheck(approved, TransferControl()) }.message!!.contains("changed after review"))
    }

    @Test fun approvalCannotSilentlyAcceptAModifiedUncheckpointedTail() {
        val f = Fixture(); val saved = f.save(); f.storage.mutate(saved.staging, "abcZZ".toByteArray())
        val approved = f.recovery.review(saved); f.storage.mutate(saved.staging, "abcYY".toByteArray())
        assertTrue(failure { f.recovery.recheck(approved, TransferControl()) }.message!!.contains("changed after review"))
    }

    @Test fun sourceMutationDuringReviewCannotProduceAnApproval() {
        val f = Fixture(); val saved = f.save(); var changed = false
        f.storage.onRead = { if (it == saved.source && !changed) { changed = true; f.storage.mutate(saved.source, "abcXXXXX".toByteArray()) } }
        failure { f.recovery.review(saved) }
    }

    @Test fun stagingMutationDuringSourceReadIsDetected() {
        val f = Fixture(); val saved = f.save(); var reads = 0
        f.storage.onRead = { if (it == saved.source && ++reads == 2) f.storage.mutate(saved.staging, "abX".toByteArray()) }
        failure { f.recovery.review(saved) }
    }

    @Test fun cancellingAReviewKeepsSourceAndStagingUntouched() {
        val f = Fixture(); val saved = f.save(); val control = TransferControl().apply { cancel() }
        assertTrue(failure { f.recovery.review(saved, control) } is java.util.concurrent.CancellationException)
        assertTrue(f.storage.nodes.containsKey(saved.staging)); assertArrayEquals(original, f.storage.nodes.getValue(saved.source).bytes)
    }

    @Test fun discardRemovesOnlyItsOwnedFileAndEmptyDirectory() {
        val f = Fixture(); val saved = f.save(); f.storage.file("/target/other.bin", "keep".toByteArray())
        f.recovery.discard(saved)
        assertFalse(f.storage.nodes.containsKey(saved.staging)); assertTrue(f.storage.nodes.containsKey("/private/partial"))
        assertArrayEquals(original, f.storage.nodes.getValue(saved.source).bytes)
        assertArrayEquals("keep".toByteArray(), f.storage.nodes.getValue("/target/other.bin").bytes)
    }

    @Test fun aForgedStagingPathCannotDeleteASourceDocument() {
        val f = Fixture(); val saved = f.save(); val forged = saved.copy(staging = saved.source)
        failure { f.recovery.discard(forged) }; assertArrayEquals(original, f.storage.nodes.getValue(saved.source).bytes)
    }

    @Test fun publicationAmbiguityRequiresDestinationReview() {
        val f = Fixture(); val saved = f.save().copy(publishing = true)
        failure { f.recovery.review(saved) }; failure { f.recovery.discard(saved) }
        assertTrue(f.storage.nodes.containsKey(saved.staging))
    }

    @Test fun canonicalLocationChangesCannotRedirectResume() {
        val f = Fixture(); val saved = f.save(); f.storage.aliases = mapOf("/target" to "/elsewhere")
        failure { f.recovery.review(saved) }
    }

    @Test fun checkpointCannotMoveBackwardOrBeyondTheSource() {
        val f = Fixture(); val saved = f.save()
        f.storage.open(saved.source).use { source -> f.storage.open(saved.staging, writable = true).use { stage ->
            failure { f.recovery.checkpoint(saved, source, stage, 2, hash("ab".toByteArray())) }
            failure { f.recovery.checkpoint(saved, source, stage, 9, hash(ByteArray(9))) }
        } }
    }

    @Test fun malformedCheckpointDoesNotReadOrDeleteFiles() {
        val f = Fixture(); val saved = f.save(); f.storage.calls.clear()
        failure { f.recovery.review(saved.copy(prefixSha256 = "invalid")) }
        failure { f.recovery.discard(saved.copy(owner = "../source")) }
        assertTrue(f.storage.calls.isEmpty())
    }

    @Test fun reviewedResumePublishesExactBytesAndCountsOnlyNewWrites() {
        val f = Fixture(); val saved = f.save(); f.storage.mutate(saved.staging, "abcZZ".toByteArray())
        val approval = f.recovery.review(saved); val checkpoints = mutableListOf<PartialCopyRecord?>(); var reused = 0L
        val control = TransferControl()
        val session = PartialCopySession(f.recovery, f.storage, approval) { record, _, bytes -> checkpoints += record; reused = bytes }
        val result = session.copy(java.io.File(saved.source), java.io.File(saved.target), control, null)
        assertEquals(saved.target, result); assertTrue(session.completed)
        assertArrayEquals(original, f.storage.nodes.getValue(saved.target).bytes)
        assertFalse(f.storage.nodes.containsKey(saved.staging))
        assertEquals(3L, reused); assertEquals(5L, control.snapshot.bytes)
        assertTrue(checkpoints.last()!!.publishing)
    }

    @Test fun resumedVerificationUsesTheCompleteFileWithoutInflatingCopiedBytes() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved)
        val verification = CopyVerificationSession(saved.source); val control = TransferControl()
        val session = PartialCopySession(f.recovery, f.storage, approval) { _, _, _ -> }
        session.copy(java.io.File(saved.source), java.io.File(saved.target), control, verification)
        val result = verification.results.single()
        assertEquals(CopyVerificationStatus.VERIFIED, result.status)
        assertEquals(hash(original), result.expectedSha256); assertEquals(result.expectedSha256, result.actualSha256)
        assertEquals(saved.target, result.destination); assertEquals(8L, result.bytes); assertEquals(5L, control.snapshot.bytes)
    }

    @Test fun staleDestinationApprovalCannotBeginAResumeOrOverwriteTheNewFile() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved)
        f.storage.file(saved.target, "keep".toByteArray()); var changes = 0
        val session = PartialCopySession(f.recovery, f.storage, approval) { _, _, _ -> changes++ }
        failure { session.copy(java.io.File(saved.source), java.io.File(saved.target), TransferControl(), null) }
        assertEquals(0, changes); assertArrayEquals("keep".toByteArray(), f.storage.nodes.getValue(saved.target).bytes)
        assertArrayEquals("abc".toByteArray(), f.storage.nodes.getValue(saved.staging).bytes)
    }

    @Test fun cancellingDuringRemainingWritesRemovesOnlyOwnedUnpublishedWork() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved); val control = TransferControl()
        var last: PartialCopyRecord? = saved
        f.storage.onWrite = { if (it == saved.staging) control.cancel() }
        val session = PartialCopySession(f.recovery, f.storage, approval) { record, _, _ -> last = record }
        failure { session.copy(java.io.File(saved.source), java.io.File(saved.target), control, null) }
        assertNull(last); assertFalse(f.storage.nodes.containsKey(saved.target)); assertFalse(f.storage.nodes.containsKey(saved.staging))
        assertArrayEquals(original, f.storage.nodes.getValue(saved.source).bytes)
    }

    @Test fun sourceMutationWhileRemainingBytesAreWrittenCannotPublishAMixedFile() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved)
        f.storage.onWrite = { if (it == saved.staging) f.storage.mutate(saved.source, "abcXXXXX".toByteArray()) }
        val session = PartialCopySession(f.recovery, f.storage, approval) { _, _, _ -> }
        failure { session.copy(java.io.File(saved.source), java.io.File(saved.target), TransferControl(), null) }
        assertFalse(f.storage.nodes.containsKey(saved.target)); assertTrue(f.storage.nodes.containsKey(saved.staging))
        assertArrayEquals("abcXXXXX".toByteArray(), f.storage.nodes.getValue(saved.source).bytes)
    }

    @Test fun journalFailureBeforePublicationKeepsTheSourceAndARecoverableSavedPrefix() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved)
        val session = PartialCopySession(f.recovery, f.storage, approval) { record, _, _ ->
            if (record?.offset == 8L) throw IOException("Journal offline")
        }
        assertTrue(failure { session.copy(java.io.File(saved.source), java.io.File(saved.target), TransferControl(), null) }.message!!.contains("Journal offline"))
        assertFalse(f.storage.nodes.containsKey(saved.target)); assertArrayEquals(original, f.storage.nodes.getValue(saved.source).bytes)
        assertEquals(3L, f.recovery.review(saved).savedBytes)
    }

    @Test fun aCollisionAfterPublicationPreparationCannotReplaceTheDestination() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved)
        val session = PartialCopySession(f.recovery, f.storage, approval) { record, _, _ ->
            if (record?.publishing == true) f.storage.file(saved.target, "keep".toByteArray())
        }
        failure { session.copy(java.io.File(saved.source), java.io.File(saved.target), TransferControl(), null) }
        assertArrayEquals("keep".toByteArray(), f.storage.nodes.getValue(saved.target).bytes)
        assertTrue(f.storage.nodes.containsKey(saved.staging)); assertFalse(session.completed)
    }

    @Test fun cleanupFailureKeepsTheKnownPublishedDestinationAndItsOwnershipRecord() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved); f.storage.failRemove = java.io.File(saved.staging).parent!!
        var last: PartialCopyRecord? = null
        val session = PartialCopySession(f.recovery, f.storage, approval) { record, _, _ -> last = record }
        val error = failure { session.copy(java.io.File(saved.source), java.io.File(saved.target), TransferControl(), null) }
        assertTrue(error is TransferPublicationException); assertEquals(saved.target, (error as TransferPublicationException).destination)
        assertArrayEquals(original, f.storage.nodes.getValue(saved.target).bytes)
        assertTrue(last!!.publishing); assertFalse(f.storage.nodes.containsKey(saved.staging))
    }

    @Test fun journalCallbacksNeverRunAfterTheAtomicPublication() {
        val f = Fixture(); val saved = f.save(); val approval = f.recovery.review(saved); var published = false
        f.storage.onLink = { if (it == saved.target) published = true }
        val session = PartialCopySession(f.recovery, f.storage, approval) { _, _, _ -> assertFalse(published) }
        session.copy(java.io.File(saved.source), java.io.File(saved.target), TransferControl(), null)
        assertTrue(published)
    }

    @Test fun evenARequiredDestinationReadbackDoesNotCountSavedBytesAsNewWrites() {
        val f = Fixture(); val saved = f.save(); val control = TransferControl()
        val session = PartialCopySession(f.recovery, f.storage, f.recovery.review(saved)) { _, _, _ -> }
        session.copy(java.io.File(saved.source), java.io.File(saved.target), control, null)
        assertEquals(5L, control.snapshot.bytes); assertEquals(TransferStage.FINISHING, control.snapshot.stage)
        assertArrayEquals(original, f.storage.nodes.getValue(saved.target).bytes)
    }

    @Test fun reviewedCleanupKeepsAPublishedDestinationAndItsExactBytes() {
        val f = Fixture(); val record = f.save(original).copy(publishing = true)
        f.storage.moveNoReplace(record.staging, record.target)
        val review = f.recovery.reviewCleanup(record)
        assertTrue(review.destinationMatches)
        f.recovery.discardReviewed(review)
        assertArrayEquals(original, f.storage.nodes.getValue(record.target).bytes)
        assertArrayEquals(original, f.storage.nodes.getValue(record.source).bytes)
        assertFalse(f.storage.nodes.containsKey(record.staging))
    }

    @Test fun reviewedPublicationCleanupDoesNotRemoveADifferentDestination() {
        val f = Fixture(); val record = f.save(original).copy(publishing = true)
        f.storage.file(record.target, "keep".toByteArray())
        val review = f.recovery.reviewCleanup(record)
        assertFalse(review.destinationMatches); f.recovery.discardReviewed(review)
        assertArrayEquals("keep".toByteArray(), f.storage.nodes.getValue(record.target).bytes)
    }

    @Test fun cleanupAfterPublicationWithoutADestinationCannotResumeOrRemoveTheSource() {
        val f = Fixture(); val record = f.save(original).copy(publishing = true)
        val review = f.recovery.reviewCleanup(record)
        assertNull(review.destination); failure { f.recovery.review(record) }
        f.recovery.discardReviewed(review)
        assertArrayEquals(original, f.storage.nodes.getValue(record.source).bytes)
    }

    @Test fun interruptedPrivateCleanupCanBeReviewedAndCompletedAgain() {
        val f = Fixture(); val record = f.save(original).copy(publishing = true)
        f.storage.moveNoReplace(record.staging, record.target)
        val review = f.recovery.reviewCleanup(record)
        assertNull(review.observedStaging); assertTrue(review.destinationMatches)
        f.recovery.discardReviewed(review)
        assertFalse(f.storage.nodes.containsKey(java.io.File(record.staging).parent!!))
        assertArrayEquals(original, f.storage.nodes.getValue(record.target).bytes)
    }

    @Test fun aCrashAfterAllPrivateCleanupCanClearOnlyTheStaleRecoveryRecord() {
        val f = Fixture(); val record = f.save(original).copy(publishing = true)
        f.storage.moveNoReplace(record.staging, record.target)
        f.storage.remove(java.io.File(record.staging).parent!!)
        val before = f.storage.calls.size
        val review = f.recovery.reviewCleanup(record); f.recovery.discardReviewed(review)
        assertEquals(before, f.storage.calls.size)
        assertArrayEquals(original, f.storage.nodes.getValue(record.target).bytes)
    }

    @Test fun changedSourceBlocksResumeButAllowsExplicitOwnedStageDiscard() {
        val f = Fixture(); val record = f.save(); f.storage.mutate(record.source, "edited".toByteArray())
        failure { f.recovery.review(record) }
        f.recovery.discardReviewed(f.recovery.reviewCleanup(record))
        assertArrayEquals("edited".toByteArray(), f.storage.nodes.getValue(record.source).bytes)
    }

    @Test fun cleanupRefusesAFileReplacedAfterItsReview() {
        val f = Fixture(); val record = f.save(); val review = f.recovery.reviewCleanup(record)
        f.storage.file(record.staging, "keep replacement".toByteArray())
        failure { f.recovery.discardReviewed(review) }
        assertArrayEquals("keep replacement".toByteArray(), f.storage.nodes.getValue(record.staging).bytes)
    }

    @Test fun cleanupRefusesAnEditMadeAfterItsReview() {
        val f = Fixture(); val record = f.save(); val review = f.recovery.reviewCleanup(record)
        f.storage.mutate(record.staging, "edited".toByteArray())
        failure { f.recovery.discardReviewed(review) }
        assertArrayEquals("edited".toByteArray(), f.storage.nodes.getValue(record.staging).bytes)
    }

    @Test fun cleanupDoesNotFollowARedirectedPrivateRoot() {
        val f = Fixture(); val record = f.save()
        f.storage.aliases = mapOf("/private/partial" to "/elsewhere")
        failure { f.recovery.reviewCleanup(record) }
        assertTrue(f.storage.nodes.containsKey(record.staging))
    }

    @Test fun reviewedCleanupNeverRecursivelyDeletesUnexpectedPrivateFiles() {
        val f = Fixture(); val record = f.save()
        val unrelated = java.io.File(java.io.File(record.staging).parent!!, "unexpected").path
        f.storage.file(unrelated, "keep".toByteArray())
        failure { f.recovery.discardReviewed(f.recovery.reviewCleanup(record)) }
        assertArrayEquals("keep".toByteArray(), f.storage.nodes.getValue(unrelated).bytes)
        assertArrayEquals(original, f.storage.nodes.getValue(record.source).bytes)
    }


    @Test fun initialStablePlatformAliasesAreStoredAndLaterRedirectionIsRejected() {
        val storage = MemoryStorage().also {
            it.file("/source/input.bin", original)
            it.aliases = mapOf("/source/input.bin" to "/resolved/input.bin", "/target" to "/resolved/target",
                "/private/partial" to "/resolved/private", "/private/partial/unused" to "/resolved/private/unused")
        }
        val recovery = PartialCopyRecovery(storage, "/private/partial")
        val record = recovery.allocate("/source/input.bin", "/target/copy.bin", false)
        assertEquals("/resolved/input.bin", record.resolvedSource)
        assertEquals("/resolved/target", record.resolvedDestinationParent)
        assertEquals("/resolved/private", record.resolvedPrivateRoot)
        storage.open(record.source).use { input -> storage.open(record.staging, writable = true).use { stage ->
            assertEquals(record, recovery.checkpoint(record, input, stage, 0, record.prefixSha256))
        } }
        storage.aliases = storage.aliases + ("/source/input.bin" to "/redirected/input.bin")
        failure { recovery.checkpoint(record, storage.open(record.source), storage.open(record.staging, writable = true), 0, record.prefixSha256) }
        assertArrayEquals(original, storage.nodes.getValue(record.source).bytes)
    }

}
