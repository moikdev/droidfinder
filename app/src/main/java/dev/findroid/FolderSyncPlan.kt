package dev.findroid

import java.nio.file.Path
import java.nio.file.Paths

internal enum class FolderSyncDirection { PRIMARY_TO_SECONDARY, SECONDARY_TO_PRIMARY }
internal enum class FolderSyncCategory(val title: String) {
    ADDITION("Addition"), UPDATE("Proposed update"), CONFLICT("Conflict"),
    DELETION("Destination only"), UNCHANGED("Unchanged"),
}

/** A read-only proposal. A missing subtree is selected as one complete, disclosed operation. */
internal data class FolderSyncEntry(
    val relative: String,
    val source: ComparisonNode?,
    val destination: ComparisonNode?,
    val destinationPath: String,
    val category: FolderSyncCategory,
    val explanation: String,
    val coveredBy: String? = null,
    val descendantCount: Int = 0,
) {
    val selectable get() = coveredBy == null && category in setOf(
        FolderSyncCategory.ADDITION, FolderSyncCategory.UPDATE, FolderSyncCategory.DELETION,
    )
    val initiallySelected get() = selectable && category == FolderSyncCategory.ADDITION
}

internal data class FolderSyncPreview(
    val sourceRoot: String,
    val destinationRoot: String,
    val direction: FolderSyncDirection,
    val entries: List<FolderSyncEntry>,
    val problems: List<String>,
) {
    val executable get() = problems.isEmpty()
    val initialSelection get() = if (executable) entries.filter { it.initiallySelected }.map { it.relative }.toSet() else emptySet()
}

internal data class ReviewedFolderSync(
    val preview: FolderSyncPreview,
    val operations: List<FolderSyncEntry>,
    val updatesConfirmed: Boolean,
    val trashConfirmed: Boolean,
) {
    /** Includes all disclosed descendants of selected new subtrees, without counting copies twice. */
    val copyFiles: List<ComparisonNode> get() {
        val roots = operations.filter { it.category != FolderSyncCategory.DELETION }.map { it.relative }.toSet()
        return preview.entries.filter { it.relative in roots || it.coveredBy in roots }
            .mapNotNull { it.source }.filterNot { it.folder }
    }
    val copyBytes: Long? get() {
        var bytes = 0L
        for (file in copyFiles) {
            val size = file.bytes ?: return null
            if (size < 0 || Long.MAX_VALUE - bytes < size) return null
            bytes += size
        }
        return bytes
    }
}

/**
 * One-way review, without a previous synchronization baseline. Timestamps never pick a winner.
 * The caller must collect a complete local comparison and hold separate native identity proofs;
 * this pure planner performs no I/O and grants no permission to mutate a filesystem.
 */
internal fun planFolderSync(
    primaryRoot: String,
    secondaryRoot: String,
    comparison: FolderComparison,
    direction: FolderSyncDirection,
): FolderSyncPreview {
    val problems = mutableListOf<String>()
    fun root(value: String): Path? = try {
        Paths.get(value).takeIf { it.isAbsolute && it == it.normalize() }
    } catch (_: Exception) { null }
    val primary = root(primaryRoot)
    val secondary = root(secondaryRoot)
    if (primary == null || secondary == null) problems += "Choose two local folders. Connected storage needs its own synchronization checks."
    if (primary != null && secondary != null && (primary.startsWith(secondary) || secondary.startsWith(primary)))
        problems += "Choose separate folders; a folder cannot synchronize with itself or one of its descendants."
    val forward = direction == FolderSyncDirection.PRIMARY_TO_SECONDARY
    val source = if (forward) primary else secondary
    val destination = if (forward) secondary else primary
    val sourceName = source?.toString() ?: if (forward) primaryRoot else secondaryRoot
    val destinationName = destination?.toString() ?: if (forward) secondaryRoot else primaryRoot
    if (comparison.incomplete) problems += "The comparison is incomplete. Resolve unreadable items and run it again before applying a plan."
    if (comparison.rows.size > 20_000) {
        problems += "This comparison exceeds the synchronization review limit. Choose smaller folders."
        return FolderSyncPreview(sourceName, destinationName, direction, emptyList(), problems.distinct())
    }
    val relativeCounts = comparison.rows.groupingBy { it.relative }.eachCount()
    if (relativeCounts.any { it.value != 1 }) problems += "An item appears more than once in the comparison. Run it again."
    fun relative(value: String): Boolean = value.isNotEmpty() && value.toByteArray().size <= 4096 && value.split('/').let { parts -> parts.size <= 128 && parts.all { it.isNotBlank() && it != "." && it != ".." } } && '\u0000' !in value
    fun mapped(node: ComparisonNode?, base: Path?, name: String): Boolean = node == null || base == null || try {
        val path = Paths.get(node.path)
        path.isAbsolute && path == path.normalize() && path == base.resolve(name) && node.name == name.substringAfterLast('/')
    } catch (_: Exception) { false }
    val entries = comparison.rows.map { row ->
        val from = if (forward) row.left else row.right
        val to = if (forward) row.right else row.left
        val valid = relative(row.relative) && mapped(row.left, primary, row.relative) && mapped(row.right, secondary, row.relative)
        if (!valid) problems += "A compared path is ambiguous or outside the chosen folders. Run the comparison again."
        if (from?.link == true || to?.link == true) problems += "Links and special files need manual review before synchronization."
        val coherent = when (row.state) {
            ComparisonState.ONLY_LEFT -> row.left != null && row.right == null
            ComparisonState.ONLY_RIGHT -> row.right != null && row.left == null
            ComparisonState.TYPE_CHANGED -> row.left != null && row.right != null && row.left.folder != row.right.folder
            ComparisonState.FOLDER -> row.left?.folder == true && row.right?.folder == true
            ComparisonState.SIZE_CHANGED, ComparisonState.CHANGED, ComparisonState.UNVERIFIED, ComparisonState.IDENTICAL ->
                row.left != null && row.right != null && !row.left.folder && !row.right.folder
            ComparisonState.UNAVAILABLE -> true
        }
        if (!coherent) problems += "The compared item types are inconsistent. Run the comparison again."
        val category = when {
            !valid || !coherent || from?.link == true || to?.link == true -> FolderSyncCategory.CONFLICT
            row.state == ComparisonState.UNAVAILABLE || row.state == ComparisonState.UNVERIFIED || row.state == ComparisonState.TYPE_CHANGED -> FolderSyncCategory.CONFLICT
            from == null -> FolderSyncCategory.DELETION
            to == null -> FolderSyncCategory.ADDITION
            row.state == ComparisonState.CHANGED || row.state == ComparisonState.SIZE_CHANGED -> FolderSyncCategory.UPDATE
            else -> FolderSyncCategory.UNCHANGED
        }
        val explanation = when (category) {
            FolderSyncCategory.ADDITION -> "Copy from the source; keep existing destination names."
            FolderSyncCategory.UPDATE -> "The contents differ. Replacing the destination requires your explicit choice and preserves its current copy for recovery."
            FolderSyncCategory.DELETION -> "This item exists only at the destination. Keep it unless you explicitly choose to move it to Trash."
            FolderSyncCategory.CONFLICT -> row.detail ?: when (row.state) {
                ComparisonState.UNVERIFIED -> "Contents were not checked. Verify them and compare again."
                ComparisonState.TYPE_CHANGED -> "A file and a folder use the same name. Resolve them manually, then compare again."
                else -> "This item could not be compared safely. Resolve it manually, then compare again."
            }
            FolderSyncCategory.UNCHANGED -> if (from?.folder == true) "Folder compared recursively." else "File contents were verified identical."
        }
        val target = if (relative(row.relative)) destination?.resolve(row.relative)?.toString() ?: to?.path.orEmpty() else ""
        FolderSyncEntry(row.relative, from, to, target, category, explanation)
    }.sortedBy { it.relative }
    if (entries.any { !relative(it.relative) }) return FolderSyncPreview(sourceName, destinationName, direction, entries, problems.distinct())
    val byPath = entries.associateBy { it.relative }
    for (entry in entries) {
        val parentName = entry.relative.substringBeforeLast('/', "")
        if (parentName.isNotEmpty()) {
            val parent = byPath[parentName]
            if (parent == null || entry.source != null && parent.source?.folder != true || entry.destination != null && parent.destination?.folder != true)
                problems += "A nested item has no matching parent folder in the comparison. Run it again."
        }
    }
    fun ancestors(relative: String) = generateSequence(relative.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }.takeWhile { it.isNotEmpty() }.toList()
    val descendantCounts = mutableMapOf<String, Int>()
    for (entry in entries) for (parent in ancestors(entry.relative)) descendantCounts[parent] = (descendantCounts[parent] ?: 0) + 1
    val collapsed = entries.map { entry ->
        val ancestors = ancestors(entry.relative)
        val covering = ancestors.mapNotNull(byPath::get).lastOrNull {
            it.category == entry.category && it.category in setOf(FolderSyncCategory.ADDITION, FolderSyncCategory.DELETION) &&
                (it.source?.folder == true || it.destination?.folder == true)
        }
        // A missing parent cannot contain a counterpart. Never silently hide inconsistent rows.
        if (ancestors.mapNotNull(byPath::get).any {
            it.category in setOf(FolderSyncCategory.ADDITION, FolderSyncCategory.DELETION) && it.category != entry.category
        }) problems += "A missing subtree has inconsistent counterparts. Run the comparison again."
        entry.copy(coveredBy = covering?.relative,
            descendantCount = if (entry.source?.folder == true || entry.destination?.folder == true)
                descendantCounts[entry.relative] ?: 0 else 0)
    }
    return FolderSyncPreview(sourceName, destinationName, direction, collapsed, problems.distinct())
}

/** Final selection review still requires the executor to recheck native proofs before any work. */
internal fun reviewFolderSync(
    preview: FolderSyncPreview,
    selected: Set<String>,
    confirmUpdates: Boolean = false,
    confirmTrash: Boolean = false,
): ReviewedFolderSync {
    check(preview.executable) { preview.problems.firstOrNull() ?: "Run the comparison again." }
    val choices = preview.entries.filter { it.relative in selected }
    check(choices.size == selected.size && choices.all { it.selectable }) { "The selection contains a conflict or a covered child. Review the plan again." }
    check(confirmUpdates || choices.none { it.category == FolderSyncCategory.UPDATE }) { "Review and confirm the proposed replacements first." }
    check(confirmTrash || choices.none { it.category == FolderSyncCategory.DELETION }) { "Review and confirm moving destination-only items to Trash first." }
    return ReviewedFolderSync(preview, choices.sortedWith(compareBy<FolderSyncEntry> { it.category.ordinal }.thenBy { it.relative }), confirmUpdates, confirmTrash)
}
