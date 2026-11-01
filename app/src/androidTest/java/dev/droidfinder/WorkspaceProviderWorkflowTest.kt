package dev.droidfinder

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

class WorkspaceProviderWorkflowTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val app
        get() = context.applicationContext as FinderApplication

    private val resolver
        get() = context.contentResolver

    private val access
        get() = app.workspaceDocuments

    private lateinit var folder: File
    private var enabled = true

    private fun uri(id: String) = DocumentsContract.buildDocumentUri(access.authority, id)

    private fun children(id: String) =
        DocumentsContract.buildChildDocumentsUri(access.authority, id)

    @Before
    fun setup() {
        enabled = access.enabled
        access.setEnabled(true)
        folder = File(access.root, "Interop-${System.nanoTime()}").apply { mkdirs() }
        File(folder, "Notes.txt").writeText("Workspace provider fixture")
    }

    @After
    fun cleanup() {
        WorkspaceMutationGate.endApp()
        access.ids.forget(listOf(folder.path))
        app.localEngine
            .trashItems()
            .filter { app.localEngine.originalPath(it)?.contains(folder.name) == true }
            .forEach(app.localEngine::deleteForever)
        folder.deleteRecursively()
        access.setEnabled(enabled)
    }

    @Test
    fun rootAndChildMetadataAreReadable() {
        resolver
            .query(DocumentsContract.buildRootsUri(access.authority), null, null, null, null)!!
            .use { assertEquals(1, it.count) }
        resolver
            .query(
                children(WorkspaceDocumentIds.ROOT),
                arrayOf(Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )!!
            .use { c ->
                val names = buildList { while (c.moveToNext()) add(c.getString(0)) }
                assertTrue("Root children: $names", folder.name in names)
            }
        val id = access.ids.id(folder)
        resolver.acquireContentProviderClient(access.authority)!!.use { client ->
            val provider = client.localContentProvider as WorkspaceDocumentsProvider
            val full = provider.findDocumentPath(null, id)
            assertEquals(WorkspaceDocumentIds.ROOT, full.rootId)
            assertEquals(listOf(WorkspaceDocumentIds.ROOT, id), full.path)
        }
        resolver.query(children(id), null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(
                "Notes.txt",
                c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)),
            )
        }
    }

    private fun read(uri: Uri) =
        resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    private fun failing(work: () -> Unit) {
        assertTrue("Expected request to fail", runCatching(work).isFailure)
    }

    @Test
    fun createWriteRenameMoveCopyAndRecoverableDeleteUseStableIds() {
        val parent = uri(access.ids.id(folder))
        val created =
            DocumentsContract.createDocument(resolver, parent, "text/plain", "Created.txt")!!
        resolver.openOutputStream(created, "wt")!!.use {
            it.write("cross-app editing".toByteArray())
        }
        assertEquals("cross-app editing", read(created))
        val id = DocumentsContract.getDocumentId(created)
        val renamed = DocumentsContract.renameDocument(resolver, created, "Renamed.txt")!!
        assertEquals(id, DocumentsContract.getDocumentId(renamed))
        assertEquals("cross-app editing", read(created))
        val sub =
            DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, "Moved")!!
        val moved = DocumentsContract.moveDocument(resolver, renamed, parent, sub)!!
        assertEquals(id, DocumentsContract.getDocumentId(moved))
        assertEquals("cross-app editing", read(created))
        val copied = DocumentsContract.copyDocument(resolver, moved, parent)!!
        assertNotEquals(id, DocumentsContract.getDocumentId(copied))
        assertEquals("cross-app editing", read(copied))
        assertTrue(DocumentsContract.deleteDocument(resolver, moved))
        failing { read(created) }
        assertTrue(
            app.localEngine.trashItems().any {
                app.localEngine.originalPath(it)?.endsWith("${folder.name}/Moved/Renamed.txt") ==
                    true
            },
        )
        assertEquals("cross-app editing", read(copied))
    }

    @Test
    fun delayedUiRefreshDoesNotRetargetCopiesAtVacatedPaths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { app.model }
        val parent = uri(access.ids.id(folder))
        val source = uri(access.ids.id(File(folder, "Notes.txt")))
        val sub =
            DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, "Moved")!!
        instrumentation.waitForIdleSync()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        lateinit var moved: Uri
        lateinit var copied: Uri
        try {
            moved = DocumentsContract.moveDocument(resolver, source, parent, sub)!!
            copied = DocumentsContract.copyDocument(resolver, moved, parent)!!
        } finally {
            release.countDown()
        }
        instrumentation.waitForIdleSync()
        assertEquals(
            DocumentsContract.getDocumentId(source),
            DocumentsContract.getDocumentId(moved),
        )
        assertNotEquals(
            DocumentsContract.getDocumentId(moved),
            DocumentsContract.getDocumentId(copied),
        )
        assertEquals("Workspace provider fixture", read(source))
        assertEquals("Workspace provider fixture", read(copied))
        assertTrue(DocumentsContract.deleteDocument(resolver, moved))
        failing { read(source) }
        assertEquals("Workspace provider fixture", read(copied))
    }

    @Test
    fun reservedFilesSymlinksUnknownIdsAndRootMutationsAreRejected() {
        val hidden = File(folder, ".droidfinder-working").apply { writeText("not published") }
        val outside =
            File(context.filesDir, "Outside-${System.nanoTime()}").apply {
                writeText("private state")
            }
        val link = File(folder, "Alias")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            failing { access.ids.id(hidden) }
            failing { access.ids.id(link) }
            failing { access.ids.id(outside) }
            failing { read(uri("../${outside.name}")) }
            failing {
                DocumentsContract.renameDocument(resolver, uri(WorkspaceDocumentIds.ROOT), "Other")
            }
            failing { DocumentsContract.deleteDocument(resolver, uri(WorkspaceDocumentIds.ROOT)) }
            resolver
                .query(
                    children(access.ids.id(folder)),
                    arrayOf(Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )!!
                .use { c ->
                    val names = buildList { while (c.moveToNext()) add(c.getString(0)) }
                    assertEquals(listOf("Notes.txt"), names)
                }
            assertEquals("private state", outside.readText())
        } finally {
            outside.delete()
            link.delete()
        }
    }

    @Test
    fun writeCallsWaitForAppOperationsButExistingDocumentsRemainReadable() {
        val id = access.ids.id(File(folder, "Notes.txt"))
        assertTrue(WorkspaceMutationGate.beginApp())
        try {
            assertEquals("Workspace provider fixture", read(uri(id)))
            resolver.query(uri(id), arrayOf(Document.COLUMN_FLAGS), null, null, null)!!.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(
                    0,
                    c.getInt(0) and
                        (Document.FLAG_SUPPORTS_WRITE or
                            Document.FLAG_SUPPORTS_DELETE or
                            Document.FLAG_SUPPORTS_RENAME),
                )
            }
            failing { resolver.openFileDescriptor(uri(id), "wt")!!.close() }
            failing {
                DocumentsContract.createDocument(
                    resolver,
                    uri(access.ids.id(folder)),
                    "text/plain",
                    "Blocked.txt",
                )
            }
            failing { DocumentsContract.renameDocument(resolver, uri(id), "Blocked.txt") }
        } finally {
            WorkspaceMutationGate.endApp()
        }
        assertEquals("Workspace provider fixture", read(uri(id)))
        assertNotNull(DocumentsContract.renameDocument(resolver, uri(id), "After.txt"))
    }

    @Test
    fun optOutBlocksNewRequestsWithoutClosingAlreadyGrantedDescriptors() {
        val source = uri(access.ids.id(File(folder, "Notes.txt")))
        val input = resolver.openInputStream(source)!!
        access.setEnabled(false)
        try {
            resolver
                .query(DocumentsContract.buildRootsUri(access.authority), null, null, null, null)!!
                .use { assertEquals(0, it.count) }
            failing { resolver.openInputStream(source)!!.close() }
            assertEquals("Workspace provider fixture", input.bufferedReader().readText())
        } finally {
            input.close()
            access.setEnabled(true)
        }
        assertEquals("Workspace provider fixture", read(source))
    }

    @Test
    fun replacementCannotRetargetOldUriOrTruncateAnotherDocument() {
        val original = File(folder, "Notes.txt")
        val id = access.ids.id(original)
        val backup = File(folder, "Original.txt")
        java.nio.file.Files.move(original.toPath(), backup.toPath())
        original.writeText("new document")
        failing { resolver.openFileDescriptor(uri(id), "wt")!!.close() }
        assertEquals("new document", original.readText())
        assertNotEquals(id, access.ids.id(original))
        val valid = access.ids.id(backup)
        android.os.ParcelFileDescriptor.open(
                original,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            .use { fd -> failing { access.ids.verifyDescriptor(valid, fd) } }
    }

    @Test
    fun idsSurviveTheNamespaceWindowOfAnAppRenameAndDatabaseReopen() {
        val source = File(folder, "Notes.txt")
        val id = access.ids.id(source)
        val target = File(folder, "After.txt")
        assertTrue(WorkspaceMutationGate.beginApp())
        try {
            java.nio.file.Files.move(source.toPath(), target.toPath())
            failing { access.ids.file(id) }
            source.writeText("replacement at old path")
            failing { access.ids.id(source) }
            access.ids.relocate(listOf(source.path to target.path))
        } finally {
            WorkspaceMutationGate.endApp()
        }
        assertEquals("Workspace provider fixture", read(uri(id)))
        WorkspaceDocumentIds(context, access.root).use { reopened ->
            assertEquals(id, reopened.id(target))
            assertEquals(target.canonicalPath, reopened.file(id).path)
        }
        assertEquals("replacement at old path", source.readText())
    }
}
