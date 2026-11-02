package dev.findroid

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppUpdateWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application
    private val jobs = mutableListOf<Job>()
    private val files = mutableListOf<File>()
    private val preferenceName = "update-test-${System.nanoTime()}"

    @After fun cleanup() {
        jobs.forEach { it.cancel() }
        files.forEach { it.deleteRecursively() }
        app.deleteSharedPreferences(preferenceName)
    }

    private fun fixture(name: String): File {
        val file = File(app.cacheDir, "fixture-${System.nanoTime()}.apk")
        InstrumentationRegistry.getInstrumentation().context.assets.open("updates/$name.apk").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        files += file
        return file
    }

    private fun metadata(file: File) = AppUpdate(
        BuildConfig.VERSION_CODE + 1L, "update-fixture", "Findroid-update.apk", file.length(),
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
        "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/vtest/Findroid-update.apk", "A signed test update.",
    )

    private fun directory() = File(app.cacheDir, "updates/test-${System.nanoTime()}").also { files += it }

    private fun controller(source: UpdateSource, directory: File): AppUpdateController {
        val job = SupervisorJob().also { jobs += it }
        return AppUpdateController(app, source, directory = directory, preferences = preferenceName,
            scope = CoroutineScope(job + Dispatchers.Main.immediate)).also { it.changeAutomatic(false) }
    }

    private fun source(apk: File, update: AppUpdate = metadata(apk)) = object : UpdateSource {
        override suspend fun latest(currentCode: Long) = update
        override suspend fun download(update: AppUpdate, destination: File, progress: (Long) -> Unit) {
            apk.copyTo(destination, overwrite = true)
            progress(apk.length())
        }
    }

    @Test fun verifiedDownloadSurvivesControllerRecreationAndOffersInstall() {
        val apk = fixture("valid")
        val directory = directory()
        lateinit var controller: AppUpdateController
        compose.runOnIdle { controller = controller(source(apk), directory); controller.open() }
        compose.waitUntil(10_000) { controller.state.update != null }
        var installRequests = 0
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { AppUpdateDialog(controller) { installRequests++ } } }
        }
        compose.onNodeWithText("Download update").performClick()
        compose.waitUntil(10_000) { controller.state.ready }
        compose.onNodeWithText("Install update").assertIsEnabled().performClick()
        assertEquals(1, installRequests)
        assertTrue(File(directory, "ready.apk").isFile)
        lateinit var restored: AppUpdateController
        val restoring = CountDownLatch(1)
        val releaseRestore = CountDownLatch(1)
        val restoreJob = SupervisorJob().also { jobs += it }
        val restoreScope = CoroutineScope(restoreJob + Dispatchers.Main.immediate)
        compose.runOnIdle {
            restored = AppUpdateController(app, source(apk), directory = directory,
                preferences = preferenceName, scope = restoreScope,
                verify = { file, update ->
                    restoring.countDown()
                    check(releaseRestore.await(10, TimeUnit.SECONDS))
                    verifyUpdatePackage(app, file, update)
                })
            restored.changeAutomatic(false)
        }
        assertTrue(restoring.await(10, TimeUnit.SECONDS))
        lateinit var installFile: Deferred<File?>
        try {
            compose.runOnIdle {
                installFile = restoreScope.async(start = CoroutineStart.UNDISPATCHED) {
                    restored.verifiedInstallFile()
                }
                assertFalse("Installation must wait for cache restoration", installFile.isCompleted)
            }
        } finally { releaseRestore.countDown() }
        val ready = runBlocking { installFile.await() }
        assertNotNull(ready)
        assertTrue(restored.state.ready)
        assertEquals(metadata(apk), restored.state.update)
    }

    @Test fun anotherAppAnotherSignerOldVersionsAndUnsupportedAndroidAreRejected() {
        for (name in listOf("wrong-package", "wrong-signer", "old", "newer-android")) {
            val apk = fixture(name)
            val update = metadata(apk)
            assertThrows("Must reject $name", IllegalArgumentException::class.java) {
                verifyUpdatePackage(app, apk, update)
            }
        }
    }

    @Test fun tamperedCachedUpdateNeverReachesTheInstaller() {
        val apk = fixture("valid")
        val directory = directory()
        lateinit var controller: AppUpdateController
        compose.runOnIdle { controller = controller(source(apk), directory); controller.open() }
        compose.waitUntil(10_000) { controller.state.update != null }
        compose.runOnIdle { controller.download() }
        compose.waitUntil(10_000) { controller.state.ready }
        File(directory, "ready.apk").writeBytes(ByteArray(apk.length().toInt()))
        val ready = runBlocking { withContext(Dispatchers.Main) { controller.verifiedInstallFile() } }
        assertNull(ready)
        assertFalse(controller.state.ready)
        assertTrue(controller.state.error!!.contains("checksum"))
    }

    @Test fun cancelledDownloadsRemovePartialFilesAndAllowRetry() {
        val apk = fixture("valid")
        val update = metadata(apk)
        var block = true
        val directory = directory()
        val source = object : UpdateSource {
            override suspend fun latest(currentCode: Long) = update
            override suspend fun download(update: AppUpdate, destination: File, progress: (Long) -> Unit) {
                if (block) {
                    destination.writeBytes(byteArrayOf(1, 2, 3))
                    progress(3)
                    awaitCancellation()
                }
                apk.copyTo(destination, overwrite = true)
                progress(apk.length())
            }
        }
        lateinit var controller: AppUpdateController
        compose.runOnIdle { controller = controller(source, directory); controller.open() }
        compose.waitUntil(10_000) { controller.state.update != null }
        compose.runOnIdle { controller.download() }
        compose.waitUntil(10_000) { File(directory, "download.part").isFile }
        compose.runOnIdle { controller.cancelDownload() }
        compose.waitUntil(10_000) { !controller.state.downloading && !File(directory, "download.part").exists() }
        assertFalse(controller.state.ready)
        block = false
        compose.runOnIdle { controller.download() }
        compose.waitUntil(10_000) { controller.state.ready }
    }

    @Test fun unavailableFeedLeavesFilesUsableAndManualChecksCanRetry() {
        val directory = directory()
        var unavailable = true
        val source = object : UpdateSource {
            override suspend fun latest(currentCode: Long): AppUpdate? {
                if (unavailable) error("The update source is not available yet. Try again later.")
                return null
            }
            override suspend fun download(update: AppUpdate, destination: File, progress: (Long) -> Unit) = Unit
        }
        lateinit var controller: AppUpdateController
        compose.runOnIdle { controller = controller(source, directory); controller.open() }
        compose.waitUntil(10_000) { controller.state.error != null }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { AppUpdateDialog(controller) {} } }
        }
        compose.onNodeWithText("The update source is not available yet. Try again later.").assertExists()
        unavailable = false
        compose.onNodeWithText("Check again").performClick()
        compose.waitUntil(10_000) { controller.state.checked && controller.state.error == null }
        compose.onNodeWithText("You're up to date.").assertExists()
    }

    @Test fun automaticChecksRespectTheDailyLimitAndCanBeDisabled() {
        val directory = directory()
        var calls = 0
        val source = object : UpdateSource {
            override suspend fun latest(currentCode: Long): AppUpdate? { calls++; return null }
            override suspend fun download(update: AppUpdate, destination: File, progress: (Long) -> Unit) = Unit
        }
        lateinit var controller: AppUpdateController
        compose.runOnIdle { controller = controller(source, directory); controller.open() }
        compose.waitUntil(10_000) { controller.state.checked }
        assertEquals(1, calls)
        compose.runOnIdle { controller.changeAutomatic(true); controller.onResume() }
        assertEquals(1, calls)
        compose.runOnIdle { controller.check() }
        compose.waitUntil(10_000) { calls == 2 && !controller.state.checking }
        compose.runOnIdle {
            controller.changeAutomatic(false)
            app.getSharedPreferences(preferenceName, 0).edit().putLong("lastAttempt", 0).commit()
            controller.onResume()
        }
        assertEquals(2, calls)
        assertEquals(0L, app.getSharedPreferences(preferenceName, 0).getLong("lastAttempt", -1))
    }

}
