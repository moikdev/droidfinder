package dev.droidfinder

import androidx.compose.runtime.*
import androidx.exifinterface.media.ExifInterface
import java.io.FilterInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*

internal class RenameController(private val model: FinderModel, private val scope: CoroutineScope) {
    private val store
        get() = model.session.renamePresetStore

    private var catalogJob: Job? = null
    private var planJob: Job? = null
    private var token = ListingControl()
    private var generation = 0
    private var planGeneration = 0
    private var needsRefresh = false
    private var paths = emptyList<Entry>()
    private var siblings = emptyList<RenameSibling>()
    private var zone = ZoneId.systemDefault()
    var visible by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    var planning by mutableStateOf(false)
        private set

    var applying by mutableStateOf(false)
        private set

    var inputs by mutableStateOf<List<RenameInput>>(emptyList())
        private set

    var plan by mutableStateOf<RenamePlan?>(null)
        private set

    var recipe by mutableStateOf(RenameRecipe())
        private set

    var order by mutableStateOf(RenameOrder.CURRENT)
        private set

    var reverse by mutableStateOf(false)
        private set

    var excluded by mutableStateOf<Set<String>>(emptySet())
        private set

    var prepareUndo by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
        private set

    var metadataStatus by mutableStateOf<String?>(null)
        private set

    var presets by mutableStateOf<List<RenamePreset>>(emptyList())
        private set

    var presetError by mutableStateOf<String?>(null)
        private set

    var presetId by mutableStateOf<String?>(null)
        private set

    var presetSaving by mutableStateOf(false)
        private set

    var presetNotice by mutableStateOf<String?>(null)
        private set

    var progress by mutableStateOf("")
        private set

    var recoveryRoutes by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    fun open(entries: List<Entry>) {
        if (applying) {
            visible = true
            return
        }
        close()
        visible = true
        paths = entries.distinctBy { it.path }
        recipe = RenameRecipe()
        inputs = emptyList()
        plan = null
        excluded = emptySet()
        order = RenameOrder.CURRENT
        reverse = false
        zone = ZoneId.systemDefault()
        prepareUndo = true
        presetId = null
        metadataStatus = null
        presetNotice = null
        recoveryRoutes = emptyList()
        refresh()
    }

    fun close() {
        visible = false
        stop(false)
    }

    fun stop(showError: Boolean = true) {
        generation++
        planGeneration++
        token.cancel()
        catalogJob?.cancel()
        planJob?.cancel()
        loading = false
        planning = false
        if (showError) {
            needsRefresh = true
            error = "Preview stopped. Refresh to continue."
            plan = null
        }
    }

    fun configure(value: RenameRecipe) {
        recipe =
            if (value.method != recipe.method)
                value.copy(
                    start = value.start.takeIf { it >= 0 } ?: 1,
                    step = value.step.takeIf { it in 1..1_000_000 } ?: 1,
                    padding = value.padding.coerceIn(1, 12),
                )
            else value
        replan()
    }

    fun sort(value: RenameOrder, descending: Boolean = reverse) {
        order = value
        reverse = descending
        replan()
    }

    fun toggle(id: String) {
        excluded = if (id in excluded) excluded - id else excluded + id
        replan()
    }

    fun includeAll(include: Boolean) {
        excluded = if (include) emptySet() else inputs.map { it.id }.toSet()
        replan()
    }

    fun excludeErrors() {
        excluded = excluded + plan?.rows.orEmpty().filter { it.error != null }.map { it.input.id }
        replan()
    }

    fun move(id: String, delta: Int) {
        val ordered = orderedRenameInputs(inputs, order, reverse).toMutableList()
        val at = ordered.indexOfFirst { it.id == id }
        val target = at + delta
        if (at < 0 || target !in ordered.indices) return
        val item = ordered.removeAt(at)
        ordered.add(target, item)
        inputs = ordered
        order = RenameOrder.CURRENT
        reverse = false
        replan()
    }

    fun refresh() {
        stop(false)
        needsRefresh = false
        error = null
        plan = null
        loading = true
        val request = generation
        val control = ListingControl().also { token = it }
        val savedTags = model.tagAssignments.toMap()
        catalogJob = scope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        require(paths.size in 1..10_000) {
                            "Select between 1 and 10,000 items for one reviewed rename."
                        }
                        var last = 0L
                        val captured = paths.mapIndexed { index, entry ->
                            control.check()
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - last > 120) {
                                last = now
                                progress = "Reading ${index + 1} of ${paths.size}"
                            }
                            runCatching {
                                capture(entry.path, savedTags[entry.organizationPath].orEmpty(), control)
                            }
                                .getOrElse {
                                    if (it is CancellationException) throw it
                                    RenameInput(
                                        entry.path,
                                        entry.parentPath.orEmpty(),
                                        entry.name,
                                        entry.folder,
                                        null,
                                        null,
                                        "unavailable",
                                        problem = it.message ?: "This item is unavailable.",
                                    )
                                }
                        }
                        val neighbors = mutableListOf<RenameSibling>()
                        val parentErrors = mutableMapOf<String, String>()
                        for (parent in
                            captured.filter { it.problem == null }.map { it.parent }.distinct()) {
                            control.check()
                            progress = "Checking destination names"
                            try {
                                val reply =
                                    model.documents.visitChildren(parent, control) {
                                        check(neighbors.size < 100_000) {
                                            "Destination preview exceeds 100,000 entries. Narrow the selection."
                                        }
                                        neighbors += RenameSibling(it.path, parent, it.name)
                                        true
                                    }
                                check(!reply.loading && reply.error == null) {
                                    reply.error
                                        ?: "The provider is still listing this folder. Refresh when it is ready."
                                }
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                parentErrors[parent] =
                                    failure.message ?: "Couldn't inspect this destination."
                            }
                        }
                        val presetValues = store.load()
                        Triple(
                            captured.map { input ->
                                input.copy(problem = input.problem ?: parentErrors[input.parent])
                            },
                            neighbors.toList(),
                            presetValues,
                        )
                    }
                if (request == generation) {
                    inputs = result.first
                    siblings = result.second
                    presets = result.third
                    presetError = store.error
                    loading = false
                    replan()
                }
            } catch (failure: Exception) {
                if (request == generation && failure !is CancellationException)
                    error = failure.message
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    internal fun capture(
        path: String,
        tags: Collection<String>,
        control: ListingControl,
    ): RenameInput {
        control.check()
        val entry = model.documents.read(path, control)
        val node = model.analysisSource.node(entry)
        check(!node.link) { "Links are not included in reviewed batch renames." }
        val parent =
            entry.parentPath ?: model.documents.parent(path) ?: error("Parent folder unavailable.")
        check(model.allowedPath(parent)) { "The parent folder needs storage access." }
        val ancestors = mutableSetOf<String>()
        var next: String? = parent
        repeat(128) {
            control.check()
            val current = next ?: return@repeat
            check(ancestors.add(current)) { "A parent-folder cycle was found." }
            next = model.documents.parent(current)
        }
        check(next == null) { "The parent hierarchy exceeds 128 folders." }
        return RenameInput(
            path,
            parent,
            entry.name,
            entry.folder,
            if (entry.documentUri == null) entry.modified else entry.modified.takeIf { it > 0 },
            entry.size.takeIf { !entry.folder && entry.sizeKnown },
            node.revision,
            model.title(parent),
            tags.sorted(),
            ancestors,
            problem =
                if (!entry.canRename) "This item is read-only or cannot be renamed." else null,
            mime = entry.mime,
        )
    }

    private fun replan() {
        if (loading || applying || needsRefresh) return
        planJob?.cancel()
        val request = ++planGeneration
        val catalog = orderedRenameInputs(inputs, order, reverse)
        val rule = recipe
        val skipped = excluded
        val neighbors = siblings
        plan = null
        planning = true
        planJob = scope.launch {
            try {
                val job = currentCoroutineContext().job
                val result =
                    withContext(Dispatchers.Default) {
                        planRecipeRename(catalog, neighbors, skipped, rule, zone) {
                            job.ensureActive()
                        }
                    }
                if (request == planGeneration) plan = result
            } catch (failure: Exception) {
                if (request == planGeneration && failure !is CancellationException)
                    error = failure.message
            } finally {
                if (request == planGeneration) planning = false
            }
        }
    }

    fun loadPhotoMetadata() {
        if (loading || applying || inputs.isEmpty() || needsRefresh) return
        stop(false)
        loading = true
        plan = null
        error = null
        val request = generation
        val control = ListingControl().also { token = it }
        val snapshot = inputs
        catalogJob = scope.launch {
            try {
                var loaded = 0
                var failed = 0
                val result =
                    withContext(Dispatchers.IO) {
                        snapshot.mapIndexed { index, input ->
                            control.check()
                            progress = "Reading photo metadata ${index + 1} of ${snapshot.size}"
                            if (input.folder || input.problem != null) input
                            else {
                                val current = model.documents.read(input.id, control)
                                if (
                                    fileType(current.extension, current.mime).group !=
                                        FileTypeGroup.IMAGE
                                )
                                    input
                                else {
                                    val photo = runCatching {
                                        check(
                                            capture(input.id, input.tags, control).revision ==
                                                input.revision,
                                        ) {
                                            "The source changed. Refresh the preview."
                                        }
                                        val metadata =
                                            model.documents.input(input.id, control).use {
                                                PhotoMetadataInput(it, control).use { bounded ->
                                                    ExifInterface(bounded)
                                                }
                                            }
                                        val date =
                                            metadata
                                                .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                                ?.let { value ->
                                                    runCatching {
                                                        LocalDateTime.parse(
                                                            value,
                                                            DateTimeFormatter.ofPattern(
                                                                    "uuuu:MM:dd HH:mm:ss",
                                                                )
                                                                .withResolverStyle(
                                                                    java.time.format.ResolverStyle
                                                                        .STRICT,
                                                                ),
                                                        )
                                                    }
                                                        .getOrNull()
                                                }
                                        val width =
                                            metadata
                                                .getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                                                .takeIf { it > 0 }
                                                ?: metadata
                                                    .getAttributeInt(
                                                        ExifInterface.TAG_PIXEL_X_DIMENSION,
                                                        0,
                                                    )
                                                    .takeIf { it > 0 }
                                        val height =
                                            metadata
                                                .getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                                                .takeIf { it > 0 }
                                                ?: metadata
                                                    .getAttributeInt(
                                                        ExifInterface.TAG_PIXEL_Y_DIMENSION,
                                                        0,
                                                    )
                                                    .takeIf { it > 0 }
                                        val camera =
                                            listOfNotNull(
                                                    metadata.getAttribute(ExifInterface.TAG_MAKE),
                                                    metadata.getAttribute(ExifInterface.TAG_MODEL),
                                                )
                                                .distinct()
                                                .joinToString(" ")
                                                .trim()
                                                .takeIf { it.isNotEmpty() }
                                        check(
                                            capture(input.id, input.tags, control).revision ==
                                                input.revision,
                                        ) {
                                            "The source changed. Refresh the preview."
                                        }
                                        RenamePhoto(date, camera, width, height)
                                    }
                                        .getOrElse {
                                            if (it is CancellationException) throw it
                                            failed++
                                            null
                                        }
                                    if (photo != null) loaded++
                                    input.copy(photo = photo)
                                }
                            }
                        }
                    }
                if (request == generation) {
                    inputs = result
                    metadataStatus =
                        "$loaded ${if (loaded == 1) "photo" else "photos"} read · $failed unavailable. Missing tokens remain explicit errors."
                    loading = false
                    replan()
                }
            } catch (failure: Exception) {
                if (request == generation && failure !is CancellationException)
                    error = failure.message
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    fun loadPreset(preset: RenamePreset) {
        recipe = preset.recipe
        order = preset.order
        reverse = preset.reverse
        presetId = preset.id
        replan()
    }

    fun savePreset(name: String, update: Boolean = false) {
        if (presetSaving) return
        presetSaving = true
        presetNotice = null
        val request = generation
        val rule = recipe
        val sort = order
        val backwards = reverse
        val id = presetId.takeIf { update }
        scope.launch {
            try {
                val saved =
                    withContext(Dispatchers.IO) { store.save(name, rule, sort, backwards, id) }
                if (
                    request == generation && recipe == rule && order == sort && reverse == backwards
                ) {
                    presetId = saved.id
                    presetNotice = "Saved ${saved.name}"
                }
                presets = withContext(Dispatchers.IO) { store.load() }
                presetError = null
            } catch (failure: Exception) {
                presetError = failure.message
            } finally {
                presetSaving = false
            }
        }
    }

    fun removePreset(id: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.remove(id) }
                presets = withContext(Dispatchers.IO) { store.load() }
                if (presetId == id) presetId = null
                presetError = null
            } catch (failure: Exception) {
                presetError = failure.message
            }
        }
    }

    fun apply() {
        val reviewed = plan?.takeIf { it.canApply } ?: return
        if (loading || planning || applying) return
        applying = true
        error = null
        model.renameReviewed(reviewed, prepareUndo) { failure ->
            applying = false
            if (failure == null) close()
            else {
                needsRefresh = true
                error = failure.message
                plan = null
                if (failure is RenameRecoveryException) {
                    recoveryRoutes = failure.changes
                    val routes = failure.changes.toMap()
                    paths = paths.map { entry ->
                        routes[entry.path]?.let { current ->
                            entry.copy(
                                file =
                                    if (isDocumentPath(current)) entry.file
                                    else java.io.File(current),
                                documentUri =
                                    if (isDocumentPath(current)) android.net.Uri.parse(current)
                                    else null,
                            )
                        } ?: entry
                    }
                }
            }
        }
    }
}

private class PhotoMetadataInput(input: InputStream, private val control: ListingControl) :
    FilterInputStream(input) {
    private var remaining = 16L * 1024 * 1024

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        control.check()
        require(remaining > 0) { "Photo metadata exceeds the 16 MiB read limit." }
        val count = `in`.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        control.check()
        return count
    }

    override fun skip(count: Long): Long {
        var skipped = 0L
        val buffer = ByteArray(8192)
        while (skipped < count) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), count - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }

    override fun markSupported() = false
}
