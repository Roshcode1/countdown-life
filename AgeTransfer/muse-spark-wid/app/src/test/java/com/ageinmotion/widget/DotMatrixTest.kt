package com.ageinmotion.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotMatrixTest {

    @Test
    fun `measure matches reference glyph widths`() {
        // 11 digits x5 + dot x1 + 11 gaps = 56 + 11 = 67 cells.
        assertEquals(67, DotMatrix.measureCells("27.318836116"))
    }

    @Test
    fun `unknown chars fall back to blank`() {
        assertEquals(3, DotMatrix.measureCells("~"))
    }

    @Test
    fun `middot separator sits at mid height, decimal point at baseline`() {
        val mid = DotMatrix.layout("·")
        assertEquals(1, mid.cols)
        assertEquals(1, mid.lit.size)
        assertEquals(3, mid.lit[0].y)
        val dot = DotMatrix.layout(".")
        assertEquals(DotMatrix.ROWS - 1, dot.lit[0].y)
    }

    @Test
    fun `layout covers every grid cell exactly once`() {
        // Glyph area: (11 digits x5 + dot x1) cols x 7 rows = 392 cells.
        val dots = DotMatrix.layout("27.318836116")
        assertEquals(67, dots.cols)
        assertEquals(392, dots.lit.size + dots.unlit.size)
        assertTrue(dots.lit.isNotEmpty())
        assertTrue(dots.unlit.isNotEmpty())
        for (c in dots.lit + dots.unlit) {
            assertTrue(c.x in 0 until dots.cols)
            assertTrue(c.y in 0 until DotMatrix.ROWS)
        }
    }

    @Test
    fun `rendered bitmap stays far under the binder limit`() {
        // Pure-math estimate of what render() allocates (ARGB_8888 = 4 B/px,
        // transparent so the card shows through).
        val cols = DotMatrix.measureCells("129.9999999999") // widest realistic age
        val bytes = (cols * DotMatrix.CELL_PX).toInt() *
            (DotMatrix.ROWS * DotMatrix.CELL_PX).toInt() * 4
        assertTrue("estimated $bytes bytes", bytes < 512 * 1024)
    }

    @Test
    fun `card alpha maps opacity percent to 0-255`() {
        assertEquals(255, AgeWidgetRenderer.cardAlpha(settings(100)))
        assertEquals(0, AgeWidgetRenderer.cardAlpha(settings(0)))
        assertEquals(127, AgeWidgetRenderer.cardAlpha(settings(50)))
    }

    private fun settings(opacity: Int) =
        AgeWidgetRenderer.WidgetSettings(
            dobMillis = 0L, decimals = 9, ghost = false, live = false,
            opacity = opacity, style = AgeWidgetRenderer.STYLE_DOTS,
            dotColor = AgeWidgetRenderer.DEFAULT_DOT_COLOR,
            mode = AgeWidgetRenderer.MODE_AGE,
            digitSize = AgeWidgetRenderer.DEFAULT_DIGIT_SIZE,
        )

    @Test
    fun `digit padding shrinks digits as size drops`() {
        assertEquals(8f, AgeWidgetRenderer.digitPaddingDp(100), 0.001f)
        assertEquals(32f, AgeWidgetRenderer.digitPaddingDp(40), 0.001f)
        // Out-of-range inputs clamp instead of breaking layout.
        assertEquals(32f, AgeWidgetRenderer.digitPaddingDp(0), 0.001f)
        assertEquals(8f, AgeWidgetRenderer.digitPaddingDp(150), 0.001f)
    }

    @Test
    fun `formatAge honors decimals like the site`() {
        assertEquals("27.318836116", AgeUtils.formatAge(27.318836116, 9))
        assertEquals("27.32", AgeUtils.formatAge(27.318836116, 2))
        assertEquals("27", AgeUtils.formatAge(27.318836116, 0))
    }

    @Test
    fun `stats match the web recreation`() {
        val dob = 0L
        val now = 60_000L
        assertEquals(72L, AgeUtils.heartbeats(dob, now))
        assertEquals(0L, AgeUtils.hoursAlive(dob, now))
        assertEquals(0L, AgeUtils.nextBirthdayInDays(now, now))
        // Half a year in -> ~182 days to next birthday.
        val halfYear = (AgeUtils.YEAR_MS / 2).toLong()
        assertEquals(182L, AgeUtils.nextBirthdayInDays(now - halfYear, now))
    }
}
