package dev.findroid

import org.json.JSONArray
import org.json.JSONObject

/** A path anchor survives insertions/sorting; the index is a fallback if that item disappeared. */
data class ViewportAnchor(val path: String? = null, val index: Int = 0, val offset: Int = 0)

data class ViewportKey(val location: String, val view: String, val search: String = "")

/** The scan budget belongs to a folder/filter scope, independent of its visual layout. */
data class ListingScope(
    val location: String,
    val rules: SearchRules = SearchRules(),
    val hidden: Boolean = false,
    val root: String? = null,
) {
    fun remap(routes: List<Pair<String, String>>): ListingScope {
        val tags =
            routes
                .filter { it.first.startsWith("@tag:") && it.second.startsWith("@tag:") }
                .associate { it.first.removePrefix("@tag:") to it.second.removePrefix("@tag:") }
        return copy(
            location = remapPath(location, routes),
            root = root?.let { remapPath(it, routes) },
            rules = rules.remapTags(tags).remapRoutes(routes),
        )
    }
}

data class TabNavigation(
    val selected: Set<String> = emptySet(),
    val lead: String? = null,
    val anchor: String? = null,
    val expanded: Set<String> = emptySet(),
    val multiple: Boolean = false,
    val positions: Map<ViewportKey, ViewportAnchor> = emptyMap(),
    val budgets: Map<ListingScope, ListingBudget> = emptyMap(),
) {
    fun remap(routes: List<Pair<String, String>>): TabNavigation {
        fun path(p: String) = remapPath(p, routes)
        return copy(
            selected = selected.map(::path).toSet(),
            lead = lead?.let(::path),
            anchor = anchor?.let(::path),
            expanded = expanded.map(::path).toSet(),
            budgets = budgets.mapKeys { (key, _) -> key.remap(routes) },
            positions =
                positions
                    .map { (key, value) ->
                        key.copy(location = path(key.location)) to
                            value.copy(path = value.path?.let(::path))
                    }
                    .toMap(),
        )
    }
}

data class FinderTab(
    val history: List<String>,
    val index: Int = history.lastIndex,
    val id: String = java.util.UUID.randomUUID().toString(),
    val search: String = "",
    val rules: SearchRules = SearchRules(),
    val pinned: Boolean = false,
    val navigation: TabNavigation = TabNavigation(),
) {
    val location
        get() = history[index]

    fun remap(routes: List<Pair<String, String>>): FinderTab {
        val tags =
            routes
                .filter { it.first.startsWith("@tag:") && it.second.startsWith("@tag:") }
                .associate { it.first.removePrefix("@tag:") to it.second.removePrefix("@tag:") }
        return copy(
            history = history.map { remapPath(it, routes) },
            rules = rules.remapTags(tags).remapRoutes(routes),
            navigation = navigation.remap(routes),
        )
    }
}

/** Backwards compatible with legacy arrays of paths and the 0.8 history/query objects. */
object NavigationJson {
    fun rules(r: SearchRules): JSONObject =
        JSONObject()
            .put("name", r.name)
            .put("kind", r.kind)
            .put("tag", r.tag)
            .put("days", r.days)
            .put("min", r.minimumBytes)
            .put("max", r.maximumBytes)
            .put("content", r.content)
            .put("terms", JSONArray(r.nameTerms))
            .put("tags", JSONArray(r.tags.sorted()))
            .put("tagMatch", r.tagMatch.name)
            .put("includeSubfolders", r.includeSubfolders)
            .put("excludedFolderNames", JSONArray(r.excludedFolderNames.sorted()))
            .apply {
                // Omit default additions so earlier version-1 setup backups remain canonical.
                if (r.excludedLocations.isNotEmpty()) put("excludedLocations", JSONArray(r.excludedLocations.map {
                    JSONObject().put("path", it.path).put("folder", it.folder)
                }))
                if (r.date != SearchDate.ANY) put("date", r.date.name)
                if (r.size != SearchSize.ANY) put("size", r.size.name)
            }

    fun rules(r: JSONObject?): SearchRules =
        if (r == null) SearchRules()
        else
            SearchRules(
                name = r.optString("name"),
                kind = r.optString("kind", "Any"),
                tag = r.optString("tag"),
                days = r.optInt("days"),
                minimumBytes = r.optLong("min"),
                maximumBytes = r.optLong("max"),
                content = r.optString("content"),
                nameTerms = strings(r.optJSONArray("terms"), 100),
                tags = strings(r.optJSONArray("tags"), Int.MAX_VALUE).toSet(),
                tagMatch = runCatching { TagMatch.valueOf(r.optString("tagMatch", "ALL")) }.getOrDefault(TagMatch.ALL),
                includeSubfolders = r.optBoolean("includeSubfolders", true),
                excludedFolderNames = strings(r.optJSONArray("excludedFolderNames"), Int.MAX_VALUE).toSet(),
                excludedLocations = if (!r.has("excludedLocations")) emptyList() else r.getJSONArray("excludedLocations").let { values ->
                    require(values.length() <= 100) { "Keep at most 100 exact search exclusions." }
                    List(values.length()) { index ->
                        val value = values.getJSONObject(index)
                        require(value.get("path") is String && value.get("folder") is Boolean) { "Invalid search exclusion." }
                        SearchExclusion(value.getString("path"), value.getBoolean("folder"))
                    }
                },
                date = if (!r.has("date")) SearchDate.ANY else SearchDate.valueOf(r.getString("date")),
                size = if (!r.has("size")) SearchSize.ANY else SearchSize.valueOf(r.getString("size")),
            )

    private fun strings(a: JSONArray?, max: Int): List<String> =
        if (a == null) emptyList()
        else
            (0 until minOf(a.length(), max)).mapNotNull {
                a.optString(it).takeIf(String::isNotEmpty)
            }

    fun tab(tab: FinderTab): JSONObject {
        val n = tab.navigation
        return JSONObject()
            .put("id", tab.id)
            .put("history", JSONArray(tab.history))
            .put("index", tab.index)
            .put("query", tab.search)
            .put("rules", rules(tab.rules))
            .put("pinned", tab.pinned)
            .put(
                "navigation",
                JSONObject()
                    .put("selected", JSONArray(n.selected))
                    .put("lead", n.lead)
                    .put("anchor", n.anchor)
                    .put("expanded", JSONArray(n.expanded))
                    .put("multiple", n.multiple)
                    .put(
                        "budgets",
                        JSONArray(
                            n.budgets.entries.toList().takeLast(32).map { (k, v) ->
                                JSONObject()
                                    .put("location", k.location)
                                    .put("rules", rules(k.rules))
                                    .put("hidden", k.hidden)
                                    .put("root", k.root)
                                    .put("results", v.results)
                                    .put("scanned", v.scanned)
                            },
                        ),
                    )
                    .put(
                        "positions",
                        JSONArray(
                            n.positions.map { (k, v) ->
                                JSONObject()
                                    .put("location", k.location)
                                    .put("view", k.view)
                                    .put("search", k.search)
                                    .put("path", v.path)
                                    .put("index", v.index)
                                    .put("offset", v.offset)
                            },
                        ),
                    ),
            )
    }

    fun tabs(value: JSONArray?, fallback: String): List<FinderTab> {
        if (value == null) return listOf(FinderTab(listOf(fallback)))
        return (0 until minOf(value.length(), 64))
            .mapNotNull { i ->
                runCatching {
                    val v = value.optJSONObject(i)
                    if (v == null) FinderTab(listOf(value.getString(i)))
                    else {
                        val history =
                            strings(v.optJSONArray("history"), 100).ifEmpty { listOf(fallback) }
                        val n = v.optJSONObject("navigation")
                        val positions = n?.optJSONArray("positions")
                        val points =
                            (0 until minOf(positions?.length() ?: 0, 32))
                                .mapNotNull { j ->
                                    val p = positions!!.optJSONObject(j) ?: return@mapNotNull null
                                    ViewportKey(
                                        p.optString("location"),
                                        p.optString("view"),
                                        p.optString("search"),
                                    ) to
                                        ViewportAnchor(
                                            p.optString("path").takeIf(String::isNotEmpty),
                                            p.optInt("index").coerceAtLeast(0),
                                            p.optInt("offset").coerceIn(0, 100_000),
                                        )
                                }
                                .toMap()
                        val rawBudgets = n?.optJSONArray("budgets")
                        val budgets =
                            (0 until minOf(rawBudgets?.length() ?: 0, 32))
                                .mapNotNull { j ->
                                    val p = rawBudgets!!.optJSONObject(j) ?: return@mapNotNull null
                                    ListingScope(
                                        p.optString("location"),
                                        rules(p.optJSONObject("rules")),
                                        p.optBoolean("hidden"),
                                        p.optString("root").takeIf(String::isNotEmpty),
                                    ) to
                                        ListingBudget(
                                                p.optInt("results", 20_000),
                                                p.optInt("scanned", 50_000),
                                            )
                                            .normalized()
                                }
                                .toMap()
                        FinderTab(
                            history,
                            v.optInt("index", history.lastIndex).coerceIn(history.indices),
                            v.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            v.optString("query"),
                            rules(v.optJSONObject("rules")),
                            v.optBoolean("pinned"),
                            TabNavigation(
                                strings(n?.optJSONArray("selected"), 50_000).toSet(),
                                n?.optString("lead")?.takeIf(String::isNotEmpty),
                                n?.optString("anchor")?.takeIf(String::isNotEmpty),
                                strings(n?.optJSONArray("expanded"), 1000).toSet(),
                                n?.optBoolean("multiple") ?: false,
                                points,
                                budgets,
                            ),
                        )
                    }
                }
                    .getOrNull()
            }
            .distinctBy { it.id }
            .sortedByDescending { it.pinned }
            .ifEmpty { listOf(FinderTab(listOf(fallback))) }
    }
}
