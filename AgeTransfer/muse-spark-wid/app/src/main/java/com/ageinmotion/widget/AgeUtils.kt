package com.ageinmotion.widget

/** Pure math helpers — kept separate so the logic is unit-testable. */
object AgeUtils {
    /** Average Gregorian year in milliseconds. */
    const val YEAR_MS = 31_556_952_000.0

    /** Fractional age in years for [dobMillis] → [nowMillis]. Never negative. */
    fun ageYears(dobMillis: Long, nowMillis: Long): Double =
        ((nowMillis - dobMillis).coerceAtLeast(0L) / YEAR_MS)

    /** Whole days lived. */
    fun daysAlive(dobMillis: Long, nowMillis: Long): Long =
        ((nowMillis - dobMillis).coerceAtLeast(0L) / 86_400_000L)

    /** Life-progress percent (0..100) given a life expectancy in years. */
    fun lifeProgress(dobMillis: Long, nowMillis: Long, lifeExpectancyYears: Int): Int =
        ((ageYears(dobMillis, nowMillis) / lifeExpectancyYears.coerceIn(1, 130)) * 100.0)
            .toInt().coerceIn(0, 100)

    /** Decimal age string with [decimals] places, e.g. 9 → "27.318836116". */
    fun formatAge(years: Double, decimals: Int): String =
        "%.${decimals.coerceIn(0, 10)}f".format(java.util.Locale.US, years)

    /** Whole hours lived. */
    fun hoursAlive(dobMillis: Long, nowMillis: Long): Long =
        ((nowMillis - dobMillis).coerceAtLeast(0L) / 3_600_000L)

    /** Approximate heartbeats at 72 bpm (same stat as the web recreation). */
    fun heartbeats(dobMillis: Long, nowMillis: Long): Long =
        ((nowMillis - dobMillis).coerceAtLeast(0L) / 60_000L) * 72L

    /**
     * Days until the next birthday, using the same approximation as the web
     * recreation: (ceil(years) - years) * 365.2425.
     */
    fun nextBirthdayInDays(dobMillis: Long, nowMillis: Long): Long {
        val years = ageYears(dobMillis, nowMillis)
        return (((kotlin.math.ceil(years) - years) * 365.2425).toLong()).coerceAtLeast(0L)
    }

    /**
     * Milliseconds until the displayed decimal string would actually change.
     * The live engine sleeps exactly this long between redraws — e.g. ~31ms
     * at 9 decimals but ~31s at 6 and days at 2 — so it never burns CPU or
     * binder traffic on frames nobody could see. Clamped to [60ms, 60s].
     */
    fun msUntilTextChange(dobMillis: Long, nowMillis: Long, decimals: Int): Long {
        val stepYears = Math.pow(10.0, -decimals.coerceIn(0, 10).toDouble())
        val elapsed = (nowMillis - dobMillis).coerceAtLeast(0L).toDouble()
        val elapsedSteps = elapsed / YEAR_MS / stepYears
        val remainingMs = ((kotlin.math.floor(elapsedSteps) + 1) * stepYears * YEAR_MS - elapsed)
        if (!remainingMs.isFinite()) return 60_000L
        return remainingMs.toLong().coerceIn(60L, 60_000L)
    }

    /** Calendar-correct countdown decomposition. Months are whole calendar
     *  months (variable lengths handled by Calendar); the remainder splits
     *  into days/hours/mins/secs/tenths. A passed target yields zeros. */
    data class Countdown(
        val months: Long,
        val days: Long,
        val hours: Long,
        val mins: Long,
        val secs: Long,
        val tenth: Long,
        val expired: Boolean,
    )

    fun countdownParts(
        fromMs: Long,
        toMs: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Countdown {
        if (toMs <= fromMs) return Countdown(0, 0, 0, 0, 0, 0, true)
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = fromMs }
        var months = 0L
        while (true) {
            val trial = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, 1) }
            if (trial.timeInMillis <= toMs) {
                cal.timeInMillis = trial.timeInMillis
                months++
            } else break
        }
        var rem = toMs - cal.timeInMillis
        val days = rem / 86_400_000; rem %= 86_400_000
        val hours = rem / 3_600_000; rem %= 3_600_000
        val mins = rem / 60_000; rem %= 60_000
        val secs = rem / 1000
        val tenth = (rem % 1000) / 100
        return Countdown(months, days, hours, mins, secs, tenth, false)
    }

    /** Numbers only, e.g. "02·05·05·29·447" — the last group is seconds
     *  with the fast tenths digit appended. Leading zero groups are dropped
     *  so the eye tracks only live units ("05·067" instead of a wall of
     *  zeros); an empty/expired countdown is a single "0". The middot
     *  (U+00B7) sits at mid height, unlike the baseline decimal point. */
    fun formatCountdown(c: Countdown): String {
        val u = java.util.Locale.US
        fun p2(n: Long) = String.format(u, "%02d", n)
        if (c.months == 0L && c.days == 0L && c.hours == 0L &&
            c.mins == 0L && c.secs == 0L && c.tenth == 0L
        ) {
            return "0"
        }
        val tail = "${p2(c.secs)}${c.tenth}"
        return when {
            c.months > 0 -> "${p2(c.months)}·${p2(c.days)}·${p2(c.hours)}·${p2(c.mins)}·$tail"
            c.days > 0 -> "${p2(c.days)}·${p2(c.hours)}·${p2(c.mins)}·$tail"
            c.hours > 0 -> "${p2(c.hours)}·${p2(c.mins)}·$tail"
            c.mins > 0 -> "${p2(c.mins)}·$tail"
            else -> tail
        }
    }

    /** Sleep until the next exact 100ms wall-clock boundary, so the tenths
     *  digit turns over precisely when the clock does (no cumulative drift). */
    fun countdownFrameDelay(nowMs: Long): Long {
        val r = 100L - (nowMs % 100L)
        return if (r <= 0L) 100L else r
    }
}
