package com.mcaw.util

object LabelMapper {
    fun mapLabel(label: String?): String {
        val normalized = label?.lowercase()?.trim()
        if (normalized.isNullOrBlank()) {
            return ""
        }
        return when (normalized) {
            "car", "auto", "vehicle" -> "auto"
            "motorcycle", "motorbike", "bike", "motorka" -> "motorka"
            "truck", "lorry", "nákladák", "nakladak" -> "nákladák"
            "van", "dodavka", "dodávka" -> "dodávka"
            "bus" -> "autobus"
            "bicycle" -> "kolo"
            "person", "pedestrian", "chodec" -> "chodec"
            "unknown" -> "neznámý objekt"
            else -> label
        }
    }
}
