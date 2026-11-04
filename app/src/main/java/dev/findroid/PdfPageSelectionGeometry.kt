package dev.findroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Use the existing extraction permission, cancellation and per-page text limit. */
internal suspend fun pdfSelectionMap(content: PdfPageText): PdfSelectionMap = withContext(Dispatchers.Default) {
    val job = currentCoroutineContext()
    PdfSelectionMap(content.text, content.spans.asSequence()
        .filter { it.start >= 0 && it.end > it.start && it.end <= content.text.length }
        .flatMap { span -> span.bounds.asSequence().map { box ->
            PdfSelectionGlyph(PdfTextRange(span.start, span.end),
                PdfSelectionRect(box.left, box.top, box.right, box.bottom))
        } }, content.limited, checkActive = { job.ensureActive() })
}
