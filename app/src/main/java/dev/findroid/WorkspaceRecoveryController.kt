package dev.findroid

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** One controller per window; selection/review survives Activity recreation and pane changes. */
internal class WorkspaceRecoveryController(private val model: FinderModel, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var control = ListingControl()
    private var generation = 0
    var visible by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var applying by mutableStateOf(false)
        private set
    var workspace by mutableStateOf<SavedWorkspace?>(null)
        private set
    var statuses by mutableStateOf<List<WorkspaceDestinationStatus>>(emptyList())
        private set
    var replacements by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var review by mutableStateOf<WorkspaceRecoveryReview?>(null)
        private set
    var keepUnavailable by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val missing get() = statuses.filter { it.problem != null }

    private fun stop() {
        generation++; control.cancel(); job?.cancel(); job = null; working = false
    }
    fun close() {
        if (applying) return
        stop(); visible = false; workspace = null; review = null
    }
    private fun run(work: suspend (Int, ListingControl) -> Unit) {
        if (applying) return
        stop()
        val id = generation
        val token = ListingControl().also { control = it }
        working = true; error = null
        job = scope.launch {
            try { work(id, token) }
            catch (_: CancellationException) { }
            catch (failure: Exception) { if (id == generation) error = failure.message ?: "Couldn't check this workspace." }
            finally { if (id == generation) working = false }
        }
    }
    fun open(id: String) {
        if (applying || model.session.workspaceRepairing.value) {
            model.message = "Wait for the workspace repairs to finish saving."
            return
        }
        val value = model.savedWorkspaces.firstOrNull { it.id == id } ?: return
        workspace = value; statuses = emptyList(); replacements = emptyMap(); review = null; keepUnavailable = false
        visible = true
        run { current, token ->
            val checked = checkDestinations(value, token)
            ensureCurrent(value)
            if (current != generation) return@run
            if (checked.none { it.problem != null }) {
                visible = false
                model.restoreWorkspaceSnapshot(value)
            } else statuses = checked
        }
    }
    private fun ensureCurrent(value: SavedWorkspace) {
        check(model.savedWorkspaces.firstOrNull { it.id == value.id } == value) { "This saved workspace changed. Close this review and choose it again." }
    }
    private suspend fun checkDestinations(value: SavedWorkspace, token: ListingControl): List<WorkspaceDestinationStatus> {
        val known = setOf("@recent", "@trash") + model.tagDefinitions.map { "@tag:${it.name}" } +
            model.collections.map { "@collection:${it.id}" }
        val searches = model.smartFolders.associateBy { "@smart:${it.id}" }
        return withContext(Dispatchers.IO) {
            fun folderProblem(path: String): String? = try {
                token.check()
                check(model.allowedPath(path)) { "Reconnect this storage folder or choose a replacement." }
                check(model.documents.read(path, token).folder) { "This location is now a file. Choose a replacement folder." }
                null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failure.message ?: "This folder is unavailable. Reconnect storage or choose a replacement."
            }
            value.openDestinations().map { destination ->
                token.check()
                val search = searches[destination.path]
                val problem = when {
                    search != null -> folderProblem(search.root)?.let { "Saved search folder ${search.root}: $it" }
                    destination.path.startsWith("@") -> if (destination.path in known) null else "This tag, collection or saved search is no longer available."
                    else -> folderProblem(destination.path)
                }
                WorkspaceDestinationStatus(destination, problem, search)
            }
        }
    }

    fun replacement(path: String, target: String?) {
        if (working || applying || missing.none { it.destination.path == path }) return
        replacements = if (target == null) replacements - path else replacements + (path to target)
        review = null; keepUnavailable = false; error = null
    }
    fun acknowledgeUnavailable(value: Boolean) { if (!working && !applying) keepUnavailable = value }
    fun edit() { if (!working && !applying) { review = null; error = null; keepUnavailable = false } }
    fun recheck() {
        val value = workspace ?: return
        run { current, token ->
            val checked = checkDestinations(value, token)
            ensureCurrent(value)
            if (current == generation) {
                statuses = checked; replacements = replacements.filterKeys { path -> checked.any { it.destination.path == path && it.problem != null } }
                review = null; keepUnavailable = false
                if (checked.none { it.problem != null }) { visible = false; model.restoreWorkspaceSnapshot(value) }
            }
        }
    }
    fun prepareReview() {
        val value = workspace ?: return
        val choices = replacements
        run { current, token ->
            val originals = checkDestinations(value, token)
            ensureCurrent(value)
            if (originals != statuses) {
                if (current == generation) {
                    statuses = originals; replacements = choices.filterKeys { p -> originals.any { it.destination.path == p && it.problem != null } }
                    keepUnavailable = false
                }
                error("Storage availability changed, or a saved search was edited. Check the updated locations and review again.")
            }
            val searchRules = originals.mapNotNull { status -> status.search?.let { status.destination.path to it.rules } }.toMap()
            val repaired = value.repairDestinations(originals.filter { it.problem != null }.map { it.destination.path }.toSet(), choices, searchRules)
            val checked = checkDestinations(repaired, token)
            check(checked.filter { it.problem != null }.none { it.destination.path in choices.values }) { "A replacement folder is unavailable. Choose it again or reconnect its storage." }
            if (current == generation) review = WorkspaceRecoveryReview(value, originals, checked, choices, repaired)
        }
    }
    fun apply(save: Boolean) {
        val prepared = review ?: return
        if (working || applying) return
        if (prepared.unresolved > 0 && !keepUnavailable) { error = "Choose whether to keep the unavailable locations before opening."; return }
        run { _, token ->
            ensureCurrent(prepared.original)
            val originals = checkDestinations(prepared.original, token)
            val destinations = checkDestinations(prepared.repaired, token)
            check(originals == prepared.originals && destinations == prepared.destinations) { "Storage availability changed, or a saved search was edited. Edit the choices and review again." }
            ensureCurrent(prepared.original)
            if (!save || prepared.replacements.isEmpty()) {
                visible = false; model.restoreWorkspaceSnapshot(prepared.repaired)
                return@run
            }
            val session = model.session
            check(model.busy == null && !model.workspaceSaving && !session.workspaceRepairing.value) { "Wait for file operations and workspace changes to finish, then try again." }
            check(model.workspaceError == null) { model.workspaceError ?: "The saved workspace library needs attention before repairs can be saved." }
            check(WorkspaceMutationGate.beginApp()) { "Another app is changing Workspace. Try again shortly." }
            val before = model.savedWorkspaces
            val after = before.map { if (it.id == prepared.original.id) prepared.repaired else it }
            applying = true; session.workspaceRepairing.value = true; session.workspaceWrites.intValue++
            session.busy.value = "Repairing saved workspace…"
            // A single AtomicFile publication finishes even when its Activity closes.
            TransferRuntime.scope.launch {
                try {
                    session.workspaceMutex.withLock {
                        withContext(Dispatchers.IO + NonCancellable) {
                            val disk = session.workspaceStore.load()
                            check(session.workspaceStore.error == null) { session.workspaceStore.error!! }
                            check(disk == before) { "The saved workspace library changed on disk. Its contents were kept. Reopen Findroid before saving repairs." }
                            session.workspaceStore.write(after)
                        }
                        session.savedWorkspaces.value = after
                    }
                    visible = false
                    if (model in session.panes) model.restoreWorkspaceSnapshot(prepared.repaired)
                } catch (failure: Exception) {
                    error = failure.message ?: "Couldn't save these workspace repairs. The original layout was kept."
                    if (session.workspaceStore.error != null) session.workspaceError.value = session.workspaceStore.error
                } finally {
                    session.workspaceWrites.intValue--; session.workspaceRepairing.value = false
                    session.busy.value = null; applying = false; WorkspaceMutationGate.endApp()
                }
            }
        }
    }
}
