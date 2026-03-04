package com.mcaw.ai

import com.mcaw.model.Box
import com.mcaw.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPostProcessorTest {

    @Test
    fun airborneBig_rejected() {
        val pp = DetectionPostProcessor()
        val frameW = 1000f
        val frameH = 1000f

        // Large box with bottom edge high in the frame ("floating").
        val det = Detection(
            box = Box(100f, 50f, 900f, 420f), // bottomY=0.42
            score = 0.99f,
            label = "car"
        )

        val res = pp.process(listOf(det), frameW, frameH)
        assertEquals(1, res.trackable.size)
        assertEquals(0, res.seedable.size)
        assertTrue(res.rejected.any { it.reason == "nonSeedable:airborneBig" })
    }

    @Test
    fun fullWidthHard_rejected() {
        val pp = DetectionPostProcessor()
        val frameW = 1000f
        val frameH = 1000f

        val det = Detection(
            box = Box(0f, 200f, 990f, 700f), // widthRatio=0.99
            score = 0.99f,
            label = "car"
        )

        val res = pp.process(listOf(det), frameW, frameH)
        assertEquals(1, res.trackable.size)
        assertEquals(0, res.seedable.size)
        assertTrue(res.rejected.any { it.reason == "nonSeedable:fullWidthHard" })
    }

    @Test
    fun wideThinStripe_rejectedSeedOnly() {
        val pp = DetectionPostProcessor()
        val frameW = 1000f
        val frameH = 1000f

        // Very wide + thin horizontal stripe-like box.
        val det = Detection(
            box = Box(50f, 500f, 950f, 680f), // w=0.90, h=0.18 => aspect=5.0
            score = 0.99f,
            label = "car"
        )

        val res = pp.process(listOf(det), frameW, frameH)
        assertEquals(1, res.trackable.size)
        assertEquals(0, res.seedable.size)
        assertTrue(res.rejected.any { it.reason == "nonSeedable:wideThinStripe" })
    }

    @Test
    fun tallThinStripe_rejectedSeedOnly() {
        val pp = DetectionPostProcessor()
        val frameW = 1000f
        val frameH = 1000f

        // Very tall + thin vertical stripe-like box.
        val det = Detection(
            box = Box(450f, 50f, 650f, 950f), // w=0.20, h=0.90 => invAspect=4.5
            score = 0.99f,
            label = "car"
        )

        val res = pp.process(listOf(det), frameW, frameH)
        assertEquals(1, res.trackable.size)
        assertEquals(0, res.seedable.size)
        assertTrue(res.rejected.any { it.reason == "nonSeedable:tallThinStripe" })
    }

    @Test
    fun hugeBox_rejectedSeedOnly() {
        val pp = DetectionPostProcessor()
        val frameW = 1000f
        val frameH = 1000f

        // Near-fullscreen box.
        val det = Detection(
            box = Box(0f, 0f, 960f, 960f), // area=0.9216
            score = 0.99f,
            label = "car"
        )

        val res = pp.process(listOf(det), frameW, frameH)
        assertEquals(1, res.trackable.size)
        assertEquals(0, res.seedable.size)
        assertTrue(res.rejected.any { it.reason == "nonSeedable:hugeBox" })
    }

    @Test
    fun normalVehicleRear_isSeedable() {
        val pp = DetectionPostProcessor()
        val frameW = 1000f
        val frameH = 1000f

        // Reasonable rear-view vehicle-like box.
        val det = Detection(
            box = Box(350f, 520f, 650f, 740f), // w=0.30, h=0.22 => aspect~1.36
            score = 0.99f,
            label = "car"
        )

        val res = pp.process(listOf(det), frameW, frameH)
        assertEquals(1, res.trackable.size)
        assertEquals(1, res.seedable.size)
        assertTrue(res.seedableIndices.size == 1)
    }

}
