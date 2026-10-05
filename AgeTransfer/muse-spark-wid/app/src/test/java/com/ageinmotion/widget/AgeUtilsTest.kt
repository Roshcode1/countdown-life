package com.ageinmotion.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AgeUtilsTest {

    private fun midnightUtc(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `exactly 25 Gregorian years is 25 point 0`() {
        // 25 avg Gregorian years = 25 * 365.2425 days.
        val dob = midnightUtc(2000, Calendar.JANUARY, 1)
        val now = dob + (25 * AgeUtils.YEAR_MS).toLong()
        assertEquals(25.0, AgeUtils.ageYears(dob, now), 1e-9)
    }

    @Test
    fun `known calendar span matches decimal age`() {
        // 1999-05-14 -> 2026-09-07 is 9978 days.
        val dob = midnightUtc(1999, Calendar.MAY, 14)
        val now = midnightUtc(2026, Calendar.SEPTEMBER, 7)
        assertEquals(9978L, AgeUtils.daysAlive(dob, now))
        val expected = 9978.0 / 365.2425
        assertEquals(expected, AgeUtils.ageYears(dob, now), 1e-6)
        assertTrue(AgeUtils.ageYears(dob, now) > 27.31)
    }

    @Test
    fun `future dob clamps to zero`() {
        assertEquals(0.0, AgeUtils.ageYears(2_000_000L, 1_000_000L), 0.0)
        assertEquals(0L, AgeUtils.daysAlive(2_000_000L, 1_000_000L))
    }

    @Test
    fun `life progress is bounded 0 to 100`() {
        val dob = midnightUtc(2000, Calendar.JANUARY, 1)
        val now = dob + (90 * AgeUtils.YEAR_MS).toLong()
        assertEquals(100, AgeUtils.lifeProgress(dob, now, 80))
        assertEquals(0, AgeUtils.lifeProgress(now, now, 80))
        assertEquals(50, AgeUtils.lifeProgress(dob, dob + (40 * AgeUtils.YEAR_MS).toLong(), 80))
    }
}
