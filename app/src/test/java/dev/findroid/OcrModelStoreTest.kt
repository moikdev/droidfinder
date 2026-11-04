package dev.findroid

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.*
import org.junit.Assert.*

class OcrModelStoreTest {
    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private val language = OcrPolicy.language("eng")
    private val model by lazy { File("src/androidTest/assets/ocr/eng.traineddata").readBytes() }
    private fun store(root: File = File(temporary.root, "ocr"), publish: (File, File) -> Unit = { a,b -> Files.move(a.toPath(), b.toPath()); Unit }) = OcrModelStore(root, publish)

    @Test fun verifiedFixtureMatchesPinnedModelAndInstallsWithoutSourceChanges() {
        assertEquals(language.bytes, model.size.toLong())
        assertEquals(language.sha256, MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) })
        val store = store()
        assertFalse(store.state(language).verified)
        val progress = mutableListOf<Long>()
        val installed = store.install(language, ByteArrayInputStream(model), progress = { progress += it })
        assertTrue(installed.verified)
        assertEquals(language.bytes, progress.last())
        assertTrue(store.incomplete().isEmpty())
        assertArrayEquals(model, File(store.engineDirectory, "tessdata/eng.traineddata").readBytes())
    }
    @Test fun checksumFailureKeepsPartialAndNoActiveModel() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.install(language, ByteArrayInputStream(model.copyOf().apply { this[99] = (this[99].toInt() xor 0xff).toByte() })) }
        assertFalse(store.state(language).verified)
        assertEquals(1, store.incomplete().size)
        assertEquals(language.bytes, store.incomplete().single().bytes)
    }
    @Test fun truncatedAndOversizedImportsNeverActivate() {
        val store = store()
        for (bytes in listOf(model.copyOf(80), model + byteArrayOf(1))) {
            assertThrows(IllegalArgumentException::class.java) { store.install(language, ByteArrayInputStream(bytes)) }
            assertFalse(store.state(language).verified)
        }
        assertEquals(2, store.incomplete().size)
        assertTrue(store.incomplete().all { it.bytes <= language.bytes })
    }
    @Test fun cancellationRetainsOnlyOwnedPartialAndPreservesOtherFiles() {
        val sentinel = File(temporary.root, "document.txt").apply { writeText("Ocean original") }
        val store = store()
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            store.install(language, ByteArrayInputStream(model), check = { if (cancelled) throw CancellationException() }, progress = { cancelled = true })
        }
        assertEquals("Ocean original", sentinel.readText())
        assertEquals(1, store.incomplete().size)
        assertTrue(store.incomplete().single().bytes in 1 until language.bytes)
        assertFalse(store.state(language).verified)
    }
    @Test fun publicationFailureRetainsCompleteStageWithoutAdoptingIt() {
        val store = store(publish = { _,_ -> throw IOException("Injected publication refusal") })
        assertThrows(IOException::class.java) { store.install(language, ByteArrayInputStream(model)) }
        assertEquals(language.bytes, store.incomplete().single().bytes)
        assertFalse(store.state(language).verified)
    }
    @Test fun existingValidModelIsKeptWithoutReadingNewInput() {
        val store = store()
        val before = store.install(language, ByteArrayInputStream(model))
        val unreadable = object : java.io.InputStream() { override fun read(): Int = throw AssertionError("Must not read") }
        assertEquals(before, store.install(language, unreadable))
    }
    @Test fun changedExistingModelCannotBeReplacedAndRemovalRequiresCurrentReview() {
        val store = store()
        val old = store.install(language, ByteArrayInputStream(model))
        val file = File(store.engineDirectory, "tessdata/eng.traineddata").apply { writeText("unexpected bytes") }
        assertThrows(IllegalArgumentException::class.java) { store.install(language, ByteArrayInputStream(model)) }
        assertThrows(IllegalArgumentException::class.java) { store.removeReviewedModel(old) }
        assertEquals("unexpected bytes", file.readText())
        val reviewed = store.state(language)
        store.removeReviewedModel(reviewed)
        assertFalse(file.exists())
    }
    @Test fun incompleteRemovalRequiresMatchingFullReview() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.install(language, ByteArrayInputStream(byteArrayOf(1,2))) }
        val review = store.incomplete()
        assertThrows(IllegalArgumentException::class.java) { store.install(language, ByteArrayInputStream(byteArrayOf(3,4))) }
        assertThrows(IllegalArgumentException::class.java) { store.discardReviewedIncomplete(review) }
        assertEquals(2, store.incomplete().size)
        store.discardReviewedIncomplete(store.incomplete())
        assertTrue(store.incomplete().isEmpty())
    }
    @Test fun linkedStorageAndModelNamesAreRefusedWithoutTouchingTargets() {
        val outside = temporary.newFolder("outside")
        val root = File(temporary.root, "linked")
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { store(root).state(language) }
        assertTrue(outside.listFiles()!!.isEmpty())
        val store = store()
        store.state(language)
        val target = File(outside, "document.txt").apply { writeText("Kept") }
        Files.createSymbolicLink(File(store.engineDirectory, "tessdata/eng.traineddata").toPath(), target.toPath())
        assertThrows(IllegalArgumentException::class.java) { store.state(language) }
        assertEquals("Kept", target.readText())
    }
}
