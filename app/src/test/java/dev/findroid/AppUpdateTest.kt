package dev.findroid

import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val repository = "moikdev/droidfinder"
    private val bytes = "signed apk fixture".toByteArray()
    private fun update() = AppUpdate(30, "0.29.1-preview", "Findroid-0.29.1-preview.apk", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "https://github.com/$repository/releases/download/v0.29.1-preview/Findroid-0.29.1-preview.apk", "Release notes")

    @Test fun manifestRoundTripsWithoutLosingVersionSizeOrDigest() {
        assertEquals(update(), AppUpdate.parse(update().json(), repository))
    }

    @Test fun manifestRejectsAnotherPackageAndInvalidVersions() {
        for ((key,value) in listOf("packageName" to "other.app", "versionCode" to "0",
            "versionCode" to "2147483648", "versionCode" to "1.5", "versionName" to "")) {
            val json = JsonParser.parseString(update().json()).asJsonObject
            json.addProperty(key, value)
            assertThrows(Exception::class.java) { AppUpdate.parse(json.toString(), repository) }
        }
    }

    @Test fun manifestRejectsUnboundedSizesAndMissingIntegrityInformation() {
        for ((key,value) in listOf("bytes" to "0", "bytes" to (MAX_UPDATE_BYTES + 1).toString(),
            "sha256" to "", "sha256" to "x".repeat(64), "apkName" to "../app.apk")) {
            val json = JsonParser.parseString(update().json()).asJsonObject
            json.addProperty(key,value)
            assertThrows(Exception::class.java) { AppUpdate.parse(json.toString(), repository) }
        }
    }

    @Test fun assetsMustBelongToTheConfiguredRepositoryAndExactFilename() {
        val u = update()
        assertTrue(updateAssetUrl(u.downloadUrl, repository, u.apkName))
        for (url in listOf(u.downloadUrl.replace("https:","http:"),
            u.downloadUrl.replace("github.com", "github.com.attacker.test"),
            u.downloadUrl.replace(repository, "another/repository"),
            u.downloadUrl + "?redirect=elsewhere", u.downloadUrl + "#fragment",
            u.downloadUrl.replace("github.com", "user@github.com"),
            u.downloadUrl.replace("/v0.29.1-preview/", "/../"),
            u.downloadUrl.replace(u.apkName,"other.apk"))) {
            assertFalse(url, updateAssetUrl(url, repository, u.apkName))
        }
    }

    @Test fun redirectsNeverAllowCleartextLocalHostsCredentialsOrUnrelatedSites() {
        assertTrue(updateNetworkUrl("https://release-assets.githubusercontent.com/signed/path?token=download"))
        for (url in listOf("http://github.com/file", "https://localhost/file", "https://127.0.0.1/file",
            "https://evil.test/file", "file:///tmp/app.apk", "https://user:password@github.com/file",
            "https://github.com:8443/file", "https://github.com.attacker.test/file")) {
            assertFalse(url, updateNetworkUrl(url))
        }
    }

    @Test fun partialChangedAndOversizedDownloadsFailVerification() {
        val file = File.createTempFile("findroid-update", ".apk")
        try {
            file.writeBytes(bytes)
            verifyUpdateBytes(file, update())
            file.writeBytes(bytes.dropLast(1).toByteArray())
            assertThrows(IllegalArgumentException::class.java) { verifyUpdateBytes(file, update()) }
            file.writeBytes(bytes + byteArrayOf(0))
            assertThrows(IllegalArgumentException::class.java) { verifyUpdateBytes(file, update()) }
            file.writeBytes(ByteArray(bytes.size))
            assertThrows(IllegalArgumentException::class.java) { verifyUpdateBytes(file, update()) }
        } finally { file.delete() }
    }
}
