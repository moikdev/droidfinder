package dev.findroid

/** A label assembled from cached titles and lexical paths; it never resolves a filesystem path. */
internal fun FinderModel.storageAccessLocation(path: String): String {
    if (isDocumentPath(path)) return displayPath(path)
    val root = (listOf(workspace, deviceRoot) + volumes)
        .filter { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }
        .maxByOrNull { it.path.length } ?: return path
    val suffix = path.removePrefix(root.path).trimStart('/')
    return title(root.path) + if (suffix.isEmpty()) "" else " / " + suffix.replace("/", " / ")
}

internal fun FinderModel.storageAccessFacts(items: List<Entry>): List<StorageAccessFacts> {
    val roots = connectedLocations.map { it.uri }.toSet()
    val current = folderPath
    val issue = folderStorageIssue?.detail
    return items.map { entry ->
        val root = entry.path in roots
        val restrictions = if (root) buildMap {
            put(StorageAction.DELETE, "A connected root cannot be deleted. Remove the connection in Storage locations to keep its contents.")
            if (entry.parentPath == null)
                put(StorageAction.RENAME, "Android does not expose a parent for this connected root. Choose an item inside the connection to rename it.")
        } else emptyMap()
        val unavailable = issue?.takeIf {
            current != null && generateSequence(entry.path) { documents.parent(it) }
                .take(32).any { it == current }
        }
        StorageAccessFacts(entry.path, entry.name, entry.folder, isDocumentPath(entry.path), true,
            readable = entry.readable, writable = entry.writable, rename = entry.canRename,
            delete = entry.canDelete, document = entry.providerAccess, unavailable = unavailable,
            inTrash = isTrash, restrictions = restrictions)
    }
}

internal fun FinderModel.currentStorageAccessFacts(): StorageAccessFacts? {
    val path = folderPath ?: return null
    folderInfo?.let { return storageAccessFacts(listOf(it)).single() }
    return StorageAccessFacts(path, title(), folder = true, provider = isDocumentPath(path), loaded = false,
        unavailable = folderStorageIssue?.detail ?: listingError?.takeUnless { loading })
}

internal fun FinderModel.storageCommandProblem(entry: Entry, action: StorageAction): CommandProblem? {
    val detail = storageActionDetail(storageAccessFacts(listOf(entry)).single(), action)
    if (detail.support == ActionSupport.AVAILABLE || detail.support == ActionSupport.NOT_APPLICABLE) return null
    fun concise(value: String, limit: Int) = if (value.length <= limit) value else value.take(limit - 1) + "…"
    val scope = concise(entry.name, 120) + " · " + concise(storageAccessLocation(entry.path), 240)
    return CommandProblem("$scope\n\n${detail.reason}", detail.recovery)
}
