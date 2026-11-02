package dev.findroid

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal data class SetupRestoreReview(
    val before: SetupSnapshot,
    val merged: SetupMerge,
    val resolution: SetupResolution,
    val fingerprint: String,
    val files: Map<String, ByteArray>,
)

/** Pane-owned review, retained by the application across Activity recreation. */
internal class SetupController(private val model: FinderModel, private val scope: CoroutineScope) {
    private val app = model.getApplication<Application>()
    internal val storage = SetupStorage(app)
    private var job: Job? = null
    private var control = ListingControl()
    private var generation = 0
    var visible by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var applying by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var archive by mutableStateOf<SetupArchive?>(null)
        private set
    var exportBytes by mutableStateOf<ByteArray?>(null)
        private set
    var exportArchive by mutableStateOf<SetupArchive?>(null)
        private set
    var targets by mutableStateOf<Map<String, String?>>(emptyMap())
        private set
    var rootStatus by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var sections by mutableStateOf(SetupSection.entries.toSet())
        private set
    var replace by mutableStateOf(false)
        private set
    var review by mutableStateOf<SetupRestoreReview?>(null)
        private set
    var keepUnavailable by mutableStateOf(false)
        private set
    var restoredTabsId by mutableStateOf<String?>(null)
        private set
    val hasPrevious get() = storage.previous.isFile

    fun open() {
        if (applying) return
        stop()
        visible = true; error = null; notice = null; archive = null; review = null
        exportBytes = null; exportArchive = null; restoredTabsId = null
    }
    fun close() {
        if (applying) return
        stop(); visible = false; archive = null; review = null; exportBytes = null; exportArchive = null
    }
    fun home() {
        if (applying) return
        stop(); archive = null; review = null; exportBytes = null; exportArchive = null; error = null
    }
    fun stop() {
        if (applying) return
        generation++; control.cancel(); job?.cancel(); job = null; working = false
    }
    private fun run(label: String, work: suspend (Int, ListingControl) -> Unit) {
        if (applying) return
        stop(); val id = generation; val token = ListingControl().also { control = it }
        error = null; notice = null; working = true; progress = label
        job = scope.launch {
            try { work(id, token) }
            catch (failure: CancellationException) { /* Explicit cancellation leaves setup untouched. */ }
            catch (failure: Exception) { if (id == generation) error = failure.message ?: "Couldn't complete this setup action." }
            finally { if (id == generation) working = false }
        }
    }
    private suspend fun count(id: Int, done: Int, total: Int) = withContext(Dispatchers.Main.immediate) {
        if (id == generation) progress = "Checking saved references · $done of $total"
    }

    fun prepareExport() {
        var snapshot: SetupSnapshot
        try { snapshot = storage.snapshot(model) } catch (failure: Exception) { error = failure.message; return }
        archive = null; review = null
        run("Preparing setup backup…") { id, token ->
            val prepared = withContext(Dispatchers.IO) {
                val fingerprint = storage.transaction.fingerprint()
                val portable = portableSetup(model, snapshot, token) { done, total -> count(id, done, total) }
                token.check()
                val bytes = SetupJson.encode(portable).toByteArray(Charsets.UTF_8)
                // Export the same strict format that import will accept.
                SetupJson.decode(bytes.toString(Charsets.UTF_8))
                check(storage.transaction.fingerprint() == fingerprint) { "Setup changed while preparing this backup. Prepare it again." }
                portable to bytes
            }
            if (id == generation) { exportArchive = prepared.first; exportBytes = prepared.second }
        }
    }
    fun export(uri: Uri) {
        val bytes = exportBytes ?: return
        run("Saving setup backup…") { id, token ->
            withContext(Dispatchers.IO) {
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the backup destination.")
                output.use { stream ->
                    var offset = 0
                    while (offset < bytes.size) {
                        token.check()
                        val count = minOf(16 * 1024, bytes.size - offset)
                        stream.write(bytes, offset, count); offset += count
                    }
                    stream.flush()
                }
            }
            if (id == generation) { notice = "Setup backup saved. Your files remain in their folders."; exportBytes = null; exportArchive = null }
        }
    }
    fun load(uri: Uri) = run("Reading setup backup…") { id, token ->
        val value = withContext(Dispatchers.IO) {
            token.stream { signal -> app.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream() ?: error("Couldn't open this backup.") }
                .use { SetupJson.decode(SetupJsonTree.read(it)) }
        }
        loaded(id, token, value)
    }
    internal fun loadText(text: String) = run("Reading setup backup…") { id, token ->
        val value = withContext(Dispatchers.IO) { SetupJson.decode(text) }
        loaded(id, token, value)
    }
    fun loadPrevious() = run("Reading previous setup…") { id, token ->
        val value = withContext(Dispatchers.IO) { storage.previous.inputStream().use { SetupJson.decode(SetupJsonTree.read(it)) } }
        loaded(id, token, value)
    }
    private suspend fun loaded(id: Int, token: ListingControl, value: SetupArchive) {
        val defaults = linkedMapOf<String, String?>()
        val statuses = linkedMapOf<String, String>()
        withContext(Dispatchers.IO) {
            value.roots.forEach { root ->
                token.check()
                val target = if (root.kind == SetupRootKind.WORKSPACE) model.workspace.path else root.source
                val available = runCatching { model.allowedPath(target) && model.documents.read(target, token).folder }.getOrDefault(false)
                defaults[root.id] = target.takeIf { available }
                statuses[root.id] = if (available) "Available" else "Unavailable · reconnect or choose a replacement"
            }
        }
        if (id == generation) {
            archive = value; review = null; targets = defaults; rootStatus = statuses
            sections = SetupSection.entries.toSet(); replace = false; keepUnavailable = false; exportBytes = null; exportArchive = null
        }
    }
    fun target(id: String, path: String?) {
        if (working || applying) return
        targets = targets + (id to path); review = null; keepUnavailable = false
        rootStatus = rootStatus + (id to if (path == null) "Keep original references" else "Replacement selected · checked during review")
    }
    fun section(section: SetupSection, selected: Boolean) {
        if (working || applying) return
        sections = if (selected) sections + section else sections - section; review = null; keepUnavailable = false
    }
    fun mode(replace: Boolean) { if (!working && !applying) { this.replace = replace; review = null; keepUnavailable = false } }
    fun acknowledgeUnavailable(value: Boolean) { if (!applying) keepUnavailable = value }
    fun editReview() { if (!applying) { review = null; keepUnavailable = false; error = null } }

    fun prepareReview() {
        val source = archive ?: return
        if (sections.isEmpty()) { error = "Choose at least one setup section."; return }
        val before = try { storage.snapshot(model) } catch (failure: Exception) { error = failure.message; return }
        val chosen = sections; val mappedRoots = targets; val replacing = replace
        run("Reviewing root mappings and changes…") { id, token ->
            val value = withContext(Dispatchers.IO) {
                val fingerprint = storage.transaction.fingerprint()
                val resolution = resolveSetup(model, source, mappedRoots, chosen, token) { done, total -> count(id, done, total) }
                val incoming = source.snapshot.mapLocations(resolution.paths)
                val merged = mergeSetup(before, incoming, chosen, replacing, source.id)
                val normalized = normalizedOrganization(merged.value.organization).let { org ->
                    org.copy(tags = org.tags.copy(definitions = org.tags.definitions.sortedBy { setupNameKey(it.name) }), collections = org.collections.sortedBy { setupNameKey(it.name) })
                }
                val final = merged.copy(value = merged.value.copy(organization = normalized).checked())
                token.check()
                val beforeArchive = portableSetup(model, before, token) { done, total -> count(id, done, total) }
                val files = storage.stage(final.value, beforeArchive)
                check(storage.transaction.fingerprint() == fingerprint) { "The current setup changed. Review the restore again." }
                SetupRestoreReview(before, final, resolution.withVirtualReferences(final.value, before), fingerprint, files)
            }
            if (id == generation) { review = value; keepUnavailable = false }
        }
    }

    fun applyReview() {
        val prepared = review ?: return
        val source = archive ?: return
        if (working || applying || prepared.merged.changedSections.isEmpty()) return
        if (prepared.resolution.issueCount > 0 && !keepUnavailable) { error = "Review unavailable references and choose whether to keep them."; return }
        val chosen = sections; val mappedRoots = targets
        run("Rechecking storage before restore…") { _, token ->
            val verified = withContext(Dispatchers.IO) { resolveSetup(model, source, mappedRoots, chosen, token).withVirtualReferences(prepared.merged.value, prepared.before) }
            check(verified == prepared.resolution) { "Storage availability changed. Edit the choices and review the restore again." }
            check(model.windowState.id == "main" && model.session.panes.map { it.windowState.id }.toSet() == setOf("main")) { "Close other Findroid windows and restore setup from the main window." }
            check(storage.snapshot(model) == prepared.before) { "The current setup changed. Refresh the restore review before continuing." }
            check(WorkspaceMutationGate.beginApp()) { "Another app is changing Workspace. Try again shortly." }
            applying = true; model.session.busy.value = "Restoring setup…"
            // The journal must finish or roll back even if the originating Activity is closed.
            TransferRuntime.scope.launch {
                try {
                    withContext(Dispatchers.IO + NonCancellable) { storage.transaction.apply(prepared.fingerprint, prepared.files, storage.preferences(prepared.merged.value)) }
                    storage.publish(model, prepared.merged.value)
                    restoredTabsId = prepared.merged.value.workspaces.firstOrNull { it.id == setupStableId(source.id, "open-tabs") }?.id
                    notice = "Setup restored. A recovery copy of the previous setup is available here. Open tabs were saved as a workspace. Session Undo history was reset."
                    model.message = "Setup restored"
                    review = null; archive = null
                } catch (failure: Exception) { error = failure.message ?: "Couldn't restore setup. The previous setup was kept." }
                finally { model.session.busy.value = null; applying = false; WorkspaceMutationGate.endApp() }
            }
        }
    }
    fun openRestoredTabs() {
        restoredTabsId?.let(model::openWorkspace)
        close()
    }
}
