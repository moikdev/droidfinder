package dev.findroid

import java.util.concurrent.CancellationException

enum class ComparisonState(val title: String) {
    ONLY_LEFT("Only left"),
    ONLY_RIGHT("Only right"),
    TYPE_CHANGED("Different types"),
    SIZE_CHANGED("Different sizes"),
    UNVERIFIED("Contents not checked"),
    IDENTICAL("Verified identical"),
    CHANGED("Different contents"),
    FOLDER("Folder pair"),
    UNAVAILABLE("Unable to compare"),
}

data class ComparisonNode(
    val path: String,
    val name: String,
    val folder: Boolean,
    val bytes: Long?,
    val modified: Long,
    val link: Boolean = false,
)

data class ComparisonRow(
    val relative: String,
    val left: ComparisonNode?,
    val right: ComparisonNode?,
    val state: ComparisonState,
    val detail: String? = null,
)

data class FolderComparison(val rows: List<ComparisonRow>, val limited: Boolean = false) {
    val incomplete
        get() = limited || rows.any { it.state == ComparisonState.UNAVAILABLE }
}

/** Read-only pairing. Equal metadata never implies equal contents. Names retain provider casing. */
fun compareFolders(
    left: String,
    right: String,
    read: (String) -> ComparisonNode,
    list: (String) -> Iterable<ComparisonNode>,
    fingerprint: (String, TransferControl) -> String,
    verifyContents: Boolean = false,
    control: TransferControl = TransferControl(),
    limit: Int = 20_000,
): FolderComparison {
    require(limit > 0)
    control.check()
    val leftRoot = read(left)
    control.check()
    val roots = leftRoot to read(right)
    require(roots.first.folder && roots.second.folder) { "Open a folder in each pane." }
    require(!roots.first.link && !roots.second.link) { "Folder links cannot be compared." }
    data class Pending(
        val relative: String,
        val left: ComparisonNode?,
        val right: ComparisonNode?,
        val depth: Int,
    )
    val queue = ArrayDeque<Pending>()
    queue.add(Pending("", roots.first, roots.second, 0))
    val rows = mutableListOf<ComparisonRow>()
    val visitedLeft = mutableSetOf<String>()
    val visitedRight = mutableSetOf<String>()
    var inspected = 0
    var limited = false
    fun children(node: ComparisonNode?, seen: MutableSet<String>): Map<String, ComparisonNode> {
        if (node == null) return emptyMap()
        check(seen.add(node.path)) { "A provider folder cycle was encountered." }
        val result = linkedMapOf<String, ComparisonNode>()
        val iterator = list(node.path).iterator()
        while (true) {
            control.check()
            if (inspected >= limit) {
                limited = true
                break
            }
            if (!iterator.hasNext()) break
            val child = iterator.next()
            inspected++
            check(
                child.name.isNotBlank() && '/' !in child.name && child.name !in setOf(".", ".."),
            ) {
                "An item has an ambiguous name."
            }
            check(result.put(child.name, child) == null) {
                "This provider returned duplicate names."
            }
        }
        return result
    }
    control.phase(if (verifyContents) "Comparing contents" else "Comparing folders")
    while (queue.isNotEmpty()) {
        control.check()
        val item = queue.removeFirst()
        val a = item.left
        val b = item.right
        control.item(item.relative)
        fun add(state: ComparisonState, detail: String? = null) {
            if (rows.size >= limit) {
                limited = true
                return
            }
            rows += ComparisonRow(item.relative.ifEmpty { "." }, a, b, state, detail)
        }
        if (a?.link == true || b?.link == true) {
            add(ComparisonState.UNAVAILABLE, "Symbolic links are not followed.")
            continue
        }
        if (a != null && b != null && a.folder != b.folder) {
            add(ComparisonState.TYPE_CHANGED)
            continue
        }
        val folder = a?.folder ?: b?.folder ?: false
        if (folder) {
            if (item.relative.isNotEmpty())
                add(
                    if (a == null) ComparisonState.ONLY_RIGHT
                    else if (b == null) ComparisonState.ONLY_LEFT else ComparisonState.FOLDER,
                )
            if (item.depth >= 64) {
                limited = true
                continue
            }
            try {
                val ac = children(a, visitedLeft)
                if (limited) break
                val bc = children(b, visitedRight)
                // If the budget interrupted listing, missing counterparts cannot be inferred
                // safely.
                if (limited) break
                (ac.keys + bc.keys).forEach { name ->
                    queue.add(
                        Pending(
                            if (item.relative.isEmpty()) name else "${item.relative}/$name",
                            ac[name],
                            bc[name],
                            item.depth + 1,
                        ),
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                add(ComparisonState.UNAVAILABLE, error.message ?: "Folder unavailable.")
            }
            continue
        }
        if (a == null) {
            add(ComparisonState.ONLY_RIGHT)
            continue
        }
        if (b == null) {
            add(ComparisonState.ONLY_LEFT)
            continue
        }
        if (a.bytes != null && b.bytes != null && a.bytes != b.bytes) {
            add(ComparisonState.SIZE_CHANGED)
            continue
        }
        if (!verifyContents) {
            add(ComparisonState.UNVERIFIED)
            continue
        }
        try {
            val ah = fingerprint(a.path, control)
            val bh = fingerprint(b.path, control)
            control.check()
            // Detect ordinary changes/removal during hashing. This is a point-in-time comparison,
            // not a locked filesystem snapshot; later copy actions revalidate through the queue.
            check(read(a.path) == a && read(b.path) == b) {
                "An item changed while it was being compared. Run the comparison again."
            }
            add(if (ah == bh) ComparisonState.IDENTICAL else ComparisonState.CHANGED)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            add(ComparisonState.UNAVAILABLE, error.message ?: "Contents unavailable.")
        }
    }
    return FolderComparison(rows.sortedBy { it.relative }, limited)
}
