package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine
import kotlin.math.max

/**
 * Scenario DSL for MCAW 2.0 offline simulations.
 *
 * Goals:
 * - Human readable scenario "story".
 * - Deterministic inputs (no video dependency).
 * - Machine-usable output for regression + tuning.
 */

data class ScenarioCatalog(
    val title: String,
    val version: String,
    val scenarios: List<Scenario>
)

data class Scenario(
    val id: String,
    val suite: ScenarioSuite = ScenarioSuite.ENGINE_ONLY,
    val title: String,
    val domain: Domain,
    val vehicle: Vehicle,
    val notes: String,
    val config: ScenarioConfig,
    val expectations: List<Expectation>,
    val segments: List<Segment>
)

enum class Domain { CITY, TUNNEL, HIGHWAY, RURAL }
enum class ScenarioSuite { ENGINE_ONLY, E2E }
enum class Vehicle { CAR, MOTO }

data class ScenarioConfig(
    /** effectiveMode passed into RiskEngine (1 city/default, 2 sport, 3 user) */
    val effectiveMode: Int = 1,
    /** simulation rate in Hz (10Hz recommended for risk stability tests) */
    val hz: Int = 10,
    /** rider speed in m/s (used by engine + brake cue gating in real pipeline) */
    val riderSpeedMps: Float = 12f,
    /** rider speed confidence 0..1 */
    val riderSpeedConfidence: Float = 1.0f,
    /** optional dynamic-distance mode override (null = engine default) */
    val dynamicDistanceEnabled: Boolean? = null,
    /** optional dynamic-distance RED headway sec override */
    val dynamicDistanceRedSec: Float? = null,
    /** optional dynamic-distance ORANGE headway sec override */
    val dynamicDistanceOrangeSec: Float? = null,
    /** qualityWeight 0.6..1.0 */
    val qualityWeight: Float = 1.0f,
    /** default ROI containment 0..1 */
    val roiContainment: Float = 1.0f,
    /** ego offset normalized 0..2 */
    val egoOffsetN: Float = 0.0f,
    /** lean angle deg (NaN if unknown / car) */
    val leanDeg: Float = Float.NaN,
    /** derive REL from distance derivative EMA in runner (default MCAW 2.0 contract) */
    val deriveRelFromDistance: Boolean = true
)

data class Segment(
    val tFromSec: Float,
    val tToSec: Float,
    val label: String,
    /** meters */
    val distanceM: (t: Float) -> Float,
    /** m/s (closing speed; negative means pulling away, but engine clamps <0 to 0) */
    val approachSpeedMps: (t: Float) -> Float,
    /** seconds; may be NaN/Inf in special tests */
    val ttcSec: (t: Float) -> Float,
    /** sec/sec; negative = closing faster */
    val ttcSlopeSecPerSec: (t: Float) -> Float = { 0f },
    val cutInActive: (t: Float) -> Boolean = { false },
    val brakeCueActive: (t: Float) -> Boolean = { false },
    val brakeCueStrength: (t: Float) -> Float = { 0f },
    val egoBrakingConfidence: (t: Float) -> Float = { 0f },
    /** optional rider acceleration profile (m/s^2). If speed override is missing, integrated over dt. */
    val riderAccelMps2: (t: Float) -> Float? = { null },
    /** optional explicit rider speed profile (m/s), takes precedence over acceleration integration. */
    val riderSpeedMps: (t: Float) -> Float? = { null },
    /** optional rider speed confidence profile 0..1 */
    val riderSpeedConfidence: (t: Float) -> Float? = { null },
    val roiContainment: (t: Float) -> Float? = { null },
    val egoOffsetN: (t: Float) -> Float? = { null },
    val qualityWeight: (t: Float) -> Float? = { null },
    val leanDeg: (t: Float) -> Float? = { null },
    val boxHeightPx: (t: Float) -> Float? = { null },
    val trackedPresent: (t: Float) -> Boolean? = { null },
    val bottomOccluded: (t: Float) -> Boolean? = { null },
    val occlConfirmed: (t: Float) -> Boolean? = { null }
)

sealed class Expectation {
    data class MustEnterLevelBy(
        val level: Int,
        /** relative to hazardTimeSec */
        val latestSecAfterHazard: Float,
        val hazardTimeSec: Float,
        val message: String
    ) : Expectation()

    data class MustNotEnterLevel(
        val level: Int,
        val message: String
    ) : Expectation()

    data class MaxTransitionsInWindow(
        val maxTransitions: Int,
        val windowSec: Float,
        val message: String
    ) : Expectation()

    data class MustNotAlertWhenTtcInvalidAndRelLow(
        val relMpsMax: Float,
        val message: String
    ) : Expectation()
}

data class SimFrame(
    val tSec: Float,
    val tsMs: Long,
    val distM: Float,
    val relMpsRaw: Float,
    val relDerivValid: Boolean,
    val ttcSec: Float,
    val ttcSlope: Float,
    val roiContainment: Float,
    val egoOffsetN: Float,
    val cutInActive: Boolean,
    val brakeCueActive: Boolean,
    val brakeCueStrength: Float,
    val qualityWeight: Float,
    val boxHeightPx: Float,
    val trackedPresent: Boolean,
    val bottomOccluded: Boolean,
    val occlConfirmed: Boolean,
    val riderSpeedMps: Float,
    val riderSpeedConfidence: Float,
    val egoBrakingConfidence: Float,
    val leanDeg: Float,
    val segLabel: String
)

data class SimEvent(
    val type: String,
    val tSec: Float,
    val level: Int,
    val risk: Float,
    val reasonBits: Int,
    val reasonId: Int,
    val allowRed: Boolean?,
    val preGuardLevel: Int?,
    val derived: RiskEngine.DerivedThresholds,
    val extra: Map<String, Any?> = emptyMap()
)


data class FrameTraceInput(
    val effectiveMode: Int,
    val distanceM: Float,
    val approachSpeedMps: Float,
    val ttcSec: Float,
    val ttcSlopeSecPerSec: Float,
    val roiContainment: Float,
    val egoOffsetN: Float,
    val cutInActive: Boolean,
    val brakeCueActive: Boolean,
    val brakeCueStrength: Float,
    val occlusionCloseFactor: Float,
    val occlusionCloseEligible: Boolean,
    val qualityWeight: Float,
    val riderSpeedMps: Float,
    val riderSpeedConfidence: Float,
    val egoBrakingConfidence: Float,
    val leanDeg: Float
)

data class FrameTraceOutput(
    val level: Int,
    val riskScore: Float,
    val reasonBits: Int
)

data class FrameTraceEvent(
    val type: String = "FRAME",
    val tSec: Float,
    val input: FrameTraceInput,
    val output: FrameTraceOutput,
    val derived: RiskEngine.DerivedThresholds? = null
)

data class ScenarioRun(
    val scenario: Scenario,
    val derived: RiskEngine.DerivedThresholds,
    val frames: List<SimFrame>,
    val levels: List<Int>,
    val events: List<SimEvent>,
    val frameTraceEvents: List<FrameTraceEvent>,
    val verdicts: List<Verdict>
)

data class Verdict(
    val ok: Boolean,
    val rule: String,
    val details: String
)

fun buildFrames(s: Scenario): List<SimFrame> {
    val hz = max(1, s.config.hz)
    val dt = 1f / hz.toFloat()
    val frames = ArrayList<SimFrame>(hz * 20)
    var tsMs = 0L
    val segments = s.segments.sortedBy { it.tFromSec }
    var simRiderSpeedMps = s.config.riderSpeedMps.coerceAtLeast(0f)

    for (seg in segments) {
        var t = seg.tFromSec
        while (t <= seg.tToSec + 1e-6f) {
            val dist = seg.distanceM(t)
            val relRaw = seg.approachSpeedMps(t)
            val ttc = seg.ttcSec(t)
            val slope = seg.ttcSlopeSecPerSec(t)
            val roi = seg.roiContainment(t) ?: s.config.roiContainment
            val egoOff = seg.egoOffsetN(t) ?: s.config.egoOffsetN
            val qW = seg.qualityWeight(t) ?: s.config.qualityWeight
            val lean = seg.leanDeg(t) ?: s.config.leanDeg
            val explicitSpeed = seg.riderSpeedMps(t)
            simRiderSpeedMps = if (explicitSpeed != null) {
                explicitSpeed.coerceAtLeast(0f)
            } else {
                val a = seg.riderAccelMps2(t) ?: 0f
                (simRiderSpeedMps + a * dt).coerceAtLeast(0f)
            }
            val riderSpeedConf = (seg.riderSpeedConfidence(t) ?: s.config.riderSpeedConfidence).coerceIn(0f, 1f)

            frames.add(
                SimFrame(
                    tSec = t,
                    tsMs = tsMs,
                    distM = dist,
                    relMpsRaw = relRaw,
                    relDerivValid = true,
                    ttcSec = ttc,
                    ttcSlope = slope,
                    roiContainment = roi,
                    egoOffsetN = egoOff,
                    cutInActive = seg.cutInActive(t),
                    brakeCueActive = seg.brakeCueActive(t),
                    brakeCueStrength = seg.brakeCueStrength(t),
                    qualityWeight = qW,
                    boxHeightPx = seg.boxHeightPx(t) ?: (1200f / dist.coerceAtLeast(1f)),
                    trackedPresent = seg.trackedPresent(t) ?: true,
                    bottomOccluded = seg.bottomOccluded(t) ?: false,
                    occlConfirmed = seg.occlConfirmed(t) ?: false,
                    riderSpeedMps = simRiderSpeedMps,
                    riderSpeedConfidence = riderSpeedConf,
                    egoBrakingConfidence = seg.egoBrakingConfidence(t),
                    leanDeg = lean,
                    segLabel = seg.label
                )
            )
            tsMs += (dt * 1000f).toLong()
            t += dt
        }
    }
    return frames
}
