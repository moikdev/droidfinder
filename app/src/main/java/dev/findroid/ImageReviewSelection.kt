package dev.findroid

/** Session-only decisions. No file, tag, or browser-selection changes happen here. */
@ConsistentCopyVisibility
internal data class ImageReviewSelection private constructor(
    val paths: List<String>,
    val active: String,
    val flagged: Set<String> = emptySet(),
    val flaggedOnly: Boolean = false,
    val reference: String? = null,
) {
    val shown get() = if (flaggedOnly) paths.filter { it in flagged } else paths
    val index get() = shown.indexOf(active)
    val comparison get() = reference?.takeIf { it != active }?.let { listOf(it, active) }

    fun select(path: String) = if (path in shown) copy(active = path) else this
    fun step(delta: Int) = copy(active = shown[(index.toLong() + delta).coerceIn(0, shown.lastIndex.toLong()).toInt()])
    fun filter(flaggedOnly: Boolean): ImageReviewSelection {
        val filtered = flaggedOnly && flagged.isNotEmpty()
        val shown = if (filtered) paths.filter { it in flagged } else paths
        return copy(flaggedOnly = filtered, active = active.takeIf { it in shown } ?: shown.first())
    }

    fun toggleFlag(): ImageReviewSelection {
        val next = if (active in flagged) flagged - active else flagged + active
        val filtered = flaggedOnly && next.isNotEmpty()
        val shown = if (filtered) paths.filter { it in next } else paths
        return copy(flagged = next, flaggedOnly = filtered,
            active = active.takeIf { it in shown } ?: shown[index.coerceAtMost(shown.lastIndex)])
    }

    fun clearFlags() = copy(flagged = emptySet(), flaggedOnly = false)
    fun pinReference() = copy(reference = active)
    fun clearReference() = copy(reference = null)

    companion object {
        const val LIMIT = 200
        fun start(paths: List<String>): ImageReviewSelection {
            val unique = paths.distinct()
            require(unique.size in 1..LIMIT && unique.none { it.isBlank() }) { "Select 1–200 images for a review." }
            return ImageReviewSelection(unique.toList(), unique.first())
        }
    }
}
