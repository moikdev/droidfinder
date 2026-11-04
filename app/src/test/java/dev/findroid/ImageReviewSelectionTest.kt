package dev.findroid

import org.junit.Assert.*
import org.junit.Test

class ImageReviewSelectionTest {
    private fun start() = ImageReviewSelection.start(listOf("a", "b", "c"))
    @Test fun keepsFrozenOrderAndDeduplicatesIds() {
        val input = mutableListOf("c", "a", "c", "b")
        val review = ImageReviewSelection.start(input); input.clear()
        assertEquals(listOf("c", "a", "b"), review.paths); assertEquals("c", review.active)
    }
    @Test fun refusesEmptyReview() { assertThrows(IllegalArgumentException::class.java) { ImageReviewSelection.start(emptyList()) } }
    @Test fun refusesBlankId() { assertThrows(IllegalArgumentException::class.java) { ImageReviewSelection.start(listOf(" ")) } }
    @Test fun enforcesUniqueImageLimit() {
        assertEquals(200, ImageReviewSelection.start((1..200).map(Int::toString)).paths.size)
        assertThrows(IllegalArgumentException::class.java) { ImageReviewSelection.start((1..201).map(Int::toString)) }
    }
    @Test fun navigationClampsAtEndsEvenForLargeDeltas() {
        assertEquals("a", start().step(-1).active); assertEquals("c", start().step(Int.MAX_VALUE).active)
        assertEquals("a", start().step(2).step(Int.MIN_VALUE).active)
        assertEquals("c", start().step(2).step(Int.MAX_VALUE).active)
    }
    @Test fun invalidSelectionDoesNotLeaveTheReview() { assertEquals(start(), start().select("missing")) }
    @Test fun flagsToggleWithoutChangingOrderOrOtherFlags() {
        val review = start().toggleFlag().step(2).toggleFlag()
        assertEquals(setOf("a", "c"), review.flagged); assertEquals(setOf("a"), review.toggleFlag().flagged)
        assertEquals(start().paths, review.paths)
    }
    @Test fun flaggedFilterUsesOriginalOrderAndRetainsCurrentWhenPossible() {
        val review = start().step(2).toggleFlag().step(-2).toggleFlag().filter(true)
        assertEquals(listOf("a", "c"), review.shown); assertEquals("a", review.active)
        assertEquals("c", review.step(1).active); assertEquals("c", review.select("b").step(1).active)
    }
    @Test fun filteringAnUnflaggedCurrentChoosesFirstCandidate() {
        assertEquals("a", start().toggleFlag().step(1).filter(true).active)
    }
    @Test fun unflagInFilteredListChoosesNextThenPrevious() {
        var review = start().toggleFlag().step(1).toggleFlag().step(1).toggleFlag().filter(true)
        review = review.select("b").toggleFlag(); assertEquals("c", review.active)
        review = review.toggleFlag(); assertEquals("a", review.active); assertTrue(review.flaggedOnly)
    }
    @Test fun removingLastCandidateReturnsToAllImages() {
        val review = start().step(1).toggleFlag().filter(true).toggleFlag()
        assertFalse(review.flaggedOnly); assertTrue(review.flagged.isEmpty()); assertEquals("b", review.active)
    }
    @Test fun emptyFilterKeepsAllImagesReachable() { assertEquals(start(), start().filter(true)) }
    @Test fun clearFlagsKeepsCurrentAndReference() {
        val review = start().pinReference().step(1).toggleFlag().filter(true).clearFlags()
        assertEquals("b", review.active); assertEquals("a", review.reference)
        assertFalse(review.flaggedOnly); assertEquals(listOf("a", "b", "c"), review.shown)
    }
    @Test fun referenceNeverComparesWithItselfAndPersistsOutsideFilteredList() {
        val review = start().pinReference().step(1).toggleFlag().filter(true)
        assertEquals(listOf("a", "b"), review.comparison)
        assertNull(review.pinReference().comparison); assertNull(review.clearReference().comparison)
    }
    @Test fun newSessionHasNoFlagsReferenceOrFilter() {
        val first = start().pinReference().toggleFlag().filter(true)
        val next = ImageReviewSelection.start(first.paths)
        assertTrue(next.flagged.isEmpty()); assertNull(next.reference); assertFalse(next.flaggedOnly)
    }
    @Test fun commandRejectsMixedEmptyOversizedAndTrashSelections() {
        for (context in listOf(CommandContext(), CommandContext(selected=2,selectedImages=1), CommandContext(selected=201,selectedImages=201)))
            assertTrue(commandProblem("imageReview", context)!!.reason.contains("1–200"))
        assertTrue(commandProblem("imageReview", CommandContext(selected=2,selectedImages=2,trash=true))!!.reason.contains("Restore"))
    }
    @Test fun readOnlyImagesCanBeReviewedDuringAnUnrelatedTransfer() {
        val context = CommandContext(selected=2,selectedImages=2,busy="Copying",renameAllowed=false,removeAllowed=false,createAllowed=false)
        assertNull(commandProblem("imageReview", context))
        assertEquals(CommandRecovery.STORAGE, commandProblem("imageReview", context.copy(disconnected=true))!!.recovery)
    }
}
