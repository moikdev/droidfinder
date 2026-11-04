package dev.findroid

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real host files exercise serialization/reopen and failure boundaries; kernel publication has native cases. */
class PersistentUndoTest {
    @get:Rule val temporary = TemporaryFolder()
    private val state by lazy { temporary.newFolder("state") }
    private val workspace by lazy { temporary.newFolder("Workspace") }
    private val directory get() = File(state, ".undo-history")
    private val journal get() = File(directory, "history.json")
    private val marker get() = File(directory, "pending.txt")
    private val fs = HostUndoFiles()
    private fun open(beforeWrite: (String) -> Unit = {}, fileSystem: UndoHistoryFiles = fs): FileHistory =
        FileHistory(FileEngine(state), UndoHistoryStore(directory, fileSystem::syncDirectory, beforeWrite), fileSystem)
    private fun file(name: String = "Draft.txt", text: String = "original") = File(workspace, name).apply { writeText(text) }
    private fun fails(work: () -> Unit): Exception = try { work(); error("Expected a protected failure") } catch (error: Exception) {
        if (error.message == "Expected a protected failure") throw error
        error
    }
    private fun create(history: FileHistory, name: String = "Draft.txt"): File {
        history.beginOperation("Create")
        val value = file(name)
        history.record("Create $name", listOf(FileStep.Created(value)))
        history.finishOperation(true)
        return value
    }
    private fun callback() = FileStep.Callback(check = {}, perform = { emptyList() })
    private fun saved() = UndoHistoryJson.decode(journal.readText())

    @Test fun checkpointKeepsThePendingBarrierAndNeverReplaysAfterAReopen() {
        val history = open(); history.beginOperation("Reviewed sync")
        val value = file(); history.record("Reviewed sync", listOf(FileStep.Created(value)))
        history.checkpointOperation()
        assertTrue(marker.exists()); assertEquals("Reviewed sync", saved().undo.single().label)
        val reopened = open()
        assertNotNull(reopened.recoveryProblem); assertNull(reopened.undoLabel)
        assertEquals("original", value.readText()); assertTrue(marker.exists())
    }
    @Test fun failedCheckpointRetainsItsMarkerAndCannotBeFinalized() {
        var stop = false
        val history = open(beforeWrite = { if (stop && it == "history.json") error("Synthetic checkpoint failure") })
        history.beginOperation("Reviewed sync"); val value = file()
        history.record("Reviewed sync", listOf(FileStep.Created(value))); stop = true
        fails { history.checkpointOperation() }; stop = false
        fails { history.finishOperation(true) }
        assertTrue(marker.exists()); assertNotNull(open().recoveryProblem); assertEquals("original", value.readText())
    }

    @Test fun checkpointCanBeFinalizedOnlyAfterTheEnclosingOperationSucceeds() {
        val history = open(); history.beginOperation("Reviewed sync")
        val value = file(); history.record("Reviewed sync", listOf(FileStep.Created(value)))
        history.checkpointOperation(); history.finishOperation(true)
        assertFalse(marker.exists()); assertNull(open().recoveryProblem)
        open().undo(); assertFalse(value.exists())
    }

    @Test fun restartNeverReplaysAndUndoRedoSurviveSeparateReopens() {
        val value = create(open())
        val reopened = open(); assertTrue(value.isFile); assertEquals("Create Draft.txt", reopened.undoLabel)
        assertTrue(reopened.recentActions().single().savedForRestart)
        reopened.undo(); assertFalse(value.exists())
        val again = open(); assertFalse(value.exists()); assertEquals("Create Draft.txt", again.redoLabel)
        again.redo(); assertEquals("original", value.readText()); assertFalse(marker.exists())
    }
    @Test fun createRenameTrashChainCanBeUndoneAndRedoneAcrossRestarts() {
        var history = open(); val value = create(history); val renamed = File(workspace,"Renamed.txt")
        history.beginOperation("Rename"); FileEngine(state).relocate(value,renamed)
        history.record("Rename",listOf(FileStep.Moved(value,renamed))); history.finishOperation(true)
        history.beginOperation("Trash"); val item = FileEngine(state).trash(renamed)
        history.record("Trash",listOf(FileStep.Trashed(renamed,item))); history.finishOperation(true)
        repeat(3) { history = open(); history.undo() }
        assertFalse(value.exists()); assertFalse(renamed.exists())
        repeat(3) { history = open(); history.redo() }
        assertFalse(value.exists()); assertFalse(renamed.exists()); assertEquals("original",FileEngine(state).trashItems().single().readText())
    }
    @Test fun moveUsesItsOriginalParentAfterReopen() {
        val history = open(); val source = file(); val destination = File(temporary.newFolder("Destination"),source.name)
        FileEngine(state).relocate(source,destination); history.record("Move",listOf(FileStep.Moved(source,destination)))
        open().undo(); assertEquals("original",source.readText()); assertFalse(destination.exists())
        open().redo(); assertFalse(source.exists()); assertEquals("original",destination.readText())
    }
    @Test fun renamedCyclesRestoreWithoutFingerprintingVacantOriginalNamesDuringLoad() {
        val first = file("First.txt","one"); val second = file("Second.txt","two"); val engine = FileEngine(state)
        val pairs = engine.renameBatch(listOf(RenameProposal(first,second.name),RenameProposal(second,first.name)))
        open().record("Swap",listOf(FileStep.Renamed(pairs)))
        open().undo(); assertEquals("one",first.readText()); assertEquals("two",second.readText())
        open().redo(); assertEquals("two",first.readText()); assertEquals("one",second.readText())
    }
    @Test fun groupedFolderAndItsPrivateEmptyRecoverySurviveReopen() {
        val one=file("One.txt","one");val two=file("Two.txt","two");val folder=File(workspace,"Group").apply { mkdir() };val engine=FileEngine(state)
        val pairs=listOf(one,two).map { it to engine.relocate(it,File(folder,it.name)) }
        open().record("Group",listOf(FileStep.Grouped(folder,pairs)))
        open().undo();assertFalse(folder.exists());assertEquals("one",one.readText());assertEquals("two",two.readText())
        open().redo();assertTrue(folder.isDirectory);assertEquals("two",File(folder,two.name).readText())
    }
    @Test fun restoreActionKeepsRedoLocationThroughItsOwnedTrashBucket() {
        val engine=FileEngine(state);val value=file();val item=engine.trash(value);engine.restore(item)
        open().record("Restore",listOf(FileStep.Restored(value)))
        open().undo();assertFalse(value.exists());open().redo();assertEquals("original",value.readText())
    }
    @Test fun parentAndChildActionsStayOrderedAcrossReopen() {
        var history=open();history.beginOperation("Folder");val folder=File(workspace,"Folder").apply { mkdir() }
        history.record("Folder",listOf(FileStep.Created(folder)));history.finishOperation(true)
        history.beginOperation("Child");val child=File(folder,"Child.txt").apply { writeText("child") }
        history.record("Child",listOf(FileStep.Created(child)));history.finishOperation(true)
        history=open();history.undo();history=open();history.undo();assertFalse(folder.exists())
        history=open();history.redo();history=open();history.redo();assertEquals("child",child.readText())
    }
    @Test fun opaqueActionFormsAnUndoBoundaryWithoutSkippingOlderLocalCommands() {
        val history=open();create(history,"Older.txt");history.record("Tags",listOf(callback()));create(history,"Newer.txt")
        val reopened=open();assertEquals(listOf("Create Newer.txt"),reopened.recentActions().map { it.label })
        reopened.undo();assertNull(reopened.undoLabel);assertTrue(File(workspace,"Older.txt").exists())
        assertEquals(2,history.recentActions().count { !it.savedForRestart })
    }
    @Test fun opaqueRedoBoundaryNeverExposesCommandsBeyondIt() {
        val history=open();create(history,"Older.txt");history.record("Tags",listOf(callback()));create(history,"Newer.txt")
        history.undo();history.undo()
        val reopened=open();assertNull(reopened.undoLabel);assertNull(reopened.redoLabel)
        assertFalse(File(workspace,"Newer.txt").exists())
    }
    @Test fun actionsHiddenByAnOpaqueBoundaryStaySessionOnlyEvenAfterThatBoundaryIsUndone() {
        val history=open();history.record("Old tags",listOf(callback()));create(history,"Middle.txt")
        history.record("New tags",listOf(callback()));create(history,"Latest.txt")
        history.undo();history.undo();history.undo()
        val reopened=open();assertNull(reopened.undoLabel);assertNull(reopened.redoLabel)
        assertEquals("Create Middle.txt",history.redoLabel);assertFalse(File(workspace,"Latest.txt").exists())
    }
    @Test fun undoingACallbackDoesNotInventNewIdentityProofsForAnOlderAction() {
        val history=open();val older=create(history,"Older.txt");history.record("Tags",listOf(callback()))
        val replacement=file("Replacement.txt");assertTrue(replacement.setLastModified(older.lastModified()))
        Files.move(replacement.toPath(),older.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        history.undo()
        assertEquals("Create Older.txt",history.undoLabel)
        assertTrue(open().recentActions().isEmpty());assertFalse(history.recentActions().first { it.label=="Create Older.txt" }.savedForRestart)
    }
    @Test fun aMixedLocalAndCallbackCommandStaysEntirelyInTheSession() {
        val history=open();val value=file();history.record("Mixed",listOf(FileStep.Created(value),callback()))
        assertFalse(history.recentActions().single().savedForRestart);assertTrue(open().recentActions().isEmpty());assertTrue(value.exists())
    }
    @Test fun changedBytesStopBeforeAnyUndoMutation() {
        val value=create(open());value.writeText("later edits")
        val history=open();fails { history.undo() };assertEquals("later edits",value.readText());assertFalse(marker.exists());assertNotNull(history.lastFailure)
    }
    @Test fun sameByteReplacementWithTheSameTimeStillHasTheWrongIdentity() {
        val value=create(open());val time=value.lastModified();val replacement=file("Replacement.txt")
        assertTrue(replacement.setLastModified(time));Files.move(replacement.toPath(),value.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val history=open();fails { history.undo() };assertEquals("original",value.readText());assertFalse(marker.exists())
    }
    @Test fun modifiedThenRestoredBytesAndMtimeDoNotEraseTheChangeTimeGuard() {
        val value=create(open());val old=Files.getLastModifiedTime(value.toPath());value.writeText("original")
        Files.setLastModifiedTime(value.toPath(),old)
        fails { open().undo() };assertTrue(value.exists())
    }
    @Test fun finalSymlinkIsRejectedWithoutReadingItsTarget() {
        val value=create(open());val outside=file("Outside.txt","keep");assertTrue(value.delete());Files.createSymbolicLink(value.toPath(),outside.toPath())
        fails { open().undo() };assertEquals("keep",outside.readText());assertTrue(Files.isSymbolicLink(value.toPath()))
    }
    @Test fun parentRedirectionStopsBeforeChangingEitherDirectory() {
        val folder=temporary.newFolder("Parent");val value=File(folder,"Draft.txt").apply { writeText("original") }
        open().record("Create",listOf(FileStep.Created(value)));val moved=File(folder.parentFile,"Moved");assertTrue(folder.renameTo(moved))
        Files.createSymbolicLink(folder.toPath(),moved.toPath());fails { open().undo() };assertEquals("original",value.readText())
    }
    @Test fun anOccupiedOriginalNameIsKeptOnUndo() {
        val source=file();val target=File(workspace,"Moved.txt");FileEngine(state).relocate(source,target)
        open().record("Move",listOf(FileStep.Moved(source,target)));source.writeText("occupied")
        fails { open().undo() };assertEquals("occupied",source.readText());assertEquals("original",target.readText());assertFalse(marker.exists())
    }
    @Test fun recreatedDestinationParentCannotAcquireAnOldUndoPromise() {
        val parent=temporary.newFolder("Original");val source=File(parent,"Draft.txt").apply { writeText("original") };val target=File(workspace,source.name)
        FileEngine(state).relocate(source,target);open().record("Move",listOf(FileStep.Moved(source,target)))
        assertTrue(parent.delete());assertTrue(parent.mkdir());fails { open().undo() };assertTrue(target.exists());assertFalse(source.exists())
    }
    @Test fun missingFilesAreDecodedWithoutFingerprintingOrAutomaticRecovery() {
        val value=create(open());val encoded=journal.readBytes();assertTrue(value.delete())
        val history=open();assertNull(history.recoveryProblem);assertEquals("Create Draft.txt",history.undoLabel);assertArrayEquals(encoded,journal.readBytes())
        fails { history.undo() };assertFalse(value.exists())
    }
    @Test fun processDeathAfterTheMarkerKeepsTheTimelineReadOnly() {
        val history=open();val value=create(history);history.beginOperation("Unfinished rename")
        val reopened=open();assertNotNull(reopened.recoveryProblem);assertNull(reopened.undoLabel);assertNull(reopened.redoLabel)
        assertEquals("original",value.readText());assertTrue(marker.isFile);assertFalse(reopened.recentActions().single().nextUndo)
    }
    @Test fun deferredUndoKeepsTheMarkerUntilMetadataHasFinished() {
        val history=open();val value=create(history);history.undoDeferred();assertFalse(value.exists());assertTrue(marker.isFile)
        val interrupted=open();assertNotNull(interrupted.recoveryProblem);assertNull(interrupted.redoLabel)
        history.finishOperation(true);val complete=open();assertNull(complete.recoveryProblem);assertNotNull(complete.redoLabel)
    }
    @Test fun metadataFailureAfterUndoRemainsManualOnRestart() {
        val history=open();val value=create(history);history.undoDeferred();history.finishOperation(false)
        val reopened=open();assertNotNull(reopened.recoveryProblem);assertFalse(value.exists());assertEquals("original",FileEngine(state).trashItems().single().readText())
    }
    @Test fun prewriteFailurePreventsTheFileMutation() {
        val value=create(open());val history=open(beforeWrite={ if(it=="pending.txt") error("Disk full") })
        fails { history.undo() };assertEquals("original",value.readText());assertNotNull(history.recoveryProblem)
    }
    @Test fun snapshotWriteFailureRetainsThePendingMarkerAndRecoveryFile() {
        val value=create(open());val history=open(beforeWrite={ if(it=="history.json") error("Disk full") })
        fails { history.undo() };assertFalse(value.exists());assertTrue(marker.isFile);assertNotNull(open().recoveryProblem)
        assertEquals("original",FileEngine(state).trashItems().single().readText())
    }
    @Test fun completionFailureCannotExposeRedoOnRestart() {
        create(open());val history=open(beforeWrite={ if(it=="complete") error("Completion failed") })
        fails { history.undo() };assertTrue(marker.exists());assertNull(open().redoLabel)
    }
    @Test fun corruptedSnapshotIsKeptAndNeverReplacedByAnOlderBackup() {
        create(open());val good=journal.readBytes();File(directory,"history.json.bak").writeBytes(good);journal.writeText("broken")
        val history=open();assertNotNull(history.recoveryProblem);assertTrue(history.recentActions().isEmpty());assertEquals("broken",journal.readText())
        history.record("Session action",listOf(callback()));assertEquals("broken",journal.readText());assertNull(history.undoLabel)
    }
    @Test fun unexpectedWriteStagingIsEvidenceOfAnInterruptedSave() {
        val value=create(open());File(directory,".write-crashed").writeText("partial")
        assertNotNull(open().recoveryProblem);assertEquals("original",value.readText())
    }
    @Test fun resetPreservesTheWholeOldJournalAndEveryTrashDocument() {
        val history=open();val value=create(history);history.undoDeferred();history.finishOperation(false)
        val before=directory.listFiles()!!.associate { it.name to it.readBytes() };val trash=FileEngine(state).trashItems().single();val bytes=trash.readBytes()
        val preserved=history.resetSavedHistory()!!;assertFalse(directory.exists());val old=File(state,preserved)
        before.forEach { (name,data) -> assertArrayEquals(data,File(old,name).readBytes()) };assertArrayEquals(bytes,trash.readBytes())
        assertFalse(value.exists());assertNull(history.recoveryProblem);assertTrue(history.recentActions().isEmpty());assertNull(open().recoveryProblem)
    }
    @Test fun clearingReferencesNeverDeletesTrashItems() {
        val history=open();create(history);history.undo();val item=FileEngine(state).trashItems().single();history.clear()
        assertEquals("original",item.readText());assertTrue(open().recentActions().isEmpty())
    }
    @Test fun retainingFiftyActionsDoesNotPurgeOldRecoveryDocuments() {
        val history=open();val value=create(history);history.undo();val item=FileEngine(state).trashItems().single()
        repeat(51) { create(history,"Draft $it.txt") }
        assertEquals(50,open().recentActions().size);assertEquals("original",item.readText());assertFalse(value.exists())
    }
    @Test fun journalSymlinksNeverReadOrOverwriteAnOutsideFile() {
        directory.mkdirs();val outside=file("Outside.txt","private");Files.createSymbolicLink(journal.toPath(),outside.toPath())
        val history=open();assertNotNull(history.recoveryProblem);history.clear();assertEquals("private",outside.readText());assertTrue(Files.isSymbolicLink(journal.toPath()))
    }
    @Test fun directorySymlinksCannotBeResetByFollowingTheTarget() {
        val outside=temporary.newFolder("Outside");File(outside,"keep").writeText("keep");Files.createSymbolicLink(directory.toPath(),outside.toPath())
        val history=open();assertNotNull(history.recoveryProblem);fails { history.resetSavedHistory() };assertEquals("keep",File(outside,"keep").readText())
    }
    @Test fun unsupportedStorageMakesTheCommandSessionOnly() {
        val unsupported=object: UndoHistoryFiles by fs { override fun sync(paths:Set<String>) { if(paths.isNotEmpty()) error("Unsupported storage") } }
        val history=open(fileSystem=unsupported);val value=file();history.record("Create",listOf(FileStep.Created(value)))
        assertNull(history.recoveryProblem);assertFalse(history.recentActions().single().savedForRestart);assertNotNull(history.persistenceNotice)
        assertTrue(open().recentActions().isEmpty());assertEquals("original",value.readText())
    }
    @Test fun changedUnrelatedSavedFilesAreNotBlessedByANewOperation() {
        val history=open();val first=create(history,"First.txt");first.writeText("later edits")
        create(history,"Second.txt");assertNotNull(history.recoveryProblem);assertNull(open().undoLabel)
        assertEquals("later edits",first.readText());assertTrue(File(workspace,"Second.txt").exists())
    }
    @Test fun partialMultiStepUndoRemainsReadOnlyAfterFailure() {
        val base=fs;var moves=0
        val failing=object: UndoHistoryFiles by base { override fun moveNoReplace(from:File,to:File) {
            if(++moves>=2) error("Injected second move failure");base.moveNoReplace(from,to)
        } }
        val history=open(fileSystem=failing);val one=file("One.txt");val two=file("Two.txt")
        history.record("Create two",listOf(FileStep.Created(one),FileStep.Created(two)))
        assertTrue(fails { history.undo() } is HistoryFailure);assertTrue(marker.isFile)
        val reopened=open();assertNotNull(reopened.recoveryProblem);assertNull(reopened.undoLabel);assertNull(reopened.redoLabel)
        assertTrue(one.exists());assertFalse(two.exists());assertEquals("original",FileEngine(state).trashItems().single().readText())
    }
    @Test fun decimalStringsPreserveIdentitiesAboveTheJsonIntegerPrecisionLimit() {
        create(open());val state=saved();val proof=state.proofs.single();val huge=9_007_199_254_740_993L
        val stamp=proof.entries.values.single().copy(inode=huge);val altered=state.copy(nextId=huge,undo=state.undo.map { it.copy(id=huge-1,time=huge) },
            proofs=listOf(proof.copy(anchorInode=huge,entries=mapOf(proof.path to stamp))))
        assertEquals(altered,UndoHistoryJson.decode(UndoHistoryJson.encode(altered)))
    }
    @Test fun duplicateFieldsTrailingValuesAndUnknownSchemasAreRejected() {
        create(open());val text=journal.readText()
        listOf(text.replace("\"schema\":1","\"schema\":1,\"schema\":1"),text+" {}",text.replace("\"schema\":1","\"schema\":2"),text.replace("\"nextId\":\"2\"","\"nextId\":2")).forEach { fails { UndoHistoryJson.decode(it) } }
    }
    @Test fun unknownFieldsAndRoundedOrNonCanonicalNumbersAreRejected() {
        create(open());val text=journal.readText()
        listOf(text.replace("\"schema\":1","\"schema\":1,\"extra\":true"),text.replace("\"nextId\":\"2\"","\"nextId\":\"02\""),text.replace("\"nextId\":\"2\"","\"nextId\":\"2.0\"")).forEach { fails { UndoHistoryJson.decode(it) } }
    }
    @Test fun wrongCursorAndMissingRecoveryStateAreRejectedBeforeLoadingSteps() {
        val history=open();create(history);history.undo();val state=saved()
        fails { UndoHistoryJson.encode(state.copy(redo=state.redo.map { it.copy(applied=listOf(true)) })) }
        fails { UndoHistoryJson.encode(state.copy(redo=state.redo.map { it.copy(steps=it.steps.map { step -> step.copy(recovery=null) }) })) }
    }
    @Test fun traversalAndInvalidNativeIdentitiesAreRejected() {
        create(open());val state=saved();val proof=state.proofs.single()
        fails { UndoHistoryJson.encode(state.copy(proofs=listOf(proof.copy(path=workspace.path+"/../Draft.txt")))) }
        fails { UndoHistoryJson.encode(state.copy(proofs=listOf(proof.copy(anchorInode=0)))) }
        fails { UndoHistoryJson.encode(state.copy(proofs=listOf(proof.copy(entries=proof.entries.mapValues { it.value.copy(regular=false,directory=false) })))) }
    }
    @Test fun anOversizedLocalCommandStaysUsableInTheSessionWithoutBlockingSavedUndo() {
        val history=open();create(history,"Older.txt")
        val values=(0..256).map { file("Bulk $it.txt","") }
        history.record("Large copy",values.map { FileStep.Created(it) })
        assertNull(history.recoveryProblem);assertEquals("Large copy",history.undoLabel)
        assertTrue(open().recentActions().isEmpty());assertFalse(history.recentActions().first().savedForRestart)
        assertTrue(values.all { it.isFile });assertTrue(File(workspace,"Older.txt").exists())
    }
    @Test fun thePersistedPathBudgetRetiresOnlyTheOldestReferences() {
        val history=open();val first=file("Keep oldest.txt","")
        repeat(49) { batch ->
            val values=(0 until 84).map { item -> if(batch==0 && item==0) first else file("Batch $batch item $item.txt","") }
            history.record("Batch $batch",values.map { FileStep.Created(it) })
        }
        assertNull(history.recoveryProblem);assertEquals(49,history.recentActions().size)
        assertEquals(48,open().recentActions().size);assertFalse(history.recentActions().last().savedForRestart)
        assertTrue(first.exists());assertTrue(File(workspace,"Batch 48 item 83.txt").exists())
    }
    @Test fun oversizedUtf8JournalsStopAtTheByteLimitWithoutTruncatingARecord() {
        create(open());val state=saved();val proof=state.proofs.single();val stamp=proof.entries.values.single()
        val root=File(workspace,"Deep folder").path;val prefix=root+"/"+("a".repeat(125)+"/").repeat(30)
        val entries=linkedMapOf(root to stamp.copy(regular=false,directory=true))
        repeat(2300) { entries[prefix+"Item $it.txt"]=stamp }
        val command=state.undo.single().copy(steps=listOf(LocalUndoStep(LocalUndoKind.CREATED,listOf(root),listOf("0".repeat(64)))))
        val deep=state.copy(undo=listOf(command),proofs=listOf(proof.copy(path=root,resolved=root,entries=entries)))
        assertTrue(fails { UndoHistoryJson.encode(deep) } is UndoHistoryCapacityException)
        assertEquals(state,saved())
    }
    @Test fun aFailedReviewSnapshotStillMakesTheLiveTimelineReadOnly() {
        val value=create(open());val before=journal.readBytes()
        val history=open(beforeWrite={ if(it=="history.json") error("No space for review snapshot") })
        value.writeText("Keep later edits")
        fails { history.beginOperation("New operation") }
        assertNotNull(history.recoveryProblem);assertNull(history.undoLabel);assertNull(history.redoLabel)
        assertTrue(marker.exists());assertEquals("Keep later edits",value.readText());assertArrayEquals(before,journal.readBytes())
    }
    @Test fun failedValidationLeavesTheReadyJournalByteForByteUnchanged() {
        val value=create(open());val before=journal.readBytes();value.writeText("edited")
        fails { open().undo() };assertArrayEquals(before,journal.readBytes());assertFalse(marker.exists())
    }
    @Test fun resetDoesNotReuseIdsInTheSameProcess() {
        val history=open();create(history);val id=history.recentActions().single().id;history.resetSavedHistory();create(history,"Another.txt")
        assertTrue(history.recentActions().single().id>id)
    }
}

private class HostUndoFiles : UndoHistoryFiles {
    private fun stamp(file: File): PartialFileStamp? {
        if (!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)) return null
        val attributes=Files.readAttributes(file.toPath(),"unix:*",LinkOption.NOFOLLOW_LINKS)
        fun number(key:String)=(attributes[key] as Number).toLong()
        fun time(key:String)=(attributes[key] as FileTime).to(TimeUnit.NANOSECONDS)
        val modified=time("lastModifiedTime");val changed=time("ctime")
        return PartialFileStamp(number("dev"),number("ino"),number("size"),modified/1_000_000_000,modified%1_000_000_000,changed/1_000_000_000,changed%1_000_000_000,
            attributes["isRegularFile"] as Boolean,attributes["isDirectory"] as Boolean,number("nlink"))
    }
    override fun observe(path:String):UndoPathProof {
        var anchor=File(path).parentFile!!;while(stamp(anchor)==null) anchor=anchor.parentFile!!
        val parent=stamp(anchor)!!;check(parent.directory)
        val entries=linkedMapOf<String,PartialFileStamp>()
        fun visit(file:File) { val value=stamp(file)?:return;check(value.regular||value.directory);entries[file.path]=value
            if(value.directory) file.listFiles()!!.sortedBy { it.name }.forEach(::visit)
        }
        visit(File(path));return UndoPathProof(path,File(path).canonicalPath,anchor.path,anchor.canonicalPath,parent.device,parent.inode,entries)
    }
    override fun sync(paths:Set<String>) { paths.forEach(::observe) }
    override fun syncDirectory(directory:File) {}
    override fun moveNoReplace(from:File,to:File) { Files.move(from.toPath(),to.toPath()) }
}
