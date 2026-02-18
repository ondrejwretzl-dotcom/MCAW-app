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
    // Calibration metrics (audit/debug)
    val calibrationRmsM: Float = 0f,
    val calibrationMaxErrM: Float = 0f,
    val calibrationImuStdDeg: Float = 0f,
    val calibrationSavedUptimeMs: Long = 0L,
    val calibrationQuality: Int = 0,
    val calibrationGeomQuality: Int = 0,
    val calibrationImuQuality: Int = 0,
    val calibrationImuExtraErrAt10m: Float = 0f,
    val calibrationCombinedErrAt10m: Float = 0f,
    val laneEgoMaxOffset: Float,
    // ROI trapezoid
    val roiTopY: Float,
    val roiBottomY: Float,
    val roiTopHalfW: Float,
    val roiBottomHalfW: Float,
    val roiCenterX: Float,
)
