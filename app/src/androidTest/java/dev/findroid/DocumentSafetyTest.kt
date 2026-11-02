package dev.findroid

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Data-loss regressions use real DocumentsProvider calls and inspect the surviving bytes. */
class DocumentSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: File
    private lateinit var destination: File
    private lateinit var docs: DocumentAccess
    private lateinit var root: String

    @Before
    fun setup() {
        fixture = File(context.filesDir, "ProviderFixtures/Safety-${System.nanoTime()}").apply { mkdirs() }
        destination = File(context.filesDir, "Safety-out-${System.nanoTime()}").apply { mkdirs() }
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture.name}")
        root = DocumentsContract.buildDocumentUriUsingTree(tree, "root/${fixture.name}").toString()
        docs = DocumentAccess(context)
    }

    @After
    fun cleanup() {
        FixtureDocumentsProvider.listingDirectory = null
        FixtureDocumentsProvider.listingExtras = null
        FixtureDocumentsProvider.readByteLimits = emptyMap()
        // Remove explicit fixture links before test-only recursive cleanup.
        File(fixture, "link").delete()
        fixture.deleteRecursively()
        destination.deleteRecursively()
    }

    private fun pendingFolder(error: Boolean): String {
        val source = File(fixture, "Source").apply { mkdir() }
        File(source, "keep.txt").writeText("source must survive")
        val path = docs.list(root).single().path
        FixtureDocumentsProvider.listingDirectory = source.canonicalPath
        FixtureDocumentsProvider.listingExtras = Bundle().apply {
            if (error) putString(DocumentsContract.EXTRA_ERROR, "Cloud listing incomplete")
            else putBoolean(DocumentsContract.EXTRA_LOADING, true)
        }
        return path
    }

    private fun incompleteFolderIsKept(error: Boolean, move: Boolean) {
        val source = pendingFolder(error)
        val failure = runCatching { docs.copy(source, destination.path, move) }.exceptionOrNull()
        assertNotNull("Incomplete provider listings cannot authorize a folder transfer", failure)
        assertEquals("source must survive", File(fixture, "Source/keep.txt").readText())
        assertTrue("An incomplete copy must not be published", destination.list()!!.isEmpty())
    }

    @Test fun loadingFolderCannotBeMoved() = incompleteFolderIsKept(false, true)
    @Test fun errorFolderCannotBeMoved() = incompleteFolderIsKept(true, true)
    @Test fun loadingFolderCannotBePublishedAsACompleteCopy() = incompleteFolderIsKept(false, false)
    @Test fun errorFolderCannotBePublishedAsACompleteCopy() = incompleteFolderIsKept(true, false)

    @Test
    fun pendingDestinationCannotHideAnExistingCollision() {
        File(fixture, "keep.txt").writeText("existing provider file")
        val incoming = File(destination, "keep.txt").apply { writeText("incoming") }
        FixtureDocumentsProvider.listingDirectory = fixture.canonicalPath
        FixtureDocumentsProvider.listingExtras = Bundle().apply {
            putBoolean(DocumentsContract.EXTRA_LOADING, true)
        }
        assertTrue(runCatching { docs.copy(incoming.path, root, true) }.isFailure)
        assertEquals("incoming", incoming.readText())
        assertEquals("existing provider file", File(fixture, "keep.txt").readText())
        assertEquals(setOf("keep.txt"), fixture.list()!!.toSet())
    }

    @Test
    fun providerReturningPrematureEofCannotDeleteTheOriginal() {
        val file = File(fixture, "short.bin").apply { writeText("complete source bytes") }
        val source = docs.list(root).single().path
        FixtureDocumentsProvider.readByteLimits = mapOf(file.canonicalPath to 3)
        val failure = runCatching { docs.copy(source, destination.path, true) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("incomplete"))
        assertEquals("complete source bytes", file.readText())
        assertTrue(destination.list()!!.isEmpty())
    }

    @Test
    fun importingTruncatedProviderStreamsKeepsBothDestinationsClean() {
        val file = File(fixture, "short.bin").apply { writeText("complete source bytes") }
        val providerOut = File(fixture, "Out").apply { mkdir() }
        val source = docs.list(root).first { it.name == file.name }.path
        val provider = docs.list(root).first { it.name == providerOut.name }.path
        FixtureDocumentsProvider.readByteLimits = mapOf(file.canonicalPath to 3)
        for (parent in listOf(destination.path, provider)) {
            assertTrue(runCatching { docs.importUri(android.net.Uri.parse(source), parent, TransferControl()) }.isFailure)
        }
        assertEquals("complete source bytes", file.readText())
        assertTrue(destination.list()!!.isEmpty())
        assertTrue(providerOut.list()!!.isEmpty())
    }

    @Test
    fun unknownSizeProviderStillCopiesAllAvailableBytes() {
        val file = File(fixture, "Unknown-size.bin").apply { writeText("size is unknown, data is complete") }
        val source = docs.list(root).single()
        assertFalse(source.sizeKnown)
        val copied = docs.copy(source.path, destination.path, true)
        assertEquals("size is unknown, data is complete", File(copied).readText())
        assertFalse(file.exists())
    }

    @Test
    fun sourceChangedAfterPlanningCannotBeMoved() {
        val file = File(fixture, "changed.txt").apply { writeText("original source") }
        val source = docs.list(root).single().path
        var changed = false
        val control = TransferControl(progress = {
            if (!changed && it.phase == "Moving") {
                changed = true
                file.writeText("edited")
            }
        })
        assertTrue(runCatching { docs.copy(source, destination.path, true, control) }.isFailure)
        assertTrue(changed)
        assertEquals("edited", file.readText())
        assertTrue(destination.list()!!.isEmpty())
    }

    @Test
    fun providerFolderChangedDuringCopyIsKeptWithItsNewMember() {
        val folder = File(fixture, "Changing").apply { mkdir() }
        File(folder, "first.txt").writeText("first")
        val source = docs.list(root).single().path
        var added = false
        val control = TransferControl(progress = {
            if (!added && it.bytes > 0) {
                added = true
                File(folder, "arrived.txt").writeText("arrived")
            }
        })
        assertTrue(runCatching { docs.copy(source, destination.path, true, control) }.isFailure)
        assertTrue(added)
        assertEquals("first", File(folder, "first.txt").readText())
        assertEquals("arrived", File(folder, "arrived.txt").readText())
        assertTrue(destination.list()!!.isEmpty())
    }

    @Test
    fun deletingLocalFolderDoesNotTraverseDirectoryLinks() {
        val keep = File(destination, "keep.txt").apply { writeText("outside data") }
        Files.createSymbolicLink(File(fixture, "link").toPath(), destination.toPath())
        docs.delete(fixture.path)
        assertFalse(fixture.exists())
        assertEquals("outside data", keep.readText())
    }
}
