package dev.findroid

import java.io.File
import java.security.MessageDigest

/** One disclosed file, pinned to the comparison that the user reviewed. */
internal data class FolderSyncCopySpec(
    val source: String,
    val expected: PartialFileStamp,
    val sha256: String,
)

/** Validates a review before creating any working files or moving a destination. */
internal fun folderSyncCopySpecs(inspection: FolderSyncInspection, review: ReviewedFolderSync): Map<String, FolderSyncCopySpec> {
    check(review.preview == inspection.preview && review == reviewFolderSync(inspection.preview,
        review.operations.map { it.relative }.toSet(), review.updatesConfirmed, review.trashConfirmed)) {
        "The synchronization choices changed. Review the comparison again."
    }
    val native = inspection.primaryProof.entries + inspection.secondaryProof.entries
    val regular = native.filterValues { it.regular }.keys
    check(inspection.contents.keys == regular && inspection.contents.values.all { value ->
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }) { "The reviewed file checksums are incomplete. Compare again."
    }
    val nodes = review.copyFiles
    check(nodes.map { it.path }.toSet().size == nodes.size) { "A copied item appears more than once. Compare again." }
    return nodes.associate { node ->
        val stamp = native[node.path]
        check(stamp?.regular == true && !stamp.directory && stamp.links == 1L && stamp.size >= 0 && !node.link && !node.folder && node.bytes == stamp.size) {
            "A copied item no longer matches the verified comparison. Compare again."
        }
        node.path to FolderSyncCopySpec(node.path, stamp, inspection.contents.getValue(node.path))
    }
}

/**
 * Creates only a new working file; callers must allocate its owned staging directory first.
 * The source is opened without following links, every byte is checked, and the completed
 * working file is read back before it can become a publication candidate. Failed or cancelled
 * working files remain in their owned directory for manual review.
 */
internal fun stageFolderSyncFile(
    spec: FolderSyncCopySpec,
    target: String,
    parent: PartialFileStamp,
    resolvedParent: String,
    control: TransferControl,
    storage: PartialCopyStorage = AndroidPartialCopyStorage(),
): PartialFileStamp {
    val expected = spec.expected
    check(expected.regular && !expected.directory && expected.links == 1L && expected.size >= 0 &&
        spec.sha256.length == 64 && spec.sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
        "A reviewed file could not be verified. Compare again."
    }
    val directory = File(target).parentFile?.path ?: error("The working folder is unavailable.")
    check(parent.directory && !parent.regular && File(target).isAbsolute && File(target).toPath().normalize().toString() == target && target != spec.source) {
        "The working file location is invalid. Original files were kept."
    }
    fun checkParent() {
        check(storage.stat(directory)?.let { it.directory && it.sameObject(parent) } == true && storage.canonical(directory) == resolvedParent) {
            "The working folder changed. Original files were kept."
        }
    }
    control.check();checkParent()
    check(storage.stat(target) == null && storage.stat(spec.source) == expected) {
        "A reviewed source or working file changed. Compare again."
    }
    control.phase("Preparing synchronization copy", expected.size, TransferStage.COPYING)
    var completed: PartialFileStamp? = null
    storage.open(spec.source).use { source ->
        control.guard(source) {
            check(source.stamp() == expected) { "The reviewed source changed before it could be read. Compare again." }
            control.check();checkParent()
            storage.open(target, writable = true, createNew = true).use { working ->
                control.guard(working) {
                    val owned = working.stamp()
                    check(owned.regular && !owned.directory && owned.links == 1L && owned.size == 0L && owned.device == parent.device) {
                        "This location cannot create a verified working file. Original files were kept."
                    }
                    check(storage.stat(target) == owned) { "The new working file changed. Original files were kept." }
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var remaining = expected.size
                    while (remaining > 0) {
                        control.check()
                        val count = control.storage(TransferIo.SOURCE) { source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()) }
                        check(count > 0 && count <= minOf(remaining, buffer.size.toLong())) { "The reviewed source ended at an unexpected size. Compare again." }
                        hash.update(buffer, 0, count)
                        var written = 0
                        while (written < count) {
                            control.check()
                            val amount = control.storage(TransferIo.DESTINATION) { working.write(buffer, written, count - written) }
                            check(amount in 1..(count - written)) { "The working file could not be written completely. Original files were kept." }
                            written += amount
                            control.bytes(amount)
                        }
                        remaining -= count
                    }
                    control.check()
                    check(source.read(buffer, 0, 1) in -1..0 && source.stamp() == expected && storage.stat(spec.source) == expected) {
                        "The reviewed source changed while it was copied. Compare again."
                    }
                    val actual = hash.digest().joinToString("") { "%02x".format(it) }
                    check(actual == spec.sha256) { "The source contents no longer match the reviewed checksum. Compare again." }
                    val before = working.stamp()
                    check(before.sameObject(owned) && before.links == 1L && before.size == expected.size && storage.stat(target) == before) {
                        "The working file changed while it was written. Original files were kept."
                    }
                    working.sync()
                    check(working.stamp() == before && storage.stat(target) == before) { "The working file changed while it was saved." }
                    completed = before
                }
            }
        }
    }
    val ready = completed ?: error("The working copy was not completed.")
    control.check();checkParent()
    control.phase("Verifying synchronization copy", expected.size, TransferStage.VERIFYING)
    check(folderSyncDigest(target, ready, control, storage) == spec.sha256) { "The working copy did not match the reviewed source. Original files were kept." }
    check(storage.stat(spec.source) == expected) { "The source changed after the working copy was checked. Compare again." }
    control.check();checkParent()
    storage.open(directory, directory = true).use { handle ->
        check(handle.stamp().sameObject(parent)) { "The working folder changed before its copy could be saved." }
        handle.sync()
    }
    return ready
}
