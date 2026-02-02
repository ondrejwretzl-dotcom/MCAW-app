package com.mcaw.model

data class Detection(
    val box: Box,
    val score: Float,
    val label: String? = null,
    val trackId: Long? = null,
    // runtime doplòovaná telemetrie
    var distanceM: Float? = null,
    var relSpeedMps: Float? = null,
    var ttcSec: Float? = null
)
