package com.mcaw.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.mcaw.model.Box
import kotlin.math.max

/**
 * Jednoduchý overlay, který vykreslí jeden detekèní box a související telemetrii.
 * - Vstupní data se nastavují pøes veøejné properties: [box], [distance], [speed], [ttc]
 * - Kreslí rámeèek a popisek v levém horním rohu boxu.
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
     * Odhad vzdálenosti v metrech (mùže být -1f, pokud není známá).
     */
    var distance: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Relativní rychlost pøibližování (m/s). Pokud nepoužíváš, nech -1f.
     */
    var speed: Float = -1f
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

    // ---- KRESLENÍ --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val b = box ?: return

        // Vykreslení obdélníku
        canvas.drawRect(b.x1, b.y1, b.x2, b.y2, boxPaint)

        // Sestavení popiskù
        val lines = buildList {
            add("BOX  [%.0f×%.0f]".format((b.x2 - b.x1), (b.y2 - b.y1)))
            if (distance >= 0f && distance.isFinite()) add("DIST %.2f m".format(distance))
            if (speed >= 0f && speed.isFinite()) add("SPEED %.2f m/s".format(speed))
            if (ttc >= 0f && ttc.isFinite()) add("TTC  %.2f s".format(ttc))
        }

        if (lines.isEmpty()) return

        // Mìøení textu – šíøka nejdelšího øádku, výška øádku
        var textW = 0f
        val fm = textPaint.fontMetrics
        val lineH = (fm.bottom - fm.top)
        for (ln in lines) textW = max(textW, textPaint.measureText(ln))

        val padding = 10f
        val bgLeft = b.x1
        val bgBottom = b.y1
        val bgTop = bgBottom - (lineH * lines.size) - (2 * padding)
        val bgRight = bgLeft + textW + (2 * padding)

        // Oøez proti hranám plátna (pokud by byl box u kraje)
        val topClamped = max(0f, bgTop)
        val rect = RectF(bgLeft, topClamped, bgRight, bgBottom)
        canvas.drawRoundRect(rect, 10f, 10f, textBgPaint)

        // Vykreslení textu po øádcích
        var y = (bgBottom - padding) - (lines.size - 1) * lineH
        for (ln in lines) {
            canvas.drawText(ln, bgLeft + padding, y, textPaint)
            y += lineH
        }
    }
}
