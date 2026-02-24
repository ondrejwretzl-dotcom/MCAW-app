package com.mcaw.util

import com.mcaw.risk.RiskEngine

/**
 * Stable mapping from audit reason bits (integer contract) to human-readable short WHY.
 *
 * Notes:
 * - reasonBits is the primary contract (logged & broadcast).
 * - UI should NOT invent its own rules; it should only format what RiskEngine produced.
 */
object ReasonTextMapper {

    /**
     * Short, stable text derived from [reasonBits].
     * If bits are 0, returns empty string.
     */
    fun short(reasonBits: Int): String {
        if (reasonBits == 0) return ""
        val payload = RiskEngine.stripReasonVersion(reasonBits)
        if ((payload and RiskEngine.BIT_SUPPRESS_ADJACENT_OVERTAKE) != 0) return "Potlačeno: předjíždění vedle dráhy"
        if ((payload and RiskEngine.BIT_SUPPRESS_RECEDING_OBJECT) != 0) return "Potlačeno: objekt se vzdaluje"
        if ((payload and RiskEngine.BIT_SUPPRESS_STANDING) != 0) return "Potlačeno: stojíme"
        if ((payload and RiskEngine.BIT_SUPPRESS_BOTTOM_OCCLUSION_NO_CONFIRM) != 0) return "Potlačeno: dolní okluze bez potvrzení"
        if ((payload and RiskEngine.BIT_OCCLUSION_CONFIRMED) != 0) return "Potvrzená dolní okluze"
        if ((payload and RiskEngine.BIT_OCCLUSION_CANDIDATE) != 0) return "Kandidát dolní okluze"
        return RiskEngine.formatReasonShort(reasonBits)
    }

    /**
     * Prefer [reasonBits] formatting; if bits are missing/0, use [fallbackText] (legacy extra).
     */
    fun shortOrFallback(reasonBits: Int, fallbackText: String): String {
        val s = short(reasonBits)
        return if (s.isNotEmpty()) s else fallbackText
    }
}
