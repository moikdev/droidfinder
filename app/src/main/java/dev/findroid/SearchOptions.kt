package dev.findroid

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** Captured once per listing. Calendar choices are reevaluated on every refresh. */
data class SearchCalendar(
    val now: Long = System.currentTimeMillis(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val locale: Locale = Locale.getDefault(),
)

enum class SearchDate(val title: String) {
    ANY("Any time"), TODAY("Today"), THIS_WEEK("This week"), THIS_MONTH("This month");

    fun bounds(calendar: SearchCalendar): LongRange? {
        if (this == ANY) return null
        val today = Instant.ofEpochMilli(calendar.now).atZone(calendar.zone).toLocalDate()
        val start = when (this) {
            TODAY -> today
            THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(WeekFields.of(calendar.locale).firstDayOfWeek))
            THIS_MONTH -> today.withDayOfMonth(1)
            ANY -> return null
        }
        val end = when (this) {
            TODAY -> start.plusDays(1)
            THIS_WEEK -> start.plusWeeks(1)
            THIS_MONTH -> start.plusMonths(1)
            ANY -> return null
        }
        return start.atStartOfDay(calendar.zone).toInstant().toEpochMilli() until
            end.atStartOfDay(calendar.zone).toInstant().toEpochMilli()
    }
}

internal fun searchCalendarDescription(date: SearchDate, calendar: SearchCalendar): String {
    val bounds = date.bounds(calendar) ?: return "Any time"
    val format = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(calendar.locale)
    val first = Instant.ofEpochMilli(bounds.first).atZone(calendar.zone).toLocalDate().format(format)
    val last = Instant.ofEpochMilli(bounds.last).atZone(calendar.zone).toLocalDate().format(format)
    return date.title + ": " + first + (if (first == last) "" else " – $last") + " · ${calendar.zone.id}"
}

enum class SearchSize(val title: String, val minimum: Long = 0, val maximum: Long = 0) {
    ANY("Any size"),
    UNDER_1_MB("Under 1 MB", maximum = 1024L * 1024 - 1),
    OVER_1_GB("Over 1 GB", minimum = 1024L * 1024 * 1024 + 1),
}

/** Lossless decimal KB, including imported bounds that are not multiples of 1024. */
internal fun searchKilobytes(bytes: Long): String =
    if (bytes == 0L) "" else BigDecimal.valueOf(bytes).divide(BigDecimal(1024)).stripTrailingZeros().toPlainString()

internal fun searchBytes(kilobytes: String): Long? {
    if (kilobytes.isBlank()) return 0
    if (kilobytes.length > 80 || !kilobytes.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) return null
    return runCatching { BigDecimal(kilobytes).multiply(BigDecimal(1024)).longValueExact().takeIf { it >= 0 } }.getOrNull()
}

data class MatchRange(val start: Int, val end: Int)
data class ContentSnippet(val text: String, val ranges: List<MatchRange>, val leading: Boolean, val trailing: Boolean)
data class SearchEvidence(
    val nameRanges: List<MatchRange> = emptyList(),
    val reasons: List<String> = emptyList(),
    val snippet: ContentSnippet? = null,
) {
    val explained get() = reasons.isNotEmpty() || snippet != null
}

/** Offsets always refer to the original string, including Unicode case variants. */
internal fun matchRanges(text: String, terms: List<String>, limit: Int = 24): List<MatchRange> {
    val spans = mutableListOf<MatchRange>()
    for (term in terms.filter(String::isNotBlank).distinct()) {
        var from = 0
        while (from <= text.length - term.length && spans.size < limit) {
            val at = text.indexOf(term, from, ignoreCase = true)
            if (at < 0) break
            spans += MatchRange(at, at + term.length)
            from = at + 1
        }
        if (spans.size >= limit) break
    }
    val result = mutableListOf<MatchRange>()
    for (span in spans.sortedBy { it.start }) {
        val previous = result.lastOrNull()
        if (previous != null && span.start <= previous.end) {
            result[result.lastIndex] = previous.copy(end = maxOf(previous.end, span.end))
        } else result += span
    }
    return result.toList()
}
