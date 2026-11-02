package dev.findroid

import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransferQueueWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun singlePaneLayout() {
        compose.runOnIdle {
            val root = compose.activity.model
            if (root.dualPane) root.toggleDualPane()
        }
    }

    private val model
        get() = compose.activity.model

    @Test
    fun pausedCopyKeepsUiResponsiveAndQueuedJobsCanReorderCancelAndRun() {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(
                    compose.activity.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
        compose.runOnIdle { model.updateNotificationState() }
        val fixture =
            File(compose.activity.filesDir, "ProviderFixtures/Queue-${System.nanoTime()}").apply {
                mkdirs()
            }
        val target =
            File(compose.activity.filesDir, "Workspace/Queue-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(fixture, "Gate.bin").writeBytes(ByteArray(3 * 1024 * 1024) { 42 })
        val first = File(target, "First.txt").apply { writeText("first") }
        val second = File(target, "Second.txt").apply { writeText("second") }
        val out = File(target, "Out").apply { mkdir() }
        val gate = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = CountDownLatch(1)
        val root =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${fixture.name}",
            )
        try {
            compose.runOnIdle {
                model.connectLocation(root, false)
                model.navigate(target.path)
            }
            compose.waitUntil(10_000) { !model.loading }
            val source =
                DocumentsContract.buildDocumentUriUsingTree(root, "root/${fixture.name}/Gate.bin")
                    .toString()
            compose.runOnIdle { model.transferPaths(listOf(source), out.path, false) }
            assertTrue(FixtureDocumentsProvider.gateOpened!!.await(8, TimeUnit.SECONDS))
            lateinit var running: String
            lateinit var queued: String
            compose.runOnIdle {
                running = model.activeTransferId!!
            }
            val notifications =
                compose.activity.getSystemService(android.app.NotificationManager::class.java)
            compose.waitUntil(5000) {
                notifications.activeNotifications.any {
                    it.id == 24 &&
                        it.notification.actions.any { action -> action.title.toString() == "Pause" }
                }
            }
            notifications.activeNotifications
                .first { it.id == 24 }
                .notification
                .actions
                .first { it.title.toString() == "Pause" }
                .actionIntent
                .send()
            compose.waitUntil(5000) { model.activeTransfer?.state == TransferState.PAUSED }
            compose.runOnIdle {
                model.transferPaths(listOf(first.path), out.path, false)
                queued = model.transferJobs.last().id
                model.transferPaths(listOf(second.path), out.path, false)
                model.moveQueuedTransfer(model.transferJobs.last().id, -1)
                model.cancelQueuedTransfer(queued)
            }
            gate.countDown()
            compose.onNodeWithText("Transfers", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("transfer-center").assertExists()
            compose
                .onNode(hasText("Resume") and hasAnyAncestor(hasTestTag("transfer-center")))
                .assertExists()
            assertEquals(TransferState.PAUSED, model.activeTransfer?.state)
            assertFalse(File(out, "Gate.bin").exists())
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .uiAutomation
                        .executeShellCommand(
                            "screencap -p /sdcard/findroid-transfer-center.png",
                        ),
                )
                .use { it.readBytes() }
            compose
                .onNode(hasText("Resume") and hasAnyAncestor(hasTestTag("transfer-center")))
                .performClick()
            compose.waitUntil(15_000) {
                model.busy == null &&
                    model.transferJobs.none {
                        it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
                    }
            }
            assertEquals(3L * 1024 * 1024, File(out, "Gate.bin").length())
            assertEquals("second", File(out, "Second.txt").readText())
            assertFalse(File(out, "First.txt").exists())
            assertEquals(
                TransferState.CANCELLED,
                model.transferJobs.first { it.id == queued }.state,
            )
            val saved = SqliteTransferStore(compose.activity).use { it.load() }
            assertEquals(TransferState.COMPLETED, saved.first { it.id == running }.state)
            compose.onNodeWithContentDescription("Close transfers").performClick()
        } finally {
            gate.countDown()
            model.cancelTransfer()
            compose.waitUntil(15_000) { model.busy == null }
            compose.runOnIdle {
                model.disconnectLocation(root.toString())
                model.navigate(model.workspace.path)
            }
            FixtureDocumentsProvider.transferGate = null
            FixtureDocumentsProvider.gateOpened = null
            fixture.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    fun retryOnlyRetriesFailedItemsAndCompletedHistorySurvivesReload() {
        val folder =
            File(compose.activity.filesDir, "Workspace/Retry-${System.nanoTime()}").apply {
                mkdirs()
            }
        val out = File(folder, "Out").apply { mkdir() }
        val good = File(folder, "Good.txt").apply { writeText("good") }
        val missing = File(folder, "Missing.txt")
        try {
            lateinit var id: String
            compose.runOnIdle {
                model.transferPaths(listOf(good.path, missing.path), out.path, false)
                id = model.transferJobs.last().id
            }
            compose.waitUntil(15_000) {
                model.transferJobs.first { it.id == id }.state == TransferState.FAILED &&
                    model.busy == null
            }
            assertEquals(listOf(missing.path), model.transferJobs.first { it.id == id }.remaining)
            missing.writeText("restored")
            compose.runOnIdle { model.retryTransfer(id) }
            compose.waitUntil(15_000) {
                model.busy == null && model.transferJobs.last().state == TransferState.COMPLETED
            }
            assertEquals(setOf("Good.txt", "Missing.txt"), out.list()!!.toSet())
            assertEquals("restored", File(out, "Missing.txt").readText())
            assertEquals(
                TransferState.COMPLETED,
                SqliteTransferStore(compose.activity).use { it.load() }.last().state,
            )
        } finally {
            compose.runOnIdle { model.navigate(model.workspace.path) }
            folder.deleteRecursively()
        }
    }
}
