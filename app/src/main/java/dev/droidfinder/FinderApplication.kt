package dev.droidfinder

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Keeps the explorer and its active transfer alive when the Activity is recreated or closed. */
class FinderApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    internal val thumbnails by lazy { ThumbnailRepository(this) }
    internal val model: FinderModel by lazy {
        androidx.lifecycle
            .ViewModelProvider(
                this,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(this),
            )[FinderModel::class.java]
    }
    private val windowStores = mutableMapOf<String, ViewModelStore>()

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
        if (id == "main") return
        windowStores.remove(id)?.clear()
        deleteSharedPreferences("finder-window-$id")
        deleteSharedPreferences("finder-window-$id-primary")
        deleteSharedPreferences("finder-window-$id-secondary")
    }
}
