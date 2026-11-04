package dev.findroid

import androidx.compose.runtime.*

internal val Entry.reviewableImage: Boolean
    get() = !folder && fileType(extension, mime).group == FileTypeGroup.IMAGE

/** Pane-owned so flags and the frozen review order survive activity recreation. */
internal class ImageReviewController {
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var selection by mutableStateOf<ImageReviewSelection?>(null)
        private set
    val active get() = entries.firstOrNull { it.path == selection?.active }
    val flaggedEntries get() = entries.filter { it.path in selection?.flagged.orEmpty() }

    fun open(entries: List<Entry>) {
        require(entries.isNotEmpty() && entries.all { it.reviewableImage }) { "Select only images for a review." }
        val next = ImageReviewSelection.start(entries.map { it.path })
        this.entries = entries.distinctBy { it.path }.toList()
        selection = next
    }

    fun update(change: (ImageReviewSelection) -> ImageReviewSelection) { selection = selection?.let(change) }
    fun close() { selection = null; entries = emptyList() }
}
