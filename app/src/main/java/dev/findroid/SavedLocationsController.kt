package dev.findroid

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal data class SavedOrganizationLocation(
    val item: OrganizationItem,
    val tags: Set<String>,
    val collections: List<String>,
)

/** One metadata page is read at a time, including failed references; payloads are never opened. */
internal class SavedLocationsController(
    private val model: FinderModel,
    private val scope: CoroutineScope,
) {
    var visible by mutableStateOf(false)
        private set

    var running by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
        private set

    var page by mutableIntStateOf(0)
        private set

    var total by mutableIntStateOf(0)
        private set

    var rows by mutableStateOf<List<SavedOrganizationLocation>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var progress by mutableStateOf("")
        private set

    val pageSize = 200
    private var job: Job? = null
    private var token = ListingControl()
    private var generation = 0

    fun open() {
        visible = true
        page = 0
        load()
    }

    fun search(value: String) {
        query = value.take(512)
        page = 0
        load(true)
    }

    fun move(delta: Int) {
        if (!running) {
            page = (page + delta).coerceIn(0, ((total - 1).coerceAtLeast(0) / pageSize))
            load()
        }
    }

    fun load(debounce: Boolean = false) {
        stop(false)
        error = null
        rows = emptyList()
        running = true
        val id = ++generation
        val control = ListingControl().also { token = it }
        val filter = query.trim()
        val requestedPage = page
        job = scope.launch {
            try {
                if (debounce) delay(200)
                withContext(Dispatchers.IO) {
                    val canonicalFilter =
                        if (filter.startsWith("/")) organizationKey(filter) else filter
                    val catalog = model.session.tagStore.reload()
                    check(model.session.tagStore.error == null) { model.session.tagStore.error!! }
                    val memberships = mutableMapOf<String, MutableList<String>>()
                    catalog.collections.forEach { c ->
                        c.paths.forEach { path ->
                            memberships.getOrPut(path) { mutableListOf() }.add(c.name)
                        }
                    }
                    val paths =
                        (catalog.tags.assignments.keys + memberships.keys)
                            .filter { path ->
                                control.check()
                                filter.isBlank() ||
                                    path.contains(filter, true) ||
                                    path.contains(canonicalFilter, true) ||
                                    catalog.tags.assignments[path].orEmpty().any {
                                        it.contains(filter, true)
                                    } ||
                                    memberships[path].orEmpty().any { it.contains(filter, true) }
                            }
                            .sorted()
                    val last = (paths.size - 1).coerceAtLeast(0) / pageSize
                    val current = requestedPage.coerceAtMost(last)
                    withContext(Dispatchers.Main.immediate) {
                        if (id == generation) {
                            total = paths.size
                            page = current
                            model.syncLibrary()
                        }
                    }
                    val result = ArrayList<SavedOrganizationLocation>()
                    paths.drop(current * pageSize).take(pageSize).forEachIndexed { index, path ->
                        control.check()
                        val item = model.organization.readLocation(path, model.title(path), control)
                        result.add(
                            SavedOrganizationLocation(
                                item,
                                catalog.tags.assignments[path].orEmpty(),
                                memberships[path].orEmpty(),
                            ),
                        )
                        if (index % 16 == 0) {
                            val partial = result.toList()
                            withContext(Dispatchers.Main.immediate) {
                                if (id == generation) {
                                    rows = partial
                                    progress =
                                        "Checking ${index + 1} of ${minOf(pageSize, paths.size - current * pageSize)} locations…"
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (id == generation) rows = result.toList()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && id == generation)
                    error = e.message ?: "Couldn't read saved locations."
            } finally {
                if (id == generation) running = false
            }
        }
    }

    fun stop(showError: Boolean = true) {
        ++generation
        token.cancel()
        job?.cancel()
        job = null
        if (showError && running)
            error = "Location checking stopped. Refresh to check this page again."
        running = false
    }

    fun dismiss() {
        stop(false)
        visible = false
    }
}
