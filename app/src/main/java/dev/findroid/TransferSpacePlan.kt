package dev.findroid

/** A point-in-time report, never a reservation or permission to write a document. */
internal data class TransferSpaceLocation(
    val path: String,
    val availableBytes: Long?,
    /** Supply only a locally observed storage-volume UUID. Provider labels are not volume proofs. */
    val storageVolumeId: String? = null,
) {
    val reportedAvailable get() = availableBytes?.takeIf { it >= 0 }
}

internal data class TransferSpaceRequirement(
    val id: String,
    val title: String,
    val location: TransferSpaceLocation,
    val knownBytes: Long,
    val complete: Boolean,
) {
    init { require(id.isNotBlank() && knownBytes >= 0) }
}

internal enum class TransferSpaceAssessment(val explanation: String) {
    INSUFFICIENT("The reported free space is smaller than this plan’s known allowance."),
    UNREPORTED("This location does not report usable capacity."),
    INCOMPLETE("Some required bytes are unknown. The known content is only a lower bound."),
    KNOWN_CONTENT_FITS("The known content fits the reported space. Keep room for filesystem overhead and other activity."),
}

internal data class TransferSpaceBudget(
    val requirements: List<TransferSpaceRequirement>,
    val knownBytes: Long,
    val overflow: Boolean,
    val complete: Boolean,
    val availableBytes: Long?,
) {
    val assessment: TransferSpaceAssessment get() = when {
        availableBytes == null -> TransferSpaceAssessment.UNREPORTED
        overflow || knownBytes > availableBytes -> TransferSpaceAssessment.INSUFFICIENT
        !complete -> TransferSpaceAssessment.INCOMPLETE
        else -> TransferSpaceAssessment.KNOWN_CONTENT_FITS
    }
    val shortfallBytes get() = availableBytes?.takeUnless { overflow }?.let { (knownBytes - it).coerceAtLeast(0L) }
    val remainingAfterKnownContent get() = availableBytes?.takeUnless { overflow }?.let { (it - knownBytes).coerceAtLeast(0L) }
}

internal data class TransferSpacePlan(
    val contents: TransferEstimate,
    val move: Boolean,
    val budgets: List<TransferSpaceBudget>,
    val sharedStorageUnproven: Boolean,
    val stagingExplanation: String,
    val cautions: List<String>,
) {
    val knownShortfall get() = budgets.any { it.assessment == TransferSpaceAssessment.INSUFFICIENT }
    val uncertain get() = sharedStorageUnproven || budgets.any {
        it.assessment == TransferSpaceAssessment.UNREPORTED || it.assessment == TransferSpaceAssessment.INCOMPLETE
    }
}

/**
 * Counts the new payload once: an unpublished working copy becomes the final copy rather than
 * requiring a second payload allocation. Separate original-preservation copies are additional.
 * No future source removal or replacement is credited as space that is already available.
 */
internal fun planTransferSpace(
    contents: TransferEstimate,
    destination: TransferSpaceLocation,
    move: Boolean,
    recoveryCopies: TransferSpaceRequirement? = null,
): TransferSpacePlan {
    val payload = TransferSpaceRequirement("destination-payload", "New destination contents", destination,
        contents.knownBytes, contents.complete)
    val requirements = listOfNotNull(payload, recoveryCopies)
    require(requirements.map { it.id }.distinct().size == requirements.size) { "Space requirements need distinct identities." }
    // A different unknown URI/path can still consume the same physical storage. Do not add its
    // available capacity to another location or claim independent reservations.
    val grouped = requirements.groupBy { requirement ->
        requirement.location.storageVolumeId?.let { "volume:$it" } ?: "unverified:${requirement.location.path}"
    }
    val budgets = grouped.values.map { members ->
        var bytes = 0L; var overflow = false
        members.forEach { member ->
            if (Long.MAX_VALUE - bytes < member.knownBytes) { bytes = Long.MAX_VALUE; overflow = true }
            else bytes += member.knownBytes
        }
        TransferSpaceBudget(members, bytes, overflow, members.all { it.complete } && !overflow,
            members.mapNotNull { it.location.reportedAvailable }.minOrNull())
    }
    val sharedUnknown = grouped.size > 1 && requirements.any { it.location.storageVolumeId == null }
    val cautions = buildList {
        add("Space reports can change before work starts. The copy engine checks capacity again; this review does not reserve space.")
        add("Directory entries, allocation units, journals and provider overhead are not included in content byte counts. Leave additional free space.")
        if (!contents.complete) add("Unknown file sizes, unreadable items or the inventory limit can increase the requirement. No missing size is treated as zero.")
        if (sharedUnknown) add("Some locations may share storage. Their free capacities cannot be added, and their combined demand is not proven to fit.")
        if (move) add("A same-filesystem rename may need less space. This plan budgets the full staged-copy fallback and never assumes source deletion has already freed space.")
        if (recoveryCopies != null) add("The separate recovery-copy allowance preserves originals when a replacement needs another copy. Space is not reclaimed by deleting those originals during this review.")
    }
    return TransferSpacePlan(contents, move, budgets, sharedUnknown,
        "Unpublished working files use the new destination-content allowance until publication. Their payload is counted once. Durable local checkpoints may use app-private storage on the same filesystem; unsupported copies use working data beside the destination.",
        cautions)
}
