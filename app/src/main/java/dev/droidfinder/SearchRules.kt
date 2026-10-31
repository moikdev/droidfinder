package dev.droidfinder

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class GroupBy(val title: String) {
    NONE("None"),
    KIND("Kind"),
    MODIFIED("Date modified"),
    TAG("Tags"),
    SIZE("Size"),
}

data class SearchRules(
    val name: String = "",
    val kind: String = "Any",
    val tag: String = "",
    val days: Int = 0,
    val minimumBytes: Long = 0,
    val maximumBytes: Long = 0,
    val content: String = "",
    val nameTerms: List<String> = emptyList(),
) {
    private val contentPattern by lazy { StreamingText(content) }

    val active
        get() =
            name.isNotBlank() ||
                kind != "Any" ||
                tag.isNotBlank() ||
                days > 0 ||
                minimumBytes > 0 ||
                maximumBytes > 0 ||
                content.isNotBlank() ||
                nameTerms.isNotEmpty()

    fun matches(
        entry: Entry,
        tags: Set<String>,
        now: Long = System.currentTimeMillis(),
        read: ((Entry) -> java.io.InputStream)? = null,
        check: () -> Unit = {},
        contentIssue: (String) -> Unit = {},
    ): Boolean {
        check()
        if (name.isNotBlank() && !entry.name.contains(name, true)) return false
        if (nameTerms.any { !entry.name.contains(it, true) }) return false
        if (kind != "Any" && entry.kind != kind) return false
        if (tag.isNotBlank() && tag !in tags) return false
        if (days > 0 && entry.modified < now - days * 86_400_000L) return false
        if (minimumBytes > 0 && (entry.folder || entry.size < minimumBytes)) return false
        if (maximumBytes > 0 && (entry.folder || entry.size > maximumBytes)) return false
        if (content.isNotBlank()) {
            if (entry.folder || entry.kind != "Text document") return false
            return try {
                (read?.invoke(entry) ?: entry.file.inputStream())
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader ->
                        contentPattern.contains(reader, check) {
                            contentIssue("Binary content in a text file wasn't searched")
                        }
                    }
            } catch (error: Exception) {
                check()
                if (error is kotlinx.coroutines.CancellationException) throw error
                contentIssue(error.message ?: "Couldn't search this file's contents")
                false
            }
        }
        return true
    }
}

data class SmartFolder(val id: String, val name: String, val root: String, val rules: SearchRules)

fun groupLabel(
    entry: Entry,
    group: GroupBy,
    tags: Set<String>,
    today: LocalDate = LocalDate.now(),
): String =
    when (group) {
        GroupBy.NONE -> ""
        GroupBy.KIND -> entry.kind
        GroupBy.TAG -> tags.sorted().joinToString(", ").ifEmpty { "Untagged" }
        GroupBy.SIZE ->
            when {
                entry.folder -> "Folders"
                entry.size < 1024 -> "Less than 1 KB"
                entry.size < 1024 * 1024 -> "1 KB to 1 MB"
                entry.size < 1024L * 1024 * 1024 -> "1 MB to 1 GB"
                else -> "1 GB or more"
            }
        GroupBy.MODIFIED -> {
            val date =
                Instant.ofEpochMilli(entry.modified).atZone(ZoneId.systemDefault()).toLocalDate()
            when {
                date >= today -> "Today"
                date == today.minusDays(1) -> "Yesterday"
                date >= today.minusDays(7) -> "Previous 7 days"
                date >= today.minusDays(30) -> "Previous 30 days"
                else -> "Earlier"
            }
        }
    }

data class FolderStats(val files: Int, val folders: Int, val bytes: Long, val limited: Boolean)

fun folderStats(folder: File, limit: Int = 100_000): FolderStats {
    var files = 0
    var folders = 0
    var bytes = 0L
    val pending = ArrayDeque<File>()
    pending.add(folder)
    while (pending.isNotEmpty() && files + folders < limit) {
        val current = pending.removeFirst()
        current.listFiles()?.forEach { child ->
            if (!java.nio.file.Files.isSymbolicLink(child.toPath()) && files + folders < limit) {
                if (child.isDirectory) {
                    folders++
                    pending.add(child)
                } else {
                    files++
                    bytes += child.length()
                }
            }
        }
    }
    return FolderStats(files, folders, bytes, pending.isNotEmpty() || files + folders >= limit)
}
