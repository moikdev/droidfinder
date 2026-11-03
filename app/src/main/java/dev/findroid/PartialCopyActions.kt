package dev.findroid

/** Shared across panes, retained only in this process, and never serialized as an approval. */
data class PartialCopyReviewState(
    val token: String,
    val jobId: String,
    val record: PartialCopyRecord,
    val checking: Boolean = true,
    val applying: Boolean = false,
    val progress: TransferProgress = TransferProgress(),
    val resume: PartialCopyReview? = null,
    val cleanup: PartialCopyCleanupReview? = null,
    val error: String? = null,
)

internal data class PartialCopyResumePlan(val jobs: List<TransferJob>, val resumed: TransferJob)

/** One journal transaction transfers ownership; the old history remains review-only for this source. */
internal fun planPartialCopyResume(
    jobs: List<TransferJob>,
    jobId: String,
    approval: PartialCopyReview,
): PartialCopyResumePlan {
    validatePartialCopyJobs(jobs)
    val old = jobs.single { it.id == jobId }
    require(old.terminal && old.partialCopy == approval.record && !approval.record.publishing && approval.savedBytes > 0) {
        "The saved copy changed. Review it again before resuming."
    }
    val resumed = TransferJob(
        paths = listOf(approval.record.source),
        destination = old.destination,
        move = false,
        conflictPolicy = ConflictPolicy.ASK,
        verifyCopies = old.verifyCopies,
        partialCopy = approval.record,
        partialCopyNotice = "Saved bytes were reviewed. They will be checked again before copying continues.",
    )
    val next = jobs.map {
        if (it.id == old.id) it.copy(
            partialCopy = null,
            inFlight = approval.record.source,
            partialCopyNotice = "This saved copy was continued in a separately reviewed transfer.",
        ) else it
    } + resumed
    validatePartialCopyJobs(next)
    return PartialCopyResumePlan(next, resumed)
}
