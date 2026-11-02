package dev.findroid

import android.app.ActivityManager
import android.provider.DocumentsContract
import androidx.compose.runtime.snapshotFlow
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TransferLifecycleTest {
    private fun await(condition: () -> Boolean) = runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(15_000) { snapshotFlow(condition).first { it } }
        }
    }

    private fun exercise(cancel: Boolean, blocked: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val filename = if (cancel && !blocked) "Slow.bin" else "Gate.bin"
        if (!cancel || blocked) {
            FixtureDocumentsProvider.transferGate = CountDownLatch(1)
            FixtureDocumentsProvider.gateOpened = CountDownLatch(1)
        }
        val fixture =
            File(context.filesDir, "ProviderFixtures/Lifecycle-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(fixture, filename).outputStream().use { output ->
            repeat(48) { output.write(ByteArray(64 * 1024) { 42 }) }
        }
        val destination =
            File(context.filesDir, "Workspace/Lifecycle-${System.nanoTime()}").apply { mkdirs() }
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: FinderModel
        scenario.onActivity {
            model = it.model
            model.connectLocation(
                DocumentsContract.buildTreeDocumentUri(
                    "dev.findroid.fixtures",
                    "root/${fixture.name}",
                ),
                false,
            )
        }
        try {
            await {
                model.location.contains(fixture.name) &&
                    model.entries.any { it.name == filename } &&
                    !model.loading
            }
            val root = model.location
            val source = model.entries.first { it.name == filename }.path
            scenario.onActivity { model.transferPaths(listOf(source), destination.path, false) }
            if (cancel && !blocked) await { model.transferProgress.bytes > 0 && model.busy != null }
            else assertTrue(FixtureDocumentsProvider.gateOpened!!.await(8, TimeUnit.SECONDS))
            if (cancel) {
                // ActivityScenario's idle synchronization can outlast a short streamed copy.
                // Cancellation itself is thread-safe and must reach the active transfer now.
                model.cancelTransfer()
                await { model.busy == null }
                assertFalse(File(destination, filename).exists())
                assertTrue(destination.listFiles().isNullOrEmpty())
                assertTrue(File(fixture, filename).exists())
            } else {
                scenario.close()
                assertNotNull(model.busy)
                @Suppress("DEPRECATION")
                val running =
                    context
                        .getSystemService(ActivityManager::class.java)
                        .getRunningServices(30)
                        .any { it.service.className == TransferService::class.java.name }
                assertTrue("The transfer must remain in its foreground service", running)
                FixtureDocumentsProvider.transferGate?.countDown()
                await { model.busy == null }
                assertEquals(3L * 1024 * 1024, File(destination, filename).length())
                scenario = ActivityScenario.launch(MainActivity::class.java)
                scenario.onActivity { assertSame(model, it.model) }
            }
            scenario.onActivity {
                model.navigate(model.workspace.path)
                model.disconnectLocation(root)
            }
        } finally {
            FixtureDocumentsProvider.transferGate?.countDown()
            FixtureDocumentsProvider.transferGate = null
            FixtureDocumentsProvider.gateOpened = null
            scenario.close()
            fixture.deleteRecursively()
            destination.deleteRecursively()
        }
    }

    @Test fun transferContinuesAfterActivityCloses() = exercise(false)

    @Test fun cancelledProviderCopyRemovesStagingAndKeepsOriginal() = exercise(true)

    @Test fun cancellationClosesABlockedProviderStream() = exercise(true, true)
}
