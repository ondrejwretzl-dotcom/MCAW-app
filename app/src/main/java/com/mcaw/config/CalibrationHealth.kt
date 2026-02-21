package com.mcaw.config

/**
 * User-facing health summary of calibration & mount geometry.
 *
 * This is intentionally simple (OK/WARNING/INVALID) and stable for UI gating.
 * Detailed metrics (RMS/IMU std) stay in AppPreferences for audit/debug only.
 */
object CalibrationHealth {

    enum class State { OK, WARNING, INVALID }

    data class Result(
        val state: State,
        val distanceReliable: Boolean,
        val speedReliable: Boolean,
        /** Stable reason code for logs / UI mapping. */
        val reasonCode: String,
        /** Short banner text for Main/Preview. */
        val bannerText: String,
        /** Longer explanation for wizard summary. */
        val detailText: String,
        /** Optional hint formatted like ±X% if known. Empty if unknown. */
        val distanceTypicalErrorHint: String
    )

    fun evaluate(): Result {
        // ROI validity is critical. If ROI is degenerate, we must not claim reliable distance/speeds.
        val roiValid = isRoiTrapezoidValid()

        val geomQ = AppPreferences.calibrationGeomQuality
        val imuQ = AppPreferences.calibrationImuQuality
        val q = AppPreferences.calibrationQuality

        // Unknown calibration is treated as INVALID for metric outputs (distance/speeds),
        // but we keep TTC/risk running (image-based) with conservative gating elsewhere.
        val baseState: State = when {
            !roiValid -> State.INVALID
            geomQ == 3 || imuQ == 3 -> State.INVALID
            geomQ == 2 || imuQ == 2 -> State.WARNING
            // If we have only overall quality without geom/imu split, keep it as a hint.
            q == 3 -> State.INVALID
            q == 2 -> State.WARNING
            // q==0 unknown -> invalid for metric outputs
            q == 0 -> State.INVALID
            else -> State.OK
        }

        val distanceReliable = baseState == State.OK
        val speedReliable = baseState == State.OK

        val (reason, banner, detail) = when {
            !roiValid -> Triple(
                "CALIB_ROI_INVALID",
                "Kalibrace: ROI není správně nastavená",
                "Oblast před tebou (trapezoid) je nastavená špatně. Oprav ROI podle směru jízdy – jinak nebudou spolehlivé vzdálenosti ani rychlosti objektu."
            )
            q == 0 -> Triple(
                "CALIB_NOT_DONE",
                "Kalibrace není dokončená",
                "Kalibrace ještě nebyla dokončená. Varování může fungovat, ale vzdálenost a rychlosti objektu jsou vypnuté, dokud kalibraci nedokončíš."
            )
            geomQ == 3 || q == 3 -> Triple(
                "CALIB_GEOM_BAD",
                "Kalibrace: nepřesná vzdálenost",
                "Geometrie vychází špatně. Aplikace proto schová vzdálenost a rychlosti objektu. Proveď kalibraci znovu na klidném místě."
            )
            imuQ == 3 -> Triple(
                "CALIB_MOUNT_UNSTABLE",
                "Kalibrace: držák je nestabilní",
                "Telefon se během kalibrace moc hýbal (vibrace / nestabilní držák). Vzdálenost a rychlosti objektu jsou vypnuté, dokud držák nezpevníš a kalibraci nezopakuješ."
            )
            geomQ == 2 || q == 2 -> Triple(
                "CALIB_GEOM_WARN",
                "Kalibrace: omezená přesnost",
                "Kalibrace je použitelná, ale s omezenou přesností. Vzdálenost může být méně přesná, hlavně při vibracích."
            )
            imuQ == 2 -> Triple(
                "CALIB_MOUNT_WARN",
                "Kalibrace: možné vibrace",
                "Držák/telefon nebyl úplně stabilní. Varování může fungovat, ale přesnost vzdálenosti se může zhoršit."
            )
            else -> Triple(
                "CALIB_OK",
                "",
                "Kalibrace vypadá dobře. Vzdálenost a rychlosti objektu by měly být spolehlivé v běžných podmínkách."
            )
        }

        val hint = buildTypicalDistanceHint()
        return Result(
            state = baseState,
            distanceReliable = distanceReliable,
            speedReliable = speedReliable,
            reasonCode = reason,
            bannerText = banner,
            detailText = detail,
            distanceTypicalErrorHint = hint
        )
    }

    private fun isRoiTrapezoidValid(): Boolean {
        val topY = AppPreferences.roiTrapTopY
        val bottomY = AppPreferences.roiTrapBottomY
        val topHW = AppPreferences.roiTrapTopHalfW
        val bottomHW = AppPreferences.roiTrapBottomHalfW
        val cx = AppPreferences.roiTrapCenterX

        if (!topY.isFinite() || !bottomY.isFinite() || !topHW.isFinite() || !bottomHW.isFinite() || !cx.isFinite()) return false
        if (topY < 0.0f || topY > 1.0f) return false
        if (bottomY < 0.0f || bottomY > 1.0f) return false
        if (topY >= bottomY) return false

        // half widths are normalized to [0..0.5]
        if (topHW <= 0.01f || bottomHW <= 0.02f) return false
        if (topHW > 0.5f || bottomHW > 0.5f) return false

        // centerX normalized to [0..1]
        if (cx < 0.0f || cx > 1.0f) return false

        // prevent fully off-screen trapezoid
        if (cx - bottomHW < 0.0f || cx + bottomHW > 1.0f) return false
        if (cx - topHW < 0.0f || cx + topHW > 1.0f) return false

        return true
    }

    private fun buildTypicalDistanceHint(): String {
        // Use RMS as a rough hint but keep it user-friendly.
        val rmsM = AppPreferences.calibrationRmsM
        val maxErrM = AppPreferences.calibrationMaxErrM
        if (!(rmsM > 0f) || !(maxErrM > 0f)) return ""
        // Very rough mapping: if RMS small, say ±10%, else ±20% etc.
        val percent = when {
            rmsM <= 0.4f -> 10
            rmsM <= 0.8f -> 15
            rmsM <= 1.5f -> 20
            else -> 30
        }
        return "Vzdálenost typicky do ±${percent}%"
    }
}
