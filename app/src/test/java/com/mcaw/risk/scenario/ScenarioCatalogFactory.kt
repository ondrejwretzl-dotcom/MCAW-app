package com.mcaw.risk.scenario

import kotlin.math.max

/**
 * Curated scenario catalog based on the CURRENT RiskEngine logic.
 *
 * These scenarios are designed to be:
 * - representative (city/tunnel/highway/rural + moto)
 * - deterministic
 * - auditable via generated reports
 */
object ScenarioCatalogFactory {

    const val CATALOG_VERSION = "2026-02-18"

    fun createDefaultCatalog(): ScenarioCatalog {
        val list = ArrayList<Scenario>()

        list += cityParkedPassBy()
        list += cityJamApproach()
        list += tunnelExposureDrop()
        list += highwaySteadyFollowing()
        list += highwaySuddenBrake()
        list += ruralCurveOncomingIgnored()

        // Moto
        list += motoFollowInCurve()
        list += motoJamSuddenBrake()

        return ScenarioCatalog(
            title = "MCAW 2.0 Scenario Simulation Catalog",
            version = CATALOG_VERSION,
            scenarios = list
        )
    }

    private fun cityParkedPassBy(): Scenario {
        // Goal: parked vehicles at edge ROI must NOT cause alert.
        val hazard = 4.0f
        return Scenario(
            id = "C1_CITY_PARKED_PASS_BY",
            title = "City: parked cars pass-by at ROI edge",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            notes = """
                Rider passes a line of parked cars at the edge of ROI.
                The detected object may appear large but has near-zero closing speed.

                Expectation: No ORANGE/RED. This is a trust-critical anti-false-alarm scenario.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 11f,
                qualityWeight = 0.95f,
                roiContainment = 0.55f,
                egoOffsetN = 1.2f,
                leanDeg = Float.NaN
            ),
            expectations = listOf(
                Expectation.MustNotEnterLevel(level = 1, message = "Parked edge ROI must not trigger ORANGE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 2, windowSec = 5f, message = "No alert flapping."),
                Expectation.MustNotAlertWhenTtcInvalidAndRelLow(relMpsMax = 0.8f, message = "Invalid TTC with low closing speed must not alert.")
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = 8f,
                    label = "parked pass-by",
                    distanceM = { t -> 6.5f + 0.3f * (if ((t * 2).toInt() % 2 == 0) 1f else -1f) },
                    approachSpeedMps = { _ -> 0.2f },
                    ttcSec = { _ -> 10f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    roiContainment = { _ -> 0.55f },
                    egoOffsetN = { _ -> 1.2f }
                )
            )
        )
    }

    private fun cityJamApproach(): Scenario {
        // Goal: in a fast closing jam approach, engine should reach RED.
        val hazard = 5.0f
        return Scenario(
            id = "C2_CITY_JAM_APPROACH",
            title = "City: approach traffic jam (fast closing)",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            notes = """
                Rider approaches a forming traffic jam. Closing speed remains high and TTC drops below ttcRed.

                Expectation: ORANGE then RED (stable), derived from current mode thresholds and red combo guard.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 14f,
                qualityWeight = 1.0f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.0f, hazardTimeSec = hazard, message = "Must warn quickly with ORANGE once hazard starts."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 2.0f, hazardTimeSec = hazard, message = "Must reach RED for confirmed critical closing."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 4, windowSec = 5f, message = "No excessive blinking."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "steady follow",
                    distanceM = { _ -> 35f },
                    approachSpeedMps = { _ -> 1.0f },
                    ttcSec = { _ -> 10f },
                    ttcSlopeSecPerSec = { _ -> 0f }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 10f,
                    label = "closing",
                    distanceM = { t -> max(6.8f, 35f - (t - hazard) * 7.0f) },
                    approachSpeedMps = { _ -> 8.0f },
                    ttcSec = { t -> max(0.7f, 2.6f - (t - hazard) * 0.35f) },
                    ttcSlopeSecPerSec = { _ -> -1.8f },
                    roiContainment = { _ -> 1.0f },
                    egoOffsetN = { _ -> 0.2f }
                )
            )
        )
    }

    private fun tunnelExposureDrop(): Scenario {
        // Goal: quality drops shouldn't cause spikes; still should warn if kinematics are dangerous.
        val hazard = 6.0f
        return Scenario(
            id = "T1_TUNNEL_EXPOSURE_DROP",
            title = "Tunnel: exposure drop + continued closing",
            domain = Domain.TUNNEL,
            vehicle = Vehicle.CAR,
            notes = """
                Enter tunnel: qualityWeight decreases quickly, then recovers. Closing continues.

                Expectation: warning remains stable (no flapping), RED may be delayed by conservative thresholds but must occur if closing stays critical.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 18f,
                qualityWeight = 1.0f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.5f, hazardTimeSec = hazard, message = "ORANGE should still appear under quality drop when kinematics are dangerous."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 3.5f, hazardTimeSec = hazard, message = "RED should occur if critical closing persists."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 6f, message = "Quality change must not cause excessive blinking."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "before tunnel",
                    distanceM = { _ -> 45f },
                    approachSpeedMps = { _ -> 2.0f },
                    ttcSec = { _ -> 12f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    qualityWeight = { _ -> 1.0f }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 12f,
                    label = "in tunnel",
                    distanceM = { t -> max(7.2f, 45f - (t - hazard) * 6.5f) },
                    approachSpeedMps = { _ -> 7.5f },
                    ttcSec = { t -> max(0.8f, 2.8f - (t - hazard) * 0.30f) },
                    ttcSlopeSecPerSec = { _ -> -1.4f },
                    qualityWeight = { t ->
                        // quick drop to 0.65 then recover to 0.85
                        val x = (t - hazard)
                        when {
                            x < 1.0f -> 1.0f - 0.35f * x
                            x < 3.0f -> 0.65f
                            else -> 0.65f + 0.20f * ((x - 3.0f) / 3.0f).coerceIn(0f, 1f)
                        }
                    }
                )
            )
        )
    }

    private fun highwaySteadyFollowing(): Scenario {
        return Scenario(
            id = "H1_HIGHWAY_STEADY_FOLLOW",
            title = "Highway: steady follow (no alerts)",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            notes = """
                Highway cruise with stable distance and low closing speed.
                This scenario exists to prevent false alarms and ensure stability.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 2, // sport/highway
                hz = 10,
                riderSpeedMps = 27f,
                qualityWeight = 0.90f
            ),
            expectations = listOf(
                Expectation.MustNotEnterLevel(level = 1, message = "Steady follow must remain SAFE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 2, windowSec = 8f, message = "No spurious transitions."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = 12f,
                    label = "steady",
                    distanceM = { t -> 45f + 2f * (if ((t * 1.2f).toInt() % 2 == 0) 1f else -1f) },
                    approachSpeedMps = { _ -> 0.6f },
                    ttcSec = { _ -> 25f },
                    ttcSlopeSecPerSec = { _ -> 0f }
                )
            )
        )
    }

    private fun highwaySuddenBrake(): Scenario {
        // Goal: sudden brake ahead on highway should go ORANGE then RED.
        val hazard = 3.0f
        return Scenario(
            id = "H2_HIGHWAY_SUDDEN_BRAKE",
            title = "Highway: lead vehicle sudden braking",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            notes = """
                Highway closing event where the lead vehicle brakes hard.
                We model it by a rapid TTC drop and strong closing speed. BrakeCue is active.

                Expectation: ORANGE quickly, RED after confirmation (combo guard satisfied).
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 2,
                hz = 10,
                riderSpeedMps = 30f,
                qualityWeight = 0.92f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 0.8f, hazardTimeSec = hazard, message = "Must warn quickly when lead brakes."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 1.8f, hazardTimeSec = hazard, message = "Must reach RED for critical closing with brake cue."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 6f, message = "No alert flapping."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "cruise",
                    distanceM = { _ -> 60f },
                    approachSpeedMps = { _ -> 1.0f },
                    ttcSec = { _ -> 20f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    brakeCueActive = { _ -> false }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 8f,
                    label = "brake event",
                    distanceM = { t -> max(10.5f, 60f - (t - hazard) * 10.0f) },
                    approachSpeedMps = { _ -> 12.0f },
                    ttcSec = { t -> max(0.7f, 2.2f - (t - hazard) * 0.35f) },
                    ttcSlopeSecPerSec = { _ -> -2.2f },
                    brakeCueActive = { t -> (t - hazard) >= 0.2f },
                    brakeCueStrength = { t -> if ((t - hazard) < 0.4f) 0.7f else 1.0f },
                    egoBrakingConfidence = { t -> if ((t - hazard) >= 0.2f) 0.8f else 0f },
                    roiContainment = { _ -> 1.0f },
                    egoOffsetN = { _ -> 0.15f }
                )
            )
        )
    }

    private fun ruralCurveOncomingIgnored(): Scenario {
        // Goal: oncoming in a curve (outside ROI corridor) must not produce alerts.
        return Scenario(
            id = "R1_RURAL_CURVE_ONCOMING",
            title = "Rural: curve with oncoming vehicles outside ROI",
            domain = Domain.RURAL,
            vehicle = Vehicle.CAR,
            notes = """
                Rural road curve. An oncoming vehicle appears but is off-center / low ROI containment.
                Closing speed relative to rider is not meaningful for collision with the lead lane.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 20f,
                qualityWeight = 0.88f,
                roiContainment = 0.40f,
                egoOffsetN = 1.4f
            ),
            expectations = listOf(
                Expectation.MustNotEnterLevel(level = 1, message = "Oncoming outside ROI must not trigger ORANGE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 2, windowSec = 10f, message = "No spurious transitions."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = 12f,
                    label = "curve",
                    distanceM = { _ -> 18f },
                    approachSpeedMps = { _ -> 2.0f },
                    ttcSec = { _ -> 9f },
                    ttcSlopeSecPerSec = { _ -> -0.1f },
                    roiContainment = { _ -> 0.40f },
                    egoOffsetN = { _ -> 1.4f }
                )
            )
        )
    }

    private fun motoFollowInCurve(): Scenario {
        // Goal: moto follow in a curve: lean reduces sensitivity; must remain stable but still warn when truly closing.
        val hazard = 4.0f
        return Scenario(
            id = "M1_MOTO_FOLLOW_CURVE",
            title = "Moto: follow another motorcycle in a curve",
            domain = Domain.RURAL,
            vehicle = Vehicle.MOTO,
            notes = """
                Motorcyclist follows another motorcycle while leaning in a curve.
                Detection jitter and ROI shift may occur; engine reduces gain at high lean.

                Expectation: no flapping; ORANGE when closing becomes meaningful.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 19f,
                qualityWeight = 0.90f,
                roiContainment = 0.85f,
                egoOffsetN = 0.5f,
                leanDeg = 28f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.2f, hazardTimeSec = hazard, message = "Must warn when closing becomes significant, even under lean."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 8f, message = "No excessive blinking in curve."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "stable follow",
                    distanceM = { _ -> 18f },
                    approachSpeedMps = { _ -> 0.7f },
                    ttcSec = { _ -> 12f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    leanDeg = { _ -> 28f }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 10f,
                    label = "closing in curve",
                    distanceM = { t -> max(7.0f, 18f - (t - hazard) * 2.3f) },
                    approachSpeedMps = { _ -> 6.0f },
                    ttcSec = { t -> max(0.9f, 2.8f - (t - hazard) * 0.25f) },
                    ttcSlopeSecPerSec = { _ -> -1.2f },
                    leanDeg = { t -> if ((t - hazard) < 2f) 30f else 25f },
                    roiContainment = { t -> 0.80f + 0.05f * (if (((t - hazard) * 4).toInt() % 2 == 0) 1f else -1f) }
                )
            )
        )
    }

    private fun motoJamSuddenBrake(): Scenario {
        val hazard = 3.5f
        return Scenario(
            id = "M2_MOTO_JAM_SUDDEN_BRAKE",
            title = "Moto: sudden braking ahead",
            domain = Domain.CITY,
            vehicle = Vehicle.MOTO,
            notes = """
                Motorcyclist approaches a slow/stopped vehicle with a sudden braking event.
                Smaller target + jitter should not prevent critical warning.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 16f,
                qualityWeight = 0.88f,
                roiContainment = 0.90f,
                egoOffsetN = 0.3f,
                leanDeg = 10f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 0.9f, hazardTimeSec = hazard, message = "Must warn quickly (ORANGE)."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 2.2f, hazardTimeSec = hazard, message = "Must reach RED for confirmed critical closing."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 6, windowSec = 8f, message = "No excessive blinking."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "approach",
                    distanceM = { _ -> 30f },
                    approachSpeedMps = { _ -> 1.5f },
                    ttcSec = { _ -> 15f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    leanDeg = { _ -> 10f }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 9f,
                    label = "critical closing",
                    distanceM = { t -> max(7.5f, 30f - (t - hazard) * 5.5f) },
                    approachSpeedMps = { _ -> 9.0f },
                    ttcSec = { t -> max(0.75f, 2.6f - (t - hazard) * 0.32f) },
                    ttcSlopeSecPerSec = { _ -> -1.9f },
                    brakeCueActive = { t -> (t - hazard) >= 0.2f },
                    brakeCueStrength = { _ -> 0.9f },
                    egoBrakingConfidence = { _ -> 0.75f },
                    leanDeg = { _ -> 10f }
                )
            )
        )
    }
}
