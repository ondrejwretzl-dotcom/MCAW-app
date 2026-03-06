package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine
import java.io.File
import kotlin.math.abs

object ScenarioRunner {

    private const val TREND_STEADY = 0
    private const val TREND_APPROACH = 1
    private const val TREND_RECEDE = 2

    private const val REL_EPS_IN = 0.35f
    private const val REL_EPS_OUT = 0.55f
    private const val DIST_STEADY_EPS = 0.20f
    private const val DIST_APPROACH_EPS = 0.25f
    private const val STEADY_SUPPRESS_MS = 1200L
    private const val STEADY_SUPPRESS_RIDER_MIN_MPS = 3.0f
    private const val UNSUPPRESS_CONFIRM_MS = 300L
    private const val SUPPRESS_REENTER_MS = 400L

    private data class TrendGateState(
        var prevTsMs: Long = -1L,
        var prevDistanceM: Float = Float.NaN,
        var relSignedEmaMps: Float = 0f,
        var relDerivValid: Boolean = false,
        var distSlopeEmaMps: Float = Float.NaN,
        var distSlopeValid: Boolean = false,
        var trendState: Int = TREND_STEADY,
        var steadyMs: Long = 0L,
        var approachMs: Long = 0L,
        var steadySuppressActive: Boolean = false,
        var reenterCooldownMs: Long = 0L,
        var recedingStableCount: Int = 0,
        var recedingDistanceTrendCount: Int = 0
    )

    fun runScenario(s: EngineOnlyScenario): ScenarioRun {
        val engine = RiskEngine()
        val baseFrames = buildFrames(s)

        // derived thresholds are computed once per scenario (qualityWeight is per-frame; use scenario default)
        val dynEnabled = s.config.dynamicDistanceEnabled ?: com.mcaw.config.AppPreferences.dynamicDistanceThresholdEnabled
        val dynRedSec = s.config.dynamicDistanceRedSec ?: com.mcaw.config.AppPreferences.dynamicDistanceRedSec
        val dynOrangeSec = s.config.dynamicDistanceOrangeSec ?: com.mcaw.config.AppPreferences.dynamicDistanceOrangeSec

        val derived = engine.debugDerivedThresholds(
            s.config.effectiveMode,
            s.config.qualityWeight,
            dynamicDistanceEnabled = dynEnabled,
            dynamicDistanceOrangeSec = dynOrangeSec,
            dynamicDistanceRedSec = dynRedSec
        )

        val levels = ArrayList<Int>(baseFrames.size)
        val events = ArrayList<SimEvent>(32)
        val frameTraceEvents = ArrayList<FrameTraceEvent>(baseFrames.size)
        val frames = ArrayList<SimFrame>(baseFrames.size)

        var lastLevel = 0
        var transitions = 0
        val tg = TrendGateState()

        for (f in baseFrames) {
            val derivedRelEnabled = s.config.deriveRelFromDistance
            val trendOut = if (derivedRelEnabled) {
                updateTrendGate(tg, f)
            } else {
                val rel = f.relMpsRaw
                TrendOut(relSignedMps = rel, relDerivValid = true, suppressRecedingHard = false, suppressSteadyGapHard = false)
            }
            val relSigned = if (trendOut.relSignedMps.isFinite()) trendOut.relSignedMps else 0f
            val rel = abs(relSigned)
            frames.add(f.copy(relMpsRaw = relSigned, relDerivValid = trendOut.relDerivValid))

            // In engine-only scenarios distance is synthetic, but we still allow tests to model occlusion by down-weighting.
            val distanceConfidence = if (f.bottomOccluded) 0.35f else 1.0f
            val occlusionCloseFactor = computeOcclusionCloseFactor(
                distM = f.distM,
                distRedThr = derived.distRed,
                distOrangeThr = derived.distOrange,
                occlConfirmed = f.occlConfirmed
            )
            val occlusionCloseEligible = f.occlConfirmed && f.bottomOccluded && rel >= 0.8f && !trendOut.suppressRecedingHard && !trendOut.suppressSteadyGapHard

            val r = engine.evaluate(
                tsMs = f.tsMs,
                effectiveMode = s.config.effectiveMode,
                distanceM = f.distM,
                distanceConfidence = distanceConfidence,
                approachSpeedMps = rel,
                ttcSec = f.ttcSec,
                ttcSlopeSecPerSec = f.ttcSlope,
                roiContainment = f.roiContainment,
                egoOffsetN = f.egoOffsetN,
                cutInActive = f.cutInActive,
                brakeCueActive = f.brakeCueActive,
                brakeCueStrength = f.brakeCueStrength,
                // Mirror app behavior: during cut-in boost window the pipeline bypasses EMA risk integration.
                bypassEma = f.cutInActive,
                occlusionCloseFactor = occlusionCloseFactor,
                occlusionCloseEligible = occlusionCloseEligible,
                qualityWeight = f.qualityWeight,
                riderSpeedMps = f.riderSpeedMps,
                riderSpeedConfidence = f.riderSpeedConfidence,
                egoBrakingConfidence = f.egoBrakingConfidence,
                leanDeg = f.leanDeg,
                suppressRecedingHard = trendOut.suppressRecedingHard,
                suppressSteadyGapHard = trendOut.suppressSteadyGapHard,
                dynamicDistanceEnabled = dynEnabled,
                dynamicDistanceRedSec = dynRedSec,
                dynamicDistanceOrangeSec = dynOrangeSec
            )

            val level = r.level
            levels.add(level)

            frameTraceEvents.add(
                FrameTraceEvent(
                    tSec = f.tSec,
                    input = FrameTraceInput(
                        effectiveMode = s.config.effectiveMode,
                        distanceM = f.distM,
                        distanceConfidence = distanceConfidence,
                        approachSpeedMps = rel,
                        ttcSec = f.ttcSec,
                        ttcHeightSec = f.ttcSec,
                        ttcDistSec = f.ttcSec,
                        ttcSlopeSecPerSec = f.ttcSlope,
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
                        relDerivValid = trendOut.relDerivValid,
                        relSignedSampleMps = f.relMpsRaw,
                        relSignedEmaMps = relSigned,
                        suppressRecedingHard = trendOut.suppressRecedingHard,
                        suppressSteadyGapHard = trendOut.suppressSteadyGapHard,
                        segmentLabel = f.segLabel
                    ),
                    output = FrameTraceOutput(
                        level = r.level,
                        riskScore = r.riskScore,
                        reasonBits = r.reasonBits
                    )
                )
            )

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
                                                derived = engine.debugDerivedThresholds(
                            s.config.effectiveMode,
                            f.qualityWeight,
                            dynamicDistanceEnabled = dynEnabled,
                            dynamicDistanceOrangeSec = dynOrangeSec,
                            dynamicDistanceRedSec = dynRedSec
                        ),
                        extra = mapOf(
                            "segment" to f.segLabel,
                            "distM" to f.distM,
                            "relMps" to rel,
                            "ttcSec" to f.ttcSec,
                            "ttcSlope" to f.ttcSlope,
                            "roi" to f.roiContainment,
                            "egoOffsetN" to f.egoOffsetN,
                            "qW" to f.qualityWeight,
                            "relDerived" to derivedRelEnabled
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
                                derived = derived,
                extra = mapOf(
                    "frames" to frames.size,
                    "transitions" to transitions,
                    "durationSec" to ((frames.lastOrNull()?.tSec ?: 0f) - (frames.firstOrNull()?.tSec ?: 0f)),
                    "approachSpeedSource" to if (s.config.deriveRelFromDistance) "derived_from_distance_ema" else "segment_legacy"
                )
            )
        )

        return ScenarioRun(s, derived, frames, levels, events, frameTraceEvents, verdicts)
    }

    private fun computeOcclusionCloseFactor(
        distM: Float,
        distRedThr: Float,
        distOrangeThr: Float,
        occlConfirmed: Boolean
    ): Float {
        if (!occlConfirmed || !distM.isFinite()) return 0f
        // Map close distance into 0..1 (>=orange -> 0, <=red -> 1). Conservative and stable.
        if (distOrangeThr <= distRedThr) return 0f
        val x = (distOrangeThr - distM) / (distOrangeThr - distRedThr)
        return x.coerceIn(0f, 1f)
    }

    private data class TrendOut(
        val relSignedMps: Float,
        val relDerivValid: Boolean,
        val suppressRecedingHard: Boolean,
        val suppressSteadyGapHard: Boolean
    )

    private fun updateTrendGate(state: TrendGateState, frame: SimFrame): TrendOut {
        val prevTs = state.prevTsMs
        val dtMs = if (prevTs >= 0L) (frame.tsMs - prevTs).coerceAtLeast(0L) else 0L
        val dtSec = dtMs.toFloat() / 1000f

        val derivValid = prevTs >= 0L && dtSec > 0f && frame.distM.isFinite() && state.prevDistanceM.isFinite()

        if (state.reenterCooldownMs > 0L && dtMs > 0L) {
            state.reenterCooldownMs = (state.reenterCooldownMs - dtMs).coerceAtLeast(0L)
        }

        var relSigned = 0f
        if (derivValid) {
            val distSlopeSample = (frame.distM - state.prevDistanceM) / dtSec
            relSigned = -distSlopeSample
            state.relSignedEmaMps = if (!state.relDerivValid || !state.relSignedEmaMps.isFinite()) {
                relSigned
            } else {
                state.relSignedEmaMps + RiskEngine.EMA_ALPHA_REL * (relSigned - state.relSignedEmaMps)
            }
            val slopeSample = (frame.distM - state.prevDistanceM) / dtSec
            state.distSlopeEmaMps = if (!state.distSlopeValid || !state.distSlopeEmaMps.isFinite()) {
                slopeSample
            } else {
                state.distSlopeEmaMps + RiskEngine.EMA_ALPHA_REL * (slopeSample - state.distSlopeEmaMps)
            }
            state.distSlopeValid = state.distSlopeEmaMps.isFinite()
            state.relDerivValid = state.relSignedEmaMps.isFinite()
            relSigned = state.relSignedEmaMps
        } else {
            state.relDerivValid = false
            state.distSlopeValid = false
            state.distSlopeEmaMps = Float.NaN
            relSigned = 0f
        }

        val trendApproach = state.relDerivValid && relSigned > REL_EPS_OUT
        val trendRecede = state.relDerivValid && relSigned < -REL_EPS_OUT
        val trendSteady = state.relDerivValid && abs(relSigned) < REL_EPS_IN
        state.trendState = when (state.trendState) {
            TREND_APPROACH -> if (trendSteady) TREND_STEADY else TREND_APPROACH
            TREND_RECEDE -> if (trendSteady) TREND_STEADY else TREND_RECEDE
            else -> if (trendApproach) TREND_APPROACH else if (trendRecede) TREND_RECEDE else TREND_STEADY
        }

        val approachIndication = (state.relDerivValid && relSigned > REL_EPS_OUT) ||
            (state.distSlopeValid && state.distSlopeEmaMps < -DIST_APPROACH_EPS)
        if (approachIndication && dtMs > 0L) {
            state.approachMs += dtMs
        } else {
            state.approachMs = 0L
        }

        val steadyCandidate = state.trendState == TREND_STEADY && state.distSlopeValid &&
            abs(state.distSlopeEmaMps) < DIST_STEADY_EPS && state.relDerivValid
        if (steadyCandidate && dtMs > 0L) {
            state.steadyMs += dtMs
        } else if (state.relDerivValid && state.distSlopeValid) {
            state.steadyMs = 0L
        }

        if (!state.steadySuppressActive && state.steadyMs >= STEADY_SUPPRESS_MS &&
            frame.riderSpeedMps.isFinite() && frame.riderSpeedMps >= STEADY_SUPPRESS_RIDER_MIN_MPS &&
            state.reenterCooldownMs == 0L
        ) {
            state.steadySuppressActive = true
        }
        if (state.approachMs >= UNSUPPRESS_CONFIRM_MS) {
            state.steadySuppressActive = false
            state.reenterCooldownMs = SUPPRESS_REENTER_MS
            state.steadyMs = 0L
        }

        val recedingNow = state.relDerivValid && relSigned < -RiskEngine.RECEDE_EPS_MPS
        state.recedingStableCount = if (recedingNow) state.recedingStableCount + 1 else 0
        val distGrowing = derivValid && frame.distM > state.prevDistanceM + 0.05f
        state.recedingDistanceTrendCount = if (distGrowing) state.recedingDistanceTrendCount + 1 else 0
        val suppressRecedingHard = state.recedingStableCount >= RiskEngine.K_STABLE &&
            state.recedingDistanceTrendCount >= RiskEngine.K_STABLE

        state.prevTsMs = frame.tsMs
        state.prevDistanceM = frame.distM

        return TrendOut(
            relSignedMps = relSigned,
            relDerivValid = state.relDerivValid,
            suppressRecedingHard = suppressRecedingHard,
            suppressSteadyGapHard = state.steadySuppressActive
        )
    }

    private fun evaluateExpectations(s: EngineOnlyScenario, frames: List<SimFrame>, levels: List<Int>): List<Verdict> {
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
                    val maxLevel = levels.maxOrNull() ?: 0
                    val firstOrange = firstTimeAtOrAbove(1)
                    val firstRed = firstTimeAtOrAbove(2)
                    val ok = first != null && first <= deadline + 1e-3f
                    val details = if (first == null) {
                        "Nikdy nedošlo k level>=${e.level} (deadline t<=${fmt(deadline)}s, maxLevel=$maxLevel, firstOrange=${firstOrange?.let { fmt(it) + "s" } ?: "n/a"}, firstRed=${firstRed?.let { fmt(it) + "s" } ?: "n/a"})."
                    } else {
                        "Dosaženo v t=${fmt(first)}s; deadline t<=${fmt(deadline)}s (hazard t=${fmt(e.hazardTimeSec)}s, maxLevel=$maxLevel, firstOrange=${firstOrange?.let { fmt(it) + "s" } ?: "n/a"}, firstRed=${firstRed?.let { fmt(it) + "s" } ?: "n/a"})."
                    }
                    out.add(Verdict(ok, "MustEnterLevelBy(level=${e.level})", "${e.message} :: $details"))
                }

                is Expectation.MustNotEnterLevel -> {
                    val ok = levels.none { it >= e.level }
                    val details = if (ok) "OK (nikdy nedošlo k level>=${e.level})." else "NESPLNĚNO (došlo k level>=${e.level})."
                    out.add(Verdict(ok, "MustNotEnterLevel(level=${e.level})", "${e.message} :: $details"))
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
                    out.add(Verdict(ok, "MustExitToLevelBy(level<=${e.level})", "${e.message} :: $details"))
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
        ScenarioReportWriter.writeFrameTraceJsonl(run, File(outDir, "${run.scenario.id}.frames.jsonl"))
    }

    fun writeReports(run: ScenarioRun, outDir: File, suiteDirName: String) {
        val suiteDir = File(outDir, suiteDirName)
        suiteDir.mkdirs()
        writeReports(run, suiteDir)
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)
}
