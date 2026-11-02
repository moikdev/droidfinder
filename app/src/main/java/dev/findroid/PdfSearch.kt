package dev.findroid

import kotlinx.coroutines.*

internal data class PdfSearchIssue(val page: Int, val reason: String)

internal data class PdfSearchBatch(
    val matches: List<PdfMatch>,
    val scanned: Int,
    val issueCount: Int,
    val issues: List<PdfSearchIssue>,
    val capped: Boolean,
)

/** A canceled/failed page never becomes an unqualified no-match result. */
internal suspend fun searchPdfPages(
    pages: Int,
    query: String,
    read: suspend (Int) -> PdfPageText,
    publish: suspend (PdfSearchBatch) -> Unit,
    maximum: Int = 2000,
) {
    if (query.isBlank() || pages <= 0 || maximum <= 0) return
    val results = ArrayList<PdfMatch>()
    val issues = ArrayList<PdfSearchIssue>()
    var failures = 0
    for (page in 0 until pages) {
        currentCoroutineContext().ensureActive()
        try {
            val text = read(page)
            currentCoroutineContext().ensureActive()
            if (text.limited) {
                failures++
                if (issues.size < 10)
                    issues +=
                        PdfSearchIssue(
                            page,
                            "The text layer exceeds this preview's 200,000-character page limit.",
                        )
            }
            results +=
                withContext(Dispatchers.Default) {
                    pdfMatches(page, text.text, query, maximum - results.size)
                }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException) throw error
            failures++
            if (issues.size < 10)
                issues +=
                    PdfSearchIssue(
                        page,
                        error.message?.take(240) ?: "Couldn't read this page's text",
                    )
        }
        currentCoroutineContext().ensureActive()
        val capped = results.size >= maximum
        publish(PdfSearchBatch(results.toList(), page + 1, failures, issues.toList(), capped))
        if (capped) return
        yield()
    }
}
