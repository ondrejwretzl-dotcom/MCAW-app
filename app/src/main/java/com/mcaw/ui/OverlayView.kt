package com.mcaw.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.mcaw.model.Box
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }
    private val brakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.FILL
    }



    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val roiDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 0, 0)
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

    var box: Box? = null
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

    var distance: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /** REL = approach speed (m/s), always >= 0. */
    var speed: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /** OBJ speed estimate (m/s). */
    var objectSpeed: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /** RID speed (m/s). */
    var riderSpeed: Float = -1f
        set(value) {
            field = value
            invalidate()
        }
    /** Brake cue active (rozsvícená brzdová světla – heuristika). */
    var brakeCueActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var brakeCueStrength: Float = 0f
        set(value) {
            field = value
            invalidate()
        }



    var ttc: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var showTelemetry: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // ROI normalized [0..1]
    var roiLeftN: Float = 0.15f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var roiTopN: Float = 0.15f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var roiRightN: Float = 0.85f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var roiBottomN: Float = 0.85f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var roiEditMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Callback při změně ROI v edit módu.
     * isFinal=true na ACTION_UP/CANCEL -> vhodné pro uložení do prefs.
     */
    var onRoiChanged: ((l: Float, t: Float, r: Float, b: Float, isFinal: Boolean) -> Unit)? = null

    private enum class DragHandle { NONE, LEFT, TOP, RIGHT, BOTTOM, MOVE }

    private var activeHandle: DragHandle = DragHandle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val touchSlopPx: Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics)

    private val minRoiSizeN = 0.10f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val roiRect = mapRoiToView() ?: return
        canvas.drawRect(roiRect, roiDimPaint)
        canvas.drawRect(roiRect, roiPaint)

        val b = box
        if (b != null) {
            val mappedBox = mapToView(b) ?: return
            canvas.drawRect(mappedBox, boxPaint)
            drawCornerDots(canvas, mappedBox)
            if (label.isNotBlank()) drawLabelTag(canvas, mappedBox, label)

            if (showTelemetry) drawTelemetry(canvas, mappedBox, b)
            if (brakeCueActive) drawBrakeCue(canvas, mappedBox)
        } else {
            if (showTelemetry) drawStatus(canvas, "DEBUG OVERLAY: čekám na detekci")
        }

        if (roiEditMode) {
            drawEditHint(canvas)
        }
    }

    private fun drawTelemetry(canvas: Canvas, mapped: RectF, b: Box) {
        val lines = buildList {
            if (label.isNotBlank()) add("OBJ  $label")
            add("BOX  [%.0f×%.0f]".format((b.x2 - b.x1), (b.y2 - b.y1)))
            if (distance.isFinite() && distance >= 0f) add("DIST %.2f m".format(distance))
            if (speed.isFinite() && speed >= 0f) add("REL  %.1f km/h".format(speed * 3.6f))
            if (objectSpeed.isFinite() && objectSpeed >= 0f) add("OBJ  %.1f km/h".format(objectSpeed * 3.6f))
            if (riderSpeed.isFinite() && riderSpeed >= 0f) add("RID  %.1f km/h".format(riderSpeed * 3.6f))
            if (ttc.isFinite() && ttc >= 0f) add("TTC  %.2f s".format(ttc))
            if (brakeCueActive) add("BRAKE ON")
        }
        if (lines.isEmpty()) return

        var textW = 0f
        val fm = textPaint.fontMetrics
        val lineH = (fm.bottom - fm.top)
        for (ln in lines) textW = max(textW, textPaint.measureText(ln))

        val padding = 10f
        val bgLeft = mapped.left
        val bgBottom = mapped.top
        val bgTop = bgBottom - (lineH * lines.size) - (2 * padding)
        val bgRight = bgLeft + textW + (2 * padding)

        val topClamped = max(0f, bgTop)
        val rect = RectF(bgLeft, topClamped, bgRight, bgBottom)
        canvas.drawRoundRect(rect, 10f, 10f, textBgPaint)

        var y = (bgBottom - padding) - (lines.size - 1) * lineH
        for (ln in lines) {
            canvas.drawText(ln, bgLeft + padding, y, textPaint)
            y += lineH
        }
    }

    private fun drawEditHint(canvas: Canvas) {
        val msg = "EDIT ROI: táhni hrany / uvnitř přesun"
        val padding = 12f
        val fm = textPaint.fontMetrics
        val lineH = (fm.bottom - fm.top)
        val textW = textPaint.measureText(msg)
        val left = padding
        val top = padding
        val right = left + textW + padding * 2
        val bottom = top + lineH + padding * 2
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, 10f, 10f, textBgPaint)
        canvas.drawText(msg, left + padding, bottom - padding, textPaint)
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

    
    private fun drawBrakeCue(canvas: Canvas, mapped: RectF) {
        // malé červené "brzdové světlo" u boxu (debug)
        val radius = 10f
        val cx = mapped.right - radius - 6f
        val cy = mapped.top + radius + 6f
        canvas.drawCircle(cx, cy, radius, brakePaint)
        val txt = "B"
        val oldSize = textPaint.textSize
        textPaint.textSize = 22f
        val tw = textPaint.measureText(txt)
        canvas.drawText(txt, cx - tw / 2f, cy + 8f, textPaint)
        textPaint.textSize = oldSize
    }

override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!roiEditMode) return false

        val roiRect = mapRoiToView() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = pickHandle(event.x, event.y, roiRect)
                lastTouchX = event.x
                lastTouchY = event.y
                return activeHandle != DragHandle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == DragHandle.NONE) return false
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                applyDrag(dx, dy, roiRect)
                onRoiChanged?.invoke(roiLeftN, roiTopN, roiRightN, roiBottomN, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle != DragHandle.NONE) {
                    onRoiChanged?.invoke(roiLeftN, roiTopN, roiRightN, roiBottomN, true)
                }
                activeHandle = DragHandle.NONE
                return true
            }
        }
        return false
    }

    private fun pickHandle(x: Float, y: Float, roi: RectF): DragHandle {
        val nearLeft = abs(x - roi.left) <= touchSlopPx && y >= roi.top - touchSlopPx && y <= roi.bottom + touchSlopPx
        val nearRight = abs(x - roi.right) <= touchSlopPx && y >= roi.top - touchSlopPx && y <= roi.bottom + touchSlopPx
        val nearTop = abs(y - roi.top) <= touchSlopPx && x >= roi.left - touchSlopPx && x <= roi.right + touchSlopPx
        val nearBottom = abs(y - roi.bottom) <= touchSlopPx && x >= roi.left - touchSlopPx && x <= roi.right + touchSlopPx

        return when {
            nearLeft -> DragHandle.LEFT
            nearRight -> DragHandle.RIGHT
            nearTop -> DragHandle.TOP
            nearBottom -> DragHandle.BOTTOM
            roi.contains(x, y) -> DragHandle.MOVE
            else -> DragHandle.NONE
        }
    }

    private fun applyDrag(dxView: Float, dyView: Float, roiView: RectF) {
        val inv = viewToFrameDelta(dxView, dyView) ?: return
        val dxN = inv.first
        val dyN = inv.second

        var l = roiLeftN
        var t = roiTopN
        var r = roiRightN
        var b = roiBottomN

        when (activeHandle) {
            DragHandle.LEFT -> l = (l + dxN).coerceIn(0f, r - minRoiSizeN)
            DragHandle.RIGHT -> r = (r + dxN).coerceIn(l + minRoiSizeN, 1f)
            DragHandle.TOP -> t = (t + dyN).coerceIn(0f, b - minRoiSizeN)
            DragHandle.BOTTOM -> b = (b + dyN).coerceIn(t + minRoiSizeN, 1f)
            DragHandle.MOVE -> {
                val w = r - l
                val h = b - t
                val nl = (l + dxN).coerceIn(0f, 1f - w)
                val nt = (t + dyN).coerceIn(0f, 1f - h)
                l = nl
                t = nt
                r = l + w
                b = t + h
            }
            else -> return
        }

        roiLeftN = l
        roiTopN = t
        roiRightN = r
        roiBottomN = b
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

    private fun mapRoiToView(): RectF? {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return null

        val fw = if (frameWidth > 0f) frameWidth else viewW
        val fh = if (frameHeight > 0f) frameHeight else viewH

        val scale = min(viewW / fw, viewH / fh)
        val scaledW = fw * scale
        val scaledH = fh * scale
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f

        val l = roiLeftN.coerceIn(0f, 1f)
        val t = roiTopN.coerceIn(0f, 1f)
        val r = roiRightN.coerceIn(0f, 1f)
        val b = roiBottomN.coerceIn(0f, 1f)

        val leftPx = l * fw
        val topPx = t * fh
        val rightPx = r * fw
        val bottomPx = b * fh

        return RectF(
            leftPx * scale + dx,
            topPx * scale + dy,
            rightPx * scale + dx,
            bottomPx * scale + dy
        )
    }

    /** Convert view delta (dx,dy) to normalized frame delta (0..1) for current mapping. */
    private fun viewToFrameDelta(dxView: Float, dyView: Float): Pair<Float, Float>? {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return null

        val fw = if (frameWidth > 0f) frameWidth else viewW
        val fh = if (frameHeight > 0f) frameHeight else viewH

        val scale = min(viewW / fw, viewH / fh)
        if (scale <= 0f) return null

        val dxFrame = dxView / scale
        val dyFrame = dyView / scale
        return Pair(dxFrame / fw, dyFrame / fh)
    }
}
