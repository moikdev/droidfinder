package dev.findroid

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/** Keeps the explorer and its active transfer alive when the Activity is recreated or closed. */
class FinderApplication : Application(), ViewModelStoreOwner {
    internal var setupRecoveryError by mutableStateOf<String?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        recoverSetup()
    }

    internal fun recoverSetup() {
        setupRecoveryError = runCatching { SetupStorage(this).transaction.recover() }.exceptionOrNull()?.let {
            "An interrupted setup restore needs recovery before browsing can continue. The recovery copy has been kept. ${it.message}"
        }
    }

    internal val updates by lazy { AppUpdateController(this) }
    internal val searchIndex by lazy { LocalSearchIndex(this) }
    internal val localEngine by lazy { FileEngine(filesDir) }
    internal val workspaceDocuments by lazy { WorkspaceDocuments(this) }
    private val mainModel = lazy {
        androidx.lifecycle
            .ViewModelProvider(
                this,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(this),
            )[FinderModel::class.java]
    }

    internal fun workspaceChanged(routes: List<Pair<String, String>> = emptyList()) {
        searchIndex.noteChanged(listOf(java.io.File(filesDir, "Workspace").absolutePath))
        if (!mainModel.isInitialized()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            model.externalWorkspaceChanged(routes)
        }
    }

    override val viewModelStore = ViewModelStore()
    internal val thumbnails by lazy { ThumbnailRepository(this) }
    internal val model: FinderModel
        get() = mainModel.value

    private val windowStores = mutableMapOf<String, ViewModelStore>()
    private val windowOwners = mutableMapOf<String, MutableSet<Any>>()

    internal fun retainWindow(id: String, owner: Any) {
        if (id != "main") windowOwners.getOrPut(id) { mutableSetOf() }.add(owner)
    }

    internal fun releaseWindow(id: String, owner: Any, finishing: Boolean) {
        if (id == "main") return
        val owners = windowOwners[id] ?: return
        if (!owners.remove(owner)) return
        if (owners.isEmpty()) {
            windowOwners.remove(id)
            // A configuration change retains the store for its replacement Activity.
            if (finishing) releaseWindow(id)
        }
    }

    internal fun windowModel(id: String, initialPath: String?): FinderModel {
        if (id == "main") return model
        val store = windowStores.getOrPut(id) { ViewModelStore() }
        return androidx.lifecycle
            .ViewModelProvider(
                store,
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(
                        modelClass: Class<T>,
                    ): T {
                        val state = FinderWindowState(this@FinderApplication, id)
                        val saved =
                            getSharedPreferences("finder-window-$id-primary", 0).contains("tabs")
                        val pane =
                            FinderModel(this@FinderApplication, model.session, "primary", state)
                        if (!saved && initialPath != null) {
                            val virtual =
                                initialPath == "@recent" ||
                                    initialPath == "@trash" ||
                                    pane.tagDefinitions.any { initialPath == "@tag:${it.name}" } ||
                                    pane.collections.any {
                                        initialPath == "@collection:${it.id}"
                                    } ||
                                    pane.smartFolders.any { initialPath == "@smart:${it.id}" }
                            if (virtual) pane.navigate(initialPath)
                            else pane.goTo(initialPath, preservePath = true)
                        }
                        @Suppress("UNCHECKED_CAST")
                        return pane as T
                    }
                },
            )[FinderModel::class.java]
    }

    internal fun releaseWindow(id: String) {
        if (id == "main" || windowOwners[id]?.isNotEmpty() == true) return
        windowStores.remove(id)?.clear()
        deleteSharedPreferences("finder-window-$id")
        deleteSharedPreferences("finder-window-$id-primary")
        deleteSharedPreferences("finder-window-$id-secondary")
    }
}
