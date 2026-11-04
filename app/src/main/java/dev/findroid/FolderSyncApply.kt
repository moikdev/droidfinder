package dev.findroid

import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

internal data class FolderSyncAppliedItem(
    val operation: FolderSyncEntry,
    val originalRecovery: File?,
    val published: String?,
)

internal data class FolderSyncApplyState(
    val workingDirectory: String,
    val journalDirectory: String?,
    val completed: List<FolderSyncAppliedItem>,
    val preservedOriginals: List<FolderSyncPreservationIntent>,
    val publishedRoots: List<FolderSyncPublicationIntent>,
    val preservation: FolderSyncPreservationIntent?,
    val publication: FolderSyncPublicationIntent?,
    val renameAttempted: Boolean,
)

internal class FolderSyncApplyFailure(val state: FolderSyncApplyState, cause: Exception) :
    Exception("Synchronization stopped. Completed files, originals in Trash and working data were kept for manual review. ${cause.message}", cause)

internal class FolderSyncApplyCancelled(val state: FolderSyncApplyState, cause: Exception) :
    CancellationException("Synchronization cancelled. Completed files, originals in Trash and working data were kept for manual review.") {
    init { initCause(cause) }
}

/** All journal records must fit before an original can move, including every update's two moves. */
internal fun checkFolderSyncJournalBudget(prepared: PreparedFolderSync, recovery: FolderSyncTrashLocation?) {
    var records = 0
    var bytes = 0L
    fun record(value: JsonObject) {
        val size = value.toString().toByteArray(Charsets.UTF_8).size
        records++; bytes += size
        check(records <= 20_002 && size <= 64 * 1024 && bytes <= 32L * 1024 * 1024) {
            "This complete plan would exceed the synchronization journal limit. Select fewer changes and review again."
        }
    }
    record(JsonObject().apply {
        addProperty("schema", 1); addProperty("event", "created")
        addProperty("purpose", "Manual review only; never replay automatically")
        addProperty("workingDirectory", prepared.directory); addProperty("destination", prepared.review.preview.destinationRoot)
    })
    val staged = prepared.roots.associateBy { it.operation.relative }
    for (operation in prepared.review.operations) {
        fun move(kind: String, from: String, to: String) {
            for (status in listOf("intent", "complete")) record(JsonObject().apply {
                addProperty("schema", 1); addProperty("event", "$kind-$status")
                addProperty(if (kind == "preservation") "original" else "working", from)
                addProperty(if (kind == "preservation") "recovery" else "destination", to)
            })
        }
        if (operation.category != FolderSyncCategory.ADDITION) {
            val parent = recovery ?: error("The original recovery location is missing.")
            // FileEngine allocates a canonical UUID. Every possible value has this encoded length.
            val target = File(File(parent.path, "00000000-0000-0000-0000-000000000000"), File(operation.destinationPath).name)
            move("preservation", operation.destinationPath, target.path)
        }
        if (operation.category != FolderSyncCategory.DELETION)
            move("publication", staged.getValue(operation.relative).path, operation.destinationPath)
    }
    record(JsonObject().apply { addProperty("schema", 1); addProperty("event", "finished") })
}

/**
 * Applies only the prepared choices. The two callbacks must save metadata/history after each
 * verified move and flush their durable state before the run can finish. No rollback, replay,
 * deletion or adoption of an unrelated tree change is available here. Call from an IO dispatcher.
 */
internal fun applyPreparedFolderSync(
    prepared: PreparedFolderSync,
    engine: FileEngine,
    journalParent: String,
    allowed: (String) -> Boolean,
    preserved: (FolderSyncEntry, File) -> Unit,
    published: (FolderSyncEntry, File?) -> Unit,
    finishMetadata: () -> Unit,
    files: UndoHistoryFiles,
    storage: PartialCopyStorage = AndroidPartialCopyStorage(),
    control: TransferControl = TransferControl(),
    journalOwner: () -> String = { UUID.randomUUID().toString() },
): FolderSyncApplyState {
    var journalPath: String? = null
    var preservation: FolderSyncPreservationIntent? = null
    var publication: FolderSyncPublicationIntent? = null
    var renameAttempted = false
    val completed = mutableListOf<FolderSyncAppliedItem>()
    val preservedOriginals = mutableListOf<FolderSyncPreservationIntent>()
    val publishedRoots = mutableListOf<FolderSyncPublicationIntent>()
    fun state() = FolderSyncApplyState(prepared.directory, journalPath, completed.toList(), preservedOriginals.toList(), publishedRoots.toList(), preservation, publication, renameAttempted)
    try {
        check(prepared.review.operations.isNotEmpty()) { "Choose at least one change before synchronizing." }
        checkPreparedFolderSync(prepared, allowed, files, storage, control)
        val inspection = prepared.inspection
        val source = if (prepared.review.preview.sourceRoot == inspection.primaryProof.path) inspection.primaryProof else inspection.secondaryProof
        var destination = if (prepared.review.preview.destinationRoot == inspection.primaryProof.path) inspection.primaryProof else inspection.secondaryProof
        var working = prepared.proof
        val originals = prepared.review.operations.filter { it.category != FolderSyncCategory.ADDITION }
            .map { folderSyncSubtreeProof(destination, it.destinationPath) }
        val recovery = originals.takeIf { it.isNotEmpty() }?.let { engine.reviewSyncTrash(it, files, storage, control) }
        fun overlaps(a: String, b: String) = File(a).toPath().startsWith(File(b).toPath()) || File(b).toPath().startsWith(File(a).toPath())
        if (recovery != null) check(listOf(source.resolved, destination.resolved, working.resolved).none { overlaps(it, recovery.resolved) }) {
            "The original recovery location overlaps a reviewed or working folder. Choose separate folders."
        }
        val resolvedJournalParent = storage.canonical(journalParent)
        check(listOf(source.resolved, destination.resolved, working.resolved).none {
            File(resolvedJournalParent).toPath().startsWith(File(it).toPath())
        }) { "Keep the synchronization journal outside the compared and working folders." }
        checkFolderSyncJournalBudget(prepared, recovery)
        val owner = journalOwner()
        check(runCatching { UUID.fromString(owner).toString() == owner }.getOrDefault(false)) { "The synchronization journal identifier is invalid." }
        journalPath = File(journalParent, "sync-$owner").path
        val journal = FolderSyncPublicationLog.create(journalParent, working.path, destination.path, storage) { owner }
        data class Recovered(val original: String, val proof: UndoPathProof, val contents: Map<String, String>)
        val recovered = mutableListOf<Recovered>()
        val destinationContents = inspection.contents.filterKeys { it in destination.entries }.toMutableMap()
        val sourceContents = inspection.contents.filterKeys { it in source.entries }
        val staged = prepared.roots.associateBy { it.operation.relative }
        fun gate(deep: Boolean = false) {
            control.check()
            val paths = setOf(source.path, source.resolved, destination.path, destination.resolved,
                working.path, working.resolved, working.anchor, working.resolvedAnchor)
            check(paths.all(allowed)) { "Folder access changed. Reconnect the folders and review again." }
            val proofs = listOf(source, destination, working) + recovered.map { it.proof }
            check(proofs.all { files.observe(it.path) == it }) { "A source, destination, recovery item or working copy changed. Compare again." }
            recovery?.let {
                engine.checkSyncRecoveryIndex(storage, control)
                check(storage.stat(it.path)?.sameObject(it.identity) == true && storage.canonical(it.path) == it.resolved) {
                    "The reviewed original recovery location changed. Keep the records for manual review."
                }
            }
            for (item in recovered) check(engine.originalPath(File(item.proof.path)) == item.original) {
                "An original recovery mapping changed."
            }
            if (deep) {
                val stamps = proofs.flatMap { it.entries.entries }.associate { it.key to it.value }
                val contents = sourceContents + destinationContents + recovered.flatMap { it.contents.entries }.associate { it.key to it.value }
                for ((path, checksum) in contents) check(folderSyncDigest(path, stamps.getValue(path), control, storage) == checksum) {
                    "Reviewed contents changed. Keep the working and recovery data and compare again."
                }
                check(proofs.all { files.observe(it.path) == it }) { "A reviewed folder changed during its final content check." }
            }
        }
        gate()
        for (operation in prepared.review.operations) {
            gate()
            control.phase("Applying reviewed synchronization", stage = TransferStage.WORKING)
            var saved: File? = null
            if (operation.category != FolderSyncCategory.ADDITION) {
                val before = folderSyncSubtreeProof(destination, operation.destinationPath)
                val hashes = destinationContents.filterKeys { it in before.entries }
                try {
                    saved = engine.trashReviewedByRename(before, hashes, files, storage, control, recovery) { intent ->
                        gate(); journal.beginPreservation(intent); preservation = intent; gate()
                    }
                    renameAttempted = true
                } catch (error: FolderSyncRecoveryFailure) { renameAttempted = renameAttempted || error.moveAttempted; throw error }
                val after = files.observe(destination.path)
                checkFolderSyncTreeChange(destination, after, operation.destinationPath)
                val proof = files.observe(saved.path)
                checkFolderSyncRelocation(before.path, before.entries, proof, hashes) { path, stamp -> folderSyncDigest(path, stamp, control, storage) }
                destination = after
                destinationContents.keys.removeAll(before.entries.keys)
                recovered += Recovered(before.path, proof, hashes.mapKeys { (path, _) -> saved.path + path.removePrefix(before.path) })
                preservedOriginals += preservation!!
                journal.completePreservation(preservation!!)
                gate(); preserved(operation, saved); gate()
                preservation = null
            }
            if (operation.category != FolderSyncCategory.DELETION) {
                val item = staged.getValue(operation.relative)
                val root = folderSyncSubtreeProof(working, item.path)
                val hashes = root.entries.filterValues { it.regular }.keys.associateWith { path ->
                    inspection.contents.getValue(operation.source!!.path + path.removePrefix(item.path))
                }
                val result = try {
                    publishFolderSyncRoot(destination, working, root, operation.destinationPath, hashes, allowed, { intent ->
                        gate(); journal.begin(intent); publication = intent; gate()
                    }, files, storage, control)
                } catch (error: FolderSyncPublicationFailure) { renameAttempted = renameAttempted || error.moveAttempted; throw error }
                renameAttempted = true
                destination = result.destination; working = result.working
                destinationContents.putAll(hashes.mapKeys { (path, _) -> operation.destinationPath + path.removePrefix(item.path) })
                publishedRoots += publication!!
                journal.complete(publication!!)
                gate(); published(operation, saved); gate()
                publication = null
            }
            completed += FolderSyncAppliedItem(operation, saved, operation.destinationPath.takeIf { operation.category != FolderSyncCategory.DELETION })
            control.item(File(operation.destinationPath).name)
        }
        gate(deep = true)
        finishMetadata()
        gate()
        journal.finish()
        gate()
        return state()
    } catch (error: Exception) {
        if (control.cancelled || error is CancellationException) throw FolderSyncApplyCancelled(state(), error)
        throw FolderSyncApplyFailure(state(), error)
    }
}
