package dev.findroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Keep cache-backed readers safe across panes, archive members and independent windows. */
@Composable
internal fun ProtectPreviewCache(entry: Entry) {
    val root = LocalContext.current.cacheDir.toPath().toAbsolutePath().normalize()
    if (entry.file.toPath().toAbsolutePath().normalize().startsWith(root)) {
        DisposableEffect(entry.file.path) {
            val lease = CacheLeases.pin(entry.file)
            onDispose { lease.close() }
        }
    }
}
