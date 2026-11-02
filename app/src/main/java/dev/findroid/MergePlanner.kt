package dev.findroid

/** Storage-independent directory names and identities, without reading file contents. */
data class MergeNode(
    val path: String,
    val name: String,
    val folder: Boolean,
    val symbolicLink: Boolean = false,
)

/** Only pre-existing folder pairs are expanded. A new subtree remains one staged copy unit. */
fun planMergeMembers(
    source: String,
    destination: String,
    list: (String) -> List<MergeNode>,
    control: TransferControl = TransferControl(),
    limit: Int = 50_000,
): List<MergeMember> {
    require(source != destination) { "A folder cannot be merged with itself." }
    require(limit > 0)
    data class Pairing(val source: String, val destination: String, val depth: Int)
    val pending = ArrayDeque<Pairing>().apply { add(Pairing(source, destination, 0)) }
    val visited = mutableSetOf<String>()
    val members = mutableListOf<MergeMember>()
    var inspected = 0
    while (pending.isNotEmpty()) {
        control.check()
        val pair = pending.removeFirst()
        require(pair.depth <= 128 && visited.add(pair.source)) {
            "A folder cycle or excessively deep merge was found."
        }
        val incoming = list(pair.source)
        val existing = list(pair.destination)
        require(existing.map { it.name }.distinct().size == existing.size) {
            "The destination contains ambiguous duplicate names."
        }
        val byName = existing.associateBy { it.name }
        for (child in incoming) {
            control.check()
            require(++inspected <= limit) {
                "This merge exceeds the $limit item planning limit. Use smaller batches."
            }
            require(!child.symbolicLink) { "Symbolic links are not merged." }
            val target = byName[child.name]
            require(target?.symbolicLink != true || !child.folder || !target.folder) {
                "A destination folder link cannot be merged implicitly."
            }
            if (child.folder && target?.folder == true)
                pending.add(Pairing(child.path, target.path, pair.depth + 1))
            else members += MergeMember(child.path, pair.destination)
        }
    }
    require(members.map { it.source }.distinct().size == members.size) {
        "A source item appeared more than once in the merge."
    }
    return members
}
