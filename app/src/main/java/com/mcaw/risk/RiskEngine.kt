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

    data class Result(
        val level: Int,            // 0 SAFE, 1 ORANGE, 2 RED
        val riskScore: Float,       // 0..1
        val reason: String,         // krátké WHY
        val state: State
    )

    data class Thresholds(
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

        val reason = buildReason(
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

        return Result(level = level, riskScore = risk, reason = reason, state = state)
    }

    private fun thresholdsForMode(mode: Int): Thresholds {
        // Mirror existing behavior (City default / Sport / User) but include dist+rel too.
        // 1 = Město, 2 = Sport, 3 = Uživatel (Auto is resolved before calling)
        return when (mode) {
            2 -> Thresholds(ttcOrange = 4.0f, ttcRed = 1.5f, distOrange = 30f, distRed = 12f, relOrange = 5f, relRed = 9f)
            3 -> Thresholds(
                ttcOrange = com.mcaw.config.AppPreferences.userTtcOrange,
                ttcRed = com.mcaw.config.AppPreferences.userTtcRed,
                distOrange = com.mcaw.config.AppPreferences.userDistOrange,
                distRed = com.mcaw.config.AppPreferences.userDistRed,
                relOrange = com.mcaw.config.AppPreferences.userSpeedOrange,
                relRed = com.mcaw.config.AppPreferences.userSpeedRed
            )
            else -> Thresholds(ttcOrange = 3.0f, ttcRed = 1.2f, distOrange = 15f, distRed = 8f, relOrange = 3f, relRed = 5f)
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

    private fun buildReason(
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
    ): String {
        val main = when (level) {
            2 -> "CRIT"
            1 -> "WARN"
            else -> "SAFE"
        }
        val parts = ArrayList<String>(6)
        parts.add(main)
        parts.add("r=%.2f".format(risk))
        if (ttc.isFinite()) parts.add("ttc=%.2f".format(ttc))
        if (dist.isFinite()) parts.add("d=%.1f".format(dist))
        if (rel.isFinite()) parts.add("rel=%.1f".format(rel))
        parts.add("roi=%.2f off=%.2f".format(roi, egoOff))
        if (brakeCueActive) parts.add("brakeCue")
        if (cutInActive) parts.add("cutIn")
        if (egoBrake >= 0.65f) parts.add("egoBrake")
        if (conservative) parts.add("QCONSERV")
        return parts.joinToString(" ")
    }
}
