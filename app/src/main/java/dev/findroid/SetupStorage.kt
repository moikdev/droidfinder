package dev.findroid

import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidSetupPreferences(private val context: Context) : SetupPreferencePort {
    override fun read(): Map<String, Map<String, SetupPref>> = SetupTransaction.KEYS.mapValues { (store, keys) ->
        context.getSharedPreferences(store, 0).all.filterKeys { it in keys }.mapValues { (_, value) -> when (value) {
            is String -> SetupPref("string", listOf(value))
            is Boolean -> SetupPref("boolean", listOf(value.toString()))
            is Int -> SetupPref("int", listOf(value.toString()))
            is Long -> SetupPref("long", listOf(value.toString()))
            is Set<*> -> SetupPref("set", value.map { it as String }.sorted())
            else -> error("An existing setup preference has an unsupported type. Its data was kept.")
        } }
    }
    @android.annotation.SuppressLint("UseKtx") // Restoration must check synchronous commit success.
    override fun write(value: Map<String, Map<String, SetupPref>>) {
        require(value.keys == SetupTransaction.KEYS.keys)
        value.forEach { (store, values) ->
            require(values.keys.all { it in SetupTransaction.KEYS.getValue(store) })
            val editor = context.getSharedPreferences(store, 0).edit()
            SetupTransaction.KEYS.getValue(store).forEach(editor::remove)
            values.forEach { (key, pref) -> when (pref.type) {
                "string" -> editor.putString(key, pref.values.single())
                "boolean" -> editor.putBoolean(key, pref.values.single().toBooleanStrict())
                "int" -> editor.putInt(key, pref.values.single().toInt())
                "long" -> editor.putLong(key, pref.values.single().toLong())
                "set" -> editor.putStringSet(key, pref.values.toSet())
                else -> error("Unknown setup preference type.")
            } }
            check(editor.commit()) { "Couldn't save restored preferences." }
        }
    }
}

internal class SetupStorage(private val app: Application) {
    val transaction = SetupTransaction(app.filesDir, AndroidSetupPreferences(app))
    val previous get() = File(app.filesDir, "setup-previous.json")

    fun snapshot(model: FinderModel): SetupSnapshot {
        val session = model.session
        check(model.busy == null && !model.workspaceSaving && !session.folderViews.saving) { "Wait for files and setup changes to finish saving, then try again." }
        listOfNotNull(model.sidebarError, model.workspaceError, model.libraryError, session.folderViews.error, (app as? FinderApplication)?.setupRecoveryError).firstOrNull()?.let { error(it) }
        val presets = session.renamePresetStore.load()
        check(session.renamePresetStore.error == null) { session.renamePresetStore.error!! }
        val primary = model.primaryPane
        val current = SavedWorkspace("open-tabs", "Open tabs", primary.snapshotPane(), primary.secondaryPane.snapshotPane(), model.dualPane, model.panesReversed, model.paneRatio, model.windowState.activePane.value)
        val value = SetupSnapshot(model.sidebar, session.tagStore.snapshot(), model.smartFolders, model.savedWorkspaces, current,
            session.folderViews.values, model.folderStyles, presets,
            SetupPreferences(model.density, model.showExtensions, model.darkTheme, model.sidebarWidth, model.sidebarVisible, model.tagsVisible, !model.gestureHintVisible, model.conflictPolicy, ZipLimitsStore(app).read()))
        val rawSearches = app.getSharedPreferences("finder", 0).getString("smartFolders", "[]")!!
        check(SetupJsonTree.parse(rawSearches) == SetupJsonTree.parse(SetupJson.searches(value.searches).toString())) { "Saved Smart Folders could not be fully read. The original setup has been kept." }
        return value.checked()
    }

    /** Use the existing store codecs in isolated staging, never raw imported file names. */
    fun stage(value: SetupSnapshot, before: SetupArchive): Map<String, ByteArray> {
        value.checked()
        val directory = File.createTempFile("setup-stage-", ".tmp", app.cacheDir)
        check(directory.delete() && directory.mkdir())
        try {
            val tags = TagStore(File(directory, "tags.properties"))
            tags.restoreOrganization(tags.snapshot(), value.organization)
            // An empty default library does not cause TagStore to write; materialize it explicitly.
            if (!File(directory, "tags.properties").exists()) {
                java.util.Properties().apply {
                    setProperty("catalog", "2")
                    value.organization.tags.definitions.forEach { setProperty("definition:${it.name}", it.color) }
                }.also { p -> File(directory, "tags.properties").outputStream().use { p.store(it, "Findroid organization library") } }
            }
            SavedWorkspaceStore(File(directory, "workspaces.json")).write(value.workspaces)
            FolderViewStore(File(directory, "folder-views.json")).write(value.folderViews)
            FolderStyleStore(File(directory, "folder-styles.properties")).replace(value.folderStyles)
            RenamePresetStore(File(directory, "rename-presets.properties")).replace(value.presets)
            File(directory, "setup-previous.json").writeText(SetupJson.encode(before))
            return SetupTransaction.FILES.associateWith { File(directory, it).readBytes() }
        } finally { directory.deleteRecursively() }
    }

    fun preferences(value: SetupSnapshot): Map<String, Map<String, SetupPref>> {
        fun str(v: String) = SetupPref("string", listOf(v))
        fun bool(v: Boolean) = SetupPref("boolean", listOf(v.toString()))
        fun int(v: Int) = SetupPref("int", listOf(v.toString()))
        fun long(v: Long) = SetupPref("long", listOf(v.toString()))
        val p = value.preferences
        return mapOf("finder" to mapOf(
            SidebarJson.KEY to str(SidebarJson.encode(value.sidebar)), "favorites" to SetupPref("set", value.sidebar.paths.sorted()),
            "smartFolders" to str(SetupJson.searches(value.searches).toString()), "density" to str(p.density.name),
            "extensions" to bool(p.extensions), "dark" to bool(p.dark), "sidebarWidth" to int(p.sidebarWidth),
            "sidebarVisible" to bool(p.sidebarVisible), "tagsVisible" to bool(p.tagsVisible),
            "gestureHintDismissed" to bool(p.gestureHintDismissed), "conflictPolicy" to str(p.conflictPolicy.name)),
            "zip-limits" to mapOf("entries" to int(p.zip.entries), "total" to long(p.zip.expandedBytes), "member" to long(p.zip.memberBytes), "ratio" to int(p.zip.ratio), "preview" to long(p.zip.previewBytes)))
    }

    fun publish(model: FinderModel, value: SetupSnapshot) {
        val session = model.session
        session.tagStore.acceptSetup(value.organization)
        session.styleStore.acceptSetup(value.folderStyles)
        session.sidebar.value = value.sidebar
        session.smartFolders.value = value.searches
        session.savedWorkspaces.value = value.workspaces
        session.folderViews.acceptSetup(value.folderViews)
        session.folderStyles.value = session.styleStore.styles
        session.history.clear()
        session.undoLabel.value = null; session.redoLabel.value = null
        val p = value.preferences
        session.density.value = p.density; session.showExtensions.value = p.extensions; session.darkTheme.value = p.dark
        session.sidebarWidth.value = p.sidebarWidth; session.sidebarVisible.value = p.sidebarVisible; session.tagsVisible.value = p.tagsVisible
        session.gestureHintVisible.value = !p.gestureHintDismissed; session.conflictPolicy.value = p.conflictPolicy
        session.panes.forEach { it.setupRestored() }
    }
}
