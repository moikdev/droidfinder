package dev.droidfinder

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Keeps the explorer and its active transfer alive when the Activity is recreated or closed. */
class FinderApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    internal val model: FinderModel by lazy {
        androidx.lifecycle
            .ViewModelProvider(
                this,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(this),
            )[FinderModel::class.java]
    }
}
