package dev.droidfinder

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.sync.Mutex
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

data class FinderTab(
    val history: List<String>,
    val index: Int = history.lastIndex,
    val id: String = java.util.UUID.randomUUID().toString(),
    val search: String = "",
    val rules: SearchRules = SearchRules(),
) {
    val location
        get() = history[index]
}

data class FinderContextRequest(val point: Offset? = null, val background: Boolean = false)

private data class TabSelection(
    val paths: Set<String>,
    val lead: String?,
    val expanded: Set<String>,
)

data class ClipboardFiles(val paths: List<String>, val cut: Boolean) {
    val files
        get() = paths.map(::File)
}

val tagNames = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple")

class FinderModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("finder", 0)
    val workspace = File(app.filesDir, "Workspace").apply { mkdirs() }
    val deviceRoot: File = Environment.getExternalStorageDirectory()
    val engine = FileEngine(app.filesDir)
    val documents = DocumentAccess(app, engine)
    var connectedLocations by mutableStateOf(documents.locations)
        private set

    var notificationsEnabled by
        mutableStateOf(
            androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled(),
        )
        private set

    fun updateNotificationState() {
        notificationsEnabled =
            androidx.core.app.NotificationManagerCompat.from(getApplication())
                .areNotificationsEnabled()
    }

    var hasAccess by mutableStateOf(Environment.isExternalStorageManager())
        private set

    var tabs by mutableStateOf(loadTabs())
        private set

    var activeTab by mutableStateOf(prefs.getInt("activeTab", 0).coerceIn(0, tabs.lastIndex))
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
        get() = folderPath != null && (folderInfo?.writable ?: directory?.canWrite() ?: false)

    val isTrash: Boolean
        get() = location == "@trash"

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    var expandedFolders by mutableStateOf<Set<String>>(emptySet())
        private set

    private var expandedChildren by mutableStateOf<Map<String, List<Entry>>>(emptyMap())
    val canExpandList
        get() =
            folderPath != null && query.isBlank() && !searchRules.active && grouping == GroupBy.NONE

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
        if (entry.path in expandedFolders) {
            expandedFolders = expandedFolders - entry.path
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
    var shareRequested by mutableStateOf<List<Entry>?>(null)
    var query by mutableStateOf(tabs[activeTab].search)
        private set

    var searchRules by mutableStateOf(tabs[activeTab].rules)
        private set

    var smartFolders by mutableStateOf(loadSmartFolders())
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
            runCatching { GroupBy.valueOf(prefs.getString("group", "NONE")!!) }
                .getOrDefault(GroupBy.NONE),
        )
        private set

    var density by
        mutableStateOf(
            runCatching { FinderDensity.valueOf(prefs.getString("density", "COMFORTABLE")!!) }
                .getOrDefault(FinderDensity.COMFORTABLE),
        )
        private set

    fun changeDensity(value: FinderDensity) {
        density = value
        prefs.edit().putString("density", value.name).apply()
    }

    var iconSize by mutableStateOf(prefs.getInt("iconSize", 120))
        private set

    var columnWidth by mutableStateOf(prefs.getInt("columnWidth", 240))
        private set

    var previewWidth by mutableStateOf(prefs.getInt("previewWidth", 280))
        private set

    var showExtensions by mutableStateOf(prefs.getBoolean("extensions", true))
        private set

    var darkTheme by mutableStateOf(prefs.getBoolean("dark", false))
        private set

    var sidebarWidth by mutableStateOf(prefs.getInt("sidebarWidth", 206))
        private set

    var sidebarVisible by mutableStateOf(prefs.getBoolean("sidebarVisible", true))
        private set

    var tagsVisible by mutableStateOf(prefs.getBoolean("tagsVisible", true))
        private set

    private val styleStore = FolderStyleStore(File(app.filesDir, "folder-styles.properties"))
    var folderStyles by mutableStateOf(styleStore.styles)
        private set

    var contextRequested by mutableStateOf<FinderContextRequest?>(null)
    private var closedTabs by mutableStateOf<List<FinderTab>>(emptyList())
    val canReopenTab
        get() = closedTabs.isNotEmpty()

    private val tabSelections = mutableMapOf<String, TabSelection>()
    var gridColumns = 4
    private var typedPrefix = ""
    private var typedAt = 0L
    var mode by
        mutableStateOf(
            runCatching { ViewMode.valueOf(prefs.getString("view", "LIST")!!) }
                .getOrDefault(ViewMode.LIST),
        )
        private set

    var sort by
        mutableStateOf(
            runCatching { SortBy.valueOf(prefs.getString("sort", "NAME")!!) }
                .getOrDefault(SortBy.NAME),
        )
        private set

    var ascending by mutableStateOf(prefs.getBoolean("ascending", true))
        private set

    var showHidden by mutableStateOf(prefs.getBoolean("hidden", false))
        private set

    var previewVisible by mutableStateOf(prefs.getBoolean("preview", true))
        private set

    var foldersFirst by mutableStateOf(prefs.getBoolean("foldersFirst", true))
        private set

    var loading by mutableStateOf(false)
        private set

    var busy by mutableStateOf<String?>(null)
        private set

    private val transferJournal: TransferStore = SqliteTransferStore(app)
    private val journalMutex = Mutex()
    var journalError by mutableStateOf<String?>(null)
        private set

    var damagedTransferJournal by mutableStateOf(false)
        private set

    var transferJobs by
        mutableStateOf(
            runCatching { transferJournal.load().map { it.afterRestart() } }
                .getOrElse {
                    journalError = it.message
                    damagedTransferJournal = true
                    emptyList()
                },
        )
        private set

    var activeTransferId by mutableStateOf<String?>(null)
        private set

    val activeTransfer
        get() = transferJobs.firstOrNull { it.id == activeTransferId }

    private data class TransferSubmission(
        val clipboard: ClipboardFiles?,
        val clearClipboard: Boolean,
        val tab: String,
        val location: String,
        val selection: Set<String>,
    )

    private val transferSubmissions = mutableMapOf<String, TransferSubmission>()
    var fileConflict by mutableStateOf<FileConflict?>(null)
        private set

    @Volatile private var conflictAnswer: CompletableDeferred<ConflictDecision>? = null
    var conflictPolicy by
        mutableStateOf(
            runCatching { ConflictPolicy.valueOf(prefs.getString("conflictPolicy", "ASK")!!) }
                .getOrDefault(ConflictPolicy.ASK),
        )
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

    var message by mutableStateOf<String?>(null)
    var interruptedTransfer by mutableStateOf<String?>(null)
        private set

    var listingError by mutableStateOf<String?>(null)
        private set

    var truncated by mutableStateOf(false)
        private set

    var clipboard by mutableStateOf<ClipboardFiles?>(null)
        private set

    private val history = FileHistory(engine)
    var undoLabel by mutableStateOf<String?>(null)
        private set

    var redoLabel by mutableStateOf<String?>(null)
        private set

    val canUndoTrash
        get() = undoLabel == "Move to Trash"

    var transferProgress by mutableStateOf(TransferProgress())
        private set

    private var transferControl = TransferControl()
    var favorites by mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.toSet())
        private set

    private val tagStore =
        TagStore(File(app.filesDir, "tags.properties")).also { it.migrate(loadTags()) }
    var tagAssignments by mutableStateOf(tagStore.assignments)
        private set

    var tagDefinitions by mutableStateOf(tagStore.definitions)
        private set

    var tags by mutableStateOf(tagAssignments.mapValues { it.value.firstOrNull().orEmpty() })
        private set

    val tagNames
        get() = tagDefinitions.map { it.name }

    var volumes by mutableStateOf<List<File>>(emptyList())
        private set

    private var listingJob: Job? = null
    private var observer: FileObserver? = null
    private var providerObserver: android.database.ContentObserver? = null
    val selectedEntries
        get() = visibleEntries.filter { it.path in selected }

    val focused
        get() =
            visibleEntries.firstOrNull { it.path == leadSelection && it.path in selected }
                ?: selectedEntries.lastOrNull()

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
        val marker = File(app.filesDir, "active-transfer.txt")
        if (marker.exists() && TransferRuntime.label == null)
            interruptedTransfer = runCatching { marker.readText() }.getOrNull()
        journalError?.let { message = it }
        refresh()
    }

    fun dismissRecovery() {
        File(getApplication<Application>().filesDir, "active-transfer.txt").delete()
        interruptedTransfer = null
    }

    private fun rulesJson(r: SearchRules) =
        JSONObject()
            .put("name", r.name)
            .put("kind", r.kind)
            .put("tag", r.tag)
            .put("days", r.days)
            .put("min", r.minimumBytes)
            .put("max", r.maximumBytes)
            .put("content", r.content)
            .put("terms", JSONArray(r.nameTerms))

    private fun parseRules(r: JSONObject?) =
        if (r == null) SearchRules()
        else
            SearchRules(
                r.optString("name"),
                r.optString("kind", "Any"),
                r.optString("tag"),
                r.optInt("days"),
                r.optLong("min"),
                r.optLong("max"),
                r.optString("content"),
                r.optJSONArray("terms")
                    ?.let { terms -> (0 until terms.length()).map(terms::getString) }
                    .orEmpty(),
            )

    private fun loadTabs(): List<FinderTab> = runCatching {
        val array = JSONArray(prefs.getString("tabs", "[]"))
        (0 until array.length())
            .map { i ->
                val value = array.optJSONObject(i)
                if (value == null) FinderTab(listOf(array.getString(i)))
                else {
                    val h =
                        value
                            .getJSONArray("history")
                            .let { history -> (0 until history.length()).map(history::getString) }
                            .ifEmpty { listOf(workspace.path) }
                    FinderTab(
                        h,
                        value.optInt("index", h.lastIndex).coerceIn(0, h.lastIndex),
                        value.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        value.optString("query"),
                        parseRules(value.optJSONObject("rules")),
                    )
                }
            }
            .ifEmpty { listOf(FinderTab(listOf(workspace.absolutePath))) }
    }
        .getOrElse { listOf(FinderTab(listOf(workspace.absolutePath))) }

    private fun loadTags(): Map<String, String> =
        prefs.all
            .filterKeys { it.startsWith("tag:") }
            .mapKeys { it.key.removePrefix("tag:") }
            .mapValues { it.value.toString() }

    private fun loadSmartFolders(): List<SmartFolder> = runCatching {
        val array = JSONArray(prefs.getString("smartFolders", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val r = item.getJSONObject("rules")
            SmartFolder(
                item.getString("id"),
                item.getString("name"),
                item.getString("root"),
                SearchRules(
                    r.optString("name"),
                    r.optString("kind", "Any"),
                    r.optString("tag"),
                    r.optInt("days"),
                    r.optLong("min"),
                    r.optLong("max"),
                    r.optString("content"),
                    r.optJSONArray("terms")
                        ?.let { terms -> (0 until terms.length()).map(terms::getString) }
                        .orEmpty(),
                ),
            )
        }
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
        if (location == "@smart:$id") navigate(workspace.absolutePath)
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

    fun changeGrouping(group: GroupBy) {
        grouping = group
        prefs.edit().putString("group", group.name).apply()
    }

    fun changeIconSize(size: Int) {
        iconSize = size.coerceIn(90, 200)
        prefs.edit().putInt("iconSize", iconSize).apply()
    }

    fun changeColumnWidth(size: Int) {
        columnWidth = size.coerceIn(150, 500)
        prefs.edit().putInt("columnWidth", columnWidth).apply()
    }

    fun changePreviewWidth(size: Int) {
        previewWidth = size.coerceIn(200, 480)
        prefs.edit().putInt("previewWidth", previewWidth).apply()
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
        refresh()
    }

    fun displayName(entry: Entry) =
        if (showExtensions || entry.folder || !entry.name.contains('.')) entry.name
        else entry.name.substringBeforeLast('.')

    fun groups(): List<Pair<String, List<Entry>>> =
        if (grouping == GroupBy.NONE) listOf("" to entries)
        else
            entries.groupBy { groupLabel(it, grouping, tagAssignments[it.path].orEmpty()) }.toList()

    private fun saveTabState() {
        val tab = tabs[activeTab]
        tabs =
            tabs.toMutableList().also {
                it[activeTab] = tab.copy(search = query, rules = searchRules)
            }
        tabSelections[tab.id] = TabSelection(selected, leadSelection, expandedFolders)
    }

    private fun persistTabs() {
        saveTabState()
        prefs
            .edit()
            .putString(
                "tabs",
                JSONArray(
                        tabs.map { tab ->
                            JSONObject()
                                .put("id", tab.id)
                                .put("history", JSONArray(tab.history))
                                .put("index", tab.index)
                                .put("query", tab.search)
                                .put("rules", rulesJson(tab.rules))
                        },
                    )
                    .toString(),
            )
            .putInt("activeTab", activeTab)
            .apply()
    }

    private fun restoreTabState() {
        val tab = tabs[activeTab]
        val saved = tabSelections[tab.id]
        query = tab.search
        searchRules = tab.rules
        clearSelection()
        expandedFolders = tabSelections[tab.id]?.expanded.orEmpty()
        expandedChildren = emptyMap()
        refresh()
        val job = listingJob
        viewModelScope.launch {
            job?.join()
            if (listingJob === job && tabs[activeTab].id == tab.id)
                saved?.let { saved ->
                    selectPaths(saved.paths.intersect(visibleEntries.map { it.path }.toSet()))
                    leadSelection = saved.lead?.takeIf { it in selected }
                    tabSelections[tab.id] = saved.copy(paths = selected)
                }
        }
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
        return path
    }

    fun navigate(path: String) {
        if (path == location) {
            updateQuery("")
            return
        }
        val current = tabs[activeTab]
        tabs =
            tabs.toMutableList().also {
                val history = (current.history.take(current.index + 1) + path).takeLast(100)
                it[activeTab] = current.copy(history = history, index = history.lastIndex)
            }
        query = ""
        searchRules = SearchRules()
        clearSelection()
        persistTabs()
        refresh()
    }

    fun back(): Boolean {
        val tab = tabs[activeTab]
        if (tab.index <= 0) return false
        tabs = tabs.toMutableList().also { it[activeTab] = tab.copy(index = tab.index - 1) }
        query = ""
        searchRules = SearchRules()
        clearSelection()
        persistTabs()
        refresh()
        return true
    }

    fun forward() {
        val tab = tabs[activeTab]
        if (tab.index < tab.history.lastIndex) {
            tabs = tabs.toMutableList().also { it[activeTab] = tab.copy(index = tab.index + 1) }
            query = ""
            searchRules = SearchRules()
            clearSelection()
            persistTabs()
            refresh()
        }
    }

    fun up() {
        parentPath()?.let(::navigate)
    }

    fun newTab(path: String = location, activate: Boolean = true) {
        saveTabState()
        val insertion = activeTab + 1
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
        val copy = tabs[index].copy(id = java.util.UUID.randomUUID().toString())
        tabs = tabs.toMutableList().also { it.add(index + 1, copy) }
        activeTab = index + 1
        restoreTabState()
        persistTabs()
    }

    fun moveTab(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices || from == to) return
        val active = tabs[activeTab].id
        tabs = tabs.toMutableList().also { it.add(to, it.removeAt(from)) }
        activeTab = tabs.indexOfFirst { it.id == active }
        persistTabs()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
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
        closedTabs = (closedTabs + tabs.filterIndexed { i, _ -> i != index }).takeLast(20)
        tabs = listOf(kept)
        activeTab = 0
        restoreTabState()
        persistTabs()
    }

    fun reopenTab() {
        if (closedTabs.isEmpty()) return
        saveTabState()
        val tab = closedTabs.last()
        closedTabs = closedTabs.dropLast(1)
        tabs = tabs.toMutableList().also { it.add(activeTab + 1, tab) }
        activeTab++
        restoreTabState()
        persistTabs()
    }

    fun allowed(file: File): Boolean = runCatching {
        val path = file.canonicalFile.toPath()
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

    fun connectLocation(uri: Uri, persist: Boolean = true) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { documents.connect(uri, persist) } }
                .onSuccess {
                    connectedLocations = documents.locations
                    navigate(it.uri)
                }
                .onFailure { message = it.message ?: "Couldn't connect this folder." }
        }
    }

    fun disconnectLocation(uri: String) {
        documents.disconnect(uri)
        connectedLocations = documents.locations
        if (location == uri || !allowedPath(location)) navigate(workspace.absolutePath)
    }

    fun goTo(path: String) {
        viewModelScope.launch {
            val valid =
                withContext(Dispatchers.IO) {
                    allowedPath(path) &&
                        runCatching { documents.read(path).folder }.getOrDefault(false)
                }
            if (valid) navigate(if (isDocumentPath(path)) path else File(path).canonicalPath)
            else message = "That folder isn't available. Check its path and storage access."
        }
    }

    fun select(entry: Entry, multiple: Boolean = false, range: Boolean = false) {
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

    fun stepSelection(delta: Int, extend: Boolean) {
        val items = visibleEntries
        if (items.isEmpty()) return
        val current = items.indexOfFirst { it.path == focused?.path }
        val next = items[(if (current < 0) 0 else current + delta).coerceIn(0, items.lastIndex)]
        if (extend) {
            if (rangeAnchor == null) rangeAnchor = focused?.path ?: next.path
            val anchor = items.indexOfFirst { it.path == rangeAnchor }.coerceAtLeast(0)
            val end = items.indexOf(next)
            selected =
                items.subList(minOf(anchor, end), maxOf(anchor, end) + 1).map { it.path }.toSet()
        } else {
            selected = setOf(next.path)
            multiSelecting = false
            rangeAnchor = next.path
        }
        leadSelection = next.path
    }

    fun selectAll() {
        selected = visibleEntries.map { it.path }.toSet()
        multiSelecting = true
    }

    fun selectPaths(paths: Set<String>) {
        selected = paths.intersect(visibleEntries.map { it.path }.toSet())
        multiSelecting = selected.isNotEmpty()
        leadSelection = visibleEntries.lastOrNull { it.path in selected }?.path
    }

    fun clearSelection() {
        selected = emptySet()
        multiSelecting = false
        leadSelection = null
        rangeAnchor = null
    }

    fun requestContext(entry: Entry, point: Offset? = null) {
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
        clearSelection()
        contextRequested = FinderContextRequest(point, true)
    }

    fun typeSelect(character: Char) {
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
        query = value
        clearSelection()
        persistTabs()
        refresh(debounce = true)
    }

    fun changeMode(value: ViewMode) {
        mode = value
        if (value != ViewMode.LIST) selected = selected.intersect(entries.map { it.path }.toSet())
        prefs.edit().putString("view", value.name).apply()
    }

    fun changeSort(value: SortBy) {
        if (sort == value) ascending = !ascending
        else {
            sort = value
            ascending = true
        }
        prefs.edit().putString("sort", sort.name).putBoolean("ascending", ascending).apply()
        entries = sorted(entries)
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs.edit().putBoolean("hidden", showHidden).apply()
        refresh()
    }

    fun togglePreview() {
        previewVisible = !previewVisible
        prefs.edit().putBoolean("preview", previewVisible).apply()
    }

    fun toggleFoldersFirst() {
        foldersFirst = !foldersFirst
        prefs.edit().putBoolean("foldersFirst", foldersFirst).apply()
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
            if (location.startsWith("@tag:")) refresh()
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
        if (location == "@tag:$name") navigate(workspace.absolutePath)
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
            searchRules = searchRules.copy(tag = replacements[searchRules.tag] ?: searchRules.tag)
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
        tabs = tabs.map { tab -> tab.copy(history = tab.history.map(::update)) }
        persistTabs()
    }

    private fun copyTags(old: String, new: String) {
        tagStore.relocate(listOf(old to new), copy = true)
        syncTags()
        styleStore.relocate(listOf(old to new), copy = true)
        folderStyles = styleStore.styles
    }

    private fun copyMetadataPaths(pairs: List<Pair<String, String>>) {
        tagStore.relocate(pairs, copy = true)
        syncTags()
        styleStore.relocate(pairs, copy = true)
        folderStyles = styleStore.styles
    }

    fun onResume() {
        updateNotificationState()
        val before = hasAccess
        hasAccess = Environment.isExternalStorageManager()
        volumes =
            getApplication<Application>()
                .getSystemService(StorageManager::class.java)
                .storageVolumes
                .mapNotNull { it.directory }
                .filter { it != deviceRoot && it.canRead() }
        if (!before && hasAccess && location == workspace.absolutePath)
            navigate(deviceRoot.absolutePath)
        else refresh()
    }

    private fun sorted(items: List<Entry>): List<Entry> {
        var comparator =
            when (sort) {
                SortBy.NAME -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                SortBy.MODIFIED -> compareBy { it.modified }
                SortBy.SIZE -> compareBy { it.size }
                SortBy.KIND -> compareBy { it.kind }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        if (!ascending) comparator = comparator.reversed()
        return items.sortedWith(
            if (foldersFirst) compareByDescending<Entry> { it.folder }.then(comparator)
            else comparator,
        )
    }

    fun refresh(debounce: Boolean = false) {
        listingJob?.cancel()
        val path = location
        val search = query.trim()
        val hidden = showHidden
        val smart = smartFolders.firstOrNull { path == "@smart:${it.id}" }
        val rules = effectiveSearchRules
        listingJob = viewModelScope.launch {
            if (debounce) delay(220)
            loading = true
            listingError = null
            truncated = false
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    var traversalLimited = false
                    val info =
                        if (!path.startsWith("@")) {
                            check(allowedPath(path)) {
                                "Storage access is needed to open this folder."
                            }
                            documents.read(path)
                        } else null
                    val base: List<Entry> =
                        when {
                            smart != null -> {
                                check(allowedPath(smart.root)) {
                                    "Reconnect the Smart Folder's location."
                                }
                                scanPath(smart.root, hidden, 50_000).let { (files, limited) ->
                                    traversalLimited = limited
                                    files
                                }
                            }
                            path == "@trash" -> engine.trashItems().map(::Entry)
                            path.startsWith("@tag:") ->
                                tagAssignments
                                    .filterValues { path.removePrefix("@tag:") in it }
                                    .keys
                                    .filter(::allowedPath)
                                    .mapNotNull { runCatching { documents.read(it) }.getOrNull() }
                                    .filter { it.documentUri != null || it.file.exists() }
                            path == "@recent" -> {
                                val roots =
                                    listOf(workspace.absolutePath) +
                                        (if (hasAccess) listOf(deviceRoot.absolutePath)
                                        else emptyList()) +
                                        connectedLocations.map { it.uri }
                                roots
                                    .flatMap { root ->
                                        runCatching {
                                                scanPath(root, hidden, 50_000).let {
                                                    (files, limited) ->
                                                    traversalLimited = traversalLimited || limited
                                                    files
                                                }
                                            }
                                            .getOrDefault(emptyList())
                                    }
                                    .filterNot { it.folder }
                                    .sortedByDescending { it.modified }
                                    .take(200)
                            }
                            else ->
                                if (rules.active)
                                    scanPath(path, hidden, 50_000).let { (files, limited) ->
                                        traversalLimited = limited
                                        files
                                    }
                                else documents.list(path)
                        }
                    val filtered =
                        base
                            .asSequence()
                            .filter { hidden || !it.name.startsWith('.') }
                            .filter { !it.name.startsWith(".droidfinder-") }
                            .filter {
                                rules.matches(
                                    it,
                                    tagAssignments[it.path].orEmpty(),
                                    read = { entry -> documents.input(entry.path) },
                                )
                            }
                            .map { it.copy(style = folderStyles[it.path]) }
                            .take(5001)
                            .toList()
                    Triple(filtered.take(5000), filtered.size > 5000 || traversalLimited, info)
                }
            }
            result
                .onSuccess { (items, limited, info) ->
                    folderInfo = info
                    entries = sorted(items)
                    truncated = limited
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
                    selected = selected.intersect(visibleEntries.map { it.path }.toSet())
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    entries = emptyList()
                    listingError = it.message ?: "Couldn't load this folder."
                }
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
                                    if (busy == null) refresh(debounce = true)
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
                                    viewModelScope.launch {
                                        if (busy == null && query.isEmpty())
                                            refresh(debounce = true)
                                    }
                                }
                            }
                            .also { it.startWatching() }
                }
        }
    }

    private suspend fun scanPath(
        root: String,
        hidden: Boolean,
        limit: Int,
    ): Pair<List<Entry>, Boolean> {
        val found = ArrayList<Entry>()
        val pending = ArrayDeque<String>()
        pending.add(root)
        val visited = mutableSetOf<String>()
        var partial = false
        while (pending.isNotEmpty() && found.size < limit) {
            coroutineContext.ensureActive()
            val folder = pending.removeFirst()
            if (!visited.add(folder)) continue
            val children = runCatching {
                documents.list(folder)
            }
                .getOrElse {
                    if (folder == root) throw it
                    partial = true
                    emptyList()
                }
            children.forEach { entry ->
                val symlink =
                    entry.documentUri == null &&
                        java.nio.file.Files.isSymbolicLink(entry.file.toPath())
                if (
                    found.size < limit &&
                        (hidden || !entry.name.startsWith('.')) &&
                        !entry.name.startsWith(".droidfinder-") &&
                        !symlink
                ) {
                    found.add(entry)
                    if (
                        entry.folder &&
                            !(entry.parentPath == deviceRoot.absolutePath &&
                                entry.name == "Android")
                    )
                        pending.add(entry.path)
                }
            }
        }
        return found to (found.size >= limit || partial)
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
                refresh()
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
        val parent = folderPath ?: item.parentPath ?: return
        operation("Creating shortcut…") {
            val staging =
                File(getApplication<Application>().filesDir, "shortcut-staging").apply { mkdirs() }
            val local = File(staging, validateFilename("${item.name}.finderlink"))
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        FinderShortcut.write(local, item.path)
                        val result =
                            documents.copy(local.absolutePath, parent, false, transferControl)
                        result to documents.createdStep(result)
                    }
                rememberOperation("Make shortcut", listOf(result.second))
                "Shortcut created"
            } finally {
                local.delete()
            }
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
        operation("Importing files…") {
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
                onComplete()
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
        operation("Importing files…") {
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
                onComplete()
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
            TransferSubmission(clipboard, clearClipboard, tabs[activeTab].id, location, selected)
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
        val files = actionEntries.map { it.file }
        if (files.isEmpty()) return
        operation("Deleting permanently…") {
            withContext(Dispatchers.IO) {
                files.forEach {
                    transferControl.check()
                    engine.deleteForever(it)
                }
            }
            history.clear()
            "${files.size} items deleted permanently"
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
        if (isDocumentPath(parent) || focused?.documentUri != null) {
            providerArchive(true, parent)
            return
        }
        val file = focused?.file ?: return
        val folder = File(parent)
        operation("Extracting…") {
            val (result, step) =
                withContext(Dispatchers.IO) {
                    val extracted = engine.extract(file, folder, transferControl)
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
    ) {
        val items = if (extract) listOfNotNull(focused) else actionEntries
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
                            if (extract) engine.extract(local.single(), staging, transferControl)
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
