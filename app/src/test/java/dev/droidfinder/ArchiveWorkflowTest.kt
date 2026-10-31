package dev.droidfinder

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ArchiveWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var engine: FileEngine

    @Before
    fun setup() {
        root = temporary.newFolder("files")
        engine = FileEngine(temporary.newFolder("state"))
    }

    private fun zip(name: String, values: Map<String, ByteArray>, stored: Boolean = false): File =
        File(root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                values.forEach { (path, bytes) ->
                    val entry = ZipEntry(path)
                    if (stored) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = entry.size
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    output.putNextEntry(entry)
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }

    private fun fixture(name: String) =
        File(root, name).apply {
            ArchiveWorkflowTest::class.java.getResourceAsStream("/$name")!!.use { input ->
                outputStream().use(input::copyTo)
            }
        }

    @Test
    fun selectedFilesAndFoldersPreservePathsAndDoNotExtractOthers() {
        val archive =
            zip(
                "Bundle.zip",
                mapOf(
                    "A/one.txt" to "one".toByteArray(),
                    "A/Deep/two.txt" to "two".toByteArray(),
                    "B/skip.txt" to "skip".toByteArray(),
                    "Empty/" to byteArrayOf(),
                ),
            )
        val before = archive.readBytes()
        val output = engine.extract(archive, root, selection = setOf("A/Deep/two.txt", "Empty"))
        assertEquals("two", File(output, "A/Deep/two.txt").readText())
        assertTrue(File(output, "Empty").isDirectory)
        assertFalse(File(output, "A/one.txt").exists())
        assertFalse(File(output, "B").exists())
        val folder = engine.extract(archive, root, selection = setOf("A", "A/Deep/two.txt"))
        assertEquals("one", File(folder, "A/one.txt").readText())
        assertEquals("two", File(folder, "A/Deep/two.txt").readText())
        assertArrayEquals(before, archive.readBytes())
    }

    @Test
    fun crcCorruptionIsReportedAndNeverPublished() {
        val payload = "INTEGRITY_PAYLOAD_UNIQUE".toByteArray()
        val archive =
            zip(
                "Broken.zip",
                mapOf("bad.txt" to payload, "good.txt" to "good".toByteArray()),
                stored = true,
            )
        val bytes = archive.readBytes()
        val at =
            bytes.indices.first { i ->
                i + payload.size <= bytes.size &&
                    payload.indices.all { bytes[i + it] == payload[it] }
            }
        bytes[at] = (bytes[at].toInt() xor 1).toByte()
        archive.writeBytes(bytes)
        val checked = verifyZip(archive)
        assertFalse(checked.healthy)
        assertEquals(1, checked.failures)
        assertEquals(2, checked.checked)
        assertTrue(checked.details.first().contains("bad.txt"))
        assertThrows(ZipIntegrityException::class.java) { engine.extract(archive, root) }
        assertFalse(File(root, "Broken").exists())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".droidfinder-") })
        assertEquals(
            "good",
            File(engine.extract(archive, root, selection = setOf("good.txt")), "good.txt")
                .readText(),
        )
    }

    @Test
    fun declaredAndActualBudgetsAndRatioAreEnforced() {
        val archive = zip("Data.zip", mapOf("a.txt" to ByteArray(100), "b.txt" to ByteArray(100)))
        assertThrows(ZipLimitException::class.java) {
            engine.extract(
                archive,
                root,
                limits = ZipLimits(expandedBytes = 150, memberBytes = 150),
            )
        }
        assertTrue(
            File(
                    engine.extract(
                        archive,
                        root,
                        selection = setOf("a.txt"),
                        limits = ZipLimits(expandedBytes = 150, memberBytes = 150),
                    ),
                    "a.txt",
                )
                .exists(),
        )
        val bomb = zip("Ratio.zip", mapOf("zeros.bin" to ByteArray(3 * 1024 * 1024)))
        assertThrows(ZipLimitException::class.java) { engine.extract(bomb, root) }
        val checked = verifyZip(bomb)
        assertNotNull(checked.limit)
        assertFalse(checked.healthy)
        assertTrue(verifyZip(bomb, ZipLimits(ratio = 10000)).healthy)
        assertFalse(File(root, "Ratio").exists())
    }

    @Test
    fun cancelledExtractionRollsBackAndCheckingDoesNotClaimSuccess() {
        val archive =
            zip(
                "Cancel.zip",
                mapOf("large.bin" to ByteArray(512 * 1024) { (it % 251).toByte() }),
                stored = true,
            )
        lateinit var control: TransferControl
        control = TransferControl { if (it.bytes >= 64 * 1024) control.cancel() }
        assertThrows(CancellationException::class.java) { engine.extract(archive, root, control) }
        assertFalse(File(root, "Cancel").exists())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".droidfinder-") })
        lateinit var checking: TransferControl
        checking = TransferControl { if (it.bytes >= 64 * 1024) checking.cancel() }
        assertThrows(CancellationException::class.java) { verifyZip(archive, control = checking) }
    }

    @Test
    fun zip64DirectoryAndLocalHeadersWorkWithoutLargeAllocations() {
        val small = fixture("zip64-small.zip")
        assertEquals(22L, inspectZip(small).size)
        assertTrue(verifyZip(small).healthy)
        assertEquals(
            "ZIP64 preview fixture\n",
            File(engine.extract(small, root), "Readme.txt").readText(),
        )
        val large = fixture("zip64-large-metadata.zip")
        assertEquals(4L * 1024 * 1024 * 1024 + 1, inspectZip(large).size)
        assertThrows(ZipLimitException::class.java) { engine.extract(large, root) }
        assertNotNull(verifyZip(large).limit)
    }

    @Test
    fun aliasesCollisionsAndUnselectedUnsafeNamesAreRejected() {
        val duplicate =
            zip(
                "Aliases.zip",
                mapOf("dir/file.txt" to byteArrayOf(1), "dir/./file.txt" to byteArrayOf(2)),
            )
        assertThrows(IllegalArgumentException::class.java) { inspectZip(duplicate) }
        val conflict =
            zip("Conflict.zip", mapOf("parent" to byteArrayOf(1), "parent/child" to byteArrayOf(2)))
        assertThrows(IllegalArgumentException::class.java) { engine.extract(conflict, root) }
        val unsafe =
            zip("Unsafe.zip", mapOf("safe.txt" to byteArrayOf(1), "../escape" to byteArrayOf(2)))
        assertThrows(IllegalArgumentException::class.java) {
            engine.extract(unsafe, root, selection = setOf("safe.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectedZipItems(
                inspectZip(zip("Valid.zip", mapOf("safe.txt" to byteArrayOf(1)))),
                setOf("missing"),
            )
        }
    }

    @Test
    fun capacityFailureAndEntryLimitsLeaveNoOutput() {
        val archive = zip("Space.zip", mapOf("a.txt" to ByteArray(100)))
        val full = FileEngine(temporary.newFolder("full-state")) { 1L }
        assertThrows(Exception::class.java) { full.extract(archive, root) }
        assertFalse(File(root, "Space").exists())
        val many = zip("Many.zip", (0..100).associate { "$it.txt" to byteArrayOf(1) })
        assertThrows(ZipLimitException::class.java) { inspectZip(many, ZipLimits(entries = 100)) }
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".droidfinder-") })
    }
}
