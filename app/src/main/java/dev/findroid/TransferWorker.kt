package dev.findroid

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job

/** One serialized job; all durable checkpoints run on the model's dispatcher. */
class TransferWorker(
    private val job: TransferJob,
    private val documents: DocumentAccess,
    private val control: TransferControl,
    private val current: () -> TransferJob,
    private val checkpoint: suspend ((TransferJob) -> TransferJob) -> Unit,
    private val ask: suspend (Entry, Entry, Boolean) -> ConflictDecision,
    private val metadata: (List<Pair<String, String>>, Boolean) -> Unit,
    private val steps: MutableList<FileStep>,
    private val partialApproval: PartialCopyReview? = null,
) {
    private var policy = job.conflictPolicy
    private val completedPartialSources = hashSetOf<String>()

    private suspend fun <T> io(work: () -> T): T = withContext(Dispatchers.IO) { work() }

    private class CheckpointFailure(cause: Exception) : Exception(cause.message, cause)

    private suspend fun save(change: (TransferJob) -> TransferJob) {
        try {
            checkpoint(change)
        } catch (error: Exception) {
            throw CheckpointFailure(error)
        }
    }

    private suspend fun result(result: TransferItemResult) = save {
        it.copy(
            results = it.results.filterNot { r -> r.source == result.source } + result,
            inFlight = if (it.partialCopy?.source == result.source && result.destination == null) result.source else null,
            partialCopy = it.partialCopy.takeUnless { result.source in completedPartialSources },
        )
    }

    suspend fun run() {
        job.partialCopy?.let { record ->
            check(partialApproval?.record == record && job.paths == listOf(record.source) && !record.publishing) {
                "Review the saved copy again before resuming. Its source and saved bytes were kept."
            }
        }
        control.phase("Checking files", stage = TransferStage.PREPARING)
        val estimate = io { documents.estimate(job.paths, control) }
        if (job.verifyCopies) check(estimate.files <= MAX_COPY_VERIFICATION_RESULTS) {
            "Verification supports up to $MAX_COPY_VERIFICATION_RESULTS file results per transfer. Split this selection into smaller copies."
        }
        val available = io { documents.availableSpace(job.destination) }
        save { it.copy(estimate = estimate, availableAtStart = available) }
        for (path in job.paths) {
            io { control.check() }
            save { it.copy(inFlight = path, current = path.substringAfterLast('/')) }
            try {
                result(transferOne(path, job.destination, job.move, true))
            } catch (error: Exception) {
                if (error is CheckpointFailure) throw error
                io { control.check() }
                result(
                    TransferItemResult(
                        path,
                        (error as? TransferPublicationException)?.destination
                            ?: current().mergePlans.firstOrNull { it.source == path }?.destination,
                        error.message ?: "Transfer failed",
                    ),
                )
                if (current().partialCopy != null) return
            }
        }
    }

    private suspend fun decision(
        before: Entry,
        existing: Entry,
        mergeAllowed: Boolean,
    ): ConflictDecision {
        val answer =
            when (policy) {
                ConflictPolicy.ASK -> ask(before, existing, mergeAllowed)
                ConflictPolicy.KEEP_BOTH -> ConflictDecision(ConflictAction.KEEP_BOTH)
                ConflictPolicy.SKIP -> ConflictDecision(ConflictAction.SKIP)
                ConflictPolicy.REPLACE -> ConflictDecision(ConflictAction.REPLACE)
                ConflictPolicy.MERGE ->
                    if (mergeAllowed && before.folder && existing.folder)
                        ConflictDecision(ConflictAction.MERGE)
                    else ask(before, existing, mergeAllowed)
            }
        if (answer.applyAll)
            policy =
                when (answer.action) {
                    ConflictAction.KEEP_BOTH -> ConflictPolicy.KEEP_BOTH
                    ConflictAction.SKIP -> ConflictPolicy.SKIP
                    ConflictAction.REPLACE -> ConflictPolicy.REPLACE
                    ConflictAction.MERGE -> ConflictPolicy.MERGE
                    else -> policy
                }
        return answer
    }

    private suspend fun transferOne(
        path: String,
        parent: String,
        move: Boolean,
        mergeAllowed: Boolean,
    ): TransferItemResult {
        val ownerContext = currentCoroutineContext().minusKey(Job)
        val verification = if (job.verifyCopies)
            CopyVerificationSession(path, MAX_COPY_VERIFICATION_RESULTS - current().verifications.size) { records ->
                // Synchronous file APIs invoke this only on Dispatchers.IO. Wait for the model's
                // single-writer checkpoint before continuing an unpublished file.
                runBlocking { withContext(ownerContext) { saveVerification(records) } }
            }
        else null
        try {
            return performTransferOne(path, parent, move, mergeAllowed, verification)
        } finally {
            saveVerification(verification?.results.orEmpty())
        }
    }

    private suspend fun saveVerification(records: List<CopyVerificationResult>) {
        if (records.isEmpty()) return
        save { latest ->
            val keys = records.map { it.root to it.source }.toSet()
            latest.copy(verifications = latest.verifications.filterNot { (it.root to it.source) in keys } + records)
        }
    }

    private suspend fun performTransferOne(
        path: String,
        parent: String,
        move: Boolean,
        mergeAllowed: Boolean,
        verification: CopyVerificationSession?,
    ): TransferItemResult {
        val before = io {
            documents.validateTransfer(path, parent, move)
            documents.read(path)
        }
        val approved = partialApproval?.takeIf { it.record.source == path }
        check(approved == null || !move && !before.folder && File(approved.record.target).parent == parent) {
            "The reviewed copy no longer matches this transfer."
        }
        val existing = io {
            documents.list(parent).firstOrNull { it.name == (approved?.record?.target?.let { File(it).name } ?: before.name) && it.path != path }
        }
        check(approved == null || existing == null) { "An item now exists at the destination. Review it before restarting; it was kept." }
        var desiredName: String? = approved?.record?.target?.let { File(it).name }
        var replaced: Pair<Entry, File>? = null
        if (existing != null) {
            val answer = decision(before, existing, mergeAllowed)
            when (answer.action) {
                ConflictAction.CANCEL -> {
                    control.cancel()
                    io { control.check() }
                }
                ConflictAction.SKIP -> {
                    io { verification?.skipped(path) }
                    return TransferItemResult(path, skipped = true)
                }
                ConflictAction.RENAME -> desiredName = validateFilename(answer.name.orEmpty())
                ConflictAction.MERGE -> {
                    check(mergeAllowed && before.folder && existing.folder) {
                        "These items cannot be merged."
                    }
                    return merge(before, existing)
                }
                ConflictAction.REPLACE -> {
                    check(existing.canDelete) { "The existing item cannot be replaced." }
                    val stash = io {
                        documents.validateReplacement(path, existing.path)
                        val latest = documents.read(existing.path)
                        check(
                            latest.size == existing.size &&
                                latest.modified == existing.modified &&
                                latest.folder == existing.folder,
                        ) {
                            "The destination changed while choosing. Review it and retry."
                        }
                        documents.trash(existing.path, control)
                    }
                    replaced = existing to stash
                    try {
                        metadata(listOf(existing.path to stash.path), true)
                    } catch (error: Exception) {
                        throw TransferPublicationException(
                            stash.path,
                            "The original is in Trash; updating its metadata failed: ${error.message}",
                            error,
                        )
                    }
                    desiredName = existing.name
                }
                else -> Unit
            }
        }
        val ownerContext = currentCoroutineContext().minusKey(Job)
        val partial = if (!move && !before.folder && !isDocumentPath(path) && !isDocumentPath(parent) && replaced == null &&
            (approved != null || before.size >= PARTIAL_COPY_MINIMUM_BYTES)) {
            io { documents.partialCopySession(approved) { record, notice, reused ->
                runBlocking { withContext(ownerContext) {
                    save { it.copy(partialCopy = record, partialCopyNotice = notice, reusedBytes = maxOf(it.reusedBytes, reused)) }
                } }
            } }
        } else null
        if (partial == null) save { it.copy(partialCopyNotice = when {
            isDocumentPath(path) || isDocumentPath(parent) -> "Connected-storage streams must restart after interruption. Review the destination before retrying."
            move || before.folder || replaced != null -> "Moves, folders and replacements must restart after interruption. Review the destination before retrying."
            else -> "This file is copied without a saved checkpoint. Review the destination before retrying after interruption."
        }) }
        val published =
            try {
                io { documents.copy(path, parent, move, control, desiredName, verification = verification, partial = partial) }
                    .also { if (partial?.completed == true) completedPartialSources += path }
            } catch (error: Exception) {
                if (replaced != null && error !is TransferPublicationException) {
                    val restored = io { documents.restore(replaced.second) }
                    metadata(listOf(replaced.second.path to restored), true)
                }
                throw error
            }
        val mappings = documents.lastCopyMappings.ifEmpty { listOf(before.path to published) }
        control.phase("Preparing Undo", stage = TransferStage.VERIFYING)
        val historyError =
            try {
                steps += io {
                    if (replaced != null)
                        replacementStep(
                            documents,
                            replaced.first,
                            replaced.second,
                            before,
                            published,
                            move,
                        )
                    else if (move) documents.movedStep(before, published)
                    else documents.createdStep(published)
                }
                null
            } catch (error: Exception) {
                "File transferred; Undo unavailable: ${error.message}"
            }
        val metadataError =
            try {
                metadata(mappings, move)
                null
            } catch (error: Exception) {
                "File transferred; metadata update failed: ${error.message}"
            }
        return TransferItemResult(
            path,
            published,
            listOfNotNull(historyError, metadataError)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("; "),
        )
    }

    private suspend fun savePlan(plan: MergePlan) = save {
        it.copy(mergePlans = it.mergePlans.filterNot { p -> p.source == plan.source } + plan)
    }

    private suspend fun merge(source: Entry, target: Entry): TransferItemResult {
        var plan = io {
            documents.validateTransfer(source.path, target.path, job.move)
            require(target.documentUri != null || !Files.isSymbolicLink(target.file.toPath())) {
                "A destination folder link cannot be merged implicitly."
            }
            control.phase("Verifying source", stage = TransferStage.VERIFYING)
            val stamp = documents.digest(source.path, control)
            val members =
                planMergeMembers(
                    source.path,
                    target.path,
                    { path ->
                        documents.list(path).map {
                            MergeNode(
                                it.path,
                                it.name,
                                it.folder,
                                it.documentUri == null && Files.isSymbolicLink(it.file.toPath()),
                            )
                        }
                    },
                    control,
                )
            members.forEach { documents.validateTransfer(it.source, it.parent, job.move) }
            check(documents.digest(source.path, control) == stamp) {
                "The source changed during merge planning. Retry after reviewing it."
            }
            MergePlan(source.path, target.path, stamp, members)
        }
        // No destination member is written before this complete plan is durable.
        save {
            it.copy(
                mergePlans = it.mergePlans + plan,
                results =
                    it.results +
                        TransferItemResult(
                            source.path,
                            target.path,
                            "Merge unfinished; review member results.",
                        ),
                inFlight = null,
            )
        }
        for (member in plan.members) {
            io { control.check() }
            save {
                it.copy(inFlight = member.source, current = member.source.substringAfterLast('/'))
            }
            val outcome =
                try {
                    transferOne(member.source, member.parent, false, false)
                } catch (error: Exception) {
                    if (error is CheckpointFailure) throw error
                    io { control.check() }
                    TransferItemResult(
                        member.source,
                        (error as? TransferPublicationException)?.destination,
                        error.message ?: "Transfer failed",
                    )
                }
            plan =
                plan.copy(
                    members =
                        plan.members.map {
                            if (it.source == member.source)
                                it.copy(
                                    destination = outcome.destination,
                                    error = outcome.error,
                                    skipped = outcome.skipped,
                                    cleanup =
                                        if (job.move) SourceCleanup.PENDING else SourceCleanup.KEPT,
                                )
                            else it
                        },
                )
            savePlan(plan)
            save { it.copy(inFlight = null) }
        }
        if (job.move) plan = cleanupSource(plan, source)
        else {
            plan = plan.copy(cleanup = SourceCleanup.KEPT)
            savePlan(plan)
        }
        val errors = plan.members.count { it.error != null }
        val error = plan.error ?: if (errors > 0) "$errors merge members need attention." else null
        return TransferItemResult(source.path, target.path, error)
    }

    private suspend fun cleanupSource(original: MergePlan, source: Entry): MergePlan {
        var plan = original
        val copied =
            plan.members.filter { it.destination != null && it.error == null && !it.skipped }
        val complete = copied.size == plan.members.size
        try {
            io {
                control.check()
                control.phase("Verifying merge", stage = TransferStage.VERIFYING)
                check(documents.digest(source.path, control) == plan.sourceStamp) {
                    "The source changed during the merge. All sources were kept; review the destination copies."
                }
                copied.forEach { member ->
                    check(
                        documents.digest(member.source, control) ==
                            documents.digest(member.destination!!, control),
                    ) {
                        "A destination copy differs from its source. Sources were kept; review the merge."
                    }
                }
            }
        } catch (error: Exception) {
            io { control.check() }
            return plan.copy(cleanup = SourceCleanup.KEPT, error = error.message).also {
                savePlan(it)
            }
        }
        val units = if (complete) listOf(source.path) else copied.map { it.source }
        for (path in units) {
            io { control.check() }
            save {
                it.copy(
                    inFlight = path,
                    current = "Recovering source: ${path.substringAfterLast('/')}",
                )
            }
            var recovered: File? = null
            try {
                val before = io { documents.read(path) }
                if (!complete) {
                    val member = copied.first { it.source == path }
                    io {
                        check(
                            documents.digest(path, control) ==
                                documents.digest(member.destination!!, control),
                        ) {
                            "The source or destination changed before cleanup. Source kept."
                        }
                    }
                }
                val recovery = io { documents.trash(path, control) }
                recovered = recovery
                // This step restores source units before Undo removes their destination copies.
                try {
                    steps += io { documents.trashedStep(before, recovery, relocateMetadata = true) }
                } catch (error: Exception) {
                    blockUndo(
                        "The merge source is in Trash, but its Undo record could not be created. Restore it from Trash before changing the destination.",
                    )
                    throw TransferPublicationException(
                        recovery.path,
                        error.message ?: "Undo unavailable",
                        error,
                    )
                }
                metadata(documents.trashMappings(recovery), true)
                plan =
                    if (complete)
                        plan.copy(
                            cleanup = SourceCleanup.RECOVERED,
                            recovery = recovery.path,
                            members =
                                plan.members.map { it.copy(cleanup = SourceCleanup.RECOVERED) },
                        )
                    else
                        plan.copy(
                            members =
                                plan.members.map {
                                    if (it.source == path)
                                        it.copy(
                                            cleanup = SourceCleanup.RECOVERED,
                                            recovery = recovery.path,
                                        )
                                    else it
                                },
                        )
                savePlan(plan)
                save { it.copy(inFlight = null) }
            } catch (error: Exception) {
                if (error is CheckpointFailure) throw error
                // Cleanup may already have removed some source data. Keep all destination copies.
                blockUndo(
                    "Merge source cleanup stopped. Review source, destination and Trash before Undo: ${error.message}",
                )
                val recovery =
                    (error as? TransferPublicationException)?.destination ?: recovered?.path
                plan =
                    if (complete)
                        plan.copy(
                            cleanup = SourceCleanup.FAILED,
                            recovery = recovery,
                            error = error.message,
                        )
                    else
                        plan.copy(
                            error = "Some sources could not be recovered: ${error.message}",
                            members =
                                plan.members.map {
                                    if (it.source == path)
                                        it.copy(
                                            cleanup = SourceCleanup.FAILED,
                                            recovery = recovery,
                                            error = error.message,
                                        )
                                    else it
                                },
                        )
                savePlan(plan)
                io { control.check() }
                save { it.copy(inFlight = null) }
                break
            }
        }
        if (!complete && plan.error == null) {
            plan = plan.copy(cleanup = SourceCleanup.KEPT)
            savePlan(plan)
        }
        return plan
    }

    private fun blockUndo(message: String) {
        steps += FileStep.Callback(check = { error(message) }, perform = { error(message) })
    }
}
