package dev.droidfinder

import java.io.File
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

/** Archive paths never contain absolute roots, Windows separators, or parent traversal. */
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

data class ZipItem(val path: String, val folder: Boolean, val size: Long, val compressedSize: Long)

data class ZipContents(val items: List<ZipItem>, val size: Long)

/** Reads the central directory only; does not inflate or execute archive contents. */
fun inspectZip(file: File): ZipContents =
    ZipFile(file).use { zip ->
        require(zip.size() <= 10_000) { "This archive has too many entries (limit: 10,000)." }
        val items = linkedMapOf<String, ZipItem>()
        val explicit = mutableSetOf<String>()
        var size = 0L
        zip.entries().asSequence().forEach { entry ->
            val path = safeZipPath(entry.name)
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
            items[path] = ZipItem(path, entry.isDirectory, entry.size, entry.compressedSize)
            if (!entry.isDirectory) {
                val bytes = entry.size.coerceAtLeast(0)
                require(bytes <= 2L * 1024 * 1024 * 1024 - size) {
                    "Archive exceeds the 2 GB extraction limit."
                }
                size += bytes
            }
            require(items.size <= 20_000) { "This archive has too many nested folders." }
        }
        ZipContents(items.values.toList(), size)
    }
