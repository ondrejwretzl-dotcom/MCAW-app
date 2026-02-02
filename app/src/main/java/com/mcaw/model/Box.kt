package com.mcaw.ai

import com.mcaw.model.Box
import com.mcaw.model.Detection
import kotlin.math.max
import kotlin.math.min

object DetectionPhysics {

    /** IoU + NMS; jednoduchá implementace */
    fun nms(input: List<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        val work = input.sortedByDescending { it.score }.toMutableList()
        val out = mutableListOf<Detection>()
        while (work.isNotEmpty()) {
            val best = work.removeAt(0)
            out.add(best)
            work.removeAll { iou(best.box, it.box) > iouThreshold }
        }
        return out
    }

    private fun iou(a: Box, b: Box): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }
}
