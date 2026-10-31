package dev.droidfinder

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

enum class FinderDensity(val title: String, val description: String) {
    COMFORTABLE("Comfortable", "Larger touch targets for tablet use"),
    COMPACT("Compact", "More files on screen for mouse and keyboard");

    val touch
        get() = this == COMFORTABLE

    val rowHeight
        get() = if (touch) 48.dp else 36.dp

    val tabHeight
        get() = if (touch) 50.dp else 40.dp

    val targetSize
        get() = if (touch) 48.dp else 32.dp
}

val LocalFinderDensity = staticCompositionLocalOf { FinderDensity.COMFORTABLE }
