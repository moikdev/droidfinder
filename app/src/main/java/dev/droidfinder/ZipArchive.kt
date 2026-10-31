package dev.droidfinder

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipFile

enum class ZipCompression(val title: String, val level: Int) {
    BALANCED("Balanced", -1),
    FAST("Fast", 1),
    SMALLEST("Smallest size", 9),
    NONE("No compression", 0),
}

fun zipFilename(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotBlank() && !trimmed.equals(".zip", true)) { "Enter an archive name." }
    return validateFilename(if (trimmed.endsWith(".zip", true)) trimmed else "$trimmed.zip")
}

fun safeZipPath(path: String): String {
    require(
        !path.startsWith('/') &&
            '\\' !in path &&
            '\u0000' !in path &&
            !Regex("^[A-Za-z]:").containsMatchIn(path),
    ) {
        "Unsafe archive path: $path"
    }
    require(path.length <= 4096) { "Archive path is too long." }
    val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
    require(parts.isNotEmpty() && parts.none { it == ".." }) { "Unsafe archive path: $path" }
    require(parts.size <= 128) { "Archive path is too deeply nested." }
    return parts.joinToString("/")
}

data class ZipLimits(
    val entries: Int = 10_000,
    val expandedBytes: Long = 2L * 1024 * 1024 * 1024,
    val memberBytes: Long = 512L * 1024 * 1024,
    val ratio: Int = 1000,
    val previewBytes: Long = 128L * 1024 * 1024,
) {
    fun validated(): ZipLimits {
        require(
            entries in 100..50_000 &&
                expandedBytes in 1..8L * 1024 * 1024 * 1024 &&
                memberBytes in 1..expandedBytes &&
                ratio in 1..100_000 &&
                previewBytes in 1..256L * 1024 * 1024,
        ) {
            "Invalid ZIP resource limits."
        }
        return this
    }
}

class ZipLimitException(message: String) : IOException(message)

class ZipIntegrityException(message: String) : IOException(message)

data class ZipItem(
    val path: String,
    val folder: Boolean,
    val size: Long,
    val compressedSize: Long,
    val rawName: String? = null,
    val crc: Long = -1,
    val modified: Long = -1,
)

data class ZipContents(val items: List<ZipItem>, val size: Long)

data class ZipCheckResult(
    val checked: Int,
    val total: Int,
    val bytes: Long,
    val failures: Int,
    val details: List<String>,
    val limit: String? = null,
) {
    val healthy
        get() = checked == total && failures == 0 && limit == null
}

internal fun inspectZipOpen(
    zip: ZipFile,
    limits: ZipLimits,
    control: TransferControl,
): ZipContents {
    limits.validated()
    control.check()
    if (zip.size() > limits.entries)
        throw ZipLimitException("This archive exceeds the ${limits.entries} entry limit.")
    val items = linkedMapOf<String, ZipItem>()
    val explicit = mutableSetOf<String>()
    var size = 0L
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        control.check()
        val entry = entries.nextElement()
        val path = safeZipPath(entry.name)
        require(entry.size >= 0 && entry.compressedSize >= 0 && entry.crc >= 0) {
            "Missing size/checksum metadata: $path"
        }
        require(entry.method == 0 || entry.method == 8) {
            "Unsupported ZIP compression method for $path."
        }
        require(!entry.isDirectory || entry.size == 0L && entry.crc == 0L) {
            "Folder record contains unexpected data: $path"
        }
        require(explicit.add(path) || entry.isDirectory && items[path]?.folder == true) {
            "Duplicate archive entry: $path"
        }
        val parts = path.split('/')
        for (i in 1 until parts.size) {
            val parent = parts.take(i).joinToString("/")
            require(items[parent]?.folder != false) {
                "Archive file conflicts with a folder: $parent"
            }
            items.putIfAbsent(parent, ZipItem(parent, true, 0, 0))
        }
        require(items[path] == null || items[path]!!.folder == entry.isDirectory) {
            "Archive file conflicts with a folder: $path"
        }
        items[path] =
            ZipItem(
                path,
                entry.isDirectory,
                entry.size,
                entry.compressedSize,
                entry.name,
                entry.crc,
                entry.time,
            )
        if (!entry.isDirectory) {
            require(entry.size <= Long.MAX_VALUE - size) {
                "Archive size metadata overflows its range."
            }
            size += entry.size
        }
        if (items.size > limits.entries * 2)
            throw ZipLimitException(
                "This archive has too many inferred folders (limit: ${limits.entries * 2}).",
            )
    }
    return ZipContents(items.values.toList(), size)
}

/** Central-directory inspection never inflates members. Size limits apply to selected work. */
fun inspectZip(
    file: File,
    limits: ZipLimits = ZipLimits(),
    control: TransferControl = TransferControl(),
): ZipContents {
    control.check()
    return ZipFile(file).use { zip -> control.guard(zip) { inspectZipOpen(zip, limits, control) } }
}

fun selectedZipItems(
    contents: ZipContents,
    selection: Set<String>?,
    check: () -> Unit = {},
): List<ZipItem> {
    if (selection == null) return contents.items
    require(selection.isNotEmpty()) { "Select archive items to extract." }
    val paths = selection.map(::safeZipPath).toSet()
    val available =
        contents.items.mapTo(hashSetOf()) {
            check()
            it.path
        }
    require(
        paths.all { wanted ->
            check()
            wanted in available
        },
    ) {
        "The archive selection is no longer available. Reopen the archive."
    }
    return contents.items.filter { item ->
        check()
        var path = item.path
        var chosen = path in paths
        while (!chosen && '/' in path) {
            path = path.substringBeforeLast('/')
            chosen = path in paths
        }
        chosen
    }
}

private fun checkRatio(bytes: Long, compressed: Long, limits: ZipLimits, path: String) {
    val denominator = compressed.coerceAtLeast(1)
    if (
        bytes > 1024 * 1024 &&
            denominator <= Long.MAX_VALUE / limits.ratio &&
            bytes > denominator * limits.ratio
    )
        throw ZipLimitException(
            "Expansion ratio exceeds ${limits.ratio}:1 for $path. Review ZIP limits before continuing.",
        )
}

fun validateZipWork(items: List<ZipItem>, limits: ZipLimits): Long {
    limits.validated()
    var bytes = 0L
    items
        .filter { !it.folder }
        .forEach { item ->
            if (item.size > limits.memberBytes)
                throw ZipLimitException(
                    "${item.path} exceeds the per-file limit (${formatArchiveBytes(limits.memberBytes)}).",
                )
            if (item.size > limits.expandedBytes - bytes)
                throw ZipLimitException(
                    "Selected contents exceed the expanded-byte limit (${formatArchiveBytes(limits.expandedBytes)}).",
                )
            checkRatio(item.size, item.compressedSize, limits, item.path)
            bytes += item.size
        }
    return bytes
}

private fun formatArchiveBytes(value: Long) = "${value / (1024 * 1024)} MiB"

internal class ZipReadBudget(var bytes: Long = 0)

/** Verify actual bytes and CRC independently of ZipFile's stream implementation. */
internal fun readZipMember(
    zip: ZipFile,
    item: ZipItem,
    output: OutputStream?,
    limits: ZipLimits,
    budget: ZipReadBudget,
    control: TransferControl,
) {
    val entry =
        zip.getEntry(item.rawName ?: item.path) ?: error("Archive entry disappeared: ${item.path}")
    val crc = CRC32()
    var bytes = 0L
    zip.getInputStream(entry).use { input ->
        control.guard(input) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                control.check()
                val count = input.read(buffer)
                control.check()
                if (count < 0) break
                if (count == 0) continue
                if (count > limits.memberBytes - bytes)
                    throw ZipLimitException(
                        "Inflated file exceeds the per-file limit: ${item.path}",
                    )
                if (count > limits.expandedBytes - budget.bytes)
                    throw ZipLimitException("Inflated contents exceed the expanded-byte limit.")
                bytes += count
                budget.bytes += count
                if (bytes > item.size)
                    throw ZipIntegrityException("Size differs from ZIP metadata: ${item.path}")
                checkRatio(bytes, item.compressedSize, limits, item.path)
                crc.update(buffer, 0, count)
                output?.write(buffer, 0, count)
                control.bytes(count)
            }
        }
    }
    if (bytes != item.size || crc.value != item.crc)
        throw ZipIntegrityException("Size/CRC check failed: ${item.path}")
}

fun verifyZip(
    file: File,
    limits: ZipLimits = ZipLimits(),
    control: TransferControl = TransferControl(),
): ZipCheckResult {
    control.check()
    val stamp = file.length() to file.lastModified()
    return ZipFile(file).use { zip ->
        control.guard(zip) {
            val contents = inspectZipOpen(zip, limits, control)
            val files = contents.items.filter { !it.folder }
            control.phase("Checking ZIP", contents.size)
            val budget = ZipReadBudget()
            var checked = 0
            var failed = 0
            val details = ArrayList<String>()
            for (item in files) {
                control.item(item.path)
                try {
                    validateZipWork(listOf(item), limits)
                    readZipMember(zip, item, null, limits, budget, control)
                } catch (limit: ZipLimitException) {
                    return@guard ZipCheckResult(
                        checked,
                        files.size,
                        budget.bytes,
                        failed,
                        details,
                        limit.message,
                    )
                } catch (error: IOException) {
                    control.check()
                    failed++
                    if (details.size < 20)
                        details += "${item.path}: ${error.message.orEmpty().take(200)}"
                }
                checked++
            }
            control.check()
            check(stamp == (file.length() to file.lastModified())) {
                "The archive changed while it was being checked. Reopen it."
            }
            ZipCheckResult(checked, files.size, budget.bytes, failed, details)
        }
    }
}
