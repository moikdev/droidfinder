package dev.droidfinder

import android.util.AtomicFile
import androidx.compose.runtime.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

data class DetailColumns(
    val date: Boolean = true,
    val size: Boolean = true,
    val kind: Boolean = true,
    val dateWidth: Int = 150,
    val sizeWidth: Int = 75,
    val kindWidth: Int = 110,
    val adaptive: Boolean = true,
) {
    fun normalized() =
        copy(
            dateWidth = dateWidth.coerceIn(100, 320),
            sizeWidth = sizeWidth.coerceIn(60, 180),
            kindWidth = kindWidth.coerceIn(80, 260),
        )
}

data class FolderView(
    val mode: ViewMode = ViewMode.LIST,
    val layout: Int = 0,
    val sort: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val group: GroupBy = GroupBy.NONE,
    val hidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val preview: Boolean = true,
    val details: Boolean = false,
    val icons: Int = 120,
    val column: Int = 240,
    val inspector: Int = 280,
    val fit: Boolean = false,
    val columns: DetailColumns = DetailColumns(),
)

fun folderViewKey(path: String): String? =
    when {
        path.startsWith("@") -> null
        isDocumentPath(path) -> android.net.Uri.parse(path).normalizeScheme().toString()
        else -> runCatching { File(path).canonicalPath }.getOrNull()
    }

object FolderViewJson {
    fun columns(v: DetailColumns): JSONObject =
        JSONObject()
            .put("date", v.date)
            .put("size", v.size)
            .put("kind", v.kind)
            .put("dateWidth", v.dateWidth)
            .put("sizeWidth", v.sizeWidth)
            .put("kindWidth", v.kindWidth)
            .put("adaptive", v.adaptive)

    fun columns(v: JSONObject?): DetailColumns =
        if (v == null) DetailColumns()
        else
            DetailColumns(
                    v.optBoolean("date", true),
                    v.optBoolean("size", true),
                    v.optBoolean("kind", true),
                    v.optInt("dateWidth", 150),
                    v.optInt("sizeWidth", 75),
                    v.optInt("kindWidth", 110),
                    v.optBoolean("adaptive", true),
                )
                .normalized()

    fun view(v: FolderView): JSONObject =
        JSONObject()
            .put("mode", v.mode.name)
            .put("layout", v.layout)
            .put("sort", v.sort.name)
            .put("ascending", v.ascending)
            .put("group", v.group.name)
            .put("hidden", v.hidden)
            .put("foldersFirst", v.foldersFirst)
            .put("preview", v.preview)
            .put("details", v.details)
            .put("icons", v.icons)
            .put("column", v.column)
            .put("inspector", v.inspector)
            .put("fit", v.fit)
            .put("columns", columns(v.columns))

    fun view(v: JSONObject): FolderView =
        FolderView(
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
            v.optBoolean("fit"),
            columns(v.optJSONObject("columns")),
        )
}

interface FolderViewPersistence {
    val error: String?

    fun load(): Map<String, FolderView>

    fun write(values: Map<String, FolderView>)
}

class FolderViewStore(file: File) : FolderViewPersistence {
    private val atomic = AtomicFile(file)
    override var error: String? = null
        private set

    override fun load(): Map<String, FolderView> = runCatching {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists())
            return emptyMap()
        val root =
            JSONObject(
                atomic.openRead().use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 4 * 1024 * 1024) {
                            "Folder settings are too large. The original file was preserved."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                },
            )
        check(root.optInt("version") == 1) {
            "Folder settings format is unavailable. The original file was preserved."
        }
        val rows = root.getJSONArray("folders")
        check(rows.length() <= 2000) { "Too many saved folders. The original file was preserved." }
        buildMap {
            repeat(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                val path = folderViewKey(row.getString("path")) ?: error("Invalid saved folder")
                put(path, FolderViewJson.view(row.getJSONObject("view")))
            }
        }
    }
        .getOrElse {
            error =
                it.message ?: "Folder settings could not be read. The original file was preserved."
            emptyMap()
        }

    override fun write(values: Map<String, FolderView>) {
        check(error == null) { error!! }
        check(values.size <= 2000)
        val bytes =
            JSONObject()
                .put("version", 1)
                .put(
                    "folders",
                    JSONArray(
                        values.map { (path, view) ->
                            JSONObject().put("path", path).put("view", FolderViewJson.view(view))
                        },
                    ),
                )
                .toString()
                .toByteArray()
        check(bytes.size <= 4 * 1024 * 1024) { "Folder settings are too large." }
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}

/** One app-owned writer. Slider changes coalesce; writes cannot overtake one another. */
class FolderViews(private val store: FolderViewPersistence) : AutoCloseable {
    var values by mutableStateOf(store.load())
        private set

    var error by mutableStateOf(store.error)
        private set

    val protectedFile
        get() = store.error != null

    var saving by mutableStateOf(false)
        private set

    private var revision = 0L
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var writer: Job? = null

    var notice by mutableStateOf<String?>(null)
        private set

    private data class Relocation(
        val routes: List<Pair<String, String>>,
        val moved: List<Triple<String, String, FolderView>>,
        val replaced: Map<String, FolderView>,
    )

    private val relocationHistory = java.util.ArrayDeque<Relocation>()

    private fun changed(next: Map<String, FolderView>) {
        if (next == values) return
        values = next
        notice = null
        revision++
        requestSave()
    }

    fun put(key: String, view: FolderView): Boolean {
        if (protectedFile || (key !in values && values.size >= 2000)) return false
        changed(values + (key to view))
        return true
    }

    fun remove(key: String) {
        if (!protectedFile) changed(values - key)
    }

    fun relocate(routes: List<Pair<String, String>>, copy: Boolean = false) {
        if (protectedFile) return
        val paths =
            routes
                .mapNotNull { (a, b) ->
                    val old = folderViewKey(a)
                    val new = folderViewKey(b)
                    if (old == null || new == null) null else old to new
                }
                .distinct()
                .sortedWith(compareBy({ it.first }, { it.second }))
        if (paths.isEmpty()) return
        val previous = relocationHistory.lastOrNull()
        val inverse =
            paths.map { it.second to it.first }.sortedWith(compareBy({ it.first }, { it.second }))
        if (!copy && previous?.routes == inverse) {
            // Undo only the profiles that belonged to the moved items. Restore destination
            // profiles displaced by replacement, while retaining subsequent source-view edits.
            val next = values.toMutableMap()
            val owners = previous.moved.associate { it.second to it.first }
            val restore =
                previous.moved.map { (old, new, before) ->
                    old to if (owners[new] == old) values[new] else before
                }
            previous.moved.forEach { next.remove(it.second) }
            restore.forEach { (path, view) ->
                if (view == null) next.remove(path) else next[path] = view
            }
            next.putAll(previous.replaced)
            relocationHistory.removeLast()
            changed(next)
            return
        }
        val moved = values.mapNotNull { (key, view) ->
            remapPath(key, paths).takeIf { it != key }?.let { Triple(key, it, view) }
        }
        val next = values.toMutableMap()
        if (!copy) {
            val sources = moved.map { it.first }.toSet()
            val replaced =
                moved
                    .mapNotNull { (_, target, _) ->
                        values[target]?.takeIf { target !in sources }?.let { target to it }
                    }
                    .toMap()
            relocationHistory.addLast(Relocation(paths, moved, replaced))
            while (relocationHistory.size > 50) relocationHistory.removeFirst()
            moved.forEach { next.remove(it.first) }
        }
        var skipped = false
        moved.forEach { (_, key, view) ->
            if (!copy || key !in next) {
                if (!copy || next.size < 2000) next[key] = view else skipped = true
            }
        }
        changed(next)
        if (skipped)
            notice =
                "Some copied folders use pane defaults because the 2,000-folder limit was reached."
    }

    fun retry() {
        if (!protectedFile) requestSave()
    }

    private fun requestSave() {
        if (protectedFile) return
        saving = true
        if (writer == null)
            writer = scope.launch {
                for (signal in signals) {
                    delay(200)
                    while (signals.tryReceive().isSuccess) {
                        /* coalesce */
                    }
                    val at = revision
                    val snapshot = values
                    try {
                        withContext(Dispatchers.IO) { store.write(snapshot) }
                        error = null
                        saving = at != revision
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Folder settings could not be saved."
                        saving = false
                    }
                }
            }
        signals.trySend(Unit)
    }

    override fun close() {
        signals.close()
        scope.cancel()
    }
}
