package dev.findroid

import java.io.File

internal data class FolderSyncPublicationIntent(val working: String, val destination: String)
internal data class FolderSyncPublication(
    val destination: UndoPathProof,
    val working: UndoPathProof,
    val published: UndoPathProof,
)

internal class FolderSyncPublicationFailure(
    val intent: FolderSyncPublicationIntent,
    val moveAttempted: Boolean,
    val moved: Boolean,
    cause: Exception,
) : Exception(when {
    moved -> "Synchronization stopped after a prepared item was published. Completed files and recovery data were kept for manual review."
    moveAttempted -> "The publication stopped. Review the destination and the saved working copies before continuing."
    else -> "Synchronization stopped before publication. Original files and working copies were kept."
} + " " + cause.message, cause)

/**
 * One native no-replace publication, after the complete-plan gate has passed. The caller must
 * durably record the intent outside these two trees before the rename. A failed or ambiguous
 * result is never rolled back or deleted. Remaining operations require a fresh gate against
 * the returned, exactly validated transition; this primitive does not execute a whole plan.
 */
internal fun publishFolderSyncRoot(
    destinationBefore: UndoPathProof,
    workingBefore: UndoPathProof,
    root: UndoPathProof,
    target: String,
    contents: Map<String, String>,
    allowed: (String) -> Boolean,
    recordIntent: (FolderSyncPublicationIntent) -> Unit,
    files: UndoHistoryFiles,
    storage: PartialCopyStorage = AndroidPartialCopyStorage(),
    control: TransferControl = TransferControl(),
): FolderSyncPublication {
    val intent = FolderSyncPublicationIntent(root.path, target)
    var moveAttempted = false
    var moved = false
    try {
        val destination = File(destinationBefore.path).toPath()
        val working = File(workingBefore.path).toPath()
        val targetFile = File(target)
        check(destination.isAbsolute && working.isAbsolute && !destination.startsWith(working) && !working.startsWith(destination) &&
            targetFile.isAbsolute && targetFile.toPath().normalize().toString() == target &&
            target != destinationBefore.path && targetFile.toPath().startsWith(destination)) {
            "The publication locations are outside their reviewed folders. Compare again."
        }
        check(root.path != workingBefore.path && folderSyncSubtreeProof(workingBefore, root.path) == root &&
            contents.keys == root.entries.filterValues { it.regular }.keys && contents.values.all { hash ->
                hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
            }) { "The prepared publication proof is incomplete. Compare again." }
        val parent = destinationBefore.entries[targetFile.parentFile?.path]
        check(parent?.directory == true && !parent.regular &&
            root.entries.values.all { it.device == parent.device && it.regular != it.directory && (it.directory || it.links == 1L) } &&
            destinationBefore.entries.keys.none { it == target || it.startsWith(target + "/") }) {
            "The destination name, parent, or storage cannot accept this prepared item."
        }
        val paths = setOf(destinationBefore.path, destinationBefore.resolved, workingBefore.path, workingBefore.resolved,
            destinationBefore.anchor, destinationBefore.resolvedAnchor, workingBefore.anchor, workingBefore.resolvedAnchor)
        fun guard() {
            control.check()
            check(paths.all(allowed)) { "Folder access changed. Reconnect the folders before synchronizing." }
            check(files.observe(destinationBefore.path) == destinationBefore && files.observe(workingBefore.path) == workingBefore) {
                "A reviewed folder or working copy changed. Compare again."
            }
            check(storage.stat(target) == null && storage.canonical(target) ==
                destinationBefore.resolved + target.removePrefix(destinationBefore.path)) {
                "The destination name became occupied or redirected. Its contents were kept."
            }
        }
        guard()
        for ((path, hash) in contents) {
            control.check()
            check(folderSyncDigest(path, root.entries.getValue(path), control, storage) == hash) {
                "A prepared file no longer matches its reviewed contents."
            }
        }
        guard()
        recordIntent(intent)
        guard()
        moveAttempted = true
        files.moveNoReplace(File(root.path), targetFile)
        moved = true
        val destinationAfter = files.observe(destinationBefore.path)
        val workingAfter = files.observe(workingBefore.path)
        val published = files.observe(target)
        checkFolderSyncTreeChange(destinationBefore, destinationAfter, target, root)
        checkFolderSyncTreeChange(workingBefore, workingAfter, root.path)
        check(published == folderSyncSubtreeProof(destinationAfter, target)) { "The published item changed during review." }
        checkFolderSyncRelocation(root.path, root.entries, published, contents) { path, stamp ->
            folderSyncDigest(path, stamp, control, storage)
        }
        files.sync(setOf(root.path, target))
        check(paths.all(allowed) && files.observe(destinationBefore.path) == destinationAfter &&
            files.observe(workingBefore.path) == workingAfter) { "The published folders changed while they were saved." }
        return FolderSyncPublication(destinationAfter, workingAfter, published)
    } catch (error: Exception) {
        throw FolderSyncPublicationFailure(intent, moveAttempted, moved, error)
    }
}
