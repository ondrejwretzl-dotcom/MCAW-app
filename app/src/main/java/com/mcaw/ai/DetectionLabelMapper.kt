package com.mcaw.ai

object DetectionLabelMapper {
    private val aliases = mapOf(
        "car" to "car",
        "auto" to "car",
        "vehicle" to "car",
        "van" to "van",
        "dodavka" to "van",
        "dodávka" to "van",
        "truck" to "truck",
        "lorry" to "truck",
        "nakladak" to "truck",
        "nákladák" to "truck",
        "motorcycle" to "motorcycle",
        "motorbike" to "motorcycle",
        "motorka" to "motorcycle",
        "bicycle" to "bicycle",
        "bike" to "bicycle",
        "kolo" to "bicycle",
        "person" to "person",
        "pedestrian" to "person",
        "chodec" to "person",
        "bus" to "bus"
    )

    fun toCanonical(label: String?): String? {
        val normalized = label?.trim()?.lowercase() ?: return null
        return aliases[normalized]
    }

    fun cocoLabel(classId: Int): String = when (classId) {
        0 -> "person"
        1 -> "bicycle"
        2 -> "car"
        3 -> "motorcycle"
        5 -> "bus"
        7 -> "truck"
        else -> "unknown"
    }
}
