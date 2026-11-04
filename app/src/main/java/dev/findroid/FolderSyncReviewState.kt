package dev.findroid

internal data class FolderSyncReviewState(
    val token: String,
    val left: String,
    val right: String,
    val direction: FolderSyncDirection,
    val visible: Boolean = true,
    val checking: Boolean = true,
    val inspection: FolderSyncInspection? = null,
    val selected: Set<String> = emptySet(),
    val confirming: Boolean = false,
    val updatesConfirmed: Boolean = false,
    val trashConfirmed: Boolean = false,
    val applying: Boolean = false,
    val progress: TransferProgress = TransferProgress(),
    val applied: FolderSyncApplyState? = null,
    val workingDirectory: String? = null,
    val journalDirectory: String? = null,
    val error: String? = null,
    val finished: Boolean = false,
)

/** Access to one allocated sibling is scoped to this run, including a Workspace-root target. */
internal fun folderSyncRunAccess(
    destination: String,
    working: String,
    workspace: String,
    regularAllowed: (String) -> Boolean,
    canonical: (String) -> String = { java.io.File(it).canonicalPath },
): (String) -> Boolean {
    val target = java.io.File(destination)
    val parent = target.parentFile ?: error("Choose a destination with a parent folder.")
    check(java.io.File(working).parentFile == parent && java.io.File(working).name.matches(
        Regex("\\.findroid-sync-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    val rootTarget = destination == workspace && canonical(destination) == canonical(workspace)
    val resolvedParent = canonical(parent.path)
    val resolvedWorking = java.io.File(resolvedParent, java.io.File(working).name).path
    return { path ->
        runCatching {
            val file = java.io.File(path)
            check(file.isAbsolute && file.toPath().normalize().toString() == path)
            if (regularAllowed(path)) true
            else rootTarget && regularAllowed(workspace) && canonical(parent.path) == resolvedParent && (
                path == parent.path && canonical(path) == resolvedParent ||
                    (path == working || path.startsWith("$working/")) &&
                    canonical(path) == resolvedWorking + path.removePrefix(working)
                )
        }.getOrDefault(false)
    }
}
