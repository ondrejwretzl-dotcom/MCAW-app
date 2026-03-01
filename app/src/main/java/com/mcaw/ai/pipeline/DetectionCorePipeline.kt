package com.mcaw.ai.pipeline

import com.mcaw.ai.DetectionPhysics
import com.mcaw.risk.RiskEngine
import kotlin.math.abs

class DetectionCorePipeline(
    private val tuning: DetectionTuning = DetectionTuning.DEFAULT
) {
    data class Input(
        val tsMs: Long,
        val distanceM: Float,
        val boxHeightPx: Float,
        val trackedPresent: Boolean,
        val bottomOccluded: Boolean,
        val occlusionConfirmed: Boolean,
        val qualityWeight: Float,
        val roiContainment: Float,
        val riderSpeedMps: Float,
        val relSignedSampleMps: Float
    )

    data class Output(
        val ttcFromHeightHeldSec: Float?,
        val ttcFromDistSec: Float,
        val ttcSec: Float,
        val relSignedEmaMps: Float,
        val approachEmaMps: Float,
        val relDerivValid: Boolean,
        val recedingImmediateActive: Boolean,
        val suppressRecedingHard: Boolean,
        val suppressSteadyGapHard: Boolean,
        val occlusionCandidate: Boolean,
        val occlusionConfirmed: Boolean,
        val fusionWeightDist: Float,
        val fusionMismatchRatio: Float,
        val fusionSanityActive: Boolean
    )

    private var lastBoxHeightPx: Float = Float.NaN
    private var lastBoxHeightTsMs: Long = -1L
    private var lastTtcHeight: Float = Float.POSITIVE_INFINITY
    private var lastTtcHeightTsMs: Long = -1L
    private var ttcEma = Float.POSITIVE_INFINITY
    private var ttcEmaValid = false
    private var lastTtcUpdateTsMs = -1L
    private var lastTtcFiniteTsMs = -1L
    private var relSignedEmaMps = 0f
    private var relSignedValid = false
    private var approachEmaMps = 0f
    private var approachEmaValid = false
    private var prevDistanceM = Float.NaN
    private var prevTsMs = -1L
    private var distSlopeEmaMps = Float.NaN
    private var distSlopeValid = false
    private var steadyMs = 0L
    private var approachMs = 0L
    private var steadySuppressActive = false
    private var reenterCooldownMs = 0L
    private var recedingStableCount = 0
    private var recedingDistanceTrendCount = 0
    private var suppressRecedingImmediateActive = false
    private val fusionTmp = FloatArray(3)

    fun reset() {
        lastBoxHeightPx = Float.NaN
        lastBoxHeightTsMs = -1L
        lastTtcHeight = Float.POSITIVE_INFINITY
        lastTtcHeightTsMs = -1L
        ttcEma = Float.POSITIVE_INFINITY
        ttcEmaValid = false
        lastTtcUpdateTsMs = -1L
        lastTtcFiniteTsMs = -1L
        relSignedEmaMps = 0f
        relSignedValid = false
        approachEmaMps = 0f
        approachEmaValid = false
        prevDistanceM = Float.NaN
        prevTsMs = -1L
        distSlopeEmaMps = Float.NaN
        distSlopeValid = false
        steadyMs = 0L
        approachMs = 0L
        steadySuppressActive = false
        reenterCooldownMs = 0L
        recedingStableCount = 0
        recedingDistanceTrendCount = 0
        suppressRecedingImmediateActive = false
    }

    fun update(
        tsMs: Long,
        distanceM: Float,
        boxHeightPx: Float,
        trackedPresent: Boolean,
        bottomOccluded: Boolean,
        occlusionConfirmed: Boolean,
        qualityWeight: Float,
        roiContainment: Float,
        riderSpeedMps: Float,
        relSignedSampleMps: Float
    ): Output = update(
        Input(
            tsMs = tsMs,
            distanceM = distanceM,
            boxHeightPx = boxHeightPx,
            trackedPresent = trackedPresent,
            bottomOccluded = bottomOccluded,
            occlusionConfirmed = occlusionConfirmed,
            qualityWeight = qualityWeight,
            roiContainment = roiContainment,
            riderSpeedMps = riderSpeedMps,
            relSignedSampleMps = relSignedSampleMps
        )
    )

    fun update(input: Input): Output {
        val dtMs = if (prevTsMs > 0L) (input.tsMs - prevTsMs).coerceAtLeast(0L) else 0L
        val dtSec = if (dtMs > 0L) dtMs / 1000f else Float.NaN

        val relSample = if (input.relSignedSampleMps.isFinite()) input.relSignedSampleMps else 0f
        relSignedEmaMps = if (!relSignedValid) relSample else relSignedEmaMps + RiskEngine.EMA_ALPHA_REL * (relSample - relSignedEmaMps)
        relSignedValid = input.trackedPresent
        val approachSample = relSignedEmaMps.coerceAtLeast(0f)
        approachEmaMps = if (!approachEmaValid) approachSample else approachEmaMps + RiskEngine.EMA_ALPHA_APP * (approachSample - approachEmaMps)
        approachEmaValid = input.trackedPresent

        val ttcFromHeightsNow = if (!input.bottomOccluded) computeTtcFromBoxHeights(input.boxHeightPx, input.tsMs) else null
        val ttcFromHeightsHeld = when {
            ttcFromHeightsNow != null -> ttcFromHeightsNow
            lastTtcHeight.isFinite() && lastTtcHeightTsMs > 0L && (input.tsMs - lastTtcHeightTsMs) <= tuning.ttcHeightHoldMs -> lastTtcHeight
            else -> null
        }

        val ttcFromDist = if (input.distanceM.isFinite() && approachEmaMps > tuning.ttcFromDistApproachGateMps) {
            (input.distanceM / approachEmaMps).coerceIn(0.05f, 120f)
        } else Float.POSITIVE_INFINITY

        val ttcRaw = DetectionPhysics.fuseTtc(
            ttcHeightSec = ttcFromHeightsHeld,
            ttcDistSec = ttcFromDist,
            distanceM = input.distanceM,
            approachMps = approachEmaMps,
            bottomOccluded = input.bottomOccluded,
            occlusionConfirmed = input.occlusionConfirmed,
            qualityWeight = input.qualityWeight,
            out3 = fusionTmp
        )
        val recedingStableForRelease = relSignedEmaMps < 0f && approachEmaMps < 0.4f && !ttcFromDist.isFinite()
        val ttc = smoothTtc(ttcRaw, input.tsMs, recedingStableForRelease)

        if (reenterCooldownMs > 0L && dtMs > 0L) reenterCooldownMs = (reenterCooldownMs - dtMs).coerceAtLeast(0L)

        val derivValid = dtSec.isFinite() && dtSec > 0f && prevDistanceM.isFinite() && input.distanceM.isFinite()
        if (derivValid) {
            val slopeSample = (input.distanceM - prevDistanceM) / dtSec
            distSlopeEmaMps = if (!distSlopeValid || !distSlopeEmaMps.isFinite()) slopeSample else distSlopeEmaMps + RiskEngine.EMA_ALPHA_REL * (slopeSample - distSlopeEmaMps)
            distSlopeValid = distSlopeEmaMps.isFinite()
        } else {
            distSlopeValid = false
            distSlopeEmaMps = Float.NaN
        }

        val approachIndication = relSignedEmaMps > tuning.relEpsOutMps || (distSlopeValid && distSlopeEmaMps < -tuning.distApproachEpsMps)
        approachMs = if (approachIndication && dtMs > 0L) approachMs + dtMs else 0L
        val steadyCandidate = distSlopeValid && abs(distSlopeEmaMps) < tuning.distSteadyEpsMps && abs(relSignedEmaMps) < tuning.relEpsInMps
        steadyMs = if (steadyCandidate && dtMs > 0L) steadyMs + dtMs else if (distSlopeValid) 0L else steadyMs

        if (!steadySuppressActive && steadyMs >= tuning.steadySuppressMs && input.riderSpeedMps >= tuning.steadySuppressRiderMinMps && reenterCooldownMs == 0L) {
            steadySuppressActive = true
        }
        if (approachMs >= tuning.unsuppressConfirmMs) {
            steadySuppressActive = false
            reenterCooldownMs = tuning.suppressReenterMs
            steadyMs = 0L
        }

        val recedingNow = relSignedEmaMps < -RiskEngine.RECEDE_EPS_MPS
        recedingStableCount = if (recedingNow) recedingStableCount + 1 else 0
        val distGrowing = derivValid && input.distanceM > prevDistanceM + 0.05f
        recedingDistanceTrendCount = if (distGrowing) recedingDistanceTrendCount + 1 else 0
        val existingRecedingStable = recedingStableCount >= RiskEngine.K_STABLE && recedingDistanceTrendCount >= RiskEngine.K_STABLE

        val recedingImmediateActive = when {
            approachEmaMps >= 0.60f -> false
            !distSlopeValid || !distSlopeEmaMps.isFinite() -> false
            suppressRecedingImmediateActive -> distSlopeEmaMps > 0.10f
            else -> distSlopeEmaMps > 0.15f
        }
        suppressRecedingImmediateActive = recedingImmediateActive
        val suppressRecedingHard = recedingImmediateActive || existingRecedingStable

        prevDistanceM = input.distanceM
        prevTsMs = input.tsMs

        return Output(
            ttcFromHeightHeldSec = ttcFromHeightsHeld,
            ttcFromDistSec = ttcFromDist,
            ttcSec = ttc,
            relSignedEmaMps = relSignedEmaMps,
            approachEmaMps = approachEmaMps,
            relDerivValid = derivValid,
            recedingImmediateActive = recedingImmediateActive,
            suppressRecedingHard = suppressRecedingHard,
            suppressSteadyGapHard = steadySuppressActive,
            occlusionCandidate = input.bottomOccluded && input.roiContainment < 0.5f,
            occlusionConfirmed = input.occlusionConfirmed,
            fusionWeightDist = fusionTmp[0],
            fusionMismatchRatio = fusionTmp[1],
            fusionSanityActive = fusionTmp[2] > 0.5f
        )
    }

    private fun computeTtcFromBoxHeights(currHPx: Float, tsMs: Long): Float? {
        if (currHPx <= 0f || !currHPx.isFinite()) {
            lastBoxHeightPx = Float.NaN
            lastBoxHeightTsMs = tsMs
            return null
        }
        if (!lastBoxHeightPx.isFinite() || lastBoxHeightTsMs <= 0L) {
            lastBoxHeightPx = currHPx
            lastBoxHeightTsMs = tsMs
            return null
        }
        val dtSec = ((tsMs - lastBoxHeightTsMs).coerceAtLeast(1L)).toFloat() / 1000f
        if (dtSec > 1.0f) {
            lastBoxHeightPx = currHPx
            lastBoxHeightTsMs = tsMs
            return null
        }
        val ttc = DetectionPhysics.computeTtcFromHeights(
            prevH = lastBoxHeightPx,
            currH = currHPx,
            dtSec = dtSec,
            minDtSec = 0.05f,
            minGrowthRatio = tuning.minGrowthRatio,
            minDeltaHPx = tuning.minDeltaHPx,
            maxTtcSec = 120f
        )
        lastBoxHeightPx = currHPx
        lastBoxHeightTsMs = tsMs
        if (ttc != null && ttc.isFinite()) {
            lastTtcHeight = ttc
            lastTtcHeightTsMs = tsMs
        }
        return ttc
    }

    private fun smoothTtc(ttcRaw: Float, tsMs: Long, recedingStable: Boolean): Float {
        val raw = if (ttcRaw.isFinite() && ttcRaw > 0f) ttcRaw.coerceIn(0.05f, 120f) else Float.POSITIVE_INFINITY
        if (!raw.isFinite()) {
            val holdMs = if (recedingStable) 300L else tuning.ttcInvalidHoldMs
            if (ttcEmaValid && ttcEma.isFinite() && lastTtcFiniteTsMs > 0L && (tsMs - lastTtcFiniteTsMs) <= holdMs) return ttcEma
            ttcEmaValid = false
            ttcEma = Float.POSITIVE_INFINITY
            lastTtcUpdateTsMs = tsMs
            return Float.POSITIVE_INFINITY
        }
        lastTtcFiniteTsMs = tsMs
        if (!ttcEmaValid || !ttcEma.isFinite() || lastTtcUpdateTsMs <= 0L) {
            ttcEma = raw
            ttcEmaValid = true
            lastTtcUpdateTsMs = tsMs
            return ttcEma
        }
        val dtSec = ((tsMs - lastTtcUpdateTsMs).coerceAtLeast(1L)).toFloat() / 1000f
        lastTtcUpdateTsMs = tsMs
        val maxDrop = tuning.maxDropRate * dtSec
        val maxRise = tuning.maxRiseRate * dtSec
        val prev = ttcEma
        val clamped = if (raw < prev) raw.coerceAtLeast(prev - maxDrop) else raw.coerceAtMost(prev + maxRise)
        val alpha = if (clamped < prev) tuning.alphaDrop else tuning.alphaRise
        ttcEma = prev + alpha * (clamped - prev)
        return ttcEma
    }
}

data class DetectionTuning(
    val ttcInvalidHoldMs: Long,
    val ttcHeightHoldMs: Long,
    val maxDropRate: Float,
    val maxRiseRate: Float,
    val alphaDrop: Float,
    val alphaRise: Float,
    val minGrowthRatio: Float,
    val minDeltaHPx: Float,
    val ttcFromDistApproachGateMps: Float,
    val relEpsInMps: Float = 0.35f,
    val relEpsOutMps: Float = 0.55f,
    val distSteadyEpsMps: Float = 0.20f,
    val distApproachEpsMps: Float = 0.25f,
    val steadySuppressMs: Long = 1200L,
    val steadySuppressRiderMinMps: Float = 3.0f,
    val unsuppressConfirmMs: Long = 300L,
    val suppressReenterMs: Long = 400L
) {
    companion object {
        val DEFAULT = DetectionTuning(
            ttcInvalidHoldMs = 400L,
            ttcHeightHoldMs = 500L,
            maxDropRate = 12f,
            maxRiseRate = 3f,
            alphaDrop = 0.65f,
            alphaRise = 0.20f,
            minGrowthRatio = 1.01f,
            minDeltaHPx = 0.7f,
            ttcFromDistApproachGateMps = 0.20f
        )
    }
}
