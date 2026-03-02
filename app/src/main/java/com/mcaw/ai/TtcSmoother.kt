package com.mcaw.ai

import com.mcaw.config.AppPreferences

/**
 * Stateful TTC smoothing with:
 * - hold-last-finite when TTC momentarily becomes invalid (prevents UI blinking)
 * - rate limiting (sec/sec)
 * - asymmetric EMA (faster for worsening TTC than for improving TTC)
 * - speed/context-aware alphas (CITY vs HIGH SPEED)
 *
 * NOTE: No allocations on the hot path.
 */
class TtcSmoother(
    private val ttcInvalidHoldMs: Long,
) {
    private var ttcEma: Float = Float.POSITIVE_INFINITY
    private var ttcEmaValid: Boolean = false
    private var lastTtcUpdateTsMs: Long = -1L
    private var lastTtcFiniteTsMs: Long = -1L

    fun reset() {
        ttcEma = Float.POSITIVE_INFINITY
        ttcEmaValid = false
        lastTtcUpdateTsMs = -1L
        lastTtcFiniteTsMs = -1L
    }

    /**
     * @param effectiveMode AppPreferences.MODE_CITY / MODE_SPORT / MODE_USER
     */
    fun update(
        ttcRaw: Float,
        tsMs: Long,
        riderSpeedMps: Float,
        riderSpeedConfidence: Float,
        effectiveMode: Int,
        recedingStable: Boolean = false,
    ): Float {
        val raw = if (ttcRaw.isFinite() && ttcRaw > 0f) ttcRaw.coerceIn(0.05f, 120f) else Float.POSITIVE_INFINITY

        // If TTC becomes invalid, hold the last finite value briefly to avoid UI blinking.
        if (!raw.isFinite()) {
            val holdMs = if (recedingStable) 300L else ttcInvalidHoldMs
            if (ttcEmaValid && ttcEma.isFinite() && lastTtcFiniteTsMs > 0L && (tsMs - lastTtcFiniteTsMs) <= holdMs) {
                return ttcEma
            }
            ttcEmaValid = false
            ttcEma = Float.POSITIVE_INFINITY
            lastTtcUpdateTsMs = tsMs
            return Float.POSITIVE_INFINITY
        }

        // Remember last finite TTC timestamp
        lastTtcFiniteTsMs = tsMs

        if (!ttcEmaValid || !ttcEma.isFinite() || lastTtcUpdateTsMs <= 0L) {
            ttcEma = raw
            ttcEmaValid = true
            lastTtcUpdateTsMs = tsMs
            return ttcEma
        }

        val dtSec = ((tsMs - lastTtcUpdateTsMs).coerceAtLeast(1L)).toFloat() / 1000f
        lastTtcUpdateTsMs = tsMs

        // Limit TTC change rate (seconds per second). Allow faster drops than rises.
        val maxDropRate = 12.0f  // TTC can drop by up to 12s per 1s
        val maxRiseRate = 3.0f   // TTC can rise by up to 3s per 1s
        val maxDrop = maxDropRate * dtSec
        val maxRise = maxRiseRate * dtSec

        val prev = ttcEma
        val clamped = when {
            raw < prev -> raw.coerceAtLeast(prev - maxDrop)
            else -> raw.coerceAtMost(prev + maxRise)
        }

        val highSpeed = isHighSpeed(
            riderSpeedMps = riderSpeedMps,
            riderSpeedConfidence = riderSpeedConfidence,
            effectiveMode = effectiveMode
        )

        // Requested behavior:
        // - TTC drops (hazard rising): CITY 0.60, HIGH SPEED 0.75
        // - TTC rises (hazard clearing): CITY 0.18, HIGH SPEED 0.22
        val alphaDown = if (highSpeed) 0.75f else 0.60f
        val alphaUp = if (highSpeed) 0.22f else 0.18f
        val alpha = if (clamped < prev) alphaDown else alphaUp

        ttcEma = prev + alpha * (clamped - prev)
        return ttcEma
    }

    private fun isHighSpeed(
        riderSpeedMps: Float,
        riderSpeedConfidence: Float,
        effectiveMode: Int
    ): Boolean {
        // Manual SPORT is treated as high-speed regardless of speed confidence.
        if (effectiveMode == AppPreferences.MODE_SPORT) return true

        // Otherwise, decide by speed if it's reliable.
        val speedOk = riderSpeedMps.isFinite() && riderSpeedMps >= 0f && riderSpeedConfidence >= 0.60f
        if (!speedOk) return false
        val kmh = riderSpeedMps * 3.6f
        return kmh >= 55f
    }
}
