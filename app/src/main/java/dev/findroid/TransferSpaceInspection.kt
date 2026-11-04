package dev.findroid

import java.util.concurrent.CancellationException

internal data class TransferSpaceRequest(
    val paths: List<String>,
    val destination: String,
    val move: Boolean,
    val policy: ConflictPolicy,
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    init { require(paths.isNotEmpty() && paths.size <= 10_000 && paths.distinct().size == paths.size) }
}

internal data class TransferSpaceInspection(
    val request: TransferSpaceRequest,
    val plan: TransferSpacePlan,
    val matchingDestinationItems: Int,
    val conflictInventoryComplete: Boolean,
    val recoveryExplanation: String,
    val checkedAtMillis: Long,
)

/** Metadata only. This review grants no authority to replace, remove, resume or publish a file. */
internal fun inspectTransferSpace(
    request: TransferSpaceRequest,
    read: (String) -> Entry,
    list: (String) -> List<Entry>,
    estimate: (List<String>) -> TransferEstimate,
    location: (String) -> TransferSpaceLocation,
    recoveryPath: String,
    control: TransferControl,
    clock: () -> Long = ::transferClockMillis,
): TransferSpaceInspection {
    control.check()
    val destination = read(request.destination)
    check(destination.folder && destination.writable) { "Choose an available, writable destination folder." }
    val contents = estimate(request.paths)
    control.check()
    val destinationSpace = location(request.destination)
    control.check()
    val canReplace = request.policy !in setOf(ConflictPolicy.KEEP_BOTH, ConflictPolicy.SKIP)
    var complete = true
    var duplicates = false
    var matching = emptyList<String>()
    if (canReplace) {
        val sourceNames = mutableMapOf<String, MutableSet<String>>()
        for (path in request.paths) {
            control.check()
            try {
                val name = read(path).name
                val sameName = sourceNames.getOrPut(name) { mutableSetOf() }
                if (sameName.isNotEmpty()) duplicates = true
                sameName.add(path)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                complete = false
            }
        }
        try {
            val children = list(request.destination)
            // A missing source name can conflict with any destination item.
            matching = children.filter { !complete || sourceNames[it.name].orEmpty().any { source -> source != it.path } }
                .map { it.path }.distinct()
            if (children.size > 100_000 || matching.size > 10_000) {
                matching = matching.take(10_000)
                complete = false
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            complete = false
        }
    }
    val prior = if (matching.isEmpty()) TransferEstimate() else estimate(matching)
    // With repeated incoming names, a later operation can replace a copy produced by an earlier
    // one. The total incoming content is a conservative additional allowance for those backups.
    val incomingAllowance = if (duplicates) contents else TransferEstimate()
    val overflow = Long.MAX_VALUE - prior.knownBytes < incomingAllowance.knownBytes
    val recoveryBytes = if (overflow) Long.MAX_VALUE else prior.knownBytes + incomingAllowance.knownBytes
    val recoveryComplete = complete && prior.complete && incomingAllowance.complete && !overflow
    val recovery = if (canReplace && (matching.isNotEmpty() || duplicates || !complete))
        TransferSpaceRequirement("possible-recovery-copies", "Possible replacement recovery copies",
            location(recoveryPath), recoveryBytes, recoveryComplete)
    else null
    val plan = planTransferSpace(contents, destinationSpace, request.move, recovery)
    val explanation = when {
        !canReplace -> "The current conflict policy keeps both names or skips conflicts, so this plan adds no replacement backup allowance."
        !complete -> "Some conflict metadata could not be read. The backup allowance is incomplete; additional recovery copies may need space in app storage."
        duplicates -> "Some selected items have the same name. The allowance includes existing matches and all incoming content because later items may replace earlier copies."
        matching.isNotEmpty() -> "The allowance counts every matching destination item as if it were replaced. Asking, merging or skipping may need less. Originals kept in Trash still occupy space."
        else -> "No matching destination names were found at inspection time. A later change or a different conflict choice can require recovery copies."
    }
    control.check()
    return TransferSpaceInspection(request, plan, matching.size, complete, explanation, clock())
}
