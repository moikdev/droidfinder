package dev.findroid

import android.app.Application
import android.os.Environment
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray

/** App-owned shared operation state. Pane models own navigation and independent listing jobs. */
class FinderSession(
    private val app: Application,
    val workspaceStore: WorkspacePersistence =
        SavedWorkspaceStore(File(app.filesDir, "workspaces.json")),
    folderViewPersistence: FolderViewPersistence =
        FolderViewStore(File(app.filesDir, "folder-views.json")),
) {
    private val prefs = app.getSharedPreferences("finder", 0)
    internal val panes = mutableListOf<FinderModel>()
    val workspace = File(app.filesDir, "Workspace").apply { mkdirs() }

    val deviceRoot: File = Environment.getExternalStorageDirectory()

    val engine = (app as? FinderApplication)?.localEngine ?: FileEngine(app.filesDir)
    internal val renamePresetStore =
        RenamePresetStore(File(app.filesDir, "rename-presets.properties"))

    val documents = DocumentAccess(app, engine)

    internal val storage = StorageMonitor(app, workspace, deviceRoot, documents)

    internal val workspaceInPicker =
        mutableStateOf((app as? FinderApplication)?.workspaceDocuments?.enabled ?: true)

    val connectedLocations = mutableStateOf(documents.locations)

    val notificationsEnabled =
        mutableStateOf(
            androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled(),
        )

    val hasAccess = mutableStateOf(Environment.isExternalStorageManager())

    val smartFolders = mutableStateOf(loadSmartFolders())

    val density =
        mutableStateOf(
            runCatching { FinderDensity.valueOf(prefs.getString("density", "COMFORTABLE")!!) }
                .getOrDefault(FinderDensity.COMFORTABLE),
        )

    val showExtensions = mutableStateOf(prefs.getBoolean("extensions", true))

    val darkTheme = mutableStateOf(prefs.getBoolean("dark", false))

    val sidebarWidth = mutableStateOf(prefs.getInt("sidebarWidth", 206))

    val sidebarVisible = mutableStateOf(prefs.getBoolean("sidebarVisible", true))

    val gestureHintVisible = mutableStateOf(!prefs.getBoolean("gestureHintDismissed", false))

    val tagsVisible = mutableStateOf(prefs.getBoolean("tagsVisible", true))

    val savedWorkspaces = mutableStateOf(workspaceStore.load())
    val workspaceError = mutableStateOf(workspaceStore.error)
    val folderViews = FolderViews(folderViewPersistence)
    val workspaceMutex = Mutex()
    val workspaceWrites = androidx.compose.runtime.mutableIntStateOf(0)

    val styleStore = FolderStyleStore(File(app.filesDir, "folder-styles.properties"))

    val folderStyles = mutableStateOf(styleStore.styles)

    val busy = mutableStateOf<String?>(null)

    val transferJournal: TransferStore = SqliteTransferStore(app)

    val journalMutex = Mutex()

    val journalError = mutableStateOf<String?>(null)

    val damagedTransferJournal = mutableStateOf(false)

    val transferJobs =
        mutableStateOf(
            runCatching { transferJournal.load().map { it.afterRestart() } }
                .getOrElse {
                    journalError.value = it.message
                    damagedTransferJournal.value = true
                    emptyList()
                },
        )

    val activeTransferId = mutableStateOf<String?>(null)

    val transferSubmissions = mutableMapOf<String, TransferSubmission>()

    val fileConflict = mutableStateOf<FileConflict?>(null)

    @Volatile var conflictAnswer: CompletableDeferred<ConflictDecision>? = null

    val conflictPolicy =
        mutableStateOf(
            runCatching { ConflictPolicy.valueOf(prefs.getString("conflictPolicy", "ASK")!!) }
                .getOrDefault(ConflictPolicy.ASK),
        )

    val message = mutableStateOf<String?>(engine.trashIndexNotice)

    val interruptedTransfer = mutableStateOf<String?>(null)

    val clipboard = mutableStateOf<ClipboardFiles?>(null)

    val history = FileHistory(engine)

    val undoLabel = mutableStateOf<String?>(null)

    val redoLabel = mutableStateOf<String?>(null)

    val transferProgress = mutableStateOf(TransferProgress())

    var transferControl = TransferControl()

    private val sidebarLoad = runCatching {
        prefs.getString(SidebarJson.KEY, null)?.let(SidebarJson::decode)
            ?: SidebarLayout.migrate(prefs.getStringSet("favorites", emptySet()).orEmpty())
    }
    val sidebar = mutableStateOf(sidebarLoad.getOrDefault(SidebarLayout()))
    val sidebarError = mutableStateOf(sidebarLoad.exceptionOrNull()?.message)

    internal fun saveSidebar(value: SidebarLayout): Boolean {
        check(sidebarError.value == null) { "Sidebar setup could not be read. Its original data has been kept." }
        val encoded = SidebarJson.encode(value)
        check(prefs.edit().putString(SidebarJson.KEY, encoded)
            .putStringSet("favorites", value.paths).commit()) { "Couldn't save sidebar changes." }
        sidebar.value = value
        return true
    }

    val tagStore = TagStore(File(app.filesDir, "tags.properties")).also { it.migrate(loadTags()) }

    val tagAssignments = mutableStateOf(tagStore.assignments)

    val tagDefinitions = mutableStateOf(tagStore.definitions)

    val collections = mutableStateOf(tagStore.collections)
    val libraryError = mutableStateOf(tagStore.error)
    val libraryNotice = mutableStateOf<String?>(null)

    val tags = mutableStateOf(tagAssignments.value.mapValues { it.value.firstOrNull().orEmpty() })

    val volumes = mutableStateOf<List<File>>(emptyList())

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
                NavigationJson.rules(r),
            )
        }
    }
        .getOrDefault(emptyList())
}

data class TransferSubmission(
    val pane: FinderModel,
    val clipboard: ClipboardFiles?,
    val clearClipboard: Boolean,
    val tab: String,
    val location: String,
    val selection: Set<String>,
)

/** Navigation belongs to a window; storage, mutations and recovery belong to FinderSession. */
class FinderWindowState(app: Application, val id: String = "main") {
    internal val fileClicks = FileClickSequence()
    internal var nativeMousePressTime = Long.MIN_VALUE
    // The icon owns a held pointer; its parent row must not toggle that selection.
    internal var iconDragGesture: String? = null
    internal val prefs =
        app.getSharedPreferences(if (id == "main") "finder" else "finder-window-$id", 0)
    internal val panes = mutableListOf<FinderModel>()
    internal val secondaryStore = ViewModelStore()
    val shareRequested = mutableStateOf<List<Entry>?>(null)
    // Retained by the window across Activity recreation, without serializing file catalogs.
    internal val peekEntry = mutableStateOf<Entry?>(null)
    internal val peekCandidates = mutableStateOf<List<Entry>>(emptyList())
    internal val peekMultipleSelection = mutableStateOf(false)
    val dualPane = mutableStateOf(prefs.getBoolean("dualPane", false))
    val activePane =
        mutableStateOf(
            prefs.getString("activePane", "primary").takeIf { it == "secondary" } ?: "primary",
        )
    val paneRatio =
        mutableStateOf(
            prefs.getFloat("paneRatio", .5f).takeIf { it.isFinite() }?.coerceIn(.3f, .7f) ?: .5f,
        )
    val panesReversed = mutableStateOf(prefs.getBoolean("panesReversed", false))
}
