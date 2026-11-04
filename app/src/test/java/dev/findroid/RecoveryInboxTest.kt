package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class RecoveryInboxTest {
    private fun project(jobs: List<TransferJob> = emptyList(), records: List<FolderSyncRunRecord> = emptyList(),
        originals: List<RecoveryOriginal> = emptyList(), interrupted: String? = null,
        transferProblem: String? = null, undoProblem: String? = null) =
        recoveryInboxItems(jobs, records, originals, interrupted, transferProblem, undoProblem)

    @Test fun emptyRecordsDoNotInventRecoveryWork() { assertTrue(project().isEmpty()) }

    @Test fun liveAndOrdinaryCompletedJobsAreExcluded() {
        val job = TransferJob(paths = listOf("/source"), destination = "/destination", move = false)
        assertTrue(project(TransferState.entries.filter { it != TransferState.INTERRUPTED && it != TransferState.FAILED && it != TransferState.CANCELLED }
            .map { job.copy(state = it) }).isEmpty())
    }

    @Test fun interruptedInFlightSourceIsExplainedWithoutRetryingIt() {
        val job = TransferJob(id = "interrupted", paths = listOf("/source"), destination = "/destination", move = true,
            state = TransferState.INTERRUPTED, inFlight = "/source")
        val item = project(listOf(job)).single()
        assertTrue(item.explanation.contains("uncertain outcome"))
        assertEquals(listOf(RecoveryInboxPlace("Destination", "/destination"), RecoveryInboxPlace("Unconfirmed source", "/source")), item.places)
        assertEquals(RecoveryInboxRoute.TRANSFERS, item.links.single().route)
        assertTrue(job.retryTargets.isEmpty())
    }

    @Test fun cancellationRemainsVisibleEvenWithACompletedPrefix() {
        val job = TransferJob(paths = listOf("/a", "/b"), destination = "/target", move = false,
            state = TransferState.CANCELLED, results = listOf(TransferItemResult("/a", "/target/a")))
        assertTrue(project(listOf(job)).single().explanation.contains("1 recorded completed items; 1 source items"))
        assertEquals(listOf("/b"), job.remaining)
    }

    @Test fun completedMergeWithFailedCleanupStillNeedsReview() {
        val plan = MergePlan("/source", "/target", "stamp", emptyList(), cleanup = SourceCleanup.FAILED, recovery = "/preserved")
        val job = TransferJob(paths = listOf("/source"), destination = "/target", move = true,
            state = TransferState.COMPLETED, mergePlans = listOf(plan))
        assertTrue(project(listOf(job)).single().places.contains(RecoveryInboxPlace("Preserved source", "/preserved")))
    }

    @Test fun originalsRetainExactPathsAndUseAReviewedTrashRoute() {
        val original = RecoveryOriginal("/trash/雪/Report.txt", "content://provider/document/primary%3AReport.txt", 42)
        val item = project(originals = listOf(original)).single()
        assertEquals(original.original, item.places.last().path)
        assertEquals(RecoveryInboxRoute.TRASH, item.links.single().route)
        assertTrue(item.nextStep.contains("occupied name is kept"))
    }

    @Test fun missingOriginalMappingDoesNotInventOne() {
        assertEquals(listOf(RecoveryInboxPlace("Preserved item", "/trash/item")), project(originals = listOf(RecoveryOriginal("/trash/item", null, null))).single().places)
    }

    @Test fun synchronizationIntentDoesNotClaimCompletion() {
        val record = FolderSyncRunRecord("run", "/left", "/right", "/working", "/journal", 3, 7)
        val item = project(records = listOf(record)).single()
        assertTrue(item.explanation.contains("does not prove"))
        assertEquals(listOf("/left", "/right", "/working", "/journal"), item.places.map { it.path })
        assertEquals(RecoveryInboxRoute.SYNCHRONIZATION, item.links.single().route)
    }

    @Test fun legacyMarkerHasAnExplicitDestinationAndNoMutationRoute() {
        val item = project(interrupted = "Move project\ncontent://provider/tree/old\nextra detail").single()
        assertEquals("Move project", item.explanation)
        assertEquals("content://provider/tree/old", item.places.single().path)
        assertEquals(setOf(RecoveryInboxRoute.TRANSFERS, RecoveryInboxRoute.DESTINATION), item.links.map { it.route }.toSet())
        assertEquals("content://provider/tree/old", item.links.first { it.route == RecoveryInboxRoute.DESTINATION }.path)
    }

    @Test fun blankMarkerDoesNotCreateAnEmptyCard() { assertTrue(project(interrupted = " \n ").isEmpty()) }

    @Test fun corruptHistoriesHaveIndependentReviewRoutes() {
        val items = project(transferProblem = "Transfer journal unreadable", undoProblem = "Undo interrupted")
        assertEquals(setOf(RecoveryInboxRoute.TRANSFERS, RecoveryInboxRoute.RECENT_ACTIONS), items.flatMap { it.links }.map { it.route }.toSet())
        assertEquals(setOf("Transfer journal unreadable", "Undo interrupted"), items.map { it.explanation }.toSet())
    }

    @Test fun newestTransferIsFirstAndProjectionKeepsItsInputs() {
        val older = TransferJob(id = "old", paths = listOf("/a"), destination = "/b", move = false, state = TransferState.FAILED, finished = 10)
        val newer = older.copy(id = "new", finished = 20)
        val input = listOf(older, newer)
        assertEquals(listOf("transfer:new", "transfer:old"), project(input).map { it.id })
        assertEquals(listOf(older, newer), input)
        assertEquals(TransferState.FAILED, older.state)
    }
    @Test fun acknowledgedAndCurrentCopiesOfTheSameNoticeAppearOnce() {
        val text = "Copy project\n/destination\n123"
        val notice = RecoveryNotice.fromText(text)
        val items = recoveryInboxItems(emptyList(), emptyList(), emptyList(), text, null, null, listOf(notice, notice))
        assertEquals(1, items.size)
        assertEquals(123L, items.single().recordedAt)
        assertTrue(items.single().nextStep.contains("kept after acknowledgment"))
    }

    @Test fun aNewNoticeDoesNotHideAnEarlierAcknowledgedOperation() {
        val old = RecoveryNotice.fromText("Move old\n/old\n1")
        val current = "Copy new\n/new\n2"
        val items = recoveryInboxItems(emptyList(), emptyList(), emptyList(), current, null, null, listOf(old))
        assertEquals(listOf("Copy new", "Move old"), items.map { it.explanation })
        assertEquals(setOf("/old", "/new"), items.flatMap { it.places }.map { it.path }.toSet())
    }

}
