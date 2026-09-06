package com.morselink.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Dashboard radar (cosmetic). Draws concentric range rings, a sweeping beam and
 * a dot per discovered peer. On low-end devices callers keep it in the static
 * mode (no sweep animation) per §7.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Blip(val angleDegrees: Float, val distanceFraction: Float)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.argb(60, 255, 255, 255)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bounds = RectF()
    private var animator: ValueAnimator? = null
    private var sweepAngle = 0f
    private var blips: List<Blip> = emptyList()
    private var accentColor = Color.parseColor(ACCENT_FALLBACK)
    private var animated = true

    init {
        val typed = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
        accentColor = typed.getColor(0, Color.parseColor(ACCENT_FALLBACK))
        typed.recycle()
    }

    fun setAnimated(enabled: Boolean) {
        if (animated == enabled) return
        animated = enabled
        if (enabled) start() else stop()
    }

    fun setBlips(value: List<Blip>) {
        blips = value
        invalidate()
    }

    fun start() {
        if (!animated || animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        sweepAngle = 0f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = min(w, h) / 2f - 8f
        bounds.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = bounds.width() / 2f

        for (i in 1..RINGS) {
            val r = radius * i / RINGS
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, ringPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, ringPaint)

        if (animated) {
            val shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(90, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                    accentColor
                ),
                floatArrayOf(0f, 0.75f, 1f)
            )
            sweepPaint.shader = shader
            canvas.save()
            canvas.rotate(sweepAngle, cx, cy)
            canvas.drawArc(bounds, 0f, TRAIL_DEGREES, true, sweepPaint)
            canvas.restore()
        }

        blipPaint.color = accentColor
        for (blip in blips) {
            val angle = Math.toRadians(blip.angleDegrees.toDouble())
            val distance = radius * blip.distanceFraction.coerceIn(0.05f, 0.95f)
            val x = cx + cos(angle).toFloat() * distance
            val y = cy + sin(angle).toFloat() * distance
            canvas.drawCircle(x, y, 5f * resources.displayMetrics.density, blipPaint)
        }

        corePaint.color = accentColor
        val coreRadius = 6f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, coreRadius, corePaint)
    }

    companion object {
        private const val RINGS = 3
        private const val TRAIL_DEGREES = 70f
        private const val ACCENT_FALLBACK = "#1FA36B"

        fun blipFor(index: Int): Blip {
            val angle = ((index * 67) % 360).toFloat()
            val distance = 0.35f + ((index * 37) % 55) / 100f
            return Blip(angle, distance.coerceAtMost(0.92f))
        }
    }
}
