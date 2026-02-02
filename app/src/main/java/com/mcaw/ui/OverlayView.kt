package com.mcaw.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.mcaw.model.Detection

/**
 * Jednoduchý overlay, který umí vykreslit seznam detekcí (box + label).
 * Nahrazuje pùvodní OverlayView – reference na left/top/right/bottom teï pochází z com.mcaw.model.Box.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    var detections: List<Detection> = emptyList()
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (d in detections) {
            val b = d.box
            // box
            canvas.drawRect(b.left, b.top, b.right, b.bottom, boxPaint)

            // štítek
            val label = "${d.label} ${"%.2f".format(d.score)}"
            val padding = 8f
            val textW = textPaint.measureText(label)
            val textH = textPaint.fontMetrics.run { bottom - top }

            val bgLeft = b.left
            val bgBottom = b.top
            val bgTop = bgBottom - textH - 2 * padding
            val bgRight = bgLeft + textW + 2 * padding

            val rect = RectF(bgLeft, bgTop, bgRight, bgBottom)
            canvas.drawRoundRect(rect, 8f, 8f, textBgPaint)
            canvas.drawText(label, bgLeft + padding, bgBottom - padding, textPaint)
        }
    }
}
