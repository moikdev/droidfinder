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
        fun move(control:TransferControl=TransferControl())=engine.trashReviewedByRename(expected,hashes,files,storage,control)
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
    @Test fun cancellationBeforeTheMoveLeavesTheReviewedOriginalInPlace() {
        val f=Fixture();val error=stopped{f.move(TransferControl().apply {cancel()})}
        assertNull(error.recovery);assertEquals("reviewed original",f.source.readText());assertTrue(f.engine.trashItems().isEmpty())
    }
}
