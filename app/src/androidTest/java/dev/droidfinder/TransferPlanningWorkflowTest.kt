package dev.droidfinder

import android.app.Notification
import android.app.NotificationManager
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransferPlanningWorkflowTest {
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
    fun knownProviderCopyShowsPhaseProgressEtaAndDurableTotalsWithPauseControls() {
        val folder =
            File(compose.activity.filesDir, "ProviderFixtures/Planning-${System.nanoTime()}")
                .apply { mkdirs() }
        val source =
            File(folder, "Slow.bin").apply { writeBytes(ByteArray(16 * 1024 * 1024) { 33 }) }
        val target =
            File(compose.activity.filesDir, "Workspace/Planning-${System.nanoTime()}").apply {
                mkdirs()
            }
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${folder.name}",
            )
        if (android.os.Build.VERSION.SDK_INT >= 33)
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(
                    compose.activity.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
        try {
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.onNodeWithText("Transfers").performClick()
            compose.onNodeWithTag("transfer-center").assertExists()
            compose.runOnIdle {
                model.updateNotificationState()
                model.clearTransferHistory()
                model.connectLocation(tree, false)
                model.navigate(target.path)
                model.transferPaths(
                    listOf(
                        DocumentsContract.buildDocumentUriUsingTree(
                                tree,
                                "root/${folder.name}/Slow.bin",
                            )
                            .toString(),
                    ),
                    target.path,
                    false,
                )
            }
            compose.waitUntil(20_000) {
                model.transferProgress.phase == "Copying" &&
                    model.transferProgress.secondsRemaining != null &&
                    model.transferProgress.fraction?.let { it > .1f && it < .9f } == true
            }
            val id = model.activeTransferId!!
            assertEquals(source.length(), model.activeTransfer!!.estimate!!.totalBytes)
            assertEquals(1, model.activeTransfer!!.estimate!!.files)
            assertNotNull(model.activeTransfer!!.availableAtStart)
            compose.onNodeWithTag("transfer-center").assertExists()
            val panel = compose.onNodeWithTag("transfer-center").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("A single transfer must use a compact window", panel.width / density <= 600f)
            assertTrue("A single transfer must wrap its contents", panel.height / density <= 380f)
            try {
                compose.waitUntil(3000) {
                    compose
                        .onAllNodesWithText("left", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            } catch (error: Throwable) {
                throw AssertionError(
                    "ETA not visible: progress=${model.transferProgress}; fresh=${model.transferProgress.fresh()}; active=${model.activeTransfer?.state}; ${compose.onNodeWithTag("transfer-center").printToString()}",
                    error,
                )
            }
            compose.runOnIdle { model.pauseTransfer(id) }
            compose.waitUntil(5000) { model.activeTransfer?.state == TransferState.PAUSED }
            compose
                .onNode(hasText("Resume") and hasAnyAncestor(hasTestTag("transfer-center")))
                .assertExists()
            assertTrue(
                compose
                    .onAllNodesWithText("left", substring = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            // Observe notification totals while paused, after asserting the live ETA. Waiting
            // for Android's notification delivery first can consume the short copy's ETA window.
            val manager = compose.activity.getSystemService(NotificationManager::class.java)
            compose.waitUntil(5000) {
                manager.activeNotifications.any {
                    it.id == 24 &&
                        !it.notification.extras.getBoolean(
                            Notification.EXTRA_PROGRESS_INDETERMINATE,
                        ) &&
                        it.notification.extras
                            .getCharSequence(Notification.EXTRA_TEXT)
                            .toString()
                            .contains("16.0 MB")
                }
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation()
                        .uiAutomation
                        .executeShellCommand(
                            "screencap -p /sdcard/droidfinder-transfer-planning.png",
                        ),
                )
                .use { it.readBytes() }
            compose
                .onNode(hasText("Resume") and hasAnyAncestor(hasTestTag("transfer-center")))
                .performClick()
            compose.waitUntil(25_000) {
                model.busy == null && model.transferJobs.first { it.id == id }.terminal
            }
            val saved =
                SqliteTransferStore(compose.activity).use { it.load() }.first { it.id == id }
            assertEquals(TransferState.COMPLETED, saved.state)
            assertEquals(TransferEstimate(source.length(), 1, 0), saved.estimate)
            assertEquals(source.length(), File(target, "Slow.bin").length())
            compose.onNodeWithContentDescription("Close transfers").performClick()
        } finally {
            compose.runOnIdle {
                model.transferJobs
                    .filter { !it.terminal }
                    .forEach { model.cancelQueuedTransfer(it.id) }
                if (model.busy != null) model.cancelTransfer()
            }
            compose.waitUntil(15_000) { model.busy == null }
            compose.runOnIdle {
                model.disconnectLocation(tree.toString())
                model.navigate(model.workspace.path)
            }
            folder.deleteRecursively()
            target.deleteRecursively()
        }
    }
}
