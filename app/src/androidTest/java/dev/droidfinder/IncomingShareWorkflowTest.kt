package dev.droidfinder

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.droidfinder.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

class IncomingShareWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as FinderApplication

    private lateinit var source: File
    private lateinit var destination: File
    private var scenario: ActivityScenario<ReceiveFilesActivity>? = null
    private lateinit var model: IncomingShareModel
    private var previousFolder: String? = null
    private var connected: String? = null

    @Before
    fun setup() {
        val suffix = System.nanoTime()
        source = File(app.filesDir, "ProviderFixtures/Incoming-$suffix").apply { mkdirs() }
        destination = File(app.filesDir, "Workspace/Incoming-test-$suffix").apply { mkdirs() }
        File(source, "Notes.txt").writeText("Shared document with Unicode: 日本語\n")
        val prefs = app.getSharedPreferences("incoming-share", 0)
        previousFolder = prefs.getString("folder", null)
        prefs.edit().putString("folder", destination.path).commit()
    }

    private fun uri(name: String = "Notes.txt") =
        DocumentsContract.buildDocumentUri(
            "dev.droidfinder.fixtures",
            "root/${source.name}/$name",
        )

    private fun launch(vararg names: String) {
        val uris = names.map(::uri)
        val intent =
            Intent(app, ReceiveFilesActivity::class.java).apply {
                action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                type = "*/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData =
                    ClipData.newRawUri("Incoming test", uris.first()).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
            }
        scenario = ActivityScenario.launch(intent)
        scenario!!.onActivity { model = it.incoming }
        compose.waitUntil(10_000) { !model.metadataLoading && !model.folder.loading }
        compose.onNodeWithTag("incoming-share").assertExists()
    }

    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    private fun saved() {
        compose.waitUntil(10_000) { model.result != null && model.explorer.busy == null }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        FixtureDocumentsProvider.transferGate?.countDown()
        if (::model.isInitialized) {
            main { model.stop() }
            compose.waitUntil(10_000) { model.explorer.busy == null }
            connected?.let { path -> main { model.explorer.disconnectLocation(path) } }
        }
        scenario?.close()
        FixtureDocumentsProvider.transferGate = null
        FixtureDocumentsProvider.gateOpened = null
        app.workspaceDocuments.ids.forget(listOf(destination.path))
        source.deleteRecursively()
        destination.deleteRecursively()
        app.getSharedPreferences("incoming-share", 0)
            .edit()
            .putString("folder", previousFolder)
            .commit()
    }

    @Test
    fun payloadDeduplicatesClipDataAndKeepsOptionalText() {
        val a = uri()
        val b = uri("Second.txt")
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b, a))
                clipData = ClipData.newRawUri("Files", a).apply { addItem(ClipData.Item(b)) }
                putExtra(Intent.EXTRA_TEXT, "Optional caption")
            }
        assertEquals(listOf(a, b), incomingPayload(intent).uris)
        assertEquals("Optional caption", incomingPayload(intent).text)
        assertNotNull(incomingPayload(Intent(Intent.ACTION_SEND)).error)
        assertNotNull(
            incomingPayload(
                    Intent(Intent.ACTION_SEND)
                        .putExtra(
                            Intent.EXTRA_TEXT,
                            "x".repeat(2 * 1024 * 1024 + 1),
                        ),
                )
                .error,
        )
    }

    @Test
    fun savesKeepBothAndSupportsUndoWithoutChangingSource() {
        File(destination, "Notes.txt").writeText("Existing file")
        launch("Notes.txt")
        compose.onNodeWithText("Save 1 file").performClick()
        saved()
        val result = model.result!!
        assertEquals(1, result.saved.size)
        assertTrue(result.errors.isEmpty())
        assertEquals("Existing file", File(destination, "Notes.txt").readText())
        val copy = File(result.saved.single())
        assertNotEquals("Notes.txt", copy.name)
        assertArrayEquals(File(source, "Notes.txt").readBytes(), copy.readBytes())
        main { model.explorer.undo() }
        compose.waitUntil(10_000) { model.explorer.busy == null && !copy.exists() }
        assertTrue(File(source, "Notes.txt").exists())
        assertEquals("Existing file", File(destination, "Notes.txt").readText())
    }

    @Test
    fun failedMemberKeepsSuccessfulFilesAndRetrySelectsOnlyFailures() {
        File(source, "Vanishing.txt").writeText("Disappears after metadata")
        launch("Notes.txt", "Vanishing.txt")
        File(source, "Vanishing.txt").delete()
        compose.onNodeWithText("Save 2 files").performClick()
        saved()
        assertEquals(1, model.result!!.saved.size)
        assertEquals(listOf(1), model.result!!.errors.map { it.first })
        assertArrayEquals(
            File(source, "Notes.txt").readBytes(),
            File(destination, "Notes.txt").readBytes(),
        )
        assertFalse(destination.listFiles()!!.any { it.name.startsWith(".droidfinder-") })
        compose.onNodeWithText("Select failed files").performClick()
        assertEquals(listOf(1), model.selected.map { it.id })
        File(source, "Vanishing.txt").writeText("Restored")
        compose.onNodeWithText("Save 1 file").performClick()
        saved()
        assertTrue(model.result!!.errors.isEmpty())
        assertEquals("Restored", File(destination, "Vanishing.txt").readText())
        assertEquals(2, destination.listFiles()!!.size)
    }

    @Test
    fun savesToAnAuthorizedProviderAndRespectsReadOnlyDestination() {
        val output = File(source, "Target").apply { mkdir() }
        File(output, "readonly").mkdir()
        launch("Notes.txt")
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${source.name}/Target",
            )
        // The fixture shares our UID; it has no real DocumentsUI result to persist.
        // The separate-UID client covers actual picker grants.
        val location = model.explorer.documents.connect(tree, persist = false)
        connected = location.uri
        main {
            model.explorer.session.connectedLocations.value = model.explorer.documents.locations
            model.browse(location.uri)
        }
        compose.waitUntil(10_000) { !model.folder.loading }
        main { model.browse(model.folder.children.single { it.name == "readonly" }.path) }
        compose.waitUntil(10_000) { !model.folder.loading }
        assertFalse(model.folder.writable)
        main { model.save() }
        assertFalse(model.saving)
        assertNull(model.result)
        main { model.browse(connected!!) }
        compose.waitUntil(10_000) { !model.folder.loading }
        compose.onNodeWithText("Save 1 file").performClick()
        saved()
        assertTrue(model.result!!.errors.isEmpty())
        assertArrayEquals(
            File(source, "Notes.txt").readBytes(),
            File(output, "Notes.txt").readBytes(),
        )
        assertFalse(output.listFiles()!!.any { it.name.startsWith(".droidfinder-") })
        main { model.explorer.undo() }
        compose.waitUntil(10_000) {
            model.explorer.busy == null && !File(output, "Notes.txt").exists()
        }
    }

    @Test
    fun blockedStreamCanStopBeforeTheSourceWriterResumes() {
        File(source, "Gate.bin").writeBytes(ByteArray(256 * 1024) { 17 })
        val gate = CountDownLatch(1)
        val opened = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = opened
        launch("Gate.bin")
        main { model.save() }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        main { model.stop() }
        saved()
        assertEquals(1L, gate.count)
        assertTrue(model.result!!.cancelled)
        assertTrue(model.result!!.saved.isEmpty())
        assertEquals(1, model.result!!.errors.size)
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test
    fun activityRecreationKeepsEditsAndNeverRepeatsCompletedSave() {
        launch("Notes.txt")
        main { model.rename(0, "Renamed.txt") }
        scenario!!.recreate()
        scenario!!.onActivity { model = it.incoming }
        compose.onNodeWithText("Renamed.txt").assertExists()
        compose.onNodeWithText("Save 1 file").performClick()
        saved()
        scenario!!.recreate()
        scenario!!.onActivity { model = it.incoming }
        compose.onNodeWithText("1 saved · 0 not saved").assertExists()
        assertEquals(listOf("Renamed.txt"), destination.listFiles()!!.map { it.name })
    }

    @Test
    fun restoredDraftRequiresExplicitSaveAndRejectsOwnProviderInput() {
        val restored =
            Bundle().apply {
                putString("incomingFolder", destination.path)
                putBoolean("saveWasRunning", true)
                putIntegerArrayList("incomingSelected", arrayListOf(0))
                putStringArrayList("incomingNames", arrayListOf("Reviewed.txt"))
            }
        lateinit var draft: IncomingShareModel
        main {
            draft = IncomingShareModel(app)
            draft.initialize(
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri()),
                restored,
            )
        }
        compose.waitUntil(10_000) { !draft.metadataLoading && !draft.folder.loading }
        assertTrue(draft.interrupted)
        assertEquals("Reviewed.txt", draft.items.single().name)
        assertFalse(draft.saving)
        assertTrue(destination.listFiles()!!.isEmpty())
        lateinit var own: IncomingShareModel
        main {
            own = IncomingShareModel(app)
            own.initialize(
                Intent(Intent.ACTION_SEND)
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        DocumentsContract.buildDocumentUri(
                            "dev.droidfinder.documents",
                            "workspace",
                        ),
                    ),
                null,
            )
        }
        assertTrue(own.selected.isEmpty())
        assertTrue(own.items.single().error!!.contains("already managed"))
    }
}
