package com.ageinmotion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * 5x7 dot-matrix renderer, ported glyph-for-glyph from the web recreation's
 * DotMatrix component (Nothing NDot style). RemoteViews can't draw custom
 * views/SVG, so the widget renders the age into a [Bitmap] and shows it in
 * an ImageView — visually identical dots on any launcher.
 */
object DotMatrix {

    const val ROWS = 7
    private const val GAP = 1
    private const val DOT_RADIUS_CELLS = 0.40f

    // Bitmaps stay small for the ~1MB binder cap: ARGB_8888 (needed so the
    // card behind can show through) at 15px cells keeps even 10-decimal
    // ages under ~500KB. The bitmap itself is fully transparent — the card
    // color/alpha lives in the layout so there is never a visible seam.
    const val CELL_PX = 15f
    private const val GHOST_ALPHA = 18 // ~0.07, matches the site's faint dots

    private val GLYPHS: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to arrayOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
        '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '.' to arrayOf("0", "0", "0", "0", "0", "0", "1"),
        // Middle dot (U+00B7): vertically-centered separator for countdown
        // groups. The plain '.' stays at the baseline as a true decimal point.
        '·' to arrayOf("0", "0", "0", "1", "0", "0", "0"),
        ':' to arrayOf("0", "1", "0", "0", "0", "1", "0"),
        '-' to arrayOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
        ' ' to arrayOf("000", "000", "000", "000", "000", "000", "000"),
        'A' to arrayOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
        'D' to arrayOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
        'E' to arrayOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
        'G' to arrayOf("01110", "10001", "10000", "10111", "10001", "10001", "01111"),
        'H' to arrayOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
        'I' to arrayOf("01110", "00100", "00100", "00100", "00100", "00100", "01110"),
        'M' to arrayOf("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
        'N' to arrayOf("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
        'O' to arrayOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
        'R' to arrayOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
        'S' to arrayOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
        'T' to arrayOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
        'Y' to arrayOf("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    )

    private fun glyphFor(ch: Char): Array<String> =
        GLYPHS[ch.uppercaseChar()] ?: GLYPHS[' ']!!

    /** Framework-free dot computation — unit-testable without Robolectric. */
    data class Cell(val x: Int, val y: Int)
    data class DotLayout(val cols: Int, val lit: List<Cell>, val unlit: List<Cell>)

    fun layout(text: String): DotLayout {
        val lit = ArrayList<Cell>()
        val unlit = ArrayList<Cell>()
        var cursor = 0
        for (raw in text) {
            val g = glyphFor(raw)
            val gw = g[0].length
            for (y in 0 until ROWS) {
                val row = g[y]
                for (x in 0 until gw) {
                    val cell = Cell(cursor + x, y)
                    if (row[x] == '1') lit.add(cell) else unlit.add(cell)
                }
            }
            cursor += gw + GAP
        }
        return DotLayout(cols = maxOf(cursor - GAP, 1), lit = lit, unlit = unlit)
    }

    /** Grid width in cells for [text] (same math as the site's SVG viewBox). */
    fun measureCells(text: String): Int {
        var cursor = 0
        for (ch in text) {
            cursor += glyphFor(ch)[0].length + GAP
        }
        return maxOf(cursor - GAP, 1)
    }

    /**
     * Renders [text] as flat white dots on a transparent background —
     * the card behind (with its opacity) shows through seamlessly.
     * @param ghost draws faint unlit dots (the site's "Unlit dots: Faint" option).
     */
    fun render(
        text: String,
        ghost: Boolean = false,
        dotColor: Int = Color.WHITE,
        cellPx: Float = CELL_PX,
    ): Bitmap {
        val cols = measureCells(text)
        val w = (cols * cellPx).roundToInt().coerceAtLeast(1)
        val h = (ROWS * cellPx).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val radius = DOT_RADIUS_CELLS * cellPx
        val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
        val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dotColor
            alpha = GHOST_ALPHA
        }

        val dots = layout(text)
        for (cell in dots.lit) {
            canvas.drawCircle(
                (cell.x + 0.5f) * cellPx, (cell.y + 0.5f) * cellPx, radius, litPaint,
            )
        }
        if (ghost) {
            for (cell in dots.unlit) {
                canvas.drawCircle(
                    (cell.x + 0.5f) * cellPx, (cell.y + 0.5f) * cellPx, radius, faintPaint,
                )
            }
        }
        return bmp
    }
}
