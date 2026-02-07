package com.mcaw.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.mcaw.model.Box
import kotlin.math.max
import kotlin.math.min

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


    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }


    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
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
     * ROI (Region of Interest) v pixelech v souřadnicích frame (otočený bitmap).
     * Slouží jen pro vizualizaci oblasti, ve které aktuálně běží detekce.
     */
    var roiBox: Box? = null
        set(value) {
            field = value
            invalidate()
        }

    var frameWidth: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var frameHeight: Float = 0f
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
     * Label detekovaného objektu (např. auto, kolo, chodec).
     */
    var label: String = ""
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

        // ROI vykreslujeme i v okamžiku, kdy ještě nemáme detekci
        roiBox?.let { r ->
            val mappedRoi = mapToView(r)
            if (mappedRoi != null) {
                canvas.drawRect(mappedRoi, roiPaint)
            }
        }

        val b = box
        if (b == null) {
            if (showTelemetry) {
                drawStatus(canvas, "DEBUG OVERLAY: čekám na detekci")
            }
            return
        }

        val mapped = mapToView(b) ?: return

        // Vykreslení obdélníku
        canvas.drawRect(mapped, boxPaint)
        drawCornerDots(canvas, mapped)
        if (label.isNotBlank()) {
            drawLabelTag(canvas, mapped, label)
        }

        // Sestavení popisků
        if (!showTelemetry) return
        val lines = if (showTelemetry) {
            buildList {
                if (label.isNotBlank()) add("OBJ  $label")
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
        val bgLeft = mapped.left
        val bgBottom = mapped.top
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

    private fun drawLabelTag(canvas: Canvas, rect: RectF, labelText: String) {
        val padding = 8f
        val textW = textPaint.measureText(labelText)
        val fm = textPaint.fontMetrics
        val textH = (fm.bottom - fm.top)
        val bgLeft = rect.left
        val bgBottom = rect.top
        val bgTop = bgBottom - textH - padding * 2
        val bgRight = bgLeft + textW + padding * 2
        val topClamped = max(0f, bgTop)
        val tagRect = RectF(bgLeft, topClamped, bgRight, bgBottom)
        canvas.drawRoundRect(tagRect, 10f, 10f, textBgPaint)
        val textY = bgBottom - padding
        canvas.drawText(labelText, bgLeft + padding, textY, textPaint)
    }

    private fun drawCornerDots(canvas: Canvas, rect: RectF) {
        val radius = 6f
        canvas.drawCircle(rect.left, rect.top, radius, dotPaint)
        canvas.drawCircle(rect.right, rect.top, radius, dotPaint)
        canvas.drawCircle(rect.left, rect.bottom, radius, dotPaint)
        canvas.drawCircle(rect.right, rect.bottom, radius, dotPaint)
    }

    private fun drawStatus(canvas: Canvas, message: String) {
        val padding = 12f
        val fm = textPaint.fontMetrics
        val lineH = (fm.bottom - fm.top)
        val textW = textPaint.measureText(message)
        val left = padding
        val top = padding
        val right = left + textW + padding * 2
        val bottom = top + lineH + padding * 2
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, 10f, 10f, textBgPaint)
        canvas.drawText(message, left + padding, bottom - padding, textPaint)
    }

    private fun mapToView(box: Box): RectF? {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return null
        if (frameWidth <= 0f || frameHeight <= 0f) {
            return RectF(box.x1, box.y1, box.x2, box.y2)
        }

        val scale = min(viewW / frameWidth, viewH / frameHeight)
        val scaledW = frameWidth * scale
        val scaledH = frameHeight * scale
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f

        return RectF(
            box.x1 * scale + dx,
            box.y1 * scale + dy,
            box.x2 * scale + dx,
            box.y2 * scale + dy
        )
    }
}
