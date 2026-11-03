package dev.findroid

/** Editor-only structure: empty groups are allowed here and nowhere in saved criteria. */
internal sealed interface SearchRuleDraft
internal data class SearchConditionDraft(val condition: SearchRuleNode) : SearchRuleDraft {
    init { require(condition !is SearchRuleGroup) }
}
internal data class SearchGroupDraft(
    val join: SearchRuleJoin = SearchRuleJoin.ALL,
    val children: List<SearchRuleDraft> = emptyList(),
) : SearchRuleDraft

internal fun SearchRuleGroup.draft(): SearchGroupDraft = SearchGroupDraft(join, children.map {
    if (it is SearchRuleGroup) it.draft() else SearchConditionDraft(it)
})

internal fun SearchGroupDraft.applied(): SearchRuleGroup {
    checkedDraft()
    return SearchRuleGroup(join, children.map {
        when (it) {
            is SearchConditionDraft -> it.condition
            is SearchGroupDraft -> it.applied()
        }
    })
}

internal fun SearchGroupDraft.groupAt(path: List<Int>): SearchGroupDraft =
    path.fold(this) { group, index -> group.children[index] as SearchGroupDraft }

internal fun SearchGroupDraft.replaceGroup(path: List<Int>, replacement: SearchGroupDraft): SearchGroupDraft {
    if (path.isEmpty()) return replacement
    val index = path.first()
    val child = children[index] as SearchGroupDraft
    return copy(children = children.mapIndexed { i, value ->
        if (i == index) child.replaceGroup(path.drop(1), replacement) else value
    })
}

internal val SearchRuleDraft.nodeCount: Int
    get() = when (this) {
        is SearchConditionDraft -> 1
        is SearchGroupDraft -> 1 + children.sumOf { it.nodeCount }
    }

internal val SearchRuleDraft.draftSummary: String
    get() = when (this) {
        is SearchConditionDraft -> condition.summary
        is SearchGroupDraft -> if (children.isEmpty()) "${join.title} of (no rules yet)"
            else "${join.title} of (" + children.joinToString("; ") { it.draftSummary } + ")"
    }

internal fun SearchGroupDraft.checkedDraft(): SearchGroupDraft = apply {
    var nodes = 0
    var bytes = 0
    fun walk(value: SearchRuleDraft, depth: Int) {
        require(++nodes <= SEARCH_GROUP_MAX_NODES) { "Keep at most $SEARCH_GROUP_MAX_NODES rules and groups." }
        when (value) {
            is SearchGroupDraft -> {
                require(depth <= SEARCH_GROUP_MAX_DEPTH) { "Keep at most $SEARCH_GROUP_MAX_DEPTH levels of groups." }
                value.children.forEach { walk(it, depth + 1) }
            }
            is SearchConditionDraft -> {
                bytes += when (val leaf = value.condition) {
                    is SearchNameRule -> leaf.value.toByteArray(Charsets.UTF_8).size
                    is SearchKindRule -> leaf.value.toByteArray(Charsets.UTF_8).size
                    is SearchTagRule -> leaf.value.toByteArray(Charsets.UTF_8).size
                    else -> 0
                }
                require(bytes <= SEARCH_GROUP_TEXT_BYTES) { "The combined rule values are too large." }
            }
        }
    }
    walk(this, 1)
}
