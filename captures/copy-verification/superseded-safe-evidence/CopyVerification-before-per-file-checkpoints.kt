package dev.findroid

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal const val MAX_COPY_VERIFICATION_RESULTS = 10_000

enum class CopyVerificationStatus(val title: String) {
    VERIFIED("Verified"),
    SKIPPED("Skipped"),
    UNREADABLE("Could not read the copy"),
    MISMATCH("Contents did not match"),
    INCOMPLETE("Verification incomplete"),
}

/** A result describes the bytes read back, never an assurance about later changes. */
data class CopyVerificationResult(
    val root: String,
    val source: String,
    val status: CopyVerificationStatus = CopyVerificationStatus.INCOMPLETE,
    val destination: String? = null,
    val bytes: Long = 0,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    val error: String? = null,
)

/** Bounded results for one unpublished root; the worker checkpoints them even on cancellation. */
class CopyVerificationSession(
    private val root: String,
    private val capacity: Int = MAX_COPY_VERIFICATION_RESULTS,
) {
    private val records = linkedMapOf<String, CopyVerificationResult>()
    val results: List<CopyVerificationResult> get() = records.values.toList()

    fun begin(source: String): MessageDigest {
        check(source !in records) { "A source file was encountered more than once while verifying." }
        check(records.size < capacity) {
            "Verification supports up to $MAX_COPY_VERIFICATION_RESULTS file results per transfer. Split this selection into smaller copies. The source was kept."
        }
        records[source] = CopyVerificationResult(root, source)
        return MessageDigest.getInstance("SHA-256")
    }

    fun skipped(source: String) {
        begin(source)
        records[source] = records.getValue(source).copy(status = CopyVerificationStatus.SKIPPED)
    }

    fun verify(
        source: String,
        copied: Long,
        expected: ByteArray,
        control: TransferControl,
        provider: Boolean = false,
        readback: () -> InputStream,
    ) {
        require(copied >= 0)
        val original = records.getValue(source).copy(bytes = copied, expectedSha256 = expected.hex())
        records[source] = original
        control.phase("Verifying copy", copied, TransferStage.VERIFYING)
        var checked = 0L
        val actual = MessageDigest.getInstance("SHA-256")
        try {
            val kind = if (provider) TransferIo.PROVIDER else TransferIo.DESTINATION
            control.check()
            control.storage(kind, readback).use { input ->
                control.guard(input) {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        control.check()
                        val count = control.storage(kind) { input.read(buffer) }
                        if (count < 0) break
                        if (count == 0) continue
                        actual.update(buffer, 0, count)
                        checked = Math.addExact(checked, count.toLong())
                        control.verifiedBytes(count)
                        if (checked > copied) break
                    }
                }
            }
            control.check()
            val digest = actual.digest()
            if (checked != copied || !MessageDigest.isEqual(expected, digest)) {
                records[source] = original.copy(status = CopyVerificationStatus.MISMATCH, actualSha256 = digest.hex().takeIf { checked <= copied },
                    error = "The copy did not match the bytes read from the source.")
                throw CopyVerificationException("The copy did not match the source bytes. The source was kept.")
            }
            records[source] = original.copy(status = CopyVerificationStatus.VERIFIED, actualSha256 = digest.hex())
        } catch (error: CancellationException) {
            records[source] = original.copy(error = "Verification was cancelled before publication.")
            throw error
        } catch (error: CopyVerificationException) {
            throw error
        } catch (error: Exception) {
            records[source] = original.copy(status = CopyVerificationStatus.UNREADABLE,
                error = "The destination could not be read completely: ${error.message.orEmpty().take(512)}")
            throw CopyVerificationException("Verification could not read the completed copy. The source was kept.", error)
        }
    }

    /** Only a successful publication may attach a destination to a verified result. */
    fun published(destinations: Map<String, String>) {
        records.replaceAll { source, result ->
            result.copy(destination = destinations[source])
        }
    }
}

private class CopyVerificationException(message: String, cause: Throwable? = null) : IOException(message, cause)

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

internal fun copyVerificationSummary(job: TransferJob): String = when {
    !job.verifyCopies -> "Not requested"
    job.verifications.isEmpty() && !job.terminal -> "SHA-256 readback requested"
    job.verifications.isEmpty() -> "No file contents were checked"
    else -> {
        val verified = job.verifications.count { it.status == CopyVerificationStatus.VERIFIED && it.destination != null }
        val unpublished = job.verifications.count { it.status == CopyVerificationStatus.VERIFIED && it.destination == null }
        val skipped = job.verifications.count { it.status == CopyVerificationStatus.SKIPPED }
        val attention = job.verifications.size - verified - unpublished - skipped
        buildList {
            add("$verified verified and copied")
            if (unpublished > 0) add("$unpublished verified but not published")
            if (skipped > 0) add("$skipped skipped")
            if (attention > 0) add("$attention not verified")
        }.joinToString(" · ")
    }
}

internal fun validateCopyVerification(job: TransferJob) {
    require(!job.move || !job.verifyCopies) { "Optional verification is available for copies." }
    require(job.verifications.isEmpty() || job.verifyCopies) { "Verification results need a verified-copy request." }
    require(job.verifications.size <= MAX_COPY_VERIFICATION_RESULTS) { "Too many verification results." }
    require(job.verifications.map { it.root to it.source }.distinct().size == job.verifications.size) { "Duplicate verification results." }
    job.verifications.forEach { result ->
        require(result.root.isNotBlank() && result.source.isNotBlank() && result.bytes >= 0)
        require(listOfNotNull(result.expectedSha256, result.actualSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(result.destination == null || result.status == CopyVerificationStatus.VERIFIED)
        if (result.status == CopyVerificationStatus.VERIFIED)
            require(result.expectedSha256 != null && result.expectedSha256 == result.actualSha256)
    }
}
