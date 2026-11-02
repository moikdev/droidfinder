package dev.findroid

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test

/** Host-driven acceptance: confirm Android's dialog, then verify the installed version and data. */
class AppUpdateInstallerSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun openVerifiedUpdateInAndroidInstaller() {
        val activity = compose.activity
        val file = File(activity.cacheDir, "updates/installer-smoke.apk")
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("updates/valid.apk").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val update = AppUpdate(BuildConfig.VERSION_CODE + 1L, "update-fixture", "Findroid-update.apk", file.length(),
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
            "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/vtest/Findroid-update.apk", "Test update")
        verifyUpdatePackage(activity.application, file, update)
        File(activity.filesDir, "Workspace/OtaUpgradeProbe.txt").writeText("OTA preserved this file")
        activity.getSharedPreferences("ota-upgrade-probe", 0).edit().putString("value", "preserved").commit()
        compose.runOnIdle { activity.startActivity(appUpdateInstallIntent(activity, file)) }
    }
}
