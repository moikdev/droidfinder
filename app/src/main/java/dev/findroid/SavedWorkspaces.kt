package dev.findroid

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class PaneSnapshot(
    val tabs: List<FinderTab>,
    val active: Int,
    val mode: ViewMode,
    val windowsLayout: Int,
    val sort: SortBy,
    val ascending: Boolean,
    val grouping: GroupBy,
    val hidden: Boolean,
    val foldersFirst: Boolean,
    val preview: Boolean,
    val details: Boolean,
    val iconSize: Int,
    val columnWidth: Int,
    val previewWidth: Int,
    val columns: DetailColumns = DetailColumns(),
    val fit: Boolean = false,
    val defaults: FolderView? = null,
) {
    fun remap(routes: List<Pair<String, String>>) = copy(tabs = tabs.map { it.remap(routes) })
}

data class SavedWorkspace(
    val id: String,
    val name: String,
    val primary: PaneSnapshot,
    val secondary: PaneSnapshot,
    val dual: Boolean,
    val reversed: Boolean,
    val ratio: Float,
    val activePane: String,
) {
    val tabCount
        get() = primary.tabs.size + if (dual) secondary.tabs.size else 0

    fun remap(routes: List<Pair<String, String>>) =
        copy(primary = primary.remap(routes), secondary = secondary.remap(routes))
}

interface WorkspacePersistence {
    val error: String?

    fun load(): List<SavedWorkspace>

    fun write(values: List<SavedWorkspace>)
}

class SavedWorkspaceStore(file: File) : WorkspacePersistence {
    private val atomic = AtomicFile(file)
    override var error: String? = null
        private set

    override fun load(): List<SavedWorkspace> = runCatching {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists())
            return emptyList()
        WorkspaceJson.decode(atomic.openRead().bufferedReader().use { it.readText() })
    }.getOrElse {
        error = it.message ?: "Saved workspaces could not be read. The original file was preserved."
        emptyList()
    }

    override fun write(values: List<SavedWorkspace>) {
        check(error == null) { error!! }
        val stream = atomic.startWrite()
        try {
            stream.write(WorkspaceJson.encode(values).toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}

/** Shared with reviewed setup backups; the on-disk workspace format stays version 1. */
internal object WorkspaceJson {
    fun decode(text: String): List<SavedWorkspace> {
        val root = JSONObject(text)
        check(root.optInt("version") == 1) {
            "Saved workspace format is unavailable. The original file was preserved."
        }
        val a = root.getJSONArray("workspaces")
        return (0 until minOf(a.length(), 24))
            .map { i ->
                val v = a.getJSONObject(i)
                SavedWorkspace(
                    v.getString("id"),
                    v.getString("name"),
                    pane(v.getJSONObject("primary")),
                    pane(v.getJSONObject("secondary")),
                    v.optBoolean("dual"),
                    v.optBoolean("reversed"),
                    v.optDouble("ratio", .5).toFloat().let {
                        if (it.isFinite()) it.coerceIn(.3f, .7f) else .5f
                    },
                    if (v.optString("activePane") == "secondary") "secondary" else "primary",
                )
            }
            .distinctBy { it.id }
    }

    fun encode(values: List<SavedWorkspace>): String {
        return JSONObject()
                .put("version", 1)
                .put(
                    "workspaces",
                    JSONArray(
                        values.map { v ->
                            JSONObject()
                                .put("id", v.id)
                                .put("name", v.name)
                                .put("primary", pane(v.primary))
                                .put("secondary", pane(v.secondary))
                                .put("dual", v.dual)
                                .put("reversed", v.reversed)
                                .put("ratio", v.ratio)
                                .put("activePane", v.activePane)
                        },
                    ),
                )
                .toString()
    }

    private fun pane(p: PaneSnapshot) =
        JSONObject()
            .put("tabs", JSONArray(p.tabs.map(NavigationJson::tab)))
            .put("active", p.active)
            .put("mode", p.mode.name)
            .put("layout", p.windowsLayout)
            .put("sort", p.sort.name)
            .put("ascending", p.ascending)
            .put("group", p.grouping.name)
            .put("hidden", p.hidden)
            .put("foldersFirst", p.foldersFirst)
            .put("preview", p.preview)
            .put("details", p.details)
            .put("icons", p.iconSize)
            .put("column", p.columnWidth)
            .put("inspector", p.previewWidth)
            .put("columns", FolderViewJson.columns(p.columns))
            .put("fit", p.fit)
            .put("defaults", p.defaults?.let(FolderViewJson::view))

    private fun pane(v: JSONObject): PaneSnapshot {
        val tabs = NavigationJson.tabs(v.optJSONArray("tabs"), "@recent")
        return PaneSnapshot(
            tabs,
            v.optInt("active").coerceIn(tabs.indices),
            runCatching { ViewMode.valueOf(v.getString("mode")) }.getOrDefault(ViewMode.LIST),
            v.optInt("layout").coerceIn(0, 9),
            runCatching { SortBy.valueOf(v.getString("sort")) }.getOrDefault(SortBy.NAME),
            v.optBoolean("ascending", true),
            runCatching { GroupBy.valueOf(v.getString("group")) }.getOrDefault(GroupBy.NONE),
            v.optBoolean("hidden"),
            v.optBoolean("foldersFirst", true),
            v.optBoolean("preview", true),
            v.optBoolean("details"),
            v.optInt("icons", 120).coerceIn(90, 200),
            v.optInt("column", 240).coerceIn(150, 500),
            v.optInt("inspector", 280).coerceIn(200, 480),
            FolderViewJson.columns(v.optJSONObject("columns")),
            v.optBoolean("fit"),
            v.optJSONObject("defaults")?.let(FolderViewJson::view),
        )
    }
}
