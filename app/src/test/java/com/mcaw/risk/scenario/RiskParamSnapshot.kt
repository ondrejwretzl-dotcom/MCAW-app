package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine

/**
 * Stabilní mapping pro "kritické parametry" v reportech scénářů.
 *
 * Cíl:
 * - index.html a MD reporty musí umět vypsat hodnoty, které mění ORANGE/RED
 * - bez reflexe a bez zásahu do hot-path enginu
 * - klíče jsou stabilní stringy (používají se v katalogu scénářů)
 */
object RiskParamSnapshot {

    /**
     * Vrací hodnotu parametru jako string pro report.
     *
     * Pozn.: Používáme [RiskEngine.DerivedThresholds] (test-only) + const val z RiskEngine.
     */
    fun valueFor(key: String, derived: RiskEngine.DerivedThresholds): String? {
        return when (key) {
            // --- Derived thresholds (z kódu, závisí na mode/quality/conserv) ---
            "risk.ttcOrange" -> fmt(derived.ttcOrange) + " s"
            "risk.ttcRed" -> fmt(derived.ttcRed) + " s"
            "risk.distOrange" -> fmt(derived.distOrange) + " m"
            "risk.distRed" -> fmt(derived.distRed) + " m"
            "risk.relOrange" -> fmt(derived.relOrange) + " m/s"
            "risk.relRed" -> fmt(derived.relRed) + " m/s"
            "risk.orangeOn" -> fmt(derived.orangeOn)
            "risk.orangeOff" -> fmt(derived.orangeOff)
            "risk.redOn" -> fmt(derived.redOn)
            "risk.redOff" -> fmt(derived.redOff)
            "risk.redCombo.slopeThr" -> fmt(derived.slopeThr)
            "risk.redCombo.strongK" -> fmt(derived.strongK)
            "risk.redCombo.midK" -> fmt(derived.midK)
            "risk.distDynamic" -> if (derived.distDynamic) "true" else "false"
            "risk.distHeadwayOrangeSec" -> fmt(derived.distHeadwayOrangeSec) + " s"
            "risk.distHeadwayRedSec" -> fmt(derived.distHeadwayRedSec) + " s"

            // --- RiskEngine constants (stabilní napříč derived) ---
            "suppress.receding.epsMps" -> fmt(RiskEngine.RECEDE_EPS_MPS) + " m/s"
            "suppress.approach.epsMps" -> fmt(RiskEngine.APPROACH_EPS_MPS) + " m/s"
            "suppress.stand.speedMps" -> fmt(RiskEngine.STAND_SPEED_MPS) + " m/s"
            "suppress.creep.speedMps" -> fmt(RiskEngine.CREEP_SPEED_MPS) + " m/s"
            "roi.contain.low" -> fmt(RiskEngine.ROI_CONTAIN_LOW)
            "ego.offset.high" -> fmt(RiskEngine.EGO_OFFSET_HIGH)
            "dist.closeM" -> fmt(RiskEngine.DIST_CLOSE_M) + " m"
            "suppress.adjacentOvertake.scale" -> fmt(RiskEngine.S_ADJACENT_OVERTAKE)
            "suppress.bottomTouchCandidate.scale" -> fmt(RiskEngine.S_BOTTOM_TOUCH_CANDIDATE)
            "suppress.receding.scale" -> fmt(RiskEngine.S_RECEDING)
            "ema.alphaRel" -> fmt(RiskEngine.EMA_ALPHA_REL)
            "ema.alphaApproach" -> fmt(RiskEngine.EMA_ALPHA_APP)
            "occl.kStable" -> RiskEngine.K_STABLE.toString()
            "occl.kConfirm" -> RiskEngine.K_CONFIRM_OCCL.toString()
            "occl.kRelease" -> RiskEngine.K_RELEASE.toString()
            else -> null
        }
    }

    fun fmtCriticalParams(keys: List<String>, derived: RiskEngine.DerivedThresholds): List<Pair<String, String>> {
        return keys.mapNotNull { k -> valueFor(k, derived)?.let { v -> k to v } }
    }

    private fun fmt(v: Float): String {
        if (!v.isFinite()) return "NaN"
        val s = String.format(java.util.Locale.US, "%.3f", v)
        // strip trailing zeros
        return s.trimEnd('0').trimEnd('.')
    }
}
