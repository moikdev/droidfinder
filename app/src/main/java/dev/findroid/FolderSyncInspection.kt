package dev.findroid

import java.nio.file.Paths

/** A volatile read-only comparison, tied to both complete native folder snapshots. */
internal data class FolderSyncInspection(
    val preview: FolderSyncPreview,
    val primaryProof: UndoPathProof,
    val secondaryProof: UndoPathProof,
    val contents: Map<String, String>,
)

/** The permission callback runs before any native inspection or content read. */
internal fun inspectFolderSync(
    primaryRoot: String,
    secondaryRoot: String,
    direction: FolderSyncDirection,
    allowed: (String) -> Boolean,
    read: (String) -> ComparisonNode,
    list: (String) -> Iterable<ComparisonNode>,
    fingerprint: (String, PartialFileStamp, TransferControl) -> String,
    files: UndoHistoryFiles,
    control: TransferControl = TransferControl(),
): FolderSyncInspection {
    val roots = planFolderSync(primaryRoot, secondaryRoot, FolderComparison(emptyList()), FolderSyncDirection.PRIMARY_TO_SECONDARY)
    check(roots.executable) { roots.problems.first() }
    val primary = roots.sourceRoot
    val secondary = roots.destinationRoot
    check(allowed(primary) && allowed(secondary)) { "Reconnect both folders before reviewing synchronization." }
    control.check()
    control.phase("Checking synchronization folders", stage = TransferStage.PREPARING)
    val primaryProof = files.observe(primary)
    control.check()
    val secondaryProof = files.observe(secondary)
    control.check()
    fun folder(proof: UndoPathProof, expected: String) {
        check(proof.path == expected && proof.entries[expected]?.let { it.directory && !it.regular } == true) { "Choose two available local folders." }
        check(proof.entries.size <= 10_000 && proof.entries.all { (path, stamp) ->
            (path == expected || path.startsWith(expected.trimEnd('/') + "/")) &&
                (stamp.directory && !stamp.regular || stamp.regular && !stamp.directory && stamp.links == 1L)
        }) { "This folder needs a smaller review or contains linked or special files. Resolve it manually before synchronizing." }
    }
    folder(primaryProof, primary);folder(secondaryProof, secondary)
    check((primaryProof.entries.keys + secondaryProof.entries.keys).none { isWorkingFileName(it.substringAfterLast('/')) }) {
        "Review unfinished transfers in these folders before synchronizing them. Working data is kept in place."
    }
    check(allowed(primaryProof.resolved) && allowed(secondaryProof.resolved)) { "The resolved folders are unavailable. Reconnect them and compare again." }
    val a = Paths.get(primaryProof.resolved)
    val b = Paths.get(secondaryProof.resolved)
    val primaryDirectories = primaryProof.entries.values.filter { it.directory }.map { it.device to it.inode }.toSet()
    val secondaryDirectories = secondaryProof.entries.values.filter { it.directory }.map { it.device to it.inode }.toSet()
    check(!a.startsWith(b) && !b.startsWith(a) && primaryDirectories.intersect(secondaryDirectories).isEmpty()) {
        "These locations refer to overlapping folders. Choose separate source and destination folders."
    }
    val nativeEntries = primaryProof.entries + secondaryProof.entries
    fun reviewedNode(node: ComparisonNode): ComparisonNode {
        val stamp = nativeEntries[node.path]
        check(stamp != null && !node.link && node.folder == stamp.directory && (node.folder || stamp.regular)) {
            "A compared item is outside the verified folder snapshot or changed type. Compare again."
        }
        return if (stamp.regular) node.copy(bytes = stamp.size) else node
    }
    val contents = mutableMapOf<String, String>()
    fun checkedDigest(path: String, token: TransferControl): String {
        token.check()
        return contents.getOrPut(path) {
            val expected = nativeEntries[path]
            check(expected?.regular == true) { "A file is outside the reviewed folder snapshot. Compare again." }
            fingerprint(path, expected, token).also { value ->
                check(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "A file checksum could not be verified. Compare again." }
            }
        }
    }
    val comparison = compareFolders(primary, secondary,
        read = { path -> reviewedNode(read(path)).also { check(it.path == path) { "A compared location changed. Compare again." } } },
        list = { path ->
            check(nativeEntries[path]?.directory == true) { "A compared folder changed. Compare again." }
            list(path).asSequence().map(::reviewedNode).asIterable()
        }, fingerprint = ::checkedDigest,
        verifyContents = true, control = control, limit = 20_000)
    val comparedPaths = comparison.rows.flatMap { listOfNotNull(it.left?.path, it.right?.path) }.toSet() + setOf(primary, secondary)
    check(comparedPaths == nativeEntries.keys) {
        "The comparison omitted files from the verified folders. Compare again before reviewing synchronization."
    }
    // Missing counterparts and differing sizes do not require hashes merely to classify them.
    // The reviewed plan still needs their original contents before a future copy or replacement.
    control.phase("Checking reviewed file contents", stage = TransferStage.VERIFYING)
    for (node in comparison.rows.flatMap { listOfNotNull(it.left, it.right) }.filterNot { it.folder }.distinctBy { it.path }) {
        control.check();control.item(node.name)
        checkedDigest(node.path, control)
    }
    control.check()
    val preview = planFolderSync(primary, secondary, comparison, direction)
    val inspection = FolderSyncInspection(preview, primaryProof, secondaryProof, contents.toMap())
    checkFolderSyncInspection(inspection, files, control)
    return inspection
}

/** Closing/reopening the review never reuses newly invented identities for an older plan. */
internal fun checkFolderSyncInspection(
    inspection: FolderSyncInspection,
    files: UndoHistoryFiles,
    control: TransferControl = TransferControl(),
) {
    for (proof in listOf(inspection.primaryProof, inspection.secondaryProof)) {
        control.check()
        check(files.observe(proof.path) == proof) {
            "A folder changed after the comparison. Compare again before applying any changes."
        }
    }
    control.check()
}
