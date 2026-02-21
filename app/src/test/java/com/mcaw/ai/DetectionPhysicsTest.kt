package com.mcaw.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPhysicsTest {

    @Test
    fun groundPlaneDistanceAtY_isMonotonic() {
        val dTop = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(300f, 1080, 1000f, 1.2f, 6f)!!
        val dMid = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(700f, 1080, 1000f, 1.2f, 6f)!!
        val dBottom = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(1000f, 1080, 1000f, 1.2f, 6f)!!

        assertTrue(dTop > dMid)
        assertTrue(dMid > dBottom)
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
}
