package dev.findroid

import android.view.KeyEvent
import androidx.compose.runtime.*

/**
 * Child previews can own text editing and document shortcuts without leaking keys to the browser.
 */
internal class PeekKeys {
    var editing by mutableStateOf(false)
    var handle: ((KeyEvent) -> Boolean)? = null
}

internal val LocalPeekKeys = staticCompositionLocalOf<PeekKeys?> { null }
