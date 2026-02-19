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
            title = "MCAW 2.0 – Katalog simulací scénářů",
            version = CATALOG_VERSION,
            scenarios = list
        )
    }

    private fun cityParkedPassBy(): Scenario {
        // Goal: parked vehicles at edge ROI must NOT cause alert.
        val hazard = 4.0f
        return Scenario(
            id = "C1_CITY_PARKED_PASS_BY",
            title = "Město: průjezd kolem zaparkovaných aut na okraji ROI",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            notes = """
                Jízda kolem řady zaparkovaných aut na okraji ROI.
                Objekt může vypadat velký, ale má téměř nulové přibližování.

                Očekávání: žádné ORANGE/RED (kritický anti-false-alarm scénář pro důvěru uživatele).
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
                Expectation.MustNotEnterLevel(level = 1, message = "Zaparkovaná auta na okraji ROI nesmí spustit ORANGE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 2, windowSec = 5f, message = "Žádné blikání/přepínání alertů."),
                Expectation.MustNotAlertWhenTtcInvalidAndRelLow(relMpsMax = 0.8f, message = "Invalid TTC + nízké přibližování nesmí varovat.")
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
            title = "Město: dojezd do kolony (rychlé přibližování)",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            notes = """
                Dojezd do tvořící se kolony. Přibližování zůstává vysoké a TTC klesá pod ttcRed.

                Očekávání: nejdřív ORANGE, poté stabilní RED (dle prahů aktuálního módu + RED combo guard).
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 14f,
                qualityWeight = 1.0f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.0f, hazardTimeSec = hazard, message = "Po začátku hazardu musí rychle přijít ORANGE."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 2.0f, hazardTimeSec = hazard, message = "Při potvrzeném kritickém přibližování musí přijít RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 4, windowSec = 5f, message = "Bez nadměrného blikání."),
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
            title = "Tunel: pokles kvality obrazu + pokračující přibližování",
            domain = Domain.TUNNEL,
            vehicle = Vehicle.CAR,
            notes = """
                Vjezd do tunelu: qualityWeight rychle klesne, poté se částečně obnoví. Přibližování pokračuje.

                Očekávání: varování je stabilní (bez cvakání). RED se může zpozdit kvůli konzervativním prahům, ale musí nastat, pokud kritické přibližování trvá.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 18f,
                qualityWeight = 1.0f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.5f, hazardTimeSec = hazard, message = "I při poklesu kvality musí přijít ORANGE, pokud je kinematika nebezpečná."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 3.5f, hazardTimeSec = hazard, message = "Pokud kritické přibližování trvá, musí nastat RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 6f, message = "Změna kvality nesmí způsobit nadměrné cvakání."),
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
            title = "Dálnice: stabilní odstup (bez alertů)",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            notes = """
                Jízda po dálnici se stabilním odstupem a nízkým přibližováním.
                Scénář brání falešným alarmům a hlídá stabilitu.
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 2, // sport/highway
                hz = 10,
                riderSpeedMps = 27f,
                qualityWeight = 0.90f
            ),
            expectations = listOf(
                Expectation.MustNotEnterLevel(level = 1, message = "Stabilní odstup musí zůstat SAFE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 2, windowSec = 8f, message = "Bez náhodných přechodů."),
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
            title = "Dálnice: náhlé brzdění vozidla vpředu",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            notes = """
                Situace na dálnici, kdy vozidlo vpředu prudce brzdí.
                Modelujeme ji rychlým poklesem TTC a vysokým přibližováním. BrakeCue je aktivní.

                Očekávání: ORANGE rychle, poté RED po potvrzení (combo guard splněn).
            """.trimIndent(),
            config = ScenarioConfig(
                effectiveMode = 2,
                hz = 10,
                riderSpeedMps = 30f,
                qualityWeight = 0.92f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 0.8f, hazardTimeSec = hazard, message = "Při brzdění auta vpředu musí rychle přijít ORANGE."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 1.8f, hazardTimeSec = hazard, message = "Při kritickém přibližování + brake cue musí přijít RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 6f, message = "Bez blikání/přepínání alertů."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "jízda",
                    distanceM = { _ -> 60f },
                    approachSpeedMps = { _ -> 1.0f },
                    ttcSec = { _ -> 20f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    brakeCueActive = { _ -> false }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 8f,
                    label = "brzdění vpředu",
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
            title = "Okreska: zatáčka + protijedoucí mimo ROI",
            domain = Domain.RURAL,
            vehicle = Vehicle.CAR,
            notes = """
                Zatáčka na okresce. Protijedoucí vozidlo se objeví mimo střed / nízké ROI containment.
                Relativní přibližování není relevantní pro kolizi v našem pruhu.
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
                Expectation.MustNotEnterLevel(level = 1, message = "Protijedoucí mimo ROI nesmí spustit ORANGE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 2, windowSec = 10f, message = "Bez náhodných přechodů."),
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
            title = "Motorka: motorka před motorkou v zatáčce",
            domain = Domain.RURAL,
            vehicle = Vehicle.MOTO,
            notes = """
                Motorkář jede za motorkou při náklonu v zatáčce.
                Může vznikat jitter detekce a posun ROI; engine snižuje citlivost při velkém náklonu.

                Očekávání: bez cvakání; ORANGE ve chvíli, kdy je přibližování skutečně významné.
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
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.2f, hazardTimeSec = hazard, message = "Při významném přibližování musí přijít ORANGE i při náklonu."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 8f, message = "V zatáčce bez nadměrného cvakání."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "stabilní jízda",
                    distanceM = { _ -> 18f },
                    approachSpeedMps = { _ -> 0.7f },
                    ttcSec = { _ -> 12f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    leanDeg = { _ -> 28f }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 10f,
                    label = "přibližování v zatáčce",
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
            title = "Motorka: náhlé brzdění vpředu",
            domain = Domain.CITY,
            vehicle = Vehicle.MOTO,
            notes = """
                Motorkář dojíždí pomalé/stojící vozidlo s náhlým brzděním vpředu.
                Menší target + jitter nesmí zabránit kritickému varování.
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
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 0.9f, hazardTimeSec = hazard, message = "Musí rychle přijít ORANGE."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 2.2f, hazardTimeSec = hazard, message = "Při potvrzeném kritickém přibližování musí přijít RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 6, windowSec = 8f, message = "Bez nadměrného cvakání."),
            ),
            segments = listOf(
                Segment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "dojezd",
                    distanceM = { _ -> 30f },
                    approachSpeedMps = { _ -> 1.5f },
                    ttcSec = { _ -> 15f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    leanDeg = { _ -> 10f }
                ),
                Segment(
                    tFromSec = hazard,
                    tToSec = 9f,
                    label = "kritické přibližování",
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
