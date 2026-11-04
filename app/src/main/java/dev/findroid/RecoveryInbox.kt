package dev.findroid

/** Recorded evidence only. Building the inbox never reads, retries, deletes or adopts a file. */
internal enum class RecoveryInboxKind(val title: String) {
    TRANSFER("Transfers needing review"),
    ORIGINAL("Preserved originals"),
    SYNCHRONIZATION("Synchronization records"),
    NOTICE("Records needing attention"),
}

internal enum class RecoveryInboxRoute {
    TRANSFERS, SAVED_COPY, SYNCHRONIZATION, RECENT_ACTIONS, TRASH, STORAGE, DESTINATION,
}

internal data class RecoveryInboxPlace(val label: String, val path: String)
internal data class RecoveryInboxLink(val label: String, val route: RecoveryInboxRoute, val jobId: String? = null, val path: String? = null)
internal data class RecoveryInboxItem(
    val id: String,
    val kind: RecoveryInboxKind,
    val title: String,
    val explanation: String,
    val nextStep: String,
    val places: List<RecoveryInboxPlace> = emptyList(),
    val links: List<RecoveryInboxLink> = emptyList(),
    val recordedAt: Long? = null,
)

internal data class RecoveryOriginal(val path: String, val original: String?, val savedAt: Long?)
internal data class RecoveryInboxState(
    val token: String,
    val items: List<RecoveryInboxItem> = emptyList(),
    val checking: Boolean = true,
    val notices: List<String> = emptyList(),
)

/** All locations are unverified references until the user explicitly opens or reviews them. */
internal fun recoveryInboxItems(
    transfers: List<TransferJob>,
    synchronization: List<FolderSyncRunRecord>,
    originals: List<RecoveryOriginal>,
    interrupted: String?,
    transferProblem: String?,
    undoProblem: String?,
    operationNotices: List<RecoveryNotice> = emptyList(),
): List<RecoveryInboxItem> = buildList {
    (operationNotices + listOfNotNull(interrupted?.takeIf { it.isNotBlank() }?.let(RecoveryNotice::fromText)))
        .distinctBy { it.id }.forEach { notice ->
        add(RecoveryInboxItem(
            id = "operation-notice:" + notice.id, kind = RecoveryInboxKind.TRANSFER,
            title = "A previous operation needs review",
            explanation = notice.label?.takeIf { it.isNotBlank() } ?: "The previous operation did not record its completion.",
            nextStep = "Review the destination and any hidden .findroid-* working data before starting another operation. Completed files stay in place. This notice is kept after acknowledgment.",
            places = notice.destination?.let { listOf(RecoveryInboxPlace("Recorded destination", it)) }.orEmpty(),
            links = buildList {
                notice.destination?.let { add(RecoveryInboxLink("Review destination", RecoveryInboxRoute.DESTINATION, path = it)) }
                add(RecoveryInboxLink("Review transfers", RecoveryInboxRoute.TRANSFERS))
            },
            recordedAt = notice.recordedAt,
        ))
    }
    transfers.filter { job -> job.terminal && (
        job.state in setOf(TransferState.INTERRUPTED, TransferState.FAILED, TransferState.CANCELLED) ||
        job.partialCopy != null || job.inFlight != null || job.partialCopyNotice != null ||
        job.mergePlans.any { it.error != null || it.cleanup == SourceCleanup.FAILED || it.members.any { member -> member.error != null || member.cleanup == SourceCleanup.FAILED } }
    ) }.forEach { job ->
        val places = buildList {
            add(RecoveryInboxPlace("Destination", job.destination))
            job.inFlight?.let { add(RecoveryInboxPlace("Unconfirmed source", it)) }
            job.partialCopy?.let { partial ->
                add(RecoveryInboxPlace("Saved copy source", partial.source))
                add(RecoveryInboxPlace("Planned destination", partial.target))
                add(RecoveryInboxPlace("Private working file", partial.staging))
            }
            job.mergePlans.forEach { plan ->
                plan.recovery?.let { add(RecoveryInboxPlace("Preserved source", it)) }
                plan.members.forEach { member -> member.recovery?.let { add(RecoveryInboxPlace("Preserved source", it)) } }
            }
        }.distinct()
        val explanation = buildList {
            add(job.state.title + ". ${job.successful.size} recorded completed items; ${job.remaining.size} source items without a recorded completion.")
            job.partialCopyNotice?.let(::add)
            if (job.inFlight != null) add("The in-flight source has an uncertain outcome and is excluded from whole-item retry.")
            if (job.partialCopy != null) add("An unpublished partial copy has a saved checkpoint. Its source and working data need fresh checks.")
        }.joinToString(" ")
        add(RecoveryInboxItem(
            id = "transfer:" + job.id, kind = RecoveryInboxKind.TRANSFER,
            title = job.title, explanation = explanation,
            nextStep = if (job.partialCopy != null)
                "Review the saved copy before deciding whether to resume or discard its private working data. Both choices require their own checks and confirmation."
                else "Review the recorded results and destination first. Retry only the eligible unfinished items shown in Transfers.",
            places = places,
            links = buildList {
                if (job.partialCopy != null) add(RecoveryInboxLink("Review saved copy", RecoveryInboxRoute.SAVED_COPY, job.id))
                add(RecoveryInboxLink("Review transfer", RecoveryInboxRoute.TRANSFERS, job.id))
            },
            recordedAt = job.finished.takeIf { it > 0 } ?: job.created.takeIf { it > 0 },
        ))
    }
    originals.forEach { original ->
        add(RecoveryInboxItem(
            id = "original:" + original.path, kind = RecoveryInboxKind.ORIGINAL,
            title = "An original is preserved in Findroid Trash",
            explanation = "Trash includes removed files and originals saved before replacement. This record does not decide which copy to keep.",
            nextStep = "Review the item and its original location in Trash. Restore only after checking the current destination; an occupied name is kept.",
            places = buildList {
                add(RecoveryInboxPlace("Preserved item", original.path))
                original.original?.let { add(RecoveryInboxPlace("Recorded original location", it)) }
            },
            links = listOf(RecoveryInboxLink("Review Trash", RecoveryInboxRoute.TRASH)),
            recordedAt = original.savedAt,
        ))
    }
    synchronization.forEach { record ->
        add(RecoveryInboxItem(
            id = "synchronization:" + record.id, kind = RecoveryInboxKind.SYNCHRONIZATION,
            title = "Synchronization run record",
            explanation = "${record.operations} selected changes were recorded before preparation. An intent does not prove that preparation or publication finished.",
            nextStep = "Review the source, destination, working folder and manual journal. Opening the record never retries, restores, removes or adopts files.",
            places = listOf(
                RecoveryInboxPlace("Source", record.source), RecoveryInboxPlace("Destination", record.destination),
                RecoveryInboxPlace("Working folder", record.working), RecoveryInboxPlace("Manual journal", record.journal),
            ),
            links = listOf(RecoveryInboxLink("Review synchronization records", RecoveryInboxRoute.SYNCHRONIZATION)),
            recordedAt = record.createdAt,
        ))
    }
    if (transferProblem != null) add(RecoveryInboxItem(
        id = "transfer-journal", kind = RecoveryInboxKind.NOTICE,
        title = "Transfer history needs review", explanation = transferProblem,
        nextStep = "Keep the damaged journal and review affected destinations. Transfers offers a separate reviewed reset that preserves the old journal.",
        links = listOf(RecoveryInboxLink("Review transfer history", RecoveryInboxRoute.TRANSFERS)),
    ))
    if (undoProblem != null) add(RecoveryInboxItem(
        id = "undo-journal", kind = RecoveryInboxKind.NOTICE,
        title = "Saved Undo needs review", explanation = undoProblem,
        nextStep = "Review the affected folders and Trash. Recent actions offers a separate reviewed reset that preserves the old Undo journal.",
        links = listOf(RecoveryInboxLink("Review recent actions", RecoveryInboxRoute.RECENT_ACTIONS)),
    ))
}.sortedWith(compareBy<RecoveryInboxItem> { it.kind.ordinal }.thenByDescending { it.recordedAt ?: Long.MAX_VALUE }.thenBy { it.id })
