package dev.findroid

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(
        entries: Int = 1,
        localExtra: ByteArray = byteArrayOf(),
        centralExtra: ByteArray = localExtra,
        comment: String = "",
        name: String = "Readme.txt",
    ): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(entries) { index ->
                zip.putNextEntry(ZipEntry(if (entries == 1) name else "$index.txt").apply {
                    method = ZipEntry.STORED
                    size = 0
                    compressedSize = 0
                    crc = 0
                    extra = localExtra
                    this.comment = comment
                })
                zip.closeEntry()
            }
        }
        // The writer repeats local extras centrally. Replace equal-sized central fields separately.
        if (!centralExtra.contentEquals(localExtra)) {
            require(centralExtra.size == localExtra.size)
            RandomAccessFile(file, "rw").use { raw ->
                var at = 0L
                while (at < raw.length() - 46) {
                    raw.seek(at)
                    if (Integer.reverseBytes(raw.readInt()) == 0x02014b50) {
                        raw.seek(at + 28)
                        val nameBytes = java.lang.Short.reverseBytes(raw.readShort()).toInt() and 65535
                        raw.seek(at + 46 + nameBytes)
                        raw.write(centralExtra)
                    }
                    at++
                }
            }
        }
    }

    private fun patchEndCount(file: File, count: Int) {
        RandomAccessFile(file, "rw").use { raw ->
            raw.seek(raw.length() - 22 + 8)
            repeat(2) { raw.write(count and 255); raw.write(count ushr 8) }
        }
    }

    private fun extraFields(count: Int) = ByteArray(count * 4).apply {
        repeat(count) { index ->
            val id = 0x4000 + index
            this[index * 4] = id.toByte()
            this[index * 4 + 1] = (id ushr 8).toByte()
        }
    }

    @Test
    fun configuredEntryLimitRejectsBeforeReaderIndexingOrOutputCreation() {
        val file = archive(entries = 101)
        val limits = ZipLimits(entries = 100)
        val error = assertThrows(ZipLimitException::class.java) { inspectZip(file, limits) }
        assertTrue(error.message.orEmpty().contains("100 entry limit"))
        assertThrows(ZipLimitException::class.java) { verifyZip(file, limits) }
        val destination = temporary.newFolder()
        val engine = FileEngine(temporary.newFolder())
        assertThrows(ZipLimitException::class.java) {
            engine.extract(file, destination, limits = limits)
        }
        assertTrue(destination.listFiles()!!.isEmpty())
        assertEquals(101, inspectZip(file, ZipLimits(entries = 101)).items.size)
    }

    @Test
    fun actualDirectoryCountCannotBypassLimitWithAForgedEndRecord() {
        val file = archive(entries = 101)
        patchEndCount(file, 1)
        assertThrows(ZipLimitException::class.java) { inspectZip(file, ZipLimits(entries = 100)) }
    }

    @Test
    fun inconsistentCountIsRejectedEvenWhenBelowResourceLimits() {
        val file = archive(entries = 2)
        patchEndCount(file, 1)
        assertThrows(ZipException::class.java) { inspectZip(file) }
    }

    @Test
    fun largeAggregateCommentsAreRejectedBeforeReaderAllocations() {
        val file = archive(entries = 140, comment = "c".repeat(65535))
        val error = assertThrows(ZipLimitException::class.java) { inspectZip(file) }
        assertTrue(error.message.orEmpty().contains("8 MiB"))
    }

    @Test
    fun oversizedNamesAreRejectedBeforeDecoding() {
        val file = archive(name = "a".repeat(16 * 1024 + 1))
        assertThrows(ZipLimitException::class.java) { inspectZip(file) }
    }

    @Test
    fun excessiveCentralExtraFieldsAreRejected() {
        val file = archive(localExtra = extraFields(129))
        val error = assertThrows(ZipLimitException::class.java) { inspectZip(file) }
        assertTrue(error.message.orEmpty().contains("extra fields"))
    }

    @Test
    fun excessiveLocalExtraFieldsAreRejectedWhenCentralFieldsAreSmall() {
        val central = ByteArray(129 * 4).apply {
            this[0] = 0x42
            this[1] = 0x42
            this[2] = 0
            this[3] = 2 // One unknown field containing 512 bytes.
        }
        val file = archive(localExtra = extraFields(129), centralExtra = central)
        assertThrows(ZipLimitException::class.java) { inspectZip(file) }
    }

    @Test
    fun aggregateExtraFieldsAreBoundedAcrossBothHeaderCopies() {
        val file = archive(entries = 260, localExtra = extraFields(128))
        assertThrows(ZipLimitException::class.java) { inspectZip(file) }
    }

    @Test
    fun malformedExtraFieldsAreRejectedWithoutIndexing() {
        val file = archive(localExtra = byteArrayOf(0x42, 0x42, 0, 0))
        RandomAccessFile(file, "rw").use { raw ->
            raw.seek(30 + "Readme.txt".length + 2L)
            raw.write(0xff)
            raw.write(0xff)
        }
        assertThrows(ZipException::class.java) { inspectZip(file) }
    }

    @Test
    fun selfExtractingPrefixAndArchiveCommentsRemainReadable() {
        val file = archive(comment = "member comment", localExtra = extraFields(3))
        file.writeBytes("#!/bin/sh\nexit\n".toByteArray() + file.readBytes())
        assertEquals(listOf("Readme.txt"), inspectZip(file).items.map { it.path })
        assertTrue(verifyZip(file).healthy)
    }

    @Test
    fun emptyZip64SelfExtractorCanHaveALargePrefix() {
        val prefix = ByteArray(70_000).apply { this[0] = 'M'.code.toByte(); this[1] = 'Z'.code.toByte() }
        val bytes = java.nio.ByteBuffer.allocate(prefix.size + 98)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(prefix)
            .putInt(0x06064b50).putLong(44).putShort(45).putShort(45)
            .putInt(0).putInt(0).putLong(0).putLong(0).putLong(0).putLong(prefix.size.toLong())
            .putInt(0x07064b50).putInt(0).putLong(prefix.size.toLong()).putInt(1)
            .putInt(0x06054b50).putShort(0).putShort(0).putShort(0).putShort(0)
            .putInt(0).putInt(prefix.size).putShort(0)
            .array()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        assertTrue(inspectZip(file).items.isEmpty())
        assertTrue(verifyZip(file).healthy)
    }

    @Test
    fun longArchiveCommentRemainsReadable() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setComment("c".repeat(65_535))
            zip.putNextEntry(ZipEntry("Readme.txt"))
            zip.write("content".toByteArray())
            zip.closeEntry()
        }
        assertEquals(7L, inspectZip(file).size)
        assertTrue(verifyZip(file).healthy)
    }

    @Test
    fun truncatedDirectoryAndImpossibleZip64LocatorsFailCleanly() {
        val file = archive()
        RandomAccessFile(file, "rw").use { raw -> raw.setLength(raw.length() - 4) }
        assertThrows(ZipException::class.java) { inspectZip(file) }
        val zip64 = temporary.newFile().apply {
            ZipMetadataTest::class.java.getResourceAsStream("/zip64-small.zip")!!.use { input ->
                outputStream().use(input::copyTo)
            }
        }
        RandomAccessFile(zip64, "rw").use { raw ->
            raw.seek(raw.length() - 22 - 20 + 8)
            repeat(8) { raw.write(0xff) }
        }
        assertThrows(ZipException::class.java) { inspectZip(zip64) }
    }

    @Test
    fun cancellationDuringMetadataCaptureClosesTheSource() {
        val file = archive(entries = 20)
        val source = java.nio.file.Files.newByteChannel(file.toPath())
        val control = TransferControl()
        val cancelling = object : java.nio.channels.SeekableByteChannel by source {
            override fun read(dst: java.nio.ByteBuffer): Int = source.read(dst).also {
                control.cancel()
            }
        }
        assertThrows(CancellationException::class.java) {
            openZip(cancelling, ZipLimits(), control)
        }
        assertFalse(source.isOpen)
    }

    @Test
    fun readerIndexesSnapshotsWhenTheSourceIsRewrittenAfterValidation() {
        val file = archive()
        val source = java.nio.file.Files.newByteChannel(file.toPath())
        var rewritten = false
        val rewriting = object : java.nio.channels.SeekableByteChannel by source {
            override fun read(dst: java.nio.ByteBuffer): Int {
                check(!rewritten) { "Indexing requested unchecked source bytes after validation." }
                val start = source.position()
                return source.read(dst).also { count ->
                    // Rewrite after the sole local header's filename has been captured.
                    if (start == 30L && count == "Readme.txt".length) {
                        patchEndCount(file, 101)
                        rewritten = true
                    }
                }
            }
        }
        openZip(rewriting, ZipLimits(), TransferControl()).use { zip ->
            assertTrue(rewritten)
            assertEquals(listOf("Readme.txt"), java.util.Collections.list(zip.entries).map { it.name })
        }
        assertFalse(source.isOpen)
        assertThrows(ZipException::class.java) { inspectZip(file) }
    }

    @Test
    fun cancelledMetadataReadNeverOpensTheReader() {
        val control = TransferControl().apply { cancel() }
        assertThrows(CancellationException::class.java) { inspectZip(archive(), control = control) }
    }
}
