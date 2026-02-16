package com.mcaw.risk

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MCAW 2.0 – RiskEngine v2 (reasonBits contract)
 *
 * Zásady:
 * - O(1) výpočet
 * - Bez IO / bez UI závislostí
 * - Bez per-frame alokací v hot path (evaluate)
 *
 * Pozn.: Textové WHY se generuje jen na vyžádání (debug overlay / sampled log).
 */
class RiskEngine {

    enum class State { SAFE, CAUTION, CRITICAL }

    /**
     * Výsledek evaluace. Mutable kvůli re-use (minimalizace GC).
     * Caller si NESMÍ instanci držet dlouhodobě – RiskEngine ji znovu přepíše.
     */
    class Result(
        var level: Int = 0,          // 0 SAFE, 1 ORANGE, 2 RED
        var riskScore: Float = 0f,    // 0..1
        var reasonBits: Int = 0,      // stabilní WHY kontrakt
        var state: State = State.SAFE
    )

    data class Thresholds(
        val ttcOrange: Float,
        val ttcRed: Float,
        val distOrange: Float,
        val distRed: Float,
        val relOrange: Float,
        val relRed: Float
    )

    companion object {
        // --- Reason bits (stabilní kontrakt pro logy/overlay) ---
        // Kontrakt je verzovaný v horním nibblu (bits 28..31), aby nebylo nutné měnit CSV schema.
        // Parser: version = (reasonBits ushr 28) & 0xF ; payloadBits = reasonBits & 0x0FFFFFFF
        const val REASON_BITS_VERSION_CURRENT = 2
        private const val REASON_BITS_VERSION_SHIFT = 28
        private const val REASON_BITS_VERSION_MASK = 0xF shl REASON_BITS_VERSION_SHIFT
        private const val REASON_BITS_PAYLOAD_MASK = REASON_BITS_VERSION_MASK.inv()

        const val BIT_TTC = 1 shl 0
        const val BIT_DIST = 1 shl 1
        const val BIT_REL = 1 shl 2
        const val BIT_ROI_LOW = 1 shl 3
        const val BIT_BRAKE_CUE = 1 shl 4
        const val BIT_CUT_IN = 1 shl 5
        const val BIT_EGO_BRAKE = 1 shl 6
        const val BIT_QUALITY_CONSERV = 1 shl 7
        const val BIT_RIDER_STAND = 1 shl 8
        const val BIT_TTC_SLOPE_STRONG = 1 shl 9
        const val BIT_RED_COMBO_OK = 1 shl 10
        const val BIT_RED_GUARDED = 1 shl 11

        fun reasonVersion(reasonBits: Int): Int = (reasonBits ushr REASON_BITS_VERSION_SHIFT) and 0xF

        fun stripReasonVersion(reasonBits: Int): Int = reasonBits and REASON_BITS_PAYLOAD_MASK

        fun packReasonBits(payloadBits: Int, version: Int = REASON_BITS_VERSION_CURRENT): Int {
            if (payloadBits == 0) return 0
            val v = (version and 0xF) shl REASON_BITS_VERSION_SHIFT
            return (payloadBits and REASON_BITS_PAYLOAD_MASK) or v
        }

        fun formatReasonShort(bits: Int): String {
            val payload = stripReasonVersion(bits)
            if (payload == 0) return "SAFE"
            val sb = StringBuilder(48)
            if ((payload and BIT_RIDER_STAND) != 0) sb.append("RID_STAND ")
            if ((payload and BIT_TTC) != 0) sb.append("TTC ")
            if ((payload and BIT_TTC_SLOPE_STRONG) != 0) sb.append("SLOPE ")
            if ((payload and BIT_DIST) != 0) sb.append("DIST ")
            if ((payload and BIT_REL) != 0) sb.append("REL ")
            if ((payload and BIT_ROI_LOW) != 0) sb.append("ROI_LOW ")
            if ((payload and BIT_BRAKE_CUE) != 0) sb.append("BRAKE_CUE ")
            if ((payload and BIT_CUT_IN) != 0) sb.append("CUT_IN ")
            if ((payload and BIT_EGO_BRAKE) != 0) sb.append("EGO_BRAKE ")
            if ((payload and BIT_QUALITY_CONSERV) != 0) sb.append("QCONSERV ")
            if ((payload and BIT_RED_COMBO_OK) != 0) sb.append("RED_OK ")
            if ((payload and BIT_RED_GUARDED) != 0) sb.append("RED_GUARD ")
            // trim trailing space
            if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
            return sb.toString()
        }
    }

    // Hysteresis on riskScore (prevents blinking)
    private var lastLevel: Int = 0

    // EMA-integrated riskScore (anti-blink / continuity)
    private var emaRisk: Float = 0f
    private var emaInit: Boolean = false

    // Separate hysteresis for TTC (kept inside engine; not fallback)
    private var lastTtcLevel: Int = 0

    // Single reusable result instance (avoid per-frame allocations)
    private val out = Result()

    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        tsMs: Long,
        effectiveMode: Int,
        distanceM: Float,
        approachSpeedMps: Float,
        ttcSec: Float,
        ttcSlopeSecPerSec: Float = 0f, // sec/sec (negative = closing faster)
        roiContainment: Float,   // 0..1
        egoOffsetN: Float,       // 0..2 (0 center)
        cutInActive: Boolean,
        brakeCueActive: Boolean,
        brakeCueStrength: Float, // 0..1
        // C2: quality acts as a weight (0..1), not a hard gate.
        // 1.0 = full confidence, lower values = more conservative thresholds and lower gain.
        qualityWeight: Float = 1f,
        riderSpeedMps: Float,
        egoBrakingConfidence: Float, // 0..1
        leanDeg: Float              // deg, NaN if unknown
    ): Result {

        val thr = thresholdsForMode(effectiveMode)

        // --- Core scores (0..1) ---
        val ttcLevel = ttcLevelWithHysteresis(ttcSec, thr.ttcOrange, thr.ttcRed)
        val ttcScore = when (ttcLevel) {
            2 -> 1f
            1 -> 0.70f
            else -> {
                if (!ttcSec.isFinite() || ttcSec <= 0f) 0f
                else {
                    val t = (min(ttcSec, 10f) / 10f).coerceIn(0f, 1f)
                    (0.35f * (1f - t)).coerceIn(0f, 0.35f)
                }
            }
        }

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

        // --- Weighted raw risk ---
        var rawRisk =
            (ttcScore * 0.35f) +
            (distScore * 0.20f) +
            (relScore * 0.20f) +
            (roiScore * 0.10f) +
            (brakeScore * 0.10f) +
            (cutInScore * 0.05f)

        // boost if strong ego braking (but don't make it dominant)
        if (egoBrake >= 0.65f) {
            rawRisk += 0.08f * ((egoBrake - 0.65f) / 0.35f).coerceIn(0f, 1f)
        }

        // C2: Quality is a continuous weight (no ON/OFF gating).
        // Lower quality => slightly lower raw risk gain + stricter level thresholds.
        val qW = qualityWeight.coerceIn(0.60f, 1.0f)
        val conserv = (1f - qW).coerceIn(0f, 1f)
        // reduce gain up to ~12% at worst quality (matches previous boolean behavior, but smooth)
        rawRisk *= (1f - 0.12f * conserv)

        // Lean: high lean -> more jitter / different optical flow; reduce sensitivity moderately.
        if (leanDeg.isFinite()) {
            val lean = abs(leanDeg)
            val k = ((lean - 20f) / 25f).coerceIn(0f, 1f) // after ~20° start reducing
            rawRisk *= (1f - 0.12f * k) // max -12%
        }

        rawRisk = rawRisk.coerceIn(0f, 1f)

        // --- EMA integrate (continuity) ---
        val riseAlpha = 0.30f
        val fallAlpha = 0.15f
        if (!emaInit) {
            emaRisk = rawRisk
            emaInit = true
        } else {
            val a = if (rawRisk >= emaRisk) riseAlpha else fallAlpha
            emaRisk += a * (rawRisk - emaRisk)
        }
        val risk = emaRisk.coerceIn(0f, 1f)

        // --- CRITICAL combo guard (avoid single-factor RED spikes) ---
        val slope = if (ttcSlopeSecPerSec.isFinite()) ttcSlopeSecPerSec else 0f
        val slopeThr = -1.0f - 0.40f * conserv
        val slopeStrong = slope <= slopeThr
        val strongTtc = (ttcLevel >= 2) || (ttcScore >= 0.85f)
        val strongK = 0.85f + 0.05f * conserv
        val midK = 0.60f + 0.10f * conserv
        val strongDist = distScore >= strongK
        val strongRel = relScore >= strongK
        val midDist = distScore >= midK
        val midRel = relScore >= midK
        val allowRed = strongTtc && (strongDist || strongRel || (slopeStrong && (midDist || midRel)))

        // --- Convert risk -> level (with hysteresis) ---
        val preGuardLevel = riskToLevelWithHysteresis(risk, conserv)
        var level = preGuardLevel
        if (preGuardLevel == 2 && !allowRed) {
            // Cap to ORANGE when combo confirmation is missing.
            // Also break RED latch immediately to prevent "stuck RED" on spikes.
            lastLevel = 1
            level = 1
        }

        val state = when (level) {
            2 -> State.CRITICAL
            1 -> State.CAUTION
            else -> State.SAFE
        }

        // --- Build reason bits (stable contract; cheap) ---
        var bits = 0
        if (level > 0) {
            if (ttcLevel > 0 || ttcScore >= 0.55f) bits = bits or BIT_TTC
            if (distScore >= 0.55f) bits = bits or BIT_DIST
            if (relScore >= 0.60f) bits = bits or BIT_REL
            if (roiScore <= 0.40f) bits = bits or BIT_ROI_LOW
            if (brakeCueActive) bits = bits or BIT_BRAKE_CUE
            if (cutInActive) bits = bits or BIT_CUT_IN
            if (egoBrake >= 0.65f) bits = bits or BIT_EGO_BRAKE
            if (conserv >= 0.15f) bits = bits or BIT_QUALITY_CONSERV
            if (slopeStrong) bits = bits or BIT_TTC_SLOPE_STRONG
            if (level == 2 && allowRed) bits = bits or BIT_RED_COMBO_OK
            if (level == 1 && preGuardLevel == 2 && !allowRed) bits = bits or BIT_RED_GUARDED
        } else {
            if (conserv >= 0.15f) bits = bits or BIT_QUALITY_CONSERV
        }

        val packedBits = packReasonBits(bits)

        // Fill reusable result
        out.level = level
        out.riskScore = risk
        out.reasonBits = packedBits
        out.state = state
        return out
    }

    fun standingResult(_riderSpeedMps: Float): Result {
        out.level = 0
        out.riskScore = 0f
        out.reasonBits = packReasonBits(BIT_RIDER_STAND)
        out.state = State.SAFE
        return out
    }

    private fun thresholdsForMode(mode: Int): Thresholds {
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
            val t = ((value - redThr) / max(0.001f, (orangeThr - redThr))).coerceIn(0f, 1f)
            return 1f - t * 0.55f
        }
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

    private fun riskToLevelWithHysteresis(risk: Float, conserv: Float): Int {
        // C2: smooth thresholds between normal and conservative values.
        val c = conserv.coerceIn(0f, 1f)
        val orangeOn = 0.45f + 0.17f * c
        val redOn = 0.75f + 0.07f * c

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
}
