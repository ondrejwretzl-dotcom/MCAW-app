package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine

/**
 * Minimal snapshot of "kritických" parametrů (odvozených z kódu), které typicky mění ORANGE/RED.
 *
 * Zásada: nic nehádat – buď umíme hodnotu z kódu získat, nebo ji v reportu označíme jako N/A.
 *
 * Pozn.: pro testy používáme to samé, co používá runner: RiskEngine.debugDerivedThresholds(...)
 */
object RiskParamSnapshot {

    /**
     * Mapuje stabilní klíče -> čitelné hodnoty (string). Neznámé klíče vrací "N/A".
     */
    fun snapshot(
        engine: RiskEngine,
        effectiveMode: Int,
        qualityWeight: Float,
        dynamicDistanceEnabled: Boolean,
        dynamicDistanceOrangeSec: Float,
        dynamicDistanceRedSec: Float
    ): Map<String, String> {
        val d = engine.debugDerivedThresholds(
            effectiveMode = effectiveMode,
            qualityWeight = qualityWeight,
            dynamicDistanceEnabled = dynamicDistanceEnabled,
            dynamicDistanceOrangeSec = dynamicDistanceOrangeSec,
            dynamicDistanceRedSec = dynamicDistanceRedSec
        )

        // NOTE: DerivedThresholds je kontrakt test frameworku. Klíče níže jsou náš stabilní "Spec" kontrakt.
        return linkedMapOf(
            // TTC
            "thr.ttc.orangeSec" to fmt3(d.ttcOrange),
            "thr.ttc.redSec" to fmt3(d.ttcRed),

            // Distance
            "thr.dist.orangeM" to fmt3(d.distOrange),
            "thr.dist.redM" to fmt3(d.distRed),

            // Approach speed
            "thr.approach.orangeMps" to fmt3(d.relOrange),
            "thr.approach.redMps" to fmt3(d.relRed),

            // Risk hysteresis
            "thr.risk.orangeOn" to fmt3(d.orangeOn),
            "thr.risk.orangeOff" to fmt3(d.orangeOff),
            "thr.risk.redOn" to fmt3(d.redOn),
            "thr.risk.redOff" to fmt3(d.redOff),

            // Combo guard
            "guard.redCombo.slopeThr" to fmt3(d.slopeThr),
            "guard.redCombo.strongK" to fmt3(d.strongK),
            "guard.redCombo.midK" to fmt3(d.midK),

            // Dynamic distance settings (gate)
            "dynDist.enabled" to dynamicDistanceEnabled.toString(),
            "dynDist.orangeSec" to fmt3(dynamicDistanceOrangeSec),
            "dynDist.redSec" to fmt3(dynamicDistanceRedSec)
        )
    }

    fun valueOrNa(snapshot: Map<String, String>, key: String): String = snapshot[key] ?: "N/A"

    private fun fmt3(v: Float): String {
        return if (v.isFinite()) String.format("%.3f", v) else "NaN"
    }
}
