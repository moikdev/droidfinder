package dev.findroid

import java.io.File
import java.util.Properties
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderSyncApplyTest {
    @get:Rule val temporary = TemporaryFolder()
    private inner class Fixture(stateInSource: Boolean = false) {
        val base = temporary.newFolder()
        val source = File(base, "Source").apply { mkdir() }
        val destination = File(base, "Destination").apply { mkdir() }
        val state = File(if (stateInSource) source else base, "State").apply { mkdir() }
        val journal = File(base, "Journal").apply { mkdir() }
        val storage = FolderSyncNioStorage()
        val files = FolderSyncNioProofs(storage)
        val engine = FileEngine(state)
        var moves = 0
        var finishCount = 0
        val events = mutableListOf<String>()
        init {
            File(source, "New/Sub").mkdirs(); File(source, "New/Sub/A.txt").writeText("reviewed addition")
            File(source, "Empty").mkdir(); File(source, "Update.txt").writeText("new data")
            File(source, "Same.txt").writeText("same")
            File(destination, "Update.txt").writeText("old data"); File(destination, "Same.txt").writeText("same")
            File(destination, "Extra").mkdir(); File(destination, "Extra/Keep.txt").writeText("destination-only original")
            files.beforeRename = { _, _ -> moves++ }
        }
        fun inspect(direction: FolderSyncDirection = FolderSyncDirection.PRIMARY_TO_SECONDARY): FolderSyncInspection {
            fun node(path: String): ComparisonNode {
                val stamp = storage.stat(path)!!
                return ComparisonNode(path, File(path).name, stamp.directory, stamp.size.takeIf { stamp.regular },
                    stamp.modifiedSeconds * 1000 + stamp.modifiedNanos / 1_000_000)
            }
            return inspectFolderSync(source.path, destination.path, direction, { true }, ::node,
                { path -> File(path).listFiles()!!.map { node(it.path) } },
                { path, stamp, token -> folderSyncDigest(path, stamp, token, storage) }, files)
        }
        fun prepare(selected: Set<String>? = null, direction: FolderSyncDirection = FolderSyncDirection.PRIMARY_TO_SECONDARY): PreparedFolderSync {
            val inspection = inspect(direction)
            val review = reviewFolderSync(inspection.preview, selected ?: inspection.preview.initialSelection, true, true)
            return prepareFolderSync(inspection, review, { true }, files, storage)
        }
        fun apply(prepared: PreparedFolderSync,
                  allowed: (String) -> Boolean = { true },
                  afterPreserved: (FolderSyncEntry, File) -> Unit = { _, _ -> },
                  afterPublished: (FolderSyncEntry, File?) -> Unit = { _, _ -> },
                  finish: () -> Unit = {}, control: TransferControl = TransferControl(), parent: String = journal.path): FolderSyncApplyState =
            applyPreparedFolderSync(prepared, engine, parent, allowed,
                { op, saved -> events += "preserved:" + op.relative; afterPreserved(op, saved) },
                { op, saved -> events += "published:" + op.relative; afterPublished(op, saved) },
                { finishCount++; finish() }, files, storage, control)
        fun journalText() = journal.walkTopDown().filter { it.isFile }.sortedBy { it.path }.joinToString("\n") { it.readText() }
    }
    private fun stopped(work: () -> Unit): FolderSyncApplyFailure {
        try { work(); fail("Expected synchronization refusal") } catch (error: FolderSyncApplyFailure) { return error }
        error("Unreachable")
    }
    private fun cancelled(work: () -> Unit): FolderSyncApplyCancelled {
        try { work(); fail("Expected cancellation") } catch (error: FolderSyncApplyCancelled) { return error }
        error("Unreachable")
    }
    @Test fun defaultRunPublishesOnlyDisclosedAdditionsAndKeepsBothOriginalTrees() {
        val f = Fixture(); val prepared = f.prepare(); val source = prepared.inspection.primaryProof
        val result = f.apply(prepared)
        assertEquals(listOf("Empty", "New"), result.completed.map { it.operation.relative })
        assertEquals(listOf("published:Empty", "published:New"), f.events)
        assertTrue(result.preservedOriginals.isEmpty()); assertEquals(2, result.publishedRoots.size)
        assertEquals("reviewed addition", File(f.destination, "New/Sub/A.txt").readText())
        assertTrue(File(f.destination, "Empty").isDirectory)
        assertEquals("old data", File(f.destination, "Update.txt").readText())
        assertEquals("destination-only original", File(f.destination, "Extra/Keep.txt").readText())
        assertEquals(source, f.files.observe(f.source.path)); assertEquals(1, f.finishCount)
        assertTrue(f.journalText().contains("\"event\":\"finished\""))
        assertTrue(File(prepared.directory, "review.json").isFile)
        assertFalse(prepared.roots.any { File(it.path).exists() })
    }
    @Test fun aMixedRunPreservesEachReviewedOriginalBeforePublishingAndNeverTouchesUnselectedFiles() {
        val f = Fixture(); val prepared = f.prepare(setOf("New", "Empty", "Update.txt", "Extra"))
        val result = f.apply(prepared)
        assertEquals(4, result.completed.size); assertEquals(2, result.preservedOriginals.size); assertEquals(3, result.publishedRoots.size)
        assertEquals(listOf("published:Empty", "published:New", "preserved:Update.txt", "published:Update.txt", "preserved:Extra"), f.events)
        assertEquals("new data", File(f.destination, "Update.txt").readText()); assertFalse(File(f.destination, "Extra").exists())
        for (item in result.completed.filter { it.originalRecovery != null }) {
            val old = item.operation.destinationPath; val saved = item.originalRecovery!!
            assertEquals(old, f.engine.originalPath(saved)); assertEquals(old, FileEngine(f.state).originalPath(saved))
            assertEquals(prepared.inspection.secondaryProof.entries.getValue(old).inode, f.storage.stat(saved.path)!!.inode)
        }
        assertEquals("old data", result.completed.single { it.operation.relative == "Update.txt" }.originalRecovery!!.readText())
        assertEquals("destination-only original", File(result.completed.single { it.operation.relative == "Extra" }.originalRecovery!!, "Keep.txt").readText())
        assertEquals("same", File(f.destination, "Same.txt").readText()); assertEquals(prepared.inspection.primaryProof, f.files.observe(f.source.path))
    }
    @Test fun aTrashOnlyRunHasNoPublicationAndKeepsTheOriginalSubtreeInItsNativeRecoveryBucket() {
        val f = Fixture(); val prepared = f.prepare(setOf("Extra")); val result = f.apply(prepared)
        assertEquals(1, result.completed.size); assertTrue(result.publishedRoots.isEmpty())
        assertEquals("destination-only original", File(result.completed.single().originalRecovery!!, "Keep.txt").readText())
        assertEquals("old data", File(f.destination, "Update.txt").readText()); assertFalse(File(f.destination, "New").exists())
    }
    @Test fun reverseDirectionUsesTheReviewedSourceRatherThanInventingAWinningTimestamp() {
        val f = Fixture(); val prepared = f.prepare(direction = FolderSyncDirection.SECONDARY_TO_PRIMARY)
        val secondary = prepared.inspection.secondaryProof; val result = f.apply(prepared)
        assertEquals(listOf("Extra"), result.completed.map { it.operation.relative })
        assertEquals("destination-only original", File(f.source, "Extra/Keep.txt").readText())
        assertEquals("new data", File(f.source, "Update.txt").readText()); assertEquals(secondary, f.files.observe(f.destination.path))
    }
    @Test fun sourceChangesAfterPreparationStopBeforeCreatingAnApplyJournal() {
        val f = Fixture(); val prepared = f.prepare(); File(f.source, "Update.txt").writeText("later source")
        val error = stopped { f.apply(prepared) }; assertNull(error.state.journalDirectory)
        assertEquals(0, f.moves); assertTrue(f.journal.listFiles()!!.isEmpty()); assertFalse(File(f.destination, "Empty").exists())
    }
    @Test fun destinationArrivalsAfterPreparationKeepTheirBytesAndStopTheWholePlan() {
        val f = Fixture(); val prepared = f.prepare(); File(f.destination, "Arrival.txt").writeText("keep arrival")
        stopped { f.apply(prepared) }; assertEquals(0, f.moves); assertEquals("keep arrival", File(f.destination, "Arrival.txt").readText())
    }
    @Test fun editedWorkingCopiesNeverBecomeANewBaseline() {
        val f = Fixture(); val prepared = f.prepare(); File(prepared.roots.single { it.operation.relative == "New" }.path, "Sub/A.txt").writeText("edited stage")
        stopped { f.apply(prepared) }; assertEquals(0, f.moves); assertEquals("reviewed addition", File(f.source, "New/Sub/A.txt").readText())
    }
    @Test fun accessLossRefusesEveryOriginalAndPublication() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt", "Extra"))
        stopped { f.apply(prepared, allowed = { it != f.destination.path }) }; assertEquals(0, f.moves)
        assertEquals("old data", File(f.destination, "Update.txt").readText()); assertTrue(f.engine.trashItems().isEmpty())
    }
    @Test fun theApplyJournalCannotBeCreatedInsideTheSource() {
        val f = Fixture(); val prepared = f.prepare(); stopped { f.apply(prepared, parent = f.source.path) }
        assertEquals(prepared.inspection.primaryProof, f.files.observe(f.source.path)); assertEquals(0, f.moves)
    }
    @Test fun recoveryInsideAReviewedTreeIsRefusedBeforeCreatingABucket() {
        val f = Fixture(stateInSource = true); val prepared = f.prepare(setOf("Update.txt"))
        stopped { f.apply(prepared) }; assertEquals(prepared.inspection.primaryProof, f.files.observe(f.source.path))
        assertTrue(f.engine.trashItems().isEmpty()); assertEquals(0, f.moves)
    }
    @Test fun aFailedIntentWriteKeepsThePreparedAndDestinationTrees() {
        val f = Fixture(); val prepared = f.prepare()
        f.storage.afterWrite = { if (it.endsWith("00001.json")) error("intent disk failure") }
        val error = stopped { f.apply(prepared) }; assertFalse(error.state.renameAttempted)
        assertEquals(prepared.proof, f.files.observe(prepared.directory)); assertEquals(0, f.moves)
        assertNotNull(error.state.journalDirectory); assertFalse(f.journalText().contains("\"event\":\"finished\""))
    }
    @Test fun aLaterKernelCollisionKeepsTheArrivalAndTheRemainingPreparedSubtree() {
        val f = Fixture(); val prepared = f.prepare()
        f.files.beforeRename = { _, to -> f.moves++; if (to.name == "New") to.writeText("last-moment arrival") }
        val error = stopped { f.apply(prepared) }
        assertEquals(listOf("Empty"), error.state.completed.map { it.operation.relative })
        assertEquals("last-moment arrival", File(f.destination, "New").readText()); assertTrue(File(f.destination, "Empty").isDirectory)
        assertEquals("reviewed addition", File(prepared.roots.single { it.operation.relative == "New" }.path, "Sub/A.txt").readText())
        assertEquals(f.destination.resolve("New").path, error.state.publication!!.destination)
    }
    @Test fun aSourceEditBetweenOperationsStopsTheRemainingPlan() {
        val f = Fixture(); val prepared = f.prepare()
        val error = stopped { f.apply(prepared, afterPublished = { _, _ -> File(f.source, "Update.txt").writeText("later source edit") }) }
        assertEquals(1, error.state.publishedRoots.size); assertEquals(1, f.moves)
        assertFalse(File(f.destination, "New").exists()); assertEquals("later source edit", File(f.source, "Update.txt").readText())
    }
    @Test fun anUnselectedDestinationEditBetweenOperationsIsNotAdopted() {
        val f = Fixture(); val prepared = f.prepare()
        stopped { f.apply(prepared, afterPublished = { _, _ -> File(f.destination, "Same.txt").writeText("later destination edit") }) }
        assertEquals(1, f.moves); assertEquals("later destination edit", File(f.destination, "Same.txt").readText()); assertFalse(File(f.destination, "New").exists())
    }
    @Test fun anEditedRemainingStageStopsTheNextPublication() {
        val f = Fixture(); val prepared = f.prepare(); val next = File(prepared.roots.single { it.operation.relative == "New" }.path, "Sub/A.txt")
        stopped { f.apply(prepared, afterPublished = { _, _ -> next.writeText("keep edited stage") }) }
        assertEquals(1, f.moves); assertEquals("keep edited stage", next.readText()); assertFalse(File(f.destination, "New").exists())
    }
    @Test fun aPublicationMetadataFailureExposesTheKnownPublicationWithoutMovingAnotherItem() {
        val f = Fixture(); val prepared = f.prepare()
        val error = stopped { f.apply(prepared, afterPublished = { _, _ -> error("metadata not saved") }) }
        assertEquals(1, error.state.publishedRoots.size); assertNotNull(error.state.publication)
        assertEquals(1, f.moves); assertEquals(0, f.finishCount); assertTrue(File(f.destination, "Empty").isDirectory)
    }
    @Test fun aPreservationMetadataFailureExposesTheOriginalAndKeepsThePreparedReplacement() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt"))
        val error = stopped { f.apply(prepared, afterPreserved = { _, _ -> error("metadata not saved") }) }
        assertEquals(1, error.state.preservedOriginals.size); assertNotNull(error.state.preservation); assertTrue(error.state.publishedRoots.isEmpty())
        assertEquals("old data", File(error.state.preservedOriginals.single().recovery).readText()); assertFalse(File(f.destination, "Update.txt").exists())
        assertEquals("new data", File(prepared.roots.single().path).readText()); assertEquals(1, f.moves)
    }
    @Test fun failureOfTheFinalMetadataFlushNeverWritesARunCompletionOrRollsBackFiles() {
        val f = Fixture(); val prepared = f.prepare(); val error = stopped { f.apply(prepared, finish = { error("durable metadata flush failed") }) }
        assertEquals(2, error.state.completed.size); assertTrue(File(f.destination, "Empty").isDirectory)
        assertEquals("reviewed addition", File(f.destination, "New/Sub/A.txt").readText()); assertFalse(f.journalText().contains("\"event\":\"finished\""))
    }
    @Test fun cancellationBeforeTheApplyCreatesNoJournalAndMovesNoOriginal() {
        val f = Fixture(); val prepared = f.prepare(); val token = TransferControl().apply { cancel() }
        val error = cancelled { f.apply(prepared, control = token) }; assertNull(error.state.journalDirectory); assertEquals(0, f.moves)
    }
    @Test fun cancellationAfterOnePublicationKeepsItAndTheRemainingWorkingData() {
        val f = Fixture(); val prepared = f.prepare(); val token = TransferControl()
        val error = cancelled { f.apply(prepared, control = token, afterPublished = { _, _ -> token.cancel() }) }
        assertEquals(1, error.state.publishedRoots.size); assertEquals(1, f.moves); assertTrue(File(f.destination, "Empty").isDirectory)
        assertTrue(File(prepared.roots.single { it.operation.relative == "New" }.path).exists()); assertFalse(File(f.destination, "New").exists())
    }
    @Test fun aSyncFaultAfterNativePublicationKeepsItsVisibleResultAndManualIntent() {
        val f = Fixture(); val prepared = f.prepare(); f.files.failFinalSync = true
        val error = stopped { f.apply(prepared) }; assertTrue(error.state.renameAttempted); assertNotNull(error.state.publication)
        assertTrue(File(f.destination, "Empty").isDirectory); assertEquals(0, f.finishCount); assertTrue(f.events.isEmpty())
    }
    @Test fun aSyncFaultAfterOriginalPreservationKeepsItsRecoveryPointer() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt")); f.files.failFinalSync = true
        val error = stopped { f.apply(prepared) }; assertTrue(error.state.renameAttempted)
        assertEquals("old data", File(error.state.preservation!!.recovery).readText()); assertFalse(File(f.destination, "Update.txt").exists())
        assertEquals("new data", File(prepared.roots.single().path).readText()); assertTrue(f.events.isEmpty())
    }
    @Test fun sourceEditingDuringTheDurablePublicationIntentStopsTheNativeMove() {
        val f = Fixture(); val prepared = f.prepare()
        f.storage.afterWrite = { if (it.endsWith("00001.json")) File(f.source, "Update.txt").writeText("source edited during intent") }
        stopped { f.apply(prepared) }; assertEquals(0, f.moves); assertEquals(prepared.proof, f.files.observe(prepared.directory))
    }
    @Test fun sourceEditingDuringTheDurablePreservationIntentKeepsTheOriginalInPlace() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt"))
        f.storage.afterWrite = { if (it.endsWith("00001.json")) File(f.source, "Update.txt").writeText("source edited during original intent") }
        stopped { f.apply(prepared) }; assertEquals(0, f.moves); assertEquals("old data", File(f.destination, "Update.txt").readText())
        assertTrue(f.engine.trashItems().isEmpty()); assertEquals("new data", File(prepared.roots.single().path).readText())
    }
    @Test fun anArrivalDuringThePreservationIntentCannotJoinTheDestinationBaseline() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt"))
        f.storage.afterWrite = { if (it.endsWith("00001.json")) File(f.destination, "Arrival.txt").writeText("keep arrival") }
        stopped { f.apply(prepared) }; assertEquals(0, f.moves); assertEquals("old data", File(f.destination, "Update.txt").readText())
        assertEquals("keep arrival", File(f.destination, "Arrival.txt").readText())
    }
    @Test fun changedOnDiskRecoveryMappingsStopBeforeTheReplacementAndKeepThoseChangedBytes() {
        val f = Fixture(); val prepared = f.prepare(setOf("Update.txt")); val index = File(f.state, "trash.properties")
        val error = stopped { f.apply(prepared, afterPreserved = { _, saved ->
            val props = Properties().apply { index.inputStream().use(::load) }
            props.setProperty(saved.parentFile!!.name, File(f.destination, "Another.txt").path)
            index.outputStream().use { props.store(it, "later mapping edit") }
        }) }
        assertEquals(1, f.moves); assertEquals(1, error.state.preservedOriginals.size); assertTrue(error.state.publishedRoots.isEmpty())
        assertTrue(index.readText().contains("Another.txt")); assertEquals("old data", File(error.state.preservedOriginals.single().recovery).readText())
        assertEquals("new data", File(prepared.roots.single().path).readText())
    }

    // These are capacity tests of the complete-plan preflight. Synthetic paths keep the tests
    // independent of native path-length limits and avoid writing thousands of disposable files.
    private fun budgetPlan(category: FolderSyncCategory, count: Int, padding: String = ""): PreparedFolderSync {
        val baseline = Fixture().prepare()
        val operations = (0 until count).map { index ->
            FolderSyncEntry("${category.name}-$index", null, null, "/destination/$padding/${category.name}-$index", category, "reviewed")
        }
        return baseline.copy(
            review = baseline.review.copy(operations = operations),
            roots = operations.filter { it.category != FolderSyncCategory.DELETION }.map {
                FolderSyncStagedRoot(it, "/working/$padding/${it.relative}")
            },
        )
    }
    private fun budgetRecovery(): FolderSyncTrashLocation = Fixture().let {
        FolderSyncTrashLocation(File(it.state, "Trash").path, File(it.state, "Trash").canonicalPath, it.storage.stat(File(it.state, "Trash").path)!!)
    }
    private fun budgetRefused(plan: PreparedFolderSync, recovery: FolderSyncTrashLocation? = null) {
        try { checkFolderSyncJournalBudget(plan, recovery); fail("Expected complete-plan journal refusal") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("journal limit")) }
    }
    @Test fun journalRecordLimitIncludesBothMovesForEveryReplacement() {
        val recovery = budgetRecovery()
        checkFolderSyncJournalBudget(budgetPlan(FolderSyncCategory.UPDATE, 5_000), recovery)
        budgetRefused(budgetPlan(FolderSyncCategory.UPDATE, 5_001), recovery)
    }
    @Test fun journalRecordLimitIncludesCreationAndCompletionForAdditionsAndTrash() {
        for (category in listOf(FolderSyncCategory.ADDITION, FolderSyncCategory.DELETION)) {
            val recovery = budgetRecovery().takeIf { category == FolderSyncCategory.DELETION }
            checkFolderSyncJournalBudget(budgetPlan(category, 10_000), recovery)
            budgetRefused(budgetPlan(category, 10_001), recovery)
        }
    }
    @Test fun mixedPlanAtRecordLimitCannotAddAnotherPublication() {
        val recovery = budgetRecovery()
        val updates = budgetPlan(FolderSyncCategory.UPDATE, 4_999)
        val additions = budgetPlan(FolderSyncCategory.ADDITION, 3)
        val atLimit = updates.copy(review = updates.review.copy(operations = updates.review.operations + additions.review.operations.take(2)),
            roots = updates.roots + additions.roots.take(2))
        checkFolderSyncJournalBudget(atLimit, recovery)
        budgetRefused(atLimit.copy(review = atLimit.review.copy(operations = atLimit.review.operations + additions.review.operations.last()),
            roots = atLimit.roots + additions.roots.last()), recovery)
    }
    @Test fun totalJournalByteLimitStopsLongPlansBeforeTheRecordLimit() {
        checkFolderSyncJournalBudget(budgetPlan(FolderSyncCategory.ADDITION, 5_000, "x".repeat(1_400)), null)
        budgetRefused(budgetPlan(FolderSyncCategory.ADDITION, 5_000, "x".repeat(1_800)))
    }
    @Test fun perRecordBudgetCountsEncodedUtf8BytesAndJsonEscapes() {
        checkFolderSyncJournalBudget(budgetPlan(FolderSyncCategory.ADDITION, 1, "x".repeat(17_000)), null)
        budgetRefused(budgetPlan(FolderSyncCategory.ADDITION, 1, "é".repeat(17_000)))
        budgetRefused(budgetPlan(FolderSyncCategory.ADDITION, 1, "\\".repeat(17_000)))
    }
    @Test fun oversizedInitialJournalRecordIsRejectedWithoutAnyMoves() {
        val plan = budgetPlan(FolderSyncCategory.DELETION, 1).copy(directory = "/working/" + "x".repeat(65_536))
        budgetRefused(plan, budgetRecovery())
    }
}
