package com.ageinmotion.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HSV color wheel: hue = angle, saturation = radius, selected color shown
 * in the middle. Brightness is driven by an outside slider via [brightness].
 * Shaders are built once per size — no per-pixel loops, always 60fps.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var hue = 0f
        private set
    var saturation = 1f
        private set
    var brightness = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onColorChanged: ((Int) -> Unit)? = null

    val selectedColor: Int
        get() = Color.HSVToColor(floatArrayOf(hue, saturation, brightness))

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var hueShader: Shader? = null
    private var satShader: Shader? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.WHITE
    }

    init {
        // Marker ring uses a shadow for lift; needs software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        markerRing.setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 8f
        hueShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED,
            ),
            null,
        )
        satShader = RadialGradient(
            cx, cy, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wheelPaint.shader = hueShader
        canvas.drawCircle(cx, cy, radius, wheelPaint)
        overlayPaint.shader = satShader
        canvas.drawCircle(cx, cy, radius, overlayPaint)

        val cr = radius * 0.30f
        centerPaint.color = selectedColor
        canvas.drawCircle(cx, cy, cr, centerPaint)
        canvas.drawCircle(cx, cy, cr, centerRing)

        // Marker sits exactly on the picked hue/saturation (sweep starts
        // at east/red, matching atan2's angle convention).
        val rad = Math.toRadians(hue.toDouble())
        val mr = saturation * radius
        val mx = cx + (mr * cos(rad)).toFloat()
        val my = cy + (mr * sin(rad)).toFloat()
        markerFill.color = selectedColor
        canvas.drawCircle(mx, my, 16f, markerFill)
        canvas.drawCircle(mx, my, 16f, markerRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - cx
                val dy = event.y - cy
                val r = sqrt(dx * dx + dy * dy)
                if (r <= radius + 48f) {
                    hue = (((Math.toDegrees(atan2(dy, dx).toDouble())) % 360 + 360) % 360).toFloat()
                    saturation = (r / radius).coerceIn(0f, 1f)
                    invalidate()
                    onColorChanged?.invoke(selectedColor)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }
}
