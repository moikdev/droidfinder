package dev.findroid

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderSyncTrashTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)}
    private inner class Fixture(folder:Boolean=false) {
        val base=temporary.newFolder()
        val state=File(base,"State").apply {mkdir()}
        val source=File(base,"Original").apply {if(folder){mkdir();File(this,"Keep.txt").writeText("reviewed original")}else writeText("reviewed original")}
        val storage=FolderSyncNioStorage()
        val files=FolderSyncNioProofs(storage)
        val engine=FileEngine(state)
        val expected=files.observe(source.path)
        val hashes=expected.entries.filterValues {it.regular}.keys.associateWith {digest(File(it))}
        fun move(control:TransferControl=TransferControl(),recordIntent:(FolderSyncPreservationIntent)->Unit={})=
            engine.trashReviewedByRename(expected,hashes,files,storage,control,recordIntent=recordIntent)
    }
    private fun stopped(work:()->Unit):FolderSyncRecoveryFailure {
        try{work();fail("Expected recovery refusal")}catch(error:FolderSyncRecoveryFailure){return error}
        error("Unreachable")
    }
    @Test fun sameFilesystemOriginalGetsAnExactDurableTrashMapping() {
        val f=Fixture();val bytes=f.source.readBytes();val saved=f.move()
        assertFalse(f.source.exists());assertArrayEquals(bytes,saved.readBytes())
        assertEquals(f.source.path,f.engine.originalPath(saved));assertEquals(f.source.path,FileEngine(f.state).originalPath(saved))
        assertTrue(f.engine.ownsRecovery(saved));assertEquals(f.expected.entries.getValue(f.source.path).inode,f.storage.stat(saved.path)!!.inode)
    }
    @Test fun aDisclosedFolderMovesAsOneOriginalWithoutRecursivelyCopyingIt() {
        val f=Fixture(true);val saved=f.move()
        assertFalse(f.source.exists());assertEquals("reviewed original",File(saved,"Keep.txt").readText())
        assertEquals(f.expected.entries.getValue(f.source.path+"/Keep.txt").inode,f.storage.stat(saved.path+"/Keep.txt")!!.inode)
    }
    @Test fun crossFilesystemTrashIsRefusedBeforeCreatingABucketOrReadingNewData() {
        val f=Fixture();val trash=File(f.state,"Trash")
        f.storage.overrideStamp={path,s->if(path==trash.path)s.copy(device=s.device+1)else s}
        val error=stopped{f.move()}
        assertNull(error.recovery);assertEquals("reviewed original",f.source.readText());assertTrue(trash.listFiles()!!.isEmpty())
    }
    @Test fun staleReviewedBytesAreNeverMovedToTrash() {
        val f=Fixture();f.source.writeText("later edit")
        val error=stopped{f.move()}
        assertNull(error.recovery);assertEquals("later edit",f.source.readText());assertTrue(f.engine.trashItems().isEmpty())
    }
    @Test fun aRecoveryNameCollisionPreservesBothTheLiveOriginalAndTheOccupant() {
        val f=Fixture();var occupant:File?=null
        f.files.beforeRename={_,target->occupant=target;target.writeText("keep occupant")}
        val error=stopped{f.move()}
        assertNull(error.recovery);assertEquals("reviewed original",f.source.readText());assertEquals("keep occupant",occupant!!.readText())
    }
    @Test fun aFaultAfterTheRenameReportsThePreservedOriginalAndKeepsItsMapping() {
        val f=Fixture();f.files.failFinalSync=true
        val error=stopped{f.move()};val saved=error.recovery
        assertNotNull(saved);assertFalse(f.source.exists());assertEquals("reviewed original",saved!!.readText())
        assertEquals(f.source.path,FileEngine(f.state).originalPath(saved))
    }
    @Test fun aLastMomentRewriteIsPreservedInTrashAndRefusesFurtherSynchronization() {
        val f=Fixture();f.files.beforeRename={source,_->source.writeText("latest original edit")}
        val error=stopped{f.move()};val saved=error.recovery
        assertNotNull(saved);assertEquals("latest original edit",saved!!.readText());assertEquals(f.source.path,f.engine.originalPath(saved))
    }
    @Test fun recoveryCannotMoveTrashIntoItselfOrAnAncestorEvenWithoutAPreflightToken() {
        val f = Fixture(); val trash = File(f.state, "Trash")
        val ancestor = f.files.observe(f.state.path)
        stopped { f.engine.trashReviewedByRename(ancestor, emptyMap(), f.files, f.storage) }
        assertTrue(trash.listFiles()!!.isEmpty()); assertFalse(File(f.state, "trash.properties").exists())
        val original = File(trash, "Kept.txt").apply { writeText("keep recovery bytes") }
        val proof = f.files.observe(original.path)
        stopped { f.engine.trashReviewedByRename(proof, mapOf(original.path to digest(original)), f.files, f.storage) }
        assertEquals("keep recovery bytes", original.readText()); assertEquals(listOf(original), trash.listFiles()!!.toList())
        assertFalse(File(f.state, "trash.properties").exists())
    }
    @Test fun theExactRecoveryIntentIsRecordedAfterTheDurableMappingAndBeforeTheOriginalMoves() {
        val f = Fixture(); var intent: FolderSyncPreservationIntent? = null
        f.files.beforeRename = { source, target -> assertEquals(FolderSyncPreservationIntent(source.path, target.path), intent) }
        val saved = f.move(recordIntent = { value ->
            assertTrue(f.source.exists()); assertFalse(File(value.recovery).exists())
            assertEquals(f.source.path, FileEngine(f.state).originalPath(File(value.recovery)))
            intent = value
        })
        assertEquals(FolderSyncPreservationIntent(f.source.path, saved.path), intent)
        assertEquals("reviewed original", saved.readText())
    }
    @Test fun aFailedPreservationIntentLeavesTheOriginalAndItsRecordedRecoveryLocationForReview() {
        val f = Fixture(); var intent: FolderSyncPreservationIntent? = null
        val error = stopped { f.move(recordIntent = { value -> intent = value; error("Synthetic journal write failure") }) }
        assertFalse(error.moveAttempted); assertNull(error.recovery)
        assertEquals("reviewed original", f.source.readText()); assertFalse(File(intent!!.recovery).exists())
        assertEquals(f.source.path, FileEngine(f.state).originalPath(File(intent!!.recovery)))
        assertTrue(File(intent!!.recovery).parentFile!!.isDirectory)
    }
    @Test fun anOriginalEditedWhileItsIntentIsSavedIsRecheckedBeforeTheNativeMove() {
        val f = Fixture()
        val error = stopped { f.move(recordIntent = { f.source.writeText("last edit before native move") }) }
        assertFalse(error.moveAttempted); assertNull(error.recovery)
        assertEquals("last edit before native move", f.source.readText()); assertTrue(f.engine.trashItems().isEmpty())
    }
    @Test fun cancellationBeforeTheMoveLeavesTheReviewedOriginalInPlace() {
        val f=Fixture();val error=stopped{f.move(TransferControl().apply {cancel()})}
        assertNull(error.recovery);assertEquals("reviewed original",f.source.readText());assertTrue(f.engine.trashItems().isEmpty())
    }
}
