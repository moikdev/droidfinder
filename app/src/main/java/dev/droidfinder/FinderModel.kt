package dev.droidfinder

import android.app.Application
import android.os.Environment
import android.os.FileObserver
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

enum class ViewMode(val title: String) { ICONS("Icons"), LIST("List"), COLUMNS("Columns"), GALLERY("Gallery") }
enum class SortBy(val title: String) { NAME("Name"), MODIFIED("Date modified"), SIZE("Size"), KIND("Kind") }
data class Entry(val file: File, val name: String = file.name, val folder: Boolean = file.isDirectory, val size: Long = file.length(), val modified: Long = file.lastModified()) {
    val path: String get() = file.absolutePath
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val kind: String get() = if (folder) "Folder" else when (extension) {
        "jpg", "jpeg", "png", "webp", "gif", "heic" -> "Image"
        "pdf" -> "PDF document"
        "txt", "md", "json", "kt", "xml", "csv", "log", "html" -> "Text document"
        "mp4", "mkv", "webm", "mov" -> "Video"
        "mp3", "wav", "m4a", "flac", "ogg" -> "Audio"
        "zip" -> "ZIP archive"
        "apk" -> "Android app"
        "doc", "docx" -> "Word document"
        "xls", "xlsx" -> "Spreadsheet"
        else -> if (extension.isBlank()) "Document" else "${extension.uppercase()} file"
    }
    val mime: String get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}
data class FinderTab(val history: List<String>, val index: Int = history.lastIndex) { val location get() = history[index] }
data class ClipboardFiles(val files: List<File>, val cut: Boolean)
val tagNames = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple")

class FinderModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("finder", 0)
    val workspace = File(app.filesDir, "Workspace").apply { mkdirs() }
    val deviceRoot: File = Environment.getExternalStorageDirectory()
    val engine = FileEngine(app.filesDir)
    var hasAccess by mutableStateOf(Environment.isExternalStorageManager()); private set
    var tabs by mutableStateOf(loadTabs()); private set
    var activeTab by mutableStateOf(prefs.getInt("activeTab", 0).coerceIn(0, tabs.lastIndex)); private set
    val location: String get() = tabs[activeTab].location
    val directory: File? get() = location.takeUnless { it.startsWith("@") }?.let(::File)
    val isTrash: Boolean get() = location == "@trash"
    var entries by mutableStateOf<List<Entry>>(emptyList()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var multiSelecting by mutableStateOf(false); private set
    private var leadSelection by mutableStateOf<String?>(null)
    var query by mutableStateOf(""); private set
    var mode by mutableStateOf(runCatching { ViewMode.valueOf(prefs.getString("view", "LIST")!!) }.getOrDefault(ViewMode.LIST)); private set
    var sort by mutableStateOf(runCatching { SortBy.valueOf(prefs.getString("sort", "NAME")!!) }.getOrDefault(SortBy.NAME)); private set
    var ascending by mutableStateOf(prefs.getBoolean("ascending", true)); private set
    var showHidden by mutableStateOf(prefs.getBoolean("hidden", false)); private set
    var previewVisible by mutableStateOf(prefs.getBoolean("preview", true)); private set
    var foldersFirst by mutableStateOf(prefs.getBoolean("foldersFirst", true)); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var listingError by mutableStateOf<String?>(null); private set
    var truncated by mutableStateOf(false); private set
    var clipboard by mutableStateOf<ClipboardFiles?>(null); private set
    private var undoTrashItems by mutableStateOf<List<File>>(emptyList())
    val canUndoTrash get() = undoTrashItems.isNotEmpty()
    var favorites by mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.toSet()); private set
    var tags by mutableStateOf(loadTags()); private set
    var volumes by mutableStateOf<List<File>>(emptyList()); private set
    private var listingJob: Job? = null
    private var observer: FileObserver? = null
    val selectedEntries get() = entries.filter { it.path in selected }
    val focused get() = entries.firstOrNull { it.path == leadSelection && it.path in selected } ?: selectedEntries.lastOrNull()

    init { refresh() }
    private fun loadTabs(): List<FinderTab> = runCatching {
        val array = JSONArray(prefs.getString("tabs", "[]"))
        (0 until array.length()).map { FinderTab(listOf(array.getString(it))) }.ifEmpty { listOf(FinderTab(listOf(workspace.absolutePath))) }
    }.getOrElse { listOf(FinderTab(listOf(workspace.absolutePath))) }
    private fun loadTags(): Map<String, String> = prefs.all.filterKeys { it.startsWith("tag:") }.mapKeys { it.key.removePrefix("tag:") }.mapValues { it.value.toString() }
    private fun persistTabs() { prefs.edit().putString("tabs", JSONArray(tabs.map { it.location }).toString()).putInt("activeTab", activeTab).apply() }
    fun title(path: String = location): String = when {
        path == "@trash" -> "Trash"
        path == "@recent" -> "Recents"
        path.startsWith("@tag:") -> path.removePrefix("@tag:")
        path == deviceRoot.absolutePath -> "Tablet"
        path == workspace.absolutePath -> "Workspace"
        else -> File(path).name.ifBlank { "Storage" }
    }

    fun navigate(path: String) {
        if (busy != null) return
        if (path == location) { updateQuery(""); return }
        val current = tabs[activeTab]
        tabs = tabs.toMutableList().also { it[activeTab] = FinderTab(current.history.take(current.index + 1) + path) }
        query = ""; clearSelection(); persistTabs(); refresh()
    }
    fun back(): Boolean {
        if (busy != null) return false
        val tab = tabs[activeTab]
        if (tab.index <= 0) return false
        tabs = tabs.toMutableList().also { it[activeTab] = tab.copy(index = tab.index - 1) }
        query = ""; clearSelection(); persistTabs(); refresh(); return true
    }
    fun forward() { val tab = tabs[activeTab]; if (tab.index < tab.history.lastIndex && busy == null) { tabs = tabs.toMutableList().also { it[activeTab] = tab.copy(index = tab.index + 1) }; query = ""; clearSelection(); persistTabs(); refresh() } }
    fun up() { directory?.parentFile?.takeIf { allowed(it) }?.let { navigate(it.absolutePath) } }
    fun newTab(path: String = location) { if (busy != null) return; tabs = tabs + FinderTab(listOf(path)); activeTab = tabs.lastIndex; query = ""; clearSelection(); persistTabs(); refresh() }
    fun switchTab(index: Int) { if (busy != null) return; activeTab = index; query = ""; clearSelection(); persistTabs(); refresh() }
    fun closeTab(index: Int) { if (tabs.size == 1 || busy != null) return; tabs = tabs.filterIndexed { i, _ -> i != index }; activeTab = (if (index < activeTab) activeTab - 1 else activeTab).coerceAtMost(tabs.lastIndex); query = ""; clearSelection(); persistTabs(); refresh() }
    fun allowed(file: File): Boolean = runCatching { val path = file.canonicalFile.toPath(); path.startsWith(workspace.canonicalFile.toPath()) || (hasAccess && (path.startsWith(deviceRoot.canonicalFile.toPath()) || volumes.any { path.startsWith(it.canonicalFile.toPath()) })) }.getOrDefault(false)
    fun goTo(path: String) { val file = File(path); if (allowed(file) && file.isDirectory) navigate(file.canonicalPath) else message = "That folder isn't available. Check its path and storage access." }
    fun select(entry: Entry, multiple: Boolean = false, range: Boolean = false) {
        val anchor = entries.indexOfFirst { it.path == leadSelection }
        val end = entries.indexOfFirst { it.path == entry.path }
        if (range && anchor >= 0 && end >= 0) { selected = selected + entries.subList(minOf(anchor, end), maxOf(anchor, end) + 1).map { it.path }; return }
        if (multiple) multiSelecting = true
        selected = if (multiple || multiSelecting) selected.toMutableSet().also { if (!it.add(entry.path)) it.remove(entry.path) } else setOf(entry.path)
        leadSelection = entry.path
        if (selected.isEmpty()) multiSelecting = false
    }
    fun stepSelection(delta: Int, extend: Boolean) {
        if (entries.isEmpty()) return
        val current = entries.indexOfFirst { it.path == focused?.path }
        val next = entries[(if (current < 0) 0 else current + delta).coerceIn(0, entries.lastIndex)]
        if (extend) selected = selected + next.path else { selected = setOf(next.path); multiSelecting = false }
        leadSelection = next.path
    }
    fun selectAll() { selected = entries.map { it.path }.toSet(); multiSelecting = true }
    fun clearSelection() { selected = emptySet(); multiSelecting = false; leadSelection = null }
    fun updateQuery(value: String) { query = value; clearSelection(); refresh(debounce = true) }
    fun changeMode(value: ViewMode) { mode = value; prefs.edit().putString("view", value.name).apply() }
    fun changeSort(value: SortBy) { if (sort == value) ascending = !ascending else { sort = value; ascending = true }; prefs.edit().putString("sort", sort.name).putBoolean("ascending", ascending).apply(); entries = sorted(entries) }
    fun toggleHidden() { showHidden = !showHidden; prefs.edit().putBoolean("hidden", showHidden).apply(); refresh() }
    fun togglePreview() { previewVisible = !previewVisible; prefs.edit().putBoolean("preview", previewVisible).apply() }
    fun toggleFoldersFirst() { foldersFirst = !foldersFirst; prefs.edit().putBoolean("foldersFirst", foldersFirst).apply(); entries = sorted(entries) }
    fun favorite(file: File) { favorites = favorites.toMutableSet().also { if (!it.add(file.absolutePath)) it.remove(file.absolutePath) }; prefs.edit().putStringSet("favorites", favorites).apply() }
    fun tag(color: String?) { val edit = prefs.edit(); val update = tags.toMutableMap(); selected.forEach { if (color == null) { update.remove(it); edit.remove("tag:$it") } else { update[it] = color; edit.putString("tag:$it", color) } }; edit.apply(); tags = update; if (location.startsWith("@tag:")) refresh() }
    private fun relocateMetadata(old: String, new: String) {
        val edit = prefs.edit(); val update = tags.toMutableMap()
        tags.filterKeys { it == old || it.startsWith("$old/") }.forEach { (path, color) -> val newPath = new + path.removePrefix(old); update.remove(path); update[newPath] = color; edit.remove("tag:$path").putString("tag:$newPath", color) }
        tags = update
        favorites = favorites.map { if (it == old || it.startsWith("$old/")) new + it.removePrefix(old) else it }.toSet()
        edit.putStringSet("favorites", favorites).apply()
        tabs = tabs.map { tab -> tab.copy(history = tab.history.map { if (it == old || it.startsWith("$old/")) new + it.removePrefix(old) else it }) }
        persistTabs()
    }
    private fun copyTags(old: String, new: String) {
        val update = tags.toMutableMap(); val edit = prefs.edit()
        tags.filterKeys { it == old || it.startsWith("$old/") }.forEach { (path, color) -> val copy = new + path.removePrefix(old); update[copy] = color; edit.putString("tag:$copy", color) }
        tags = update; edit.apply()
    }

    fun onResume() {
        val before = hasAccess; hasAccess = Environment.isExternalStorageManager()
        volumes = getApplication<Application>().getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }.filter { it != deviceRoot && it.canRead() }
        if (!before && hasAccess && location == workspace.absolutePath) navigate(deviceRoot.absolutePath) else refresh()
    }
    private fun sorted(items: List<Entry>): List<Entry> {
        var comparator = when (sort) {
            SortBy.NAME -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.MODIFIED -> compareBy { it.modified }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.KIND -> compareBy { it.kind }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        if (!ascending) comparator = comparator.reversed()
        return items.sortedWith(if (foldersFirst) compareByDescending<Entry> { it.folder }.then(comparator) else comparator)
    }

    fun refresh(debounce: Boolean = false) {
        listingJob?.cancel()
        val path = location; val search = query.trim(); val hidden = showHidden
        listingJob = viewModelScope.launch {
            if (debounce) delay(220)
            loading = true; listingError = null; truncated = false
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    var traversalLimited = false
                    val base: List<File> = when {
                        path == "@trash" -> engine.trashItems()
                        path.startsWith("@tag:") -> tags.filterValues { it == path.removePrefix("@tag:") }.keys.map(::File).filter { it.exists() && allowed(it) }
                        path == "@recent" -> scan(if (hasAccess) deviceRoot else workspace, hidden, 50_000).let { (files, limited) -> traversalLimited = limited; files }.filter { it.isFile }.sortedByDescending { it.lastModified() }.take(200)
                        else -> {
                            val folder = File(path)
                            check(allowed(folder)) { "Storage access is needed to open this folder." }
                            if (search.isNotEmpty()) scan(folder, hidden, 50_000).let { (files, limited) -> traversalLimited = limited; files } else folder.listFiles()?.toList() ?: error("Couldn't read this folder. It may have been removed or access was revoked.")
                        }
                    }
                    val filtered = base.asSequence().filter { hidden || !it.name.startsWith('.') }.filter { !it.name.startsWith(".droidfinder-") }.filter { search.isEmpty() || it.name.contains(search, ignoreCase = true) }.take(5001).map(::Entry).toList()
                    Pair(filtered.take(5000), filtered.size > 5000 || traversalLimited)
                }
            }
            result.onSuccess { (items, limited) -> entries = sorted(items); truncated = limited; selected = selected.intersect(entries.map { it.path }.toSet()) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; entries = emptyList(); listingError = it.message ?: "Couldn't load this folder." }
            loading = false
            observer?.stopWatching()
            directory?.takeIf { it.isDirectory && allowed(it) }?.let { folder ->
                observer = object : FileObserver(folder, CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) { viewModelScope.launch { if (busy == null && query.isEmpty()) refresh(debounce = true) } }
                }.also { it.startWatching() }
            }
        }
    }

    private suspend fun scan(root: File, hidden: Boolean, limit: Int): Pair<List<File>, Boolean> {
        val found = ArrayList<File>()
        val pending = ArrayDeque<File>(); pending.add(root)
        while (pending.isNotEmpty() && found.size < limit) {
            coroutineContext.ensureActive()
            val folder = pending.removeFirst()
            folder.listFiles()?.forEach { file ->
                if (found.size < limit && (hidden || !file.name.startsWith('.')) && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.name != ".DroidFinderTrash") {
                    found.add(file)
                    if (file.isDirectory && !(file.parentFile == deviceRoot && file.name == "Android")) pending.add(file)
                }
            }
        }
        return found to (found.size >= limit)
    }

    private fun operation(label: String, work: suspend () -> String) {
        if (busy != null) return
        busy = label
        viewModelScope.launch {
            try { message = work() } catch (error: Exception) { message = error.message ?: "Couldn't complete the operation." }
            finally { busy = null; clearSelection(); refresh() }
        }
    }
    fun createFolder(name: String) { val folder = directory ?: return; operation("Creating folder…") { withContext(Dispatchers.IO) { engine.createFolder(folder, name) }; "Folder created" } }
    fun rename(name: String) { val item = focused ?: return; operation("Renaming…") { val result = withContext(Dispatchers.IO) { engine.rename(item.file, name) }; relocateMetadata(item.path, result.absolutePath); "Renamed to ${result.name}" } }
    fun copySelection(cut: Boolean) { if (selectedEntries.isEmpty()) return; clipboard = ClipboardFiles(selectedEntries.map { it.file }, cut); message = "${selected.size} item${if (selected.size == 1) "" else "s"} ready to ${if (cut) "move" else "copy"}" }
    fun paste() {
        val clip = clipboard ?: return; val parent = directory ?: return
        operation(if (clip.cut) "Moving items…" else "Copying items…") {
            var completed = 0
            for (file in clip.files) {
                try { val target = withContext(Dispatchers.IO) { engine.copy(file, parent, clip.cut) }; if (clip.cut) relocateMetadata(file.absolutePath, target.absolutePath) else copyTags(file.absolutePath, target.absolutePath); completed++ }
                catch (error: Exception) { if (clip.cut) clipboard = clip.copy(files = clip.files.drop(completed)); error("$completed of ${clip.files.size} completed. ${error.message}") }
            }
            if (clip.cut) clipboard = null
            "$completed item${if (completed == 1) "" else "s"} ${if (clip.cut) "moved" else "copied"}"
        }
    }
    private fun batch(label: String, action: (File) -> Unit) {
        val files = selectedEntries.map { it.file }; if (files.isEmpty()) return
        operation(label) { withContext(Dispatchers.IO) { var completed = 0; for (file in files) { try { action(file); completed++ } catch (error: Exception) { error("$completed of ${files.size} completed. ${error.message}") } } }; "${files.size} item${if (files.size == 1) "" else "s"} processed" }
    }
    fun duplicate() {
        val files = selectedEntries.map { it.file }; if (files.isEmpty()) return
        operation("Duplicating…") { for (file in files) { val target = withContext(Dispatchers.IO) { engine.copy(file, file.parentFile!!) }; copyTags(file.absolutePath, target.absolutePath) }; "${files.size} items duplicated" }
    }
    fun trash() {
        if (isTrash) return
        val files = selectedEntries.map { it.file }; if (files.isEmpty()) return
        operation("Moving to Trash…") {
            undoTrashItems = emptyList()
            for (file in files) { val item = withContext(Dispatchers.IO) { engine.trash(file) }; undoTrashItems = undoTrashItems + item }
            "${files.size} item${if (files.size == 1) "" else "s"} moved to Trash"
        }
    }
    private suspend fun restoreFiles(files: List<File>): String {
        for (file in files) {
            val original = engine.originalPath(file)
            val restored = withContext(Dispatchers.IO) { engine.restore(file) }
            if (original != null && original != restored.absolutePath) relocateMetadata(original, restored.absolutePath)
            undoTrashItems = undoTrashItems - file
        }
        return "${files.size} item${if (files.size == 1) "" else "s"} restored"
    }
    fun restore() { val files = selectedEntries.map { it.file }; if (files.isEmpty()) return; operation("Restoring…") { restoreFiles(files) } }
    fun undoTrash() { val files = undoTrashItems.filter { it.exists() }; if (files.isEmpty()) { undoTrashItems = emptyList(); return }; operation("Restoring…") { restoreFiles(files) } }
    fun deleteForever() = batch("Deleting permanently…") { engine.deleteForever(it) }
    fun emptyTrash() { operation("Emptying Trash…") { withContext(Dispatchers.IO) { engine.trashItems().forEach { engine.deleteForever(it) } }; undoTrashItems = emptyList(); "Trash emptied" } }
    fun compress() { val files = selectedEntries.map { it.file }; val folder = directory ?: return; if (files.isEmpty()) return; operation("Compressing…") { val archive = withContext(Dispatchers.IO) { engine.compress(files, folder) }; "Created ${archive.name}" } }
    fun extract() { val file = focused?.file ?: return; val folder = directory ?: return; operation("Extracting…") { val result = withContext(Dispatchers.IO) { engine.extract(file, folder) }; "Extracted to ${result.name}" } }
    override fun onCleared() { observer?.stopWatching(); super.onCleared() }
}

fun formatSize(bytes: Long): String = when { bytes < 1024 -> "$bytes bytes"; bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0); bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024)); else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) }
fun formatDate(time: Long): String = if (time == 0L) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
