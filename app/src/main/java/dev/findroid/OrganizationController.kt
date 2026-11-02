package dev.findroid

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal data class OrganizationItem(
    val path: String,
    val name: String,
    val entry: Entry? = null,
    val node: AnalysisNode? = null,
    val problem: String? = null,
) {
    // A provider may resolve the requested URI to a different spelling/ID. Keep the
    // saved reference key so it can be explicitly relinked to the observed document.
    val key = if (isDocumentPath(path)) path else entry?.organizationPath ?: organizationKey(path)
}

/** Pane-owned review state, retained across activity recreation; opening never writes metadata. */
internal class OrganizationController(
    private val model: FinderModel,
    private val scope: CoroutineScope,
) {
    val repair = ReferenceRepairController(model, scope)
    val locations = SavedLocationsController(model, scope)
    val hasReads
        get() = running || membersLoading || repair.running || locations.running

    private var job: Job? = null
    private var token = ListingControl()
    private var generation = 0
    private var frozen = emptyList<Entry>()
    var visible by mutableStateOf(false)
        private set

    var running by mutableStateOf(false)
        private set

    var applying by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var snapshot by mutableStateOf<OrganizationSnapshot?>(null)
        private set

    var items by mutableStateOf<List<OrganizationItem>>(emptyList())
        private set

    var changes by mutableStateOf<Map<String, TagChange>>(emptyMap())
        private set

    var clearAll by mutableStateOf(false)
        private set

    var excluded by mutableStateOf<Set<String>>(emptySet())
        private set

    var collectionId by mutableStateOf<String?>(null)
        private set

    var members by mutableStateOf<List<OrganizationItem>>(emptyList())
        private set

    var page by mutableIntStateOf(0)
    private var memberJob: Job? = null
    private var memberToken = ListingControl()
    private var memberGeneration = 0
    var memberSelection by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggleMember(path: String) {
        memberSelection =
            if (path in memberSelection) memberSelection - path else memberSelection + path
    }

    var progress by mutableStateOf("")
        private set

    var memberProgress by mutableStateOf("")
        private set

    var membersLoading by mutableStateOf(false)
        private set

    var exportReview by mutableStateOf<LibraryExport?>(null)
        private set

    private var exportPaths = emptySet<String>()
    var exportFilename by mutableStateOf("Findroid-library.json")
    var exportDestination by mutableStateOf(false)
    var exportSaving by mutableStateOf(false)
        private set

    var exportError by mutableStateOf<String?>(null)
        private set

    val exportSelectionCount
        get() = exportPaths.size

    fun openExport() {
        val value = model.session.tagStore.snapshot()
        exportPaths = included.map { it.key }.toSet()
        exportReview =
            LibraryExport(value, model.workspace.path, exportPaths.takeIf { it.isNotEmpty() })
        exportError = null
        exportDestination = false
    }

    fun exportScope(selected: Boolean) {
        exportReview = exportReview?.copy(selection = if (selected) exportPaths else null)
    }

    fun dismissExport() {
        if (!exportSaving) {
            exportReview = null
            exportDestination = false
        }
    }

    fun saveExport(parent: String) {
        val value = exportReview ?: return
        exportSaving = true
        exportError = null
        model.exportLibrary(value, parent, exportFilename) { failure ->
            exportSaving = false
            exportError = failure
            if (failure == null) {
                exportReview = null
                notice = "Library exported. Session Undo is available in the explorer."
            }
        }
    }

    private var selectionFresh by mutableStateOf(false)
    val included by derivedStateOf { items.filter { it.path !in excluded } }
    val changed by derivedStateOf { included.count { before(it.key) != after(it.key) } }
    val catalogReady
        get() = !applying && snapshot != null && model.libraryError == null

    val ready
        get() = catalogReady && !running && selectionFresh

    var completedSerial by mutableIntStateOf(0)
        private set

    fun before(path: String) = snapshot?.tags?.assignments?.get(path).orEmpty()

    fun after(path: String) = reviewedTags(before(path), changes, clearAll)

    fun open(
        entries: List<Entry> = model.actionEntries,
        initialPage: Int = if (entries.isEmpty()) 0 else 2,
    ) {
        if (applying || exportSaving) {
            visible = true
            return
        }
        close()
        visible = true
        frozen = entries.distinctBy { it.organizationPath }
        changes = emptyMap()
        clearAll = false
        excluded = emptySet()
        page = initialPage
        snapshot = null
        items = emptyList()
        notice = null
        collectionId =
            model.currentCollection?.id
                ?: collectionId?.takeIf { id -> model.collections.any { it.id == id } }
        memberSelection = emptySet()
        dismissExport()
        refresh()
    }

    fun refresh() {
        stop(false)
        error = null
        val id = ++generation
        val control = ListingControl().also { token = it }
        if (frozen.size > 10_000) {
            error = "Review up to 10,000 selected items at a time."
            return
        }
        running = true
        progress = "Reading library…"
        job = scope.launch {
            try {
                val catalog =
                    withContext(Dispatchers.IO) {
                        model.session.tagStore.reload().also {
                            check(model.session.tagStore.error == null) {
                                model.session.tagStore.error!!
                            }
                        }
                    }
                if (id != generation) return@launch
                snapshot = catalog
                model.syncLibrary()
                val rows =
                    withContext(Dispatchers.IO) {
                        frozen.mapIndexed { index, entry ->
                            val row = readLocation(entry.path, entry.name, control)
                            if (index % 32 == 0)
                                withContext(Dispatchers.Main.immediate) {
                                    if (id == generation)
                                        progress = "Reviewing ${index + 1} of ${frozen.size} items…"
                                }
                            row
                        }
                    }
                if (id != generation) return@launch
                items = rows
                selectionFresh = true
                changes = changes.filterKeys { name ->
                    catalog.tags.definitions.any { it.name == name }
                }
                collectionId?.let(::inspect)
            } catch (e: Exception) {
                if (e !is CancellationException && id == generation) {
                    error = e.message
                    model.syncLibrary()
                }
            } finally {
                if (id == generation) running = false
            }
        }
    }

    internal fun readLocation(
        path: String,
        name: String,
        control: ListingControl,
    ): OrganizationItem =
        try {
            control.check()
            check(model.organizationScopeAllowed(path)) { "Reconnect this location to review it." }
            check(
                isDocumentPath(path) ||
                    java.io.File(path).exists() ||
                    java.nio.file.Files.isSymbolicLink(java.io.File(path).toPath()),
            ) {
                "This file or folder is missing. Locate its replacement."
            }
            val entry = model.documents.read(path, control)
            OrganizationItem(path, entry.name, entry, model.analysisSource.node(entry))
        } catch (e: Exception) {
            control.check()
            if (e is CancellationException) throw e
            OrganizationItem(path, name, problem = e.message ?: "Item unavailable")
        }

    fun inspect(id: String) {
        memberToken.cancel()
        memberJob?.cancel()
        val request = ++memberGeneration
        if (collectionId != id) memberSelection = emptySet()
        collectionId = id
        members = emptyList()
        val paths = model.collections.firstOrNull { it.id == id }?.paths ?: emptyList()
        memberSelection = memberSelection.intersect(paths.toSet())
        val control = ListingControl().also { memberToken = it }
        membersLoading = true
        memberProgress = "Checking locations…"
        memberJob = scope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        val result = ArrayList<OrganizationItem>()
                        paths.forEachIndexed { index, path ->
                            result.add(readLocation(path, model.title(path), control))
                            if (index % 32 == 0) {
                                val partial = result.toList()
                                withContext(Dispatchers.Main.immediate) {
                                    if (request == memberGeneration) {
                                        members = partial
                                        memberProgress =
                                            "Checking ${index + 1} of ${paths.size} locations…"
                                    }
                                }
                            }
                        }
                        result.toList()
                    }
                if (request == memberGeneration) members = result
            } catch (e: Exception) {
                if (e !is CancellationException && request == memberGeneration) error = e.message
            } finally {
                if (request == memberGeneration) membersLoading = false
            }
        }
    }

    fun change(name: String, value: TagChange) {
        changes = changes + (name to value)
        error = null
    }

    fun clear(value: Boolean) {
        clearAll = value
        error = null
    }

    fun toggle(path: String) {
        excluded = if (path in excluded) excluded - path else excluded + path
    }

    fun excludeUnavailable() {
        excluded = excluded + items.filter { it.problem != null }.map { it.path }
    }

    fun resetDraft() {
        changes = emptyMap()
        clearAll = false
    }

    fun selectionFailed(message: String?) {
        applying = false
        error = message
    }

    fun completed(message: String, reset: Boolean = false) {
        completedSerial++
        applying = false
        notice = message
        if (reset) resetDraft()
        refresh()
    }

    fun applyTags() {
        if (!ready || changed == 0 || included.any { it.problem != null }) return
        applying = true
        model.applyReviewedTags(this, snapshot!!.tags, included, changes, clearAll)
    }

    fun add(id: String) {
        if (!ready || included.isEmpty() || included.any { it.problem != null }) return
        notice = null
        error = null
        applying = true
        model.addReviewedCollection(this, id, null, "Blue", included)
    }

    fun create(name: String, color: String, withSelection: Boolean) {
        if (
            !catalogReady ||
                withSelection &&
                    (!ready || included.isEmpty() || included.any { it.problem != null })
        )
            return
        notice = null
        error = null
        applying = true
        model.addReviewedCollection(
            this,
            null,
            name,
            color,
            if (withSelection) included else emptyList(),
        )
    }

    private fun catalogAction(
        label: String,
        tags: Boolean,
        route: Pair<String, String>? = null,
        edit: (TagStore) -> Unit,
    ) {
        if (!catalogReady || model.busy != null) return
        notice = null
        error = null
        applying = true
        model.editOrganizationLibrary(this, label, tags, route, edit)
    }

    fun saveTag(old: String?, name: String, color: String) =
        catalogAction("Tag saved.", true, old?.let { "@tag:$it" to "@tag:${name.trim()}" }) { store
            ->
            if (old == null) store.define(name, color) else store.rename(old, name, color)
        }

    fun saveCollection(id: String, name: String, color: String) =
        catalogAction("Collection saved.", false) { it.updateCollection(id, name, color) }

    fun deleteTag(name: String, expected: TagSnapshot) =
        catalogAction("Tag deleted.", true) { it.remove(name, expected) }

    fun deleteCollection(id: String, expected: FinderCollection) =
        catalogAction("Collection deleted.", false) { it.deleteCollection(id, expected) }

    fun removeMembers(id: String, paths: List<String>, expected: FinderCollection) =
        catalogAction("References removed.", false) {
            it.collectionItems(id, paths, false, expected)
        }

    fun stop(showError: Boolean = true) {
        repair.stop(showError)
        locations.stop(showError)
        ++generation
        token.cancel()
        job?.cancel()
        job = null
        ++memberGeneration
        memberToken.cancel()
        memberJob?.cancel()
        memberJob = null
        if (showError && (running || membersLoading))
            error = "Review stopped. Refresh before applying changes."
        running = false
        membersLoading = false
        selectionFresh = false
    }

    fun close() {
        if (applying || exportSaving || repair.applying) return
        stop(false)
        repair.dismiss()
        locations.dismiss()
        visible = false
    }
}
