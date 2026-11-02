package dev.findroid

/** A selected file excludes itself; a selected folder also excludes its descendants. */
data class SearchExclusion(val path: String, val folder: Boolean = true)

internal enum class SearchAncestry { INSIDE, OUTSIDE, UNKNOWN }

/** Local identity resolves parent aliases, preserves a final symlink, and respects path boundaries. */
internal fun localSearchAncestry(path: String, ancestor: String): SearchAncestry {
    if (path.startsWith("/") != ancestor.startsWith("/")) return SearchAncestry.OUTSIDE
    if (!path.startsWith("/")) return if (path == ancestor) SearchAncestry.INSIDE else SearchAncestry.UNKNOWN
    val child = organizationKey(path)
    val parent = organizationKey(ancestor).trimEnd('/').ifEmpty { "/" }
    return if (child == parent || child.startsWith(if (parent == "/") "/" else "$parent/"))
        SearchAncestry.INSIDE else SearchAncestry.OUTSIDE
}

/** One parent/ancestor check per folder, never one provider query per file and exclusion. */
internal class SearchExclusions(
    exclusions: List<SearchExclusion>,
    private val identity: (String) -> String,
    private val ancestry: (String, String) -> SearchAncestry,
) {
    private val rules = exclusions.distinctBy { identity(it.path) }
    private val exact = rules.map { identity(it.path) }.toSet()
    private val folders = rules.filter { it.folder }.map { it to identity(it.path) }
    private val cached = object : LinkedHashMap<String, SearchAncestry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchAncestry>?) = size > 4096
    }

    fun check(path: String, parent: String? = null): SearchAncestry {
        if (rules.isEmpty()) return SearchAncestry.OUTSIDE
        val key = identity(path)
        if (key in exact) return SearchAncestry.INSIDE
        if (path.startsWith("/")) {
            return if (folders.any { (_, root) -> root.startsWith("/") &&
                    (key == root || key.startsWith(root.trimEnd('/') + "/")) })
                SearchAncestry.INSIDE else SearchAncestry.OUTSIDE
        }
        // For streamed children the actual traversal parent proves the branch. Explicit
        // saved items without a known parent and scan roots need a direct ancestry check.
        val candidate = parent ?: path
        return cached.getOrPut(identity(candidate)) {
            var unknown = false
            for ((rule, _) in folders) {
                val relation = ancestry(candidate, rule.path)
                if (relation == SearchAncestry.INSIDE) return@getOrPut relation
                if (relation == SearchAncestry.UNKNOWN) unknown = true
            }
            if (unknown) SearchAncestry.UNKNOWN else SearchAncestry.OUTSIDE
        }
    }
}
