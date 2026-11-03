package dev.findroid

import org.json.JSONArray
import org.json.JSONObject

/** Strict shared codec: missing groups are handled by the caller; damaged groups never widen a search. */
internal object SearchRuleGroupJson {
    fun encode(group: SearchRuleGroup): JSONObject {
        checkedRuleGroup(group)
        return node(group)
    }

    private fun node(value: SearchRuleNode): JSONObject = when (value) {
        is SearchRuleGroup -> JSONObject().put("type", "group").put("match", value.join.name)
            .put("rules", JSONArray(value.children.map(::node)))
        is SearchNameRule -> JSONObject().put("type", "name").put("value", value.value)
        is SearchKindRule -> JSONObject().put("type", "kind").put("value", value.value)
        is SearchTagRule -> JSONObject().put("type", "tag").put("value", value.value)
        is SearchModifiedRule -> JSONObject().put("type", "modified").put("date", value.date.name).put("days", value.days)
        is SearchSizeRule -> JSONObject().put("type", "size").put("min", value.minimumBytes).put("max", value.maximumBytes)
    }

    fun decode(raw: Any?): SearchRuleGroup {
        val budget = Budget()
        return visit(raw, 1, budget) as? SearchRuleGroup
            ?: throw IllegalArgumentException("Saved rule groups must start with a group.")
    }

    private class Budget(var nodes: Int = 0, var bytes: Int = 0)

    private fun visit(raw: Any?, depth: Int, budget: Budget): SearchRuleNode {
        require(++budget.nodes <= SEARCH_GROUP_MAX_NODES) { "Too many saved rules and groups." }
        require(raw is JSONObject && raw.get("type") is String) { "Invalid saved rule." }
        fun keys(vararg allowed: String) {
            require(raw.keys().asSequence().toSet() == allowed.toSet()) {
                "Unsupported rule data. Keep the saved search and use a compatible Findroid version."
            }
        }
        fun text(key: String): String {
            require(raw.get(key) is String) { "Invalid saved rule value." }
            return raw.getString(key).also {
                budget.bytes += it.toByteArray(Charsets.UTF_8).size
                require(budget.bytes <= SEARCH_GROUP_TEXT_BYTES) { "Saved rule values are too large." }
            }
        }
        fun number(key: String): Long {
            require(raw.get(key) is Int || raw.get(key) is Long) { "Invalid saved rule bounds." }
            return raw.getLong(key)
        }
        return when (raw.getString("type")) {
            "group" -> {
                keys("type", "match", "rules")
                require(depth <= SEARCH_GROUP_MAX_DEPTH) { "Saved rules are nested too deeply." }
                require(raw.get("match") is String && raw.get("rules") is JSONArray) { "Invalid saved group." }
                val children = raw.getJSONArray("rules")
                require(children.length() in 1 until SEARCH_GROUP_MAX_NODES) { "Invalid saved group size." }
                SearchRuleGroup(SearchRuleJoin.valueOf(raw.getString("match")),
                    List(children.length()) { visit(children.get(it), depth + 1, budget) })
            }
            "name" -> { keys("type", "value"); SearchNameRule(text("value")) }
            "kind" -> { keys("type", "value"); SearchKindRule(text("value")) }
            "tag" -> { keys("type", "value"); SearchTagRule(text("value")) }
            "modified" -> {
                keys("type", "date", "days")
                require(raw.get("date") is String)
                val days = number("days")
                require(days in 0..Int.MAX_VALUE.toLong()) { "Invalid saved date rule." }
                SearchModifiedRule(SearchDate.valueOf(raw.getString("date")), days.toInt())
            }
            "size" -> { keys("type", "min", "max"); SearchSizeRule(number("min"), number("max")) }
            else -> throw IllegalArgumentException("Unsupported saved rule. Use a compatible Findroid version.")
        }
    }
}
