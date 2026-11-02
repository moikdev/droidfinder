package dev.findroid

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal const val MAX_UPDATE_BYTES = 128L * 1024 * 1024
internal const val UPDATE_MANIFEST = "findroid-update.json"

internal data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val bytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val notes: String,
) {
    fun json(): String = JsonObject().apply {
        addProperty("packageName", "dev.findroid")
        addProperty("versionCode", versionCode)
        addProperty("versionName", versionName)
        addProperty("apkName", apkName)
        addProperty("bytes", bytes)
        addProperty("sha256", sha256)
        addProperty("downloadUrl", downloadUrl)
        addProperty("notes", notes)
    }.toString()

    companion object {
        fun parse(text: String, repository: String): AppUpdate {
            require(text.toByteArray().size <= 32 * 1024) { "Update information is too large." }
            val value = JsonParser.parseString(text).asJsonObject
            require(value["packageName"].asString == "dev.findroid") { "This update is for another app." }
            val code = value["versionCode"].asString.toLong()
            val name = value["versionName"].asString
            val apk = value["apkName"].asString
            val bytes = value["bytes"].asString.toLong()
            val digest = value["sha256"].asString.lowercase()
            val url = value["downloadUrl"].asString
            require(code in 1..Int.MAX_VALUE.toLong() && name.length in 1..80)
            require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk").matches(apk))
            require(bytes in 1..MAX_UPDATE_BYTES) { "The update exceeds the download limit." }
            require(Regex("[0-9a-f]{64}").matches(digest)) { "The update checksum is missing." }
            require(updateAssetUrl(url, repository, apk)) { "The update download source is invalid." }
            return AppUpdate(code, name, apk, bytes, digest, url,
                value.get("notes")?.asString.orEmpty().take(4000))
        }
    }
}

internal fun updateAssetUrl(url: String, repository: String, filename: String): Boolean = runCatching {
    val uri = URI(url)
    val prefix = "/$repository/releases/download/"
    uri.scheme == "https" && uri.host == "github.com" && uri.rawUserInfo == null &&
        uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath.startsWith(prefix) && uri.rawPath.removePrefix(prefix).split('/').let {
            it.size == 2 && Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(it[0]) && it[1] == filename
        }
}.getOrDefault(false)

internal fun updateNetworkUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
        uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com")
}.getOrDefault(false)

/** Every redirect is checked before opening it; no credentials or document data are sent. */
internal class UpdateHttp {
    fun <T> read(url: String, limit: Long, block: (InputStream) -> T): T {
        var current = url
        repeat(6) {
            require(updateNetworkUrl(current)) { "The update redirected to an unsupported source." }
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Findroid/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                when (val code = connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: error("The update source returned an incomplete redirect.")
                        current = URI(current).resolve(location).toString()
                    }
                    200 -> {
                        require(connection.contentLengthLong <= limit) { "The update response is too large." }
                        return connection.inputStream.use(block)
                    }
                    404 -> error("The update source is not available yet. Try again later.")
                    403, 429 -> error("The update service is busy. Try again later.")
                    else -> error("The update service returned an error ($code). Try again later.")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("The update source redirected too many times.")
    }

    fun text(url: String, limit: Int): String = read(url, limit.toLong()) { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
            require(output.size() <= limit) { "Update information is too large." }
        }
        output.toString("UTF-8")
    }
}

internal interface UpdateSource {
    suspend fun latest(currentCode: Long): AppUpdate?
    suspend fun download(update: AppUpdate, destination: File, progress: (Long) -> Unit)
}

internal class GithubUpdateSource(
    private val repository: String = BuildConfig.UPDATE_REPOSITORY,
    private val http: UpdateHttp = UpdateHttp(),
) : UpdateSource {
    override suspend fun latest(currentCode: Long): AppUpdate? {
        val releases = JsonParser.parseString(
            http.text("https://api.github.com/repos/$repository/releases?per_page=20", 512 * 1024),
        ).asJsonArray
        val candidates = releases.filter { !it.asJsonObject["draft"].asBoolean }
            .mapNotNull { release ->
                release.asJsonObject["assets"].asJsonArray.firstOrNull {
                    it.asJsonObject["name"].asString == UPDATE_MANIFEST
                }?.asJsonObject?.get("browser_download_url")?.asString
            }.take(5)
        var latest: AppUpdate? = null
        for (url in candidates) {
            coroutineContext.ensureActive()
            require(updateAssetUrl(url, repository, UPDATE_MANIFEST)) { "The update feed is invalid." }
            val update = AppUpdate.parse(http.text(url, 32 * 1024), repository)
            if (update.versionCode > currentCode && update.versionCode > (latest?.versionCode ?: 0))
                latest = update
        }
        return latest
    }

    override suspend fun download(update: AppUpdate, destination: File, progress: (Long) -> Unit) {
        val context = coroutineContext
        http.read(update.downloadUrl, update.bytes) { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= update.bytes) { "The download is larger than expected." }
                    output.write(buffer, 0, read)
                    progress(count)
                }
                require(count == update.bytes) { "The download was interrupted. Please retry." }
            }
        }
    }
}

internal fun verifyUpdateBytes(file: File, update: AppUpdate) {
    require(file.isFile && file.length() == update.bytes) { "The update download is incomplete." }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val hex = digest.digest().joinToString("") { "%02x".format(it) }
    require(MessageDigest.isEqual(hex.toByteArray(), update.sha256.toByteArray())) {
        "The update checksum did not match. Please download it again."
    }
}
