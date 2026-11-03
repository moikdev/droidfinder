package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class FolderSyncTreeChangeTest {
    private fun stamp(inode:Long,folder:Boolean=false)=PartialFileStamp(1,inode,if(folder)0 else 4,1,0,1,0,!folder,folder,if(folder)2 else 1)
    private fun proof(root:String,entries:Map<String,PartialFileStamp>)=UndoPathProof(root,root,"/","/",1,99,entries)
    private val before=proof("/destination",mapOf("/destination" to stamp(1,true),"/destination/Keep" to stamp(2),"/destination/Folder" to stamp(3,true),"/destination/Folder/Old" to stamp(4)))
    private val staged=proof("/stage/Copy",mapOf("/stage/Copy" to stamp(5,true),"/stage/Copy/New" to stamp(6)))
    private fun publication()=before.copy(entries=before.entries.mapValues {(path,s)->if(path=="/destination")s.copy(changedNanos=3,modifiedNanos=3,size=12,links=3) else s}+
        mapOf("/destination/New" to stamp(5,true).copy(changedNanos=8),"/destination/New/New" to stamp(6)))
    private fun rejected(work:()->Unit) {try{work();fail("Expected changed-tree refusal")}catch(_:IllegalStateException){}}
    @Test fun onlyTheDeclaredPublicationAndItsAncestorMetadataCanAdvanceTheDestinationProof() {
        val actual=publication()
        checkFolderSyncTreeChange(before,actual,"/destination/New",staged)
    }
    @Test fun aRootDirectoryReplacementCannotBeAcceptedAsItsOwnChildCreation() {
        val actual=publication().let {it.copy(entries=it.entries+(it.path to stamp(90,true)))}
        rejected {checkFolderSyncTreeChange(before,actual,"/destination/New",staged)}
    }
    @Test fun newFilesOutsideTheDeclaredPublicationInvalidateTheWholeTransition() {
        val actual=publication().let {it.copy(entries=it.entries+("/destination/Late" to stamp(77)))}
        rejected {checkFolderSyncTreeChange(before,actual,"/destination/New",staged)}
    }
    @Test fun aSameSizeRewriteOfAnUnrelatedFileCannotBecomeTheNewBaseline() {
        val actual=publication().let {it.copy(entries=it.entries+("/destination/Keep" to stamp(2).copy(changedNanos=9)))}
        rejected {checkFolderSyncTreeChange(before,actual,"/destination/New",staged)}
    }
    @Test fun anUnrelatedDirectoryMetadataChangeAlsoStopsPublicationAcceptance() {
        val actual=publication().let {it.copy(entries=it.entries+("/destination/Folder" to stamp(3,true).copy(changedNanos=9)))}
        rejected {checkFolderSyncTreeChange(before,actual,"/destination/New",staged)}
    }
    @Test fun aDifferentParentOrResolvedRootStopsTheTransition() {
        for(actual in listOf(publication().copy(anchorInode=90),publication().copy(resolved="/redirected")))
            rejected {checkFolderSyncTreeChange(before,actual,"/destination/New",staged)}
    }
    @Test fun anOccupiedDestinationCannotBeTreatedAsAnAddition() {
        rejected {checkFolderSyncTreeChange(before,publication(),"/destination/Keep",staged)}
    }
    @Test fun changingAStagedDescendantDoesNotGetTheRootRenameCtimeException() {
        val actual=publication().let {it.copy(entries=it.entries+("/destination/New/New" to stamp(6).copy(changedNanos=9)))}
        rejected {checkFolderSyncTreeChange(before,actual,"/destination/New",staged)}
    }
    @Test fun removingAnEntireDisclosedSubtreePermitsOnlyItsOwnAncestorMetadataChanges() {
        val actual=before.copy(entries=(before.entries.filterKeys {!it.startsWith("/destination/Folder")})+("/destination" to stamp(1,true).copy(changedNanos=9)))
        checkFolderSyncTreeChange(before,actual,"/destination/Folder")
        rejected {checkFolderSyncTreeChange(before,actual.copy(entries=actual.entries-"/destination/Keep"),"/destination/Folder")}
    }
    @Test fun aRootOrOutsideTargetCannotBeApproved() {
        for(target in listOf("/destination","/other/New","/destination/../New"))rejected {checkFolderSyncTreeChange(before,publication(),target,staged)}
    }
    @Test fun aRelocationChecksBothCompleteMembershipAndEveryFileChecksum() {
        val original=mapOf("/original/Folder" to stamp(5,true),"/original/Folder/Item" to stamp(6))
        val moved=proof("/recovery/Folder",mapOf("/recovery/Folder" to stamp(5,true).copy(changedNanos=9),"/recovery/Folder/Item" to stamp(6)))
        var reads=0
        checkFolderSyncRelocation("/original/Folder",original,moved,mapOf("/original/Folder/Item" to "a".repeat(64))) {path,s->
            assertEquals("/recovery/Folder/Item",path);assertEquals(stamp(6),s);reads++;"a".repeat(64)
        }
        assertEquals(1,reads)
        rejected {checkFolderSyncRelocation("/original/Folder",original,moved,mapOf("/original/Folder/Item" to "a".repeat(64))) {_,_->"b".repeat(64)}}
        rejected {checkFolderSyncRelocation("/original/Folder",original,moved.copy(entries=moved.entries+("/recovery/Folder/Late" to stamp(88))),mapOf("/original/Folder/Item" to "a".repeat(64))) {_,_->"a".repeat(64)}}
    }
    @Test fun changingARelocatedFileThenRestoringItsMtimeStillRequiresItsOriginalBytes() {
        val original=mapOf("/old" to stamp(5));val moved=proof("/new",mapOf("/new" to stamp(5).copy(changedNanos=8)))
        rejected {checkFolderSyncRelocation("/old",original,moved,mapOf("/old" to "a".repeat(64))) {_,_->"b".repeat(64)}}
    }
    @Test fun subtreeProofsRetainReviewedIdentitiesWithoutInspectingNewFiles() {
        val child=folderSyncSubtreeProof(before,"/destination/Folder/Old")
        assertEquals("/destination/Folder",child.anchor);assertEquals(3L,child.anchorInode)
        assertEquals(mapOf("/destination/Folder/Old" to stamp(4)),child.entries)
        assertEquals("/destination/Folder/Old",child.resolved)
    }
    @Test fun aWholeRootReusesItsOriginalCompleteProof() {
        assertSame(before,folderSyncSubtreeProof(before,before.path))
    }
    @Test fun missingOutsideOrUnnormalizedSubtreesCannotInventProofs() {
        for(path in listOf("/destination/Missing","/other/Folder","/destination/Folder/../Keep"))
            rejected {folderSyncSubtreeProof(before,path)}
    }
}
