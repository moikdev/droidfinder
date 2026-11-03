package dev.findroid

import java.util.Collections

internal const val SEARCH_GROUP_MAX_DEPTH = 4
internal const val SEARCH_GROUP_MAX_NODES = 48
internal const val SEARCH_GROUP_TEXT_BYTES = 8192

enum class SearchRuleJoin(val title: String, val explanation: String) {
    ALL("All", "Every rule in this group must match."),
    ANY("Any", "At least one rule in this group must match."),
    NONE("None", "No rule in this group may match."),
}

sealed interface SearchRuleNode

data class SearchNameRule(val value: String) : SearchRuleNode {
    init { checkedRuleText(value, 512) }
}
data class SearchKindRule(val value: String) : SearchRuleNode {
    init { checkedRuleText(value, 128); require(value != "Any") { "Choose a kind for this rule." } }
}
data class SearchTagRule(val value: String) : SearchRuleNode {
    init { checkedRuleText(value, 128) }
}
data class SearchModifiedRule(val date: SearchDate = SearchDate.TODAY, val days: Int = 0) : SearchRuleNode {
    init {
        require((date != SearchDate.ANY && days == 0) || (date == SearchDate.ANY && days > 0)) {
            "Choose a date or a positive number of past days."
        }
    }
}
data class SearchSizeRule(val minimumBytes: Long = 0, val maximumBytes: Long = 0) : SearchRuleNode {
    init {
        require(minimumBytes >= 0 && maximumBytes >= 0 && (minimumBytes > 0 || maximumBytes > 0)) {
            "Enter at least one positive size limit."
        }
        require(minimumBytes == 0L || maximumBytes == 0L || minimumBytes <= maximumBytes) {
            "Minimum must not exceed maximum."
        }
    }
}

/** A defensive immutable tree. Empty groups exist only in the editor's separate draft. */
class SearchRuleGroup(val join: SearchRuleJoin, children: List<SearchRuleNode>) : SearchRuleNode {
    val children: List<SearchRuleNode> = immutableRuleChildren(children)
    init { checkedRuleGroup(this) }
    fun copy(join: SearchRuleJoin = this.join, children: List<SearchRuleNode> = this.children) = SearchRuleGroup(join, children)
    override fun equals(other: Any?): Boolean = other is SearchRuleGroup && join == other.join && children == other.children
    override fun hashCode(): Int = 31 * join.hashCode() + children.hashCode()
    override fun toString(): String = summary
}

private fun immutableRuleChildren(values: List<SearchRuleNode>): List<SearchRuleNode> {
    require(values.isNotEmpty()) { "Add a rule to every group before applying." }
    require(values.size < SEARCH_GROUP_MAX_NODES) { "Keep at most $SEARCH_GROUP_MAX_NODES rules and groups." }
    return Collections.unmodifiableList(ArrayList(values))
}

private fun checkedRuleText(value: String, limit: Int) {
    require(value.isNotBlank() && value.length <= limit && value.none(Char::isISOControl) &&
        Charsets.UTF_8.newEncoder().canEncode(value)) { "Enter a nonempty rule value of at most $limit characters." }
}

internal fun checkedRuleGroup(group: SearchRuleGroup) {
    val pending = ArrayDeque<Pair<SearchRuleNode, Int>>()
    pending.add(group to 1)
    var nodes = 0
    var bytes = 0
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        require(++nodes <= SEARCH_GROUP_MAX_NODES) { "Keep at most $SEARCH_GROUP_MAX_NODES rules and groups." }
        when (node) {
            is SearchRuleGroup -> {
                require(depth <= SEARCH_GROUP_MAX_DEPTH) { "Keep at most $SEARCH_GROUP_MAX_DEPTH levels of groups." }
                node.children.asReversed().forEach { pending.add(it to depth + 1) }
            }
            is SearchNameRule -> bytes += node.value.toByteArray(Charsets.UTF_8).size
            is SearchKindRule -> bytes += node.value.toByteArray(Charsets.UTF_8).size
            is SearchTagRule -> bytes += node.value.toByteArray(Charsets.UTF_8).size
            is SearchModifiedRule, is SearchSizeRule -> Unit
        }
        require(bytes <= SEARCH_GROUP_TEXT_BYTES) { "The combined rule values are too large." }
    }
}

internal val SearchRuleNode.summary: String
    get() = when (this) {
        is SearchRuleGroup -> "${join.title} of (" + children.joinToString("; ") { it.summary } + ")"
        is SearchNameRule -> "Name contains “$value”"
        is SearchKindRule -> "Kind is “$value”"
        is SearchTagRule -> "Tag is “$value”"
        is SearchModifiedRule -> if (date == SearchDate.ANY) "Modified in past $days ${if (days == 1) "day" else "days"}" else "Modified: ${date.title}"
        is SearchSizeRule -> buildList {
            if (minimumBytes > 0) add("at least $minimumBytes bytes")
            if (maximumBytes > 0) add("at most $maximumBytes bytes")
        }.joinToString(" and ", prefix = "Size: ")
    }

internal val SearchRuleNode.referencedTags: Set<String>
    get() = when (this) {
        is SearchRuleGroup -> children.flatMap { it.referencedTags }.toSet()
        is SearchTagRule -> setOf(value)
        else -> emptySet()
    }

internal fun SearchRuleNode.remapRuleTags(replacements: Map<String, String>): SearchRuleNode = when (this) {
    is SearchRuleGroup -> copy(children = children.map { it.remapRuleTags(replacements) })
    is SearchTagRule -> SearchTagRule(replacements[value] ?: value)
    else -> this
}

internal enum class SearchRuleOutcome { MATCH, NO_MATCH, UNKNOWN_SIZE }
internal data class SearchRuleEvaluation(
    val outcome: SearchRuleOutcome,
    val nameTerms: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
)

/** Unknown sizes remain unknown under negation. No branch reads file contents. */
internal fun SearchRuleNode.evaluate(
    entry: Entry,
    tags: Set<String>,
    calendar: SearchCalendar,
    check: () -> Unit,
): SearchRuleEvaluation {
    check()
    fun pass(reason: String, terms: List<String> = emptyList()) = SearchRuleEvaluation(SearchRuleOutcome.MATCH, terms, listOf(reason))
    val fail = SearchRuleEvaluation(SearchRuleOutcome.NO_MATCH)
    val unknown = SearchRuleEvaluation(SearchRuleOutcome.UNKNOWN_SIZE)
    return when (this) {
        is SearchNameRule -> if (entry.name.contains(value, ignoreCase = true)) pass("Name contains “${value.take(80)}”", listOf(value)) else fail
        is SearchKindRule -> if (entry.kind == value) pass("Kind: $value") else fail
        is SearchTagRule -> if (value in tags) pass("Tag: $value") else fail
        is SearchModifiedRule -> if ((days == 0 || entry.modified >= calendar.now - days * 86_400_000L) &&
            (date.bounds(calendar)?.let { entry.modified in it } != false)) pass(summary) else fail
        is SearchSizeRule -> when {
            entry.folder -> fail
            !entry.sizeKnown -> unknown
            (minimumBytes == 0L || entry.size >= minimumBytes) && (maximumBytes == 0L || entry.size <= maximumBytes) -> pass(summary)
            else -> fail
        }
        is SearchRuleGroup -> {
            var unresolved = false
            val passing = mutableListOf<SearchRuleEvaluation>()
            for (child in children) {
                val result = child.evaluate(entry, tags, calendar, check)
                when (result.outcome) {
                    SearchRuleOutcome.UNKNOWN_SIZE -> unresolved = true
                    SearchRuleOutcome.NO_MATCH -> if (join == SearchRuleJoin.ALL) return fail
                    SearchRuleOutcome.MATCH -> when (join) {
                        SearchRuleJoin.ANY -> return result
                        SearchRuleJoin.NONE -> return fail
                        SearchRuleJoin.ALL -> passing += result
                    }
                }
            }
            when {
                unresolved -> unknown
                join == SearchRuleJoin.ANY -> fail
                join == SearchRuleJoin.NONE -> pass("Rule group: none of ${children.size} rules matched")
                else -> SearchRuleEvaluation(SearchRuleOutcome.MATCH,
                    passing.flatMap { it.nameTerms }.distinct(), passing.flatMap { it.reasons }.distinct())
            }
        }
    }
}
