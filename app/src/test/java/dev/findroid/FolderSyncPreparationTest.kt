package dev.findroid

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderSyncPreparationTest {
    @get:Rule val temporary=TemporaryFolder()
    private inner class Fixture {
        val base=temporary.newFolder()
        val source=File(base,"Source").apply {mkdir()}
        val destination=File(base,"Destination").apply {mkdir()}
        val storage=FolderSyncNioStorage()
        val proofs=FolderSyncNioProofs(storage)
        init {
            File(source,"New/Sub").mkdirs();File(source,"New/Sub/A.txt").writeText("reviewed addition")
            File(source,"Empty").mkdir();File(source,"Update.txt").writeText("new data")
            File(destination,"Update.txt").writeText("old data")
            File(destination,"Extra").mkdir();File(destination,"Extra/Keep.txt").writeText("destination-only original")
            File(source,"Same.txt").writeText("same");File(destination,"Same.txt").writeText("same")
        }
        fun node(path:String):ComparisonNode {
            val stamp=storage.stat(path)?:error("Missing node")
            return ComparisonNode(path,File(path).name,stamp.directory,if(stamp.regular)stamp.size else null,
                stamp.modifiedSeconds*1000+stamp.modifiedNanos/1_000_000,!stamp.directory&&!stamp.regular)
        }
        fun inspection()=inspectFolderSync(source.path,destination.path,FolderSyncDirection.PRIMARY_TO_SECONDARY,{true},::node,
            {path->File(path).listFiles()!!.map {node(it.path)}},{path,stamp,token->folderSyncDigest(path,stamp,token,storage)},proofs)
        fun contents(folder:File)=Files.walk(folder.toPath()).use {paths->paths.filter {Files.isRegularFile(it)}.toList().associate {folder.toPath().relativize(it).toString() to it.toFile().readText()}}
        fun prepare(inspection:FolderSyncInspection,review:ReviewedFolderSync=reviewFolderSync(inspection.preview,inspection.preview.initialSelection),allowed:(String)->Boolean={true})=
            prepareFolderSync(inspection,review,allowed,proofs,storage)
        fun working()=base.listFiles()!!.filter {it.name.startsWith(".findroid-sync-")}
    }
    private fun failed(work:()->Unit):FolderSyncPreparationFailure {
        try {work();fail("Expected preparation refusal")}catch(error:FolderSyncPreparationFailure){return error}
        error("Unreachable")
    }
    @Test fun defaultPreparationCopiesOnlyDisclosedAdditionsAndLeavesBothOriginalTreesExact() {
        val f=Fixture();val source=f.contents(f.source);val destination=f.contents(f.destination)
        val inspection=f.inspection();val prepared=f.prepare(inspection)
        assertEquals(source,f.contents(f.source));assertEquals(destination,f.contents(f.destination))
        assertEquals(setOf("Empty","New"),prepared.roots.map {it.operation.relative}.toSet())
        val tree=prepared.roots.single {it.operation.relative=="New"}
        assertEquals(mapOf("Sub/A.txt" to "reviewed addition"),f.contents(File(tree.path)))
        assertTrue(File(prepared.directory,"review.json").isFile);assertTrue(File(prepared.directory,"prepared.json").isFile)
        assertEquals(prepared.proof,f.proofs.observe(prepared.directory))
    }
    @Test fun replacementPreparationKeepsTheExistingDestinationUntilASeparateApply() {
        val f=Fixture();val inspection=f.inspection()
        val review=reviewFolderSync(inspection.preview,setOf("Update.txt"),confirmUpdates=true)
        val prepared=f.prepare(inspection,review)
        assertEquals("old data",File(f.destination,"Update.txt").readText())
        assertEquals("new data",File(prepared.roots.single().path).readText())
    }
    @Test fun aTrashOnlyReviewPreparesItsRecordWithoutMovingAnyDestinationItem() {
        val f=Fixture();val inspection=f.inspection();val before=f.contents(f.destination)
        val prepared=f.prepare(inspection,reviewFolderSync(inspection.preview,setOf("Extra"),confirmTrash=true))
        assertTrue(prepared.roots.isEmpty());assertEquals(before,f.contents(f.destination))
        assertFalse(File(prepared.directory).listFiles()!!.any {it.name.startsWith("copy-")})
    }
    @Test fun aStaleDestinationReviewIsRejectedBeforeCreatingAWorkingDirectory() {
        val f=Fixture();val inspection=f.inspection();File(f.destination,"Added after review.txt").writeText("keep")
        val error=failed {f.prepare(inspection)}
        assertNull(error.workingDirectory);assertTrue(f.working().isEmpty());assertEquals("keep",File(f.destination,"Added after review.txt").readText())
    }
    @Test fun deniedWorkingLocationCannotCreateAnyRecoveryOrWorkingFile() {
        val f=Fixture();val inspection=f.inspection()
        val error=failed {f.prepare(inspection,allowed={it!=f.base.path})}
        assertNull(error.workingDirectory);assertTrue(f.working().isEmpty())
    }
    @Test fun aLateUndisclosedSourceFileInvalidatesTheWholePreparedTree() {
        val f=Fixture();val inspection=f.inspection();val before=f.contents(f.destination);var injected=false
        f.storage.afterWrite={path->if(!injected&&path.endsWith("A.txt")){injected=true;File(f.source,"New/Sub/Late.txt").writeText("keep late")}}
        val error=failed {f.prepare(inspection)}
        assertNotNull(error.workingDirectory);assertEquals(before,f.contents(f.destination))
        assertEquals("keep late",File(f.source,"New/Sub/Late.txt").readText())
        assertTrue(File(error.workingDirectory!!,"review.json").isFile)
        assertFalse(Files.walk(File(error.workingDirectory).toPath()).use {paths->paths.anyMatch {it.fileName.toString()=="Late.txt"}})
    }
    @Test fun changingTheDestinationDuringPreparationDoesNotReplaceOrTrashIt() {
        val f=Fixture();val inspection=f.inspection();var injected=false
        f.storage.afterWrite={path->if(!injected&&path.endsWith("A.txt")){injected=true;File(f.destination,"Update.txt").writeText("later edit")}}
        val error=failed {f.prepare(inspection)}
        assertNotNull(error.workingDirectory);assertEquals("later edit",File(f.destination,"Update.txt").readText())
        assertEquals("destination-only original",File(f.destination,"Extra/Keep.txt").readText())
    }
    @Test fun forgedReplacementConsentIsRejectedBeforeCreatingWorkingStorage() {
        val f=Fixture();val inspection=f.inspection()
        val forged=ReviewedFolderSync(inspection.preview,listOf(inspection.preview.entries.single {it.relative=="Update.txt"}),false,false)
        val error=failed {f.prepare(inspection,forged)}
        assertNull(error.workingDirectory);assertTrue(f.working().isEmpty())
    }
    @Test fun aPreviouslyCreatedWorkingFolderIsNeverReusedOrDeleted() {
        val f=Fixture();val inspection=f.inspection();val id="12345678-1234-4234-8234-123456789abc"
        val existing=File(f.base,".findroid-sync-$id").apply {mkdir()};File(existing,"Keep.txt").writeText("keep")
        val error=failed {prepareFolderSync(inspection,reviewFolderSync(inspection.preview,inspection.preview.initialSelection),{true},f.proofs,f.storage,owner={id})}
        assertNull(error.workingDirectory);assertEquals("keep",File(existing,"Keep.txt").readText())
    }
    @Test fun cancellingBeforePreparationCreatesNoWorkingFolder() {
        val f=Fixture();val inspection=f.inspection();val control=TransferControl().apply {cancel()}
        try {
            prepareFolderSync(inspection,reviewFolderSync(inspection.preview,inspection.preview.initialSelection),{true},f.proofs,f.storage,control)
            fail("Expected cancellation")
        } catch(error:FolderSyncPreparationCancelled) {assertNull(error.workingDirectory)}
        assertTrue(f.working().isEmpty())
    }
    @Test fun cancellingDuringPreparationKeepsAnOwnedReviewAndBothOriginalTrees() {
        val f=Fixture();val inspection=f.inspection();val control=TransferControl()
        val source=f.contents(f.source);val destination=f.contents(f.destination)
        f.storage.afterWrite={path->if(path.endsWith("A.txt"))control.cancel()}
        try {
            prepareFolderSync(inspection,reviewFolderSync(inspection.preview,inspection.preview.initialSelection),{true},f.proofs,f.storage,control)
            fail("Expected cancellation")
        } catch(error:FolderSyncPreparationCancelled) {
            assertNotNull(error.workingDirectory);assertTrue(File(error.workingDirectory!!,"review.json").isFile)
        }
        assertEquals(source,f.contents(f.source));assertEquals(destination,f.contents(f.destination))
    }
    @Test fun unchangedPreparationPassesTheFinalReadOnlyGate() {
        val f=Fixture();val prepared=f.prepare(f.inspection())
        val source=f.contents(f.source);val destination=f.contents(f.destination)
        checkPreparedFolderSync(prepared,{true},f.proofs,f.storage)
        assertEquals(source,f.contents(f.source));assertEquals(destination,f.contents(f.destination))
    }
    @Test fun alteredWorkingBytesCannotPassTheFinalGate() {
        val f=Fixture();val prepared=f.prepare(f.inspection());val destination=f.contents(f.destination)
        val root=prepared.roots.single {it.operation.relative=="New"};File(root.path,"Sub/A.txt").writeText("different working content")
        try {checkPreparedFolderSync(prepared,{true},f.proofs,f.storage);fail("Expected refusal")}catch(_:IllegalStateException){}
        assertEquals(destination,f.contents(f.destination));assertEquals("reviewed addition",File(f.source,"New/Sub/A.txt").readText())
    }
    @Test fun laterDestinationEditsCannotPassTheFinalGate() {
        val f=Fixture();val prepared=f.prepare(f.inspection());File(f.destination,"Update.txt").writeText("later edit")
        try {checkPreparedFolderSync(prepared,{true},f.proofs,f.storage);fail("Expected refusal")}catch(_:IllegalStateException){}
        assertEquals("later edit",File(f.destination,"Update.txt").readText())
    }
    @Test fun newlyArrivedDestinationItemsCannotPassTheFinalGate() {
        val f=Fixture();val prepared=f.prepare(f.inspection());File(f.destination,"New").writeText("occupied")
        try {checkPreparedFolderSync(prepared,{true},f.proofs,f.storage);fail("Expected refusal")}catch(_:IllegalStateException){}
        assertEquals("occupied",File(f.destination,"New").readText())
    }
    @Test fun lostPermissionsCannotPassTheFinalGate() {
        val f=Fixture();val prepared=f.prepare(f.inspection());val before=f.contents(f.destination)
        try {checkPreparedFolderSync(prepared,{false},f.proofs,f.storage);fail("Expected refusal")}catch(_:IllegalStateException){}
        assertEquals(before,f.contents(f.destination));assertEquals(prepared.proof,f.proofs.observe(prepared.directory))
    }
}
