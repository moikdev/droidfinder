package dev.findroid

import java.io.File
import java.io.IOException

enum class ConflictPolicy(val title: String) {
    ASK("Ask each time"),
    KEEP_BOTH("Keep both"),
    SKIP("Skip existing"),
    REPLACE("Replace with recovery"),
    MERGE("Merge folders; ask for files"),
}

enum class ConflictAction {
    KEEP_BOTH,
    SKIP,
    RENAME,
    REPLACE,
    MERGE,
    CANCEL,
}

data class ConflictDecision(
    val action: ConflictAction,
    val name: String? = null,
    val applyAll: Boolean = false,
)

data class FileConflict(
    val jobId: String,
    val source: Entry,
    val existing: Entry,
    val mergeAllowed: Boolean = true,
) {
    val canMerge
        get() = mergeAllowed && source.folder && existing.folder
}

/** A dependent swap has one preflight: the new file occupies the old file's original path. */
fun replacementStep(
    documents: DocumentAccess,
    old: Entry,
    recovery: File,
    source: Entry,
    published: String,
    move: Boolean,
): FileStep {
    val destinationParent = old.parentPath ?: error("Destination parent unavailable")
    val sourceParent = source.parentPath ?: error("Source parent unavailable")
    var oldRecovery = recovery
    var oldRecoveryStamp = fingerprint(recovery)
    var oldLive: String? = null
    var oldLiveStamp: String? = null
    var newLive = published
    var newLiveStamp = documents.digest(published)
    var newRecovery: File? = null
    var newRecoveryStamp: String? = null
    return FileStep.Callback(
        description = "${source.name}\n${source.path} → $published",
        check = { undo ->
            if (undo) {
                check(documents.digest(newLive) == newLiveStamp) {
                    "The replacement changed; Undo stopped to protect it."
                }
                check(fingerprint(oldRecovery) == oldRecoveryStamp) {
                    "The original recovery copy changed."
                }
                if (move)
                    check(documents.list(sourceParent).none { it.name == source.name }) {
                        "${source.name} already exists at the original location."
                    }
            } else {
                check(documents.digest(oldLive!!) == oldLiveStamp) {
                    "The restored original changed; Redo stopped to protect it."
                }
                if (move)
                    check(documents.digest(newLive) == newLiveStamp) { "The moved file changed." }
                else
                    check(newRecovery != null && fingerprint(newRecovery!!) == newRecoveryStamp) {
                        "The replacement recovery copy changed."
                    }
            }
        },
        perform = { undo ->
            if (undo) {
                val newFrom = newLive
                if (move)
                    newLive =
                        documents.copy(newLive, sourceParent, true, requestedName = source.name)
                else newRecovery = documents.trash(newLive)
                try {
                    oldLive = documents.restore(oldRecovery)
                } catch (error: Exception) {
                    try {
                        newLive =
                            if (move)
                                documents.copy(
                                    newLive,
                                    destinationParent,
                                    true,
                                    requestedName = old.name,
                                )
                            else documents.restore(newRecovery!!)
                        newLiveStamp = documents.digest(newLive)
                    } catch (rollback: Exception) {
                        throw IOException(
                            "Undo stopped. Both recovery locations were kept: $oldRecovery, ${newRecovery ?: newLive}. ${rollback.message}",
                            error,
                        )
                    }
                    throw error
                }
                oldLiveStamp = documents.digest(oldLive!!)
                if (move) newLiveStamp = documents.digest(newLive)
                else newRecoveryStamp = fingerprint(newRecovery!!)
                listOf(
                    newFrom to if (move) newLive else newRecovery!!.path,
                    oldRecovery.path to oldLive!!,
                )
            } else {
                val oldFrom = oldLive!!
                oldRecovery = documents.trash(oldFrom)
                oldRecoveryStamp = fingerprint(oldRecovery)
                val newFrom = if (move) newLive else newRecovery!!.path
                try {
                    newLive =
                        if (move)
                            documents.copy(
                                newLive,
                                destinationParent,
                                true,
                                requestedName = old.name,
                            )
                        else documents.restore(newRecovery!!)
                } catch (error: Exception) {
                    try {
                        oldLive = documents.restore(oldRecovery)
                        oldLiveStamp = documents.digest(oldLive!!)
                    } catch (rollback: Exception) {
                        throw IOException(
                            "Redo stopped. The original is in $oldRecovery. ${rollback.message}",
                            error,
                        )
                    }
                    throw error
                }
                newLiveStamp = documents.digest(newLive)
                listOf(oldFrom to oldRecovery.path, newFrom to newLive)
            }
        },
    )
}
