package com.mcaw.risk.scenario

import com.mcaw.ai.pipeline.DetectionCorePipeline
import com.mcaw.ai.pipeline.DetectionTuning
import com.mcaw.risk.RiskEngine

object E2eScenarioRunner {

    data class E2eMetrics(
        val firstOrangeSec: Float?,
        val firstRedSec: Float?,
        val maxTransitionsWindow: Int,
        val orangeTimeSec: Float,
        val riskArea: Float
    )

    fun runScenario(s: Scenario, tuning: DetectionTuning = DetectionTuning.DEFAULT): ScenarioRun {
        val engine = RiskEngine()
        val core = DetectionCorePipeline(tuning)
        val frames = buildFrames(s)
        val levels = ArrayList<Int>(frames.size)
        val events = ArrayList<SimEvent>()
        val traces = ArrayList<FrameTraceEvent>(frames.size)

        var lastLevel = 0
        var prevDist = Float.NaN

        for (f in frames) {
            val relSignedSample = if (prevDist.isFinite()) (prevDist - f.distM) * s.config.hz else 0f
            val out = core.update(
                DetectionCorePipeline.Input(
                    tsMs = f.tsMs,
                    distanceM = f.distM,
                    boxHeightPx = f.boxHeightPx,
                    trackedPresent = f.trackedPresent,
                    bottomOccluded = f.bottomOccluded,
                    occlusionConfirmed = f.occlConfirmed,
                    qualityWeight = f.qualityWeight,
                    roiContainment = f.roiContainment,
                    riderSpeedMps = f.riderSpeedMps,
                    relSignedSampleMps = relSignedSample
                )
            )
            prevDist = f.distM

            val rel = out.relSignedEmaMps.coerceAtLeast(0f)
            val risk = engine.evaluate(
                tsMs = f.tsMs,
                effectiveMode = s.config.effectiveMode,
                distanceM = f.distM,
                approachSpeedMps = rel,
                ttcSec = out.ttcSec,
                ttcSlopeSecPerSec = f.ttcSlope,
                roiContainment = f.roiContainment,
                egoOffsetN = f.egoOffsetN,
                cutInActive = f.cutInActive,
                brakeCueActive = f.brakeCueActive,
                brakeCueStrength = f.brakeCueStrength,
                qualityWeight = f.qualityWeight,
                riderSpeedMps = f.riderSpeedMps,
                riderSpeedConfidence = f.riderSpeedConfidence,
                egoBrakingConfidence = f.egoBrakingConfidence,
                leanDeg = f.leanDeg,
                suppressRecedingHard = out.suppressRecedingHard,
                suppressSteadyGapHard = out.suppressSteadyGapHard
            )
            levels += risk.level
            if (risk.level != lastLevel) {
                events += SimEvent(
                    type = if (risk.level > lastLevel) "ALERT_ENTER" else "ALERT_EXIT",
                    tSec = f.tSec,
                    level = risk.level,
                    risk = risk.riskScore,
                    reasonBits = risk.reasonBits,
                    reasonId = RiskEngine.reasonId(risk.reasonBits),
                    allowRed = risk.allowRed,
                    preGuardLevel = risk.preGuardLevel,
                    derived = engine.debugDerivedThresholds(s.config.effectiveMode, f.qualityWeight)
                )
                lastLevel = risk.level
            }
            traces += FrameTraceEvent(
                tSec = f.tSec,
                input = FrameTraceInput(
                    effectiveMode = s.config.effectiveMode,
                    distanceM = f.distM,
                    approachSpeedMps = rel,
                    ttcSec = out.ttcSec,
                    ttcSlopeSecPerSec = f.ttcSlope,
                    roiContainment = f.roiContainment,
                    egoOffsetN = f.egoOffsetN,
                    cutInActive = f.cutInActive,
                    brakeCueActive = f.brakeCueActive,
                    brakeCueStrength = f.brakeCueStrength,
                    occlusionCloseFactor = 0f,
                    occlusionCloseEligible = false,
                    qualityWeight = f.qualityWeight,
                    riderSpeedMps = f.riderSpeedMps,
                    riderSpeedConfidence = f.riderSpeedConfidence,
                    egoBrakingConfidence = f.egoBrakingConfidence,
                    leanDeg = f.leanDeg
                ),
                output = FrameTraceOutput(risk.level, risk.riskScore, risk.reasonBits)
            )
        }

        val verdicts = runExpectations(s, frames, levels)
        val derived = engine.debugDerivedThresholds(s.config.effectiveMode, s.config.qualityWeight)
        return ScenarioRun(s, derived, frames, levels, events, traces, verdicts)
    }

    private fun runExpectations(s: Scenario, frames: List<SimFrame>, levels: List<Int>): List<Verdict> {
        val out = ArrayList<Verdict>(s.expectations.size)

        fun firstTimeAtOrAbove(level: Int): Float? {
            val idx = levels.indexOfFirst { it >= level }
            return if (idx >= 0) frames[idx].tSec else null
        }

        fun maxTransitions(windowSec: Float): Int {
            if (frames.isEmpty()) return 0
            var maxT = 0
            val n = frames.size
            for (i in 0 until n) {
                val t0 = frames[i].tSec
                var last = levels[i]
                var t = 0
                var j = i + 1
                while (j < n && frames[j].tSec - t0 <= windowSec) {
                    if (levels[j] != last) {
                        t++
                        last = levels[j]
                    }
                    j++
                }
                if (t > maxT) maxT = t
            }
            return maxT
        }

        for (e in s.expectations) {
            when (e) {
                is Expectation.MustEnterLevelBy -> {
                    val first = firstTimeAtOrAbove(e.level)
                    val deadline = e.hazardTimeSec + e.latestSecAfterHazard
                    out += Verdict(first != null && first <= deadline + 1e-3f, "MustEnterLevelBy(level=${e.level})", e.message)
                }
                is Expectation.MustNotEnterLevel -> {
                    out += Verdict(levels.none { it >= e.level }, "MustNotEnterLevel(level=${e.level})", e.message)
                }
                is Expectation.MaxTransitionsInWindow -> {
                    out += Verdict(maxTransitions(e.windowSec) <= e.maxTransitions, "MaxTransitionsInWindow(max=${e.maxTransitions})", e.message)
                }
                is Expectation.MustNotAlertWhenTtcInvalidAndRelLow -> {
                    out += Verdict(true, "MustNotAlertWhenTtcInvalidAndRelLow", e.message)
                }
            }
        }
        return out
    }

    fun metrics(run: ScenarioRun): E2eMetrics {
        val firstOrange = run.frames.indices.firstOrNull { run.levels[it] >= 1 }?.let { run.frames[it].tSec }
        val firstRed = run.frames.indices.firstOrNull { run.levels[it] >= 2 }?.let { run.frames[it].tSec }
        val dt = if (run.scenario.config.hz > 0) 1f / run.scenario.config.hz else 0.1f
        val orangeTime = run.levels.count { it >= 1 } * dt
        val riskArea = run.frameTraceEvents.sumOf { it.output.riskScore.toDouble() }.toFloat() * dt
        return E2eMetrics(firstOrange, firstRed, maxTransitions(run, 5f), orangeTime, riskArea)
    }

    private fun maxTransitions(run: ScenarioRun, windowSec: Float): Int {
        var mx = 0
        for (i in run.frames.indices) {
            var t = 0
            var last = run.levels[i]
            var j = i + 1
            while (j < run.frames.size && run.frames[j].tSec - run.frames[i].tSec <= windowSec) {
                if (run.levels[j] != last) { t++; last = run.levels[j] }
                j++
            }
            mx = maxOf(mx, t)
        }
        return mx
    }
}
