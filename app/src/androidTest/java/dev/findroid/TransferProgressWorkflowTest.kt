package dev.findroid

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransferProgressWorkflowTest {
    @org.junit.Before
    fun resetOwnedUndoSessionForFixture() {
        compose.runOnIdle { resetOwnedUndoFixture(compose.activity.model) }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model

    private fun capture(name: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /sdcard/findroid-progress-$name.png",
            ),
        ).use { it.readBytes() }
    }

    private fun exercise(cancel: Boolean) {
        val key = "Progress-${System.nanoTime()}"
        val sourceFolder = File(compose.activity.filesDir, "ProviderFixtures/$key").apply { mkdirs() }
        val sourceBytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val source = File(sourceFolder, "Gate.bin").apply { writeBytes(sourceBytes) }
        val destination = File(compose.activity.filesDir, "Workspace/$key").apply { mkdirs() }
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/$key")
        val path = DocumentsContract.buildDocumentUriUsingTree(tree, "root/$key/Gate.bin").toString()
        FixtureDocumentsProvider.transferGate = CountDownLatch(1)
        FixtureDocumentsProvider.gateOpened = CountDownLatch(1)
        FixtureDocumentsProvider.readDelayMillis = mapOf(source.canonicalPath to 200L)
        var jobId: String? = null
        try {
            compose.runOnIdle {
                if (model.dualPane) model.toggleDualPane()
                model.connectLocation(tree, false)
                model.navigate(destination.path)
            }
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.onNodeWithText("Transfers").performClick()
            compose.runOnIdle {
                model.transferPaths(listOf(path), destination.path, false)
            }
            assertTrue(FixtureDocumentsProvider.gateOpened!!.await(8, TimeUnit.SECONDS))
            compose.waitUntil(8000) {
                model.transferProgress.waitingMessage() == "Waiting for the storage provider"
            }
            jobId = model.activeTransferId!!
            assertEquals(TransferStage.COPYING, model.transferProgress.stage)
            assertEquals(0L, model.transferProgress.bytes)
            assertNull(model.transferProgress.secondsRemaining)
            val waitingInPanel = hasText("Waiting for the storage provider", substring = true) and
                hasAnyAncestor(hasTestTag("transfer-center"))
            // The display clock can be one tick behind the freshly sampled model.
            compose.waitUntil(5000) {
                compose.onAllNodes(waitingInPanel).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(waitingInPanel).assertExists()
            assertArrayEquals(sourceBytes, source.readBytes())
            if (cancel) {
                compose.onNode(
                    hasContentDescription("Cancel transfer") and
                        hasAnyAncestor(hasTestTag("transfer-center")),
                ).performClick()
                compose.waitUntil(15_000) { model.busy == null }
                assertEquals(TransferState.CANCELLED, model.transferJobs.first { it.id == jobId }.state)
                assertTrue(destination.listFiles().isNullOrEmpty())
                assertArrayEquals(sourceBytes, source.readBytes())
                assertNull(TransferRuntime.label)
                val stopped = TransferRuntime.progress.updatedAtMillis
                Thread.sleep(1200)
                assertEquals("The completed transfer must stop its sampler", stopped, TransferRuntime.progress.updatedAtMillis)
            } else {
                capture("provider-wait")
                FixtureDocumentsProvider.transferGate!!.countDown()
                compose.waitUntil(12_000) {
                    model.transferProgress.stage == TransferStage.COPYING &&
                        model.transferProgress.bytesPerSecond != null &&
                        model.transferProgress.fraction?.let { it > .1f && it < .8f } == true
                }
                compose.onNode(
                    hasText("left in this phase", substring = true) and
                        hasAnyAncestor(hasTestTag("transfer-center")),
                ).assertExists()
                capture("recent-rate")
                compose.runOnIdle { model.pauseTransfer(jobId!!) }
                compose.waitUntil(5000) { model.activeTransfer?.state == TransferState.PAUSED }
                compose.onAllNodesWithText("left in this phase", substring = true).assertCountEquals(0)
                capture("paused")
                compose.onNode(hasText("Resume") and hasAnyAncestor(hasTestTag("transfer-center"))).performClick()
                compose.waitUntil(30_000) { model.busy == null }
                val job = model.transferJobs.first { it.id == jobId }
                assertEquals(TransferState.COMPLETED, job.state)
                assertEquals(sourceBytes.size.toLong(), job.bytes)
                assertArrayEquals(sourceBytes, File(destination, source.name).readBytes())
                assertArrayEquals(sourceBytes, source.readBytes())
            }
            compose.onNodeWithContentDescription("Close transfers").performClick()
        } finally {
            FixtureDocumentsProvider.transferGate?.countDown()
            FixtureDocumentsProvider.transferGate = null
            FixtureDocumentsProvider.gateOpened = null
            FixtureDocumentsProvider.readDelayMillis = emptyMap()
            compose.runOnIdle {
                jobId?.let { model.cancelQueuedTransfer(it) }
                if (model.busy != null) model.cancelTransfer()
            }
            compose.waitUntil(15_000) { model.busy == null }
            compose.runOnIdle {
                model.disconnectLocation(tree.toString())
                model.navigate(model.workspace.path)
            }
            sourceFolder.deleteRecursively()
            destination.deleteRecursively()
        }
    }

    @Test fun providerWaitExplainsTheDelayAndARecoveredCopyShowsRecentRate() = exercise(false)

    @Test fun cancellingAWaitingProviderKeepsOriginalsAndStopsTheSampler() = exercise(true)
}
