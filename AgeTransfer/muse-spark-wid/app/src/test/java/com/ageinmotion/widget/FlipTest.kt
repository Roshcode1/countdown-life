package com.ageinmotion.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipTest {

    @Test
    fun `flip progress sweeps 0 to 1`() {
        assertEquals(0f, FlipClock.flipProgress(1000L, 1000L))
        assertEquals(0.5f, FlipClock.flipProgress(1090L, 1000L))
        assertEquals(1f, FlipClock.flipProgress(5000L, 1000L))
    }

    @Test
    fun `changed positions pinpoint flipping digits`() {
        assertEquals(listOf(4), FlipClock.changedPositions("27.31", "27.32"))
        assertTrue(FlipClock.changedPositions("27.31", "27.31").isEmpty())
        assertEquals(listOf(0, 3), FlipClock.changedPositions("29.9", "39.8"))
    }

    @Test
    fun `flip duration matches snappy board feel`() {
        // ~2 animation frames at 80ms each land the flip.
        assertTrue(FlipClock.FLIP_MS in 100L..300L)
        assertEquals(80L, FlipClock.FRAME_MS)
    }
}
