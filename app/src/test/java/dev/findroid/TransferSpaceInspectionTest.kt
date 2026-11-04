package dev.findroid

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TransferSpaceInspectionTest {
    private val source = Entry(File("/source/report.txt"), folder = false, size = 40, writable = true)
    private val destination = Entry(File("/destination"), folder = true, writable = true)
    private val existing = Entry(File("/destination/report.txt"), folder = false, size = 30, writable = true)
    private val recovery = "/private"
    private fun request(paths: List<String> = listOf(source.path), policy: ConflictPolicy = ConflictPolicy.ASK) =
        TransferSpaceRequest(paths, destination.path, false, policy)
    private fun inspect(request: TransferSpaceRequest = request(), extra: List<Entry> = emptyList(),
        children: List<Entry> = emptyList(), unavailable: Set<String> = emptySet(), unknown: Set<String> = emptySet(),
        capacity: Long? = 100, control: TransferControl = TransferControl(), listingFailure: Boolean = false,
        readDestination: Entry = destination): TransferSpaceInspection {
        val all = (listOf(source, readDestination, existing) + extra).associateBy { it.path }
        return inspectTransferSpace(request,
            read = { check(it !in unavailable) { "Offline" }; all.getValue(it) },
            list = { if (listingFailure) error("Provider offline"); children },
            estimate = { paths -> estimateTransfer(paths,
                { path -> check(path !in unavailable); val entry = all.getValue(path)
                    EstimateNode(path, entry.folder, entry.size.takeUnless { path in unknown }) },
                { emptyList() }, control) },
            location = { TransferSpaceLocation(it, capacity, "shared-volume") },
            recoveryPath = recovery, control = control, clock = { 1234L })
    }

    @Test fun nonconflictingCopyNeedsOnlyOnePayloadAndStartsNoOperation() {
        val result = inspect()
        assertEquals(40L, result.plan.budgets.single().knownBytes)
        assertEquals(1, result.plan.budgets.single().requirements.size)
        assertEquals(0, result.matchingDestinationItems)
        assertEquals(1234L, result.checkedAtMillis)
    }
    @Test fun possibleReplacementsIncludePreservingTheExistingOriginal() {
        val result = inspect(children = listOf(existing), capacity = 60)
        assertEquals(70L, result.plan.budgets.single().knownBytes)
        assertEquals(10L, result.plan.budgets.single().shortfallBytes)
        assertTrue(result.recoveryExplanation.contains("as if it were replaced"))
    }
    @Test fun skipAndKeepBothDoNotInventReplacementBackups() {
        for (policy in listOf(ConflictPolicy.SKIP, ConflictPolicy.KEEP_BOTH)) {
            val result = inspect(request(policy = policy), children = listOf(existing), listingFailure = true)
            assertEquals(1, result.plan.budgets.single().requirements.size)
            assertEquals(40L, result.plan.budgets.single().knownBytes)
        }
    }
    @Test fun askingAndMergingKeepConservativeOriginalAllowance() {
        for (policy in listOf(ConflictPolicy.ASK, ConflictPolicy.MERGE, ConflictPolicy.REPLACE)) {
            assertEquals(70L, inspect(request(policy = policy), children = listOf(existing)).plan.budgets.single().knownBytes)
        }
    }
    @Test fun copyingAnItemIntoItsOwnParentDoesNotBackUpItself() {
        val result = inspect(request(paths = listOf(existing.path)), children = listOf(existing))
        assertEquals(30L, result.plan.budgets.single().knownBytes)
        assertEquals(0, result.matchingDestinationItems)
    }
    @Test fun aDifferentSourceWithTheSameNameCanReplaceAnAlsoSelectedDestination() {
        val result = inspect(request(paths = listOf(existing.path, source.path)), children = listOf(existing))
        assertEquals(1, result.matchingDestinationItems)
        assertEquals(170L, result.plan.budgets.single().knownBytes)
        assertTrue(result.recoveryExplanation.contains("later items may replace earlier copies"))
    }
    @Test fun repeatedIncomingNamesBudgetForLaterReplacementOfEarlierCopies() {
        val second = source.copy(file = File("/other/report.txt"), size = 20)
        val result = inspect(request(paths = listOf(source.path, second.path)), extra = listOf(second))
        assertEquals(120L, result.plan.budgets.single().knownBytes)
        assertTrue(result.recoveryExplanation.contains("same name"))
    }
    @Test fun missingDestinationListingKeepsTheBackupRequirementUnknown() {
        val result = inspect(listingFailure = true)
        assertFalse(result.conflictInventoryComplete)
        assertTrue(result.plan.uncertain)
        assertEquals(TransferSpaceAssessment.INCOMPLETE, result.plan.budgets.single().assessment)
        assertEquals(2, result.plan.budgets.single().requirements.size)
    }
    @Test fun unknownSourceNamesMakeEveryDestinationNameAPossibleConflict() {
        val other = existing.copy(file = File("/destination/other.txt"), name = "other.txt", size = 10)
        val result = inspect(extra = listOf(other), children = listOf(existing, other), unavailable = setOf(source.path))
        assertEquals(2, result.matchingDestinationItems)
        assertFalse(result.conflictInventoryComplete)
        assertTrue(result.plan.uncertain)
        assertEquals(40L, result.plan.budgets.single().knownBytes)
    }
    @Test fun unknownRecoveryFileSizeNeverBecomesACompleteZeroByteAllowance() {
        val result = inspect(children = listOf(existing), unknown = setOf(existing.path))
        assertTrue(result.plan.uncertain)
        assertFalse(result.plan.budgets.single().complete)
    }
    @Test fun unknownCapacityStillReportsKnownContentAndRecoveryDemand() {
        val result = inspect(children = listOf(existing), capacity = null)
        assertEquals(70L, result.plan.budgets.single().knownBytes)
        assertNull(result.plan.budgets.single().availableBytes)
        assertTrue(result.plan.uncertain)
    }
    @Test(expected = CancellationException::class) fun cancellationStopsBeforeTheFirstMetadataRead() {
        inspect(control = TransferControl().apply { cancel() })
    }
    @Test(expected = IllegalStateException::class) fun readOnlyDestinationCannotOfferTransferConfirmation() {
        inspect(readDestination = destination.copy(writable = false))
    }
    @Test(expected = IllegalStateException::class) fun aFileCannotBeReviewedAsADestinationFolder() {
        inspect(readDestination = destination.copy(folder = false))
    }
    @Test(expected = IllegalArgumentException::class) fun emptySourceSelectionIsRefused() { request(paths = emptyList()) }
    @Test(expected = IllegalArgumentException::class) fun duplicatePathsAreRefusedBeforeInventory() { request(paths = listOf(source.path, source.path)) }
}
