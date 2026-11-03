package dev.findroid

import java.security.MessageDigest

/** Pinned, non-following regular-file reads; Android storage opens with O_NONBLOCK as well. */
internal fun folderSyncDigest(
    path: String,
    expected: PartialFileStamp,
    control: TransferControl,
    storage: PartialCopyStorage = AndroidPartialCopyStorage(),
): String {
    control.check()
    check(expected.regular && !expected.directory && expected.links == 1L && expected.size >= 0) {
        "Only verified local regular files can be synchronized."
    }
    check(storage.stat(path) == expected) { "A reviewed file changed. Compare again." }
    val digest = MessageDigest.getInstance("SHA-256")
    storage.open(path).use { handle ->
        control.guard(handle) {
            check(handle.stamp() == expected) { "A reviewed file changed before it could be read. Compare again." }
            val buffer = ByteArray(64 * 1024)
            var remaining = expected.size
            while (remaining > 0) {
                control.check()
                val requested = minOf(remaining, buffer.size.toLong()).toInt()
                val count = handle.read(buffer, 0, requested)
                check(count in 1..requested) { "A reviewed file ended before its expected size. Compare again." }
                digest.update(buffer, 0, count)
                remaining -= count
            }
            control.check()
            check(handle.read(buffer, 0, 1) in -1..0 && handle.stamp() == expected && storage.stat(path) == expected) {
                "A reviewed file changed while it was being read. Compare again."
            }
        }
    }
    val alphabet = "0123456789abcdef"
    return buildString(64) {
        digest.digest().forEach { value ->
            val byte = value.toInt() and 255
            append(alphabet[byte ushr 4]);append(alphabet[byte and 15])
        }
    }
}
