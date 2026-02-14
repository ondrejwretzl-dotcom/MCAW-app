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
import kotlin.math.sqrt

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

    private val roiFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    /** 0=SAFE, 1=ORANGE, 2=RED (z DetectionAnalyzer). */
    var alertLevel: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            invalidate()
        }

var alertReason: String = ""
    var riskScore: Float = Float.NaN
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

    // ---- ROI trapezoid (normalized 0..1) ----
    // Symetrický kolem centerX=0.5
    var roiCenterX: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var roiTopY: Float = 0.32f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var roiBottomY: Float = 0.92f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var roiTopHalfW: Float = 0.18f
        set(value) {
            field = value.coerceIn(0f, 0.5f)
            invalidate()
        }

    var roiBottomHalfW: Float = 0.46f
        set(value) {
            field = value.coerceIn(0f, 0.5f)
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
    var onRoiChanged: ((topY: Float, bottomY: Float, topHalfW: Float, bottomHalfW: Float, centerX: Float, isFinal: Boolean) -> Unit)? =
        null

    private enum class DragHandle {
        NONE,
        MOVE,
        TOP_Y,
        BOTTOM_Y,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private var activeHandle: DragHandle = DragHandle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val touchSlopPx: Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics)

    private val minHeightN = 0.10f
    private val minTopHalfWN = 0.06f
    private val minBottomHalfWN = 0.12f


    private fun clampCenterX(centerX: Float, topHalfW: Float, bottomHalfW: Float): Float {
        val maxHalfW = max(topHalfW, bottomHalfW).coerceIn(0f, 0.5f)
        val minCx = maxHalfW
        val maxCx = 1f - maxHalfW
        // If widths are extreme and min > max, fall back to 0.5 (shouldn't happen due to width clamps).
        return if (minCx <= maxCx) centerX.coerceIn(minCx, maxCx) else 0.5f
    }

    private fun colorForAlert(level: Int): Int {
        return when (level.coerceIn(0, 2)) {
            2 -> Color.RED
            1 -> Color.rgb(255, 165, 0) // orange
            else -> Color.GREEN
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val c = colorForAlert(alertLevel)
        boxPaint.color = c
        dotPaint.color = c

        // ROI: v edit módu zvýrazni (žlutá + silnější), mimo edit se drž alert barvy.
        if (roiEditMode) {
            roiPaint.color = Color.YELLOW
            roiPaint.strokeWidth = 6f
            roiFillPaint.color = Color.argb(70, 255, 255, 0)
        } else {
            roiPaint.color = c
            roiPaint.strokeWidth = 4f
            roiFillPaint.color = Color.argb(60, 255, 0, 0)
        }

        val roiPath = mapRoiToViewPath() ?: return
        canvas.drawPath(roiPath, roiFillPaint)
        canvas.drawPath(roiPath, roiPaint)

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
            drawRoiHandles(canvas)
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
            if (riskScore.isFinite()) add("RISK %.2f".format(riskScore))
            if (alertLevel > 0 && alertReason.isNotBlank()) add("WHY  " + alertReason)
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
        val msg = "EDIT ROI: táhni hrany / rohy / uvnitř přesun"
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
        val radius = 14f
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

    private fun drawRoiHandles(canvas: Canvas) {
        val pts = roiPointsView() ?: return
        val radius = 14f
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        // corners
        canvas.drawCircle(pts[0], pts[1], radius, handlePaint) // TL
        canvas.drawCircle(pts[2], pts[3], radius, handlePaint) // TR
        canvas.drawCircle(pts[4], pts[5], radius, handlePaint) // BR
        canvas.drawCircle(pts[6], pts[7], radius, handlePaint) // BL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!roiEditMode) return false

        val pts = roiPointsView() ?: return false
        val path = roiPathFromPts(pts)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = pickHandle(event.x, event.y, pts, path)
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
                applyDrag(dx, dy)
                onRoiChanged?.invoke(roiTopY, roiBottomY, roiTopHalfW, roiBottomHalfW, roiCenterX, false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle != DragHandle.NONE) {
                    onRoiChanged?.invoke(roiTopY, roiBottomY, roiTopHalfW, roiBottomHalfW, roiCenterX, true)
                }
                activeHandle = DragHandle.NONE
                return true
            }
        }
        return false
    }

    private fun pickHandle(x: Float, y: Float, pts: FloatArray, roiPath: Path): DragHandle {
        fun dist(ax: Float, ay: Float): Float = sqrt((x - ax) * (x - ax) + (y - ay) * (y - ay))

        val tlx = pts[0]; val tly = pts[1]
        val trx = pts[2]; val tryy = pts[3]
        val brx = pts[4]; val bry = pts[5]
        val blx = pts[6]; val bly = pts[7]

        val cornerHit = touchSlopPx * 1.2f
        if (dist(tlx, tly) <= cornerHit) return DragHandle.TOP_LEFT
        if (dist(trx, tryy) <= cornerHit) return DragHandle.TOP_RIGHT
        if (dist(brx, bry) <= cornerHit) return DragHandle.BOTTOM_RIGHT
        if (dist(blx, bly) <= cornerHit) return DragHandle.BOTTOM_LEFT

        // near top/bottom edge (y proximity)
        val topEdgeY = (tly + tryy) * 0.5f
        val bottomEdgeY = (bly + bry) * 0.5f
        if (abs(y - topEdgeY) <= touchSlopPx && x >= min(tlx, trx) - touchSlopPx && x <= max(tlx, trx) + touchSlopPx) {
            return DragHandle.TOP_Y
        }
        if (abs(y - bottomEdgeY) <= touchSlopPx && x >= min(blx, brx) - touchSlopPx && x <= max(blx, brx) + touchSlopPx) {
            return DragHandle.BOTTOM_Y
        }

        // inside => move
        if (pointInPath(roiPath, x, y)) return DragHandle.MOVE

        return DragHandle.NONE
    }

    private fun applyDrag(dxView: Float, dyView: Float) {
        val inv = viewToFrameDelta(dxView, dyView) ?: return
        val dxN = inv.first
        val dyN = inv.second

        var topY = roiTopY
        var bottomY = roiBottomY
        var topHalfW = roiTopHalfW
        var bottomHalfW = roiBottomHalfW
        var centerX = roiCenterX

        when (activeHandle) {
            DragHandle.MOVE -> {
                val h = bottomY - topY
                val ty = (topY + dyN).coerceIn(0f, 1f - h)
                val by = ty + h
                topY = ty
                bottomY = by
                centerX = centerX + dxN
            }

            DragHandle.TOP_Y -> {
                topY = (topY + dyN).coerceIn(0f, bottomY - minHeightN)
            }

            DragHandle.BOTTOM_Y -> {
                bottomY = (bottomY + dyN).coerceIn(topY + minHeightN, 1f)
            }

            DragHandle.TOP_LEFT -> {
                // moving left corner right => shrinks, left => grows
                topHalfW = (topHalfW - dxN).coerceIn(minTopHalfWN, 0.5f)
            }

            DragHandle.TOP_RIGHT -> {
                topHalfW = (topHalfW + dxN).coerceIn(minTopHalfWN, 0.5f)
            }

            DragHandle.BOTTOM_LEFT -> {
                bottomHalfW = (bottomHalfW - dxN).coerceIn(minBottomHalfWN, 0.5f)
            }

            DragHandle.BOTTOM_RIGHT -> {
                bottomHalfW = (bottomHalfW + dxN).coerceIn(minBottomHalfWN, 0.5f)
            }

            else -> return
        }

        // enforce trapezoid constraint (bottom >= top)
        if (bottomHalfW < topHalfW) bottomHalfW = topHalfW

        centerX = clampCenterX(centerX, topHalfW, bottomHalfW)

        roiTopY = topY
        roiBottomY = bottomY
        roiTopHalfW = topHalfW
        roiBottomHalfW = bottomHalfW
        roiCenterX = centerX
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

    private fun roiPointsView(): FloatArray? {
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

        val cx = roiCenterX
        val topY = roiTopY.coerceIn(0f, 1f)
        val bottomY = roiBottomY.coerceIn(0f, 1f)
        val topHalfW = roiTopHalfW.coerceIn(0f, 0.5f)
        val bottomHalfW = roiBottomHalfW.coerceIn(0f, 0.5f)

        val tlx = (cx - topHalfW) * fw
        val trx = (cx + topHalfW) * fw
        val brx = (cx + bottomHalfW) * fw
        val blx = (cx - bottomHalfW) * fw

        val tpy = topY * fh
        val bpy = bottomY * fh

        return floatArrayOf(
            tlx * scale + dx, tpy * scale + dy,
            trx * scale + dx, tpy * scale + dy,
            brx * scale + dx, bpy * scale + dy,
            blx * scale + dx, bpy * scale + dy
        )
    }

    private fun roiPathFromPts(pts: FloatArray): Path {
        return Path().apply {
            moveTo(pts[0], pts[1])
            lineTo(pts[2], pts[3])
            lineTo(pts[4], pts[5])
            lineTo(pts[6], pts[7])
            close()
        }
    }

    private fun mapRoiToViewPath(): Path? {
        val pts = roiPointsView() ?: return null
        return roiPathFromPts(pts)
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

    private fun pointInPath(path: Path, x: Float, y: Float): Boolean {
        val r = RectF()
        path.computeBounds(r, true)
        val region = Region()
        region.setPath(
            path,
            Region(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        )
        return region.contains(x.toInt(), y.toInt())
    }
}
