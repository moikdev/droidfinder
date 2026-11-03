package dev.findroid

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FolderSyncInspectionTest {
    private val direction=FolderSyncDirection.PRIMARY_TO_SECONDARY
    private fun stamp(inode: Long, folder: Boolean=false, links: Long=1) =
        PartialFileStamp(1,inode,4,1,0,1,0,!folder,folder,links)
    private fun proof(root: String, inode: Long) = UndoPathProof(root,root,"/","/",1,100,mapOf(root to stamp(inode,true)))
    private class Files : UndoHistoryFiles {
        val values=mutableMapOf("/source" to UndoPathProof("/source","/source","/","/",1,100,mapOf("/source" to PartialFileStamp(1,1,0,1,0,1,0,false,true,2),"/source/Item" to PartialFileStamp(1,3,4,1,0,1,0,true,false,1))),
            "/destination" to UndoPathProof("/destination","/destination","/","/",1,100,mapOf("/destination" to PartialFileStamp(1,2,0,1,0,1,0,false,true,2),"/destination/Item" to PartialFileStamp(1,4,4,1,0,1,0,true,false,1))))
        var observed=0
        override fun observe(path: String)=values.getValue(path).also { observed++ }
        override fun sync(paths: Set<String>) { fail("Read-only review must not synchronize files") }
        override fun syncDirectory(directory: File) { fail("Read-only review must not synchronize directories") }
        override fun moveNoReplace(from: File,to: File) { fail("Read-only review must not move files") }
    }
    private val nodes=mutableMapOf(
        "/source" to ComparisonNode("/source","source",true,null,1),
        "/destination" to ComparisonNode("/destination","destination",true,null,1),
        "/source/Item" to ComparisonNode("/source/Item","Item",false,4,1),
        "/destination/Item" to ComparisonNode("/destination/Item","Item",false,4,1),
    )
    private var reads=0
    private fun inspect(files: Files, allowed: (String)->Boolean={true}, digest: (String,PartialFileStamp,TransferControl)->String={_,_,_->"a".repeat(64)}, control:TransferControl=TransferControl()):FolderSyncInspection =
        inspectFolderSync("/source","/destination",direction,allowed,
            read={reads++;nodes.getValue(it)},list={root->listOf(nodes.getValue(root+"/Item"))},
            fingerprint=digest,files=files,control=control)
    private fun rejected(work:()->Unit) {
        try {work();fail("Expected a review refusal")} catch(_:IllegalStateException) { }
    }
    @Test fun aReadOnlyInspectionVerifiesContentsAndBothNativeSnapshots() {
        val files=Files();var digests=0
        val inspection=inspect(files,digest={_,_,_->digests++;"a".repeat(64)})
        assertEquals(2,digests);assertEquals(4,files.observed)
        assertTrue(inspection.preview.executable)
        assertEquals(FolderSyncCategory.UNCHANGED,inspection.preview.entries.single().category)
    }
    @Test fun changedContentsBecomeAnExplicitUpdateWithoutAnAutomaticSelection() {
        val inspection=inspect(Files(),digest={path,_,_->if (path.startsWith("/source/")) "a".repeat(64) else "b".repeat(64)})
        assertEquals(FolderSyncCategory.UPDATE,inspection.preview.entries.single().category)
        assertTrue(inspection.preview.initialSelection.isEmpty())
    }
    @Test fun permissionRefusalPrecedesEveryNativeOrContentRead() {
        val files=Files();rejected {inspect(files,allowed={false})}
        assertEquals(0,files.observed);assertEquals(0,reads)
    }
    @Test fun aliasesOfTheSameDirectoryAreRejectedBeforeReadingContents() {
        val files=Files();files.values["/destination"]=proof("/destination",1)
        rejected {inspect(files)};assertEquals(0,reads)
    }
    @Test fun anAliasedDescendantCannotBypassTheRootSeparationCheck() {
        val files=Files();val source=files.values.getValue("/source")
        files.values["/source"]=source.copy(entries=source.entries+("/source/Alias" to stamp(2,true)))
        rejected {inspect(files)};assertEquals(0,reads)
    }
    @Test fun aResolvedAliasMustStillHavePermission() {
        val files=Files();files.values["/source"]=files.values.getValue("/source").copy(resolved="/unauthorized")
        rejected {inspect(files,allowed={it!="/unauthorized"})};assertEquals(0,reads)
    }
    @Test fun linkedFilesCannotEnterACopyOrReplacementProposal() {
        val files=Files();val source=files.values.getValue("/source")
        files.values["/source"]=source.copy(entries=source.entries+("/source/Item" to stamp(3,links=2)))
        rejected {inspect(files)};assertEquals(0,reads)
    }
    @Test fun aNativeChangeDuringHashingInvalidatesTheWholeReview() {
        val files=Files()
        rejected { inspect(files,digest={_,_,_->
            val old=files.values.getValue("/source")
            files.values["/source"]=old.copy(entries=old.entries.mapValues { (path,s)->if (path.endsWith("/Item")) s.copy(changedNanos=s.changedNanos+1) else s })
            "a".repeat(64)
        }) }
    }
    @Test fun changesAfterReviewCannotAcquireFreshProofsForAnOlderPlan() {
        val files=Files();val inspection=inspect(files)
        files.values["/destination"]=proof("/destination",99)
        rejected {checkFolderSyncInspection(inspection,files)}
        assertEquals(2L,inspection.secondaryProof.entries.getValue("/destination").inode)
    }
    @Test fun cancellationNeverStartsAContentReadOrMutation() {
        val files=Files();val control=TransferControl().apply { cancel() }
        try {inspect(files,control=control);fail("Expected cancellation")} catch(_:CancellationException) { }
        assertEquals(0,files.observed);assertEquals(0,reads)
    }
    @Test fun theInspectionRetainsIndependentDigestsForBothReviewedFiles() {
        val inspection=inspect(Files())
        assertEquals(setOf("/source/Item","/destination/Item"),inspection.contents.keys)
        assertTrue(inspection.contents.values.all { it=="a".repeat(64) })
    }
    @Test fun aMalformedChecksumCannotReachAReviewedPlan() {
        rejected {inspect(Files(),digest={_,_,_->"not a SHA-256 checksum"})}
    }

    @Test fun aListingCannotHideFilesPresentInTheNativeSnapshot() {
        val files=Files();val original=files.values.getValue("/source")
        files.values["/source"]=original.copy(entries=original.entries+("/source/Hidden.txt" to stamp(99)))
        rejected {inspect(files)}
    }
    @Test fun reviewedFileSizesComeFromThePinnedNativeSnapshot() {
        nodes["/source/Item"]=nodes.getValue("/source/Item").copy(bytes=null)
        val inspection=inspect(Files())
        assertEquals(4L,inspection.preview.entries.single().source!!.bytes)
    }

}
