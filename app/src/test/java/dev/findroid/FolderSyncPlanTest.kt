package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class FolderSyncPlanTest {
    private val forward = FolderSyncDirection.PRIMARY_TO_SECONDARY
    private fun node(root: String, relative: String, folder: Boolean = false, bytes: Long? = 4) =
        ComparisonNode("$root/$relative", relative.substringAfterLast('/'), folder, bytes, 1)
    private fun row(name: String, state: ComparisonState, folder: Boolean = false, leftBytes: Long? = 4, rightBytes: Long? = 4) =
        ComparisonRow(name,
            if (state == ComparisonState.ONLY_RIGHT) null else node("/source", name, folder, leftBytes),
            if (state == ComparisonState.ONLY_LEFT) null else node("/destination", name, folder, rightBytes), state)
    private fun plan(vararg rows: ComparisonRow, direction: FolderSyncDirection = forward, limited: Boolean = false) =
        planFolderSync("/source", "/destination", FolderComparison(rows.toList(), limited), direction)
    private fun rejected(work: () -> Unit) {
        try { work();fail("Expected a reviewed refusal") } catch (_: IllegalStateException) { }
    }

    @Test fun additionsAloneAreSelectedAndDestinationOnlyFilesStayUntouched() {
        val p=plan(row("New.txt",ComparisonState.ONLY_LEFT),row("Keep.txt",ComparisonState.ONLY_RIGHT),row("Edited.txt",ComparisonState.CHANGED))
        assertTrue(p.executable);assertEquals(setOf("New.txt"),p.initialSelection)
        val review=reviewFolderSync(p,p.initialSelection)
        assertEquals(listOf("New.txt"),review.operations.map { it.relative })
        assertEquals(4L,review.copyBytes)
    }
    @Test fun everyDifferenceHasItsOwnVisibleCategory() {
        val type=row("Type",ComparisonState.TYPE_CHANGED).let { it.copy(left=it.left!!.copy(folder=true)) }
        val p=plan(row("New",ComparisonState.ONLY_LEFT),row("Updated",ComparisonState.SIZE_CHANGED,leftBytes=3,rightBytes=4),
            type,row("Extra",ComparisonState.ONLY_RIGHT),row("Same",ComparisonState.IDENTICAL))
        assertTrue(p.executable)
        assertEquals(FolderSyncCategory.entries.toSet(),p.entries.map { it.category }.toSet())
        assertFalse(p.entries.single { it.relative=="Type" }.selectable)
    }
    @Test fun reversingDirectionSwapsRootsAndAdditionDeletionMeaning() {
        val p=plan(row("Left",ComparisonState.ONLY_LEFT),row("Right",ComparisonState.ONLY_RIGHT),direction=FolderSyncDirection.SECONDARY_TO_PRIMARY)
        assertEquals("/destination",p.sourceRoot);assertEquals("/source",p.destinationRoot)
        assertEquals(setOf("Right"),p.initialSelection)
        assertEquals("/source/Right",p.entries.single { it.relative=="Right" }.destinationPath)
        assertEquals("/destination/Right",p.entries.single { it.relative=="Right" }.source!!.path)
    }
    @Test fun newerTimesNeverChooseAReplacementAutomatically() {
        val item=row("Changed.txt",ComparisonState.CHANGED)
        val p=plan(item.copy(left=item.left!!.copy(modified=Long.MAX_VALUE),right=item.right!!.copy(modified=1)))
        assertTrue(p.initialSelection.isEmpty())
        rejected { reviewFolderSync(p,setOf("Changed.txt")) }
        assertEquals(1,reviewFolderSync(p,setOf("Changed.txt"),confirmUpdates=true).operations.size)
    }
    @Test fun replacementAndTrashHaveIndependentConfirmations() {
        val p=plan(row("Updated",ComparisonState.CHANGED),row("Extra",ComparisonState.ONLY_RIGHT))
        rejected { reviewFolderSync(p,setOf("Updated","Extra"),confirmUpdates=true) }
        rejected { reviewFolderSync(p,setOf("Updated","Extra"),confirmTrash=true) }
        val reviewed=reviewFolderSync(p,setOf("Updated","Extra"),true,true)
        assertEquals(listOf(FolderSyncCategory.UPDATE,FolderSyncCategory.DELETION),reviewed.operations.map { it.category })
    }
    @Test fun destinationOnlyItemsRequireExplicitTrashChoice() {
        val p=plan(row("Extra",ComparisonState.ONLY_RIGHT))
        assertTrue(p.initialSelection.isEmpty());rejected { reviewFolderSync(p,setOf("Extra")) }
        val reviewed=reviewFolderSync(p,setOf("Extra"),confirmTrash=true)
        assertTrue(reviewed.copyFiles.isEmpty());assertEquals(0L,reviewed.copyBytes)
    }
    @Test fun unverifiedContentsRemainAConflictEvenWithMatchingMetadata() {
        val p=plan(row("Unknown",ComparisonState.UNVERIFIED))
        assertEquals(FolderSyncCategory.CONFLICT,p.entries.single().category)
        assertFalse(p.entries.single().selectable);rejected { reviewFolderSync(p,setOf("Unknown"),true,true) }
    }
    @Test fun verifiedIdenticalFilesCanNeverBeSelectedForMutation() {
        val p=plan(row("Same",ComparisonState.IDENTICAL))
        assertTrue(p.initialSelection.isEmpty());rejected { reviewFolderSync(p,setOf("Same"),true,true) }
    }
    @Test fun aNewSubtreeIsDisclosedAndCopiedOnlyOnce() {
        val p=plan(row("New",ComparisonState.ONLY_LEFT,true),row("New/Sub",ComparisonState.ONLY_LEFT,true),
            row("New/Sub/a.txt",ComparisonState.ONLY_LEFT,leftBytes=3),row("New/b.txt",ComparisonState.ONLY_LEFT,leftBytes=5))
        assertEquals(setOf("New"),p.initialSelection)
        assertEquals(3,p.entries.single { it.relative=="New" }.descendantCount)
        assertTrue(p.entries.filter { it.relative!="New" }.all { it.coveredBy=="New" && !it.selectable })
        val reviewed=reviewFolderSync(p,p.initialSelection)
        assertEquals(1,reviewed.operations.size);assertEquals(2,reviewed.copyFiles.size);assertEquals(8L,reviewed.copyBytes)
        rejected { reviewFolderSync(p,setOf("New/Sub/a.txt")) }
    }
    @Test fun aDestinationOnlySubtreeNeedsOneExplicitTrashChoice() {
        val p=plan(row("Extra",ComparisonState.ONLY_RIGHT,true),row("Extra/a.txt",ComparisonState.ONLY_RIGHT))
        assertTrue(p.initialSelection.isEmpty())
        assertEquals("Extra",p.entries.single { it.relative=="Extra/a.txt" }.coveredBy)
        assertEquals(1,reviewFolderSync(p,setOf("Extra"),confirmTrash=true).operations.size)
    }
    @Test fun pairedParentsLeaveNestedChangesIndividuallySelectable() {
        val p=plan(row("Project",ComparisonState.FOLDER,true),row("Project/New.txt",ComparisonState.ONLY_LEFT),row("Project/Extra.txt",ComparisonState.ONLY_RIGHT))
        assertEquals(setOf("Project/New.txt"),p.initialSelection)
        assertTrue(p.entries.single { it.relative=="Project/Extra.txt" }.selectable)
        assertFalse(p.entries.single { it.relative=="Project" }.selectable)
    }
    @Test fun unknownOrOverflowingBytesAreReportedAsUnknown() {
        val unknown=plan(row("Unknown",ComparisonState.ONLY_LEFT,leftBytes=null))
        assertNull(reviewFolderSync(unknown,unknown.initialSelection).copyBytes)
        val huge=plan(row("Huge",ComparisonState.ONLY_LEFT,leftBytes=Long.MAX_VALUE),row("More",ComparisonState.ONLY_LEFT,leftBytes=1))
        assertNull(reviewFolderSync(huge,huge.initialSelection).copyBytes)
    }
    @Test fun zeroByteFilesAreCountedWithoutBeingCalledUnknown() {
        val p=plan(row("Empty",ComparisonState.ONLY_LEFT,leftBytes=0))
        assertEquals(0L,reviewFolderSync(p,p.initialSelection).copyBytes)
        assertEquals(1,reviewFolderSync(p,p.initialSelection).copyFiles.size)
    }
    @Test fun anIncompleteComparisonCannotInferAnyDeletionOrStartWork() {
        val p=plan(row("MaybeExtra",ComparisonState.ONLY_RIGHT),limited=true)
        assertFalse(p.executable);assertTrue(p.initialSelection.isEmpty())
        rejected { reviewFolderSync(p,setOf("MaybeExtra"),true,true) }
    }
    @Test fun unreadableItemsBlockEvenOtherApparentlySafeAdditions() {
        val p=plan(row("New",ComparisonState.ONLY_LEFT),row("Unreadable",ComparisonState.UNAVAILABLE).copy(detail="Permission was lost"))
        assertFalse(p.executable);assertEquals("Permission was lost",p.entries.single { it.relative=="Unreadable" }.explanation)
        rejected { reviewFolderSync(p,setOf("New")) }
    }
    @Test fun linksNeverAcquireAnAutomaticSubtreeCopy() {
        val linked=row("New/Link",ComparisonState.ONLY_LEFT).let { it.copy(left=it.left!!.copy(link=true)) }
        val p=plan(row("New",ComparisonState.ONLY_LEFT,true),linked)
        assertFalse(p.executable);assertTrue(p.initialSelection.isEmpty())
    }
    @Test fun overlappingAndEqualRootsAreRejectedInEitherDirection() {
        for ((a,b) in listOf("/work" to "/work", "/work" to "/work/child", "/work/child" to "/work")) {
            val p=planFolderSync(a,b,FolderComparison(emptyList()),forward)
            assertFalse(p.executable)
        }
        assertTrue(planFolderSync("/work","/work-other",FolderComparison(emptyList()),forward).executable)
    }
    @Test fun connectedOrRelativeRootsRequireTheirOwnChecks() {
        assertFalse(planFolderSync("content://provider/tree/a","/work",FolderComparison(emptyList()),forward).executable)
        assertFalse(planFolderSync("relative","/work",FolderComparison(emptyList()),forward).executable)
    }
    @Test fun traversalAbsoluteAndAmbiguousRelativeNamesCannotBeApplied() {
        for (name in listOf("../Escape","/Absolute","a//b","a/./b","a/../b","a\u0000b")) {
            val p=plan(row(name,ComparisonState.ONLY_LEFT));assertFalse(name,p.executable)
            rejected { reviewFolderSync(p,setOf(name)) }
        }
    }
    @Test fun aComparedNodeCannotPointOutsideItsDeclaredRoot() {
        val outside=row("New",ComparisonState.ONLY_LEFT).let { it.copy(left=it.left!!.copy(path="/elsewhere/New")) }
        assertFalse(plan(outside).executable)
    }
    @Test fun duplicateOrInconsistentComparisonRowsAreRejected() {
        val item=row("New",ComparisonState.ONLY_LEFT)
        assertFalse(plan(item,item).executable)
        assertFalse(plan(item.copy(state=ComparisonState.IDENTICAL)).executable)
        assertFalse(plan(row("Missing",ComparisonState.ONLY_LEFT,true),row("Missing/Impossible",ComparisonState.IDENTICAL)).executable)
    }
    @Test fun unknownAndCoveredSelectionsCannotSlipIntoAReviewedPlan() {
        val p=plan(row("New",ComparisonState.ONLY_LEFT))
        rejected { reviewFolderSync(p,setOf("NotInTheReview"),true,true) }
    }
    @Test fun emptyFoldersProduceACompletePlanWithNoChanges() {
        val p=plan();assertTrue(p.executable);assertTrue(p.entries.isEmpty());assertTrue(p.initialSelection.isEmpty())
        assertTrue(reviewFolderSync(p,emptySet()).operations.isEmpty())
    }
    @Test fun unicodeAndCaseDistinctNamesKeepTheirExactDestinationPaths() {
        val p=plan(row("Résumé",ComparisonState.FOLDER,true),row("Résumé/猫😀.txt",ComparisonState.ONLY_LEFT),row("Case",ComparisonState.ONLY_LEFT),row("case",ComparisonState.ONLY_RIGHT))
        assertTrue(p.executable);assertEquals("/destination/Résumé/猫😀.txt",p.entries.single { it.relative.startsWith("Résumé/") }.destinationPath)
        assertEquals(setOf("Résumé/猫😀.txt","Case"),p.initialSelection)
    }
    @Test fun anOrphanedNestedFileCannotInventItsMissingParents() {
        assertFalse(plan(row("Missing/File",ComparisonState.ONLY_LEFT)).executable)
        assertFalse(plan(row("Parent",ComparisonState.IDENTICAL),row("Parent/Child",ComparisonState.ONLY_LEFT)).executable)
    }
    @Test fun oversizedReviewIsBoundedBeforeBuildingPerItemChoices() {
        val rows=(0..20_000).map { row("File-$it",ComparisonState.ONLY_LEFT) }
        val p=planFolderSync("/source","/destination",FolderComparison(rows),forward)
        assertFalse(p.executable);assertTrue(p.entries.isEmpty());assertTrue(p.initialSelection.isEmpty())
    }

    @Test fun aReviewCannotRewriteDotSegmentsInItsRootOrNodePaths() {
        assertFalse(planFolderSync("/source/../elsewhere","/destination",FolderComparison(emptyList()),forward).executable)
        val changed=row("New",ComparisonState.ONLY_LEFT).let { it.copy(left=it.left!!.copy(path="/source/dir/../New")) }
        assertFalse(plan(changed).executable)
    }

}
