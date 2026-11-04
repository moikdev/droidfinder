package dev.findroid

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Fixed, reviewed language files. Recognition never downloads or selects a language implicitly. */
internal data class OcrLanguage(val id: String, val label: String, val bytes: Long, val sha256: String) {
    val filename get() = "$id.traineddata"
    val downloadUrl get() = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/65727574dfcd264acbb0c3e07860e4e9e9b22185/$filename"
}

internal object OcrPolicy {
    const val maximumPixels = 4_194_304L
    const val maximumEdge = 2400
    const val maximumText = 200_000
    const val recognitionMillis = 30_000L
    const val maximumStoredBytes = 64L * 1024 * 1024
    const val maximumIncompleteFiles = 12
    val languages = listOf(
        OcrLanguage("eng", "English", 4_113_088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
        OcrLanguage("jpn", "Japanese", 2_471_260, "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d"),
    )
    fun language(id: String) = languages.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("Choose an available OCR language.")

    /** Use a single sampled page/image; no document-wide OCR or unbounded bitmap is queued. */
    fun raster(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "The image has no readable dimensions." }
        val scale = min(1.0, min(maximumEdge.toDouble() / maxOf(width, height),
            sqrt(maximumPixels.toDouble() / width.toDouble() / height.toDouble())))
        return maxOf(1, floor(width * scale).toInt()) to maxOf(1, floor(height * scale).toInt())
    }

    fun windows(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            var end = min(text.length, start + 4000)
            var lines = 0
            for (index in start until end) if (text[index] == '\n' && ++lines == 80) { end = index + 1; break }
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            result += start until end
            start = end
        }
        return result
    }

    fun result(text: String, confidence: Int): OcrText {
        // Never cut between a UTF-16 surrogate pair. Embedded line breaks stay available for copy.
        var end = min(text.length, maximumText)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return OcrText(text.substring(0, end), confidence.coerceIn(0, 100), text.length > end)
    }
}

internal data class OcrText(val text: String, val confidence: Int, val limited: Boolean)
