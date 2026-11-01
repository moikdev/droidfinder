package dev.droidfinder

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal const val HOUSEKEEPING_DAY = 86_400_000L

internal fun <T> Iterable<T>.logicalBytes(size: (T) -> Long): Long =
    fold(0L) { total, item -> total + size(item).coerceIn(0, Long.MAX_VALUE - total) }

/** Logical bytes and metadata identity, without opening file contents or following links. */
internal data class DiskSnapshot(
    val bytes: Long,
    val files: Int,
    val folders: Int,
    val links: Int,
    val revision: String,
    val complete: Boolean,
    val issue: String? = null,
)

internal fun inspectDisk(
    file: File,
    check: () -> Unit = {},
    limit: Int = 100_000,
): DiskSnapshot {
    val digest = MessageDigest.getInstance("SHA-256")
    val records = mutableListOf<String>()
    var bytes = 0L
    var files = 0
    var folders = 0
    var links = 0
    var seen = 0
    var issue: String? = null
    fun add(path: Path, attrs: BasicFileAttributes): FileVisitResult {
        check()
        if (++seen > limit) {
            issue = "More than $limit entries; this item cannot be cleaned from this review."
            return FileVisitResult.TERMINATE
        }
        if (attrs.isSymbolicLink) links++
        else if (attrs.isDirectory) folders++
        else if (attrs.isRegularFile) {
            files++
            bytes = (Long.MAX_VALUE - bytes).coerceAtMost(attrs.size()) + bytes
        } else issue = "Contains a special filesystem item."
        val relative = file.toPath().relativize(path).toString()
        val record =
            "$relative\u0000${attrs.fileKey()}\u0000${attrs.size()}\u0000${attrs.lastModifiedTime()}\u0000${attrs.isDirectory}\u0000${attrs.isSymbolicLink}\n"
        records += record
        return FileVisitResult.CONTINUE
    }
    try {
        Files.walkFileTree(
            file.toPath(),
            emptySet(),
            128,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                    add(dir, attrs)

                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isDirectory)
                        issue =
                            "Folder depth exceeds 128; this item cannot be cleaned from this review."
                    return add(path, attrs)
                }

                override fun visitFileFailed(path: Path, error: IOException): FileVisitResult {
                    check()
                    issue = error.message ?: "Couldn't inspect an item."
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (error: IOException) {
        issue = error.message ?: "Couldn't inspect this item."
    }
    records.sorted().forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
    return DiskSnapshot(
        bytes,
        files,
        folders,
        links,
        digest.digest().joinToString("") { "%02x".format(it) },
        issue == null,
        issue,
    )
}

internal data class TrashSpaceItem(
    val file: File,
    val original: String?,
    val trashedAt: Long?,
    val disk: DiskSnapshot,
) {
    fun expired(days: Int, now: Long): Boolean =
        days > 0 && trashedAt != null && trashedAt <= now - days * HOUSEKEEPING_DAY
}

internal enum class CacheKind(val title: String, val shared: Boolean = false) {
    DOCUMENTS("Provider previews"),
    ARCHIVES("Archive previews"),
    PDF("PDF scratch files"),
    SHARES("Prepared shares", true),
    CLIPBOARD("Text clipboard", true),
    SHORTCUTS("Shortcut staging"),
}

internal data class CacheSpaceItem(
    val file: File,
    val kind: CacheKind,
    val touched: Long,
    val disk: DiskSnapshot,
    val protected: Boolean,
    val displayName: String = file.name,
) {
    fun eligible(now: Long, days: Int = 0): Boolean =
        disk.complete &&
            !protected &&
            touched > 0 &&
            touched <= now &&
            (!kind.shared || touched <= now - HOUSEKEEPING_DAY) &&
            (days == 0 || touched <= now - days * HOUSEKEEPING_DAY)
}

/** Pinning and detaching are atomic; expensive deletion runs outside this short monitor. */
internal object CacheLeases {
    private val pins = mutableMapOf<Path, Int>()

    @Synchronized
    fun pin(file: File): Closeable {
        val path = file.toPath().toAbsolutePath().normalize()
        pins[path] = (pins[path] ?: 0) + 1
        var closed = false
        return Closeable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    val remaining = pins.getValue(path) - 1
                    if (remaining == 0) pins.remove(path) else pins[path] = remaining
                }
            }
        }
    }

    @Synchronized
    fun protected(file: File): Boolean {
        val path = file.toPath().toAbsolutePath().normalize()
        return pins.keys.any { path.startsWith(it) || it.startsWith(path) }
    }

    @Synchronized
    fun detach(file: File): File? {
        if (protected(file)) return null
        val target = File(file.parentFile, ".cleanup-${UUID.randomUUID()}")
        Files.move(file.toPath(), target.toPath())
        return target
    }
}

/**
 * Only known disposable cache units are exposed; settings, originals and transfer state are absent.
 */
internal class CacheMaintenance(private val root: File) {
    private fun safe(file: File): Boolean {
        val base = root.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        if (path == base || !path.startsWith(base)) return false
        var ancestor: Path? = path
        while (ancestor != null && ancestor.startsWith(base)) {
            if (Files.isSymbolicLink(ancestor)) return false
            ancestor = ancestor.parent
        }
        return true
    }

    fun inspect(check: () -> Unit = {}): Pair<List<CacheSpaceItem>, List<String>> {
        val items = mutableListOf<CacheSpaceItem>()
        val issues = mutableListOf<String>()
        var remaining = 100_000
        fun units(relative: String, kind: CacheKind, accept: (File) -> Boolean = { true }) {
            check()
            val directory = File(root, relative)
            if (!directory.exists() && !Files.isSymbolicLink(directory.toPath())) return
            if (!safe(directory)) {
                issues += "${kind.title}: symbolic-link cache paths were excluded."
                return
            }
            try {
                Files.newDirectoryStream(directory.toPath()).use { children ->
                    for (path in children) {
                        val file = path.toFile()
                        check()
                        if (accept(file)) {
                            if (items.size >= 10_000) {
                                if (issues.none { it.startsWith("Cache inventory limited") })
                                    issues +=
                                        "Cache inventory limited to 10,000 units; refresh after cleaning."
                            } else if (safe(file)) {
                                val disk = inspectDisk(file, check, remaining)
                                remaining =
                                    (remaining - disk.files - disk.folders - disk.links)
                                        .coerceAtLeast(0)
                                val displayName =
                                    if (file.isDirectory)
                                        runCatching {
                                            Files.newDirectoryStream(file.toPath()).use { stream ->
                                                val children =
                                                    stream.iterator().asSequence().take(2).toList()
                                                children.singleOrNull()?.fileName?.toString()
                                            }
                                        }
                                            .getOrNull() ?: "${kind.title} · ${disk.files} files"
                                    else file.name
                                items +=
                                    CacheSpaceItem(
                                        file,
                                        kind,
                                        file.lastModified(),
                                        disk,
                                        CacheLeases.protected(file),
                                        displayName,
                                    )
                            } else issues += "${kind.title}: a symbolic link was excluded."
                        }
                    }
                }
            } catch (error: IOException) {
                issues += "${kind.title}: couldn't read cache directory: ${error.message}"
            }
        }
        units("documents", CacheKind.DOCUMENTS)
        units("shared/archive-peek", CacheKind.ARCHIVES)
        units("pdf-scratch", CacheKind.PDF)
        units("shared/text-clips", CacheKind.CLIPBOARD)
        units("shared", CacheKind.SHARES) { it.name !in setOf("archive-peek", "text-clips") }
        units("shortcuts", CacheKind.SHORTCUTS)
        return items to issues
    }

    fun remove(item: CacheSpaceItem, now: Long, check: () -> Unit = {}): Boolean {
        require(safe(item.file) && knownUnit(item)) { "This is not a disposable cache unit." }
        if (!item.eligible(now) || CacheLeases.protected(item.file)) return false
        check()
        val fresh = inspectDisk(item.file, check)
        if (
            !fresh.complete ||
                fresh.revision != item.disk.revision ||
                item.file.lastModified() != item.touched
        )
            return false
        val detached = CacheLeases.detach(item.file) ?: return false
        // Once detached, new readers regenerate the original path; a stopped deletion is kept as
        // another visible cache unit and can be reviewed again.
        deleteDiskTree(detached, check)
        return true
    }

    private fun knownUnit(item: CacheSpaceItem): Boolean {
        val relative =
            root
                .toPath()
                .toAbsolutePath()
                .normalize()
                .relativize(item.file.toPath().toAbsolutePath().normalize())
        val parent = relative.parent?.toString()?.replace(File.separatorChar, '/')
        return when (item.kind) {
            CacheKind.DOCUMENTS -> parent == "documents"
            CacheKind.ARCHIVES -> parent == "shared/archive-peek"
            CacheKind.PDF -> parent == "pdf-scratch"
            CacheKind.CLIPBOARD -> parent == "shared/text-clips"
            CacheKind.SHORTCUTS -> parent == "shortcuts"
            CacheKind.SHARES ->
                parent == "shared" && item.file.name !in setOf("archive-peek", "text-clips")
        }
    }
}

internal fun deleteDiskTree(file: File, check: () -> Unit = {}) {
    Files.walkFileTree(
        file.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                check()
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                check()
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
