package dev.findroid

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartialCopyPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(): PartialCopyRecord {
        val owner = UUID.randomUUID().toString()
        val file = PartialFileStamp(17, 9_007_199_254_740_993L, 8, 1, 123_456_789, 2, 987_654_321, true, false, 1)
        val folder = file.copy(inode = file.inode + 1, size = 4096, regular = false, directory = true)
        return PartialCopyRecord(owner, File(temporary.root, "source.bin").path, File(temporary.root, "copy.bin").path,
            File(temporary.root, ".partial/$owner/data").path, file, folder, folder.copy(inode = folder.inode + 1),
            file.copy(inode = file.inode + 3, size = 3), 3,
            MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).joinToString("") { "%02x".format(it) }, true)
    }
    private fun job(record: PartialCopyRecord) = TransferJob(paths = listOf(record.source), destination = File(record.target).parent!!,
        move = false, state = TransferState.RUNNING, inFlight = record.source, verifyCopies = record.verifyCopies,
        partialCopy = record, reusedBytes = 3, partialCopyNotice = "Reviewed saved bytes")
    private fun failure(work: () -> Unit): Exception {
        try { work() } catch (error: Exception) { return error }
        throw AssertionError("Invalid recovery history was accepted")
    }

    @Test fun exactNativeIdentitiesAndNanosecondsSurviveJsonRoundTrip() {
        val record = record()
        val encoded = encodePartialCopy(record)
        assertTrue(encoded.contains("\"9007199254740993\""))
        assertEquals(record, decodePartialCopy(encoded))
    }

    @Test fun durableOffsetAndVerificationChoiceSurviveLegacyJournalRoundTrip() {
        val record = record(); val original = job(record); val file = temporary.newFile("transfers.properties")
        file.delete(); val store = TransferJournal(file); store.save(listOf(original))
        assertEquals(original, TransferJournal(file).load().single())
    }

    @Test fun restartKeepsSavedProofButRequiresManualReview() {
        val original = job(record())
        val recovered = original.afterRestart()
        assertEquals(TransferState.INTERRUPTED, recovered.state)
        assertEquals(original.partialCopy, recovered.partialCopy)
        assertEquals(3L, recovered.reusedBytes)
        assertTrue(recovered.retryTargets.none { it.source == original.partialCopy!!.source })
    }

    @Test fun oldJournalDefaultsToNoSavedCopyAndNoReusedBytes() {
        val file = temporary.newFile("old.properties"); file.delete()
        val original = TransferJob(paths = listOf("old-source"), destination = "old-destination", move = false)
        TransferJournal(file).save(listOf(original))
        val properties = Properties().apply { file.inputStream().use(::load) }
        properties.remove("0.partialCopy"); properties.remove("0.reusedBytes"); properties.remove("0.partialCopyNotice")
        file.outputStream().use { properties.store(it, "Old format") }
        assertEquals(original, TransferJournal(file).load().single())
    }

    @Test fun rejectsUnsupportedSavedCopySchema() {
        val objectValue = JsonParser.parseString(encodePartialCopy(record())).asJsonObject.also { it.addProperty("schema", 2) }
        failure { decodePartialCopy(objectValue.toString()) }
    }

    @Test fun identitiesCannotBeRoundedJsonFloatingPointNumbers() {
        val objectValue = JsonParser.parseString(encodePartialCopy(record())).asJsonObject
        objectValue.getAsJsonObject("sourceStamp").addProperty("inode", 9_007_199_254_740_993L.toDouble())
        failure { decodePartialCopy(objectValue.toString()) }
    }

    @Test fun rejectsAnOffsetPastTheSourceAndNanosecondsOutOfRange() {
        val record = record()
        failure { encodePartialCopy(record.copy(offset = 9, stagingStamp = record.stagingStamp.copy(size = 9))) }
        failure { encodePartialCopy(record.copy(sourceStamp = record.sourceStamp.copy(changedNanos = 1_000_000_000))) }
    }

    @Test fun rejectsPathTraversalAndForgedStageOwnership() {
        val record = record()
        failure { encodePartialCopy(record.copy(staging = File(temporary.root, "other/data").path)) }
        failure { encodePartialCopy(record.copy(source = File(temporary.root, "../source.bin").path)) }
    }

    @Test fun savedCopyMustBelongToThisSourceDestinationAndVerificationRequest() {
        val record = record(); val job = job(record)
        failure { validatePartialCopyJob(job.copy(paths = listOf("unrelated"))) }
        failure { validatePartialCopyJob(job.copy(destination = "/another")) }
        failure { validatePartialCopyJob(job.copy(verifyCopies = false)) }
        failure { validatePartialCopyJob(job.copy(move = true, reusedBytes = 0)) }
    }

    @Test fun duplicateStageOwnersCannotBeSavedByTwoJobs() {
        val original = job(record())
        failure { TransferJournal(File(temporary.root, "duplicates.properties")).save(listOf(original, original.copy(id = UUID.randomUUID().toString()))) }
    }

    @Test fun malformedPayloadCannotBecomeAnEmptyAcceptedCheckpoint() {
        failure { decodePartialCopy("{}") }
        failure { decodePartialCopy("x".repeat(33 * 1024)) }
        failure { decodePartialCopy(JsonParser.parseString(encodePartialCopy(record())).asJsonObject.also { it.addProperty("verifyCopies", "true") }.toString()) }
    }

    @Test fun completedResumeHistoryRetainsHonestReusedBytesWithoutKeepingAStage() {
        val original = job(record()).copy(partialCopy = null, state = TransferState.COMPLETED)
        val file = File(temporary.root, "completed.properties")
        TransferJournal(file).save(listOf(original))
        assertEquals(original, TransferJournal(file).load().single())
        failure { validatePartialCopyJob(original.copy(move = true)) }
        failure { validatePartialCopyJob(original.copy(reusedBytes = -1)) }
    }

    private fun approval(record: PartialCopyRecord) = PartialCopyReview(record, "a".repeat(64), record.stagingStamp)

    @Test fun manualResumeTransfersStageOwnershipInOneJournalSnapshot() {
        val record = record(); val old = job(record).afterRestart()
        val plan = planPartialCopyResume(listOf(old), old.id, approval(record))
        assertNull(plan.jobs.first().partialCopy); assertEquals(record.source, plan.jobs.first().inFlight)
        assertEquals(old.results, plan.jobs.first().results); assertTrue(plan.jobs.first().retryTargets.isEmpty())
        assertEquals(listOf(record.source), plan.resumed.paths); assertEquals(record, plan.resumed.partialCopy)
        assertTrue(plan.resumed.verifyCopies); assertEquals(0L, plan.resumed.reusedBytes)
        val file = File(temporary.root, "transferred.properties")
        TransferJournal(file).save(plan.jobs)
        assertEquals(plan.jobs, TransferJournal(file).load())
        assertEquals(1, plan.jobs.count { it.partialCopy != null })
    }

    @Test fun aResumedJobDoesNotReplayWithoutANewReviewAfterRestart() {
        val record = record(); val old = job(record).afterRestart()
        val plan = planPartialCopyResume(listOf(old), old.id, approval(record))
        val restarted = plan.resumed.afterRestart()
        assertEquals(TransferState.INTERRUPTED, restarted.state)
        assertTrue(restarted.retryTargets.isEmpty()); assertEquals(record, restarted.partialCopy)
    }

    @Test fun resumePlanKeepsOtherRootsResultsAndQueueOrdering() {
        val record = record(); val result = TransferItemResult("/other", "/out/other")
        val old = job(record).copy(state = TransferState.FAILED, paths = listOf("/other", record.source, "/unfinished"), results = listOf(result))
        val other = TransferJob(paths = listOf("/separate"), destination = "/out", move = true)
        val plan = planPartialCopyResume(listOf(other, old), old.id, approval(record))
        assertEquals(other, plan.jobs.first()); assertEquals(listOf(result), plan.jobs[1].results)
        assertEquals(listOf(TransferRetryTarget("/unfinished", old.destination)), plan.jobs[1].retryTargets)
        assertEquals(listOf(record.source), plan.jobs.last().paths)
    }

    @Test fun changedAndFinishingCheckpointsCannotBeTransferredByAnOldApproval() {
        val record = record(); val old = job(record).afterRestart()
        failure { planPartialCopyResume(listOf(old.copy(partialCopy = record.copy(offset = 2, stagingStamp = record.stagingStamp.copy(size = 2)))), old.id, approval(record)) }
        val finishing = record.copy(publishing = true)
        failure { planPartialCopyResume(listOf(old.copy(partialCopy = finishing)), old.id, approval(finishing)) }
        failure { planPartialCopyResume(listOf(old.copy(state = TransferState.RUNNING)), old.id, approval(record)) }
    }

}
