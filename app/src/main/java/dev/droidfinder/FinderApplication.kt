package dev.droidfinder

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Keeps the explorer and its active transfer alive when the Activity is recreated or closed. */
class FinderApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
