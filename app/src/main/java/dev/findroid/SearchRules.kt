package dev.findroid

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

enum class TagMatch(val title: String, val description: String) {
    ALL("All", "Match every selected tag"),
    ANY("Any", "Match at least one selected tag"),
    NONE("None", "Exclude items with any selected tag"),
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
    val tags: Set<String> = emptySet(),
    val tagMatch: TagMatch = TagMatch.ALL,
    val includeSubfolders: Boolean = true,
    val excludedFolderNames: Set<String> = emptySet(),
    val excludedLocations: List<SearchExclusion> = emptyList(),
    val date: SearchDate = SearchDate.ANY,
    val size: SearchSize = SearchSize.ANY,
) {
    private val contentPattern by lazy { StreamingText(content) }

    val filterTags: Set<String>
        get() = if (tag.isBlank()) tags else tags + tag

    val scoped: Boolean
        get() = !includeSubfolders || excludedFolderNames.isNotEmpty() || excludedLocations.isNotEmpty()

    val scopeDescription: String
        get() = buildList {
            add(if (includeSubfolders) "Folder and subfolders" else "This folder only")
            if (excludedFolderNames.isNotEmpty()) add("${excludedFolderNames.size} folder ${if (excludedFolderNames.size == 1) "name" else "names"} excluded")
            if (excludedLocations.isNotEmpty()) add("${excludedLocations.size} exact ${if (excludedLocations.size == 1) "location" else "locations"} excluded")
        }.joinToString(" · ")

    val choiceDescription: String
        get() = buildList {
            if (kind != "Any") add(if (kind == "Image") "Images" else kind)
            if (date != SearchDate.ANY) add(date.title)
            if (days > 0) add(if (days == 1) "Past 24 hours" else "Past $days days")
            if (this@SearchRules.size != SearchSize.ANY) add(this@SearchRules.size.title)
            if (minimumBytes > 0) add("At least $minimumBytes bytes")
            if (maximumBytes > 0) add("At most $maximumBytes bytes")
        }.joinToString(" · ")

    val hasSizeFilter get() = minimumBytes > 0 || maximumBytes > 0 || size != SearchSize.ANY

    fun remapLocations(path: (String) -> String): SearchRules =
        copy(excludedLocations = excludedLocations.groupBy { path(it.path) }.map { (target, values) ->
            SearchExclusion(target, values.any { it.folder })
        })

    fun excludesFolder(entry: Entry): Boolean = entry.folder && entry.name in excludedFolderNames

    fun remapRoutes(routes: List<Pair<String, String>>): SearchRules = remapLocations { path ->
        if (path.startsWith("content://")) routes.firstOrNull { it.first == path }?.second ?: path
        else remapPath(path, routes)
    }

    fun remapTags(replacements: Map<String, String>): SearchRules =
        copy(
            tag = replacements[tag] ?: tag,
            tags = tags.map { replacements[it] ?: it }.toSet(),
        )

    val active
        get() =
            name.isNotBlank() ||
                kind != "Any" ||
                filterTags.isNotEmpty() ||
                days > 0 ||
                date != SearchDate.ANY ||
                size != SearchSize.ANY ||
                minimumBytes > 0 ||
                maximumBytes > 0 ||
                content.isNotBlank() ||
                nameTerms.isNotEmpty() ||
                scoped

    fun matches(
        entry: Entry,
        tags: Set<String>,
        now: Long = System.currentTimeMillis(),
        read: ((Entry) -> java.io.InputStream)? = null,
        check: () -> Unit = {},
        contentIssue: (String) -> Unit = {},
        calendar: SearchCalendar = SearchCalendar(now),
    ): Boolean = evidence(entry, tags, now, read, check, contentIssue, calendar) != null

    /** A null result rejects admission. Evidence is transient and never enters saved rules. */
    fun evidence(
        entry: Entry,
        tags: Set<String>,
        now: Long = System.currentTimeMillis(),
        read: ((Entry) -> java.io.InputStream)? = null,
        check: () -> Unit = {},
        contentIssue: (String) -> Unit = {},
        calendar: SearchCalendar = SearchCalendar(now),
    ): SearchEvidence? {
        check()
        if (excludesFolder(entry)) return null
        if (excludedLocations.any { it.path == entry.path ||
                entry.path.startsWith("/") && it.path.startsWith("/") &&
                    (organizationKey(entry.path) == organizationKey(it.path) ||
                        it.folder && localSearchAncestry(entry.path, it.path) == SearchAncestry.INSIDE) }) return null
        if (name.isNotBlank() && !entry.name.contains(name, true)) return null
        if (nameTerms.any { !entry.name.contains(it, true) }) return null
        if (kind != "Any" && entry.kind != kind) return null
        val selectedTags = filterTags
        if (selectedTags.isNotEmpty()) {
            val matchesTags = when (tagMatch) {
                TagMatch.ALL -> tags.containsAll(selectedTags)
                TagMatch.ANY -> selectedTags.any { it in tags }
                TagMatch.NONE -> selectedTags.none { it in tags }
            }
            if (!matchesTags) return null
        }
        if (days > 0 && entry.modified < calendar.now - days * 86_400_000L) return null
        if (date.bounds(calendar)?.let { entry.modified !in it } == true) return null
        if (hasSizeFilter && (entry.folder || !entry.sizeKnown)) return null
        if (minimumBytes > 0 && entry.size < minimumBytes || maximumBytes > 0 && entry.size > maximumBytes) return null
        if (size.minimum > 0 && entry.size < size.minimum || size.maximum > 0 && entry.size > size.maximum) return null
        val snippet = if (content.isNotBlank()) {
            if (entry.folder || entry.kind != "Text document") return null
            try {
                (read?.invoke(entry) ?: entry.file.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                    contentPattern.find(reader, check) { contentIssue("Binary content in a text file wasn't searched") }
                } ?: return null
            } catch (error: Exception) {
                check()
                if (error is kotlinx.coroutines.CancellationException) throw error
                contentIssue(error.message ?: "Couldn't search this file's contents")
                return null
            }
        } else null
        val terms = (listOf(name) + nameTerms).filter(String::isNotBlank)
        return SearchEvidence(
            nameRanges = matchRanges(entry.name, terms),
            reasons = buildList {
                if (terms.isNotEmpty()) add("Name")
                if (selectedTags.isNotEmpty()) {
                    val names = (if (tagMatch == TagMatch.NONE) selectedTags else selectedTags.intersect(tags)).sorted()
                    add((if (tagMatch == TagMatch.NONE) "Without tags: " else "Tags: ") +
                        names.take(3).joinToString(", ") { it.take(60) } + if (names.size > 3) "…" else "")
                }
                if (kind != "Any") add("Kind: $kind")
                if (date != SearchDate.ANY) add("Modified: ${date.title}")
                if (days > 0) add("Modified: past $days ${if (days == 1) "day" else "days"}")
                if (this@SearchRules.size != SearchSize.ANY) add("Size: ${this@SearchRules.size.title}")
                if (minimumBytes > 0 || maximumBytes > 0) add("Size: ${entry.displaySize}")
                if (snippet != null) add("Text contents")
            },
            snippet = snippet,
        )
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
