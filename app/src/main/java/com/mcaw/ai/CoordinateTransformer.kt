package com.mcaw.ai

import com.mcaw.model.Box

data class FrameInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int
)

object CoordinateTransformer {
    fun toNormalized(box: Box, frameInfo: FrameInfo): Box {
        val w = frameInfo.width.toFloat().coerceAtLeast(1f)
        val h = frameInfo.height.toFloat().coerceAtLeast(1f)
        return Box(box.x1 / w, box.y1 / h, box.x2 / w, box.y2 / h)
    }

    fun toPixels(normBox: Box, frameInfo: FrameInfo): Box {
        val w = frameInfo.width.toFloat()
        val h = frameInfo.height.toFloat()
        return Box(normBox.x1 * w, normBox.y1 * h, normBox.x2 * w, normBox.y2 * h)
    }
}
