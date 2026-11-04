package dev.findroid

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal data class OcrStoredFile(val name: String, val bytes: Long, val sha256: String)
internal data class OcrModelState(val language: OcrLanguage, val stored: OcrStoredFile?, val verified: Boolean)

/** App-private optional data. Failed/canceled imports stay reviewable, never become active models. */
internal class OcrModelStore(
    directoryRoot: File,
    private val publishNoReplace: (File, File) -> Unit,
) {
    // Android supplies the app-private parent. Resolve platform aliases such as macOS /var,
    // while refusing a link at the OCR root or anywhere below it.
    private val root = File(requireNotNull(directoryRoot.absoluteFile.parentFile).canonicalFile, directoryRoot.name)
    private val lock = Any()
    private val trained get() = File(root, "tessdata")
    private val staging get() = File(root, "incomplete")
    val engineDirectory get() = root.absolutePath

    private fun directory(file: File) {
        val absolute = file.absoluteFile.toPath().normalize()
        val parent = requireNotNull(root.parentFile).toPath()
        require(absolute.startsWith(root.toPath()))
        var part = parent
        for (component in parent.relativize(absolute)) {
            part = part.resolve(component)
            if (Files.exists(part, NOFOLLOW_LINKS)) {
                require(Files.isDirectory(part, NOFOLLOW_LINKS)) { "OCR storage contains a link or an unexpected file. Its bytes were kept." }
            } else Files.createDirectory(part)
        }
    }

    private fun fingerprint(file: File, check: () -> Unit = {}): OcrStoredFile {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "The stored language is not a regular file. Its bytes were kept." }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes += count
                require(bytes <= OcrPolicy.maximumStoredBytes) { "The stored OCR file exceeds the review limit. Its bytes were kept." }
                digest.update(buffer, 0, count)
            }
        }
        return OcrStoredFile(file.name, bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    fun state(language: OcrLanguage, check: () -> Unit = {}): OcrModelState = synchronized(lock) {
        require(language == OcrPolicy.language(language.id))
        directory(trained)
        val file = File(trained, language.filename)
        val stored = if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) fingerprint(file, check) else null
        OcrModelState(language, stored, stored != null && stored.bytes == language.bytes && stored.sha256 == language.sha256)
    }

    fun incomplete(check: () -> Unit = {}): List<OcrStoredFile> = synchronized(lock) {
        directory(staging)
        val files = staging.listFiles() ?: throw IOException("Couldn't review incomplete OCR downloads.")
        require(files.size <= OcrPolicy.maximumIncompleteFiles) { "Too many incomplete OCR files. Review OCR storage before downloading again." }
        files.sortedBy { it.name }.map { file ->
            require(file.name.matches(Regex("[a-f0-9-]{36}\\.partial"))) { "Unexpected OCR data was kept for manual review." }
            fingerprint(file, check)
        }
    }

    fun install(language: OcrLanguage, input: InputStream, check: () -> Unit = {}, progress: (Long) -> Unit = {}): OcrModelState = synchronized(lock) {
        require(language == OcrPolicy.language(language.id))
        val old = state(language, check)
        if (old.stored != null) {
            require(old.verified) { "This language file has changed. Review and remove it before importing another model; its bytes were kept." }
            return@synchronized old
        }
        val partials = incomplete(check)
        require(partials.size < OcrPolicy.maximumIncompleteFiles &&
            partials.sumOf { it.bytes } + OcrPolicy.languages.sumOf { state(it, check).stored?.bytes ?: 0L } + language.bytes <= OcrPolicy.maximumStoredBytes) {
            "OCR storage is full. Review incomplete language downloads before trying again."
        }
        val stage = File(staging, UUID.randomUUID().toString() + ".partial")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newOutputStream(stage.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), language.bytes - bytes + 1).toInt())
                if (count < 0) break
                if (count == 0) continue
                require(bytes + count <= language.bytes) { "This language download is larger than expected. The incomplete file was kept for review." }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                bytes += count
                progress(bytes)
            }
        }
        require(bytes == language.bytes && digest.digest().joinToString("") { "%02x".format(it) } == language.sha256) {
            "The language file doesn't match the reviewed model. Its incomplete bytes were kept; no model was installed."
        }
        check()
        // Production uses the existing kernel RENAME_NOREPLACE bridge; a racing file is never replaced.
        publishNoReplace(stage, File(trained, language.filename))
        state(language, check).also { require(it.verified) { "The installed language changed. Recognition is unavailable." } }
    }

    fun removeReviewedModel(reviewed: OcrModelState) = synchronized(lock) {
        val current = state(reviewed.language)
        require(current.stored != null && current.stored == reviewed.stored) { "The language file changed after review. Its bytes were kept." }
        Files.delete(File(trained, reviewed.language.filename).toPath())
    }

    fun discardReviewedIncomplete(reviewed: List<OcrStoredFile>) = synchronized(lock) {
        require(reviewed.isNotEmpty() && reviewed == incomplete()) { "Incomplete OCR files changed after review. Review them again; their bytes were kept." }
        reviewed.forEach { Files.delete(File(staging, it.name).toPath()) }
    }
}
