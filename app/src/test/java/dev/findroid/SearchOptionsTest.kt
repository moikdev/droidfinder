package dev.findroid

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SearchOptionsTest {
    private fun calendar(instant: String, zone: String = "UTC", locale: Locale = Locale.US) =
        SearchCalendar(Instant.parse(instant).toEpochMilli(), ZoneId.of(zone), locale)
    private fun entry(bytes: Long = 12, modified: String = "2026-11-03T12:00:00Z") =
        Entry(File("/fixture/Image.png"), folder = false, size = bytes, modified = Instant.parse(modified).toEpochMilli())

    @Test fun calendarDaysUseZoneBoundariesAndDstInsteadOfRollingHours() {
        for ((day, hours) in listOf("2026-03-08T18:00:00Z" to 23, "2026-11-01T18:00:00Z" to 25)) {
            val context = calendar(day, "America/New_York")
            val bounds = SearchDate.TODAY.bounds(context)!!
            assertEquals(hours * 3_600_000L, bounds.last - bounds.first + 1)
            val rules = SearchRules(date = SearchDate.TODAY)
            assertTrue(rules.matches(entry().copy(modified = bounds.first), emptySet(), calendar = context))
            assertTrue(rules.matches(entry().copy(modified = bounds.last), emptySet(), calendar = context))
            assertFalse(rules.matches(entry().copy(modified = bounds.first - 1), emptySet(), calendar = context))
            assertFalse(rules.matches(entry().copy(modified = bounds.last + 1), emptySet(), calendar = context))
        }
    }

    @Test fun weeksFollowLocaleAndMonthsFollowCalendarIncludingYearBoundary() {
        val sunday = "2027-01-03T12:00:00Z"
        assertEquals(Instant.parse("2027-01-03T00:00:00Z").toEpochMilli(), SearchDate.THIS_WEEK.bounds(calendar(sunday))!!.first)
        assertEquals(Instant.parse("2026-12-28T00:00:00Z").toEpochMilli(), SearchDate.THIS_WEEK.bounds(calendar(sunday, locale = Locale.UK))!!.first)
        assertEquals(Instant.parse("2027-02-01T00:00:00Z").toEpochMilli() - 1, SearchDate.THIS_MONTH.bounds(calendar(sunday))!!.last)
        assertNull(SearchDate.ANY.bounds(calendar(sunday)))
    }

    @Test fun savedCalendarChoicesReevaluateButLegacyDaysKeepRollingMeaning() {
        val rules = SearchRules(date = SearchDate.TODAY)
        val item = entry(modified = "2026-11-03T01:00:00Z")
        assertTrue(rules.matches(item, emptySet(), calendar = calendar("2026-11-03T23:00:00Z")))
        assertFalse(rules.matches(item, emptySet(), calendar = calendar("2026-11-04T00:00:00Z")))
        val previousDay = entry(modified = "2026-11-02T23:30:00Z")
        val now = Instant.parse("2026-11-03T00:30:00Z").toEpochMilli()
        assertTrue(SearchRules(days = 1).matches(previousDay, emptySet(), now = now))
        assertFalse(rules.matches(previousDay, emptySet(), now = now, calendar = calendar("2026-11-03T00:30:00Z")))
        assertEquals(SearchDate.ANY, SearchRules(days = 1).date)
    }

    @Test fun sizePresetsHaveStrictThresholdsAndIntersectImportedBounds() {
        val gb = 1024L * 1024 * 1024
        val large = SearchRules(size = SearchSize.OVER_1_GB)
        assertFalse(large.matches(entry(gb), emptySet()))
        assertTrue(large.matches(entry(gb + 1), emptySet()))
        assertFalse(large.matches(entry(gb + 1).copy(sizeKnown = false), emptySet()))
        assertFalse(large.matches(entry(gb + 1).copy(folder = true), emptySet()))
        assertFalse(large.copy(maximumBytes = gb).matches(entry(gb + 1), emptySet()))
        assertTrue(large.copy(minimumBytes = gb + 2).matches(entry(gb + 2), emptySet()))
        assertFalse(large.copy(minimumBytes = gb + 2).matches(entry(gb + 1), emptySet()))
        val small = SearchRules(size = SearchSize.UNDER_1_MB)
        assertTrue(small.matches(entry(1024L * 1024 - 1), emptySet()))
        assertFalse(small.matches(entry(1024L * 1024), emptySet()))
    }

    @Test fun expertSizeEditingRoundTripsEveryWholeByteWithoutRounding() {
        for (bytes in listOf(0, 1, 27, 1023, 1024, 1025, 1999999, Long.MAX_VALUE)) {
            assertEquals(bytes, searchBytes(searchKilobytes(bytes)))
        }
        for (text in listOf("-1", "nonsense", "1e5", "NaN", "0.0001", "9223372036854775808", "1,024", ".")) {
            assertNull(text, searchBytes(text))
        }
        assertEquals(0L, searchBytes(" "))
        assertEquals(1536L, searchBytes("1.5"))
    }
}
