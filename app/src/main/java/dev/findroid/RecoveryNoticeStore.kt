package dev.findroid

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal data class RecoveryNotice(val id: String, val text: String) {
    private val lines get() = text.lines()
    private val versioned get() = lines.firstOrNull() == "findroid-operation-v1"
    private fun decoded(index: Int) = runCatching {
        String(Base64.getDecoder().decode(lines.getOrNull(index) ?: return@runCatching null), Charsets.UTF_8)
    }.getOrNull()
    val label get() = if (versioned) decoded(1) else lines.firstOrNull()
    val destination get() = (if (versioned) decoded(2) else lines.getOrNull(1))?.takeIf { it.isNotBlank() }
    val recordedAt get() = lines.getOrNull(if (versioned) 3 else 2)?.toLongOrNull()?.takeIf { it >= 0 }

    companion object {
        fun fromText(text: String) = RecoveryNotice(recoveryNoticeHash(text.toByteArray(Charsets.UTF_8)), text)
    }
}
internal data class RecoveryMarkerToken(val notice: RecoveryNotice, val stamp: PartialFileStamp)
private fun recoveryNoticeHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal data class RecoveryNotices(val records: List<RecoveryNotice>, val problems: List<String> = emptyList())

/** Append-only copies of the app's interrupted-operation marker. No document paths are executed. */
internal class RecoveryNoticeStore(private val directory: File) {
    private var activeMarkerId: String? = null
    private fun hash(bytes: ByteArray) = recoveryNoticeHash(bytes)
    private fun parent(storage: PartialCopyStorage): PartialFileStamp {
        val value = storage.stat(directory.path) ?: error("The recovery notice folder is unavailable.")
        check(value.directory && !value.regular && storage.canonical(directory.path) == File(directory.parentFile!!.canonicalFile, directory.name).path) {
            "The recovery notice folder changed. Its data was kept."
        }
        return value
    }
    private fun read(file: File, storage: PartialCopyStorage): ByteArray {
        val expected = storage.stat(file.path) ?: error("The interrupted-operation record is unavailable.")
        check(expected.regular && !expected.directory && expected.links == 1L && expected.size in 1..65_536 &&
            storage.canonical(file.path) == File(file.parentFile!!.canonicalFile, file.name).path) {
            "The interrupted-operation record could not be read safely. Keep it for manual review."
        }
        return storage.open(file.path).use { handle ->
            check(handle.stamp() == expected)
            val bytes = ByteArray(expected.size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = handle.read(bytes, offset, bytes.size - offset)
                check(count in 1..(bytes.size - offset)); offset += count
            }
            check(handle.read(ByteArray(1), 0, 1) in -1..0 && handle.stamp() == expected && storage.stat(file.path) == expected)
            bytes
        }
    }
    @Synchronized fun current(marker: File, storage: PartialCopyStorage = AndroidPartialCopyStorage()): RecoveryNotice? {
        if (storage.stat(marker.path) == null) return null
        val bytes = read(marker, storage)
        return RecoveryNotice(hash(bytes), bytes.toString(Charsets.UTF_8))
    }
    @Synchronized fun currentForReview(marker: File, storage: PartialCopyStorage = AndroidPartialCopyStorage()): RecoveryNotice? =
        current(marker, storage)?.takeUnless { it.id == activeMarkerId }

    /** Keeps the marker itself intact. A failed write leaves both its marker and incomplete archive for review. */
    @Synchronized fun preserve(marker: File, storage: PartialCopyStorage = AndroidPartialCopyStorage()): RecoveryNotice? {
        if (storage.stat(marker.path) == null) return null
        val bytes = read(marker, storage)
        val id = hash(bytes)
        if (storage.stat(directory.path) == null) {
            storage.createDirectory(directory.path)
            storage.open(directory.parentFile!!.path, directory = true).use { it.sync() }
        }
        val owned = parent(storage)
        val target = File(directory, "$id.txt")
        if (storage.stat(target.path) != null) {
            check(read(target, storage).contentEquals(bytes)) { "An existing recovery notice differs. Both records were kept for review." }
            storage.open(target.path, writable = true).use { it.sync() }
            storage.open(directory.path, directory = true).use { it.sync() }
            check(read(target, storage).contentEquals(bytes) && parent(storage).sameObject(owned))
            return RecoveryNotice(id, bytes.toString(Charsets.UTF_8))
        }
        check(Files.newDirectoryStream(directory.toPath()).use { stream -> stream.take(10_001).count() } < 10_000) {
            "The recovery notice folder is full. Its records and current marker were kept for manual review."
        }
        storage.open(target.path, writable = true, createNew = true).use { handle ->
            val fresh = handle.stamp()
            check(fresh.regular && fresh.links == 1L && fresh.size == 0L && fresh.device == owned.device && storage.stat(target.path) == fresh)
            var offset = 0
            while (offset < bytes.size) {
                val count = handle.write(bytes, offset, bytes.size - offset)
                check(count in 1..(bytes.size - offset)); offset += count
            }
            check(handle.stamp().sameObject(fresh) && handle.stamp().size == bytes.size.toLong() && storage.stat(target.path) == handle.stamp())
            handle.sync()
        }
        check(read(target, storage).contentEquals(bytes) && parent(storage).sameObject(owned))
        storage.open(directory.path, directory = true).use { handle -> check(handle.stamp().sameObject(owned)); handle.sync() }
        return RecoveryNotice(id, bytes.toString(Charsets.UTF_8))
    }
    /** Acknowledgment is bound to the notice actually shown; its current marker is never deleted. */
    @Synchronized fun acknowledge(marker: File, expectedId: String,
        storage: PartialCopyStorage = AndroidPartialCopyStorage()): RecoveryNotice {
        val before = current(marker, storage)
        check(before?.id == expectedId) { "The operation notice changed. Review the current record before acknowledging it." }
        val saved = preserve(marker, storage)
        check(saved?.id == expectedId && current(marker, storage)?.id == expectedId) {
            "The operation notice changed. All available records were kept for review."
        }
        return saved!!
    }

    /** Serialized with acknowledgment and completion. Work starts only after this durable readback. */
    @Synchronized fun begin(marker: File, label: String, destination: String, createdAt: Long,
        storage: PartialCopyStorage = AndroidPartialCopyStorage()): RecoveryMarkerToken {
        fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val bytes = "findroid-operation-v1\n${encoded(label)}\n${encoded(destination)}\n$createdAt\n${UUID.randomUUID()}".toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..65_536) { "The operation notice is too large. No file operation was started." }
        val old = storage.stat(marker.path)
        if (old != null) {
            val saved = preserve(marker, storage) ?: error("The prior operation notice changed.")
            check(storage.stat(marker.path) == old && current(marker, storage)?.id == saved.id) {
                "The prior operation notice changed. Its records were kept."
            }
            storage.remove(marker.path)
            syncMarkerParent(marker, storage)
        }
        storage.open(marker.path, writable = true, createNew = true).use { handle ->
            val initial = handle.stamp()
            check(initial.regular && initial.links == 1L && initial.size == 0L && storage.stat(marker.path) == initial)
            var offset = 0
            while (offset < bytes.size) {
                val count = handle.write(bytes, offset, bytes.size - offset)
                check(count in 1..(bytes.size - offset)); offset += count
            }
            check(handle.stamp().sameObject(initial) && handle.stamp().size == bytes.size.toLong() && storage.stat(marker.path) == handle.stamp())
            handle.sync()
        }
        check(read(marker, storage).contentEquals(bytes)) { "The new operation notice changed. No file operation was started." }
        syncMarkerParent(marker, storage)
        val stamp = storage.stat(marker.path) ?: error("The new operation notice disappeared.")
        check(read(marker, storage).contentEquals(bytes) && storage.stat(marker.path) == stamp)
        val token = RecoveryMarkerToken(RecoveryNotice(hash(bytes), bytes.toString(Charsets.UTF_8)), stamp)
        activeMarkerId = token.notice.id
        return token
    }

    /** Failed operations keep their marker and an archive. Success removes only its own unchanged marker. */
    @Synchronized fun finish(marker: File, token: RecoveryMarkerToken, succeeded: Boolean,
        storage: PartialCopyStorage = AndroidPartialCopyStorage()) {
        try {
            check(storage.stat(marker.path) == token.stamp && current(marker, storage)?.id == token.notice.id) {
                "The operation marker changed. Its data was kept for review."
            }
            if (!succeeded) {
                check(preserve(marker, storage)?.id == token.notice.id)
                return
            }
            check(storage.stat(marker.path) == token.stamp)
            storage.remove(marker.path)
            syncMarkerParent(marker, storage)
        } finally {
            if (activeMarkerId == token.notice.id) activeMarkerId = null
        }
    }

    private fun syncMarkerParent(marker: File, storage: PartialCopyStorage) {
        storage.open(marker.parentFile!!.path, directory = true).use { it.sync() }
    }

    /** Listing remains read-only, including invalid, linked, incomplete and oversized records. */
    @Synchronized fun load(storage: PartialCopyStorage = AndroidPartialCopyStorage(), checkCancelled: () -> Unit = {}): RecoveryNotices {
        if (storage.stat(directory.path) == null) return RecoveryNotices(emptyList())
        val owned = parent(storage)
        val records = mutableListOf<RecoveryNotice>()
        val problems = mutableListOf<String>()
        var bytesRead = 0L
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (path in stream) {
                checkCancelled()
                if (records.size + problems.size == 10_000) {
                    problems += "Recovery notices were limited to 10,000 records. All remaining files were kept."
                    break
                }
                try {
                    val name = path.fileName.toString()
                    check(name.matches(Regex("[0-9a-f]{64}\\.txt")))
                    val size = storage.stat(path.toString())?.size ?: 0L
                    if (size in 1..65_536 && bytesRead + size > 8L * 1024 * 1024) {
                        problems += "Recovery notices reached the 8 MiB reading limit. All remaining notice files were kept for manual review."
                        break
                    }
                    val bytes = read(path.toFile(), storage)
                    bytesRead += bytes.size
                    val id = hash(bytes)
                    check(name == "$id.txt")
                    records += RecoveryNotice(id, bytes.toString(Charsets.UTF_8))
                } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (_: Exception) { problems += "An interrupted-operation notice could not be read safely. Its exact file was kept for manual review." }
            }
        }
        check(parent(storage).sameObject(owned))
        return RecoveryNotices(records.sortedWith(compareByDescending<RecoveryNotice> { it.recordedAt ?: Long.MAX_VALUE }.thenBy { it.id }), problems.distinct())
    }
}
