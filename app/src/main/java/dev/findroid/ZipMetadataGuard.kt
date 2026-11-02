package dev.findroid

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.ZipException
import org.apache.commons.compress.archivers.zip.ZipFile

/** Fixed limits protect the reader's allocations, independently of inflated-content limits. */
private const val ZIP_METADATA_BYTES = 8 * 1024 * 1024
private const val ZIP_EXTRA_FIELDS = 65_536
private const val ZIP_HEADER_EXTRA_FIELDS = 128
private const val ZIP_NAME_BYTES = 16 * 1024
private const val CENTRAL_HEADER = 0x02014b50L
private const val END_HEADER = 0x06054b50L
private const val ZIP64_LOCATOR = 0x07064b50L
private const val ZIP64_END = 0x06064b50L
private const val UINT_MAX = 0xffffffffL

/**
 * Commons Compress indexes every entry before returning a ZipFile. Validate its exact metadata
 * first, with bounded buffers and cancellation. Index from those snapshots on the same open file,
 * so replacing or rewriting a source cannot substitute unchecked headers between the two passes.
 */
internal fun openZip(file: File, limits: ZipLimits, control: TransferControl): ZipFile {
    limits.validated()
    control.check()
    return openZip(Files.newByteChannel(file.toPath(), StandardOpenOption.READ), limits, control)
}

/** Takes ownership of source, including when validation or indexing fails. */
internal fun openZip(source: SeekableByteChannel, limits: ZipLimits, control: TransferControl): ZipFile {
    try {
        limits.validated()
        control.check()
        val channel = control.guard(source) {
            ZipMetadataGuard(source, limits.entries, control).validate()
        }
        return control.guard(channel) {
            ZipFile.builder().setSeekableByteChannel(channel).get().also { channel.indexed = true }
        }
    } catch (error: Throwable) {
        source.close()
        control.check()
        throw error
    }
}

private fun malformed(message: String): Nothing = throw ZipException(message)

private fun ByteArray.short(at: Int): Int =
    (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)

private fun ByteArray.word(at: Int): Long =
    short(at).toLong() or (short(at + 2).toLong() shl 16)

private fun ByteArray.long(at: Int): Long {
    val high = word(at + 4)
    if (high >= 0x80000000L) malformed("ZIP64 metadata exceeds the supported range.")
    return word(at) or (high shl 32)
}

private data class ZipMetadataRange(val start: Long, val bytes: ByteArray) {
    val end: Long get() = start + bytes.size
}

private class ZipMetadataGuard(
    private val source: SeekableByteChannel,
    private val entryLimit: Int,
    private val control: TransferControl,
) {
    private val size = source.size()
    private var metadataBytes = 0
    private var extraFields = 0
    private val ranges = ArrayList<ZipMetadataRange>()

    private fun capture(at: Long, count: Long): ByteArray {
        control.check()
        if (at < 0 || at > size || count < 0 || count > size - at)
            malformed("Truncated or invalid ZIP metadata.")
        if (count > ZIP_METADATA_BYTES - metadataBytes)
            throw ZipLimitException("ZIP metadata exceeds the 8 MiB safety limit.")
        metadataBytes += count.toInt()
        val bytes = ByteArray(count.toInt())
        val buffer = ByteBuffer.wrap(bytes)
        source.position(at)
        while (buffer.hasRemaining()) {
            control.check()
            if (source.read(buffer) < 0) malformed("Truncated ZIP metadata.")
        }
        ranges += ZipMetadataRange(at, bytes)
        return bytes
    }

    /** Validate TLVs without instantiating the reader's per-field objects; find ZIP64 if present. */
    private fun extras(bytes: ByteArray, start: Int, length: Int): Int? {
        var at = start
        val end = start + length
        var fields = 0
        var zip64: Int? = null
        while (at < end) {
            control.check()
            if (end - at < 4) malformed("Truncated ZIP extra field.")
            val count = bytes.short(at + 2)
            if (count > end - at - 4) malformed("Truncated ZIP extra field.")
            if (++fields > ZIP_HEADER_EXTRA_FIELDS || ++extraFields > ZIP_EXTRA_FIELDS)
                throw ZipLimitException("ZIP metadata has too many extra fields for safe indexing.")
            if (bytes.short(at) == 1) {
                if (zip64 != null) malformed("Duplicate ZIP64 extra field.")
                zip64 = at
            }
            at += 4 + count
        }
        return zip64
    }

    fun validate(): ZipMetadataChannel {
        // Match Commons Compress's backwards search, including its choice of the last signature.
        val tailStart = (size - 65_557).coerceAtLeast(0)
        val tail = capture(tailStart, size - tailStart)
        var endAt = tail.size - 22
        while (endAt >= 0 && tail.word(endAt) != END_HEADER) {
            control.check()
            endAt--
        }
        if (endAt < 0) malformed("Archive is not a ZIP archive.")
        val end = tailStart + endAt
        if (tail.short(endAt + 4) != 0 || tail.short(endAt + 6) != 0)
            malformed("Multi-volume ZIP archives are not supported.")
        if (tail.short(endAt + 20) > size - end - 22)
            malformed("Truncated ZIP comment.")

        var count = tail.short(endAt + 10).toLong()
        var directorySize = tail.word(endAt + 12)
        var directory = tail.word(endAt + 16)
        var prefix = 0L
        val locator = if (end > 20) capture(end - 20, 20) else null
        if (locator != null && locator.word(0) == ZIP64_LOCATOR) {
            if (locator.word(4) != 0L || locator.word(16) != 1L)
                malformed("Multi-volume ZIP archives are not supported.")
            val zip64At = locator.long(8)
            val zip64 = capture(zip64At, 56)
            if (zip64.word(0) != ZIP64_END || zip64.long(4) < 44 ||
                zip64At > end - 20 - 12 || zip64.long(4) != end - 20 - 12 - zip64At)
                malformed("Invalid ZIP64 directory locator.")
            if (zip64.word(16) != 0L || zip64.word(20) != 0L ||
                zip64.long(24) != zip64.long(32))
                malformed("Multi-volume ZIP archives are not supported.")
            count = zip64.long(32)
            directorySize = zip64.long(40)
            directory = zip64.long(48)
            if (directory > zip64At || directorySize > zip64At - directory)
                malformed("Invalid ZIP64 central directory.")
        } else {
            if (tail.short(endAt + 8).toLong() != count)
                malformed("Multi-volume ZIP archives are not supported.")
            if (directory > end || directorySize > end - directory)
                malformed("Invalid ZIP central directory.")
            // Ordinary self-extracting ZIPs may store offsets relative to their ZIP payload.
            prefix = end - directorySize - directory
            directory += prefix
        }
        if (count > entryLimit) tooManyEntries()
        // Include the next signature: the reader stops at a non-central-header signature, not count.
        val central = capture(directory, directorySize + 4)
        var at = 0
        var records = 0
        while (central.word(at) == CENTRAL_HEADER) {
            control.check()
            if (++records > entryLimit) tooManyEntries()
            if (directorySize - at < 46) malformed("Truncated ZIP central header.")
            val nameLength = central.short(at + 28)
            val extraLength = central.short(at + 30)
            val commentLength = central.short(at + 32)
            val length = 46 + nameLength + extraLength + commentLength
            if (length > directorySize - at) malformed("Truncated ZIP central header.")
            if (nameLength > ZIP_NAME_BYTES)
                throw ZipLimitException("ZIP filename metadata exceeds the 16 KiB safety limit.")
            val extra = extras(central, at + 46 + nameLength, extraLength)
            var local = central.word(at + 42)
            var disk = central.short(at + 34).toLong()
            if (local == UINT_MAX || disk == 0xffffL) {
                if (extra == null) malformed("Missing ZIP64 entry offsets.")
                var field = extra + 4
                if (central.word(at + 24) == UINT_MAX) field += 8
                if (central.word(at + 20) == UINT_MAX) field += 8
                val extraEnd = extra + 4 + central.short(extra + 2)
                if (local == UINT_MAX) {
                    if (extraEnd - field < 8) malformed("Truncated ZIP64 entry offset.")
                    local = central.long(field)
                    field += 8
                } else {
                    local += prefix
                }
                if (disk == 0xffffL) {
                    if (extraEnd - field < 4) malformed("Truncated ZIP64 disk number.")
                    disk = central.word(field)
                }
            } else {
                local += prefix
            }
            if (disk != 0L) malformed("Multi-volume ZIP archives are not supported.")
            if (local > directory || directory - local < 30)
                malformed("Invalid ZIP local header offset.")
            val header = capture(local, 30)
            if (header.word(0) != 0x04034b50L) malformed("Invalid ZIP local header.")
            val localName = header.short(26)
            val localExtra = header.short(28)
            if (localName > ZIP_NAME_BYTES)
                throw ZipLimitException("ZIP filename metadata exceeds the 16 KiB safety limit.")
            if (localName + localExtra > directory - local - 30)
                malformed("Truncated ZIP local header.")
            val variable = capture(local + 30, (localName + localExtra).toLong())
            extras(variable, localName, localExtra)
            at += length
        }
        if (records.toLong() != count) malformed("ZIP entry count differs from its directory.")
        // The reader probes the first local-header location even for an empty directory. ZIP64
        // uses offset zero here, which can precede the tail snapshot of a large self-extractor.
        if (records == 0) capture(prefix, 4)
        control.check()
        return ZipMetadataChannel(source, size, mergeRanges(), control)
    }

    private fun tooManyEntries(): Nothing =
        throw ZipLimitException("This archive exceeds the $entryLimit entry limit.")

    /** Coalesce overlaps so a snapshot read never falls through to a live, unchecked header. */
    private fun mergeRanges(): List<ZipMetadataRange> {
        val sorted = ranges.sortedBy { it.start }
        val merged = ArrayList<ZipMetadataRange>()
        var first = 0
        while (first < sorted.size) {
            control.check()
            val start = sorted[first].start
            var end = sorted[first].end
            var next = first + 1
            while (next < sorted.size && sorted[next].start <= end) {
                end = maxOf(end, sorted[next].end)
                next++
            }
            val bytes = ByteArray((end - start).toInt())
            var filled = 0
            for (i in first until next) {
                control.check()
                val range = sorted[i]
                val at = (range.start - start).toInt()
                val overlap = minOf(range.bytes.size, filled - at)
                for (j in 0 until overlap) {
                    if (bytes[at + j] != range.bytes[j])
                        malformed("The archive changed while reading its metadata. Reopen it.")
                }
                range.bytes.copyInto(bytes, at)
                filled = maxOf(filled, at + range.bytes.size)
            }
            merged += ZipMetadataRange(start, bytes)
            first = next
        }
        return merged
    }
}

/** Only validated snapshots are readable during indexing; member streams use the same open file. */
private class ZipMetadataChannel(
    private val source: SeekableByteChannel,
    private val length: Long,
    private var ranges: List<ZipMetadataRange>,
    private val control: TransferControl,
) : SeekableByteChannel {
    private var offset = 0L
    var indexed = false
        set(value) {
            field = value
            if (value) ranges = emptyList()
        }

    override fun read(dst: ByteBuffer): Int {
        control.check()
        if (!dst.hasRemaining()) return 0
        if (offset >= length) return -1
        if (indexed) {
            source.position(offset)
            val count = source.read(dst)
            if (count > 0) offset += count
            return count
        }
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (ranges[middle].start <= offset) low = middle + 1 else high = middle - 1
        }
        val range = ranges.getOrNull(high)
        if (range == null || offset >= range.end)
            malformed("ZIP reader requested unchecked metadata.")
        val count = minOf(dst.remaining().toLong(), range.end - offset).toInt()
        dst.put(range.bytes, (offset - range.start).toInt(), count)
        offset += count
        return count
    }

    override fun position(): Long = offset
    override fun position(newPosition: Long): SeekableByteChannel {
        control.check()
        require(newPosition >= 0)
        offset = newPosition
        return this
    }
    override fun size(): Long = length
    override fun isOpen(): Boolean = source.isOpen
    override fun close() = source.close()
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
}
