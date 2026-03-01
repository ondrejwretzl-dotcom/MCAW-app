package com.mcaw.risk.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioSpeedDslTest {

    @Test
    fun buildFrames_integratesRiderAcceleration_whenNoExplicitSpeedProfile() {
        val scenario = EngineOnlyScenario(
            id = "T_DSL_ACCEL",
            title = "dsl accel",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            notes = "",
            config = ScenarioConfig(hz = 10, riderSpeedMps = 10f),
            expectations = emptyList(),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = 1f,
                    label = "accel",
                    distanceM = { _ -> 20f },
                    approachSpeedMps = { _ -> 1f },
                    ttcSec = { _ -> 10f },
                    riderAccelMps2 = { _ -> 2.0f }
                )
            )
        )

        val frames = buildFrames(scenario)
        assertTrue(frames.size >= 10)
        val first = frames.first().riderSpeedMps
        val last = frames.last().riderSpeedMps
        assertTrue("Rider speed should increase under positive acceleration", last > first)
    }

    @Test
    fun buildFrames_explicitRiderSpeedProfile_hasPriorityOverAcceleration() {
        val scenario = EngineOnlyScenario(
            id = "T_DSL_EXPLICIT",
            title = "dsl explicit",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            notes = "",
            config = ScenarioConfig(hz = 10, riderSpeedMps = 10f, riderSpeedConfidence = 0.9f),
            expectations = emptyList(),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = 1f,
                    label = "explicit",
                    distanceM = { _ -> 20f },
                    approachSpeedMps = { _ -> 1f },
                    ttcSec = { _ -> 10f },
                    riderAccelMps2 = { _ -> 5.0f },
                    riderSpeedMps = { _ -> 7.0f },
                    riderSpeedConfidence = { _ -> 0.4f }
                )
            )
        )

        val frames = buildFrames(scenario)
        assertTrue(frames.isNotEmpty())
        frames.forEach {
            assertEquals(7.0f, it.riderSpeedMps, 0.001f)
            assertEquals(0.4f, it.riderSpeedConfidence, 0.001f)
        }
    }
}
