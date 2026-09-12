package com.morselink.feature.receive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The scanner mask for the QR pairing screen (§14.8).
 *
 * The important detail: the frame is a genuine **cut-out**, not a filled shape.
 * We dim the four rectangles surrounding it and never paint inside it, so the
 * live camera feed shows through unobstructed and a QR code can actually be
 * aligned. An earlier version filled the frame with an opaque card background,
 * which blacked out the only region the code could occupy.
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#99000000")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 90
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ACCENT
    }

    private val frame = RectF()

    /** The frame rect in view coordinates, so callers can align other UI to it. */
    val frameRect: RectF get() = frame

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = min(w, h) * FRAME_RATIO
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        val right = left + size
        val bottom = top + size
        frame.set(left, top, right, bottom)

        // Dim the four bands around the frame; the frame itself is untouched.
        canvas.drawRect(0f, 0f, w, top, scrimPaint)
        canvas.drawRect(0f, bottom, w, h, scrimPaint)
        canvas.drawRect(0f, top, left, bottom, scrimPaint)
        canvas.drawRect(right, top, w, bottom, scrimPaint)

        val density = resources.displayMetrics.density
        borderPaint.strokeWidth = 1f * density
        canvas.drawRect(frame, borderPaint)

        cornerPaint.strokeWidth = CORNER_WIDTH_DP * density
        val len = size * CORNER_LENGTH_RATIO

        // Top-left
        canvas.drawLine(left, top + len, left, top, cornerPaint)
        canvas.drawLine(left, top, left + len, top, cornerPaint)
        // Top-right
        canvas.drawLine(right - len, top, right, top, cornerPaint)
        canvas.drawLine(right, top, right, top + len, cornerPaint)
        // Bottom-left
        canvas.drawLine(left, bottom - len, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + len, bottom, cornerPaint)
        // Bottom-right
        canvas.drawLine(right - len, bottom, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - len, cornerPaint)
    }

    private companion object {
        const val ACCENT = 0xFF1FA36B.toInt()
        const val FRAME_RATIO = 0.62f
        const val CORNER_LENGTH_RATIO = 0.14f
        const val CORNER_WIDTH_DP = 4f
    }
}
