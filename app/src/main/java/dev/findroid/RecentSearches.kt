package dev.findroid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import java.util.UUID

internal const val RECENT_SEARCH_LIMIT = 20
internal const val RECENT_SEARCH_KEY = "recentSearchesV1"
internal const val REMEMBER_SEARCHES_KEY = "rememberRecentSearches"

/** Criteria and references only; result entries and content snippets never belong in history. */
internal data class RecentSearch(val id: String, val root: String, val rules: SearchRules, val usedAt: Long)

internal fun recentSearchRootSupported(root: String): Boolean {
    if (root.isBlank() || root.length > 8192 || root.any(Char::isISOControl)) return false
    return when {
        root.startsWith("/") -> true
        root.startsWith("content://") -> runCatching { !URI(root).authority.isNullOrBlank() }.getOrDefault(false)
        root in setOf("@recent", "@trash") -> true
        root.startsWith("@tag:") -> root.removePrefix("@tag:").isNotBlank()
        root.startsWith("@collection:") -> root.removePrefix("@collection:").isNotBlank()
        else -> false
    }
}

internal fun SearchRules.historyCopy(): SearchRules = copy(
    nameTerms = nameTerms.toList(), tags = tags.toSet(), excludedFolderNames = excludedFolderNames.toSet(),
    excludedLocations = excludedLocations.toList(), locations = checkedSearchLocations(locations),
)

internal fun RecentSearch.checked(): RecentSearch = apply {
    require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) { "Invalid recent search identity." }
    require(recentSearchRootSupported(root) && usedAt >= 0) { "Invalid recent search location or time." }
    checkedSearchLocations(rules.locations)
    rules.ruleGroup?.let(::checkedRuleGroup)
    require(rules.active) { "An empty search doesn't need a history entry." }
    require(rules.days >= 0 && rules.minimumBytes >= 0 && rules.maximumBytes >= 0 &&
        (rules.minimumBytes == 0L || rules.maximumBytes == 0L || rules.minimumBytes <= rules.maximumBytes)) { "Invalid recent search bounds." }
    require(rules.nameTerms.size <= 100 && rules.filterTags.size <= 256 && rules.excludedFolderNames.size <= 100 && rules.excludedLocations.size <= 100) { "Too many recent search criteria." }
    val fields = listOf(rules.name, rules.kind, rules.tag, rules.content) + rules.nameTerms + rules.filterTags + rules.excludedFolderNames
    require(fields.all { it.length <= 4096 } && rules.excludedLocations.all {
        it.path.length <= 8192 && (it.path.startsWith("/") || it.path.startsWith("content://") && recentSearchRootSupported(it.path))
    }) { "This search is too large for recent searches. Save it as a Smart Folder." }
}

internal fun recentSearchesAfterUse(previous: List<RecentSearch>, root: String, rules: SearchRules, now: Long, id: () -> String): List<RecentSearch> {
    if (!rules.active || !recentSearchRootSupported(root)) return previous
    val copy = rules.historyCopy()
    val existing = previous.firstOrNull { it.root == root && it.rules == copy }
    val item = RecentSearch(existing?.id ?: id(), root, copy, now).checked()
    return (listOf(item) + previous.filterNot { it.root == root && it.rules == copy }).take(RECENT_SEARCH_LIMIT)
}

internal val RecentSearch.title: String get() = when {
    rules.name.isNotBlank() -> rules.name
    rules.nameTerms.isNotEmpty() -> rules.nameTerms.joinToString(" · ")
    rules.content.isNotBlank() -> "Text: ${rules.content}"
    rules.choiceDescription.isNotBlank() -> rules.choiceDescription
    rules.filterTags.isNotEmpty() -> "Tags: ${rules.filterTags.sorted().joinToString(", ")}"
    rules.ruleGroup != null -> "Rule groups · ${rules.ruleGroup.join.title}"
    else -> "Filtered search"
}

internal val RecentSearch.summary: String get() = buildList {
    if (rules.name.isNotBlank()) add("Name contains “${rules.name}”")
    if (rules.nameTerms.isNotEmpty()) add("Additional names: ${rules.nameTerms.joinToString(", ")}")
    if (rules.choiceDescription.isNotBlank()) add(rules.choiceDescription)
    if (rules.filterTags.isNotEmpty()) add("${rules.tagMatch.title} tags: ${rules.filterTags.sorted().joinToString(", ")}")
    if (rules.content.isNotBlank()) add("Text contains “${rules.content}”")
    rules.ruleGroup?.let { add("Rule groups: ${it.summary}") }
    add(rules.scopeDescription)
}.joinToString(" · ")

/** A typing suggestion applies here and keeps a bounded, Unicode-safe description. */
internal val RecentSearch.suggestionSummary: String
    get() {
        val text = copy(rules = rules.copy(locations = emptyList())).summary
        if (text.codePointCount(0, text.length) <= 360) return text
        return text.substring(0, text.offsetByCodePoints(0, 359)) + "…"
    }

internal fun recentSearchMatches(search: RecentSearch, query: String, includeRoot: Boolean = true): Boolean {
    val criteria = if (includeRoot) search else search.copy(rules = search.rules.copy(locations = emptyList()))
    val fields = criteria.title + " " + criteria.summary + if (includeRoot) " " + search.root + " " + search.rules.locations.joinToString(" ") else ""
    return query.trim().split(Regex("\\s+")).filter(String::isNotBlank).all { fields.contains(it, ignoreCase = true) }
}

internal fun recentSearchSuggestions(searches: List<RecentSearch>, query: String): List<RecentSearch> =
    if (query.isBlank()) emptyList() else searches.filter { recentSearchMatches(it, query, includeRoot = false) }
        .distinctBy { it.rules.copy(locations = emptyList()) }.take(3)

internal fun remapRecentSearches(searches: List<RecentSearch>, routes: List<Pair<String, String>>): List<RecentSearch> {
    val tags = routes.filter { it.first.startsWith("@tag:") && it.second.startsWith("@tag:") }
        .associate { it.first.removePrefix("@tag:") to it.second.removePrefix("@tag:") }
    return searches.map { item ->
        val root = if (item.root.startsWith("/")) remapPath(item.root, routes)
            else routes.firstOrNull { it.first == item.root }?.second ?: item.root
        item.copy(root = root, rules = item.rules.remapTags(tags).remapRoutes(routes))
    }.distinctBy { it.root to it.rules }
}

internal interface RecentSearchPersistence {
    fun enabled(): Boolean
    fun load(): List<RecentSearch>
    fun save(searches: List<RecentSearch>): List<RecentSearch>
    fun setEnabled(enabled: Boolean)
}

internal data class RecentSearchState(val enabled: Boolean, val searches: List<RecentSearch>, val notice: String? = null)

/** Shared by every pane/window. Destructive changes invalidate drafts and pending reuse. */
internal class RecentSearchHistory(
    private val persistence: RecentSearchPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) {
    private val enabledLoad = runCatching { persistence.enabled() }
    private val entriesLoad = runCatching {
        persistence.load().map { it.checked().copy(rules = it.rules.historyCopy()) }.also {
            require(it.size <= RECENT_SEARCH_LIMIT && it.map(RecentSearch::id).distinct().size == it.size)
        }
    }
    private var damaged = enabledLoad.isFailure || entriesLoad.isFailure
    var generation = 0L
        private set
    var state by mutableStateOf(RecentSearchState(enabledLoad.getOrDefault(false), entriesLoad.getOrDefault(emptyList()),
        if (damaged) "Recent searches couldn't be read. Clear the list to start again." else null))
        private set

    fun remember(root: String, rules: SearchRules): Boolean {
        if (!state.enabled || damaged || !rules.active || !recentSearchRootSupported(root)) return false
        return save { recentSearchesAfterUse(state.searches, root, rules, clock(), nextId) }
    }

    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled == state.enabled && !damaged) return true
        persistence.setEnabled(enabled)
        generation++
        state = state.copy(enabled = enabled, notice = if (damaged) state.notice else null)
        true
    }.getOrElse {
        state = state.copy(notice = it.message ?: "Couldn't save the history preference. Try again.")
        false
    }

    fun clear(): Boolean = runCatching {
        // Repair a malformed preference as well as the entries, preserving a paused state.
        persistence.setEnabled(state.enabled)
        save { emptyList() }.also { if (it) { damaged = false; generation++ } }
    }.getOrElse {
        state = state.copy(notice = it.message ?: "Couldn't clear recent searches. Try again.")
        false
    }

    fun remove(id: String): Boolean {
        if (damaged || state.searches.none { it.id == id }) return false
        return save { state.searches.filterNot { it.id == id } }.also { if (it) generation++ }
    }

    fun remap(routes: List<Pair<String, String>>) {
        if (damaged || routes.isEmpty()) return
        val mapped = remapRecentSearches(state.searches, routes)
        if (mapped != state.searches && save { mapped }) generation++
    }

    private fun save(change: () -> List<RecentSearch>): Boolean = runCatching {
        val updated = persistence.save(change()).toList()
        state = state.copy(searches = updated, notice = null)
        true
    }.getOrElse {
        state = state.copy(notice = it.message ?: "Couldn't save recent searches. Try again.")
        false
    }
}
