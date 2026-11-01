package dev.droidfinder

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal enum class FileCategory(val title: String) {
    IMAGES("Images"),
    VIDEO("Video"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    ARCHIVES("Archives"),
    APPS("Apps"),
    OTHER("Other");

    companion object {
        fun of(name: String, mime: String): FileCategory {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (fileType(ext, mime).group) {
                FileTypeGroup.IMAGE -> IMAGES
                FileTypeGroup.VIDEO -> VIDEO
                FileTypeGroup.AUDIO -> AUDIO
                FileTypeGroup.ARCHIVE -> ARCHIVES
                FileTypeGroup.ANDROID -> APPS
                FileTypeGroup.PDF,
                FileTypeGroup.TABLE,
                FileTypeGroup.PRESENTATION,
                FileTypeGroup.CODE,
                FileTypeGroup.TEXT -> DOCUMENTS
                else ->
                    when (ext) {
                        "apks",
                        "xapk",
                        "aab" -> APPS
                        "epub" -> DOCUMENTS
                        else -> OTHER
                    }
            }
        }
    }
}

internal data class AnalysisNode(
    val path: String,
    val name: String,
    val folder: Boolean,
    val bytes: Long?,
    val modified: Long,
    val objectId: String,
    val revision: String,
    val category: FileCategory = FileCategory.OTHER,
    val link: Boolean = false,
    val canDelete: Boolean = false,
    val problem: String? = null,
    val mime: String = "application/octet-stream",
    val observedHash: String? = null,
)

internal data class AnalyzedFile(val node: AnalysisNode, val relative: String, val bucket: String)

internal data class AnalysisIssue(val path: String, val reason: String)

internal data class AnalysisLimits(
    val entries: Int = 100_000,
    val hashBytes: Long = 8L * 1024 * 1024 * 1024,
) {
    fun normalized() =
        copy(
            entries = entries.coerceIn(1000, 1_000_000),
            hashBytes = hashBytes.coerceIn(1024 * 1024, 1024L * 1024 * 1024 * 1024),
        )
}

internal data class AnalysisProgress(
    val stage: String,
    val entries: Int = 0,
    val current: String = "",
    val bytes: Long = 0,
)

internal data class FolderUsage(
    val path: String,
    val name: String,
    val bytes: Long,
    val files: Int,
    val unknown: Int,
)

internal data class AnalysisReport(
    val root: AnalysisNode,
    val files: List<AnalyzedFile>,
    val folders: Int,
    val issues: List<AnalysisIssue>,
    val issueCount: Int,
    val limited: Boolean,
    val stopped: Boolean,
    val buckets: List<FolderUsage>,
    val hiddenExcluded: Int,
    val linksSkipped: Int,
) {
    val knownBytes
        get() = files.fold(0L) { a, b -> analysisAdd(a, b.node.bytes ?: 0) }

    val unknown
        get() = files.count { it.node.bytes == null }

    val incomplete
        get() = limited || stopped || issueCount > 0
}

internal data class DuplicateGroup(val hash: String, val files: List<AnalyzedFile>) {
    val extraBytes
        get() = files.drop(1).fold(0L) { a, f -> analysisAdd(a, f.node.bytes ?: 0) }
}

internal data class DuplicateReport(
    val groups: List<DuplicateGroup>,
    val checked: Int,
    val bytes: Long,
    val skipped: Int,
    val issues: List<AnalysisIssue>,
    val stopped: Boolean,
    val observed: Map<String, FileChecksum> = emptyMap(),
    val scopeIncomplete: Boolean = false,
) {
    val incomplete
        get() = skipped > 0 || issues.isNotEmpty() || stopped || scopeIncomplete
}

internal interface AnalysisSource {
    fun read(path: String, control: ListingControl): AnalysisNode

    suspend fun children(
        path: String,
        control: ListingControl,
        visit: suspend (AnalysisNode) -> Boolean,
    ): DirectoryReply

    fun input(path: String, control: ListingControl): InputStream
}

internal fun analysisAdd(a: Long, b: Long): Long =
    if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b.coerceAtLeast(0)

internal enum class ChecksumAlgorithm(
    val title: String,
    val jca: String,
    val hexLength: Int,
    val suffix: String,
) {
    SHA256("SHA-256", "SHA-256", 64, "sha256"),
    SHA512("SHA-512", "SHA-512", 128, "sha512"),
    SHA1("SHA-1", "SHA-1", 40, "sha1"),
    MD5("MD5", "MD5", 32, "md5"),
}

internal data class FileChecksum(
    val node: AnalysisNode,
    val algorithm: ChecksumAlgorithm,
    val hex: String,
    val bytes: Long,
)

internal class AnalysisReadLimit :
    Exception("Content-read limit reached. Increase the limit and check again.")

internal fun fileChecksum(
    source: AnalysisSource,
    node: AnalysisNode,
    algorithm: ChecksumAlgorithm,
    control: ListingControl,
    maxBytes: Long = Long.MAX_VALUE,
    progress: (Long) -> Unit = {},
): FileChecksum {
    control.check()
    require(!node.folder && !node.link) { "Choose a regular file, not a folder or symbolic link." }
    check(source.read(node.path, control).revision == node.revision) {
        "The file changed. Refresh and try again."
    }
    if (node.bytes != null && node.bytes > maxBytes) throw AnalysisReadLimit()
    val hash = MessageDigest.getInstance(algorithm.jca)
    var count = 0L
    source.input(node.path, control).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            control.check()
            // Read one extra byte only to distinguish EOF from a truncated checksum.
            val remaining = maxBytes - count
            val n =
                input.read(
                    buffer,
                    0,
                    if (remaining < buffer.size) (remaining + 1).toInt() else buffer.size,
                )
            control.check()
            if (n < 0) break
            if (n == 0) continue
            if (n.toLong() > remaining) throw AnalysisReadLimit()
            hash.update(buffer, 0, n)
            count += n
            progress(count)
        }
    }
    check(node.bytes == null || count == node.bytes) {
        "The file length changed or the provider returned incomplete content."
    }
    check(source.read(node.path, control).revision == node.revision) {
        "The file changed while reading. No checksum was published."
    }
    control.check()
    return FileChecksum(
        node,
        algorithm,
        hash.digest().joinToString("") { "%02x".format(it) },
        count,
    )
}

internal fun expectedChecksum(text: String, algorithm: ChecksumAlgorithm): String {
    var value = text.trim().removePrefix("\\")
    val prefix = value.substringBefore(':', "")
    if (prefix.replace("-", "").equals(algorithm.title.replace("-", ""), true))
        value = value.substringAfter(':').trim()
    val first = value.substringBefore(' ').substringBefore('\t')
    val compact = value.filterNot { it.isWhitespace() || it == ':' }
    if (first.length == algorithm.hexLength && first.all { it in "0123456789abcdefABCDEF" }) {
        require('\n' !in value && '\r' !in value) {
            "Paste one checksum, not a multi-file manifest."
        }
    }
    val candidate =
        if (first.length == algorithm.hexLength && first.all { it in "0123456789abcdefABCDEF" })
            first
        else compact
    require(
        candidate.length == algorithm.hexLength && candidate.all { it in "0123456789abcdefABCDEF" },
    ) {
        "Enter ${algorithm.hexLength} hexadecimal characters for ${algorithm.title}."
    }
    return candidate.lowercase()
}

internal fun checksumLine(checksum: FileChecksum): String {
    val name = checksum.node.name
    val escape = name.any { it == '\\' || it == '\n' || it == '\r' }
    val safe = name.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    return (if (escape) "\\" else "") + checksum.hex + "  " + safe + "\n"
}

internal fun checksumFilename(checksum: FileChecksum): String {
    var name = checksum.node.name
    while (name.toByteArray(Charsets.UTF_8).size > 225) name =
        name.dropLast(if (name.last().isLowSurrogate() && name.length > 1) 2 else 1)
    return name + "." + checksum.algorithm.suffix
}

internal class FolderAnalyzer(private val source: AnalysisSource) {
    suspend fun scan(
        rootPath: String,
        includeHidden: Boolean,
        limits: AnalysisLimits,
        control: ListingControl,
        progress: (AnalysisProgress) -> Unit = {},
    ): AnalysisReport {
        val root = source.read(rootPath, control)
        require(root.folder && !root.link) { "Choose a real folder to analyze." }
        data class Pending(
            val node: AnalysisNode,
            val relative: String,
            val bucket: String,
            val depth: Int,
        )
        val pending = ArrayDeque<Pending>().apply { add(Pending(root, "", root.path, 0)) }
        val files = mutableListOf<AnalyzedFile>()
        val seen = mutableSetOf(root.objectId)
        val seenPaths = mutableSetOf(root.path)
        val issues = mutableListOf<AnalysisIssue>()
        val bucketNames = linkedMapOf(root.path to "Files in this folder")
        var issueCount = 0
        var folders = 0
        var visited = 0
        var hidden = 0
        var links = 0
        var limited = false
        var stopped = false
        fun issue(path: String, reason: String) {
            issueCount++
            if (issues.size < 100) issues += AnalysisIssue(path, reason)
        }
        try {
            while (pending.isNotEmpty() && !limited) {
                control.check()
                val item = pending.removeFirst()
                try {
                    check(source.read(item.node.path, control).revision == item.node.revision) {
                        "Folder changed during analysis. Refresh for a current result."
                    }
                    val reply =
                        source.children(item.node.path, control) { child ->
                            control.check()
                            if (visited >= limits.entries) {
                                limited = true
                                false
                            } else {
                                visited++
                                progress(AnalysisProgress("Scanning folders", visited, child.name))
                                when {
                                    child.problem != null -> issue(child.path, child.problem)
                                    child.name.startsWith(".droidfinder-") -> Unit
                                    !includeHidden && child.name.startsWith('.') -> hidden++
                                    child.link -> links++
                                    child.name.isBlank() ||
                                        child.name in setOf(".", "..") ||
                                        '/' in child.name ||
                                        '\u0000' in child.name ->
                                        issue(
                                            child.path,
                                            "The provider returned an ambiguous name.",
                                        )
                                    !seenPaths.add(child.path) ->
                                        issue(child.path, "A repeated document entry was skipped.")
                                    !seen.add(child.objectId) && child.folder ->
                                        issue(
                                            child.path,
                                            "A repeated folder or provider cycle was skipped.",
                                        )
                                    child.folder -> {
                                        folders++
                                        val bucket =
                                            if (item.depth == 0) child.path else item.bucket
                                        if (item.depth == 0) bucketNames[bucket] = child.name
                                        if (item.depth >= 128)
                                            issue(child.path, "Folder depth limit reached.")
                                        else
                                            pending +=
                                                Pending(
                                                    child,
                                                    if (item.relative.isEmpty()) child.name
                                                    else "${item.relative}/${child.name}",
                                                    bucket,
                                                    item.depth + 1,
                                                )
                                    }
                                    else ->
                                        files +=
                                            AnalyzedFile(
                                                child,
                                                if (item.relative.isEmpty()) child.name
                                                else "${item.relative}/${child.name}",
                                                item.bucket,
                                            )
                                }
                                true
                            }
                        }
                    if (reply.loading)
                        issue(item.node.path, "The provider is still loading this folder.")
                    reply.error?.let { issue(item.node.path, it) }
                    reply.info?.let { issue(item.node.path, it) }
                    if (source.read(item.node.path, control).revision != item.node.revision)
                        issue(
                            item.node.path,
                            "Folder changed during analysis. Refresh for a current result.",
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    control.check()
                    issue(item.node.path, e.message ?: "Folder unavailable")
                }
            }
        } catch (_: CancellationException) {
            stopped = true
        }
        val buckets =
            files
                .groupBy { it.bucket }
                .map { (path, members) ->
                    FolderUsage(
                        path,
                        bucketNames[path] ?: "Folder",
                        members.fold(0L) { a, f -> analysisAdd(a, f.node.bytes ?: 0) },
                        members.size,
                        members.count { it.node.bytes == null },
                    )
                }
                .sortedByDescending { it.bytes }
        return AnalysisReport(
            root,
            files.toList(),
            folders,
            issues.toList(),
            issueCount,
            limited,
            stopped,
            buckets,
            hidden,
            links,
        )
    }

    fun duplicates(
        report: AnalysisReport,
        limits: AnalysisLimits,
        control: ListingControl,
        progress: (AnalysisProgress) -> Unit = {},
    ): DuplicateReport {
        // Same-inode aliases are logical directory entries, not extra physical copies.
        val unique =
            report.files
                .filter {
                    it.node.bytes == null || it.node.observedHash != null || it.node.bytes > 0
                }
                .distinctBy { it.node.objectId }
        // An unknown-size file can match any known-size singleton, so those must be read too.
        val candidates =
            if (unique.any { it.node.bytes == null || it.node.observedHash != null }) unique
            else unique.groupBy { it.node.bytes }.values.filter { it.size > 1 }.flatten()
        val hashes = linkedMapOf<Pair<Long, String>, MutableList<AnalyzedFile>>()
        val observed = mutableMapOf<String, FileChecksum>()
        val issues = mutableListOf<AnalysisIssue>()
        var bytes = 0L
        var checked = 0
        var skipped = 0
        var stopped = false
        try {
            for (file in candidates) {
                control.check()
                // Observed sizes remain snapshots; they are not new metadata from the provider.
                val readNode =
                    if (file.node.observedHash != null)
                        file.node.copy(bytes = null, observedHash = null)
                    else file.node
                if (limits.hashBytes <= bytes || (readNode.bytes ?: 0) > limits.hashBytes - bytes) {
                    skipped++
                    continue
                }
                val before = bytes
                try {
                    val sum =
                        fileChecksum(
                            source,
                            readNode,
                            ChecksumAlgorithm.SHA256,
                            control,
                            limits.hashBytes - bytes,
                        ) { n ->
                            bytes = before + n
                            progress(
                                AnalysisProgress(
                                    "Checking duplicate contents",
                                    checked,
                                    file.relative,
                                    bytes,
                                ),
                            )
                        }
                    checked++
                    if (readNode.bytes == null) observed[file.node.path] = sum
                    if (sum.bytes > 0)
                        hashes
                            .getOrPut(sum.bytes to sum.hex) { mutableListOf() }
                            .add(
                                file.copy(
                                    node =
                                        file.node.copy(
                                            bytes = sum.bytes,
                                            observedHash =
                                                if (readNode.bytes == null) sum.hex
                                                else file.node.observedHash,
                                        ),
                                ),
                            )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    control.check()
                    if (issues.size < 100)
                        issues += AnalysisIssue(file.node.path, e.message ?: "Couldn't check file")
                    skipped++
                }
            }
        } catch (_: CancellationException) {
            stopped = true
            skipped += candidates.size - checked - skipped
        }
        val groups =
            hashes.entries
                .filter { it.value.size > 1 }
                .map { (key, members) ->
                    DuplicateGroup(key.second, members.sortedBy { it.relative })
                }
                .sortedByDescending { it.extraBytes }
        return DuplicateReport(
            groups,
            checked,
            bytes,
            skipped,
            issues,
            stopped,
            observed,
            report.incomplete,
        )
    }
}
