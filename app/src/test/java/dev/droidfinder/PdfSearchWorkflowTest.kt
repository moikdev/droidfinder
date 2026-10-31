package dev.droidfinder

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfSearchWorkflowTest {
    private fun text(value: String, limited: Boolean = false) =
        PdfPageText(value, emptyList(), limited)

    @Test
    fun searchPublishesReadablePagesAndMakesFailuresExplicit() = runBlocking {
        val batches = mutableListOf<PdfSearchBatch>()
        searchPdfPages(
            4,
            "needle",
            { page ->
                when (page) {
                    0 -> text("needle")
                    1 -> error("Unreadable page")
                    2 -> text("needle", true)
                    else -> text("unrelated")
                }
            },
            { batches += it },
        )
        val result = batches.last()
        assertEquals(listOf(0, 2), result.matches.map { it.page })
        assertEquals(4, result.scanned)
        assertEquals(2, result.issueCount)
        assertEquals(listOf(1, 2), result.issues.map { it.page })
        assertFalse(result.capped)
    }

    @Test
    fun stopRetainsPublishedMatchesAndRejectsALateUncancellableRead() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batches = mutableListOf<PdfSearchBatch>()
        val task = launch {
            searchPdfPages(
                3,
                "needle",
                { page ->
                    if (page == 1)
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                    text("needle")
                },
                { batches += it },
            )
        }
        entered.await()
        assertEquals(1, batches.last().scanned)
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, batches.size)
        assertEquals(1, batches.single().matches.size)
    }

    @Test
    fun cappedResultsAndIssueExamplesHaveExplicitBounds() = runBlocking {
        val batches = mutableListOf<PdfSearchBatch>()
        searchPdfPages(
            30,
            "hit",
            { page -> if (page < 20) error("Offline") else text("hit ".repeat(40)) },
            { batches += it },
            maximum = 50,
        )
        val last = batches.last()
        assertEquals(50, last.matches.size)
        assertEquals(22, last.scanned)
        assertEquals(20, last.issueCount)
        assertEquals(10, last.issues.size)
        assertTrue(last.capped)
    }
}
