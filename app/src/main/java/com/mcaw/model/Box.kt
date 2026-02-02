package com.mcaw.model

data class Box(
    val x1: Float,  // left
    val y1: Float,  // top
    val x2: Float,  // right
    val y2: Float   // bottom
) {
    val w: Float get() = (x2 - x1).coerceAtLeast(0f)
    val h: Float get() = (y2 - y1).coerceAtLeast(0f)
    val cx: Float get() = x1 + w / 2f
    val cy: Float get() = y1 + h / 2f
    val area: Float get() = w * h
}
