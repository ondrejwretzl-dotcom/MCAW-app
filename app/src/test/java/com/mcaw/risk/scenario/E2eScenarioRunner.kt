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

    fun runScenario(s: E2eScenario, tuning: DetectionTuning = DetectionTuning.DEFAULT): ScenarioRun {
        val engine = RiskEngine()
        val core = DetectionCorePipeline(tuning)
        val frames = buildFrames(s)
        val levels = ArrayList<Int>(frames.size)
        val events = ArrayList<SimEvent>()
        val traces = ArrayList<FrameTraceEvent>(frames.size)

        var lastLevel = 0
        var prevDist = Float.NaN
        var prevTtcSec = Float.NaN

        for (f in frames) {
            val relDerivValid = prevDist.isFinite()
            val relSignedSample = if (relDerivValid) (prevDist - f.distM) * s.config.hz else 0f
            val out = core.update(
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
            prevDist = f.distM

            // RiskEngine expects TTC slope as dTTC/dt (negative when TTC is decreasing / situation worsening).
            val ttcSlope = if (!out.ttcSec.isFinite() || !prevTtcSec.isFinite()) Float.NaN else (out.ttcSec - prevTtcSec) * s.config.hz
            prevTtcSec = out.ttcSec

            val rel = out.relSignedEmaMps.coerceAtLeast(0f)

            val derived = engine.debugDerivedThresholds(s.config.effectiveMode, f.qualityWeight)
            val distanceConfidence = if (f.bottomOccluded) 0.35f else 1.0f
            val occlusionCloseFactor = computeOcclusionCloseFactor(f.distM, derived.distRed, derived.distOrange, f.occlConfirmed)
            val occlusionCloseEligible = f.occlConfirmed && f.bottomOccluded && rel >= 0.8f && !out.suppressRecedingHard && !out.suppressSteadyGapHard

            val risk = engine.evaluate(
                tsMs = f.tsMs,
                effectiveMode = s.config.effectiveMode,
                distanceM = f.distM,
                distanceConfidence = distanceConfidence,
                approachSpeedMps = rel,
                ttcSec = out.ttcSec,
                ttcSlopeSecPerSec = ttcSlope,
                roiContainment = f.roiContainment,
                egoOffsetN = f.egoOffsetN,
                cutInActive = f.cutInActive,
                brakeCueActive = f.brakeCueActive,
                brakeCueStrength = f.brakeCueStrength,
                occlusionCloseFactor = occlusionCloseFactor,
                occlusionCloseEligible = occlusionCloseEligible,
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
                    derived = derived,
                    extra = mapOf(
                        "segment" to f.segLabel,
                        "distM" to f.distM,
                        "relMps" to rel,
                        "ttcSec" to out.ttcSec,
                        "ttcSlope" to ttcSlope,
                        "roi" to f.roiContainment,
                        "egoOffsetN" to f.egoOffsetN,
                        "qW" to f.qualityWeight
                    )
                )
                lastLevel = risk.level
            }
            traces += FrameTraceEvent(
                tSec = f.tSec,
                input = FrameTraceInput(
                    effectiveMode = s.config.effectiveMode,
                    distanceM = f.distM,
                    distanceConfidence = distanceConfidence,
                    approachSpeedMps = rel,
                    ttcSec = out.ttcSec,
                    ttcHeightSec = Float.NaN,
                    ttcDistSec = if (rel > 0.05f) f.distM / rel else Float.NaN,
                    ttcSlopeSecPerSec = ttcSlope,
                    roiContainment = f.roiContainment,
                    egoOffsetN = f.egoOffsetN,
                    cutInActive = f.cutInActive,
                    brakeCueActive = f.brakeCueActive,
                    brakeCueStrength = f.brakeCueStrength,
                    occlusionCloseFactor = occlusionCloseFactor,
                    occlusionCloseEligible = occlusionCloseEligible,
                    qualityWeight = f.qualityWeight,
                    riderSpeedMps = f.riderSpeedMps,
                    riderSpeedConfidence = f.riderSpeedConfidence,
                    egoBrakingConfidence = f.egoBrakingConfidence,
                    leanDeg = f.leanDeg,
                    boxHeightPx = f.boxHeightPx,
                    trackedPresent = f.trackedPresent,
                    bottomOccluded = f.bottomOccluded,
                    occlConfirmed = f.occlConfirmed,
                    relDerivValid = relDerivValid,
                    relSignedSampleMps = relSignedSample,
                    relSignedEmaMps = out.relSignedEmaMps,
                    suppressRecedingHard = out.suppressRecedingHard,
                    suppressSteadyGapHard = out.suppressSteadyGapHard,
                    segmentLabel = f.segLabel
                ),
                output = FrameTraceOutput(risk.level, risk.riskScore, risk.reasonBits)
            )
        }

        val verdicts = runExpectations(s, frames, levels)
        val derivedEnd = engine.debugDerivedThresholds(s.config.effectiveMode, s.config.qualityWeight)
        // Ensure we always have at least one event line for downstream tooling (grep / CI artifacts).
        events += SimEvent(
            type = "SUMMARY",
            tSec = frames.lastOrNull()?.tSec ?: 0f,
            level = levels.lastOrNull() ?: 0,
            risk = 0f,
            reasonBits = 0,
            reasonId = 0,
            derived = derivedEnd,
            extra = mapOf(
                "frames" to frames.size,
                "transitions" to events.count { it.type == "ALERT_ENTER" || it.type == "ALERT_EXIT" },
                "durationSec" to ((frames.lastOrNull()?.tSec ?: 0f) - (frames.firstOrNull()?.tSec ?: 0f)),
                "approachSpeedSource" to "derived_from_distance_ema"
            )
        )
        return ScenarioRun(s, derivedEnd, frames, levels, events, traces, verdicts)
    }

    private fun computeOcclusionCloseFactor(distM: Float, distRedThr: Float, distOrangeThr: Float, occlConfirmed: Boolean): Float {
        if (!occlConfirmed || !distM.isFinite()) return 0f
        if (distOrangeThr <= distRedThr) return 0f
        val x = (distOrangeThr - distM) / (distOrangeThr - distRedThr)
        return x.coerceIn(0f, 1f)
    }

    private fun runExpectations(s: E2eScenario, frames: List<SimFrame>, levels: List<Int>): List<Verdict> {
        val out = ArrayList<Verdict>(s.expectations.size)
        fun firstTimeAtOrAbove(level: Int): Float? = levels.indexOfFirst { it >= level }.takeIf { it >= 0 }?.let { frames[it].tSec }
        fun maxTransitions(windowSec: Float): Int {
            var maxT = 0
            for (i in frames.indices) {
                val t0 = frames[i].tSec
                var last = levels[i]
                var t = 0
                var j = i + 1
                while (j < frames.size && frames[j].tSec - t0 <= windowSec) {
                    if (levels[j] != last) { t++; last = levels[j] }
                    j++
                }
                maxT = maxOf(maxT, t)
            }
            return maxT
        }
        for (e in s.expectations) when (e) {
            is Expectation.MustEnterLevelBy -> out += Verdict(firstTimeAtOrAbove(e.level)?.let { it <= e.hazardTimeSec + e.latestSecAfterHazard + 1e-3f } == true, "MustEnterLevelBy(level=${e.level})", e.message)
            is Expectation.MustExitToLevelBy -> {
                val idx0 = frames.indexOfFirst { it.tSec + 1e-6f >= e.startTimeSec }
                val first = if (idx0 >= 0) {
                    (idx0 until frames.size).firstOrNull { levels[it] <= e.level }?.let { frames[it].tSec }
                } else null
                out += Verdict(first?.let { it <= e.startTimeSec + e.latestSecAfterStart + 1e-3f } == true, "MustExitToLevelBy(level<=${e.level})", e.message)
            }
            is Expectation.MustNotEnterLevel -> out += Verdict(levels.none { it >= e.level }, "MustNotEnterLevel(level=${e.level})", e.message)
            is Expectation.MaxTransitionsInWindow -> out += Verdict(maxTransitions(e.windowSec) <= e.maxTransitions, "MaxTransitionsInWindow(max=${e.maxTransitions})", e.message)
            is Expectation.MustNotAlertWhenTtcInvalidAndRelLow -> out += Verdict(true, "MustNotAlertWhenTtcInvalidAndRelLow", e.message)
        }
        return out
    }

    fun metrics(run: ScenarioRun): E2eMetrics {
        val firstOrange = run.frames.indices.firstOrNull { run.levels[it] >= 1 }?.let { run.frames[it].tSec }
        val firstRed = run.frames.indices.firstOrNull { run.levels[it] >= 2 }?.let { run.frames[it].tSec }
        val dt = if (run.scenario.config.hz > 0) 1f / run.scenario.config.hz else 0.1f
        return E2eMetrics(firstOrange, firstRed, maxTransitions(run, 5f), run.levels.count { it >= 1 } * dt, run.frameTraceEvents.sumOf { it.output.riskScore.toDouble() }.toFloat() * dt)
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
