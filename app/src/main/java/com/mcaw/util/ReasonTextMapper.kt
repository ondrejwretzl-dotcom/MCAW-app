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
