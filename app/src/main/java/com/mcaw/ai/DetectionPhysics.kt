package com.mcaw.ai

object DetectionPhysics {

    // Focal length v pixelech (nastaví McawService po startu)
    var focalLengthPx: Float = 1000f

    // skuteèná šíøka auta pøed námi (m)
    private const val REAL_CAR_WIDTH = 1.8f

    /**
     * Fyzikálnì správná vzdálenost podle pinhole camera modelu:
     *
     *  distance = (real_width * focal_length_px) / pixel_width
     */
    fun estimateDistance(box: Box): Float {
        val pixelWidth = box.width()
        if (pixelWidth <= 1f) return Float.POSITIVE_INFINITY

        return (REAL_CAR_WIDTH * focalLengthPx) / pixelWidth
    }

    /**
     * Výpoèet TTC:
     *
     *  TTC = distance / approach_speed
     */
    fun computeTTC(distance: Float, speed: Float): Float {
        if (speed <= 0.05f) return Float.POSITIVE_INFINITY
        return distance / speed
    }

    /**
     * Adaptivní prahování rizika:
     *
     * Vstup: rychlost uživatele (auto)
     * Zvyšuje prahy TTC pøi vyšší rychlosti, snižuje pøi nižší.
     */
    fun adaptiveTtcThreshold(userSpeed: Float): Float {
        return when {
            userSpeed < 5f -> 1.8f
            userSpeed < 10f -> 2.5f
            userSpeed < 20f -> 3.0f
            else -> 3.5f
        }
    }
}
