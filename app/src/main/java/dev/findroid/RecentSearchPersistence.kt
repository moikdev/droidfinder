package dev.findroid

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** History is deliberately outside SetupTransaction's export/restore preference allowlist. */
internal class PreferencesRecentSearchPersistence(private val prefs: SharedPreferences) : RecentSearchPersistence {
    override fun enabled() = prefs.getBoolean(REMEMBER_SEARCHES_KEY, true)
    override fun load() = prefs.getString(RECENT_SEARCH_KEY, null)?.let(RecentSearchJson::decode).orEmpty()

    @android.annotation.SuppressLint("UseKtx") // Report failed privacy/history writes before accepting UI state.
    override fun setEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean(REMEMBER_SEARCHES_KEY, enabled).commit()) { "Couldn't save the history preference. Try again." }
    }

    @android.annotation.SuppressLint("UseKtx") // Clear/remove must check the durable write result.
    override fun save(searches: List<RecentSearch>): List<RecentSearch> {
        var values = searches.take(RECENT_SEARCH_LIMIT)
        values.forEach { require(RecentSearchJson.entry(it).toString().toByteArray(Charsets.UTF_8).size <= RecentSearchJson.ENTRY_BYTES) { "This search is too large for recent searches. Save it as a Smart Folder." } }
        var text = RecentSearchJson.encode(values)
        while (text.toByteArray(Charsets.UTF_8).size > RecentSearchJson.TOTAL_BYTES && values.size > 1) {
            values = values.dropLast(1)
            text = RecentSearchJson.encode(values)
        }
        check(prefs.edit().putString(RECENT_SEARCH_KEY, text).commit()) { "Couldn't save recent searches. Try again." }
        return values
    }
}

internal object RecentSearchJson {
    const val TOTAL_BYTES = 256 * 1024
    const val ENTRY_BYTES = 64 * 1024
    fun entry(value: RecentSearch): JSONObject = value.checked().let {
        JSONObject().put("id", it.id).put("root", it.root).put("usedAt", it.usedAt).put("rules", NavigationJson.rules(it.rules))
    }
    fun encode(values: List<RecentSearch>): String {
        require(values.size <= RECENT_SEARCH_LIMIT)
        return JSONObject().put("version", 1).put("searches", JSONArray(values.map(::entry))).toString()
    }
    fun decode(text: String): List<RecentSearch> {
        require(text.length <= TOTAL_BYTES && text.toByteArray(Charsets.UTF_8).size <= TOTAL_BYTES)
        SetupJsonTree.validate(text)
        val value = JSONObject(text)
        value.checkKeys(setOf("version", "searches"))
        require(value.get("version") == 1) { "Unsupported recent search version." }
        val rows = value.getJSONArray("searches")
        require(rows.length() <= RECENT_SEARCH_LIMIT)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            require(row.toString().toByteArray(Charsets.UTF_8).size <= ENTRY_BYTES)
            row.checkKeys(setOf("id", "root", "usedAt", "rules"))
            require(row.get("id") is String && row.get("root") is String && (row.get("usedAt") is Long || row.get("usedAt") is Int) && row.get("rules") is JSONObject)
            RecentSearch(row.getString("id"), row.getString("root"), decodeRules(row.getJSONObject("rules")), row.getLong("usedAt")).checked()
        }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) }
    }
    // Navigation rules accept legacy input. History preserves unknown/damaged criteria.
    private fun decodeRules(value: JSONObject): SearchRules {
        val required = setOf("name", "kind", "tag", "days", "min", "max", "content", "terms", "tags", "tagMatch", "includeSubfolders", "excludedFolderNames")
        value.checkKeys(required, setOf("excludedLocations", "date", "size", "locations", "ruleGroup", "source"))
        for (key in listOf("name", "kind", "tag", "content", "tagMatch")) require(value.get(key) is String)
        for (key in listOf("days", "min", "max")) require(value.get(key) is Int || value.get(key) is Long)
        require(value.getLong("days") in 0..Int.MAX_VALUE.toLong() && value.get("includeSubfolders") is Boolean)
        fun strings(key: String, limit: Int): List<String> = value.getJSONArray(key).let { array ->
            require(array.length() <= limit)
            List(array.length()) { index -> require(array.get(index) is String); array.getString(index) }
        }
        val exclusions = if (!value.has("excludedLocations")) emptyList() else value.getJSONArray("excludedLocations").let { array ->
            require(array.length() <= 100)
            List(array.length()) { index ->
                val row = array.getJSONObject(index)
                row.checkKeys(setOf("path", "folder"))
                require(row.get("path") is String && row.get("folder") is Boolean)
                SearchExclusion(row.getString("path"), row.getBoolean("folder"))
            }
        }
        for (key in listOf("date", "size", "source")) if (value.has(key)) require(value.get(key) is String)
        return SearchRules(
            name = value.getString("name"), kind = value.getString("kind"), tag = value.getString("tag"),
            days = value.getInt("days"), minimumBytes = value.getLong("min"), maximumBytes = value.getLong("max"),
            content = value.getString("content"), nameTerms = strings("terms", 100), tags = strings("tags", 256).toSet(),
            tagMatch = TagMatch.valueOf(value.getString("tagMatch")), includeSubfolders = value.getBoolean("includeSubfolders"),
            excludedFolderNames = strings("excludedFolderNames", 100).toSet(), excludedLocations = exclusions,
            locations = if (value.has("locations")) checkedSearchLocations(strings("locations", SEARCH_LOCATION_LIMIT)) else emptyList(),
            date = if (value.has("date")) SearchDate.valueOf(value.getString("date")) else SearchDate.ANY,
            size = if (value.has("size")) SearchSize.valueOf(value.getString("size")) else SearchSize.ANY,
            ruleGroup = if (!value.has("ruleGroup")) null else SearchRuleGroupJson.decode(value.get("ruleGroup")),
            source = if (!value.has("source")) SearchSource.LIVE else SearchSource.valueOf(value.getString("source")),
        )
    }

    private fun JSONObject.checkKeys(required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = keys().asSequence().toSet()
        require(actual.containsAll(required) && actual.all { it in required || it in optional }) { "Unsupported recent search data. Clear history to start again." }
    }

}
