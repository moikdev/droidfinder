package dev.findroid

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ZipPlatformTest {
    @Test
    fun emptyZip64SelfExtractorOpensWithoutUncheckedHeaderReads() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "EmptyZip64-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(root, "Empty.zip")
            instrumentation.context.assets.open("zip64-empty-sfx.zip").use { input ->
                archive.outputStream().use(input::copyTo)
            }
            assertTrue(inspectZip(archive).items.isEmpty())
            assertTrue(verifyZip(archive).healthy)
            val output = FileEngine(File(root, "state")).extract(archive, root)
            assertTrue(output.isDirectory)
            assertTrue(output.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedDirectoryIsRejectedBeforeIndexingAndDoesNotPublishOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "ZipMetadata-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(root, "Many.zip")
            java.util.zip.ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                repeat(10_001) { index ->
                    zip.putNextEntry(java.util.zip.ZipEntry("$index.txt").apply {
                        method = java.util.zip.ZipEntry.STORED
                        size = 0
                        compressedSize = 0
                        crc = 0
                    })
                    zip.closeEntry()
                }
            }
            assertThrows(ZipLimitException::class.java) { inspectZip(archive) }
            assertThrows(ZipLimitException::class.java) { verifyZip(archive) }
            assertThrows(ZipLimitException::class.java) {
                FileEngine(File(root, "state")).extract(archive, root)
            }
            assertFalse(File(root, "Many").exists())
            // A forged EOCD count cannot hide the actual number of central headers.
            java.io.RandomAccessFile(archive, "rw").use { raw ->
                raw.seek(raw.length() - 22 + 8)
                repeat(2) { raw.write(1); raw.write(0) }
            }
            assertThrows(ZipLimitException::class.java) { inspectZip(archive) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun optionalCodecsAreRejectedWithoutLoadingMissingClasses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "ZipMethods-${System.nanoTime()}").apply { mkdirs() }
        try {
            val fixture = instrumentation.context.assets.open("zip64-small.zip").use { it.readBytes() }
            for (method in listOf(12, 93, 95)) {
                val bytes = fixture.clone()
                for (i in 0 until bytes.size - 12) {
                    if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte()) {
                        val offset = when {
                            bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte() -> 8
                            bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() -> 10
                            else -> continue
                        }
                        bytes[i + offset] = method.toByte()
                        bytes[i + offset + 1] = 0
                    }
                }
                val archive = File(root, "Method$method.zip").apply { writeBytes(bytes) }
                val error = assertThrows(IllegalArgumentException::class.java) { inspectZip(archive) }
                assertTrue(error.message.orEmpty().contains("Unsupported ZIP compression method"))
                assertThrows(IllegalArgumentException::class.java) {
                    FileEngine(File(root, "state")).extract(archive, root)
                }
                assertFalse(File(root, "Method$method").exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zip64CanBeVerifiedAndExtractedAtTheMinimumAndroidVersion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "ZipPlatform-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(root, "ZIP64.zip")
            instrumentation.context.assets.open("zip64-small.zip").use { input ->
                archive.outputStream().use(input::copyTo)
            }
            val checked = verifyZip(archive)
            assertTrue(checked.toString(), checked.healthy)
            assertEquals(22L, checked.bytes)
            val engine = FileEngine(File(root, "state"))
            val extracted = engine.extract(archive, root)
            assertEquals("ZIP64 preview fixture\n", File(extracted, "Readme.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
