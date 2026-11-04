package dev.findroid

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

internal data class FolderSyncTrashLocation(val path: String, val resolved: String, val identity: PartialFileStamp)

internal data class FolderSyncStagedRoot(val operation: FolderSyncEntry, val path: String)
internal data class PreparedFolderSync(
    val inspection: FolderSyncInspection,
    val review: ReviewedFolderSync,
    val directory: String,
    val proof: UndoPathProof,
    val roots: List<FolderSyncStagedRoot>,
)

internal class FolderSyncRecoveryFailure(val recovery: File?, cause: Exception, val moveAttempted: Boolean = false) :
    Exception(when {
        recovery != null -> "The original was moved to Trash before synchronization stopped. Its recovery record and working data were kept. ${cause.message}"
        moveAttempted -> "The recovery move stopped. Review the original location and Trash before continuing. ${cause.message}"
        else -> "Original files were kept. ${cause.message}"
    }, cause)

internal class FolderSyncPreparationFailure(val workingDirectory: String?, cause: Exception) :
    Exception(if (workingDirectory == null) cause.message else
        "Synchronization preparation stopped. Original files and the working copies were kept for review. ${cause.message}", cause)

internal class FolderSyncPreparationCancelled(val workingDirectory: String?, cause: CancellationException) :
    CancellationException(if (workingDirectory == null) "Synchronization preparation cancelled." else
        "Synchronization preparation cancelled. Original files and the working copies were kept for review.") {
    init { initCause(cause) }
}

/** A durable manual-review record; no decoder or automatic replay is provided. */
private fun folderSyncReviewRecord(inspection: FolderSyncInspection, review: ReviewedFolderSync): ByteArray {
    fun stamp(value: PartialFileStamp) = JsonObject().apply {
        addProperty("device", value.device.toString());addProperty("inode", value.inode.toString())
        addProperty("size", value.size.toString());addProperty("modifiedSeconds", value.modifiedSeconds.toString())
        addProperty("modifiedNanos", value.modifiedNanos.toString());addProperty("changedSeconds", value.changedSeconds.toString())
        addProperty("changedNanos", value.changedNanos.toString());addProperty("regular", value.regular)
        addProperty("directory", value.directory);addProperty("links", value.links.toString())
    }
    fun proof(value: UndoPathProof) = JsonObject().apply {
        addProperty("path", value.path);addProperty("resolved", value.resolved);addProperty("anchor", value.anchor)
        addProperty("resolvedAnchor", value.resolvedAnchor);addProperty("anchorDevice", value.anchorDevice.toString())
        addProperty("anchorInode", value.anchorInode.toString())
        add("entries", JsonObject().apply { value.entries.forEach { (path, identity) -> add(path, stamp(identity)) } })
    }
    return JsonObject().apply {
        addProperty("schema", 1);addProperty("purpose", "Manual review only; never replay automatically")
        addProperty("source", review.preview.sourceRoot);addProperty("destination", review.preview.destinationRoot)
        addProperty("direction", review.preview.direction.name);addProperty("updatesConfirmed", review.updatesConfirmed)
        addProperty("trashConfirmed", review.trashConfirmed)
        add("operations", JsonArray().apply { review.operations.forEach { operation -> add(JsonObject().apply {
            addProperty("relative", operation.relative);addProperty("category", operation.category.name)
            addProperty("destination", operation.destinationPath);addProperty("source", operation.source?.path)
        }) } })
        add("primaryProof", proof(inspection.primaryProof));add("secondaryProof", proof(inspection.secondaryProof))
        add("contents", JsonObject().apply { inspection.contents.forEach { (path, checksum) -> addProperty(path, checksum) } })
    }.toString().toByteArray(Charsets.UTF_8).also { check(it.size <= 8 * 1024 * 1024) { "This synchronization needs a smaller review." } }
}

/**
 * Materializes only the exact disclosed nodes into a new sibling working directory. The live
 * source and destination trees remain unchanged, including destination-only items. A reviewed
 * root on a different device from its parent is refused because sibling publication could not
 * be atomic. An interruption preserves the review and whatever working data was completed.
 */
internal fun prepareFolderSync(
    inspection: FolderSyncInspection,
    review: ReviewedFolderSync,
    allowed: (String) -> Boolean,
    files: UndoHistoryFiles,
    storage: PartialCopyStorage = AndroidPartialCopyStorage(),
    control: TransferControl = TransferControl(),
    owner: () -> String = { UUID.randomUUID().toString() },
): PreparedFolderSync {
    var working: String? = null
    try {
        control.check()
        val specs = folderSyncCopySpecs(inspection, review)
        check(review.operations.isNotEmpty()) { "Choose at least one reviewed change." }
        val destinationProof = if (review.preview.direction == FolderSyncDirection.PRIMARY_TO_SECONDARY)
            inspection.secondaryProof else inspection.primaryProof
        check(destinationProof.path == review.preview.destinationRoot) { "The destination review changed. Compare again." }
        val parentPath = File(destinationProof.path).parentFile?.path ?: error("Choose a destination with an available parent folder.")
        val requiredAccess = setOf(inspection.primaryProof.path, inspection.secondaryProof.path,
            inspection.primaryProof.resolved, inspection.secondaryProof.resolved, parentPath, destinationProof.resolvedAnchor)
        check(parentPath == destinationProof.anchor && requiredAccess.all(allowed)) {
            "Reconnect the reviewed folders and their working location before synchronizing."
        }
        val parent = storage.stat(parentPath) ?: error("The destination parent is unavailable.")
        val destination = destinationProof.entries[destinationProof.path] ?: error("The reviewed destination is unavailable.")
        check(parent.directory && !parent.regular && parent.device == destination.device &&
            parent.device == destinationProof.anchorDevice && parent.inode == destinationProof.anchorInode &&
            storage.canonical(parentPath) == destinationProof.resolvedAnchor) {
            "This destination cannot prepare an atomic synchronization beside its folder. Choose another local folder."
        }
        val record = folderSyncReviewRecord(inspection, review)
        checkFolderSyncInspection(inspection, files, control)
        val id = owner()
        check(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "A working folder identifier could not be allocated." }
        val directory = File(parentPath, ".findroid-sync-$id").path
        check(storage.stat(directory) == null) { "The working folder already exists. Its contents were kept." }
        storage.createDirectory(directory);working = directory
        val owned = storage.stat(directory) ?: error("The working folder could not be created.")
        check(owned.directory && !owned.regular && owned.device == parent.device) { "The working folder changed during preparation." }
        val resolved = storage.canonical(directory)
        check(resolved == File(destinationProof.resolvedAnchor, File(directory).name).path) { "The working folder was redirected." }
        val expected = linkedMapOf(directory to owned)
        fun guard() {
            control.check()
            check(requiredAccess.all(allowed)) { "Folder access changed. Original files were kept." }
            check(storage.stat(parentPath)?.sameObject(parent) == true && storage.canonical(parentPath) == destinationProof.resolvedAnchor &&
                storage.stat(directory)?.sameObject(owned) == true && storage.canonical(directory) == resolved) { "The working folder changed. Original files were kept." }
        }
        fun saveNew(name: String, bytes: ByteArray) {
            guard()
            val path = File(directory, name).path
            storage.open(path, writable = true, createNew = true).use { handle ->
                val fresh = handle.stamp()
                check(fresh.regular && !fresh.directory && fresh.links == 1L && fresh.size == 0L && fresh.device == owned.device && storage.stat(path) == fresh)
                var offset = 0
                while (offset < bytes.size) {
                    control.check();val count = handle.write(bytes, offset, bytes.size - offset)
                    check(count in 1..(bytes.size - offset));offset += count
                }
                val finished = handle.stamp()
                check(finished.sameObject(fresh) && finished.size == bytes.size.toLong() && finished.links == 1L && storage.stat(path) == finished)
                handle.sync();check(handle.stamp() == finished && storage.stat(path) == finished)
                expected[path] = finished
            }
            storage.open(directory, directory = true).use { handle -> check(handle.stamp().sameObject(owned));handle.sync() }
            guard()
        }
        saveNew("review.json", record)
        storage.open(parentPath, directory = true).use { handle -> check(handle.stamp().sameObject(parent));handle.sync() }
        val staged = mutableListOf<FolderSyncStagedRoot>()
        for ((index, operation) in review.operations.withIndex()) {
            guard()
            if (operation.category == FolderSyncCategory.DELETION) continue
            val path = File(directory, "copy-" + index.toString().padStart(5, '0')).path
            val source = operation.source ?: error("A reviewed copy source is missing.")
            val members = review.preview.entries.filter { it.relative == operation.relative || it.coveredBy == operation.relative }
                .sortedWith(compareBy<FolderSyncEntry> { it.relative.count { char -> char == '/' } }.thenBy { it.relative })
            check(members.isNotEmpty() && members.first().relative == operation.relative)
            for (member in members) {
                guard()
                val node = member.source ?: error("A reviewed subtree contains an inconsistent item.")
                val relative = if (member.relative == operation.relative) "" else member.relative.removePrefix(operation.relative + "/")
                val target = if (relative.isEmpty()) path else File(path, relative).path
                val targetParent = File(target).parentFile!!.path
                val parentStamp = expected[targetParent] ?: error("The working parent was not disclosed in the review.")
                val targetResolvedParent = File(resolved, File(directory).toPath().relativize(File(targetParent).toPath()).toString()).path
                check(storage.stat(targetParent)?.sameObject(parentStamp) == true && storage.canonical(targetParent) == targetResolvedParent && storage.stat(target) == null) {
                    "A working subfolder changed. Original files were kept."
                }
                if (node.folder) {
                    storage.createDirectory(target)
                    val created = storage.stat(target) ?: error("The working subfolder could not be created.")
                    check(created.directory && !created.regular && created.device == owned.device)
                    expected[target] = created
                } else {
                    expected[target] = stageFolderSyncFile(specs.getValue(node.path), target, parentStamp, targetResolvedParent, control, storage)
                }
            }
            check(source.path in inspection.primaryProof.entries || source.path in inspection.secondaryProof.entries)
            staged += FolderSyncStagedRoot(operation, path)
        }
        guard()
        checkFolderSyncInspection(inspection, files, control)
        saveNew("prepared.json", JsonObject().apply {
            addProperty("schema", 1);addProperty("status", "Prepared; manual review only")
            add("stagedRoots", JsonArray().apply { staged.forEach { root -> add(JsonObject().apply {
                addProperty("relative", root.operation.relative);addProperty("working", root.path)
                addProperty("destination", root.operation.destinationPath)
            }) } })
        }.toString().toByteArray(Charsets.UTF_8))
        // Root and child directory timestamps change during our own creation; identities may not.
        val completed = files.observe(directory)
        check(completed.entries.keys == expected.keys && completed.entries.all { (path, stamp) ->
            expected.getValue(path).let { if (it.directory) stamp.sameObject(it) else stamp == it }
        }) { "The working tree changed or contains unreviewed files. Original files were kept." }
        checkFolderSyncInspection(inspection, files, control)
        files.sync(setOf(directory));guard()
        check(files.observe(directory) == completed) { "The prepared copies changed while they were saved." }
        return PreparedFolderSync(inspection, review, directory, completed, staged.toList())
    } catch (error: Exception) {
        if (error is CancellationException) throw FolderSyncPreparationCancelled(working, error)
        throw FolderSyncPreparationFailure(working, error)
    }
}

/** Read-only final gate; changed originals, working files or permissions invalidate preparation. */
internal fun checkPreparedFolderSync(
    prepared: PreparedFolderSync,
    allowed: (String) -> Boolean,
    files: UndoHistoryFiles,
    storage: PartialCopyStorage = AndroidPartialCopyStorage(),
    control: TransferControl = TransferControl(),
) {
    val inspection = prepared.inspection
    val review = prepared.review
    folderSyncCopySpecs(inspection, review)
    val roots = review.operations.withIndex().filter { it.value.category != FolderSyncCategory.DELETION }
        .map { (index, operation) -> FolderSyncStagedRoot(operation, File(prepared.directory, "copy-" + index.toString().padStart(5, '0')).path) }
    check(prepared.roots == roots && prepared.proof.path == prepared.directory &&
        prepared.proof.entries[prepared.directory]?.directory == true) { "The prepared synchronization record changed. Compare again." }
    val paths = setOf(inspection.primaryProof.path, inspection.secondaryProof.path,
        inspection.primaryProof.resolved, inspection.secondaryProof.resolved, prepared.proof.anchor, prepared.proof.resolvedAnchor)
    control.check();check(paths.all(allowed)) { "Folder access changed. Reconnect the folders before synchronizing." }
    checkFolderSyncInspection(inspection, files, control)
    check(files.observe(prepared.directory) == prepared.proof) { "The prepared copies changed. Original files were kept; compare again." }
    val native = inspection.primaryProof.entries + inspection.secondaryProof.entries
    // Verify all original bytes, including destination-only items, before any later publication.
    for ((path, checksum) in inspection.contents) {
        control.check();check(paths.all(allowed)) { "Folder access changed. Original files were kept." }
        control.item(File(path).name)
        check(folderSyncDigest(path, native.getValue(path), control, storage) == checksum) { "An original file changed after review. Compare again." }
    }
    for (root in roots) {
        val members = review.preview.entries.filter { it.relative == root.operation.relative || it.coveredBy == root.operation.relative }
        for (member in members) {
            val source = member.source ?: error("A reviewed source is missing.")
            if (source.folder) continue
            val relative = if (member.relative == root.operation.relative) "" else member.relative.removePrefix(root.operation.relative + "/")
            val path = if (relative.isEmpty()) root.path else File(root.path, relative).path
            control.check();check(paths.all(allowed)) { "Folder access changed. Original files were kept." }
            val stamp = prepared.proof.entries[path] ?: error("A prepared file is missing. Compare again.")
            check(folderSyncDigest(path, stamp, control, storage) == inspection.contents.getValue(source.path)) { "A prepared file no longer matches the reviewed source." }
        }
    }
    check(paths.all(allowed)) { "Folder access changed. Original files were kept." }
    checkFolderSyncInspection(inspection, files, control)
    check(files.observe(prepared.directory) == prepared.proof) { "The prepared copies changed while they were checked. Compare again." }
}
