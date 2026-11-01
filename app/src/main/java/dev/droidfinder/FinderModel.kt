package dev.droidfinder

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ViewMode(val title: String) {
    ICONS("Icons"),
    LIST("List"),
    COLUMNS("Columns"),
    GALLERY("Gallery"),
}

enum class SortBy(val title: String) {
    NAME("Name"),
    MODIFIED("Date modified"),
    SIZE("Size"),
    KIND("Kind"),
}

data class Entry(
    val file: File,
    val name: String = file.name,
    val folder: Boolean = file.isDirectory,
    val size: Long = file.length(),
    val modified: Long = file.lastModified(),
    val documentUri: Uri? = null,
    val parentPath: String? = file.parent,
    val writable: Boolean = file.canWrite(),
    val canRename: Boolean = file.canWrite(),
    val canDelete: Boolean = file.canWrite(),
    val mimeType: String? = null,
    val style: FolderStyle? = null,
    val sizeKnown: Boolean = true,
) {
    val path: String
        get() = documentUri?.toString() ?: file.absolutePath

    val displaySize: String
        get() = if (folder) "—" else if (sizeKnown) formatSize(size) else "Unknown size"

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val kind: String
        get() =
            if (folder) "Folder"
            else
                when (extension) {
                    "jpg",
                    "jpeg",
                    "png",
                    "webp",
                    "gif",
                    "heic" -> "Image"
                    "pdf" -> "PDF document"
                    "txt",
                    "md",
                    "json",
                    "kt",
                    "xml",
                    "csv",
                    "log",
                    "html" -> "Text document"
                    "mp4",
                    "mkv",
                    "webm",
                    "mov" -> "Video"
                    "mp3",
                    "wav",
                    "m4a",
                    "flac",
                    "ogg" -> "Audio"
                    "zip" -> "ZIP archive"
                    "finderlink" -> "DroidFinder shortcut"
                    "apk" -> "Android app"
                    "doc",
                    "docx" -> "Word document"
                    "xls",
                    "xlsx" -> "Spreadsheet"
                    else -> if (extension.isBlank()) "Document" else "${extension.uppercase()} file"
                }

    val mime: String
        get() =
            mimeType
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
}

data class FinderContextRequest(val point: Offset? = null, val background: Boolean = false)

data class ClipboardFiles(val paths: List<String>, val cut: Boolean) {
    val files
        get() = paths.map(::File)
}

val tagNames = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple")

class FinderModel
@JvmOverloads
constructor(
    app: Application,
    internal val session: FinderSession = FinderSession(app),
    val paneId: String = "primary",
    internal val windowState: FinderWindowState = FinderWindowState(app),
) : AndroidViewModel(app) {
    val primaryPane: FinderModel
        get() = windowState.panes.first { it.paneId == "primary" }

    val secondaryPane: FinderModel
        get() =
            androidx.lifecycle
                .ViewModelProvider(
                    windowState.secondaryStore,
                    object : androidx.lifecycle.ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(
                            modelClass: Class<T>,
                        ): T {
                            @Suppress("UNCHECKED_CAST")
                            return FinderModel(getApplication(), session, "secondary", windowState)
                                as T
                        }
                    },
                )[FinderModel::class.java]

    val activePaneModel: FinderModel
        get() =
            if (dualPane && windowState.activePane.value == "secondary") secondaryPane
            else primaryPane

    val otherPane: FinderModel
        get() = if (paneId == "primary") secondaryPane else primaryPane

    val dualPane
        get() = windowState.dualPane.value

    val paneRatio
        get() = windowState.paneRatio.value

    val panesReversed
        get() = windowState.panesReversed.value

    val paneActive
        get() = activePaneModel === this

    fun activatePane() {
        if (windowState.activePane.value == paneId) return
        windowState.activePane.value = paneId
        windowState.prefs.edit().putString("activePane", paneId).apply()
    }

    fun toggleDualPane() {
        if (!dualPane) secondaryPane
        windowState.dualPane.value = !dualPane
        if (!dualPane) primaryPane.activatePane()
        windowState.prefs.edit().putBoolean("dualPane", dualPane).apply()
    }

    fun focusOtherPane() {
        if (dualPane) otherPane.activatePane()
    }

    fun swapPanes() {
        windowState.panesReversed.value = !panesReversed
        windowState.prefs.edit().putBoolean("panesReversed", panesReversed).apply()
    }

    fun changePaneRatio(value: Float) {
        if (!value.isFinite()) return
        windowState.paneRatio.value = value.coerceIn(.3f, .7f)
        windowState.prefs.edit().putFloat("paneRatio", paneRatio).apply()
    }

    val savedWorkspaces
        get() = session.savedWorkspaces.value

    val workspaceError
        get() = session.workspaceError.value

    private fun snapshotPane(): PaneSnapshot {
        flushNavigationState()
        return PaneSnapshot(
            tabs,
            activeTab,
            mode,
            windowsLayout,
            sort,
            ascending,
            grouping,
            showHidden,
            foldersFirst,
            previewVisible,
            previewDetailsOnly,
            iconSize,
            columnWidth,
            previewWidth,
            detailColumns,
            fitColumns,
            paneDefaults ?: currentFolderView(),
        )
    }

    private fun restorePane(saved: PaneSnapshot) {
        navigationSaveJob?.cancel()
        saveTabState()
        closedTabs = (closedTabs + tabs.filterNot { it.pinned }).takeLast(20)
        viewportPositions.clear()
        listingBudgets.clear()
        tabs = saved.tabs.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
        activeTab = saved.active.coerceIn(tabs.indices)
        mode = saved.mode
        windowsLayout = saved.windowsLayout
        sort = saved.sort
        ascending = saved.ascending
        grouping = saved.grouping
        showHidden = saved.hidden
        foldersFirst = saved.foldersFirst
        previewVisible = saved.preview
        previewDetailsOnly = saved.details
        inspectorRequested = false
        iconSize = saved.iconSize
        columnWidth = saved.columnWidth
        previewWidth = saved.previewWidth
        detailColumns = saved.columns
        fitColumns = saved.fit
        persistPaneDefaults(saved.defaults ?: currentFolderView())
        restoreTabState()
        persistTabs()
    }

    val workspaceSaving
        get() = session.workspaceWrites.intValue > 0

    private fun persistWorkspaces(success: String? = null) {
        // All windows share this writer. Confirm saving only after atomic storage succeeds.
        session.workspaceWrites.intValue++
        TransferRuntime.scope.launch {
            try {
                session.workspaceMutex.withLock {
                    val latest = savedWorkspaces
                    runCatching {
                        withContext(Dispatchers.IO) { session.workspaceStore.write(latest) }
                    }
                        .onSuccess {
                            session.workspaceError.value = null
                            if (success != null) message = success
                        }
                        .onFailure {
                            session.workspaceError.value = it.message
                            message = it.message
                        }
                }
            } finally {
                session.workspaceWrites.intValue--
            }
        }
    }

    fun saveWorkspace(name: String, replaceId: String? = null): Boolean {
        val title = name.trim()
        if (workspaceError != null) {
            message = workspaceError
            return false
        }
        if (title.isBlank() || title.length > 60 || title.any { it.isISOControl() }) {
            message = "Use a name from 1 to 60 characters."
            return false
        }
        if (savedWorkspaces.any { it.id != replaceId && it.name.equals(title, true) }) {
            message = "A workspace with that name already exists."
            return false
        }
        if (replaceId == null && savedWorkspaces.size >= 24) {
            message = "There are already 24 saved workspaces."
            return false
        }
        val value =
            SavedWorkspace(
                replaceId ?: java.util.UUID.randomUUID().toString(),
                title,
                primaryPane.snapshotPane(),
                secondaryPane.snapshotPane(),
                dualPane,
                panesReversed,
                paneRatio,
                windowState.activePane.value,
            )
        session.savedWorkspaces.value =
            (savedWorkspaces.filterNot { it.id == replaceId } + value).sortedBy {
                it.name.lowercase()
            }
        message = "Saving $title…"
        persistWorkspaces("Saved $title")
        return true
    }

    fun workspaceNameError(name: String, exceptId: String? = null): String? {
        val title = name.trim()
        return when {
            title.isBlank() || title.length > 60 || title.any { it.isISOControl() } ->
                "Use a name from 1 to 60 characters."
            savedWorkspaces.any { it.id != exceptId && it.name.equals(title, true) } ->
                "A workspace with that name already exists."
            else -> null
        }
    }

    fun renameWorkspace(id: String, name: String): Boolean {
        if (workspaceError != null) {
            message = workspaceError
            return false
        }
        workspaceNameError(name, id)?.let {
            message = it
            return false
        }
        session.savedWorkspaces.value =
            savedWorkspaces
                .map { if (it.id == id) it.copy(name = name.trim()) else it }
                .sortedBy { it.name.lowercase() }
        persistWorkspaces()
        return true
    }

    fun retryWorkspaceSave() {
        if (session.workspaceStore.error != null) return
        session.workspaceError.value = null
        persistWorkspaces("Workspaces saved")
    }

    fun removeWorkspace(id: String) {
        if (workspaceError != null) return
        session.savedWorkspaces.value = savedWorkspaces.filterNot { it.id == id }
        persistWorkspaces()
    }

    fun openWorkspace(id: String) {
        val saved = savedWorkspaces.firstOrNull { it.id == id } ?: return
        primaryPane.restorePane(saved.primary)
        secondaryPane.restorePane(saved.secondary)
        windowState.dualPane.value = saved.dual
        windowState.panesReversed.value = saved.reversed
        windowState.paneRatio.value = saved.ratio
        windowState.activePane.value = if (saved.dual) saved.activePane else "primary"
        windowState.prefs
            .edit()
            .putBoolean("dualPane", saved.dual)
            .putBoolean("panesReversed", saved.reversed)
            .putFloat("paneRatio", saved.ratio)
            .putString("activePane", windowState.activePane.value)
            .apply()
        message = "Opened ${saved.name}"
    }

    fun transferToOtherPane(move: Boolean) {
        if (!dualPane || isTrash) return
        val destination = otherPane
        val parent = destination.folderPath
        if (parent == null || !destination.canCreate) {
            message = "Open a writable folder in the other pane."
            return
        }
        if (move && actionEntries.any { !it.canDelete }) {
            message = "Some selected items cannot be moved."
            return
        }
        val paths = actionEntries.map { it.path }
        if (paths.isNotEmpty()) transferPaths(paths, parent, move)
    }

    private val prefs = app.getSharedPreferences("finder", 0)
    private val panePrefs =
        app.getSharedPreferences(
            if (windowState.id == "main")
                if (paneId == "primary") "finder" else "finder-pane-$paneId"
            else "finder-window-${windowState.id}-$paneId",
            0,
        )
    val workspace
        get() = session.workspace

    val deviceRoot
        get() = session.deviceRoot

    val engine
        get() = session.engine

    val documents
        get() = session.documents

    var connectedLocations by session.connectedLocations
        private set

    var notificationsEnabled by session.notificationsEnabled
        private set

    fun updateNotificationState() {
        notificationsEnabled =
            androidx.core.app.NotificationManagerCompat.from(getApplication())
                .areNotificationsEnabled()
    }

    var hasAccess by session.hasAccess
        private set

    var tabs by mutableStateOf(loadTabs())
        private set

    var activeTab by mutableStateOf(panePrefs.getInt("activeTab", 0).coerceIn(0, tabs.lastIndex))
        private set

    val location: String
        get() = tabs[activeTab].location

    val folderPath: String?
        get() = location.takeUnless { it.startsWith("@") }

    val directory: File?
        get() = folderPath?.takeUnless(::isDocumentPath)?.let(::File)

    var folderInfo by mutableStateOf<Entry?>(null)
        private set

    val canCreate
        get() = folderPath != null && folderStorageIssue == null && folderInfo?.writable == true

    val isTrash: Boolean
        get() = location == "@trash"

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    var expandedFolders by mutableStateOf<Set<String>>(emptySet())
        private set

    private var expandedChildren by mutableStateOf<Map<String, List<Entry>>>(emptyMap())
    val canExpandList
        get() =
            folderPath != null &&
                query.isBlank() &&
                !searchRules.active &&
                grouping == GroupBy.NONE &&
                windowsLayout != 8

    fun listRows(): List<Pair<Entry, Int>> {
        if (!canExpandList) return entries.map { it to 0 }
        val result = mutableListOf<Pair<Entry, Int>>()
        val seen = mutableSetOf<String>()
        fun add(items: List<Entry>, depth: Int) {
            if (depth > 16) return
            items.forEach { entry ->
                if (seen.add(entry.path) && result.size < 50_000) {
                    result += entry to depth
                    if (entry.path in expandedFolders)
                        add(expandedChildren[entry.path].orEmpty(), depth + 1)
                }
            }
        }
        add(entries, 0)
        return result
    }

    val visibleEntries
        get() = if (mode == ViewMode.LIST) listRows().map { it.first } else entries

    fun toggleExpanded(entry: Entry) {
        if (!entry.folder || !canExpandList) return
        pendingTabRestore = null
        viewportInteraction++
        if (entry.path in expandedFolders) {
            expandedFolders = expandedFolders - entry.path
            scheduleNavigationSave()
            return
        }
        if (expandedFolders.size >= 1000) {
            message = "This tab already has 1,000 expanded folders."
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    check(allowedPath(entry.path)) { "This folder isn't accessible." }
                    listPath(entry.path)
                }
            }
                .onSuccess {
                    expandedChildren = expandedChildren + (entry.path to it)
                    expandedFolders = expandedFolders + entry.path
                    scheduleNavigationSave()
                }
                .onFailure { message = it.message }
        }
    }

    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    var multiSelecting by mutableStateOf(false)
        private set

    private var leadSelection by mutableStateOf<String?>(null)
    private var rangeAnchor: String? = null
    var quickLookRequested by mutableStateOf<Entry?>(null)
    var shareRequested by windowState.shareRequested
    var query by mutableStateOf(tabs[activeTab].search)
        private set

    var searchRules by mutableStateOf(tabs[activeTab].rules)
        private set

    var smartFolders by session.smartFolders
        private set

    val currentSmartFolder
        get() = smartFolders.firstOrNull { location == "@smart:${it.id}" }

    val effectiveSearchRules
        get() =
            currentSmartFolder?.rules?.let {
                if (query.isNotBlank()) it.copy(nameTerms = (it.nameTerms + query).distinct())
                else it
            } ?: searchRules.copy(name = query)

    var grouping by
        mutableStateOf(
            runCatching { GroupBy.valueOf(panePrefs.getString("group", "NONE")!!) }
                .getOrDefault(GroupBy.NONE),
        )
        private set

    var density by session.density
        private set

    fun changeDensity(value: FinderDensity) {
        density = value
        prefs.edit().putString("density", value.name).apply()
    }

    var iconSize by mutableStateOf(panePrefs.getInt("iconSize", 120))
        private set

    var columnWidth by mutableStateOf(panePrefs.getInt("columnWidth", 240))
        private set

    var previewWidth by mutableStateOf(panePrefs.getInt("previewWidth", 280))
        private set

    var showExtensions by session.showExtensions
        private set

    var darkTheme by session.darkTheme
        private set

    var sidebarWidth by session.sidebarWidth
        private set

    var sidebarVisible by session.sidebarVisible
        private set

    var tagsVisible by session.tagsVisible
        private set

    private val styleStore
        get() = session.styleStore

    var folderStyles by session.folderStyles
        private set

    var contextRequested by mutableStateOf<FinderContextRequest?>(null)
    private var closedTabs by mutableStateOf(loadClosedTabs())
    val canReopenTab
        get() = closedTabs.isNotEmpty()

    private val viewportPositions = mutableMapOf<String, Map<ViewportKey, ViewportAnchor>>()
    private val listingBudgets = mutableMapOf<String, Map<ListingScope, ListingBudget>>()

    private data class ViewportReader(val owner: Any, val capture: () -> ViewportAnchor?)

    private val viewportReaders = mutableMapOf<Pair<String, ViewportKey>, ViewportReader>()

    internal fun registerViewport(
        tabId: String,
        key: ViewportKey,
        owner: Any,
        capture: () -> ViewportAnchor?,
    ) {
        viewportReaders[tabId to key] = ViewportReader(owner, capture)
    }

    internal fun unregisterViewport(tabId: String, key: ViewportKey, owner: Any) {
        val registered = viewportReaders[tabId to key] ?: return
        if (registered.owner !== owner) return
        registered.capture()?.let { recordViewport(tabId, key, it) }
        viewportReaders.remove(tabId to key)
    }

    private fun captureViewports() {
        val tabId = tabs[activeTab].id
        viewportReaders.toMap().forEach { (pair, reader) ->
            if (pair.first == tabId)
                reader.capture()?.let { recordViewport(pair.first, pair.second, it) }
        }
    }

    private var paneDefaults: FolderView? = null
    private var pendingTabRestore: Pair<String, TabNavigation>? = null
    private var navigationSaveJob: Job? = null
    private var savingNavigation = false
    var tabOverviewRequested by mutableStateOf(false)
    var windowsLayout by mutableStateOf(panePrefs.getInt("windowsLayout", 0))
        private set

    var fitColumns by mutableStateOf(panePrefs.getBoolean("fitColumns", false))
        private set

    var browserWidthDp = 0f
    var browserHeightDp = 0f
    var keyboardScrollRequest by mutableIntStateOf(0)
        private set

    val keyboardFocusPath
        get() = leadSelection

    val keyboardPageSize
        get() =
            when (mode) {
                ViewMode.ICONS ->
                    gridColumns * (browserHeightDp / (iconSize + 31)).toInt().coerceAtLeast(1)
                ViewMode.GALLERY -> (browserWidthDp / 103).toInt().coerceAtLeast(1)
                else ->
                    (browserHeightDp /
                            if (windowsLayout == 8) 72 else if (density.touch) 48 else 36)
                        .toInt()
                        .coerceAtLeast(1)
            }

    var inspectorRequested by mutableStateOf(false)
        private set

    var previewDetailsOnly by mutableStateOf(panePrefs.getBoolean("previewDetailsOnly", false))
        private set

    var navigationTreeVisible by mutableStateOf(panePrefs.getBoolean("navigationTree", false))
        private set

    var navigationRoots by mutableStateOf<List<Entry>>(emptyList())
        private set

    var navigationExpanded by mutableStateOf<Set<String>>(emptySet())
        private set

    private var navigationChildren by mutableStateOf<Map<String, List<Entry>>>(emptyMap())
    private var keyboardRangeBase: Set<String>? = null
    private var keyboardRangeAdditive = false

    fun windowsView(number: Int) {
        changeMode(
            when (number) {
                in 1..4,
                7 -> ViewMode.ICONS
                9 -> ViewMode.GALLERY
                else -> ViewMode.LIST
            },
        )
        windowsLayout = number
        if (number in 1..4 || number == 7)
            changeIconSize(
                when (number) {
                    1 -> 200
                    2 -> 160
                    3 -> 120
                    4 -> 90
                    else -> 150
                },
            )
        fitColumns = false
        persistViewChange()
    }

    fun fitColumnsToContents() {
        changeMode(ViewMode.LIST)
        fitColumns = true
        persistViewChange()
    }

    fun toggleInspector(details: Boolean = false) {
        val displayed =
            previewVisible &&
                (browserWidthDp >= 900 || inspectorRequested) &&
                previewDetailsOnly == details
        previewVisible = !displayed
        inspectorRequested = !displayed
        previewDetailsOnly = details
        persistViewChange()
    }

    fun selectBoundary(last: Boolean, extend: Boolean) {
        pendingTabRestore = null
        scheduleNavigationSave()
        val items = visibleEntries
        if (items.isEmpty()) return
        if (extend) {
            if (focused == null) {
                leadSelection = items.first().path
                rangeAnchor = leadSelection
            }
            stepSelection(if (last) items.size else -items.size, true)
        } else {
            keyboardRangeBase = null
            val path = (if (last) items.last() else items.first()).path
            selected = setOf(path)
            leadSelection = path
            rangeAnchor = path
            multiSelecting = false
            keyboardScrollRequest++
        }
    }

    fun moveKeyboardFocus(delta: Int) {
        pendingTabRestore = null
        scheduleNavigationSave()
        keyboardScrollRequest++
        val items = visibleEntries
        if (items.isEmpty()) return
        val current = items.indexOfFirst { it.path == leadSelection }
        leadSelection =
            items[(if (current < 0) 0 else current + delta).coerceIn(0, items.lastIndex)].path
        keyboardRangeBase = null
    }

    fun toggleFocusedSelection() {
        focused?.let { select(it, multiple = true) }
    }

    fun keyboardLeft() {
        val item = focused
        when {
            mode == ViewMode.ICONS || mode == ViewMode.GALLERY -> stepSelection(-1, false)
            item != null && item.path in expandedFolders -> toggleExpanded(item)
            mode == ViewMode.LIST && item?.parentPath != folderPath ->
                visibleEntries
                    .firstOrNull { it.path == item?.parentPath }
                    ?.let {
                        clearSelection()
                        select(it)
                    } ?: up()
            else -> up()
        }
    }

    fun keyboardRight() {
        val item = focused ?: return
        when {
            mode == ViewMode.ICONS || mode == ViewMode.GALLERY -> stepSelection(1, false)
            mode == ViewMode.LIST && canExpandList && item.folder ->
                if (item.path !in expandedFolders) toggleExpanded(item)
                else
                    visibleEntries
                        .firstOrNull { it.parentPath == item.path }
                        ?.let {
                            clearSelection()
                            select(it)
                        }
            item.folder -> navigate(item.path)
        }
    }

    fun expandKeyboardSelection(recursive: Boolean = false) {
        val item = focused?.takeIf { it.folder } ?: return
        if (mode != ViewMode.LIST) changeMode(ViewMode.LIST)
        if (!recursive) {
            if (item.path !in expandedFolders) toggleExpanded(item)
            return
        }
        val at = location
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val pending = ArrayDeque<Pair<String, Int>>()
                    pending.add(item.path to 0)
                    val children = linkedMapOf<String, List<Entry>>()
                    while (pending.isNotEmpty() && children.size < 1000) {
                        coroutineContext.ensureActive()
                        val (path, depth) = pending.removeFirst()
                        if (path in children || depth >= 16) continue
                        val files = listPath(path)
                        children[path] = files
                        files
                            .filter {
                                it.folder &&
                                    (it.documentUri != null ||
                                        !java.nio.file.Files.isSymbolicLink(it.file.toPath()))
                            }
                            .forEach { pending.add(it.path to depth + 1) }
                    }
                    children to pending.isNotEmpty()
                }
            }
                .onSuccess { (children, limited) ->
                    if (location == at) {
                        expandedChildren = expandedChildren + children
                        expandedFolders = (expandedFolders + children.keys).take(1000).toSet()
                        scheduleNavigationSave()
                    }
                    if (limited) message = "Folder expansion stopped at the 1,000-folder limit."
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    message = it.message
                }
        }
    }

    fun collapseKeyboardSelection() {
        focused?.takeIf { it.path in expandedFolders }?.let(::toggleExpanded)
    }

    fun navigationRows(): List<Pair<Entry, Int>> {
        val result = mutableListOf<Pair<Entry, Int>>()
        fun visit(entry: Entry, depth: Int) {
            if (result.size >= 1000 || depth > 16) return
            result += entry to depth
            if (entry.path in navigationExpanded)
                navigationChildren[entry.path].orEmpty().forEach { visit(it, depth + 1) }
        }
        navigationRoots.forEach { visit(it, 0) }
        return result
    }

    fun hideNavigationTree() {
        navigationTreeVisible = false
        panePrefs.edit().putBoolean("navigationTree", false).apply()
    }

    fun revealNavigationTree(showSidebar: Boolean = true) {
        if (showSidebar && !sidebarVisible) toggleSidebar()
        navigationTreeVisible = true
        panePrefs.edit().putBoolean("navigationTree", true).apply()
        val path = folderPath
        val chain =
            path?.let { generateSequence(it) { parentPath(it) }.take(32).toList() }.orEmpty()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val roots =
                        (listOf(workspace.path) +
                                (if (hasAccess) listOf(deviceRoot.path) + volumes.map { it.path }
                                else emptyList()) +
                                connectedLocations.map { it.uri })
                            .distinct()
                    val rootEntries = roots.mapNotNull {
                        runCatching { documents.read(it) }.getOrNull()
                    }
                    rootEntries to
                        chain.associateWith {
                            runCatching {
                                    listPath(it).filter { child -> child.folder }.take(500)
                                }
                                .getOrDefault(emptyList())
                        }
                }
            }
                .onSuccess { (roots, children) ->
                    navigationRoots = roots
                    navigationChildren = navigationChildren + children
                    navigationExpanded = navigationExpanded + chain
                }
                .onFailure { message = it.message }
        }
    }

    fun toggleNavigationFolder(entry: Entry) {
        if (entry.path in navigationExpanded) {
            navigationExpanded = navigationExpanded - entry.path
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    listPath(entry.path).filter { it.folder }.take(500)
                }
            }
                .onSuccess {
                    navigationChildren = navigationChildren + (entry.path to it)
                    navigationExpanded = navigationExpanded + entry.path
                }
                .onFailure { message = it.message }
        }
    }

    var gridColumns = 4
    private var typedPrefix = ""
    private var typedAt = 0L
    var mode by
        mutableStateOf(
            runCatching { ViewMode.valueOf(panePrefs.getString("view", "LIST")!!) }
                .getOrDefault(ViewMode.LIST),
        )
        private set

    var sort by
        mutableStateOf(
            runCatching { SortBy.valueOf(panePrefs.getString("sort", "NAME")!!) }
                .getOrDefault(SortBy.NAME),
        )
        private set

    var ascending by mutableStateOf(panePrefs.getBoolean("ascending", true))
        private set

    var showHidden by mutableStateOf(panePrefs.getBoolean("hidden", false))
        private set

    var previewVisible by mutableStateOf(panePrefs.getBoolean("preview", true))
        private set

    var foldersFirst by mutableStateOf(panePrefs.getBoolean("foldersFirst", true))
        private set

    var detailColumns by
        mutableStateOf(
            runCatching {
                    FolderViewJson.columns(JSONObject(panePrefs.getString("detailColumns", "{}")!!))
                }
                .getOrDefault(DetailColumns()),
        )
        private set

    var loading by mutableStateOf(false)
        private set

    var busy by session.busy
        private set

    private val transferJournal
        get() = session.transferJournal

    private val journalMutex
        get() = session.journalMutex

    var journalError by session.journalError
        private set

    var damagedTransferJournal by session.damagedTransferJournal
        private set

    var transferJobs by session.transferJobs
        private set

    var activeTransferId by session.activeTransferId
        private set

    val activeTransfer
        get() = transferJobs.firstOrNull { it.id == activeTransferId }

    private val transferSubmissions
        get() = session.transferSubmissions

    var fileConflict by session.fileConflict
        private set

    private var conflictAnswer: CompletableDeferred<ConflictDecision>?
        get() = session.conflictAnswer
        set(value) {
            session.conflictAnswer = value
        }

    var conflictPolicy by session.conflictPolicy
        private set

    fun changeConflictPolicy(value: ConflictPolicy) {
        conflictPolicy = value
        prefs.edit().putString("conflictPolicy", value.name).apply()
    }

    fun resolveConflict(decision: ConflictDecision) {
        conflictAnswer?.complete(decision)
    }

    private suspend fun askConflict(
        job: TransferJob,
        source: Entry,
        existing: Entry,
        mergeAllowed: Boolean = true,
    ): ConflictDecision {
        val answer = CompletableDeferred<ConflictDecision>()
        conflictAnswer = answer
        fileConflict = FileConflict(job.id, source, existing, mergeAllowed)
        try {
            return answer.await()
        } finally {
            if (conflictAnswer === answer) {
                fileConflict = null
                conflictAnswer = null
            }
        }
    }

    var message by session.message
    var interruptedTransfer by session.interruptedTransfer
        private set

    internal var folderStorageIssue by mutableStateOf<StorageIssue?>(null)
        private set

    internal var reconnectChoice by mutableStateOf<ReconnectChoice?>(null)
    internal var storageRequested by mutableStateOf(false)

    var listingError by mutableStateOf<String?>(null)
        private set

    var truncated by mutableStateOf(false)
        private set

    var clipboard by session.clipboard
        private set

    private val history
        get() = session.history

    var undoLabel by session.undoLabel
        private set

    var redoLabel by session.redoLabel
        private set

    val canUndoTrash
        get() = undoLabel == "Move to Trash"

    var transferProgress by session.transferProgress
        private set

    private var transferControl: TransferControl
        get() = session.transferControl
        set(value) {
            session.transferControl = value
        }

    var favorites by session.favorites
        private set

    private val tagStore
        get() = session.tagStore

    var tagAssignments by session.tagAssignments
        private set

    var tagDefinitions by session.tagDefinitions
        private set

    var tags by session.tags
        private set

    val tagNames
        get() = tagDefinitions.map { it.name }

    var volumes by session.volumes
        private set

    internal var viewportInteraction by mutableIntStateOf(0)
        private set

    private var listingLocation: String? = null
    private var listingJob: Job? = null
    private var listingControl: ListingControl? = null
    private var listingGeneration = 0L
    private var listingIdentity: List<Any?>? = null
    private var listingBudget = ListingBudget()
    private var listingBudgetKey: ListingScope? = null
    internal var listingProgress by mutableStateOf(ListingProgress())
        private set

    internal var listingDetailsRequested by mutableStateOf(false)
    internal val canLoadMore
        get() =
            (listingProgress.resultLimit && listingBudget.results < 50_000) ||
                (listingProgress.scanLimit && listingBudget.scanned < 2_000_000)

    internal val listingCountText: String
        get() =
            when {
                loading -> "${entries.size} shown · scanning…"
                selected.isNotEmpty() -> "${selected.size} of ${entries.size} selected"
                effectiveSearchRules.active || currentSmartFolder != null ->
                    "${entries.size} matches"
                else -> "${entries.size} item${if (entries.size == 1) "" else "s"}"
            } + if (!loading && listingProgress.incomplete) " · incomplete" else ""

    fun stopLoading() {
        if (!loading) return
        viewportInteraction++
        listingGeneration++
        listingControl?.cancel()
        listingJob?.cancel()
        loading = false
        listingProgress = listingProgress.copy(stopped = true)
        truncated = true
        finishListingSelection()
        stopListingObservers()
    }

    fun loadMoreResults() {
        if (loading || !canLoadMore) return
        listingBudget = listingBudget.more()
        val tab = tabs[activeTab]
        listingBudgetKey?.let { key ->
            val before = listingBudgets[tab.id] ?: tab.navigation.budgets
            listingBudgets[tab.id] =
                (before - key + (key to listingBudget)).entries.toList().takeLast(32).associate {
                    it.toPair()
                }
        }
        persistTabs()
        refresh()
    }

    private fun stopListingObservers() {
        observer?.stopWatching()
        observer = null
        providerObserver?.let {
            getApplication<Application>().contentResolver.unregisterContentObserver(it)
        }
        providerObserver = null
    }

    private fun finishListingSelection() {
        val visible = visibleEntries.map { it.path }.toSet()
        val saved = pendingTabRestore?.takeIf { it.first == tabs[activeTab].id }?.second
        if (saved != null) {
            selected = saved.selected.intersect(visible)
            leadSelection = saved.lead?.takeIf { it in visible }
            rangeAnchor = saved.anchor?.takeIf { it in visible }
            multiSelecting = saved.multiple && selected.isNotEmpty()
            pendingTabRestore = null
        } else selected = selected.intersect(visible)
    }

    private var observer: FileObserver? = null
    private var providerObserver: android.database.ContentObserver? = null
    val selectedEntries
        get() = visibleEntries.filter { it.path in selected }

    val focused
        get() =
            visibleEntries.firstOrNull { it.path == leadSelection } ?: selectedEntries.lastOrNull()

    val actionEntries
        get(): List<Entry> {
            val items = selectedEntries
            val selectedFolders = items.filter { it.folder }.map { it.path }.toHashSet()
            return items.filter { entry ->
                generateSequence(entry.parentPath) { documents.parent(it) }
                    .take(32)
                    .none { it in selectedFolders }
            }
        }

    init {
        paneDefaults = currentFolderView()
        session.panes += this
        windowState.panes += this
        val marker = File(app.filesDir, "active-transfer.txt")
        if (
            windowState.id == "main" &&
                paneId == "primary" &&
                marker.exists() &&
                TransferRuntime.label == null
        )
            interruptedTransfer = runCatching { marker.readText() }.getOrNull()
        journalError?.let { message = it }
        restoreTabState()
        if (navigationTreeVisible) revealNavigationTree(false)
    }

    fun dismissRecovery() {
        File(getApplication<Application>().filesDir, "active-transfer.txt").delete()
        interruptedTransfer = null
    }

    private fun rulesJson(r: SearchRules) = NavigationJson.rules(r)

    private fun parseRules(r: JSONObject?) = NavigationJson.rules(r)

    private fun loadTabs(): List<FinderTab> = runCatching {
        NavigationJson.tabs(JSONArray(panePrefs.getString("tabs", "[]")), workspace.path)
    }
        .getOrElse { listOf(FinderTab(listOf(workspace.path))) }

    private fun loadClosedTabs(): List<FinderTab> = runCatching {
        val array = JSONArray(panePrefs.getString("closedTabs", "[]"))
        if (array.length() == 0) emptyList()
        else NavigationJson.tabs(array, workspace.path).takeLast(20)
    }
        .getOrDefault(emptyList())

    fun saveSmartFolder(name: String) {
        val folder = folderPath ?: currentSmartFolder?.root ?: return
        if (name.isBlank()) return
        val smart =
            SmartFolder(
                java.util.UUID.randomUUID().toString(),
                name.trim(),
                folder,
                effectiveSearchRules,
            )
        smartFolders = smartFolders + smart
        persistSmartFolders()
        navigate("@smart:${smart.id}")
    }

    private fun persistSmartFolders() {
        prefs
            .edit()
            .putString(
                "smartFolders",
                JSONArray(
                        smartFolders.map { smart ->
                            JSONObject()
                                .put("id", smart.id)
                                .put("name", smart.name)
                                .put("root", smart.root)
                                .put(
                                    "rules",
                                    JSONObject()
                                        .put("name", smart.rules.name)
                                        .put("kind", smart.rules.kind)
                                        .put("tag", smart.rules.tag)
                                        .put("days", smart.rules.days)
                                        .put("min", smart.rules.minimumBytes)
                                        .put("max", smart.rules.maximumBytes)
                                        .put("content", smart.rules.content)
                                        .put("terms", JSONArray(smart.rules.nameTerms)),
                                )
                        },
                    )
                    .toString(),
            )
            .apply()
    }

    fun removeSmartFolder(id: String) {
        smartFolders = smartFolders.filterNot { it.id == id }
        persistSmartFolders()
        session.panes
            .filter { it.location == "@smart:$id" }
            .forEach { it.navigate(workspace.absolutePath) }
    }

    fun updateSearchRules(rules: SearchRules) {
        val smart = currentSmartFolder
        if (smart != null) {
            smartFolders = smartFolders.map {
                if (it.id == smart.id) it.copy(rules = rules) else it
            }
            persistSmartFolders()
            query = ""
        } else {
            searchRules = rules.copy(name = "")
            query = rules.name
        }
        clearSelection()
        refresh(debounce = true)
    }

    fun renameSmartFolder(name: String) {
        val smart = currentSmartFolder ?: return
        if (name.isNotBlank()) {
            smartFolders = smartFolders.map {
                if (it.id == smart.id) it.copy(name = name.trim()) else it
            }
            persistSmartFolders()
        }
    }

    var folderViewRequested by mutableStateOf(false)
    val folderViews
        get() = session.folderViews

    val folderRemembered
        get() = folderViewKey(location)?.let { it in folderViews.values } == true

    internal fun currentFolderView() =
        FolderView(
            mode,
            windowsLayout,
            sort,
            ascending,
            grouping,
            showHidden,
            foldersFirst,
            previewVisible,
            previewDetailsOnly,
            iconSize,
            columnWidth,
            previewWidth,
            fitColumns,
            detailColumns,
        )

    private fun persistPaneDefaults(value: FolderView) {
        paneDefaults = value
        panePrefs
            .edit()
            .putString("view", value.mode.name)
            .putInt("windowsLayout", value.layout)
            .putString("sort", value.sort.name)
            .putBoolean("ascending", value.ascending)
            .putString("group", value.group.name)
            .putBoolean("hidden", value.hidden)
            .putBoolean("foldersFirst", value.foldersFirst)
            .putBoolean("preview", value.preview)
            .putBoolean("previewDetailsOnly", value.details)
            .putInt("iconSize", value.icons)
            .putInt("columnWidth", value.column)
            .putInt("previewWidth", value.inspector)
            .putBoolean("fitColumns", value.fit)
            .putString("detailColumns", FolderViewJson.columns(value.columns).toString())
            .apply()
    }

    private fun applyFolderView(value: FolderView) {
        mode = value.mode
        windowsLayout = value.layout
        sort = value.sort
        ascending = value.ascending
        grouping = value.group
        showHidden = value.hidden
        foldersFirst = value.foldersFirst
        previewVisible = value.preview
        previewDetailsOnly = value.details
        iconSize = value.icons
        columnWidth = value.column
        previewWidth = value.inspector
        fitColumns = value.fit
        detailColumns = value.columns
        inspectorRequested = false
        if (mode != ViewMode.LIST) selected = selected.intersect(entries.map { it.path }.toSet())
        entries = sorted(entries)
    }

    private fun applyLocationView() {
        val saved = folderViewKey(location)?.let { folderViews.values[it] }
        applyFolderView(saved ?: paneDefaults ?: currentFolderView())
    }

    private fun updateOtherFolderViews(key: String) {
        session.panes
            .filter { it !== this && folderViewKey(it.location) == key }
            .forEach {
                it.saveTabState()
                val hidden = it.showHidden
                it.applyLocationView()
                if (hidden != it.showHidden) it.refresh()
            }
    }

    private fun persistViewChange() {
        val key = folderViewKey(location)
        if (key != null && key in folderViews.values) {
            if (!folderViews.put(key, currentFolderView())) message = folderViews.error
            updateOtherFolderViews(key)
        } else persistPaneDefaults(currentFolderView())
    }

    fun rememberFolderView(enabled: Boolean) {
        val key = folderViewKey(location) ?: return
        if (enabled) {
            if (!folderViews.put(key, currentFolderView())) {
                message = folderViews.error ?: "There are already 2,000 remembered folders."
                return
            }
        } else {
            if (folderViews.protectedFile) {
                message = folderViews.error
                return
            }
            folderViews.remove(key)
            saveTabState()
            val hidden = showHidden
            applyLocationView()
            if (hidden != showHidden) refresh()
        }
        updateOtherFolderViews(key)
    }

    fun forgetFolderView(path: String) {
        val key = folderViewKey(path) ?: return
        if (key == folderViewKey(location)) rememberFolderView(false)
        else {
            folderViews.remove(key)
            updateOtherFolderViews(key)
        }
    }

    fun useViewAsPaneDefaults() {
        persistPaneDefaults(currentFolderView())
        message = "View settings saved as defaults for this pane"
    }

    fun changeDetailColumns(value: DetailColumns) {
        saveTabState()
        detailColumns = value.normalized()
        fitColumns = false
        persistViewChange()
    }

    fun changeGrouping(group: GroupBy) {
        grouping = group
        persistViewChange()
    }

    fun changeIconSize(size: Int) {
        iconSize = size.coerceIn(90, 200)
        persistViewChange()
    }

    fun changeColumnWidth(size: Int) {
        columnWidth = size.coerceIn(150, 500)
        persistViewChange()
    }

    fun changePreviewWidth(size: Int) {
        previewWidth = size.coerceIn(200, 480)
        persistViewChange()
    }

    fun toggleExtensions() {
        showExtensions = !showExtensions
        prefs.edit().putBoolean("extensions", showExtensions).apply()
    }

    fun toggleTheme() {
        darkTheme = !darkTheme
        prefs.edit().putBoolean("dark", darkTheme).apply()
    }

    fun changeSidebarWidth(size: Int) {
        sidebarWidth = size.coerceIn(150, 320)
        prefs.edit().putInt("sidebarWidth", sidebarWidth).apply()
    }

    fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
        prefs.edit().putBoolean("sidebarVisible", sidebarVisible).apply()
    }

    fun toggleTagSidebar() {
        tagsVisible = !tagsVisible
        prefs.edit().putBoolean("tagsVisible", tagsVisible).apply()
    }

    fun setFolderStyle(style: FolderStyle?) {
        selectedEntries.filter { it.folder }.forEach { styleStore.set(it.path, style) }
        folderStyles = styleStore.styles
        session.panes.forEach { it.refresh() }
    }

    fun displayName(entry: Entry) =
        if (showExtensions || entry.folder || !entry.name.contains('.')) entry.name
        else entry.name.substringBeforeLast('.')

    fun groups(): List<Pair<String, List<Entry>>> =
        if (grouping == GroupBy.NONE) listOf("" to entries)
        else
            entries.groupBy { groupLabel(it, grouping, tagAssignments[it.path].orEmpty()) }.toList()

    private fun saveTabState() {
        captureViewports()
        val tab = tabs[activeTab]
        val state =
            if (pendingTabRestore?.first == tab.id) pendingTabRestore!!.second
            else
                TabNavigation(selected, leadSelection, rangeAnchor, expandedFolders, multiSelecting)
        tabs =
            tabs.toMutableList().also {
                it[activeTab] =
                    tab.copy(
                        search = query,
                        rules = searchRules,
                        navigation =
                            state.copy(
                                positions = viewportPositions[tab.id] ?: tab.navigation.positions,
                                budgets = listingBudgets[tab.id] ?: tab.navigation.budgets,
                            ),
                    )
            }
    }

    internal fun flushNavigationState() {
        navigationSaveJob?.cancel()
        persistTabs()
    }

    private fun scheduleNavigationSave() {
        if (savingNavigation) return
        navigationSaveJob?.cancel()
        navigationSaveJob = viewModelScope.launch {
            delay(300)
            persistTabs()
        }
    }

    internal fun viewportKey(view: String) =
        ViewportKey(
            location,
            view,
            JSONObject().put("query", query).put("rules", rulesJson(searchRules)).toString(),
        )

    internal fun savedViewport(tabId: String, key: ViewportKey): ViewportAnchor? =
        (viewportPositions[tabId] ?: tabs.firstOrNull { it.id == tabId }?.navigation?.positions)
            ?.get(key)

    internal fun recordViewport(tabId: String, key: ViewportKey, point: ViewportAnchor) {
        if (tabId != tabs[activeTab].id || key != viewportKey(key.view)) return
        val tab = (tabs + closedTabs).firstOrNull { it.id == tabId } ?: return
        val before = viewportPositions[tabId] ?: tab.navigation.positions
        if (before[key] == point) return
        viewportPositions[tabId] =
            (before - key + (key to point)).entries.toList().takeLast(32).associate { it.toPair() }
        scheduleNavigationSave()
    }

    private fun persistTabs() {
        if (savingNavigation) return
        savingNavigation = true
        try {
            saveTabState()
            fun positions(tab: FinderTab) =
                tab.copy(
                    navigation =
                        tab.navigation.copy(
                            positions = viewportPositions[tab.id] ?: tab.navigation.positions,
                            budgets = listingBudgets[tab.id] ?: tab.navigation.budgets,
                        ),
                )
            tabs = tabs.map(::positions)
            closedTabs = closedTabs.map(::positions)
            viewportPositions.keys.retainAll((tabs + closedTabs).map { it.id }.toSet())
            listingBudgets.keys.retainAll((tabs + closedTabs).map { it.id }.toSet())
            panePrefs
                .edit()
                .putString("tabs", JSONArray(tabs.map(NavigationJson::tab)).toString())
                .putString("closedTabs", JSONArray(closedTabs.map(NavigationJson::tab)).toString())
                .putInt("activeTab", activeTab)
                .apply()
        } finally {
            savingNavigation = false
        }
    }

    private fun restoreTabState() {
        val tab = tabs[activeTab]
        applyLocationView()
        query = tab.search
        searchRules = tab.rules
        clearSelection()
        pendingTabRestore = tab.id to tab.navigation
        expandedFolders = tab.navigation.expanded
        expandedChildren = emptyMap()
        refresh()
    }

    fun title(path: String = location): String =
        when {
            path == "@trash" -> "Trash"
            path == "@recent" -> "Recents"
            path.startsWith("@tag:") -> path.removePrefix("@tag:")
            path.startsWith("@smart:") ->
                smartFolders.firstOrNull { it.id == path.removePrefix("@smart:") }?.name
                    ?: "Smart Folder"
            isDocumentPath(path) -> documents.title(path)
            path == deviceRoot.absolutePath -> "Tablet"
            path == workspace.absolutePath -> "Workspace"
            else -> File(path).name.ifBlank { "Storage" }
        }

    fun displayPath(path: String): String {
        if (isDocumentPath(path)) {
            val chain = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var cursor: String? = path
            while (cursor != null && seen.add(cursor) && chain.size < 24) {
                chain += documents.title(cursor)
                cursor = documents.parent(cursor)
            }
            return chain.asReversed().joinToString(" / ")
        }
        val root =
            (listOf(workspace, deviceRoot) + volumes)
                .filter { pathWithin(path, it.path) }
                .maxByOrNull { it.path.length }
        if (root != null)
            return title(root.path) +
                File(path)
                    .relativeTo(root)
                    .path
                    .takeIf { it.isNotEmpty() }
                    ?.let { " / " + it.replace("/", " / ") }
                    .orEmpty()
        // Android can canonicalize /data/user/0 to /data/data. Compare canonical roots
        // as a fallback so saved folder keys still receive familiar Workspace labels.
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val aliasRoot =
            (listOf(workspace, deviceRoot) + volumes)
                .map { it to runCatching { it.canonicalPath }.getOrDefault(it.path) }
                .filter { pathWithin(canonical, it.second) }
                .maxByOrNull { it.second.length }
        if (aliasRoot != null) {
            val relative = canonical.removePrefix(aliasRoot.second).trimStart('/')
            return title(aliasRoot.first.path) +
                if (relative.isEmpty()) "" else " / " + relative.replace("/", " / ")
        }
        return path
    }

    internal fun captureSpringNavigation(): SpringNavigationState {
        saveTabState()
        return SpringNavigationState(tabs.toList(), activeTab)
    }

    internal fun restoreSpringNavigation(
        original: SpringNavigationState,
        expected: SpringNavigationState,
    ) {
        // Keep explicit tab/navigation changes made outside this drag. Scroll/selection updates
        // within a temporary folder don't relinquish ownership of the spring-loaded route.
        if (!matchesSpringNavigation(expected)) return
        tabs = original.tabs
        activeTab = original.active
        restoreTabState()
        persistTabs()
    }

    internal fun matchesSpringNavigation(expected: SpringNavigationState): Boolean {
        fun routes(tabs: List<FinderTab>) = tabs.map { it.copy(navigation = TabNavigation()) }
        return activeTab == expected.active && routes(tabs) == routes(expected.tabs)
    }

    fun navigate(path: String) {
        if (path == location) {
            updateQuery("")
            return
        }
        saveTabState()
        val current = tabs[activeTab]
        tabs =
            tabs.toMutableList().also {
                val history = (current.history.take(current.index + 1) + path).takeLast(100)
                it[activeTab] =
                    current.copy(
                        history = history,
                        index = history.lastIndex,
                        search = "",
                        rules = SearchRules(),
                        navigation =
                            TabNavigation(
                                positions = current.navigation.positions,
                                budgets = current.navigation.budgets,
                            ),
                    )
            }
        restoreTabState()
        persistTabs()
    }

    fun back(): Boolean {
        if (tabs[activeTab].index <= 0) return false
        saveTabState()
        val tab = tabs[activeTab]
        tabs =
            tabs.toMutableList().also {
                it[activeTab] =
                    tab.copy(
                        index = tab.index - 1,
                        search = "",
                        rules = SearchRules(),
                        navigation =
                            TabNavigation(
                                positions = tab.navigation.positions,
                                budgets = tab.navigation.budgets,
                            ),
                    )
            }
        restoreTabState()
        persistTabs()
        return true
    }

    fun forward() {
        if (tabs[activeTab].index >= tabs[activeTab].history.lastIndex) return
        saveTabState()
        val tab = tabs[activeTab]
        tabs =
            tabs.toMutableList().also {
                it[activeTab] =
                    tab.copy(
                        index = tab.index + 1,
                        search = "",
                        rules = SearchRules(),
                        navigation =
                            TabNavigation(
                                positions = tab.navigation.positions,
                                budgets = tab.navigation.budgets,
                            ),
                    )
            }
        restoreTabState()
        persistTabs()
    }

    fun up() {
        parentPath()?.let(::navigate)
    }

    fun newTab(path: String = location, activate: Boolean = true, atEnd: Boolean = false) {
        if (tabs.size >= 64) {
            message = "This pane already has 64 tabs."
            return
        }
        saveTabState()
        val insertion = if (atEnd) tabs.size else maxOf(activeTab + 1, tabs.count { it.pinned })
        tabs = tabs.toMutableList().also { it.add(insertion, FinderTab(listOf(path))) }
        if (activate) {
            activeTab = insertion
            restoreTabState()
        }
        persistTabs()
    }

    fun switchTab(index: Int) {
        if (index !in tabs.indices || index == activeTab) return
        saveTabState()
        activeTab = index
        restoreTabState()
        persistTabs()
    }

    fun cycleTab(delta: Int) {
        switchTab(Math.floorMod(activeTab + delta, tabs.size))
    }

    fun duplicateTab(index: Int) {
        if (index !in tabs.indices) return
        saveTabState()
        if (tabs.size >= 64) {
            message = "This pane already has 64 tabs."
            return
        }
        val copy = tabs[index].copy(id = java.util.UUID.randomUUID().toString(), pinned = false)
        val insertion = maxOf(index + 1, tabs.count { it.pinned })
        tabs = tabs.toMutableList().also { it.add(insertion, copy) }
        activeTab = insertion
        restoreTabState()
        persistTabs()
    }

    fun moveTab(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices || from == to) return
        val active = tabs[activeTab].id
        val pinned = tabs.count { it.pinned }
        val target =
            if (tabs[from].pinned) to.coerceIn(0, pinned - 1)
            else to.coerceIn(pinned, tabs.lastIndex)
        tabs = tabs.toMutableList().also { it.add(target, it.removeAt(from)) }
        activeTab = tabs.indexOfFirst { it.id == active }
        persistTabs()
    }

    fun togglePinTab(index: Int) {
        if (index !in tabs.indices) return
        saveTabState()
        val active = tabs[activeTab].id
        val target = tabs[index].copy(pinned = !tabs[index].pinned)
        val rest = tabs.filterIndexed { i, _ -> i != index }
        val at = rest.count { it.pinned }
        tabs = rest.toMutableList().also { it.add(at, target) }
        activeTab = tabs.indexOfFirst { it.id == active }
        persistTabs()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs[index].pinned) {
            message = "Unpin this tab before closing it."
            return
        }
        saveTabState()
        val active = tabs[activeTab].id
        closedTabs = (closedTabs + tabs[index]).takeLast(20)
        tabs =
            tabs
                .filterIndexed { i, _ -> i != index }
                .ifEmpty { listOf(FinderTab(listOf(workspace.path))) }
        activeTab =
            tabs.indexOfFirst { it.id == active }.takeIf { it >= 0 }
                ?: index.coerceAtMost(tabs.lastIndex)
        if (tabs[activeTab].id != active) restoreTabState()
        persistTabs()
    }

    fun closeOtherTabs(index: Int) {
        if (index !in tabs.indices) return
        saveTabState()
        val kept = tabs[index]
        closedTabs =
            (closedTabs + tabs.filterIndexed { i, tab -> i != index && !tab.pinned }).takeLast(20)
        tabs = tabs.filter { it.pinned || it.id == kept.id }
        activeTab = tabs.indexOfFirst { it.id == kept.id }
        restoreTabState()
        persistTabs()
    }

    fun reopenTab() {
        if (closedTabs.isEmpty()) return
        if (tabs.size >= 64) {
            message = "This pane already has 64 tabs."
            return
        }
        saveTabState()
        val tab = closedTabs.last()
        closedTabs = closedTabs.dropLast(1)
        val insertion =
            if (tab.pinned) tabs.count { it.pinned }
            else maxOf(activeTab + 1, tabs.count { it.pinned })
        tabs = tabs.toMutableList().also { it.add(insertion, tab) }
        activeTab = insertion
        restoreTabState()
        persistTabs()
    }

    fun allowed(file: File): Boolean = runCatching {
        if (!file.exists()) return@runCatching false
        val path = file.canonicalFile.toPath()
        if (
            protectedStoragePath(
                path.toString(),
                listOf(deviceRoot.path) + volumes.map { it.path },
                getApplication<Application>().packageName,
            )
        )
            return@runCatching false
        path.startsWith(workspace.canonicalFile.toPath()) ||
            (hasAccess &&
                (path.startsWith(deviceRoot.canonicalFile.toPath()) ||
                    volumes.any { path.startsWith(it.canonicalFile.toPath()) }))
    }
        .getOrDefault(false)

    fun allowedPath(path: String) =
        if (isDocumentPath(path)) documents.allowed(path) else allowed(File(path))

    fun parentPath(path: String = location) = documents.parent(path)?.takeIf(::allowedPath)

    fun listPath(path: String) =
        documents
            .list(path)
            .filter {
                (showHidden || !it.name.startsWith('.')) && !it.name.startsWith(".droidfinder-")
            }
            .map { it.copy(style = folderStyles[it.path]) }
            .sortedWith(
                compareByDescending<Entry> { it.folder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )

    internal val storage
        get() = session.storage

    fun connectLocation(uri: Uri, persist: Boolean = true, replacing: String? = null) {
        val original = replacing?.let { old -> connectedLocations.firstOrNull { it.uri == old } }
        if (original != null && !documents.sameTree(original.grant, uri.toString())) {
            reconnectChoice = ReconnectChoice(original, uri.toString())
            return
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { documents.connect(uri, persist) } }
                .onSuccess {
                    connectedLocations = documents.locations
                    storage.refresh(hasAccess)
                    if (original == null) navigate(it.uri)
                    else {
                        session.panes.forEach { pane ->
                            if (
                                isDocumentPath(pane.location) &&
                                    documents.sameTree(original.grant, pane.location)
                            )
                                pane.refresh()
                        }
                        message = "Folder permission restored"
                    }
                }
                .onFailure { message = it.message ?: "Couldn't connect this folder." }
        }
    }

    fun disconnectLocation(uri: String) {
        if (session.busy.value != null) {
            message = "Wait for the current operation before removing a connection."
            return
        }
        val root = connectedLocations.firstOrNull { it.uri == uri } ?: return
        documents.disconnect(uri)
        connectedLocations = documents.locations
        storage.refresh(hasAccess)
        session.panes.forEach { pane ->
            if (isDocumentPath(pane.location) && documents.sameTree(root.grant, pane.location))
                pane.refresh()
        }
    }

    fun goTo(path: String, preservePath: Boolean = false) {
        viewModelScope.launch {
            val valid =
                withContext(Dispatchers.IO) {
                    allowedPath(path) &&
                        runCatching { documents.read(path).folder }.getOrDefault(false)
                }
            if (valid)
                navigate(
                    if (isDocumentPath(path) || preservePath) path else File(path).canonicalPath,
                )
            else message = "That folder isn't available. Check its path and storage access."
        }
    }

    fun select(entry: Entry, multiple: Boolean = false, range: Boolean = false) {
        viewportInteraction++
        pendingTabRestore = null
        scheduleNavigationSave()
        keyboardRangeBase = null
        val items = visibleEntries
        val anchor = items.indexOfFirst { it.path == (rangeAnchor ?: leadSelection) }
        val end = items.indexOfFirst { it.path == entry.path }
        if (range && anchor >= 0 && end >= 0) {
            selected =
                (if (multiple) selected else emptySet()) +
                    items.subList(minOf(anchor, end), maxOf(anchor, end) + 1).map { it.path }
            leadSelection = entry.path
            return
        }
        if (multiple) multiSelecting = true
        selected =
            if (multiple || multiSelecting)
                selected.toMutableSet().also { if (!it.add(entry.path)) it.remove(entry.path) }
            else setOf(entry.path)
        leadSelection = entry.path
        rangeAnchor = entry.path
        if (selected.isEmpty()) multiSelecting = false
    }

    fun stepSelection(delta: Int, extend: Boolean, additive: Boolean = false) {
        pendingTabRestore = null
        scheduleNavigationSave()
        keyboardScrollRequest++
        val items = visibleEntries
        if (items.isEmpty()) return
        val current = items.indexOfFirst { it.path == focused?.path }
        val next = items[(if (current < 0) 0 else current + delta).coerceIn(0, items.lastIndex)]
        if (extend) {
            if (keyboardRangeBase == null || keyboardRangeAdditive != additive) {
                keyboardRangeBase = if (additive) selected else emptySet()
                keyboardRangeAdditive = additive
            }
            if (rangeAnchor == null) rangeAnchor = focused?.path ?: next.path
            val anchor = items.indexOfFirst { it.path == rangeAnchor }.coerceAtLeast(0)
            val end = items.indexOf(next)
            selected =
                keyboardRangeBase.orEmpty() +
                    items.subList(minOf(anchor, end), maxOf(anchor, end) + 1).map { it.path }
        } else {
            keyboardRangeBase = null
            selected = setOf(next.path)
            multiSelecting = false
            rangeAnchor = next.path
        }
        leadSelection = next.path
    }

    fun selectAll() {
        pendingTabRestore = null
        scheduleNavigationSave()
        selected = visibleEntries.map { it.path }.toSet()
        multiSelecting = true
    }

    fun selectPaths(paths: Set<String>) {
        viewportInteraction++
        pendingTabRestore = null
        scheduleNavigationSave()
        selected = paths.intersect(visibleEntries.map { it.path }.toSet())
        multiSelecting = selected.isNotEmpty()
        leadSelection = visibleEntries.lastOrNull { it.path in selected }?.path
    }

    fun clearSelection() {
        viewportInteraction++
        pendingTabRestore = null
        scheduleNavigationSave()
        keyboardRangeBase = null
        selected = emptySet()
        multiSelecting = false
        leadSelection = null
        rangeAnchor = null
    }

    fun requestContext(entry: Entry, point: Offset? = null) {
        activatePane()
        viewModelScope.launch {
            if (visibleEntries.none { it.path == entry.path }) {
                entry.parentPath?.let {
                    navigate(it)
                    listingJob?.join()
                }
            }
            if (entry.path !in selected) {
                clearSelection()
                select(entry)
            }
            contextRequested = FinderContextRequest(point)
        }
    }

    fun requestBackgroundContext(point: Offset? = null) {
        activatePane()
        clearSelection()
        contextRequested = FinderContextRequest(point, true)
    }

    fun typeSelect(character: Char) {
        keyboardScrollRequest++
        if (character.isISOControl() || character.isWhitespace()) return
        val items = visibleEntries
        if (items.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        typedPrefix =
            if (now - typedAt > 1400 || typedPrefix == character.toString()) character.toString()
            else typedPrefix + character
        typedAt = now
        val start = items.indexOfFirst { it.path == focused?.path }
        val indices = items.indices.map { (it + start + 1).mod(items.size) }
        indices
            .firstOrNull { items[it].name.startsWith(typedPrefix, true) }
            ?.let {
                clearSelection()
                select(items[it])
            }
    }

    fun revealSelection() {
        val item = focused ?: return
        val parent = item.parentPath ?: return
        if (!allowedPath(parent)) return
        navigate(parent)
        viewModelScope.launch {
            listingJob?.join()
            selected = setOf(item.path)
            leadSelection = item.path
        }
    }

    fun updateQuery(value: String) {
        saveTabState()
        query = value
        clearSelection()
        persistTabs()
        refresh(debounce = true)
    }

    fun changeMode(value: ViewMode) {
        saveTabState()
        fitColumns = false
        windowsLayout = 0
        mode = value
        if (value != ViewMode.LIST) selected = selected.intersect(entries.map { it.path }.toSet())
        persistViewChange()
    }

    fun changeSort(value: SortBy) {
        if (sort == value) ascending = !ascending
        else {
            sort = value
            ascending = true
        }
        persistViewChange()
        entries = sorted(entries)
    }

    fun toggleHidden() {
        showHidden = !showHidden
        persistViewChange()
        refresh()
    }

    fun togglePreview() {
        previewVisible = !previewVisible
        if (!previewVisible) inspectorRequested = false
        persistViewChange()
    }

    fun toggleFoldersFirst() {
        foldersFirst = !foldersFirst
        persistViewChange()
        entries = sorted(entries)
    }

    fun favorite(file: File) = favoritePath(file.absolutePath)

    fun favoritePath(path: String) {
        favorites = favorites.toMutableSet().also { if (!it.add(path)) it.remove(path) }
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    private fun syncTags() {
        tagAssignments = tagStore.assignments
        tagDefinitions = tagStore.definitions
        tags = tagAssignments.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun tagEdit(label: String, route: Pair<String, String>? = null, edit: () -> Unit) {
        if (busy != null) return
        message = null
        val before = tagStore.assignments
        val catalog = tagStore.definitions
        runCatching {
            edit()
            val after = tagStore.assignments
            val newCatalog = tagStore.definitions
            history.record(
                label,
                listOf(
                    FileStep.Callback(
                        check = {},
                        perform = { undo ->
                            tagStore.restore(
                                if (undo) catalog else newCatalog,
                                if (undo) before else after,
                            )
                            if (route == null) emptyList()
                            else listOf(if (undo) route.second to route.first else route)
                        },
                    ),
                ),
            )
            syncTags()
            syncHistory()
            if (route != null) relocateMetadataPaths(listOf(route))
            session.panes.filter { it.location.startsWith("@tag:") }.forEach { it.refresh() }
        }
            .onFailure { message = it.message }
    }

    fun tag(color: String?) {
        if (selected.isNotEmpty()) tagEdit("Tags") { tagStore.assign(selected, color) }
    }

    fun defineTag(name: String, color: String) = tagEdit("New tag") { tagStore.define(name, color) }

    fun renameTag(old: String, name: String, color: String) =
        tagEdit("Rename tag", "@tag:$old" to "@tag:${name.trim()}") {
            tagStore.rename(old, name, color)
        }

    fun removeTag(name: String) {
        tagEdit("Delete tag") { tagStore.remove(name) }
        session.panes
            .filter { it.location == "@tag:$name" }
            .forEach { it.navigate(workspace.absolutePath) }
    }

    fun tagColorName(name: String) = tagDefinitions.firstOrNull { it.name == name }?.color ?: "Blue"

    private fun relocateMetadata(old: String, new: String) =
        relocateMetadataPaths(listOf(old to new))

    private fun relocateMetadataMany(pairs: List<Pair<File, File>>) {
        relocateMetadataPaths(pairs.map { it.first.absolutePath to it.second.absolutePath })
    }

    private fun relocateMetadataPaths(names: List<Pair<String, String>>) {
        if (names.isEmpty()) return
        val tagRoutes = names.filter {
            it.first.startsWith("@tag:") && it.second.startsWith("@tag:")
        }
        if (tagRoutes.isNotEmpty()) {
            val replacements = tagRoutes.associate {
                it.first.removePrefix("@tag:") to it.second.removePrefix("@tag:")
            }
            smartFolders = smartFolders.map {
                it.copy(rules = it.rules.copy(tag = replacements[it.rules.tag] ?: it.rules.tag))
            }
            persistSmartFolders()
            session.panes.forEach { pane ->
                pane.searchRules =
                    pane.searchRules.copy(
                        tag = replacements[pane.searchRules.tag] ?: pane.searchRules.tag,
                    )
            }
        }
        tagStore.relocate(names)
        syncTags()
        styleStore.relocate(names)
        folderStyles = styleStore.styles
        fun update(path: String): String = remapPath(path, names)
        favorites = favorites.map(::update).toSet()
        prefs.edit().putStringSet("favorites", favorites).apply()
        smartFolders = smartFolders.map { it.copy(root = update(it.root)) }
        persistSmartFolders()
        folderViews.relocate(names)
        session.savedWorkspaces.value = savedWorkspaces.map { it.remap(names) }
        if (savedWorkspaces.isNotEmpty()) persistWorkspaces()
        session.panes.forEach { pane ->
            pane.saveTabState()
            pane.tabs = pane.tabs.map { it.remap(names) }
            pane.closedTabs = pane.closedTabs.map { it.remap(names) }
            pane.viewportPositions.replaceAll { _, points ->
                TabNavigation(positions = points).remap(names).positions
            }
            pane.listingBudgets.replaceAll { _, budgets ->
                TabNavigation(budgets = budgets).remap(names).budgets
            }
            pane.pendingTabRestore =
                pane.pendingTabRestore?.let { it.first to it.second.remap(names) }
            pane.selected = pane.selected.map(::update).toSet()
            pane.leadSelection = pane.leadSelection?.let(::update)
            pane.rangeAnchor = pane.rangeAnchor?.let(::update)
            pane.expandedFolders = pane.expandedFolders.map(::update).toSet()
            val resolvedView =
                folderViewKey(pane.location)?.let { folderViews.values[it] } ?: pane.paneDefaults
            if (resolvedView != null && resolvedView != pane.currentFolderView())
                pane.applyFolderView(resolvedView)
            pane.persistTabs()
        }
    }

    private fun copyTags(old: String, new: String) {
        folderViews.relocate(listOf(old to new), copy = true)
        tagStore.relocate(listOf(old to new), copy = true)
        syncTags()
        styleStore.relocate(listOf(old to new), copy = true)
        folderStyles = styleStore.styles
    }

    private fun copyMetadataPaths(pairs: List<Pair<String, String>>) {
        folderViews.relocate(pairs, copy = true)
        tagStore.relocate(pairs, copy = true)
        syncTags()
        styleStore.relocate(pairs, copy = true)
        folderStyles = styleStore.styles
    }

    internal fun refreshStorageVolumes() {
        volumes =
            getApplication<Application>()
                .getSystemService(StorageManager::class.java)
                .storageVolumes
                .mapNotNull { it.directory }
                .filter { it != deviceRoot && it.canRead() }
    }

    fun onResume() {
        updateNotificationState()
        val before = hasAccess
        hasAccess = Environment.isExternalStorageManager()
        refreshStorageVolumes()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { documents.refreshGrantState() }
            storage.refresh(hasAccess)
            if (!before && hasAccess && activePaneModel.location == workspace.absolutePath)
                activePaneModel.navigate(deviceRoot.absolutePath)
            else session.panes.forEach { it.refresh() }
        }
    }

    private fun entryComparator(
        order: SortBy = sort,
        forward: Boolean = ascending,
        folders: Boolean = foldersFirst,
    ): Comparator<Entry> {
        var comparator =
            when (order) {
                SortBy.NAME -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                SortBy.MODIFIED -> compareBy { it.modified }
                SortBy.SIZE -> compareBy { it.size }
                SortBy.KIND -> compareBy { it.kind }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        if (!forward) comparator = comparator.reversed()
        return if (folders) compareByDescending<Entry> { it.folder }.then(comparator)
        else comparator
    }

    private fun sorted(items: List<Entry>) = items.sortedWith(entryComparator())

    fun refresh(debounce: Boolean = false) {
        listingControl?.cancel()
        listingJob?.cancel()
        stopListingObservers()
        val generation = ++listingGeneration
        val control = ListingControl().also { listingControl = it }
        val path = location
        val search = query.trim()
        val hidden = showHidden
        val smart = smartFolders.firstOrNull { path == "@smart:${it.id}" }
        val rules = effectiveSearchRules
        val identity = listOf(path, rules, hidden, smart?.root, tabs[activeTab].id)
        val budgetKey = ListingScope(path, rules, hidden, smart?.root)
        listingBudgetKey = budgetKey
        if (listingIdentity != identity) {
            listingIdentity = identity
            val tab = tabs[activeTab]
            listingBudget =
                (listingBudgets[tab.id] ?: tab.navigation.budgets)[budgetKey]?.normalized()
                    ?: ListingBudget()
            entries = emptyList()
        }
        val previousCount = entries.size
        val order = Triple(sort, ascending, foldersFirst)
        val comparator = entryComparator()
        val tagsSnapshot = tagAssignments.toMap()
        val stylesSnapshot = folderStyles.toMap()
        val budget = listingBudget
        if (listingLocation != path) {
            entries = emptyList()
            folderInfo = null
            expandedChildren = emptyMap()
            listingLocation = path
        }
        loading = true
        listingError = null
        folderStorageIssue = null
        folderInfo = null
        truncated = false
        listingProgress = ListingProgress()
        listingJob = viewModelScope.launch {
            if (debounce) delay(220)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val info =
                        if (!path.startsWith("@")) {
                            check(allowedPath(path)) {
                                "Storage access is needed to open this folder."
                            }
                            documents.read(path, control)
                        } else null
                    val explicit =
                        when {
                            path == "@trash" -> engine.trashItems().map { it.path }
                            path.startsWith("@tag:") ->
                                tagsSnapshot
                                    .filterValues { path.removePrefix("@tag:") in it }
                                    .keys
                                    .toList()
                            else -> null
                        }
                    val roots =
                        when {
                            smart != null -> {
                                check(allowedPath(smart.root)) {
                                    "Reconnect the Smart Folder's location."
                                }
                                listOf(smart.root)
                            }
                            path == "@recent" ->
                                listOf(workspace.path) +
                                    (if (hasAccess) listOf(deviceRoot.path) else emptyList()) +
                                    connectedLocations.map { it.uri }
                            else -> listOf(path)
                        }
                    val trashRequest = path == "@trash"
                    val source =
                        object : ListingSource {
                            override suspend fun children(
                                path: String,
                                control: ListingControl,
                                visit: suspend (Entry) -> Boolean,
                            ): DirectoryReply {
                                check(allowedPath(path)) {
                                    "Storage access is needed to read this folder."
                                }
                                return documents.visitChildren(path, control, visit)
                            }

                            override fun read(path: String, control: ListingControl): Entry {
                                check(
                                    allowedPath(path) ||
                                        (trashRequest &&
                                            File(path)
                                                .canonicalFile
                                                .toPath()
                                                .startsWith(
                                                    File(
                                                            getApplication<Application>().filesDir,
                                                            "Trash",
                                                        )
                                                        .canonicalFile
                                                        .toPath(),
                                                )),
                                ) {
                                    "Storage access is needed to read this item."
                                }
                                return documents.read(path, control)
                            }

                            override fun input(entry: Entry, control: ListingControl) =
                                documents.input(entry.path, control)

                            override fun searchable(entry: Entry) =
                                entry.documentUri != null ||
                                    !java.nio.file.Files.isSymbolicLink(entry.file.toPath())

                            override fun traversable(entry: Entry) =
                                searchable(entry) &&
                                    !(entry.parentPath == deviceRoot.path &&
                                        entry.name == "Android")
                        }
                    val request =
                        ListingRequest(
                            roots,
                            smart != null || rules.active || path == "@recent",
                            hidden,
                            rules,
                            tagsSnapshot,
                            stylesSnapshot,
                            comparator,
                            budget,
                            path == "@recent",
                            explicit,
                        )
                    val batch =
                        DirectoryLoader(source).load(request, control) { update ->
                            withContext(Dispatchers.Main.immediate) {
                                if (generation == listingGeneration) {
                                    if (order != Triple(sort, ascending, foldersFirst)) {
                                        refresh()
                                    } else {
                                        folderInfo = info
                                        if (update.entries.size >= previousCount)
                                            entries = update.entries
                                        listingProgress = update.progress
                                    }
                                }
                            }
                        }
                    batch to info
                }
            }
            result
                .onSuccess { (batch, info) ->
                    if (generation != listingGeneration) return@onSuccess
                    if (order != Triple(sort, ascending, foldersFirst)) {
                        refresh()
                        return@onSuccess
                    }
                    folderInfo = info
                    entries = batch.entries
                    listingProgress = batch.progress
                    truncated = batch.progress.incomplete
                    if (expandedFolders.isNotEmpty()) {
                        expandedChildren =
                            withContext(Dispatchers.IO) {
                                expandedFolders
                                    .filter {
                                        allowedPath(it) &&
                                            (folderPath == null ||
                                                pathWithin(it, folderPath!!) ||
                                                generateSequence(documents.parent(it)) { parent ->
                                                        documents.parent(parent)
                                                    }
                                                    .take(32)
                                                    .any { parent -> parent == folderPath })
                                    }
                                    .associateWith {
                                        runCatching { listPath(it) }.getOrDefault(emptyList())
                                    }
                            }
                    }
                    if (generation != listingGeneration) return@onSuccess
                    finishListingSelection()
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (generation == listingGeneration) {
                        entries = emptyList()
                        listingError = it.message ?: "Couldn't load this folder."
                        if (!path.startsWith("@"))
                            folderStorageIssue = storage.issue(path, it, hasAccess)
                        finishListingSelection()
                    }
                }
            if (generation != listingGeneration) return@launch
            loading = false
            observer?.stopWatching()
            providerObserver?.let {
                getApplication<Application>().contentResolver.unregisterContentObserver(it)
            }
            providerObserver = null
            if (isDocumentPath(path))
                runCatching {
                    providerObserver =
                        object :
                                android.database.ContentObserver(
                                    android.os.Handler(android.os.Looper.getMainLooper()),
                                ) {
                                override fun onChange(selfChange: Boolean) {
                                    if (generation == listingGeneration && busy == null)
                                        refresh(debounce = true)
                                }
                            }
                            .also {
                                getApplication<Application>()
                                    .contentResolver
                                    .registerContentObserver(
                                        documents.notificationUri(path),
                                        true,
                                        it,
                                    )
                            }
                }
            directory
                ?.takeIf { it.isDirectory && allowed(it) }
                ?.let { folder ->
                    observer =
                        object :
                                FileObserver(
                                    folder,
                                    CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE,
                                ) {
                                override fun onEvent(event: Int, path: String?) {
                                    // Android can deliver IN_IGNORED when an old watch is stopped,
                                    // even though it is outside the requested mask.
                                    if (
                                        event and
                                            (CREATE or
                                                DELETE or
                                                MOVED_FROM or
                                                MOVED_TO or
                                                CLOSE_WRITE) == 0
                                    )
                                        return
                                    viewModelScope.launch {
                                        if (
                                            generation == listingGeneration &&
                                                directory?.path == folder.path &&
                                                busy == null &&
                                                query.isEmpty()
                                        )
                                            refresh(debounce = true)
                                    }
                                }
                            }
                            .also { it.startWatching() }
                }
        }
    }

    private fun syncHistory() {
        undoLabel = history.undoLabel
        redoLabel = history.redoLabel
    }

    private suspend fun rememberOperation(label: String, steps: List<FileStep>) {
        withContext(Dispatchers.IO) { history.record(label, steps) }
        syncHistory()
    }

    fun cancelTransfer() {
        transferControl.cancel()
        conflictAnswer?.complete(ConflictDecision(ConflictAction.CANCEL))
    }

    private fun operation(
        label: String,
        destination: String? = folderPath,
        clearOriginSelection: Boolean = true,
        onFinished: suspend (Exception?) -> Unit = {},
        work: suspend () -> String,
    ) {
        if (busy != null) {
            message = "Another file operation is in progress."
            return
        }
        val originTab = tabs[activeTab].id
        val originLocation = location
        val originSelection = selected
        message = null
        busy = label
        transferProgress = TransferProgress()
        var lastReport = 0L
        var lastPhase = ""
        transferControl = TransferControl { progress ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastReport > 80 || progress.phase != lastPhase) {
                lastReport = now
                lastPhase = progress.phase
                val jobId = activeTransferId
                TransferRuntime.scope.launch {
                    transferProgress = progress
                    jobId?.let { id ->
                        updateTransfer(id) {
                            it.copy(
                                bytes = progress.bytes,
                                items = progress.items,
                                current = progress.current,
                            )
                        }
                    }
                }
                TransferRuntime.report(getApplication(), progress)
            }
        }
        TransferRuntime.scope.launch {
            val marker = File(getApplication<Application>().filesDir, "active-transfer.txt")
            var failure: Exception? = null
            try {
                TransferRuntime.begin(
                    getApplication(),
                    label,
                    transferControl,
                    activeTransferId != null,
                )
                withContext(Dispatchers.IO) {
                    marker.writeText(
                        "$label\n${destination?:location}\n${System.currentTimeMillis()}",
                    )
                }
                message = work()
            } catch (error: Exception) {
                failure = error
                message = error.message ?: "Couldn't complete the operation."
            } finally {
                try {
                    onFinished(failure)
                } catch (error: Exception) {
                    message = "Transfer history: ${error.message}"
                }
                marker.delete()
                TransferRuntime.finish(getApplication())
                if (
                    clearOriginSelection &&
                        tabs[activeTab].id == originTab &&
                        location == originLocation &&
                        selected == originSelection
                )
                    clearSelection()
                syncHistory()
                session.panes.forEach { it.refresh() }
                busy = null
                startNextTransfer()
            }
        }
    }

    private suspend fun runHistory(backward: Boolean) {
        try {
            val changes =
                withContext(Dispatchers.IO) { if (backward) history.undo() else history.redo() }
            relocateMetadataPaths(changes)
        } catch (error: HistoryFailure) {
            relocateMetadataPaths(error.changes)
            throw error
        } finally {
            syncTags()
            folderStyles = styleStore.styles
        }
    }

    fun undo() {
        val label = undoLabel ?: return
        operation("Undoing $label…") {
            runHistory(true)
            "Undid $label"
        }
    }

    fun redo() {
        val label = redoLabel ?: return
        operation("Redoing $label…") {
            runHistory(false)
            "Redid $label"
        }
    }

    fun undoTrash() = undo()

    fun makeShortcut() {
        val item = focused ?: return
        makeShortcuts(listOf(item.path), folderPath ?: item.parentPath ?: return)
    }

    fun makeShortcuts(paths: List<String>, parent: String) {
        if (paths.isEmpty()) return
        operation("Creating shortcuts…", destination = parent, clearOriginSelection = false) {
            val staging =
                File(
                        getApplication<Application>().cacheDir,
                        "shortcuts/${java.util.UUID.randomUUID()}",
                    )
                    .apply { mkdirs() }
            val steps = mutableListOf<FileStep>()
            try {
                withContext(Dispatchers.IO) {
                    check(allowedPath(parent)) { "This destination needs storage access." }
                    for (path in paths.distinct()) {
                        transferControl.check()
                        check(allowedPath(path)) { "The shortcut target needs storage access." }
                        val entry = documents.read(path)
                        val local = File(staging, validateFilename("${entry.name}.finderlink"))
                        FinderShortcut.write(local, path)
                        val result = documents.copy(local.path, parent, false, transferControl)
                        steps += documents.createdStep(result)
                        local.delete()
                    }
                }
            } finally {
                rememberOperation("Make shortcuts", steps)
                staging.deleteRecursively()
            }
            "${steps.size} shortcut${if(steps.size==1) "" else "s"} created"
        }
    }

    fun followShortcut(entry: Entry, open: (Entry) -> Unit) {
        viewModelScope.launch {
            runCatching {
                    withContext(Dispatchers.IO) {
                        var current = entry
                        val visited = mutableSetOf<String>()
                        while (current.extension == "finderlink") {
                            check(visited.add(current.path) && visited.size <= 16) {
                                "This shortcut contains a cycle."
                            }
                            val target = FinderShortcut.read(documents.input(current.path))
                            check(allowedPath(target)) {
                                "The shortcut's location needs storage access or reconnection."
                            }
                            current = documents.read(target)
                            check(current.documentUri != null || current.file.exists()) {
                                "The shortcut target was moved or deleted."
                            }
                        }
                        current
                    }
                }
                .onSuccess(open)
                .onFailure { message = it.message ?: "Couldn't resolve this shortcut." }
        }
    }

    fun prepareFolderShare(items: List<Entry>, ready: (List<Entry>) -> Unit) {
        if (items.isEmpty()) return
        operation("Preparing files for sharing…") {
            val staging =
                File(
                        getApplication<Application>().cacheDir,
                        "shared/${java.util.UUID.randomUUID()}",
                    )
                    .apply { mkdirs() }
            try {
                val archive =
                    withContext(Dispatchers.IO) {
                        val local = items.map {
                            File(
                                documents.copy(
                                    it.path,
                                    staging.absolutePath,
                                    false,
                                    transferControl,
                                ),
                            )
                        }
                        val archive = engine.compress(local, staging, transferControl)
                        local.forEach { it.deleteRecursively() }
                        archive
                    }
                ready(listOf(Entry(archive)))
                "Share sheet opened"
            } catch (error: Exception) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    fun createFolder(name: String) {
        val folder = folderPath ?: return
        operation("Creating folder…") {
            val step =
                withContext(Dispatchers.IO) {
                    documents.createdStep(documents.create(folder, name, true))
                }
            rememberOperation("New folder", listOf(step))
            "Folder created"
        }
    }

    fun createTextFile(name: String) {
        val folder = folderPath ?: return
        operation("Creating text file…") {
            val step =
                withContext(Dispatchers.IO) {
                    documents.createdStep(
                        documents.create(folder, validateFilename(name), false, "text/plain"),
                    )
                }
            rememberOperation("New text file", listOf(step))
            "Text file created"
        }
    }

    fun rename(name: String) {
        val item = focused ?: return
        if (name == item.name) return
        operation("Renaming…") {
            val (result, step) =
                withContext(Dispatchers.IO) {
                    val result = documents.rename(item.path, name)
                    result to documents.renamedStep(item, result)
                }
            relocateMetadata(item.path, result)
            rememberOperation("Rename", listOf(step))
            "Renamed to $name"
        }
    }

    fun renameMany(rule: RenameRule) {
        val items = selectedEntries
        if (items.isEmpty()) return
        operation("Renaming ${items.size} items…") {
            val (pairs, step) =
                withContext(Dispatchers.IO) {
                    val pairs =
                        documents.renameBatch(
                            items.mapIndexed { index, item ->
                                item to rule.name(item.name, index, item.folder)
                            },
                        )
                    pairs to documents.renamedBatchStep(items, pairs)
                }
            relocateMetadataPaths(pairs)
            rememberOperation("Rename ${items.size} items", listOf(step))
            "${items.size} items renamed"
        }
    }

    fun copySelection(cut: Boolean) {
        if (selectedEntries.isEmpty()) return
        clipboard = ClipboardFiles(actionEntries.map { it.path }, cut)
        message =
            "${selected.size} item${if (selected.size == 1) "" else "s"} ready to ${if (cut) "move" else "copy"}"
    }

    fun importUris(uris: List<Uri>, parent: File, onComplete: () -> Unit = {}) {
        if (busy != null) {
            onComplete()
            return
        }
        operation("Importing files…", onFinished = { onComplete() }) {
            val steps = mutableListOf<FileStep>()
            try {
                for (uri in uris) {
                    val target =
                        withContext(Dispatchers.IO) {
                            val resolver = getApplication<Application>().contentResolver
                            val name =
                                resolver
                                    .query(
                                        uri,
                                        arrayOf(OpenableColumns.DISPLAY_NAME),
                                        null,
                                        null,
                                        null,
                                    )
                                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                                    ?: "Imported file"
                            val destination = engine.uniqueFile(parent, validateFilename(name))
                            val staging =
                                File(parent, ".droidfinder-${java.util.UUID.randomUUID()}")
                            try {
                                transferControl.item(name)
                                val input =
                                    resolver.openInputStream(uri) ?: error("Couldn't read $name")
                                input.use { source ->
                                    staging.outputStream().use { transferControl.copy(source, it) }
                                }
                                transferControl.check()
                                java.nio.file.Files.move(staging.toPath(), destination.toPath())
                            } catch (error: Exception) {
                                staging.delete()
                                throw error
                            }
                            destination
                        }
                    steps += withContext(Dispatchers.IO) { FileStep.Created(target) }
                }
            } finally {
                rememberOperation("Import", steps)
            }
            "${uris.size} files imported"
        }
    }

    fun importToPath(uris: List<Uri>, parent: String, onComplete: () -> Unit = {}) {
        if (!isDocumentPath(parent)) {
            importUris(uris, File(parent), onComplete)
            return
        }
        if (busy != null) {
            onComplete()
            return
        }
        operation("Importing files…", onFinished = { onComplete() }) {
            val steps = mutableListOf<FileStep>()
            try {
                for (uri in uris) {
                    steps +=
                        withContext(Dispatchers.IO) {
                            documents.createdStep(documents.importUri(uri, parent, transferControl))
                        }
                }
            } finally {
                rememberOperation("Import", steps)
            }
            "${uris.size} files imported"
        }
    }

    fun paste(parent: String? = folderPath) {
        val clip = clipboard ?: return
        if (parent == null) return
        transferPaths(clip.paths, parent, clip.cut, clearClipboard = clip.cut)
    }

    private fun updateTransfer(id: String, change: (TransferJob) -> TransferJob) {
        transferJobs = transferJobs.map { if (it.id == id) change(it) else it }
    }

    private suspend fun saveTransfers() {
        journalMutex.withLock {
            val snapshot = transferJobs
            try {
                withContext(Dispatchers.IO) { transferJournal.save(snapshot) }
            } catch (error: Exception) {
                journalError = "Transfer journal could not be saved: ${error.message}"
                throw error
            }
        }
    }

    fun preserveAndResetTransferJournal() {
        TransferRuntime.scope.launch {
            try {
                withContext(Dispatchers.IO) { transferJournal.preserveDamaged() }
                damagedTransferJournal = false
                saveTransfers()
                journalError = null
                startNextTransfer()
                message =
                    "Damaged history was preserved in app storage. File contents were not changed."
            } catch (error: Exception) {
                message = error.message
            }
        }
    }

    fun retryTransferJournal() {
        if (damagedTransferJournal) return
        TransferRuntime.scope.launch {
            try {
                saveTransfers()
                journalError = null
                startNextTransfer()
            } catch (error: Exception) {
                message = journalError
            }
        }
    }

    fun transferPaths(
        paths: List<String>,
        parent: String,
        move: Boolean,
        clearClipboard: Boolean = false,
        policy: ConflictPolicy = conflictPolicy,
    ) {
        if (paths.isEmpty()) return
        if (journalError != null) {
            message = journalError
            return
        }
        val job =
            TransferJob(
                paths = paths.distinct(),
                destination = parent,
                move = move,
                conflictPolicy = policy,
            )
        transferSubmissions[job.id] =
            TransferSubmission(
                this,
                clipboard,
                clearClipboard,
                tabs[activeTab].id,
                location,
                selected,
            )
        transferJobs = transferJobs + job
        TransferRuntime.scope.launch {
            try {
                saveTransfers()
                startNextTransfer()
            } catch (error: Exception) {
                updateTransfer(job.id) {
                    it.copy(
                        state = TransferState.FAILED,
                        current = "Journal write failed: ${error.message}",
                    )
                }
                message = "Couldn't safely queue the transfer: ${error.message}"
            }
        }
    }

    fun transferTo(
        files: List<File>,
        parent: File,
        move: Boolean,
        clearClipboard: Boolean = false,
    ) = transferPaths(files.map { it.absolutePath }, parent.absolutePath, move, clearClipboard)

    private fun startNextTransfer() {
        if (busy != null || activeTransferId != null || journalError != null) return
        val job = transferJobs.firstOrNull { it.state == TransferState.QUEUED } ?: return
        val origin = transferSubmissions[job.id]?.pane
        if (origin != null && origin !== this) {
            origin.startNextTransfer()
            return
        }
        activeTransferId = job.id
        updateTransfer(job.id) {
            it.copy(state = TransferState.RUNNING, started = System.currentTimeMillis())
        }
        operation(
            if (job.move) "Moving items…" else "Copying items…",
            destination = job.destination,
            clearOriginSelection = false,
            onFinished = { failure ->
                val current = transferJobs.first { it.id == job.id }
                updateTransfer(job.id) {
                    it.copy(
                        state =
                            when {
                                transferControl.cancelled -> TransferState.CANCELLED
                                failure != null ||
                                    it.results.any { result -> result.error != null } ->
                                    TransferState.FAILED
                                else -> TransferState.COMPLETED
                            },
                        bytes = transferControl.snapshot.bytes,
                        items = transferControl.snapshot.items,
                        current = failure?.message ?: it.current,
                        finished = System.currentTimeMillis(),
                    )
                }
                transferSubmissions.remove(job.id)?.let { submitted ->
                    if (submitted.clearClipboard && clipboard === submitted.clipboard)
                        clipboard =
                            current.remaining
                                .takeIf { it.isNotEmpty() }
                                ?.let { ClipboardFiles(it, true) }
                    if (
                        tabs[activeTab].id == submitted.tab &&
                            location == submitted.location &&
                            selected == submitted.selection
                    )
                        clearSelection()
                }
                activeTransferId = null
                saveTransfers()
            },
        ) {
            val steps = mutableListOf<FileStep>()
            try {
                saveTransfers()
                TransferWorker(
                        job,
                        documents,
                        transferControl,
                        current = { transferJobs.first { it.id == job.id } },
                        checkpoint = { change ->
                            updateTransfer(job.id, change)
                            saveTransfers()
                        },
                        ask = { source, existing, mergeAllowed ->
                            askConflict(job, source, existing, mergeAllowed)
                        },
                        metadata = { paths, move ->
                            if (move) relocateMetadataPaths(paths) else copyMetadataPaths(paths)
                        },
                        steps = steps,
                    )
                    .run()
            } finally {
                rememberOperation(if (job.move) "Move" else "Copy", steps)
            }
            val result = transferJobs.first { it.id == job.id }
            "${result.successful.size} of ${job.paths.size} items ${if (job.move) "moved" else "copied"}" +
                if (result.results.any { it.error != null })
                    ". Some items need attention in Transfers."
                else ""
        }
    }

    fun pauseTransfer(id: String) {
        if (id == activeTransferId) {
            transferControl.pause()
            TransferRuntime.setPaused(getApplication(), true)
            updateTransfer(id) { it.copy(state = TransferState.PAUSED) }
        } else
            updateTransfer(id) {
                if (it.state == TransferState.QUEUED) it.copy(state = TransferState.PAUSED) else it
            }
        TransferRuntime.scope.launch { saveTransfers() }
    }

    fun resumeTransfer(id: String) {
        if (id == activeTransferId) {
            transferControl.resume()
            TransferRuntime.setPaused(getApplication(), false)
            updateTransfer(id) { it.copy(state = TransferState.RUNNING) }
        } else
            updateTransfer(id) {
                if (it.state == TransferState.PAUSED) it.copy(state = TransferState.QUEUED) else it
            }
        TransferRuntime.scope.launch {
            saveTransfers()
            startNextTransfer()
        }
    }

    fun cancelQueuedTransfer(id: String) {
        if (id == activeTransferId) cancelTransfer()
        else {
            updateTransfer(id) {
                if (!it.terminal)
                    it.copy(state = TransferState.CANCELLED, finished = System.currentTimeMillis())
                else it
            }
            transferSubmissions.remove(id)
            TransferRuntime.scope.launch { saveTransfers() }
        }
    }

    fun retryTransfer(id: String) {
        val job = transferJobs.firstOrNull { it.id == id && it.terminal } ?: return
        job.retryTargets
            .groupBy { it.parent }
            .forEach { (parent, targets) ->
                transferPaths(
                    targets.map { it.source },
                    parent,
                    job.move,
                    policy = ConflictPolicy.KEEP_BOTH,
                )
            }
    }

    fun moveQueuedTransfer(id: String, delta: Int) {
        val from = transferJobs.indexOfFirst {
            it.id == id &&
                it.state in setOf(TransferState.QUEUED, TransferState.PAUSED) &&
                it.id != activeTransferId
        }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, transferJobs.lastIndex)
        transferJobs = transferJobs.toMutableList().also { it.add(to, it.removeAt(from)) }
        TransferRuntime.scope.launch { saveTransfers() }
    }

    fun clearTransferHistory() {
        transferJobs = transferJobs.filter { !it.terminal }
        TransferRuntime.scope.launch { saveTransfers() }
    }

    fun duplicate() {
        val files = actionEntries.map { it.file }
        if (files.isEmpty()) return
        if (actionEntries.any { it.documentUri != null }) {
            val items = actionEntries
            val parent = items.first().parentPath ?: return
            transferPaths(items.map { it.path }, parent, false)
            return
        }
        operation("Duplicating…") {
            val steps = mutableListOf<FileStep>()
            try {
                for (file in files) {
                    val result =
                        withContext(Dispatchers.IO) {
                            val copy =
                                engine.copy(file, file.parentFile!!, control = transferControl)
                            copy to FileStep.Created(copy)
                        }
                    steps += result.second
                    copyTags(file.absolutePath, result.first.absolutePath)
                }
            } finally {
                rememberOperation("Duplicate", steps)
            }
            "${files.size} items duplicated"
        }
    }

    fun newFolderWithSelection(name: String) {
        if (
            folderPath?.let(::isDocumentPath) == true ||
                actionEntries.any { it.documentUri != null }
        ) {
            groupProviderSelection(name)
            return
        }
        val files = actionEntries.map { it.file }
        val parent = directory ?: return
        if (files.isEmpty()) return
        operation("Organizing selection…") {
            val (folder, pairs) =
                withContext(Dispatchers.IO) {
                    val folder = engine.createFolder(parent, name)
                    val pairs = mutableListOf<Pair<File, File>>()
                    try {
                        files.forEach {
                            pairs += it to engine.copy(it, folder, true, transferControl)
                        }
                    } catch (error: Exception) {
                        pairs.asReversed().forEach { engine.relocate(it.second, it.first) }
                        folder.delete()
                        throw error
                    }
                    folder to pairs.toList()
                }
            val step = withContext(Dispatchers.IO) { FileStep.Grouped(folder, pairs) }
            relocateMetadataMany(pairs)
            rememberOperation("New folder with selection", listOf(step))
            "Moved ${files.size} items into ${folder.name}"
        }
    }

    private fun groupProviderSelection(name: String) {
        val parent = folderPath ?: return
        val items = actionEntries
        if (items.isEmpty()) return
        operation("Organizing selection…") {
            val folder = withContext(Dispatchers.IO) { documents.create(parent, name, true) }
            var currentFolder = folder
            var current = items.map { it.path }
            val pairs = mutableListOf<Pair<String, String>>()
            try {
                for (item in items) {
                    pairs +=
                        item.path to
                            withContext(Dispatchers.IO) {
                                documents.copy(item.path, folder, true, transferControl)
                            }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.IO) {
                    pairs.asReversed().forEach { (original, moved) ->
                        val item = items.first { it.path == original }
                        documents.copy(moved, item.parentPath!!, true, requestedName = item.name)
                    }
                    documents.delete(folder)
                }
                throw error
            }
            current = pairs.map { it.second }
            val stamp = withContext(Dispatchers.IO) { documents.digest(folder) }
            val step =
                FileStep.Callback(
                    check = { undo ->
                        if (undo) {
                            check(documents.digest(currentFolder) == stamp) {
                                "The grouped folder changed."
                            }
                            items.forEach { item ->
                                check(
                                    documents.list(item.parentPath!!).none { it.name == item.name },
                                ) {
                                    "${item.name} already exists."
                                }
                            }
                        } else {
                            check(documents.list(parent).none { it.name == name }) {
                                "$name already exists."
                            }
                        }
                    },
                    perform = { undo ->
                        if (undo) {
                            val result = current.mapIndexed { index, path ->
                                path to
                                    documents.copy(
                                        path,
                                        items[index].parentPath!!,
                                        true,
                                        requestedName = items[index].name,
                                    )
                            }
                            current = result.map { it.second }
                            documents.delete(currentFolder)
                            result
                        } else {
                            currentFolder = documents.create(parent, name, true)
                            val result = current.map { path ->
                                path to documents.copy(path, currentFolder, true)
                            }
                            current = result.map { it.second }
                            result
                        }
                    },
                )
            relocateMetadataPaths(pairs)
            rememberOperation("New folder with selection", listOf(step))
            "Moved ${items.size} items into $name"
        }
    }

    fun trash() {
        if (isTrash) return
        val items = actionEntries
        if (items.isEmpty()) return
        operation("Moving to Trash…") {
            val steps = mutableListOf<FileStep>()
            try {
                for (item in items) {
                    steps +=
                        withContext(Dispatchers.IO) {
                            documents.trashedStep(item, documents.trash(item.path, transferControl))
                        }
                }
            } finally {
                rememberOperation("Move to Trash", steps)
            }
            "${items.size} item${if (items.size == 1) "" else "s"} moved to Trash"
        }
    }

    fun restore() {
        val files = actionEntries.map { it.file }
        if (files.isEmpty()) return
        operation("Restoring…") {
            val steps = mutableListOf<FileStep>()
            try {
                for (file in files) {
                    val original = engine.originalPath(file)
                    val (restored, step) =
                        withContext(Dispatchers.IO) {
                            val restored = documents.restore(file, transferControl)
                            restored to documents.createdStep(restored)
                        }
                    steps += step
                    val mapping = documents.lastRestoreMappings
                    if (mapping.isNotEmpty()) relocateMetadataPaths(mapping)
                    else if (original != null && original != restored)
                        relocateMetadata(original, restored)
                }
            } finally {
                rememberOperation("Restore", steps)
            }
            "${files.size} items restored"
        }
    }

    fun deleteForever() {
        val fromTrash = isTrash
        val items = actionEntries
        if (items.isEmpty()) return
        operation("Deleting permanently…") {
            var attempted = false
            try {
                withContext(Dispatchers.IO) {
                    items.forEach { item ->
                        transferControl.check()
                        check(item.canDelete) { "${item.name} cannot be deleted." }
                        attempted = true
                        val file =
                            if (fromTrash) item.file
                            else documents.trash(item.path, transferControl)
                        engine.deleteForever(file)
                    }
                }
            } finally {
                if (attempted) history.clear()
            }
            "${items.size} items deleted permanently"
        }
    }

    fun emptyTrash() {
        operation("Emptying Trash…") {
            withContext(Dispatchers.IO) {
                engine.trashItems().forEach {
                    transferControl.check()
                    engine.deleteForever(it)
                }
            }
            history.clear()
            "Trash emptied"
        }
    }

    fun compress(name: String? = null, level: Int = -1) {
        compressTo(folderPath ?: workspace.absolutePath, name, level)
    }

    fun compressTo(parent: String, name: String? = null, level: Int = -1) {
        if (isDocumentPath(parent) || actionEntries.any { it.documentUri != null }) {
            providerArchive(false, parent, name, level)
            return
        }
        val files = actionEntries.map { it.file }
        val folder = File(parent)
        if (files.isEmpty()) return
        operation("Compressing…") {
            val (archive, step) =
                withContext(Dispatchers.IO) {
                    val file = engine.compress(files, folder, transferControl, name, level)
                    file to FileStep.Created(file)
                }
            rememberOperation("Compress", listOf(step))
            "Created ${archive.name}"
        }
    }

    fun extract() {
        extractTo(folderPath ?: focused?.parentPath ?: workspace.absolutePath)
    }

    fun extractTo(parent: String) {
        extractEntryTo(focused ?: return, parent)
    }

    fun extractEntryTo(
        entry: Entry,
        parent: String,
        selection: Set<String>? = null,
        limits: ZipLimits = ZipLimitsStore(getApplication<Application>()).read(),
    ) {
        if (isDocumentPath(parent) || entry.documentUri != null) {
            providerArchive(true, parent, source = entry, selection = selection, limits = limits)
            return
        }
        val file = entry.file
        val folder = File(parent)
        operation("Extracting…", destination = parent) {
            val (result, step) =
                withContext(Dispatchers.IO) {
                    val extracted = engine.extract(file, folder, transferControl, selection, limits)
                    extracted to FileStep.Created(extracted)
                }
            rememberOperation("Extract", listOf(step))
            "Extracted to ${result.name}"
        }
    }

    private fun providerArchive(
        extract: Boolean,
        parent: String,
        name: String? = null,
        level: Int = -1,
        source: Entry? = null,
        selection: Set<String>? = null,
        limits: ZipLimits = ZipLimits(),
    ) {
        val items = if (extract) listOfNotNull(source ?: focused) else actionEntries
        if (items.isEmpty()) return
        operation(if (extract) "Extracting archive…" else "Compressing…") {
            val staging =
                File(
                        getApplication<Application>().filesDir,
                        "archive-staging/${java.util.UUID.randomUUID()}",
                    )
                    .apply { mkdirs() }
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        val local = items.map {
                            File(
                                documents.copy(
                                    it.path,
                                    staging.absolutePath,
                                    false,
                                    transferControl,
                                ),
                            )
                        }
                        val output =
                            if (extract)
                                engine.extract(
                                    local.single(),
                                    staging,
                                    transferControl,
                                    selection,
                                    limits,
                                )
                            else engine.compress(local, staging, transferControl, name, level)
                        val result =
                            documents.copy(output.absolutePath, parent, false, transferControl)
                        result to documents.createdStep(result)
                    }
                rememberOperation(if (extract) "Extract" else "Compress", listOf(result.second))
                "Archive ${if(extract)"extracted"else "created"}"
            } finally {
                withContext(Dispatchers.IO) { staging.deleteRecursively() }
            }
        }
    }

    override fun onCleared() {
        listingControl?.cancel()
        listingJob?.cancel()
        viewportReaders.clear()
        if (paneId == "primary") windowState.secondaryStore.clear()
        session.panes.remove(this)
        windowState.panes.remove(this)
        observer?.stopWatching()
        providerObserver?.let {
            getApplication<Application>().contentResolver.unregisterContentObserver(it)
        }
        super.onCleared()
    }
}

fun formatSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

fun formatDate(time: Long): String =
    if (time == 0L) "—"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
