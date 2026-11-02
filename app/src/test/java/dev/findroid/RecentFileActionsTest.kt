package dev.findroid

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentFileActionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun engine() = FileEngine(temporary.newFolder())
    private fun step(detail: String = "Demo file", check: (Boolean) -> Unit = {}, perform: (Boolean) -> List<Pair<String, String>> = { emptyList() }) =
        FileStep.Callback(description = detail, check = check, perform = perform)
    private fun fails(work: () -> Unit): Exception {
        try { work(); error("Expected failure") } catch (failure: Exception) { assertNotEquals("Expected failure", failure.message); return failure }
    }
    @Test fun snapshotsKeepTheirStateAndRecordOrderWhenTheClockMovesBackwards() {
        var now = 20L
        val history = FileHistory(engine()) { now-- }
        history.record("First", listOf(step("first.txt"))); history.record("Second", listOf(step("second.txt")))
        val before = history.recentActions()
        assertEquals(listOf("Second", "First"), before.map { it.label })
        assertTrue(before.first().nextUndo); assertEquals("second.txt", before.first().detail)
        history.undo()
        assertEquals(RecentActionState.COMPLETED, before.first().state)
        val after = history.recentActions()
        assertEquals(RecentActionState.UNDONE, after.first().state); assertTrue(after.first().nextRedo)
        assertTrue(after.last().nextUndo)
    }
    @Test fun changedFileGuardsRemainVisibleAndNeverOverwriteLaterEdits() {
        val history = FileHistory(engine()); val file = temporary.newFile("Draft.txt").apply { writeText("original") }
        val originalTime = file.lastModified()
        history.record("New text file", listOf(FileStep.Created(file))); file.writeText("later edits")
        fails { history.undo() }
        assertEquals("later edits", file.readText()); assertTrue(history.lastFailure!!.contains("changed"))
        assertTrue(history.recentActions().single().nextUndo); assertFalse(history.recentActions().single().nextRedo)
        file.writeText("original"); assertTrue(file.setLastModified(originalTime)); history.undo(); assertFalse(file.exists()); assertNull(history.lastFailure)
        history.redo(); assertEquals("original", file.readText())
    }
    @Test fun partialUndoShowsItsStateAndRetryDoesNotRepeatCompletedSteps() {
        val history = FileHistory(engine()); var blocked = true; val calls = intArrayOf(0,0)
        history.record("Move two files", listOf(
            step(perform = { if (blocked) error("Destination changed"); calls[0]++; emptyList() }),
            step(perform = { calls[1]++; emptyList() }),
        ))
        assertTrue(fails { history.undo() } is HistoryFailure)
        assertEquals(RecentActionState.PARTLY_UNDONE, history.recentActions().single().state)
        assertTrue(history.recentActions().single().nextUndo); assertNotNull(history.lastFailure)
        blocked = false; history.undo()
        assertArrayEquals(intArrayOf(1,1), calls); assertNull(history.lastFailure)
        assertEquals(RecentActionState.UNDONE, history.recentActions().single().state)
    }
    @Test fun partialRedoCanResumeWithoutApplyingAnEarlierStepTwice() {
        val history = FileHistory(engine()); var blocked = false; val calls = intArrayOf(0,0)
        history.record("Restore files", listOf(
            step(perform = { undo -> if (!undo) calls[0]++; emptyList() }),
            step(perform = { undo -> if (!undo && blocked) error("File changed"); if (!undo) calls[1]++; emptyList() }),
        ))
        history.undo(); blocked = true; fails { history.redo() }
        assertEquals(RecentActionState.PARTLY_REDONE, history.recentActions().single().state)
        assertTrue(history.recentActions().single().nextRedo); assertFalse(history.recentActions().single().nextUndo)
        blocked = false; history.redo(); assertArrayEquals(intArrayOf(1,1),calls)
        assertEquals(RecentActionState.COMPLETED, history.recentActions().single().state)
    }
    @Test fun everyGuardRunsBeforeAnyStepChangesAFile() {
        val history = FileHistory(engine()); var applied = 0
        history.record("Rename", listOf(step(check = { error("Edited file") }),step(perform = { applied++; emptyList() })))
        fails { history.undo() }; assertEquals(0,applied)
        assertEquals("Edited file", history.lastFailure); assertEquals(RecentActionState.COMPLETED, history.recentActions().single().state)
    }
    @Test fun theTimelineIsBoundedAndNewActionsDiscardTheRedoBranch() {
        val history = FileHistory(engine())
        repeat(60) { history.record("Action $it", listOf(step())) }
        assertEquals(50,history.recentActions().size); assertEquals("Action 10",history.recentActions().last().label)
        history.undo(); assertNotNull(history.redoLabel)
        history.record("New branch",listOf(step()))
        assertNull(history.redoLabel); assertFalse(history.recentActions().any { it.label=="Action 59" })
        assertEquals("New branch",history.recentActions().first().label)
    }
    @Test fun anEmptyRecordDoesNotDestroyAnExistingRedoOrFailure() {
        val history = FileHistory(engine()); var blocked = false
        history.record("Create", listOf(step(check = { if (blocked) error("Unavailable") })))
        history.undo(); blocked=true; fails { history.redo() }; val before=history.recentActions()
        history.record("Nothing happened",emptyList())
        assertEquals(before,history.recentActions()); assertEquals("Unavailable",history.lastFailure)
    }
    @Test fun descriptionsAreBoundedWhileRetainingActualNamesAndPaths() {
        val history = FileHistory(engine())
        history.record("Copy",(1..5).map { step("/Demo/Report $it.txt") })
        val detail=history.recentActions().single().detail
        assertTrue(detail.contains("/Demo/Report 1.txt")); assertTrue(detail.contains("/Demo/Report 3.txt"))
        assertFalse(detail.contains("Report 4.txt")); assertTrue(detail.contains("2 more"))
    }
    @Test fun clearingSessionHistoryDropsFailuresAndDoesNotReuseVisibleIds() {
        val history = FileHistory(engine()); history.record("Old",listOf(step(check={ error("Changed") })))
        val old=history.recentActions().single().id; fails { history.undo() }; history.clear()
        assertNull(history.lastFailure); assertNull(history.undoLabel); assertNull(history.redoLabel); assertTrue(history.recentActions().isEmpty())
        history.record("New",listOf(step())); assertTrue(history.recentActions().single().id>old)
    }
}
