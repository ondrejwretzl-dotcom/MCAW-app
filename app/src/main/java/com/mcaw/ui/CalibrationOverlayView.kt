package com.mcaw.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Simple draggable crosshair used by CalibrationActivity.
 *
 * - No allocations in onDraw hot path.
 * - Coordinates stored normalized (0..1) so it is independent of view size.
 */
class CalibrationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPointChanged(xNorm: Float, yNorm: Float, fromUser: Boolean)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF3BA6FF.toInt() // MCAW blue
    }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x553BA6FF
    }

    var listener: Listener? = null

    var crosshairEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var xNorm: Float = 0.5f
        private set
    var yNorm: Float = 0.75f
        private set

    private var dragging = false

    fun setPointNormalized(x: Float, y: Float, fromUser: Boolean = false) {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        if (abs(nx - xNorm) < 0.0005f && abs(ny - yNorm) < 0.0005f) return
        xNorm = nx
        yNorm = ny
        listener?.onPointChanged(xNorm, yNorm, fromUser)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!crosshairEnabled) return

        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val cx = xNorm * w
        val cy = yNorm * h

        val r = 22f
        val arm = 34f

        // Fill dot
        canvas.drawCircle(cx, cy, 8f, paintFill)
        // Ring
        canvas.drawCircle(cx, cy, r, paint)
        // Cross
        canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!crosshairEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                setFromTouch(event, fromUser = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                setFromTouch(event, fromUser = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                setFromTouch(event, fromUser = true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun setFromTouch(event: MotionEvent, fromUser: Boolean) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        setPointNormalized(event.x / w, event.y / h, fromUser)
    }
}
