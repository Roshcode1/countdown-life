package com.ageinmotion.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    @Test
    fun `9 decimals ticks at the floor rate`() {
        // 1e-9 year ≈ 31.5ms -> clamped to the 60ms floor.
        assertEquals(60L, AgeUtils.msUntilTextChange(0L, 1_000_000_000L, 9))
    }

    @Test
    fun `0 decimals sleeps at the ceiling`() {
        // Whole years change at most yearly -> clamped to 60s.
        assertEquals(60_000L, AgeUtils.msUntilTextChange(0L, 1_000_000_000L, 0))
    }

    @Test
    fun `6 decimals mid-cycle waits ~31 seconds`() {
        // Step = 1e-6 year ≈ 31.557s. Half-step elapsed -> ~15.7s remaining.
        val halfStepMs = (AgeUtils.YEAR_MS * 1e-6 / 2).toLong()
        val wait = AgeUtils.msUntilTextChange(0L, halfStepMs, 6)
        assertTrue("wait=$wait", wait in 15_000L..16_500L)
    }

    @Test
    fun `decimals clamp to 0-10`() {
        assertEquals(
            AgeUtils.msUntilTextChange(0L, 1_000_000_000L, 9),
            AgeUtils.msUntilTextChange(0L, 1_000_000_000L, 99),
        )
    }

    @Test
    fun `future dob never returns nonsense`() {
        val wait = AgeUtils.msUntilTextChange(2_000_000L, 1_000_000L, 9)
        assertTrue(wait in 60L..60_000L)
    }
}
