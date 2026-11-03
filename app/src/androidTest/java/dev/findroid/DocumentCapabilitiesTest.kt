package dev.findroid

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real resolver/cursor reads against an isolated debug provider; no user grant is changed. */
class DocumentCapabilitiesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: File
    private lateinit var note: File
    private lateinit var docs: DocumentAccess
    private lateinit var tree: android.net.Uri
    private lateinit var root: String
    private lateinit var path: String

    @Before fun setup() {
        fixture = File(context.filesDir, "ProviderFixtures/Capabilities-${System.nanoTime()}").apply { mkdirs() }
        note = File(fixture, "Original.txt").apply { writeText("Keep these exact provider bytes.") }
        tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture.name}")
        root = DocumentsContract.buildDocumentUriUsingTree(tree, "root/${fixture.name}").toString()
        path = DocumentsContract.buildDocumentUriUsingTree(tree, "root/${fixture.name}/${note.name}").toString()
        docs = DocumentAccess(context, FileEngine(File(fixture, "test-state")))
    }

    @After fun cleanup() {
        FixtureDocumentsProvider.capabilityFlags = emptyMap()
        FixtureDocumentsProvider.omittedCapabilityColumns = emptySet()
        assertEquals("Keep these exact provider bytes.", note.readText())
        fixture.deleteRecursively()
    }

    private fun facts(entry: Entry) = StorageAccessFacts(entry.path, entry.name, entry.folder,
        provider = true, loaded = true, readable = entry.readable, writable = entry.writable,
        rename = entry.canRename, delete = entry.canDelete, document = entry.providerAccess)

    private fun assertUnknown(entry: Entry) {
        assertNotNull(entry.providerAccess)
        assertNull(entry.providerAccess!!.flags)
        assertFalse(entry.writable)
        assertFalse(entry.canRename)
        assertFalse(entry.canDelete)
        for (action in listOf(StorageAction.WRITE, StorageAction.RENAME, StorageAction.DELETE, StorageAction.TRASH))
            assertEquals(action.name, ActionSupport.UNKNOWN, storageActionDetail(facts(entry), action).support)
    }

    @Test fun omittedCapabilityColumnIsUnknownForDirectAndChildQueries() {
        FixtureDocumentsProvider.omittedCapabilityColumns = setOf(note.canonicalPath, fixture.canonicalPath)
        assertUnknown(docs.read(path))
        assertUnknown(docs.list(root).single { it.name == note.name })
        assertNull(docs.read(root).providerAccess!!.flags)
    }

    @Test fun explicitNullCapabilityValueRemainsUnknown() {
        FixtureDocumentsProvider.capabilityFlags = mapOf(note.canonicalPath to null)
        assertUnknown(docs.read(path))
        assertUnknown(docs.list(root).single { it.name == note.name })
    }

    @Test fun malformedAndOutOfRangeCapabilitiesCannotBecomeZeroOrWrappedFlags() {
        for (value in listOf<Any>("unreported", "6", 1.5, -1L, Long.MAX_VALUE, Int.MAX_VALUE.toLong() + 1)) {
            FixtureDocumentsProvider.capabilityFlags = mapOf(note.canonicalPath to value)
            assertUnknown(docs.read(path))
        }
    }

    @Test fun explicitZeroIsKnownUnsupportedAndDoesNotInheritRootFlags() {
        FixtureDocumentsProvider.capabilityFlags = mapOf(note.canonicalPath to 0)
        assertTrue(docs.read(root).writable)
        val entry = docs.list(root).single { it.name == note.name }
        assertEquals(0, entry.providerAccess!!.flags)
        assertEquals(path, entry.providerAccess!!.path)
        assertEquals(ActionSupport.UNAVAILABLE, storageActionDetail(facts(entry), StorageAction.RENAME).support)
        assertFalse(entry.canRename)
        assertFalse(entry.canDelete)
    }

    @Test fun individualFlagsRemainIndependentForActualDocuments() {
        for ((flag, action) in listOf(Document.FLAG_SUPPORTS_WRITE to StorageAction.WRITE,
            Document.FLAG_SUPPORTS_RENAME to StorageAction.RENAME, Document.FLAG_SUPPORTS_DELETE to StorageAction.DELETE)) {
            FixtureDocumentsProvider.capabilityFlags = mapOf(note.canonicalPath to flag)
            val item = facts(docs.read(path))
            for (candidate in listOf(StorageAction.WRITE, StorageAction.RENAME, StorageAction.DELETE))
                assertEquals(candidate.name, if (candidate == action) ActionSupport.AVAILABLE else ActionSupport.UNAVAILABLE,
                    storageActionDetail(item, candidate).support)
        }
        FixtureDocumentsProvider.capabilityFlags = mapOf(fixture.canonicalPath to Document.FLAG_SUPPORTS_WRITE)
        val folder = docs.read(root)
        assertFalse(folder.writable)
        assertEquals(ActionSupport.UNAVAILABLE, storageActionDetail(facts(folder), StorageAction.CREATE).support)
    }

    @Test fun removeFromParentFlagDoesNotAuthorizeDeletingTheOriginal() {
        FixtureDocumentsProvider.capabilityFlags = mapOf(note.canonicalPath to Document.FLAG_SUPPORTS_REMOVE)
        val entry = docs.read(path)
        assertFalse(entry.canDelete)
        assertEquals(ActionSupport.UNAVAILABLE, storageActionDetail(facts(entry), StorageAction.TRASH).support)
    }

    @Test fun cachedReadOnlyGrantOverridesTheProvidersMutationFlags() {
        // A controlled cached-grant input, not a claim of a real Android permission grant.
        val saved = DocumentAccess::class.java.getDeclaredField("savedGrants").apply { isAccessible = true }
        saved.set(docs, mapOf(documentTreeKey(path)!! to (true to false)))
        val entry = docs.read(path)
        assertEquals(true, entry.providerAccess!!.savedReadGrant)
        assertEquals(false, entry.providerAccess!!.savedWriteGrant)
        assertFalse(entry.writable)
        assertFalse(entry.canRename)
        assertFalse(entry.canDelete)
        assertEquals(ActionSupport.AVAILABLE, storageActionDetail(facts(entry), StorageAction.READ).support)
        assertTrue(storageActionDetail(facts(entry), StorageAction.RENAME).reason.contains("read only"))
        docs.refreshGrantState()
        assertNull(docs.read(path).providerAccess!!.savedWriteGrant)
    }

    @Test fun cachedCapabilityExplanationDoesNotQueryTheProviderAgain() {
        val item = facts(docs.read(path))
        val before = FixtureDocumentsProvider.metadataQueries[note.canonicalPath]!!.get()
        repeat(100) { StorageAction.entries.forEach { storageActionDetail(item, it) } }
        assertEquals(before, FixtureDocumentsProvider.metadataQueries[note.canonicalPath]!!.get())
    }
}
