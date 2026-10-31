package dev.droidfinder

/**
 * Root folders are planned completely before copying any member. New subtrees stay atomic units.
 */
data class MergeMember(
    val source: String,
    val parent: String,
    val destination: String? = null,
    val error: String? = null,
    val skipped: Boolean = false,
    val cleanup: SourceCleanup = SourceCleanup.PENDING,
    val recovery: String? = null,
)

enum class SourceCleanup(val title: String) {
    PENDING("Awaiting source cleanup"),
    KEPT("Source kept"),
    RECOVERED("Source in Trash"),
    FAILED("Source cleanup needs review"),
}

data class MergePlan(
    val source: String,
    val destination: String,
    val sourceStamp: String,
    val members: List<MergeMember>,
    val cleanup: SourceCleanup = SourceCleanup.PENDING,
    val recovery: String? = null,
    val error: String? = null,
)

interface TransferStore : java.io.Closeable {
    fun load(): List<TransferJob>

    fun save(jobs: List<TransferJob>)

    fun preserveDamaged(): List<java.io.File>

    override fun close() {}
}
