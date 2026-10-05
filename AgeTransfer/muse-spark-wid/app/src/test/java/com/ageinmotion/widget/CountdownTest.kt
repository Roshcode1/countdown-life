package com.ageinmotion.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Deterministic proof the countdown math is exact (fixed UTC timestamps). */
class CountdownTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            set(y, mo, d, h, mi, s)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    @Test
    fun `months days time split is calendar-correct`() {
        // 2026-01-10 04:30:15.250 -> 2026-03-15 10:00:00.000 UTC
        // = 2 whole months + 5d 5h 29m 44.75s.
        val c = AgeUtils.countdownParts(
            ms(2026, Calendar.JANUARY, 10, 4, 30, 15, 250),
            ms(2026, Calendar.MARCH, 15, 10, 0, 0),
            utc,
        )
        assertEquals(2L, c.months)
        assertEquals(5L, c.days)
        assertEquals(5L, c.hours)
        assertEquals(29L, c.mins)
        assertEquals(44L, c.secs)
        assertEquals(7L, c.tenth)
    }

    @Test
    fun `leap february and month-end clipping`() {
        // 2028-01-31 -> 2028-03-01 UTC: Jan31 +1mo clips to Feb29 (leap),
        // leaving exactly 1 day.
        val c = AgeUtils.countdownParts(
            ms(2028, Calendar.JANUARY, 31, 12, 0, 0),
            ms(2028, Calendar.MARCH, 1, 12, 0, 0),
            utc,
        )
        assertEquals(1L, c.months)
        assertEquals(1L, c.days)
        assertEquals(0L, c.hours)
    }

    @Test
    fun `passed target freezes at zero`() {
        val c = AgeUtils.countdownParts(2_000_000L, 1_000_000L, utc)
        assertTrue(c.expired)
        assertEquals("0", AgeUtils.formatCountdown(c))
    }

    @Test
    fun `tenths digit resolves sub-second remainders`() {
        val c = AgeUtils.countdownParts(0L, 1750L, utc)
        assertEquals(1L, c.secs)
        assertEquals(7L, c.tenth)
    }

    @Test
    fun `format matches widget glyph set`() {
        val c = AgeUtils.countdownParts(
            ms(2026, Calendar.JANUARY, 10, 4, 30, 15, 250),
            ms(2026, Calendar.MARCH, 15, 10, 0, 0),
            utc,
        )
        assertEquals("02·05·05·29·447", AgeUtils.formatCountdown(c))
    }

    @Test
    fun `frame delay lands on 100ms boundaries`() {
        assertEquals(100L, AgeUtils.countdownFrameDelay(1000L))
        assertEquals(1L, AgeUtils.countdownFrameDelay(1099L))
        assertEquals(50L, AgeUtils.countdownFrameDelay(1050L))
    }

    @Test
    fun `leading zero groups are dropped`() {
        // 3d 4h 5m 6.7s left -> days lead, months gone.
        var c = AgeUtils.countdownParts(
            ms(2026, Calendar.JANUARY, 10, 0, 0, 0),
            ms(2026, Calendar.JANUARY, 13, 4, 5, 6, 700),
            utc,
        )
        assertEquals("03·04·05·067", AgeUtils.formatCountdown(c))
        // 2h 3m 4.5s left -> hours lead.
        c = AgeUtils.countdownParts(
            ms(2026, Calendar.JANUARY, 10, 0, 0, 0),
            ms(2026, Calendar.JANUARY, 10, 2, 3, 4, 500),
            utc,
        )
        assertEquals("02·03·045", AgeUtils.formatCountdown(c))
        // Seconds only.
        c = AgeUtils.countdownParts(0L, 4750L, utc)
        assertEquals("047", AgeUtils.formatCountdown(c))
    }

    @Test
    fun `adaptive sizes grow as groups drop`() {
        val full = AgeUtils.formatCountdown(
            AgeUtils.countdownParts(
                ms(2026, Calendar.JANUARY, 10, 4, 30, 15, 250),
                ms(2026, Calendar.MARCH, 15, 10, 0, 0),
                utc,
            ),
        )
        val short = "047"
        assertTrue(AgeWidgetRenderer.countdownCellPx(short) > AgeWidgetRenderer.countdownCellPx(full))
        assertEquals(28f, AgeWidgetRenderer.countdownCellPx(short), 0.001f)
        assertTrue(AgeWidgetRenderer.countdownFontPx(short) > AgeWidgetRenderer.countdownFontPx(full))
        assertEquals(64f, AgeWidgetRenderer.countdownFontPx(full), 0.001f)
        assertEquals(110f, AgeWidgetRenderer.countdownFontPx(short), 0.001f)
    }
}
