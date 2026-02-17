package com.mcaw.config

/**
 * Mount profile = configuration snapshot tied to a specific vehicle + phone placement.
 *
 * Scope is intentionally limited to "mount/camera" parameters and ROI tuning.
 * It must NOT contain RiskEngine thresholds or alert behavior.
 */
data class MountProfile(
    val id: String,
    val name: String,
    // Mount / calibration
    val cameraHeightM: Float,
    val cameraPitchDownDeg: Float,
    val distanceScale: Float,
    val laneEgoMaxOffset: Float,
    // ROI trapezoid
    val roiTopY: Float,
    val roiBottomY: Float,
    val roiTopHalfW: Float,
    val roiBottomHalfW: Float,
    val roiCenterX: Float,
)
