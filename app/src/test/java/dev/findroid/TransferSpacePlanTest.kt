package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class TransferSpacePlanTest {
    private fun destination(free: Long? = 100, filesystem: String? = "local-volume") = TransferSpaceLocation("/destination", free, filesystem)
    private fun contents(bytes: Long = 40, unknown: Int = 0) = TransferEstimate(knownBytes = bytes, files = 2, unknownFiles = unknown)
    private fun backup(bytes: Long = 30, free: Long? = 100, filesystem: String? = "local-volume", complete: Boolean = true) =
        TransferSpaceRequirement("recovery-copies", "Preserved original copies", TransferSpaceLocation("/app/Trash", free, filesystem), bytes, complete)
    @Test fun stagingDoesNotDoubleThePayload() {
        val plan = planTransferSpace(contents(), destination(), false)
        assertEquals(40L, plan.budgets.single().knownBytes)
        assertEquals(60L, plan.budgets.single().remainingAfterKnownContent)
        assertTrue(plan.stagingExplanation.contains("counted once"))
    }
    @Test fun provenSharedFilesystemCombinesPayloadAndRecoveryDemand() {
        val budget = planTransferSpace(contents(), destination(), false, backup()).budgets.single()
        assertEquals(70L, budget.knownBytes); assertEquals(2, budget.requirements.size)
        assertEquals(TransferSpaceAssessment.KNOWN_CONTENT_FITS, budget.assessment)
    }
    @Test fun sharedFilesystemUsesTheLowerReportedCapacity() {
        val budget = planTransferSpace(contents(), destination(100), false, backup(free = 60)).budgets.single()
        assertEquals(60L, budget.availableBytes); assertEquals(10L, budget.shortfallBytes)
        assertEquals(TransferSpaceAssessment.INSUFFICIENT, budget.assessment)
    }
    @Test fun distinctProvenStorageVolumesHaveSeparateBudgets() {
        val plan = planTransferSpace(contents(), destination(), false, backup(filesystem = "other-volume"))
        assertEquals(2, plan.budgets.size); assertFalse(plan.sharedStorageUnproven); assertFalse(plan.uncertain)
        assertEquals(listOf(40L, 30L), plan.budgets.map { it.knownBytes })
    }
    @Test fun unknownProviderCapacityIsNotZeroOrInfinite() {
        val budget = planTransferSpace(contents(), destination(null, null), false).budgets.single()
        assertNull(budget.availableBytes); assertNull(budget.shortfallBytes)
        assertEquals(TransferSpaceAssessment.UNREPORTED, budget.assessment)
    }
    @Test fun negativeProviderCapacityIsUnreported() {
        assertEquals(TransferSpaceAssessment.UNREPORTED, planTransferSpace(contents(), destination(-1), false).budgets.single().assessment)
    }
    @Test fun unknownPhysicalRelationshipDoesNotAddTwoCapacities() {
        val plan = planTransferSpace(contents(), destination(50, null), false, backup(free = 50, filesystem = null))
        assertEquals(2, plan.budgets.size); assertTrue(plan.sharedStorageUnproven); assertTrue(plan.uncertain)
        assertTrue(plan.cautions.any { "cannot be added" in it })
    }
    @Test fun identicalUnknownLocationCombinesItsDemand() {
        val target = destination(60, null)
        val requirement = backup().copy(location = target)
        val budget = planTransferSpace(contents(), target, false, requirement).budgets.single()
        assertEquals(70L, budget.knownBytes); assertEquals(10L, budget.shortfallBytes)
    }
    @Test fun incompleteInventoryNeverClaimsTheWholeOperationFits() {
        val plan = planTransferSpace(contents(unknown = 1), destination(), false)
        assertEquals(TransferSpaceAssessment.INCOMPLETE, plan.budgets.single().assessment); assertTrue(plan.uncertain)
        assertTrue(plan.cautions.any { "No missing size is treated as zero" in it })
    }
    @Test fun aKnownLowerBoundCanAlreadyProveAShortfall() {
        val plan = planTransferSpace(contents(unknown = 1), destination(20), false)
        assertTrue(plan.knownShortfall); assertEquals(20L, plan.budgets.single().shortfallBytes)
    }
    @Test fun zeroFreeBytesAreARealReportedCapacity() {
        assertEquals(TransferSpaceAssessment.INSUFFICIENT, planTransferSpace(contents(), destination(0), false).budgets.single().assessment)
    }
    @Test fun equalContentAndCapacityStillExplainsUncountedOverhead() {
        val plan = planTransferSpace(contents(), destination(40), false)
        assertEquals(0L, plan.budgets.single().remainingAfterKnownContent)
        assertTrue(plan.cautions.any { "Leave additional free space" in it })
    }
    @Test fun movesBudgetTheirFallbackWithoutCreditingSourceDeletion() {
        val plan = planTransferSpace(contents(), destination(10), true)
        assertTrue(plan.knownShortfall); assertEquals(40L, plan.budgets.single().knownBytes)
        assertTrue(plan.cautions.any { "source deletion" in it })
    }
    @Test fun anUnknownBackupKeepsTheCombinedRequirementIncomplete() {
        val plan = planTransferSpace(contents(), destination(), false, backup(complete = false))
        assertEquals(TransferSpaceAssessment.INCOMPLETE, plan.budgets.single().assessment)
    }
    @Test fun saturatingTotalsNeverBecomeNegativeOrClaimAnExactFit() {
        val plan = planTransferSpace(contents(Long.MAX_VALUE), destination(Long.MAX_VALUE), false, backup(1, Long.MAX_VALUE))
        val budget = plan.budgets.single(); assertEquals(Long.MAX_VALUE, budget.knownBytes)
        assertTrue(budget.overflow); assertFalse(budget.complete); assertTrue(plan.knownShortfall)
        assertNull(budget.shortfallBytes); assertNull(budget.remainingAfterKnownContent)
    }
    @Test fun missingObservationOnAProvenSharedVolumeRetainsTheOtherReport() {
        val budget = planTransferSpace(contents(), destination(null), false, backup(free = 100)).budgets.single()
        assertEquals(100L, budget.availableBytes); assertEquals(70L, budget.knownBytes)
    }
    @Test fun unreadableOrLimitedInventoriesRemainLowerBounds() {
        for (estimate in listOf(contents().copy(unreadable = 1), contents().copy(limited = true))) {
            assertEquals(TransferSpaceAssessment.INCOMPLETE, planTransferSpace(estimate, destination(), false).budgets.single().assessment)
        }
    }
    @Test fun aZeroLengthPayloadStillDoesNotPromiseMetadataOverheadIsFree() {
        val plan = planTransferSpace(contents(0), destination(0), false)
        assertEquals(0L, plan.budgets.single().knownBytes); assertTrue(plan.cautions.any { "journals" in it })
    }
    @Test(expected = IllegalArgumentException::class) fun negativeRequirementsAreRefused() {
        backup(bytes = -1)
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateRequirementIdentitiesAreRefused() {
        planTransferSpace(contents(), destination(), false, backup().copy(id = "destination-payload"))
    }
}
