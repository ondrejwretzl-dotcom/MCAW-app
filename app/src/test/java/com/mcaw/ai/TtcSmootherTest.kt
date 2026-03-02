package com.mcaw.ai

import com.mcaw.config.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class TtcSmootherTest {

    @Test
    fun `alphaDown uses CITY 0_60 vs HIGH SPEED 0_75`() {
        val sCity = TtcSmoother(ttcInvalidHoldMs = 400L)
        val sHwy = TtcSmoother(ttcInvalidHoldMs = 400L)

        // Initialize EMA
        val t0 = 0L
        assertEquals(10.0f, sCity.update(10f, t0, 5f, 1f, AppPreferences.MODE_CITY), 1e-6f)
        assertEquals(10.0f, sHwy.update(10f, t0, 20f, 1f, AppPreferences.MODE_SPORT), 1e-6f)

        // TTC drops from 10 -> 8 over 1s
        val t1 = 1000L
        val city = sCity.update(8f, t1, riderSpeedMps = 5f, riderSpeedConfidence = 1f, effectiveMode = AppPreferences.MODE_CITY)
        val hwy = sHwy.update(8f, t1, riderSpeedMps = 20f, riderSpeedConfidence = 1f, effectiveMode = AppPreferences.MODE_SPORT)

        // CITY: 10 + 0.60*(8-10) = 8.8
        // HWY:  10 + 0.75*(8-10) = 8.5
        assertEquals(8.8f, city, 1e-4f)
        assertEquals(8.5f, hwy, 1e-4f)
    }

    @Test
    fun `alphaUp uses CITY 0_18 vs HIGH SPEED 0_22`() {
        val sCity = TtcSmoother(ttcInvalidHoldMs = 400L)
        val sHwy = TtcSmoother(ttcInvalidHoldMs = 400L)

        val t0 = 0L
        assertEquals(8.0f, sCity.update(8f, t0, 5f, 1f, AppPreferences.MODE_CITY), 1e-6f)
        assertEquals(8.0f, sHwy.update(8f, t0, 20f, 1f, AppPreferences.MODE_SPORT), 1e-6f)

        // TTC rises 8 -> 10 over 1s
        val t1 = 1000L
        val city = sCity.update(10f, t1, 5f, 1f, AppPreferences.MODE_CITY)
        val hwy = sHwy.update(10f, t1, 20f, 1f, AppPreferences.MODE_SPORT)

        // CITY: 8 + 0.18*(10-8) = 8.36
        // HWY:  8 + 0.22*(10-8) = 8.44
        assertEquals(8.36f, city, 1e-4f)
        assertEquals(8.44f, hwy, 1e-4f)
    }

    @Test
    fun `second sample after t0 uses smoothing instead of reinit`() {
        val smoother = TtcSmoother(ttcInvalidHoldMs = 400L)

        assertEquals(10.0f, smoother.update(10f, 0L, 5f, 1f, AppPreferences.MODE_CITY), 1e-6f)

        val next = smoother.update(8f, 1000L, 5f, 1f, AppPreferences.MODE_CITY)

        // Must use CITY alphaDown=0.60 on 10 -> 8, i.e. 8.8 (not raw 8.0 re-init).
        assertEquals(8.8f, next, 1e-4f)
    }

}
