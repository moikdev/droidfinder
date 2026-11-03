package dev.findroid

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual descriptors, private-directory sync and kernel no-replace publication on Android. */
class PersistentUndoNativeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var state: File
    private lateinit var workspace: File
    private val fs = AndroidUndoHistoryFiles()
    private val journal get() = File(state,".undo-history/history.json")
    private val pending get() = File(state,".undo-history/pending.txt")
    @Before fun setup() {
        root=File(context.cacheDir,"undo-native-"+UUID.randomUUID()).apply { check(mkdir()) }
        state=File(root,"State").apply { check(mkdir()) };workspace=File(root,"Workspace").apply { check(mkdir()) }
    }
    @After fun cleanup() { if(::root.isInitialized) check(root.deleteRecursively()) }
    private fun history(beforeWrite:(String)->Unit={},files:UndoHistoryFiles=fs) =
        FileHistory(FileEngine(state),UndoHistoryStore(File(state,".undo-history"),files::syncDirectory,beforeWrite),files)
    private fun file(name:String="Original.txt",text:String="original")=File(workspace,name).apply { writeText(text) }
    private fun create(h:FileHistory=history(),name:String="Original.txt"):File {
        h.beginOperation("Create");val value=file(name);h.record("Create",listOf(FileStep.Created(value)));h.finishOperation(true);return value
    }
    private fun fails(work:()->Unit):Exception = try { work();throw AssertionError("Expected protected failure") } catch(error:Exception) { error }
    @Test fun localCopyUndoAndRedoRemainExactAcrossFreshControllers() {
        val value=create();val before=value.readBytes();history().undo();assertFalse(value.exists())
        history().redo();assertArrayEquals(before,value.readBytes());assertFalse(pending.exists())
    }
    @Test fun localRenameChainRetainsNativeIdentityThroughControlledRenames() {
        val h=history();val value=create(h);val target=File(workspace,"Renamed.txt")
        h.beginOperation("Rename");FileEngine(state).relocate(value,target);h.record("Rename",listOf(FileStep.Moved(value,target)));h.finishOperation(true)
        history().undo();history().undo();assertFalse(value.exists());history().redo();history().redo()
        assertEquals("original",target.readText());assertFalse(value.exists())
    }
    @Test fun trashAndRestoreKeepTheirOwnedRecoveryMappings() {
        val value=file();val engine=FileEngine(state);val item=engine.trash(value)
        history().record("Trash",listOf(FileStep.Trashed(value,item)));history().undo();assertEquals("original",value.readText())
        history().redo();assertFalse(value.exists());assertEquals("original",FileEngine(state).trashItems().single().readText())
    }
    @Test fun unicodePublicationDoesNotChangeNonBmpNames() {
        val value=create(name="Résumé 📁 文.txt");history().undo();history().redo();assertEquals("original",value.readText())
    }
    @Test fun exactRestoreRejectsAnOriginalPathRemappedInTheTrashIndex() {
        val h=history();val value=create(h);h.undo();val engine=FileEngine(state);val item=engine.trashItems().single()
        val other=File(workspace,"Other.txt");engine.rememberOriginal(item,other.path,workspace.path)
        fails { history().redo() };assertFalse(value.exists());assertFalse(other.exists());assertEquals("original",item.readText())
    }
    @Test fun sameByteReplacementWithPreservedMtimeIsRejected() {
        val value=create();val oldTime=value.lastModified();val other=file("Replacement.txt");check(other.setLastModified(oldTime))
        Os.rename(other.path,value.path);fails { history().undo() };assertEquals("original",value.readText());assertFalse(pending.exists())
    }
    @Test fun sameInodeRewriteAndRestoredMtimeIsRejectedByCtime() {
        val value=create();val time=Files.getLastModifiedTime(value.toPath());value.writeText("original");Files.setLastModifiedTime(value.toPath(),time)
        fails { history().undo() };assertTrue(value.exists())
    }
    @Test fun finalSymlinkNeverReadsOrMovesTheOutsideTarget() {
        val value=create();val target=file("Outside.txt","keep");check(value.delete());Os.symlink(target.path,value.path)
        fails { history().undo() };assertEquals("keep",target.readText());assertTrue(Files.isSymbolicLink(value.toPath()))
    }
    @Test fun fifoReplacementIsRejectedBeforeFingerprintCanBlock() {
        val value=create();check(value.delete());Os.mkfifo(value.path,0b110000000)
        val started=android.os.SystemClock.elapsedRealtime();fails { history().undo() }
        assertTrue(android.os.SystemClock.elapsedRealtime()-started<3_000);assertTrue(OsConstants.S_ISFIFO(Os.lstat(value.path).st_mode))
    }
    @Test fun redirectedParentIsRejectedEvenWhenItContainsTheOriginalInode() {
        val parent=File(workspace,"Folder").apply { mkdir() };val value=File(parent,"Draft.txt").apply { writeText("draft") }
        history().record("Create",listOf(FileStep.Created(value)));val moved=File(workspace,"Moved");check(parent.renameTo(moved));Os.symlink(moved.path,parent.path)
        fails { history().undo() };assertEquals("draft",value.readText())
    }
    @Test fun occupiedDestinationIsNeverOverwritten() {
        val source=file();val target=File(workspace,"Moved.txt");FileEngine(state).relocate(source,target)
        history().record("Move",listOf(FileStep.Moved(source,target)));source.writeText("keep")
        fails { history().undo() };assertEquals("keep",source.readText());assertEquals("original",target.readText())
    }
    @Test fun kernelCollisionAfterPreflightKeepsBothFiles() {
        val source=file();val target=File(workspace,"Moved.txt");FileEngine(state).relocate(source,target)
        history().record("Move",listOf(FileStep.Moved(source,target)))
        val race=object:UndoHistoryFiles by fs { override fun moveNoReplace(from:File,to:File) { to.writeText("late arrival");fs.moveNoReplace(from,to) } }
        assertTrue(fails { history(files=race).undo() } is HistoryFailure)
        assertEquals("late arrival",source.readText());assertEquals("original",target.readText());assertNotNull(history().recoveryProblem)
    }
    @Test fun kernelDirectoryCollisionAlsoPreservesTheExistingFolder() {
        val source=File(workspace,"Folder").apply { mkdir() };File(source,"Keep.txt").writeText("source")
        val target=File(workspace,"Moved");FileEngine(state).relocate(source,target);history().record("Move folder",listOf(FileStep.Moved(source,target)))
        val race=object:UndoHistoryFiles by fs { override fun moveNoReplace(from:File,to:File) { to.mkdir();File(to,"Keep.txt").writeText("late");fs.moveNoReplace(from,to) } }
        fails { history(files=race).undo() };assertEquals("late",File(source,"Keep.txt").readText());assertEquals("source",File(target,"Keep.txt").readText())
    }
    @Test fun stableAndroidDataAliasCanBeReopened() {
        val value=create();val alias=File(value.path.replace("/data/user/0/","/data/data/"))
        assertEquals(value.canonicalPath,alias.canonicalPath);assertEquals("original",alias.readText());history().undo();assertFalse(alias.exists())
    }
    @Test fun anInterruptedDeferredUndoRemainsManualAndDoesNotRecreateTheDocument() {
        val h=history();val value=create(h);h.undoDeferred();val bytes=journal.readBytes()
        val reopened=history();assertNotNull(reopened.recoveryProblem);assertNull(reopened.undoLabel);assertNull(reopened.redoLabel)
        assertFalse(value.exists());assertArrayEquals(bytes,journal.readBytes());assertEquals("original",FileEngine(state).trashItems().single().readText())
    }
    @Test fun failedInitialMarkerWritePreventsMutation() {
        val value=create();fails { history(beforeWrite={ if(it=="pending.txt") error("No space") }).undo() }
        assertEquals("original",value.readText())
    }
    @Test fun failedReadySnapshotLeavesTheDurableMarker() {
        val value=create();fails { history(beforeWrite={ if(it=="history.json") error("No space") }).undo() }
        assertFalse(value.exists());assertTrue(pending.exists());assertNotNull(history().recoveryProblem)
    }
    @Test fun failedMetadataCompletionLeavesTheDurableMarker() {
        val h=history();create(h);h.undoDeferred();h.finishOperation(false);assertNotNull(history().recoveryProblem);assertTrue(pending.exists())
    }
    @Test fun corruptJournalIsPreservedForManualReset() {
        val value=create();journal.writeText("broken");val h=history();assertNotNull(h.recoveryProblem)
        val preserved=h.resetSavedHistory()!!;assertEquals("broken",File(state,preserved+"/history.json").readText());assertEquals("original",value.readText())
    }
    @Test fun resettingReferencesNeverDeletesTrashDocuments() {
        val h=history();create(h);h.undo();val item=FileEngine(state).trashItems().single();val bytes=item.readBytes();h.resetSavedHistory()
        assertArrayEquals(bytes,item.readBytes());assertTrue(history().recentActions().isEmpty())
    }
    @Test fun unsupportedSpecialFilesRemainSessionOnly() {
        val value=file();history().record("Create",listOf(FileStep.Created(value)));val symlink=File(workspace,"Link");Os.symlink(value.path,symlink.path)
        val h=history();h.record("Create link",listOf(FileStep.Created(symlink)))
        assertNull(h.recoveryProblem);assertFalse(h.recentActions().first().savedForRestart);assertTrue(history().recentActions().isEmpty());assertEquals("original",value.readText())
    }
    @Test fun savedPrivatePathsAreNotCreatedOrHashedDuringDecode() {
        val value=create();check(value.delete());val before=journal.readBytes();val reopened=history()
        assertNull(reopened.recoveryProblem);assertNotNull(reopened.undoLabel);assertFalse(value.exists());assertArrayEquals(before,journal.readBytes())
    }
}
