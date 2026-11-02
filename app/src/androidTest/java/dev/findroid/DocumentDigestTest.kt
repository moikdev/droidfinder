package dev.findroid

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DocumentDigestTest {
    @Test
    fun folderFingerprintsFrameNamesAndContentsAndFilesUseStandardSha256() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "Digest-${System.nanoTime()}").apply { mkdirs() }
        try {
            val docs = DocumentAccess(context)
            val first = File(root, "First").apply { mkdir() }
            File(first, "a").writeText("bc")
            val second = File(root, "Second").apply { mkdir() }
            File(second, "ab").writeText("c")
            val equal = File(root, "Equal").apply { mkdir() }
            File(equal, "a").writeText("bc")
            assertNotEquals(docs.digest(first.path), docs.digest(second.path))
            assertEquals(docs.digest(first.path), docs.digest(equal.path))
            val expected =
                MessageDigest.getInstance("SHA-256").digest("bc".toByteArray()).joinToString("") {
                    "%02x".format(it)
                }
            assertEquals(expected, docs.digest(File(first, "a").path))
            val cancelled = TransferControl().apply { cancel() }
            assertTrue(
                runCatching { docs.digest(first.path, cancelled) }.exceptionOrNull()
                    is java.util.concurrent.CancellationException,
            )
            assertEquals("bc", File(first, "a").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
