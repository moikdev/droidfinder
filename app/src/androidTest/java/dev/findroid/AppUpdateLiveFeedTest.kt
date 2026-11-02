package dev.findroid

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in acceptance against public GitHub assets; ordinary native runs remain offline. */
@RunWith(AndroidJUnit4::class)
class AppUpdateLiveFeedTest {
    @Test fun publicReleaseDownloadsWithProductionSource() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdates") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "live-update-acceptance.apk")
        try {
            val update = runBlocking(Dispatchers.IO) {
                val source = GithubUpdateSource()
                val latest = requireNotNull(source.latest(0)) { "No published update manifest found." }
                source.download(latest, destination) {}
                verifyUpdateBytes(destination, latest)
                assertNull("An installed latest version must not be offered again", source.latest(latest.versionCode))
                latest
            }
            val manager = context.packageManager
            val archive = manager.getPackageArchiveInfo(destination.path, PackageManager.GET_SIGNING_CERTIFICATES)
            assertNotNull(archive)
            archive!!
            val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            assertEquals(context.packageName, archive.packageName)
            assertEquals(update.versionCode, archive.longVersionCode)
            assertEquals(update.versionName, archive.versionName)
            assertTrue(archive.applicationInfo!!.minSdkVersion <= Build.VERSION.SDK_INT)
            val actualSigners = archive.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet()
            val installedSigners = installed.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet()
            assertEquals(installedSigners, actualSigners)
            File(context.filesDir, "live-update-acceptance.json").writeText(update.json())
        } finally {
            destination.delete()
        }
    }
}
