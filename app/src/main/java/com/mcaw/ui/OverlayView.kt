package com.mcaw.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.mcaw.ai.Box

class OverlayView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var box: Box? = null
    var ttc: Float? = null
    var distance: Float? = null
    var speed: Float? = null

    private val boxPaint = Paint().apply {
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 44f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val b = box ?: return
        val ttcVal = ttc ?: return
        val distVal = distance ?: return
        val speedVal = speed ?: return

        // PRAHY MUSÍ LADIT S DetectionAnalyzer
        val ttcThreshold = 2.5f

        val isHighRisk =
            (ttcVal < ttcThreshold) ||
            (distVal < 1.0f) ||
            (speedVal > 1.2f)

        val isMediumRisk =
            !isHighRisk && (
            (ttcVal < 4.0f) ||
            (distVal < 2.0f) ||
            (speedVal > 0.8f)
        )

        val boxColor = when {
            isHighRisk -> Color.RED
            isMediumRisk -> Color.YELLOW
            else -> Color.GREEN
        }

        boxPaint.color = boxColor
        textPaint.color = boxColor

        canvas.drawRect(b.left, b.top, b.right, b.bottom, boxPaint)

        canvas.drawText("TTC: %.1f s".format(ttcVal), 40f, 80f, textPaint)
        canvas.drawText("Dist: %.1f m".format(distVal), 40f, 140f, textPaint)
        canvas.drawText("Speed: %.1f".format(speedVal), 40f, 200f, textPaint)
    }
}
