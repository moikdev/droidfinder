package dev.findroid

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** One file's journal-backed ownership. Every callback happens before publication. */
class PartialCopySession internal constructor(
    private val recovery: PartialCopyRecovery,
    private val storage: PartialCopyStorage,
    private val approval: PartialCopyReview? = null,
    private val changed: (PartialCopyRecord?, String?, Long) -> Unit,
) {
    private var saved: PartialCopyRecord? = approval?.record
    private var reused = 0L
    internal var completed = false
        private set
    private fun remember(record: PartialCopyRecord?, notice: String? = null) {
        saved = record
        changed(record, notice?.take(1024), reused)
    }
    private fun MessageDigest.snapshotHex() = (clone() as MessageDigest).digest().joinToString("") { "%02x".format(it) }

    internal fun remainingBytes(source: File): Long =
        (source.length() - (approval?.savedBytes ?: 0L)).coerceAtLeast(0L)

    /** null leaves an unsupported fresh copy to the ordinary file engine. A resume never falls back silently. */
    internal fun copy(source: File, target: File, control: TransferControl, verification: CopyVerificationSession?): String? {
        val resumed = approval != null
        if (!resumed && (!source.isFile || source.length() < PARTIAL_COPY_MINIMUM_BYTES)) return null
        val cloneable = runCatching { MessageDigest.getInstance("SHA-256").snapshotHex() }.isSuccess
        if (!cloneable) {
            check(!resumed) { "This device cannot validate a saved copy. Restart the copy." }
            remember(null, "This device cannot keep an unfinished copy. Restart the copy if interrupted.")
            return null
        }
        val reviewed = approval?.let { recovery.recheck(it, control) }
        var record = reviewed?.record ?: try {
            recovery.allocate(source.absolutePath, target.absolutePath, verification != null)
        } catch (error: PartialCopyUnavailable) {
            remember(null, error.message)
            return null
        }
        check(record.source == source.absolutePath && record.target == target.absolutePath) {
            "The reviewed source or destination changed. Review the saved copy again."
        }
        var publishedTo: String? = null
        try {
            if (!resumed) remember(record)
            val checks = verification ?: if (resumed) CopyVerificationSession(record.source, 1) else null
            val digest = checks?.begin(record.source) ?: MessageDigest.getInstance("SHA-256")
            return storage.open(record.source).use { input ->
                storage.open(record.staging, writable = true).use { output ->
                    control.guard(input) {
                        control.guard(output) {
                            var offset = record.offset
                            val buffer = ByteArray(64 * 1024)
                            if (reviewed != null) {
                                record = recovery.prepareResume(reviewed, input, output)
                                control.phase("Checking saved bytes", offset, TransferStage.VERIFYING)
                                var checked = 0L
                                input.seek(0)
                                while (checked < offset) {
                                    control.check()
                                    val read = control.storage(TransferIo.SOURCE) {
                                        input.read(buffer, 0, minOf(buffer.size.toLong(), offset - checked).toInt())
                                    }
                                    check(read > 0) { "The source could not be rechecked. Restart the copy." }
                                    digest.update(buffer, 0, read); checked += read; control.verifiedBytes(read)
                                }
                                check(digest.snapshotHex() == record.prefixSha256) { "The source changed after review. Restart the copy." }
                                reused = offset
                                remember(record, "Reusing ${formatSize(offset)} of checked saved bytes.")
                            } else input.seek(0)
                            output.seek(offset)
                            control.item(source.name)
                            control.phase(if (resumed) "Copying remaining bytes" else "Copying", record.sourceStamp.size - offset, TransferStage.COPYING)
                            var checkpointAt = offset
                            while (offset < record.sourceStamp.size) {
                                control.check()
                                val read = control.storage(TransferIo.SOURCE) {
                                    input.read(buffer, 0, minOf(buffer.size.toLong(), record.sourceStamp.size - offset).toInt())
                                }
                                check(read > 0) { "The source could not be read completely. Its original was kept." }
                                var written = 0
                                while (written < read) {
                                    control.check()
                                    val count = control.storage(TransferIo.DESTINATION) { output.write(buffer, written, read - written) }
                                    check(count > 0 && count <= read - written) { "The working copy could not be written completely." }
                                    written += count
                                }
                                digest.update(buffer, 0, read)
                                offset += read
                                control.bytes(read)
                                if (offset - checkpointAt >= PARTIAL_COPY_CHECKPOINT_BYTES || offset == record.sourceStamp.size) {
                                    record = recovery.checkpoint(record, input, output, offset, digest.snapshotHex())
                                    remember(record)
                                    checkpointAt = offset
                                }
                            }
                            val expected = digest.digest()
                            if (reviewed != null) check(expected.joinToString("") { "%02x".format(it) } == reviewed.sourceSha256) {
                                "The source changed while its remaining bytes were copied. The source was kept."
                            }
                            if (checks != null) checks.verify(record.source, record.sourceStamp.size, expected, control) {
                                storage.open(record.staging).asInputStream()
                            }
                            control.phase("Publishing copy", stage = TransferStage.FINISHING)
                            control.check()
                            record = recovery.beforePublication(record, input, output)
                            remember(record)
                            val destination = recovery.publish(record, input, output)
                            publishedTo = destination
                            completed = true
                            verification?.published(mapOf(record.source to destination))
                            destination
                        }
                    }
                }
            }
        } catch (error: Exception) {
            publishedTo?.let { destination ->
                throw TransferPublicationException(destination, "The copy is available, but closing its working file needs review: ${error.message}", error)
            }
            if (error is CancellationException || control.cancelled) {
                // A published file can never be removed as though it were unfinished staging.
                if (!record.publishing) {
                    try { recovery.discard(record); remember(null, "The unfinished copy was cancelled. Its source was kept.") }
                    catch (cleanup: Exception) { remember(record, "The source was kept. Saved-copy cleanup needs review: ${cleanup.message}") }
                }
            } else if (record.offset == 0L && !record.publishing) {
                try { recovery.discard(record); remember(null, "No durable bytes were saved. Restart the copy.") }
                catch (cleanup: Exception) { remember(record, "The working file was kept for review: ${cleanup.message}") }
            }
            throw error
        }
    }
}

private fun PartialCopyHandle.asInputStream(): InputStream = object : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        val count = this@asInputStream.read(one, 0, 1)
        return if (count <= 0) -1 else one[0].toInt() and 255
    }
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
        if (count == 0) 0 else this@asInputStream.read(buffer, offset, count).let { if (it == 0) -1 else it }
    override fun close() = this@asInputStream.close()
}
