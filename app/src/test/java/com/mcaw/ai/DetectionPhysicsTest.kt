package com.mcaw.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPhysicsTest {

    @Test
    fun groundPlaneDistanceAtY_isMonotonic() {
        val dTop = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(500f, 1080, 1000f, 1.2f, 6f)
        val dMid = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(700f, 1080, 1000f, 1.2f, 6f)
        val dBottom = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(1000f, 1080, 1000f, 1.2f, 6f)

        assertNotNull(dTop)
        assertNotNull(dMid)
        assertNotNull(dBottom)
        assertTrue(dTop!! > dMid!!)
        assertTrue(dMid > dBottom!!)
    }

    @Test
    fun occlusionEpsilon_scalesWithZoomAndIsAtLeastBase() {
        val base = DetectionPhysics.computeBottomOcclusionEpsPx(frameHeightPx = 1080, zoomFactor = 1f)
        val zoomed = DetectionPhysics.computeBottomOcclusionEpsPx(frameHeightPx = 1080, zoomFactor = 1.8f)

        assertTrue(base >= 10.8f)
        assertTrue(zoomed > base)
    }

    @Test
    fun minFinite_prefersFiniteMinimum() {
        assertEquals(5f, DetectionPhysics.minFinite(5f, 8f)!!, 0.0001f)
        assertEquals(7f, DetectionPhysics.minFinite(Float.NaN, 7f)!!, 0.0001f)
        assertEquals(4f, DetectionPhysics.minFinite(4f, Float.POSITIVE_INFINITY)!!, 0.0001f)
        assertEquals(null, DetectionPhysics.minFinite(Float.NaN, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun groundPlaneDistanceAtY_canBeNullAboveHorizon() {
        val dist = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(120f, 1080, 1000f, 1.2f, 4f)
        assertEquals(null, dist)
    }



    @Test
    fun fuseTtc_sanityActivatesOnStrongMismatch() {
        val out = FloatArray(3)
        val fused = DetectionPhysics.fuseTtc(
            ttcHeightSec = 10f,
            ttcDistSec = 4f,
            distanceM = 15f,
            approachMps = 1.2f,
            bottomOccluded = false,
            occlusionConfirmed = false,
            qualityWeight = 1f,
            out3 = out
        )

        assertTrue(out[2] > 0.5f)
        assertTrue(out[0] > 0.15f)
        assertTrue(fused < 7f)
    }

    @Test
    fun fuseTtc_keepsBaseWeightInJamLikeClosing() {
        val out = FloatArray(3)
        val fused = DetectionPhysics.fuseTtc(
            ttcHeightSec = 10f,
            ttcDistSec = 2f,
            distanceM = 6f,
            approachMps = 0.2f,
            bottomOccluded = false,
            occlusionConfirmed = false,
            qualityWeight = 1f,
            out3 = out
        )

        assertTrue(out[2] < 0.5f)
        assertEquals(0.15f, out[0], 0.0001f)
        assertTrue(fused > 8f)
    }

    @Test
    fun fuseTtc_occlusionForcesSanityBias() {
        val out = FloatArray(3)
        DetectionPhysics.fuseTtc(
            ttcHeightSec = 10f,
            ttcDistSec = 8f,
            distanceM = 20f,
            approachMps = 0.5f,
            bottomOccluded = false,
            occlusionConfirmed = true,
            qualityWeight = 1f,
            out3 = out
        )

        assertTrue(out[2] > 0.5f)
        assertTrue(out[0] >= 0.25f)
    }

    @Test
    fun fuseTtc_returnsDistWhenOnlyDistFinite() {
        val out = FloatArray(3)
        val fused = DetectionPhysics.fuseTtc(null, 3f, 10f, 1f, false, false, 1f, out)
        assertEquals(3f, fused, 0.0001f)
    }

    @Test
    fun fuseTtc_returnsHeightWhenOnlyHeightFinite() {
        val out = FloatArray(3)
        val fused = DetectionPhysics.fuseTtc(5f, Float.POSITIVE_INFINITY, 10f, 1f, false, false, 1f, out)
        assertEquals(5f, fused, 0.0001f)
    }

}
