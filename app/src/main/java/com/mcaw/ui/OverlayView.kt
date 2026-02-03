package com.mcaw.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.mcaw.model.Box
import kotlin.math.max

/**
 * Jednoduchý overlay, který vykreslí jeden detekční box a související telemetrii.
 * - Vstupní data se nastavují přes veřejné properties: [box], [distance], [speed], [ttc]
 * - Kreslí rámeček a popisek v levém horním rohu boxu.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- VYKRESLOVACÍ NÁSTROJE ------------------------------------------------

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 229, 168)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }

    // ---- DATA K ZOBRAZENÍ ------------------------------------------------------

    /**
     * Jediný box, který se má vykreslit (nastavuje PreviewActivity).
     */
    var box: Box? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Odhad vzdálenosti v metrech (může být -1f, pokud není známá).
     */
    var distance: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Relativní rychlost přibližování (m/s). Pokud nepoužíváš, nech -1f.
     */
    var speed: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Odhad rychlosti objektu (m/s). Pokud nepoužíváš, nech -1f.
     */
    var objectSpeed: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Time-To-Collision v sekundách. Pokud nepoužíváš, nech -1f.
     */
    var ttc: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Když je true, zobrazí se telemetrie vedle boxu.
     */
    var showTelemetry: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // ---- KRESLENÍ --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawDetectionZone(canvas)

        val b = box ?: return

        // Vykreslení obdélníku
        canvas.drawRect(b.x1, b.y1, b.x2, b.y2, boxPaint)

        // Sestavení popisků
        val lines = if (showTelemetry) {
            buildList {
                add("BOX  [%.0f×%.0f]".format((b.x2 - b.x1), (b.y2 - b.y1)))
                if (distance >= 0f && distance.isFinite()) add("DIST %.2f m".format(distance))
                if (speed >= 0f && speed.isFinite()) add("REL  %.2f m/s".format(speed))
                if (objectSpeed >= 0f && objectSpeed.isFinite()) {
                    add("OBJ  %.2f m/s".format(objectSpeed))
                }
                if (ttc >= 0f && ttc.isFinite()) add("TTC  %.2f s".format(ttc))
            }
        } else {
            emptyList()
        }

        if (lines.isEmpty()) return

        // Měření textu – šířka nejdelšího řádku, výška řádku
        var textW = 0f
        val fm = textPaint.fontMetrics
        val lineH = (fm.bottom - fm.top)
        for (ln in lines) textW = max(textW, textPaint.measureText(ln))

        val padding = 10f
        val bgLeft = b.x1
        val bgBottom = b.y1
        val bgTop = bgBottom - (lineH * lines.size) - (2 * padding)
        val bgRight = bgLeft + textW + (2 * padding)

        // Ořez proti hranám plátna (pokud by byl box u kraje)
        val topClamped = max(0f, bgTop)
        val rect = RectF(bgLeft, topClamped, bgRight, bgBottom)
        canvas.drawRoundRect(rect, 10f, 10f, textBgPaint)

        // Vykreslení textu po řádcích
        var y = (bgBottom - padding) - (lines.size - 1) * lineH
        for (ln in lines) {
            canvas.drawText(ln, bgLeft + padding, y, textPaint)
            y += lineH
        }
    }

    private fun drawDetectionZone(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val left = w * 0.3f
        val right = w * 0.7f
        val top = h * 0.15f
        val bottom = h * 0.9f
        canvas.drawRoundRect(left, top, right, bottom, 16f, 16f, zonePaint)
    }
}
