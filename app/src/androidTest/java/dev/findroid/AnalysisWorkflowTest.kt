package dev.findroid

import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

class AnalysisWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var folder: File
    private lateinit var provider: File
    private var connected: String? = null
    private var restoreOptions: (() -> Unit)? = null

    @Before
    fun setup() {
        val id = System.nanoTime()
        folder = File(compose.activity.filesDir, "Workspace/Analysis-$id").apply { mkdirs() }
        File(folder, "Vector.txt").writeText("abc")
        File(folder, "Copy.txt").writeText("abc")
        File(folder, "Different.txt").writeText("xyz")
        File(folder, "Empty.txt").createNewFile()
        File(folder, "Archive").mkdir()
        File(folder, "Archive/Old note.md").apply {
            writeText("Old note")
            setLastModified(1_000_000)
        }
        RandomAccessFile(File(folder, "Large.bin"), "rw").use { it.setLength(12L * 1024 * 1024) }
        provider =
            File(compose.activity.filesDir, "ProviderFixtures/Analysis-$id").apply { mkdirs() }
        File(provider, "Unknown-size.bin").writeText("unknown sized content")
        File(provider, "Vector.txt").writeText("abc")
        File(provider, "readonly").mkdir()
        File(provider, "readonly/Keep.txt").writeText("abc")
        compose.runOnIdle {
            val state = model.analysis
            val limits = state.limits
            val hidden = state.includeHidden
            val large = state.largeBytes
            val days = state.oldDays
            restoreOptions = { state.configure(limits, hidden, large, days) }
            model.analysis.close()
            model.checksums.close()
        }
    }

    private fun opened() {
        compose.runOnIdle { model.analysis.open(folder.path) }
        scanned()
    }

    private fun scanned() {
        compose.waitUntil(10_000) { !model.analysis.running && model.analysis.report != null }
        compose.waitForIdle()
    }

    private fun dupes() {
        compose.runOnIdle { model.analysis.checkDuplicates() }
        compose.waitUntil(10_000) { !model.analysis.running && model.analysis.duplicates != null }
    }

    private fun connected() {
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${provider.name}",
            )
        val location = model.documents.connect(tree, persist = false)
        connected = location.uri
        compose.runOnIdle {
            model.session.connectedLocations.value = model.documents.locations
            model.analysis.open(location.uri)
        }
        scanned()
    }

    private fun providerUri(name: String) =
        DocumentsContract.buildDocumentUriUsingTree(
                android.net.Uri.parse(connected!!),
                "root/${provider.name}/$name",
            )
            .toString()

    @After
    fun cleanup() {
        FixtureDocumentsProvider.transferGate?.countDown()
        FixtureDocumentsProvider.listingGate?.countDown()
        compose.runOnIdle {
            model.analysis.stop()
            model.checksums.stop()
        }
        compose.waitUntil(10_000) { model.busy == null }
        compose.runOnIdle {
            model.analysis.close()
            model.checksums.close()
            restoreOptions?.invoke()
            connected?.let(model::disconnectLocation)
        }
        FixtureDocumentsProvider.transferGate = null
        FixtureDocumentsProvider.gateOpened = null
        FixtureDocumentsProvider.listingDirectory = null
        FixtureDocumentsProvider.listingGate = null
        FixtureDocumentsProvider.listingGateOpened = null
        model.engine
            .trashItems()
            .filter { model.engine.originalPath(it)?.contains(folder.name) == true }
            .forEach(model.engine::deleteForever)
        (compose.activity.application as FinderApplication)
            .workspaceDocuments
            .ids
            .forget(listOf(folder.path))
        folder.deleteRecursively()
        provider.deleteRecursively()
    }

    @Test
    fun overviewFiltersChecksumComparisonSidecarAndUndoWork() {
        opened()
        assertEquals(6, model.analysis.report!!.files.size)
        compose.onNodeWithText("BY FOLDER").assertIsDisplayed()
        compose.onNodeWithText("Large files").performClick()
        compose.onNodeWithText("10 MB+").performClick()
        compose.onNodeWithText("Large.bin").assertIsDisplayed()
        compose.onNodeWithText("Old files").performClick()
        compose.onNodeWithText("Old note.md").assertIsDisplayed()
        compose.onNodeWithText("Empty files").performClick()
        compose.onNodeWithText("Empty.txt").assertIsDisplayed()
        compose.runOnIdle {
            model.analysis.close()
            model.checksums.open(File(folder, "Vector.txt").path)
        }
        compose.waitUntil(10_000) { model.checksums.result != null && !model.checksums.running }
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hex, model.checksums.result!!.hex)
        compose.onNodeWithTag("checksum-expected").performTextInput(hex)
        compose.onNodeWithText("Checksum matches").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("checksum-expected").performTextClearance()
        compose.onNodeWithTag("checksum-expected").performTextInput("0".repeat(64))
        compose.onNodeWithText("Checksum does not match").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save checksum file").performClick()
        compose.onNodeWithText("Save").performClick()
        val sidecar = File(folder, "Vector.txt.sha256")
        compose.waitUntil(10_000) { sidecar.exists() && model.busy == null }
        assertEquals("$hex  Vector.txt\n", sidecar.readText())
        compose.runOnIdle {
            model.checksums.close()
            model.undo()
        }
        compose.waitUntil(10_000) { model.busy == null && !sidecar.exists() }
        assertEquals("abc", File(folder, "Vector.txt").readText())
    }

    @Test
    fun duplicatesNeedReviewKeepOneAndCleanupCanBeUndone() {
        opened()
        dupes()
        assertEquals(1, model.analysis.duplicates!!.groups.size)
        compose.onNodeWithText("Select extra copies").performClick()
        val chosen = model.analysis.selected
        assertEquals(1, chosen.size)
        compose.onNodeWithText("Review cleanup").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(chosen.all { File(it).exists() })
        compose.onNodeWithText("Review cleanup").performClick()
        compose.onNodeWithText("Move to Trash").performClick()
        compose.waitUntil(10_000) { model.busy == null && chosen.none { File(it).exists() } }
        assertEquals(1, listOf("Vector.txt", "Copy.txt").count { File(folder, it).exists() })
        compose.runOnIdle {
            model.analysis.close()
            model.undo()
        }
        compose.waitUntil(10_000) { model.busy == null && chosen.all { File(it).exists() } }
        compose.runOnIdle { model.analysis.open(folder.path) }
        scanned()
        assertEquals(6, model.analysis.report!!.files.size)
    }

    @Test
    fun changedKeeperAndSelectingEveryCopyCannotRemoveTheLastVerifiedFile() {
        opened()
        dupes()
        val report = model.analysis.report!!
        val duplicates = model.analysis.duplicates!!
        val group = duplicates.groups.single()
        val kept = group.files.first()
        val removed = group.files.last()
        File(kept.node.path).writeText("changed keeper")
        compose.runOnIdle { model.trashAnalyzed(report, setOf(removed.node.path), duplicates) }
        compose.waitUntil(10_000) { model.busy == null }
        assertTrue(group.files.all { File(it.node.path).exists() })
        compose.runOnIdle {
            model.trashAnalyzed(report, group.files.map { it.node.path }.toSet(), duplicates)
        }
        compose.waitUntil(10_000) { model.busy == null }
        assertTrue(group.files.all { File(it.node.path).exists() })
        assertTrue(model.analysis.error!!.contains("skipped"))
    }

    @Test
    fun providerUnknownSizeAndReadOnlyFilesRemainExplicitAndChecksumStillReadsFullContent() {
        connected()
        val report = model.analysis.report!!
        assertEquals(1, report.unknown)
        assertFalse(report.files.first { it.node.name == "Keep.txt" }.node.canDelete)
        compose.runOnIdle {
            model.analysis.close()
            model.checksums.open(providerUri("Unknown-size.bin"))
        }
        compose.waitUntil(10_000) { !model.checksums.running }
        assertEquals(
            "unknown sized content".toByteArray().size.toLong(),
            model.checksums.result!!.bytes,
        )
        val expected =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("unknown sized content".toByteArray())
                .joinToString("") { "%02x".format(it) }
        assertEquals(expected, model.checksums.result!!.hex)
        compose.runOnIdle {
            model.checksums.close()
            model.analysis.open(connected!!)
        }
        scanned()
        dupes()
        val changing = File(provider, "Unknown-size.bin")
        val unchangedTimestamp = changing.lastModified()
        changing.writeText("abc")
        assertTrue(changing.setLastModified(unchangedTimestamp))
        dupes()
        assertEquals(3, model.analysis.duplicates!!.groups.single().files.size)
        assertEquals(
            3L,
            model.analysis.report!!.files.first { it.node.name == "Unknown-size.bin" }.node.bytes,
        )
    }

    @Test
    fun stoppedChecksumDoesNotWaitForABlockedProviderWriterOrPublishAPartialHash() {
        File(provider, "Gate.bin").writeBytes(ByteArray(128 * 1024) { 7 })
        connected()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = started
        compose.runOnIdle {
            model.analysis.close()
            model.checksums.open(providerUri("Gate.bin"))
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { model.checksums.stop() }
        compose.waitUntil(5_000) { !model.checksums.running }
        assertNull(model.checksums.result)
        assertEquals(1L, gate.count)
        assertTrue(model.checksums.error!!.contains("stopped"))
    }

    @Test
    fun stoppedScanRetainsPartialRowsAndLateOldProviderResultsCannotReplaceANewScope() {
        repeat(100) { File(provider, "Entry-$it.txt").writeText("entry $it") }
        connected()
        FixtureDocumentsProvider.listingDirectory = provider.canonicalPath
        FixtureDocumentsProvider.listingGate = CountDownLatch(1)
        FixtureDocumentsProvider.listingGateOpened = CountDownLatch(1)
        compose.runOnIdle { model.analysis.refresh() }
        assertTrue(FixtureDocumentsProvider.listingGateOpened!!.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { model.analysis.stop() }
        FixtureDocumentsProvider.listingGate!!.countDown()
        scanned()
        assertTrue(model.analysis.report!!.stopped)
        assertTrue(model.analysis.report!!.files.isNotEmpty())
        FixtureDocumentsProvider.listingGate = CountDownLatch(1)
        FixtureDocumentsProvider.listingGateOpened = CountDownLatch(1)
        compose.runOnIdle { model.analysis.refresh() }
        assertTrue(FixtureDocumentsProvider.listingGateOpened!!.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { model.analysis.open(folder.path) }
        scanned()
        FixtureDocumentsProvider.listingGate!!.countDown()
        compose.waitForIdle()
        assertEquals(folder.path, model.analysis.report!!.root.path)
        assertEquals(6, model.analysis.report!!.files.size)
    }

    @Test
    fun recreationRetainsReviewedResultsAndSelectionWithoutStartingCleanup() {
        opened()
        dupes()
        compose.runOnIdle { model.analysis.selectExtras() }
        val selected = model.analysis.selected
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("file-analysis").assertExists()
        assertEquals(selected, model.analysis.selected)
        assertTrue(selected.all { File(it).exists() })
        assertNull(model.busy)
    }

    @Test
    fun unknownProviderSizesMatchKnownFilesAndReadOnlyCopyIsKeptDuringCleanup() {
        File(provider, "Unknown-size.bin").writeText("abc")
        connected()
        dupes()
        assertEquals(3, model.analysis.duplicates!!.groups.single().files.size)
        assertEquals(0, model.analysis.report!!.unknown)
        compose.runOnIdle { model.analysis.selectExtras() }
        assertEquals(2, model.analysis.selected.size)
        assertTrue(model.analysis.selected.none { it.contains("Keep.txt") })
        val selected = model.analysis.selected
        compose.runOnIdle {
            model.trashAnalyzed(model.analysis.report!!, selected, model.analysis.duplicates)
        }
        compose.waitUntil(10_000) {
            model.busy == null &&
                !File(provider, "Vector.txt").exists() &&
                !File(provider, "Unknown-size.bin").exists()
        }
        assertEquals("abc", File(provider, "readonly/Keep.txt").readText())
        compose.runOnIdle {
            model.analysis.close()
            model.undo()
        }
        compose.waitUntil(10_000) {
            model.busy == null &&
                File(provider, "Vector.txt").exists() &&
                File(provider, "Unknown-size.bin").exists()
        }
    }

    @Test
    fun backgroundingStopsReadJobsAndDoesNotAutomaticallyRestartThem() {
        File(provider, "Gate.bin").writeBytes(ByteArray(128 * 1024) { 7 })
        connected()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = started
        compose.runOnIdle {
            model.analysis.close()
            model.checksums.open(providerUri("Gate.bin"))
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val checksum = model.checksums
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { !checksum.running }
        assertNull(checksum.result)
        assertEquals(1L, gate.count)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("file-checksum").assertExists()
        assertFalse(checksum.running)
        assertNull(checksum.result)
    }
}
