package com.mcaw.model

/**
 * Jednotlivá detekce: box + popisek + skóre (0..1).
 */
data class Detection(
    val box: Box,
    val label: String,
    val score: Float,
    val id: Int = -1
)
