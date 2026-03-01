package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine
import kotlin.math.max

interface ScenarioMeta {
    val id: String
    val title: String
    val domain: Domain
    val vehicle: Vehicle
    val notes: String
    val config: ScenarioConfig
    val expectations: List<Expectation>
    fun segmentsForReport(): List<SegmentForReport>
}

data class SegmentForReport(
    val name: String,
    val tFromSec: Float,
    val tToSec: Float
)

data class ScenarioCatalogEngineOnly(
    val title: String,
    val version: String,
    val scenarios: List<EngineOnlyScenario>
)

data class ScenarioCatalogE2e(
    val title: String,
    val version: String,
    val scenarios: List<E2eScenario>
)

data class EngineOnlyScenario(
    override val id: String,
    override val title: String,
    override val domain: Domain,
    override val vehicle: Vehicle,
    override val notes: String,
    override val config: ScenarioConfig,
    override val expectations: List<Expectation>,
    val segments: List<EngineOnlySegment>
) : ScenarioMeta {
    override fun segmentsForReport(): List<SegmentForReport> = segments.map {
        SegmentForReport(name = it.label, tFromSec = it.tFromSec, tToSec = it.tToSec)
    }
}

data class E2eScenario(
    override val id: String,
    override val title: String,
    override val domain: Domain,
    override val vehicle: Vehicle,
    override val notes: String,
    override val config: ScenarioConfig,
    override val expectations: List<Expectation>,
    val segments: List<E2eSegment>
) : ScenarioMeta {
    override fun segmentsForReport(): List<SegmentForReport> = segments.map {
        SegmentForReport(name = it.label, tFromSec = it.tFromSec, tToSec = it.tToSec)
    }
}

enum class Domain { CITY, TUNNEL, HIGHWAY, RURAL }
enum class Vehicle { CAR, MOTO }

data class ScenarioConfig(
    val effectiveMode: Int = 1,
    val hz: Int = 10,
    val riderSpeedMps: Float = 12f,
    val riderSpeedConfidence: Float = 1.0f,
    val dynamicDistanceEnabled: Boolean? = null,
    val dynamicDistanceRedSec: Float? = null,
    val dynamicDistanceOrangeSec: Float? = null,
    val qualityWeight: Float = 1.0f,
    val roiContainment: Float = 1.0f,
    val egoOffsetN: Float = 0.0f,
    val leanDeg: Float = Float.NaN,
    val deriveRelFromDistance: Boolean = true
)

data class EngineOnlySegment(
    val tFromSec: Float,
    val tToSec: Float,
    val label: String,
    val distanceM: (t: Float) -> Float,
    val approachSpeedMps: (t: Float) -> Float,
    val ttcSec: (t: Float) -> Float,
    val ttcSlopeSecPerSec: (t: Float) -> Float = { 0f },
    val cutInActive: (t: Float) -> Boolean = { false },
    val brakeCueActive: (t: Float) -> Boolean = { false },
    val brakeCueStrength: (t: Float) -> Float = { 0f },
    val egoBrakingConfidence: (t: Float) -> Float = { 0f },
    val riderAccelMps2: (t: Float) -> Float? = { null },
    val riderSpeedMps: (t: Float) -> Float? = { null },
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

data class E2eSegment(
    val tFromSec: Float,
    val tToSec: Float,
    val label: String,
    val distM: (t: Float) -> Float,
    val boxHeightPx: (t: Float) -> Float,
    val trackedPresent: (t: Float) -> Boolean = { true },
    val bottomOccluded: (t: Float) -> Boolean = { false },
    val occlusionConfirmed: (t: Float) -> Boolean = { false },
    val roiContainment: (t: Float) -> Float? = { null },
    val qualityWeight: (t: Float) -> Float? = { null },
    val riderSpeedMps: (t: Float) -> Float? = { null }
)

sealed class Expectation {
    data class MustEnterLevelBy(val level: Int, val latestSecAfterHazard: Float, val hazardTimeSec: Float, val message: String) : Expectation()
    data class MustNotEnterLevel(val level: Int, val message: String) : Expectation()
    data class MaxTransitionsInWindow(val maxTransitions: Int, val windowSec: Float, val message: String) : Expectation()
    data class MustNotAlertWhenTtcInvalidAndRelLow(val relMpsMax: Float, val message: String) : Expectation()
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
    val derived: RiskEngine.DerivedThresholds,
    val extra: Map<String, Any?> = emptyMap()
)

data class FrameTraceInput(
    val effectiveMode: Int,
    val distanceM: Float,
    /** 0..1 confidence of distanceM as a risk input (approx/occlusion should down-weight). */
    val distanceConfidence: Float,
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

data class FrameTraceOutput(val level: Int, val riskScore: Float, val reasonBits: Int)

data class FrameTraceEvent(
    val type: String = "FRAME",
    val tSec: Float,
    val input: FrameTraceInput,
    val output: FrameTraceOutput,
    val derived: RiskEngine.DerivedThresholds? = null
)

data class ScenarioRun(
    val scenario: ScenarioMeta,
    val derived: RiskEngine.DerivedThresholds,
    val frames: List<SimFrame>,
    val levels: List<Int>,
    val events: List<SimEvent>,
    val frameTraceEvents: List<FrameTraceEvent>,
    val verdicts: List<Verdict>
)

data class Verdict(val ok: Boolean, val rule: String, val details: String)

fun buildFrames(s: EngineOnlyScenario): List<SimFrame> {
    val hz = max(1, s.config.hz)
    val dt = 1f / hz.toFloat()
    val frames = ArrayList<SimFrame>(hz * 20)
    var tsMs = 0L
    var simRiderSpeedMps = s.config.riderSpeedMps.coerceAtLeast(0f)

    for (seg in s.segments.sortedBy { it.tFromSec }) {
        var t = seg.tFromSec
        while (t <= seg.tToSec + 1e-6f) {
            val dist = seg.distanceM(t)
            val explicitSpeed = seg.riderSpeedMps(t)
            simRiderSpeedMps = if (explicitSpeed != null) explicitSpeed.coerceAtLeast(0f) else (simRiderSpeedMps + (seg.riderAccelMps2(t) ?: 0f) * dt).coerceAtLeast(0f)
            frames.add(
                SimFrame(
                    tSec = t,
                    tsMs = tsMs,
                    distM = dist,
                    relMpsRaw = seg.approachSpeedMps(t),
                    relDerivValid = true,
                    ttcSec = seg.ttcSec(t),
                    ttcSlope = seg.ttcSlopeSecPerSec(t),
                    roiContainment = seg.roiContainment(t) ?: s.config.roiContainment,
                    egoOffsetN = seg.egoOffsetN(t) ?: s.config.egoOffsetN,
                    cutInActive = seg.cutInActive(t),
                    brakeCueActive = seg.brakeCueActive(t),
                    brakeCueStrength = seg.brakeCueStrength(t),
                    qualityWeight = seg.qualityWeight(t) ?: s.config.qualityWeight,
                    boxHeightPx = seg.boxHeightPx(t) ?: (1200f / dist.coerceAtLeast(1f)),
                    trackedPresent = seg.trackedPresent(t) ?: true,
                    bottomOccluded = seg.bottomOccluded(t) ?: false,
                    occlConfirmed = seg.occlConfirmed(t) ?: false,
                    riderSpeedMps = simRiderSpeedMps,
                    riderSpeedConfidence = (seg.riderSpeedConfidence(t) ?: s.config.riderSpeedConfidence).coerceIn(0f, 1f),
                    egoBrakingConfidence = seg.egoBrakingConfidence(t),
                    leanDeg = seg.leanDeg(t) ?: s.config.leanDeg,
                    segLabel = seg.label
                )
            )
            tsMs += (dt * 1000f).toLong()
            t += dt
        }
    }
    return frames
}

fun buildFrames(s: E2eScenario): List<SimFrame> {
    val hz = max(1, s.config.hz)
    val dt = 1f / hz.toFloat()
    val frames = ArrayList<SimFrame>(hz * 20)
    var tsMs = 0L
    for (seg in s.segments.sortedBy { it.tFromSec }) {
        var t = seg.tFromSec
        while (t <= seg.tToSec + 1e-6f) {
            val dist = seg.distM(t)
            frames.add(
                SimFrame(
                    tSec = t,
                    tsMs = tsMs,
                    distM = dist,
                    relMpsRaw = 0f,
                    relDerivValid = false,
                    ttcSec = Float.NaN,
                    ttcSlope = Float.NaN,
                    roiContainment = seg.roiContainment(t) ?: s.config.roiContainment,
                    egoOffsetN = s.config.egoOffsetN,
                    cutInActive = false,
                    brakeCueActive = false,
                    brakeCueStrength = 0f,
                    qualityWeight = seg.qualityWeight(t) ?: s.config.qualityWeight,
                    boxHeightPx = seg.boxHeightPx(t),
                    trackedPresent = seg.trackedPresent(t),
                    bottomOccluded = seg.bottomOccluded(t),
                    occlConfirmed = seg.occlusionConfirmed(t),
                    riderSpeedMps = seg.riderSpeedMps(t) ?: s.config.riderSpeedMps,
                    riderSpeedConfidence = s.config.riderSpeedConfidence,
                    egoBrakingConfidence = 0f,
                    leanDeg = s.config.leanDeg,
                    segLabel = seg.label
                )
            )
            tsMs += (dt * 1000f).toLong()
            t += dt
        }
    }
    return frames
}
