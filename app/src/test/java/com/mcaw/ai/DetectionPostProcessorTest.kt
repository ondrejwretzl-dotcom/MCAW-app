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
        assertEquals(0, res.accepted.size)
        assertTrue(res.rejected.any { it.reason == "airborneBig" })
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
        assertEquals(0, res.accepted.size)
        assertTrue(res.rejected.any { it.reason == "fullWidthHard" })
    }
}
