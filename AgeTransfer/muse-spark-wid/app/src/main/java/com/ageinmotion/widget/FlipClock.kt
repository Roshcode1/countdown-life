package com.ageinmotion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Split-flap (flipclock.net style) renderer. Each character is a dark card
 * with a center split line; while a digit changes, its top half already
 * shows the new value and the bottom half still the old one, with a moving
 * shadow band — a flip animation faked convincingly at ~8-12fps, which is
 * all a home-screen widget can push through RemoteViews anyway.
 */
object FlipClock {

    /** Full flip duration; frames are ~80ms apart while any digit flips. */
    const val FLIP_MS = 180L
    const val FRAME_MS = 80L

    const val FONT_PX = 64f
    /** Biggest we'll scale flaps (keeps bitmaps under the binder cap). */
    const val MAX_FONT_PX = 110f
    private const val CARD = 0xFF262626.toInt()

    @Volatile
    private var cachedTypeface: Typeface? = null
    @Volatile
    private var typefaceLoaded = false

    /** Space Grotesk Bold for flap digits, cached; falls back to bold. */
    fun typeface(context: Context): Typeface {
        cachedTypeface?.let { return it }
        if (!typefaceLoaded) {
            typefaceLoaded = true
            cachedTypeface = try {
                ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
            } catch (_: Exception) {
                null
            }
        }
        return cachedTypeface ?: Typeface.DEFAULT_BOLD
    }

    /** 0..1 progress of the flip that started at [startMs]. Pure + tested. */
    fun flipProgress(nowMs: Long, startMs: Long): Float =
        ((nowMs - startMs).toFloat() / FLIP_MS).coerceIn(0f, 1f)

    /** Indices whose character changed (up to the shorter length). Pure + tested. */
    fun changedPositions(text: String, prev: String): List<Int> {
        val out = ArrayList<Int>()
        val n = minOf(text.length, prev.length)
        for (i in 0 until n) if (text[i] != prev[i]) out.add(i)
        return out
    }

    fun render(
        text: String,
        prev: String,
        progress: Float,
        digitColor: Int = Color.WHITE,
        typeface: Typeface? = null,
        fontPx: Float = FONT_PX,
    ): Bitmap {
        val settled = progress >= 1f || prev.length != text.length
        val chars = text.toCharArray()

        val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface ?: Typeface.DEFAULT_BOLD
            textSize = fontPx
            color = digitColor
            textAlign = Paint.Align.CENTER
        }
        // Uniform cell from the widest glyph so flaps never jitter.
        var adv = 0f
        for (c in "0123456789.-") adv = maxOf(adv, digit.measureText(c.toString()))
        val cellW = adv + fontPx * 0.47f
        val cardH = fontPx * 1.32f
        val n = chars.size
        val gapPx = fontPx * 0.125f
        val radiusPx = fontPx * 0.22f
        val w = (n * cellW + (n - 1) * gapPx).roundToInt().coerceAtLeast(1)
        val h = cardH.roundToInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CARD
        }
        val topSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 14
        }
        val split = Paint().apply {
            color = Color.BLACK
            alpha = 200
            strokeWidth = fontPx * 0.05f
        }

        val fm = digit.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        val p = progress.coerceIn(0f, 1f)
        // Shadow band sweeps the card mid-flip, strongest halfway.
        val bandAlpha = if (settled) 0 else (sin(p * Math.PI) * 110).roundToInt()
        val bandPaint = Paint().apply {
            color = Color.BLACK
            alpha = bandAlpha
        }

        for (i in chars.indices) {
            val left = i * (cellW + gapPx)
            val rect = RectF(left, 0f, left + cellW, h.toFloat())
            canvas.drawRoundRect(rect, radiusPx, radiusPx, card)

            val newCh = chars[i].toString()
            val oldCh = if (settled) newCh else prev[i].toString()
            val cx = left + cellW / 2f
            val mid = h / 2f

            // Top half: the incoming digit.
            canvas.save()
            canvas.clipRect(left, 0f, left + cellW, mid + 1f)
            canvas.drawText(newCh, cx, baseline, digit)
            canvas.drawRect(left, 0f, left + cellW, mid, topSheen)
            canvas.restore()

            // Bottom half: the outgoing digit until the flip lands.
            canvas.save()
            canvas.clipRect(left, mid, left + cellW, h.toFloat())
            canvas.drawText(oldCh, cx, baseline, digit)
            canvas.restore()

            // Split line + travelling shadow band.
            canvas.drawLine(left + 4f, mid, left + cellW - 4f, mid, split)
            if (bandAlpha > 0) {
                canvas.drawRect(left, mid - h * 0.11f, left + cellW, mid + h * 0.11f, bandPaint)
            }
        }
        return bmp
    }
}
