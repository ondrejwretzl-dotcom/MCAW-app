package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine
import java.io.File
import kotlin.math.abs
import kotlin.math.max

object ScenarioRunner {

    fun runScenario(s: Scenario): ScenarioRun {
        val engine = RiskEngine()
        val frames = buildFrames(s)

        // derived thresholds are computed once per scenario (qualityWeight is per-frame; use scenario default)
        val derived = engine.debugDerivedThresholds(s.config.effectiveMode, s.config.qualityWeight)

        val levels = ArrayList<Int>(frames.size)
        val events = ArrayList<SimEvent>(32)

        var lastLevel = 0
        var transitions = 0

        for (f in frames) {
            val rel = f.relMpsRaw.coerceAtLeast(0f)
            val r = engine.evaluate(
                tsMs = f.tsMs,
                effectiveMode = s.config.effectiveMode,
                distanceM = f.distM,
                approachSpeedMps = rel,
                ttcSec = f.ttcSec,
                ttcSlopeSecPerSec = f.ttcSlope,
                roiContainment = f.roiContainment,
                egoOffsetN = f.egoOffsetN,
                cutInActive = f.cutInActive,
                brakeCueActive = f.brakeCueActive,
                brakeCueStrength = f.brakeCueStrength,
                qualityWeight = f.qualityWeight,
                riderSpeedMps = f.riderSpeedMps,
                egoBrakingConfidence = f.egoBrakingConfidence,
                leanDeg = f.leanDeg
            )

            val level = r.level
            levels.add(level)
            if (level != lastLevel) {
                transitions++
                val type = if (level > lastLevel) "ALERT_ENTER" else "ALERT_EXIT"
                events.add(
                    SimEvent(
                        type = type,
                        tSec = f.tSec,
                        level = level,
                        risk = r.riskScore,
                        reasonBits = r.reasonBits,
                        reasonId = RiskEngine.reasonId(r.reasonBits),
                        allowRed = null,
                        preGuardLevel = null,
                        derived = engine.debugDerivedThresholds(s.config.effectiveMode, f.qualityWeight),
                        extra = mapOf(
                            "segment" to f.segLabel,
                            "distM" to f.distM,
                            "relMps" to rel,
                            "ttcSec" to f.ttcSec,
                            "ttcSlope" to f.ttcSlope,
                            "roi" to f.roiContainment,
                            "egoOffsetN" to f.egoOffsetN,
                            "qW" to f.qualityWeight
                        )
                    )
                )
                lastLevel = level
            }
        }

        val verdicts = evaluateExpectations(s, frames, levels)
        // add summary event
        events.add(
            SimEvent(
                type = "SUMMARY",
                tSec = frames.lastOrNull()?.tSec ?: 0f,
                level = levels.lastOrNull() ?: 0,
                risk = 0f,
                reasonBits = 0,
                reasonId = 0,
                allowRed = null,
                preGuardLevel = null,
                derived = derived,
                extra = mapOf(
                    "frames" to frames.size,
                    "transitions" to transitions,
                    "durationSec" to ((frames.lastOrNull()?.tSec ?: 0f) - (frames.firstOrNull()?.tSec ?: 0f))
                )
            )
        )

        return ScenarioRun(s, derived, frames, levels, events, verdicts)
    }

    private fun evaluateExpectations(s: Scenario, frames: List<SimFrame>, levels: List<Int>): List<Verdict> {
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
                    val ok = first != null && first <= deadline + 1e-3f
                    val details = if (first == null) {
                        "Never reached level>=${e.level} (deadline t<=${fmt(deadline)}s)."
                    } else {
                        "Reached at t=${fmt(first)}s; deadline t<=${fmt(deadline)}s (hazard t=${fmt(e.hazardTimeSec)}s)."
                    }
                    out.add(Verdict(ok, "MustEnterLevelBy(level=${e.level})", "${e.message} :: $details"))
                }

                is Expectation.MustNotEnterLevel -> {
                    val ok = levels.none { it >= e.level }
                    val details = if (ok) "OK (never reached level>=${e.level})." else "FAILED (reached level>=${e.level})."
                    out.add(Verdict(ok, "MustNotEnterLevel(level=${e.level})", "${e.message} :: $details"))
                }

                is Expectation.MaxTransitionsInWindow -> {
                    val mx = maxTransitions(e.windowSec)
                    val ok = mx <= e.maxTransitions
                    out.add(
                        Verdict(
                            ok,
                            "MaxTransitionsInWindow(max=${e.maxTransitions}, window=${fmt(e.windowSec)}s)",
                            "${e.message} :: maxTransitions=$mx"
                        )
                    )
                }

                is Expectation.MustNotAlertWhenTtcInvalidAndRelLow -> {
                    var ok = true
                    var firstBad: String? = null
                    for (i in frames.indices) {
                        val f = frames[i]
                        val invalidTtc = !f.ttcSec.isFinite() || f.ttcSec <= 0f
                        val rel = f.relMpsRaw
                        if (invalidTtc && abs(rel) <= e.relMpsMax && levels[i] > 0) {
                            ok = false
                            firstBad = "t=${fmt(f.tSec)}s level=${levels[i]} ttc=${f.ttcSec} relMps=${fmt(rel)} seg=${f.segLabel}"
                            break
                        }
                    }
                    out.add(
                        Verdict(
                            ok,
                            "MustNotAlertWhenTtcInvalidAndRelLow(rel<=${fmt(e.relMpsMax)})",
                            "${e.message} :: ${firstBad ?: "OK"}"
                        )
                    )
                }
            }
        }
        return out
    }

    fun writeReports(run: ScenarioRun, outDir: File) {
        outDir.mkdirs()
        ScenarioReportWriter.writeMarkdown(run, File(outDir, "${run.scenario.id}.md"))
        ScenarioReportWriter.writeJsonl(run, File(outDir, "${run.scenario.id}.jsonl"))
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)
}
