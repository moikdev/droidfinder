package dev.findroid

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal const val PARTIAL_COPY_CHECKPOINT_BYTES = 4L * 1024 * 1024
internal const val PARTIAL_COPY_MINIMUM_BYTES = 8L * 1024 * 1024

/** Native descriptor identity, including nanosecond source-change information. */
data class PartialFileStamp(
    val device: Long,
    val inode: Long,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
    val regular: Boolean,
    val directory: Boolean,
    val links: Long,
) {
    fun sameObject(other: PartialFileStamp) = device == other.device && inode == other.inode &&
        regular == other.regular && directory == other.directory
}

internal interface PartialCopyHandle : Closeable {
    fun stamp(): PartialFileStamp
    fun seek(offset: Long)
    fun read(buffer: ByteArray, offset: Int, count: Int): Int
    fun write(buffer: ByteArray, offset: Int, count: Int): Int
    fun truncate(size: Long)
    fun sync()
}

/** stat never follows the final link; open also refuses it and exposes descriptor metadata. */
internal interface PartialCopyStorage {
    fun canonical(path: String): String
    fun stat(path: String): PartialFileStamp?
    fun open(path: String, writable: Boolean = false, createNew: Boolean = false, directory: Boolean = false): PartialCopyHandle
    fun createDirectory(path: String)
    fun moveNoReplace(existing: String, target: String)
    /** Removes a single file or an empty directory. Never recursively deletes a tree. */
    fun remove(path: String)
}

/** This identifies only an unpublished private working file, never a chosen destination by name. */
data class PartialCopyRecord(
    val owner: String,
    val source: String,
    val target: String,
    val staging: String,
    val sourceStamp: PartialFileStamp,
    val destinationParentStamp: PartialFileStamp,
    val stagingParentStamp: PartialFileStamp,
    val stagingStamp: PartialFileStamp,
    val offset: Long,
    val prefixSha256: String,
    val verifyCopies: Boolean,
    val publishing: Boolean = false,
    val resolvedSource: String = source,
    val resolvedDestinationParent: String = File(target).parent.orEmpty(),
    val resolvedPrivateRoot: String = File(staging).parentFile?.parent.orEmpty(),
)

/** Approval is intentionally process-local. A restart always requires a fresh review. */
data class PartialCopyReview(
    val record: PartialCopyRecord,
    val sourceSha256: String,
    val observedStagingStamp: PartialFileStamp,
) {
    val savedBytes get() = record.offset
    val discardedTailBytes get() = observedStagingStamp.size - record.offset
}

/** Cleanup approval describes only private working data. It never authorizes deleting a destination. */
data class PartialCopyCleanupReview(
    val record: PartialCopyRecord,
    val observedStaging: PartialFileStamp?,
    val observedParent: PartialFileStamp?,
    val destination: PartialFileStamp?,
    val destinationAccessible: Boolean,
) {
    val destinationMatches get() = destination?.sameObject(record.stagingStamp) == true
}

internal class PartialCopyUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Proof and ownership operations for partial copies. The worker must persist each returned
 * checkpoint only AFTER sync(), and persist publishing=true BEFORE exposing a completed file.
 * Private staging stays outside Workspace and all other exported document-provider roots.
 */
internal class PartialCopyRecovery(
    private val storage: PartialCopyStorage,
    private val privateRoot: String,
) {
    private fun fail(message: String): Nothing = throw PartialCopyUnavailable(message)
    private fun requireSafe(value: Boolean, message: String) { if (!value) fail(message) }
    private fun parent(path: String) = File(path).parent ?: fail("This file has no usable parent folder.")
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun shape(record: PartialCopyRecord) {
        requireSafe(runCatching { UUID.fromString(record.owner).toString() == record.owner }.getOrDefault(false),
            "The saved copy has no valid ownership record. Restart the copy.")
        val expected = File(File(privateRoot, record.owner), "data").path
        requireSafe(record.staging == expected && record.source != record.target && record.source != expected && record.target != expected,
            "The saved copy does not identify its private working file. Restart the copy.")
        requireSafe(listOf(privateRoot, record.source, record.target, record.staging, record.resolvedSource, record.resolvedDestinationParent, record.resolvedPrivateRoot).all {
            File(it).isAbsolute && '\u0000' !in it && File(it).toPath().normalize().toString() == it
        }, "The saved copy has an invalid location. Restart the copy.")
        requireSafe(listOf(record.sourceStamp, record.destinationParentStamp, record.stagingParentStamp, record.stagingStamp).all {
            it.device >= 0 && it.inode > 0 && it.size >= 0 && it.links > 0 &&
                it.modifiedNanos in 0..999_999_999 && it.changedNanos in 0..999_999_999 && it.regular != it.directory
        }, "This storage does not provide a reliable saved-file identity.")
        requireSafe(record.sourceStamp.regular && record.stagingStamp.regular &&
            record.destinationParentStamp.directory && record.stagingParentStamp.directory &&
            record.sourceStamp.size >= 0 && record.offset in 0..record.sourceStamp.size &&
            record.stagingStamp.size == record.offset && record.prefixSha256.matches(Regex("[0-9a-f]{64}")),
            "The saved copy has an invalid checkpoint. Restart the copy.")
    }

    private fun currentObject(path: String, expected: PartialFileStamp, message: String) {
        requireSafe(storage.stat(path)?.sameObject(expected) == true, message)
    }

    private fun sourceUnchanged(record: PartialCopyRecord, opened: PartialCopyHandle) {
        requireSafe(opened.stamp() == record.sourceStamp && storage.stat(record.source) == record.sourceStamp,
            "The source file changed. Restart the copy after reviewing the source.")
    }

    private fun locationsUnchanged(record: PartialCopyRecord) {
        requireSafe(storage.canonical(privateRoot) == record.resolvedPrivateRoot && storage.canonical(record.source) == record.resolvedSource &&
            storage.canonical(parent(record.target)) == record.resolvedDestinationParent &&
            storage.canonical(parent(record.staging)) == File(record.resolvedPrivateRoot, record.owner).path,
            "A saved location now resolves to another folder. Restart the copy.")
        currentObject(parent(record.target), record.destinationParentStamp, "The destination folder changed. Review it before restarting.")
        currentObject(parent(record.staging), record.stagingParentStamp, "The private working folder changed. The saved copy was kept for review.")
        requireSafe(storage.stat(record.target) == null, "An item now exists at the destination. Review it before restarting; it was kept.")
    }

    private fun owned(record: PartialCopyRecord, opened: PartialCopyHandle): PartialFileStamp {
        val actual = opened.stamp()
        requireSafe(actual.sameObject(record.stagingStamp) && actual.regular && !actual.directory && actual.links == 1L,
            "The private working file was replaced or linked elsewhere. It was kept for review.")
        currentObject(record.staging, actual, "The private working file changed. It was kept for review.")
        return actual
    }

    /** Probe same-filesystem no-replace rename and directory durability before copying data. */
    fun allocate(source: String, target: String, verifyCopies: Boolean): PartialCopyRecord {
        requireSafe(source != target && File(source).isAbsolute && File(target).isAbsolute,
            "This location cannot safely keep an unfinished copy. Restart the copy if it is interrupted.")
        val original = storage.stat(source) ?: fail("The source file is unavailable.")
        requireSafe(original.regular && !original.directory, "Only individual local files can keep a resumable copy.")
        val destination = storage.stat(parent(target)) ?: fail("The destination folder is unavailable.")
        requireSafe(destination.directory && storage.stat(target) == null, "Review the destination before starting this copy.")
        val owner = UUID.randomUUID().toString()
        val folder = File(privateRoot, owner).path
        val staging = File(folder, "data").path
        storage.createDirectory(folder)
        val folderStamp = storage.stat(folder) ?: fail("The private working folder could not be created.")
        var identity: PartialFileStamp? = null
        val probe = File(parent(target), ".findroid-partial-probe-$owner").path
        var probeCreated = false
        try {
            storage.open(staging, writable = true, createNew = true).use { file ->
                identity = file.stamp()
                requireSafe(identity!!.regular && identity!!.links == 1L && identity!!.size == 0L,
                    "This storage cannot create a private working file.")
                file.sync()
                storage.moveNoReplace(staging, probe)
                probeCreated = true
                currentObject(probe, identity!!, "This storage cannot safely publish a saved copy.")
                storage.moveNoReplace(probe, staging)
                probeCreated = false
                storage.open(parent(target), directory = true).use { it.sync() }
                storage.open(folder, directory = true).use { it.sync() }
                storage.open(privateRoot, directory = true).use { it.sync() }
                val ready = file.stamp()
                requireSafe(ready.links == 1L && ready.sameObject(identity!!), "The private working file changed during preparation.")
                return PartialCopyRecord(owner, source, target, staging, original, destination, folderStamp,
                    ready, 0, digest().digest().hex(), verifyCopies, resolvedSource = storage.canonical(source),
                    resolvedDestinationParent = storage.canonical(parent(target)), resolvedPrivateRoot = storage.canonical(privateRoot)).also(::shape)
            }
        } catch (error: Exception) {
            // No tree deletion, and never remove a colliding or replaced probe/working file.
            runCatching { if (probeCreated && identity?.let { storage.stat(probe)?.sameObject(it) } == true) storage.remove(probe) }
            runCatching { if (identity?.let { storage.stat(staging)?.sameObject(it) } == true) storage.remove(staging) }
            runCatching { if (storage.stat(folder)?.sameObject(folderStamp) == true) storage.remove(folder) }
            throw PartialCopyUnavailable("This location cannot safely keep an unfinished copy. Restart the copy if it is interrupted.", error)
        }
    }

    /** The digest describes exactly the bytes written through the durable offset. */
    fun checkpoint(record: PartialCopyRecord, source: PartialCopyHandle, staging: PartialCopyHandle,
        offset: Long, prefixSha256: String): PartialCopyRecord {
        shape(record)
        requireSafe(!record.publishing && offset >= record.offset && offset <= record.sourceStamp.size,
            "The saved-copy checkpoint cannot move backward or past the source.")
        locationsUnchanged(record)
        sourceUnchanged(record, source)
        val before = owned(record, staging)
        requireSafe(before.size == offset, "The private working file has an unexpected length.")
        staging.sync()
        val durable = owned(record, staging)
        requireSafe(durable == before, "The private working file changed while saving its checkpoint.")
        sourceUnchanged(record, source)
        return record.copy(stagingStamp = durable, offset = offset, prefixSha256 = prefixSha256).also(::shape)
    }

    private fun hash(file: PartialCopyHandle, count: Long, control: TransferControl): String {
        val hash = digest()
        val buffer = ByteArray(64 * 1024)
        file.seek(0)
        var remaining = count
        while (remaining > 0) {
            control.check()
            val read = control.storage(TransferIo.SOURCE) { file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()) }
            requireSafe(read > 0 && read.toLong() <= remaining, "A saved file could not be read completely. Restart the copy.")
            hash.update(buffer, 0, read)
            remaining -= read
            control.verifiedBytes(read)
        }
        return hash.digest().hex()
    }

    /** Reads the entire current source and the saved prefix; unchecked tail bytes are never reused. */
    fun review(record: PartialCopyRecord, control: TransferControl = TransferControl()): PartialCopyReview {
        shape(record)
        requireSafe(!record.publishing, "This copy was finishing when interrupted. Review the destination before restarting.")
        requireSafe(record.offset > 0, "No durable file bytes were saved. Restart the copy.")
        locationsUnchanged(record)
        control.phase("Checking saved copy", stage = TransferStage.VERIFYING)
        storage.open(record.source).use { source ->
            storage.open(record.staging).use { staging ->
                sourceUnchanged(record, source)
                val before = owned(record, staging)
                requireSafe(before.size in record.offset..record.sourceStamp.size,
                    "The saved copy is shorter than its checkpoint or larger than the source. It was kept for review.")
                requireSafe(hash(source, record.offset, control) == record.prefixSha256 &&
                    hash(staging, record.offset, control) == record.prefixSha256,
                    "The saved bytes no longer match the source. Restart the copy; the source was kept.")
                val full = hash(source, record.sourceStamp.size, control)
                sourceUnchanged(record, source)
                requireSafe(owned(record, staging) == before, "The saved copy changed during review. Review it again.")
                locationsUnchanged(record)
                return PartialCopyReview(record, full, before)
            }
        }
    }

    /** A dialog's old approval is never enough to append bytes. Recheck again at execution. */
    fun recheck(approval: PartialCopyReview, control: TransferControl): PartialCopyReview {
        val current = review(approval.record, control)
        requireSafe(current.sourceSha256 == approval.sourceSha256 && current.observedStagingStamp == approval.observedStagingStamp,
            "The source or saved copy changed after review. Review the copy again.")
        return current
    }

    /** Discard only the uncheckpointed tail the user saw in this exact review. */
    fun prepareResume(approval: PartialCopyReview, source: PartialCopyHandle, staging: PartialCopyHandle): PartialCopyRecord {
        val record = approval.record
        shape(record)
        requireSafe(!record.publishing, "Review the destination before restarting this copy.")
        sourceUnchanged(record, source)
        locationsUnchanged(record)
        requireSafe(owned(record, staging) == approval.observedStagingStamp,
            "The saved copy changed after review. Review it again.")
        staging.truncate(record.offset)
        return checkpoint(record, source, staging, record.offset, record.prefixSha256)
    }

    fun beforePublication(record: PartialCopyRecord, source: PartialCopyHandle, staging: PartialCopyHandle): PartialCopyRecord {
        shape(record)
        requireSafe(!record.publishing && record.offset == record.sourceStamp.size,
            "The saved copy is not complete and cannot be published.")
        sourceUnchanged(record, source)
        locationsUnchanged(record)
        requireSafe(owned(record, staging) == record.stagingStamp, "The saved copy changed before it was finished.")
        return record.copy(publishing = true)
    }

    /** Caller must first persist beforePublication(). No checkpoint/cancellation callback follows the rename. */
    fun publish(record: PartialCopyRecord, source: PartialCopyHandle, staging: PartialCopyHandle): String {
        shape(record)
        requireSafe(record.publishing && record.offset == record.sourceStamp.size, "The copy was not prepared for publication.")
        sourceUnchanged(record, source)
        locationsUnchanged(record)
        requireSafe(owned(record, staging) == record.stagingStamp, "The saved copy changed before it was finished.")
        storage.moveNoReplace(record.staging, record.target)
        try {
            storage.open(parent(record.target), directory = true).use { it.sync() }
            currentObject(record.target, record.stagingStamp, "The published copy changed during cleanup.")
            storage.open(parent(record.staging), directory = true).use { it.sync() }
            storage.remove(parent(record.staging))
            storage.open(privateRoot, directory = true).use { it.sync() }
        } catch (error: Exception) {
            throw TransferPublicationException(record.target,
                "The copy is available, but finishing its saved working file needs review: ${error.message}", error)
        }
        return record.target
    }

    /** Also handles a crash after publication or after private cleanup but before its journal save. */
    fun reviewCleanup(record: PartialCopyRecord): PartialCopyCleanupReview {
        shape(record)
        requireSafe(storage.canonical(privateRoot) == record.resolvedPrivateRoot,
            "The private saved-copy folder changed. Its contents were kept for review.")
        val folder = parent(record.staging)
        val parentStamp = storage.stat(folder)
        if (parentStamp != null) {
            requireSafe(storage.canonical(folder) == File(record.resolvedPrivateRoot, record.owner).path && parentStamp.sameObject(record.stagingParentStamp),
                "The private working folder changed. Its contents were kept for review.")
        }
        val stage = storage.stat(record.staging)
        if (stage != null) {
            requireSafe(parentStamp != null && stage.sameObject(record.stagingStamp) && stage.regular && stage.links > 0 &&
                (record.publishing || stage.links == 1L),
                "The private working file was replaced or linked elsewhere. It was kept for review.")
            storage.open(record.staging).use { opened ->
                requireSafe(opened.stamp() == stage, "The private working file changed during review.")
            }
        }
        val destination = runCatching { storage.stat(record.target) }
        return PartialCopyCleanupReview(record, stage, parentStamp, destination.getOrNull(), destination.isSuccess)
    }

    /** Remove exactly the reviewed private name; a published destination and its links are kept. */
    fun discardReviewed(approval: PartialCopyCleanupReview) {
        val current = reviewCleanup(approval.record)
        requireSafe(current.observedStaging == approval.observedStaging &&
            (current.observedParent == null && approval.observedParent == null ||
                current.observedParent != null && approval.observedParent != null &&
                    current.observedParent.sameObject(approval.observedParent)),
            "The private working file changed after review. Review it again.")
        current.observedStaging?.let { storage.remove(approval.record.staging) }
        current.observedParent?.let {
            storage.open(parent(approval.record.staging), directory = true).use { it.sync() }
            storage.remove(parent(approval.record.staging))
            storage.open(privateRoot, directory = true).use { it.sync() }
        }
    }

    /** Cancellation can remove only the exact private file and its now-empty owner directory. */
    fun discard(record: PartialCopyRecord) {
        shape(record)
        requireSafe(!record.publishing, "The copy may already be available. Review its destination before cleanup.")
        currentObject(parent(record.staging), record.stagingParentStamp, "The private working folder changed. It was kept for review.")
        storage.open(record.staging).use { file -> owned(record, file) }
        storage.remove(record.staging)
        storage.open(parent(record.staging), directory = true).use { it.sync() }
        storage.remove(parent(record.staging))
        storage.open(privateRoot, directory = true).use { it.sync() }
    }
}
