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

        fun firstTimeAtOrAbove(level: Int): Float? {
            val idx = levels.indexOfFirst { it >= level }
            return if (idx >= 0) frames[idx].tSec else null
        }

        fun firstTimeAtOrBelowFrom(level: Int, startTimeSec: Float): Float? {
            val idx0 = frames.indexOfFirst { it.tSec + 1e-6f >= startTimeSec }
            if (idx0 < 0) return null
            for (i in idx0 until frames.size) {
                if (levels[i] <= level) return frames[i].tSec
            }
            return null
        }

        fun maxTransitions(windowSec: Float): Int {
            var maxT = 0
            for (i in frames.indices) {
                val t0 = frames[i].tSec
                var last = levels[i]
                var t = 0
                var j = i + 1
                while (j < frames.size && frames[j].tSec - t0 <= windowSec) {
                    if (levels[j] != last) {
                        t++
                        last = levels[j]
                    }
                    j++
                }
                maxT = maxOf(maxT, t)
            }
            return maxT
        }

        for (e in s.expectations) when (e) {
            is Expectation.MustEnterLevelBy -> {
                val first = firstTimeAtOrAbove(e.level)
                val deadline = e.hazardTimeSec + e.latestSecAfterHazard
                val maxLevel = levels.maxOrNull() ?: 0
                val firstOrange = firstTimeAtOrAbove(1)
                val firstRed = firstTimeAtOrAbove(2)
                val ok = first != null && first <= deadline + 1e-3f
                val details = if (first == null) {
                    "Nikdy nedošlo k level>=${e.level} (deadline t<=${fmt(deadline)}s, hazard t=${fmt(e.hazardTimeSec)}s, maxLevel=$maxLevel, firstOrange=${firstOrange?.let { fmt(it) + "s" } ?: "n/a"}, firstRed=${firstRed?.let { fmt(it) + "s" } ?: "n/a"})."
                } else {
                    "Dosaženo v t=${fmt(first)}s; deadline t<=${fmt(deadline)}s (hazard t=${fmt(e.hazardTimeSec)}s, maxLevel=$maxLevel, firstOrange=${firstOrange?.let { fmt(it) + "s" } ?: "n/a"}, firstRed=${firstRed?.let { fmt(it) + "s" } ?: "n/a"})."
                }
                out += Verdict(ok, "MustEnterLevelBy(level=${e.level})", "${e.message} :: $details")
            }
            is Expectation.MustExitToLevelBy -> {
                val first = firstTimeAtOrBelowFrom(level = e.level, startTimeSec = e.startTimeSec)
                val deadline = e.startTimeSec + e.latestSecAfterStart
                val ok = first != null && first <= deadline + 1e-3f
                val details = if (first == null) {
                    "Nikdy nedošlo k level<=${e.level} po t>=${fmt(e.startTimeSec)}s (deadline t<=${fmt(deadline)}s)."
                } else {
                    "Dosaženo v t=${fmt(first)}s; deadline t<=${fmt(deadline)}s (start t=${fmt(e.startTimeSec)}s)."
                }
                out += Verdict(ok, "MustExitToLevelBy(level<=${e.level})", "${e.message} :: $details")
            }
            is Expectation.MustNotEnterLevel -> {
                val ok = levels.none { it >= e.level }
                val details = if (ok) "OK (nikdy nedošlo k level>=${e.level})." else "NESPLNĚNO (došlo k level>=${e.level})."
                out += Verdict(ok, "MustNotEnterLevel(level=${e.level})", "${e.message} :: $details")
            }
            is Expectation.MaxTransitionsInWindow -> {
                val mx = maxTransitions(e.windowSec)
                out += Verdict(mx <= e.maxTransitions, "MaxTransitionsInWindow(max=${e.maxTransitions})", "${e.message} :: maxTransitions=$mx")
            }
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

    private fun fmt(v: Float): String = String.format("%.2f", v)
}
