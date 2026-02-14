package com.mcaw.risk

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MCAW 2.0 – RiskEngine v1
 *
 * Cíl: jednotné místo pro prediktivní risk scoring a rozhodnutí levelu.
 * - TTC primárně z image trendu (posílá caller)
 * - ROI jako weight, ne hard gate
 * - IMU: ego brzdění + náklon (volitelné signály)
 * - Automat default (caller dodá effectiveMode z DetectionModePolicy)
 */
class RiskEngine {

    enum class State { SAFE, CAUTION, CRITICAL }

    class Result(
    var level: Int = 0,            // 0 SAFE, 1 ORANGE, 2 RED
    var riskScore: Float = 0f,      // 0..1
    var reasonBits: Int = 0,        // bitmask WHY (stable, parseable)
    var state: State = State.SAFE
)

class Thresholds(
    val ttcOrange: Float,
    val ttcRed: Float,
    val distOrange: Float,
    val distRed: Float,
    val relOrange: Float,
    val relRed: Float
)

// NOTE: Avoid per-frame allocations: re-use objects.
private val out = Result()

private val thrCity = Thresholds(ttcOrange = 3.0f, ttcRed = 1.2f, distOrange = 15f, distRed = 8f, relOrange = 3f, relRed = 5f)
private val thrSport = Thresholds(ttcOrange = 4.0f, ttcRed = 1.5f, distOrange = 30f, distRed = 12f, relOrange = 5f, relRed = 9f)
private val thrUser = Thresholds(ttcOrange = 3.0f, ttcRed = 1.2f, distOrange = 15f, distRed = 8f, relOrange = 3f, relRed = 5f) // updated each call

(
        val ttcOrange: Float,
        val ttcRed: Float,
        val distOrange: Float,
        val distRed: Float,
        val relOrange: Float,
        val relRed: Float
    )

    // Hysteresis on riskScore (prevents blinking)
    private var lastLevel: Int = 0

    // Separate hysteresis for TTC (kept inside engine; not fallback)
    private var lastTtcLevel: Int = 0

    fun evaluate(
        tsMs: Long,
        effectiveMode: Int,
        distanceM: Float,
        approachSpeedMps: Float,
        ttcSec: Float,
        roiContainment: Float,   // 0..1
        egoOffsetN: Float,       // 0..2 (0 center)
        cutInActive: Boolean,
        brakeCueActive: Boolean,
        brakeCueStrength: Float, // 0..1
        qualityPoor: Boolean,
        riderSpeedMps: Float,
        egoBrakingConfidence: Float, // 0..1
        leanDeg: Float              // deg, NaN if unknown
    ): Result {

        val thr = thresholdsForMode(effectiveMode)

        // --- Core scores (0..1) ---
        val ttcScore = scoreTtc(ttcSec, thr)
        val distScore = scoreLowIsBad(distanceM, thr.distRed, thr.distOrange)
        val relScore = scoreHighIsBad(approachSpeedMps, thr.relOrange, thr.relRed)

        // ROI weight: containment favors objects in ROI; egoOffset penalizes off-center targets.
        val roiC = roiContainment.coerceIn(0f, 1f)
        val off = egoOffsetN.coerceIn(0f, 2f)
        val egoScore = (1f - (off / 1.15f)).coerceIn(0f, 1f) // 1.15 ~ "slightly outside lane center"
        val roiScore = (roiC * 0.70f + egoScore * 0.30f).coerceIn(0f, 1f)

        val brakeScore = if (brakeCueActive) (0.70f + 0.30f * brakeCueStrength.coerceIn(0f, 1f)) else 0f
        val cutInScore = if (cutInActive) 1.0f else 0f

        // IMU braking: if rider is braking hard, slightly raise risk (predictive) near target.
        val egoBrake = egoBrakingConfidence.coerceIn(0f, 1f)

        // --- Weighted risk ---
        var risk =
            (ttcScore * 0.35f) +
            (distScore * 0.20f) +
            (relScore * 0.20f) +
            (roiScore * 0.10f) +
            (brakeScore * 0.10f) +
            (cutInScore * 0.05f)

        // boost if strong ego braking (but don't make it dominant)
        if (egoBrake >= 0.65f) {
            risk += 0.08f * ((egoBrake - 0.65f) / 0.35f).coerceIn(0f, 1f)
        }

        // Quality gating: in poor frames suppress ORANGE; RED only when clearly critical.
        val conservative = qualityPoor
        if (conservative) {
            // reduce risk a bit to avoid "city lights / bumps" noise
            risk *= 0.88f
        }

        // Lean: high lean -> more jitter / different optical flow; reduce sensitivity moderately.
        if (leanDeg.isFinite()) {
            val lean = abs(leanDeg)
            val k = ((lean - 20f) / 25f).coerceIn(0f, 1f) // after ~20° start reducing
            risk *= (1f - 0.12f * k) // max -12%
        }

        risk = risk.coerceIn(0f, 1f)

        // --- Convert risk -> level (with hysteresis) ---
        val level = riskToLevelWithHysteresis(risk, conservative)

        val state = when (level) {
            2 -> State.CRITICAL
            1 -> State.CAUTION
            else -> State.SAFE
        }

        val reasonBits = buildReasonBits(
            level = level,
            risk = risk,
            ttc = ttcSec,
            dist = distanceM,
            rel = approachSpeedMps,
            roi = roiC,
            egoOff = off,
            brakeCueActive = brakeCueActive,
            cutInActive = cutInActive,
            egoBrake = egoBrake,
            conservative = conservative
        )

out.level = level
out.riskScore = risk
out.reasonBits = reasonBits
out.state = state
return out
    }

    private fun thresholdsForMode(mode: Int): Thresholds {
    // 1 = Město, 2 = Sport, 3 = Uživatel (Auto is resolved before calling)
    return when (mode) {
        2 -> thrSport
        3 -> {
            // Refresh user thresholds (reads are cheap, avoids object alloc)
            thrUser.ttcOrange = com.mcaw.config.AppPreferences.userTtcOrange
            thrUser.ttcRed = com.mcaw.config.AppPreferences.userTtcRed
            thrUser.distOrange = com.mcaw.config.AppPreferences.userDistOrange
            thrUser.distRed = com.mcaw.config.AppPreferences.userDistRed
            thrUser.relOrange = com.mcaw.config.AppPreferences.userSpeedOrange
            thrUser.relRed = com.mcaw.config.AppPreferences.userSpeedRed
            thrUser
        }
        else -> thrCity
    }
}

}


    private fun scoreLowIsBad(value: Float, redThr: Float, orangeThr: Float): Float {
        if (!value.isFinite() || value <= 0f) return 0f
        if (value <= redThr) return 1f
        if (value <= orangeThr) {
            // linear 1..0 between redThr..orangeThr
            val t = ((value - redThr) / max(0.001f, (orangeThr - redThr))).coerceIn(0f, 1f)
            return 1f - t * 0.55f // keep still high in orange zone
        }
        // beyond orange -> decay
        val t = ((value - orangeThr) / max(0.001f, orangeThr)).coerceIn(0f, 1f)
        return (0.45f * (1f - t)).coerceIn(0f, 0.45f)
    }

    private fun scoreHighIsBad(value: Float, orangeThr: Float, redThr: Float): Float {
        if (!value.isFinite() || value < 0f) return 0f
        if (value >= redThr) return 1f
        if (value >= orangeThr) {
            val t = ((value - orangeThr) / max(0.001f, (redThr - orangeThr))).coerceIn(0f, 1f)
            return 0.55f + t * 0.45f
        }
        val t = (value / max(0.001f, orangeThr)).coerceIn(0f, 1f)
        return 0.55f * t
    }

    private fun scoreTtc(ttc: Float, thr: Thresholds): Float {
        val lvl = ttcLevelWithHysteresis(ttc, thr.ttcOrange, thr.ttcRed)
        return when (lvl) {
            2 -> 1f
            1 -> 0.70f
            else -> {
                if (!ttc.isFinite() || ttc <= 0f) return 0f
                // smooth: large TTC -> small score
                val t = (min(ttc, 10f) / 10f).coerceIn(0f, 1f)
                (0.35f * (1f - t)).coerceIn(0f, 0.35f)
            }
        }
    }

    private fun ttcLevelWithHysteresis(ttc: Float, orangeOn: Float, redOn: Float): Int {
        if (!ttc.isFinite() || ttc <= 0f) {
            lastTtcLevel = 0
            return 0
        }
        val redOff = redOn + 0.6f
        val orangeOff = max(orangeOn + 0.9f, redOff + 0.2f)

        lastTtcLevel = when (lastTtcLevel) {
            2 -> if (ttc >= redOff) { if (ttc <= orangeOn) 1 else 0 } else 2
            1 -> when {
                ttc <= redOn -> 2
                ttc >= orangeOff -> 0
                else -> 1
            }
            else -> when {
                ttc <= redOn -> 2
                ttc <= orangeOn -> 1
                else -> 0
            }
        }
        return lastTtcLevel
    }

    private fun riskToLevelWithHysteresis(risk: Float, conservative: Boolean): Int {
        // Thresholds tuned for v1; conservative suppresses ORANGE
        val orangeOn = if (conservative) 0.62f else 0.45f
        val redOn = if (conservative) 0.82f else 0.75f

        val orangeOff = orangeOn - 0.06f
        val redOff = redOn - 0.05f

        lastLevel = when (lastLevel) {
            2 -> if (risk <= redOff) { if (risk >= orangeOn) 1 else 0 } else 2
            1 -> when {
                risk >= redOn -> 2
                risk <= orangeOff -> 0
                else -> 1
            }
            else -> when {
                risk >= redOn -> 2
                risk >= orangeOn -> 1
                else -> 0
            }
        }
        return lastLevel
    }

            // --- Reason contract (stable bitmask) ---
    companion object {
        const val BIT_TTC = 1 shl 0
        const val BIT_DIST = 1 shl 1
        const val BIT_REL = 1 shl 2
        const val BIT_ROI_LOW = 1 shl 3
        const val BIT_BRAKE_CUE = 1 shl 4
        const val BIT_CUT_IN = 1 shl 5
        const val BIT_EGO_BRAKE = 1 shl 6
        const val BIT_QUALITY_CONSERV = 1 shl 7

        /**
         * Builds a short, human readable WHY string.
         * Call only when needed (debug overlay / sampled logs) to avoid per-frame allocations.
         */
        fun formatReasonShort(reasonBits: Int): String {
            val lvl = (reasonBits ushr 30) and 0x3
            val sb = StringBuilder(32)
            when (lvl) {
                2 -> sb.append("CRIT")
                1 -> sb.append("WARN")
                else -> sb.append("SAFE")
            }
            if ((reasonBits and BIT_TTC) != 0) sb.append(" ttc")
            if ((reasonBits and BIT_DIST) != 0) sb.append(" dist")
            if ((reasonBits and BIT_REL) != 0) sb.append(" rel")
            if ((reasonBits and BIT_ROI_LOW) != 0) sb.append(" roi")
            if ((reasonBits and BIT_BRAKE_CUE) != 0) sb.append(" brakeCue")
            if ((reasonBits and BIT_CUT_IN) != 0) sb.append(" cutIn")
            if ((reasonBits and BIT_EGO_BRAKE) != 0) sb.append(" egoBrake")
            if ((reasonBits and BIT_QUALITY_CONSERV) != 0) sb.append(" QCONSERV")
            return sb.toString()
        }
    }

    private fun buildReasonBits(
        level: Int,
        risk: Float,
        ttc: Float,
        dist: Float,
        rel: Float,
        roi: Float,
        egoOff: Float,
        brakeCueActive: Boolean,
        cutInActive: Boolean,
        egoBrake: Float,
        conservative: Boolean
    ): Int {
        var bits = 0
        // Primary factors: set bits when they are in "meaningful" zone.
        if (ttc.isFinite() && ttc > 0f && ttc <= 4.5f) bits = bits or BIT_TTC
        if (dist.isFinite() && dist > 0f && dist <= 30f) bits = bits or BIT_DIST
        if (rel.isFinite() && rel >= 3f) bits = bits or BIT_REL
        if (roi < 0.35f || egoOff >= 1.15f) bits = bits or BIT_ROI_LOW

        if (brakeCueActive) bits = bits or BIT_BRAKE_CUE
        if (cutInActive) bits = bits or BIT_CUT_IN
        if (egoBrake >= 0.65f) bits = bits or BIT_EGO_BRAKE
        if (conservative) bits = bits or BIT_QUALITY_CONSERV

        // Encode level in top 2 bits for easy parsing (00/01/10).
        bits = bits or ((level and 0x3) shl 30)
        return bits
    }
}
