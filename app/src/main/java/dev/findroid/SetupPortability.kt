package dev.findroid

import android.annotation.SuppressLint
import androidx.core.net.toUri
import android.provider.DocumentsContract
import java.io.File
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException

@SuppressLint("SdCardPath") // Recognize legacy saved /sdcard references as a portable alias.
internal suspend fun portableSetup(
    model: FinderModel,
    value: SetupSnapshot,
    control: ListingControl,
    progress: suspend (Int, Int) -> Unit = { _, _ -> },
): SetupArchive {
    val roots = linkedMapOf<String, SetupRoot>()
    fun root(path: String, label: String, kind: SetupRootKind): SetupRoot = roots.getOrPut(path) {
        SetupRoot(setupStableId("root", path), renameFieldText(label, 120).ifBlank { "Saved location" }, path, kind)
    }
    val workspace = listOf(model.workspace.absolutePath, model.workspace.canonicalPath).distinct()
    workspace.forEach { root(it, "Workspace", SetupRootKind.WORKSPACE) }
    val local = (listOf(model.deviceRoot.absolutePath, model.deviceRoot.canonicalPath, "/sdcard") + model.volumes.map { it.absolutePath }).distinct()
    local.forEach { root(it, if (it in listOf(model.deviceRoot.absolutePath, model.deviceRoot.canonicalPath, "/sdcard")) "Device storage" else File(it).name, SetupRootKind.LOCAL) }
    model.documents.locations.forEach { root(it.uri, it.name, SetupRootKind.PROVIDER) }
    val names = mutableMapOf<String, String>()
    val paths = value.locations().sorted()
    val references = paths.mapIndexed { index, path ->
        control.check()
        if (index % 20 == 0) progress(index, paths.size)
        if (!isDocumentPath(path)) {
            val p = Paths.get(path)
            val chosen = roots.values.filter { it.kind != SetupRootKind.PROVIDER && p.startsWith(Paths.get(it.source)) }.maxByOrNull { it.source.length }
                ?: run {
                    val parts = p.toList().map { it.toString() }
                    val base = when {
                        parts.size >= 4 && parts.take(2) == listOf("storage", "emulated") -> "/" + parts.take(3).joinToString("/")
                        parts.size >= 2 && parts.first() == "storage" -> "/" + parts.take(2).joinToString("/")
                        else -> p.parent?.toString() ?: "/"
                    }
                    root(base, "Saved local location", SetupRootKind.LOCAL)
                }
            val relative = Paths.get(chosen.source).relativize(p)
            SetupReference(path, chosen.id, if (relative.toString().isEmpty()) emptyList() else relative.map { it.toString() })
        } else {
            val chosen = roots.values.firstOrNull { it.kind == SetupRootKind.PROVIDER && model.documents.sameTree(path, it.source) }
                ?: run {
                    val uri = path.toUri()
                    val source = runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)).toString() }.getOrDefault(path)
                    root(source, "Saved provider location", SetupRootKind.PROVIDER)
                }
            val segments = mutableListOf<String>()
            var cursor = path
            val visited = mutableSetOf<String>()
            var resolved = true
            while (cursor != chosen.source) {
                control.check()
                if (!visited.add(cursor) || visited.size > 128) { resolved = false; break }
                val parent = model.documents.parent(cursor)
                if (parent == null) { resolved = false; break }
                try {
                    val name = names.getOrPut(cursor) { model.documents.read(cursor, control).name }
                    if (!setupSegments(listOf(name))) { resolved = false; break }
                    segments += name
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    resolved = false; break
                }
                cursor = parent
            }
            SetupReference(path, chosen.id, if (resolved) segments.reversed() else null)
        }
    }
    progress(paths.size, paths.size)
    val used = references.map { it.root }.toSet()
    // Keep Workspace even for an empty setup, so the format has a trusted default root.
    return SetupArchive(roots = roots.values.filter { it.id in used || it.source == model.workspace.absolutePath }, references = references, snapshot = value)
}

internal data class SetupReferenceIssue(val original: String, val target: String, val reason: String)
internal data class SetupResolution(val paths: Map<String, String>, val issues: List<SetupReferenceIssue>, val issueCount: Int, val changed: Int)

internal fun SetupSnapshot.sectionLocations(sections: Set<SetupSection>): Set<String> = copy(
    sidebar = if (SetupSection.SIDEBAR in sections) sidebar else SidebarLayout(),
    organization = if (SetupSection.ORGANIZATION in sections) organization else OrganizationSnapshot(TagSnapshot(emptyList(), emptyMap()), emptyList()),
    searches = if (SetupSection.SEARCHES in sections) searches else emptyList(),
    workspaces = if (SetupSection.WORKSPACES in sections) workspaces else emptyList(),
    openTabs = openTabs.takeIf { SetupSection.OPEN_TABS in sections },
    folderViews = if (SetupSection.APPEARANCE in sections) folderViews else emptyMap(),
    folderStyles = if (SetupSection.APPEARANCE in sections) folderStyles else emptyMap(),
).locations()

private class SetupResolutionLimit : IllegalStateException("Root mapping scanned 200,000 provider entries. Restore fewer sections or choose a closer replacement root.")

/** Resolve each name through the selected provider. Document IDs are never assembled from names. */
internal suspend fun resolveSetup(
    model: FinderModel,
    archive: SetupArchive,
    targets: Map<String, String?>,
    sections: Set<SetupSection>,
    control: ListingControl,
    progress: suspend (Int, Int) -> Unit = { _, _ -> },
): SetupResolution {
    val wanted = archive.snapshot.sectionLocations(sections)
    val roots = archive.roots.associateBy { it.id }
    val directories = mutableMapOf<String, Map<String, List<Entry>>>()
    var scanned = 0
    suspend fun child(parent: String, name: String): Entry {
        var children = directories[parent]
        if (children == null) {
            val rows = mutableListOf<Entry>()
            val reply = model.documents.visitChildren(parent, control) {
                control.check()
                if (++scanned > 200_000) throw SetupResolutionLimit()
                rows += it
                true
            }
            check(!reply.loading && reply.error == null) { reply.error ?: "This provider is still loading. Retry after it finishes." }
            children = rows.groupBy { it.name }
            directories[parent] = children
        }
        val matches = children[name].orEmpty()
        check(matches.size == 1) { if (matches.isEmpty()) "A file or folder is missing under the replacement root." else "The provider has several items with this name; repair this reference individually." }
        return matches.single()
    }
    val checkedRoots = mutableSetOf<String>()
    val mapped = linkedMapOf<String, String>()
    val issues = mutableListOf<SetupReferenceIssue>()
    var issueCount = 0
    var changed = 0
    val refs = archive.references.filter { it.path in wanted }
    refs.forEachIndexed { index, ref ->
        control.check()
        if (index % 20 == 0) progress(index, refs.size)
        val root = roots.getValue(ref.root)
        val target = targets[root.id]
        var path = ref.path
        var reason: String? = null
        try {
            if (target != null && target != root.source) {
                val segments = if (ref.path == root.source) emptyList() else ref.segments ?: error("No relative path was recorded for this provider reference; use individual repair after restore.")
                if (target !in checkedRoots) {
                    check(model.allowedPath(target) && model.documents.read(target, control).folder) { "The replacement root is no longer available." }
                    checkedRoots += target
                }
                if (!isDocumentPath(target)) path = Paths.get(target, *segments.toTypedArray()).normalize().toString()
                else {
                    path = target
                    for ((i, name) in segments.withIndex()) {
                        val item = child(path, name)
                        check(i == segments.lastIndex || item.folder) { "A folder in the relative path is now a file." }
                        path = item.path
                    }
                }
            }
            check(model.allowedPath(path)) { "Reconnect this storage or choose a replacement root." }
            model.documents.read(path, control)
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is SetupResolutionLimit) throw failure
            reason = failure.message ?: "This reference is unavailable."
            // A local missing destination remains a useful portable path. Opaque provider IDs
            // can only be replaced after a unique complete traversal; retain the original otherwise.
            if (isDocumentPath(target.orEmpty())) path = ref.path
        }
        if (reason != null) {
            issueCount++
            if (issues.size < 100) issues += SetupReferenceIssue(ref.path, path, reason)
        }
        mapped[ref.path] = path
        if (path != ref.path) changed++
    }
    progress(refs.size, refs.size)
    return SetupResolution(mapped, issues, issueCount, changed)
}
