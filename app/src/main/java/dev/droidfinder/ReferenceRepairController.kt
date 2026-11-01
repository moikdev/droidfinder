package dev.droidfinder

import androidx.compose.runtime.*
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.*

internal data class ReferenceReview(
    val repair: ReferenceRepair,
    val original: OrganizationItem,
    val replacement: OrganizationItem,
)

/** Selection, preparation and commit are separate; browsing and opening never change metadata. */
internal class ReferenceRepairController(
    private val model: FinderModel,
    private val scope: CoroutineScope,
) {
    var visible by mutableStateOf(false)
        private set

    var picking by mutableStateOf(true)
        private set

    var running by mutableStateOf(false)
        private set

    var applying by mutableStateOf(false)
        private set

    var source by mutableStateOf("")
        private set

    var folder by mutableStateOf("")
        private set

    var folderEntry by mutableStateOf<Entry?>(null)
        private set

    var query by mutableStateOf("")
        private set

    var hidden by mutableStateOf(false)
        private set

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    var listing by mutableStateOf(ListingProgress())
        private set

    var review by mutableStateOf<ReferenceReview?>(null)
        private set

    var carryTags by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private var control = ListingControl()
    private var generation = 0
    private var budget = ListingBudget(1000, 50_000)

    fun open(path: String) {
        if (model.busy != null || applying) return
        stop(false)
        source = organizationKey(path)
        visible = true
        picking = true
        carryTags = true
        review = null
        hidden = model.showHidden
        query = ""
        chooseFolder(model.folderPath ?: model.workspace.path)
    }

    fun chooseFolder(path: String) {
        if (applying) return
        folder = path
        query = ""
        budget = ListingBudget(1000, 50_000)
        browse()
    }

    fun search(value: String) {
        query = value.take(512)
        budget = ListingBudget(1000, 50_000)
        browse(debounce = true)
    }

    fun showHidden(value: Boolean) {
        hidden = value
        browse()
    }

    fun more() {
        budget =
            ListingBudget(
                (budget.results + 1000).coerceAtMost(20_000),
                (budget.scanned + 50_000).coerceAtMost(200_000),
            )
        browse()
    }

    fun browse(debounce: Boolean = false) {
        stop(false)
        picking = true
        error = null
        entries = emptyList()
        folderEntry = null
        listing = ListingProgress()
        val requestFolder = folder
        val request =
            ListingRequest(
                roots = listOf(requestFolder),
                recursive = false,
                hidden = hidden,
                rules = SearchRules(name = query),
                tags = emptyMap(),
                styles = emptyMap(),
                comparator =
                    compareByDescending<Entry> { it.folder }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                budget = budget,
            )
        val id = ++generation
        val token = ListingControl().also { control = it }
        running = true
        job = scope.launch {
            try {
                if (debounce) delay(200)
                withContext(Dispatchers.IO) {
                    check(model.allowedPath(requestFolder)) {
                        "Reconnect this location to browse it."
                    }
                    val root = model.documents.read(requestFolder, token)
                    check(root.folder) { "Choose a folder to browse." }
                    withContext(Dispatchers.Main.immediate) {
                        if (id == generation) folderEntry = root
                    }
                    val loader =
                        DirectoryLoader(
                            object : ListingSource {
                                override suspend fun children(
                                    path: String,
                                    control: ListingControl,
                                    visit: suspend (Entry) -> Boolean,
                                ): DirectoryReply =
                                    model.documents.visitChildren(path, control, visit)

                                override fun read(path: String, control: ListingControl) =
                                    model.documents.read(path, control)

                                override fun input(
                                    entry: Entry,
                                    control: ListingControl,
                                ): InputStream =
                                    kotlin.error("Reference browsing never reads file contents")

                                override fun traversable(entry: Entry) = false
                            },
                        )
                    loader
                        .load(request, token) { batch ->
                            withContext(Dispatchers.Main.immediate) {
                                if (id == generation) {
                                    entries = batch.entries
                                    listing = batch.progress
                                }
                            }
                        }
                        .also { batch ->
                            withContext(Dispatchers.Main.immediate) {
                                if (id == generation) {
                                    entries = batch.entries
                                    listing = batch.progress
                                }
                            }
                        }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && id == generation)
                    error = e.message ?: "Couldn't browse this location."
            } finally {
                if (id == generation) running = false
            }
        }
    }

    fun openPath(path: String) {
        stop(false)
        error = null
        val id = ++generation
        val token = ListingControl().also { control = it }
        running = true
        job = scope.launch {
            try {
                val item =
                    withContext(Dispatchers.IO) {
                        model.organization.readLocation(path, model.title(path), token)
                    }
                check(item.entry != null) { item.problem ?: "This location is unavailable." }
                if (id == generation) {
                    if (item.entry.folder) chooseFolder(item.entry.path) else select(item.entry)
                }
            } catch (e: Exception) {
                if (e !is CancellationException && id == generation) error = e.message
            } finally {
                if (id == generation) running = false
            }
        }
    }

    fun select(entry: Entry) {
        if (applying) return
        stop(false)
        picking = false
        review = null
        error = null
        val id = ++generation
        val token = ListingControl().also { control = it }
        running = true
        job = scope.launch {
            try {
                val value =
                    withContext(Dispatchers.IO) {
                        val catalog = model.session.tagStore.reload()
                        check(model.session.tagStore.error == null) {
                            model.session.tagStore.error!!
                        }
                        val original =
                            model.organization.readLocation(source, model.title(source), token)
                        val observed =
                            model.organization.readLocation(entry.path, entry.name, token)
                        // The source keeps its saved key; the chosen replacement uses the
                        // current document identity returned by its metadata query.
                        val replacement =
                            observed.entry?.let { observed.copy(path = it.path) } ?: observed
                        check(replacement.problem == null && replacement.node != null) {
                            replacement.problem ?: "The replacement is unavailable."
                        }
                        ReferenceReview(
                            ReferenceRepair(catalog, source, replacement.key, carryTags),
                            original,
                            replacement,
                        )
                    }
                if (id == generation) {
                    model.syncLibrary()
                    review = value
                }
            } catch (e: Exception) {
                if (e !is CancellationException && id == generation)
                    error = e.message ?: "Couldn't prepare this repair."
            } finally {
                if (id == generation) running = false
            }
        }
    }

    fun tags(value: Boolean) {
        if (applying) return
        carryTags = value
        review = review?.let { it.copy(repair = it.repair.copy(carryTags = value)) }
    }

    fun changeReplacement() {
        if (!applying) browse()
    }

    fun refreshReview() {
        review?.replacement?.entry?.let(::select)
    }

    fun apply() {
        val value = review ?: return
        if (
            running ||
                applying ||
                error != null ||
                model.busy != null ||
                (value.repair.affectedCollections.isEmpty() &&
                    (!value.repair.carryTags || value.repair.sourceTags.isEmpty()))
        )
            return
        applying = true
        model.repairOrganization(value) { failure ->
            applying = false
            error = failure
            if (failure == null) {
                visible = false
                review = null
                model.organization.completed(
                    "Reference relinked. Original files were kept. Session Undo is available.",
                )
                if (model.organization.locations.visible) model.organization.locations.load()
            }
        }
    }

    fun stop(showError: Boolean = true) {
        val wasRunning = running
        ++generation
        control.cancel()
        job?.cancel()
        job = null
        running = false
        if (showError && wasRunning) {
            review = null
            error = "Preparation stopped. Choose the replacement again before applying."
        }
    }

    fun dismiss() {
        if (applying) return
        stop(false)
        visible = false
        review = null
    }
}
