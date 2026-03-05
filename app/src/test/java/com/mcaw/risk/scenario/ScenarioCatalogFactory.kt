package com.mcaw.risk.scenario

import kotlin.math.max
import kotlin.math.pow

/**
 * Curated scenario catalog based on the CURRENT RiskEngine logic.
 *
 * These scenarios are designed to be:
 * - representative (city/tunnel/highway/rural + moto)
 * - deterministic
 * - auditable via generated reports
 */
object ScenarioCatalogFactory {

    /*
     Scope contract:
     | Suite | Purpose |
     | ENGINEONLY (RiskEngine-only) | RiskEngine weights/thresholds/hysteresis on processed inputs |
     | E2E (core pipeline + engine) | TTC holds/smoothing/approach gate/suppress/occlusion driven behavior |
     */

    const val CATALOG_VERSION = "2026-02-28"

    private fun doc(
        purpose: String,
        riskIfBroken: String,
        alertLevelMax: Int,
        expectedState: String,
        constraintWindowSec: Float? = null,
        allowedTransitions: String = "—",
        regressionType: RegressionType = RegressionType.STABILITY,
        severity: Severity = Severity.MED,
        criticalParams: List<String> = emptyList(),
        notes: String = ""
    ): ScenarioDoc {
        return ScenarioDoc(
            purpose = purpose.trim(),
            riskIfBroken = riskIfBroken.trim(),
            expected = ExpectedBehaviorDoc(
                expectedAlertLevelMax = alertLevelMax,
                expectedRiskState = expectedState,
                constraintWindow = buildConstraintWindow(constraintWindowSec, allowedTransitions)
            ),
            regressionType = regressionType,
            severity = severity
        )
    }



    private fun buildConstraintWindow(constraintWindowSec: Float?, allowedTransitions: String): String {
        val windowPart = constraintWindowSec?.let { "okno ${"%.1f".format(it)}s" } ?: "bez explicitního časového okna"
        val transitionsPart = allowedTransitions.takeIf { it.isNotBlank() && it != "—" }
        return if (transitionsPart != null) "$windowPart; $transitionsPart" else windowPart
    }

    fun createDefaultCatalog(): ScenarioCatalogEngineOnly = createEngineOnlyCatalog()

    fun createEngineOnlyCatalog(): ScenarioCatalogEngineOnly {
        val full = createFullCatalog()
        val excluded = setOf(
            "R1_V1_TTC_INVALID_CLOSING_CONTINUES",
            "R2_V2_FOLLOW_STABLE_ORANGE"
        )
        return full.copy(
            title = "MCAW 2.0 – EngineOnly katalog simulací",
            scenarios = full.scenarios.filterNot { it.id in excluded }.map { it }
        )
    }

    fun createE2eCatalog(): ScenarioCatalogE2e {
        val e2eScenarios = listOf(
            e2eSensitivityHoldInvalidWindow(),
            e2eR1TtcInvalidClosingContinues(),
            e2eR2FollowStableOrange(),
            e2eC3RecedingHardSuppress(),
            e2eTtcHeightInvalidWindowDuringClosing(),
            e2eRecedingWarmupNoBlink()
        )
        return ScenarioCatalogE2e(
            title = "MCAW 2.0 – E2E katalog simulací",
            version = CATALOG_VERSION,
            scenarios = e2eScenarios
        )
    }

    /**
     * Dedicated sensitivity scenario:
     * - ttcFromDist is disabled by keeping approach < ttcFromDistApproachGateMps (slow closing)
     * - TTC comes from boxHeight growth, then becomes invalid for a short window
     * This makes tuning.ttcInvalidHoldMs observable.
     *
     * Not a product regression gate; used only by E2ePipelineSensitivityTest.
     */
    private fun e2eSensitivityHoldInvalidWindow(): E2eScenario {
        val hz = 10
        fun expHeight(t: Float): Float {
            // ~4% growth per frame => TTC ~ 0.1 / ln(1.04) ≈ 2.55s
            val frames = (t * hz).coerceAtLeast(0f)
            return (80f * (1.04f).pow(frames)).coerceAtMost(240f)
        }
        return E2eScenario(
            id = "E2E_SENS_HOLD_INVALID_TTC_WINDOW",
            title = "E2E sensitivity: ttcInvalidHoldMs observable",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Citlivostní scénář pro viditelné chování ttcInvalidHoldMs (není produktová regrese).",
                riskIfBroken = "Pokud se bridge invalid TTC rozbije, engine může zbytečně blikat nebo naopak zpozdit varování.",
                alertLevelMax = 2,
                expectedState = "—",
                regressionType = RegressionType.STABILITY,
                severity = Severity.LOW,
                criticalParams = listOf(
                    "ema.alphaApproach",
                    "ema.alphaRel",
                    "risk.orangeOn",
                    "risk.redOn"
                ),
                notes = "Pouze pro E2ePipelineSensitivityTest. Pomalé přibližování vypíná ttcFromDist gate; okno invalid TTC se musí držet pomocí hold/bridge." 
            ),
            config = ScenarioConfig(effectiveMode = 1, hz = hz, riderSpeedMps = 10f, qualityWeight = 0.95f),
            expectations = emptyList(),
            segments = listOf(
                E2eSegment(
                    0f,
                    2.0f,
                    "height-ttc",
                    distM = { t -> 12.5f - 0.10f * t },
                    boxHeightPx = { t -> expHeight(t) },
                    bottomOccluded = { false },
                    occlusionConfirmed = { false }
                ),
                E2eSegment(
                    2.0f,
                    4.0f,
                    "ttc-invalid",
                    distM = { t -> 12.3f - 0.10f * (t - 2.0f) },
                    boxHeightPx = { _ -> 0f },
                    bottomOccluded = { true },
                    occlusionConfirmed = { true }
                ),
                E2eSegment(
                    4.0f,
                    6.0f,
                    "recover",
                    distM = { t -> 12.1f - 0.10f * (t - 4.0f) },
                    boxHeightPx = { t -> expHeight(t) },
                    bottomOccluded = { false },
                    occlusionConfirmed = { false }
                )
            )
        )
    }

    private fun createFullCatalog(): ScenarioCatalogEngineOnly {
        val list = ArrayList<EngineOnlyScenario>()

        list += cityParkedPassBy()
        list += cityJamApproach()
        list += v1TtcInvalidClosingContinues()
        list += v2FollowNoFlap()
        list += tunnelExposureDrop()
        list += highwaySteadyFollowing()
        list += highwaySuddenBrake()
        list += cityRecedingHardSuppress()
        list += highwaySteadyGapHardSuppress()
        list += highwaySteadyToApproachUnsuppress()
        list += ruralCurveOncomingIgnored()

        // Moto
        list += motoFollowInCurve()
        list += motoJamSuddenBrake()

        // Dynamic distance reference scenarios
        list += dynSpeedDecelMaintainsStability()
        list += dynSpeedAccelBringsEarlierWarning()

        return ScenarioCatalogEngineOnly(
            title = "MCAW 2.0 – Katalog simulací scénářů",
            version = CATALOG_VERSION,
            scenarios = list
        )
    }

    private fun cityParkedPassBy(): EngineOnlyScenario {
        // Goal: parked vehicles at edge ROI must NOT cause alert.
        val hazard = 4.0f
        return EngineOnlyScenario(
            id = "C1_CITY_PARKED_PASS_BY",
            title = "Město: průjezd kolem zaparkovaných aut na okraji ROI",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Anti-false-alarm: průjezd kolem zaparkovaných aut na okraji ROI (velký objekt, téměř nulové přibližování).",
                riskIfBroken = "Falešné ORANGE/RED při běžné jízdě ve městě → ztráta důvěry a vypínání systému.",
                alertLevelMax = 0,
                expectedState = "SAFE",
                constraintWindowSec = 8f,
                allowedTransitions = "Žádné (SAFE pouze)",
                regressionType = RegressionType.FalsePositive,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.ttcOrange",
                    "risk.relOrange",
                    "roi.contain.low",
                    "suppress.approach.epsMps"
                ),
                notes = "Objekt může působit blízko (velká bbox), ale rel rychlost je nízká. ROI je hraniční (váha), ne hard gate."
            ),
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
                EngineOnlySegment(
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

    private fun cityJamApproach(): EngineOnlyScenario {
        // Goal: in a fast closing jam approach, engine should reach RED.
        val hazard = 5.0f
        return EngineOnlyScenario(
            id = "C2_CITY_JAM_APPROACH",
            title = "Město: dojezd do kolony (rychlé přibližování)",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Dojezd do kolony: přibližování je vysoké a TTC spadne do kritické oblasti.",
                riskIfBroken = "False negative (pozdní nebo chybějící ORANGE/RED) → ztráta prediktivní funkce a reálné riziko kolize.",
                alertLevelMax = 2,
                expectedState = "CRITICAL",
                constraintWindowSec = 10f,
                allowedTransitions = "SAFE→CAUTION→CRITICAL (bez nadměrného blikání)",
                regressionType = RegressionType.FalseNegative,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.redOn",
                    "risk.ttcOrange",
                    "risk.ttcRed",
                    "risk.redCombo.slopeThr",
                    "risk.redCombo.strongK",
                    "risk.relRed"
                ),
                notes = "Pokud RED nenastane, musí být z reportu jasné, zda to blokuje guard/hystereze, nebo je špatně kinematika (TTC/rel)."
            ),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 14f,
                qualityWeight = 1.0f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.6f, hazardTimeSec = hazard, message = "Po začátku hazardu musí přijít ORANGE."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 3.4f, hazardTimeSec = hazard, message = "Při potvrzeném kritickém přibližování má přijít RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 4, windowSec = 5f, message = "Bez nadměrného blikání."),
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "steady follow",
                    distanceM = { _ -> 35f },
                    approachSpeedMps = { _ -> 1.0f },
                    ttcSec = { _ -> 10f },
                    ttcSlopeSecPerSec = { _ -> 0f }
                ),
                EngineOnlySegment(
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

    private fun tunnelExposureDrop(): EngineOnlyScenario {
        // Goal: quality drops shouldn't cause spikes; still should warn if kinematics are dangerous.
        val hazard = 6.0f
        return EngineOnlyScenario(
            id = "T1_TUNNEL_EXPOSURE_DROP",
            title = "Tunel: pokles kvality obrazu + pokračující přibližování",
            domain = Domain.TUNNEL,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Tunel: qualityWeight krátce padá, ale kinematika je stále nebezpečná (přibližování pokračuje).",
                riskIfBroken = "Nesmyslné cvakání/kolísání alertů při změně kvality obrazu → UX nestabilita. Nebo zpoždění varování.",
                alertLevelMax = 2,
                expectedState = "CAUTION/CRITICAL",
                constraintWindowSec = 12f,
                allowedTransitions = "Bez nadměrného blikání; ORANGE musí přijít, pokud kinematika trvá.",
                regressionType = RegressionType.Stabilita,
                severity = Severity.MED,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.orangeOff",
                    "risk.ttcOrange",
                    "risk.relOrange",
                    "risk.redOn",
                    "risk.redOff"
                ),
                notes = "Pokud se RED zpozdí, je to ok jen tehdy, když konzervativní prahy/quality to vysvětlují. Report musí ukázat trigger (riskScore vs threshold)."
            ),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 18f,
                qualityWeight = 1.0f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 2.2f, hazardTimeSec = hazard, message = "I při poklesu kvality musí přijít ORANGE, pokud je kinematika nebezpečná."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 6f, message = "Změna kvality nesmí způsobit nadměrné cvakání."),
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "before tunnel",
                    distanceM = { _ -> 45f },
                    approachSpeedMps = { _ -> 2.0f },
                    ttcSec = { _ -> 12f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    qualityWeight = { _ -> 1.0f }
                ),
                EngineOnlySegment(
                    tFromSec = hazard,
                    tToSec = 12f,
                    label = "in tunnel",
                    distanceM = { t -> max(7.2f, 45f - (t - hazard) * 6.5f) },
                    approachSpeedMps = { _ -> 7.5f },
                    ttcSec = { t -> max(0.8f, 2.8f - (t - hazard) * 0.30f) },
                    ttcSlopeSecPerSec = { _ -> -1.4f },
                    qualityWeight = { _ -> 0.70f }
                )
            )
        )
    }

    private fun highwaySteadyFollowing(): EngineOnlyScenario {
        return EngineOnlyScenario(
            id = "H1_HIGHWAY_STEADY_FOLLOW",
            title = "Dálnice: stabilní odstup (bez alertů)",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Anti-false-alarm: stabilní odstup na dálnici (rel nízké, TTC vysoké).",
                riskIfBroken = "Falešné ORANGE při stabilním následování → uživatel systém ignoruje/vypíná.",
                alertLevelMax = 0,
                expectedState = "SAFE",
                constraintWindowSec = 12f,
                allowedTransitions = "Žádné (SAFE pouze)",
                regressionType = RegressionType.FalsePositive,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.relOrange",
                    "risk.ttcOrange",
                    "risk.orangeOff"
                )
            ),
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
                EngineOnlySegment(
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

    private fun highwaySuddenBrake(): EngineOnlyScenario {
        // Goal: sudden brake ahead on highway should go ORANGE then RED.
        val hazard = 3.0f
        return EngineOnlyScenario(
            id = "H2_HIGHWAY_SUDDEN_BRAKE",
            title = "Dálnice: náhlé brzdění vozidla vpředu",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Dálnice: prudké brzdění vpředu (brake cue), rychlý pokles TTC a vysoké přibližování.",
                riskIfBroken = "False negative / pozdní varování při reálném brzdění vpředu.",
                alertLevelMax = 2,
                expectedState = "CRITICAL",
                constraintWindowSec = 8f,
                allowedTransitions = "SAFE→CAUTION→CRITICAL (bez nadměrného blikání)",
                regressionType = RegressionType.FalseNegative,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.redOn",
                    "risk.ttcRed",
                    "risk.relRed",
                    "risk.redCombo.slopeThr",
                    "risk.redCombo.strongK",
                    "risk.redCombo.midK"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 2,
                hz = 10,
                riderSpeedMps = 30f,
                qualityWeight = 0.92f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 0.8f, hazardTimeSec = hazard, message = "Při brzdění auta vpředu musí rychle přijít ORANGE."),
                Expectation.MustEnterLevelBy(level = 2, latestSecAfterHazard = 3.2f, hazardTimeSec = hazard, message = "Při kritickém přibližování + brake cue musí přijít RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 6f, message = "Bez blikání/přepínání alertů."),
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "jízda",
                    distanceM = { _ -> 60f },
                    approachSpeedMps = { _ -> 1.0f },
                    ttcSec = { _ -> 20f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    brakeCueActive = { _ -> false }
                ),
                EngineOnlySegment(
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

    private fun ruralCurveOncomingIgnored(): EngineOnlyScenario {
        // Goal: oncoming in a curve (outside ROI corridor) must not produce alerts.
        return EngineOnlyScenario(
            id = "R1_RURAL_CURVE_ONCOMING",
            title = "Okreska: zatáčka + protijedoucí mimo ROI",
            domain = Domain.RURAL,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Anti-false-alarm: protijedoucí objekt mimo náš corridor (nízké ROI containment).",
                riskIfBroken = "Falešné ORANGE v zatáčkách proti protijedoucím → UX chaos.",
                alertLevelMax = 0,
                expectedState = "SAFE",
                constraintWindowSec = 12f,
                allowedTransitions = "Žádné (SAFE pouze)",
                regressionType = RegressionType.FalsePositive,
                severity = Severity.MED,
                criticalParams = listOf(
                    "roi.contain.low",
                    "risk.orangeOn",
                    "risk.relOrange"
                )
            ),
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
                EngineOnlySegment(
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

    private fun motoFollowInCurve(): EngineOnlyScenario {
        // Goal: moto follow in a curve: lean reduces sensitivity; must remain stable but still warn when truly closing.
        val hazard = 4.0f
        return EngineOnlyScenario(
            id = "M1_MOTO_FOLLOW_CURVE",
            title = "Motorka: motorka před motorkou v zatáčce",
            domain = Domain.RURAL,
            vehicle = Vehicle.MOTO,
            doc = doc(
                purpose = "Motorka v zatáčce: náklon snižuje citlivost, ale při reálném přibližování musí přijít ORANGE.",
                riskIfBroken = "Buď falešné alarmy v zatáčkách (nestabilita), nebo naopak potlačení varování při přibližování.",
                alertLevelMax = 1,
                expectedState = "CAUTION",
                constraintWindowSec = 10f,
                allowedTransitions = "SAFE→CAUTION (bez blikání)",
                regressionType = RegressionType.Stabilita,
                severity = Severity.MED,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.orangeOff",
                    "risk.relOrange",
                    "risk.ttcOrange"
                )
            ),
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
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 2.2f, hazardTimeSec = hazard, message = "Při významném přibližování musí přijít ORANGE i při náklonu."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 8f, message = "V zatáčce bez nadměrného cvakání."),
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "stabilní jízda",
                    distanceM = { _ -> 18f },
                    approachSpeedMps = { _ -> 0.7f },
                    ttcSec = { _ -> 12f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    leanDeg = { _ -> 28f }
                ),
                EngineOnlySegment(
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

    private fun motoJamSuddenBrake(): EngineOnlyScenario {
        val hazard = 3.5f
        return EngineOnlyScenario(
            id = "M2_MOTO_JAM_SUDDEN_BRAKE",
            title = "Motorka: náhlé brzdění vpředu",
            domain = Domain.CITY,
            vehicle = Vehicle.MOTO,
            doc = doc(
                purpose = "Motorka: dojezd + náhlé brzdění vpředu. Menší target/jitter nesmí zabránit ORANGE/RED.",
                riskIfBroken = "Chybějící RED v reálném dojezdu (FN) nebo extrémní flapping (UX).",
                alertLevelMax = 2,
                expectedState = "CRITICAL",
                constraintWindowSec = 9f,
                allowedTransitions = "SAFE→CAUTION→CRITICAL (bez nadměrného blikání)",
                regressionType = RegressionType.FalseNegative,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.redOn",
                    "risk.ttcRed",
                    "risk.relRed",
                    "risk.redCombo.slopeThr"
                )
            ),
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
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "dojezd",
                    distanceM = { _ -> 30f },
                    approachSpeedMps = { _ -> 1.5f },
                    ttcSec = { _ -> 15f },
                    ttcSlopeSecPerSec = { _ -> 0f },
                    leanDeg = { _ -> 10f }
                ),
                EngineOnlySegment(
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

    private fun dynSpeedDecelMaintainsStability(): EngineOnlyScenario {
        val hazard = 4.0f
        return EngineOnlyScenario(
            id = "DYN1_DECEL_STABILITY",
            title = "Dynamická distance: zpomalování jezdce stabilizuje riziko",
            domain = Domain.CITY,
            vehicle = Vehicle.MOTO,
            doc = doc(
                purpose = "Reference pro dynamické distance prahy: po hazardu jezdec brzdí (speed klesá).",
                riskIfBroken = "Pokud se při brzdění objevuje RED nebo flapping, dynamické prahy jsou moc agresivní/unstable.",
                alertLevelMax = 1,
                expectedState = "CAUTION/SAFE",
                constraintWindowSec = 11f,
                allowedTransitions = "SAFE↔CAUTION (bez RED)",
                regressionType = RegressionType.Stabilita,
                severity = Severity.MED,
                criticalParams = listOf(
                    "risk.distDynamic",
                    "risk.distHeadwayOrangeSec",
                    "risk.distHeadwayRedSec",
                    "risk.redOn",
                    "risk.redOff"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 17f,
                dynamicDistanceEnabled = true,
                dynamicDistanceRedSec = 1.2f,
                dynamicDistanceOrangeSec = 1.8f,
                qualityWeight = 0.95f
            ),
            expectations = listOf(
                Expectation.MustNotEnterLevel(level = 2, message = "Při plynulém decelu jezdce nemá vzniknout RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 5, windowSec = 8f, message = "Bez nadměrného cvakání i při změně rychlosti.")
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "steady",
                    distanceM = { _ -> 26f },
                    approachSpeedMps = { _ -> 3.2f },
                    ttcSec = { _ -> 8.5f },
                    riderAccelMps2 = { _ -> 0f }
                ),
                EngineOnlySegment(
                    tFromSec = hazard,
                    tToSec = 11f,
                    label = "rider decel",
                    distanceM = { t -> max(9.5f, 26f - (t - hazard) * 1.9f) },
                    approachSpeedMps = { t -> max(0.4f, 3.2f - (t - hazard) * 0.45f) },
                    ttcSec = { t -> max(2.2f, 8.5f - (t - hazard) * 0.55f) },
                    ttcSlopeSecPerSec = { _ -> -0.5f },
                    riderAccelMps2 = { _ -> -1.8f },
                    egoBrakingConfidence = { _ -> 0.75f }
                )
            )
        )
    }

    private fun dynSpeedAccelBringsEarlierWarning(): EngineOnlyScenario {
        val hazard = 3.5f
        return EngineOnlyScenario(
            id = "DYN2_ACCEL_EARLIER_ORANGE",
            title = "Dynamická distance: akcelerace jezdce přinese dřívější ORANGE",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Reference pro dynamické distance: po hazardu jezdec akceleruje, ORANGE má přijít včas (dist prahy rostou).",
                riskIfBroken = "Pozdní ORANGE při akceleraci (FN), nebo naopak RED v nekritické situaci (FP).",
                alertLevelMax = 1,
                expectedState = "CAUTION",
                constraintWindowSec = 9f,
                allowedTransitions = "SAFE→CAUTION (bez RED)",
                regressionType = RegressionType.STABILITY,
                severity = Severity.MED,
                criticalParams = listOf(
                    "risk.distDynamic",
                    "risk.distHeadwayOrangeSec",
                    "risk.orangeOn",
                    "risk.ttcOrange"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 2,
                hz = 10,
                riderSpeedMps = 22f,
                dynamicDistanceEnabled = true,
                dynamicDistanceRedSec = 1.2f,
                dynamicDistanceOrangeSec = 1.8f,
                qualityWeight = 1.0f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 5.5f, hazardTimeSec = hazard, message = "Po akceleraci má ORANGE spolehlivě nastat."),
                Expectation.MustNotEnterLevel(level = 2, message = "Tento akcelerační referenční scénář nemá eskalovat do RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 6, windowSec = 8f, message = "Změna rychlosti nesmí rozbít stabilitu.")
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "before accel",
                    distanceM = { _ -> 52f },
                    approachSpeedMps = { _ -> 2.4f },
                    ttcSec = { _ -> 13f },
                    riderAccelMps2 = { _ -> 0f }
                ),
                EngineOnlySegment(
                    tFromSec = hazard,
                    tToSec = 9f,
                    label = "rider accel",
                    distanceM = { t -> max(16f, 52f - (t - hazard) * 4.3f) },
                    approachSpeedMps = { t -> minOf(8.2f, 2.8f + (t - hazard) * 0.9f) },
                    ttcSec = { t -> max(1.8f, 6.8f - (t - hazard) * 0.75f) },
                    ttcSlopeSecPerSec = { _ -> -1.1f },
                    riderAccelMps2 = { _ -> 1.1f }
                )
            )
        )
    }

    private fun v1TtcInvalidClosingContinues(): EngineOnlyScenario {
        val hazard = 4.0f
        return EngineOnlyScenario(
            id = "R1_V1_TTC_INVALID_CLOSING_CONTINUES",
            title = "Regrese: TTC invalid, ale přibližování pokračuje",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Anti-regression: TTC se během přibližování stane invalidním, ale dist/rel dál indikují hazard.",
                riskIfBroken = "Buď false negative (ORANGE nepřijde), nebo false RED při TTC=NaN.",
                alertLevelMax = 1,
                expectedState = "CAUTION",
                constraintWindowSec = 10f,
                allowedTransitions = "SAFE→CAUTION (bez RED při TTC NaN)",
                regressionType = RegressionType.Stabilita,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.orangeOff",
                    "risk.distOrange",
                    "risk.relOrange",
                    "risk.redOn"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 13.5f,
                riderSpeedConfidence = 0.92f,
                qualityWeight = 0.92f,
                roiContainment = 0.95f,
                egoOffsetN = 0.22f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 1.2f, hazardTimeSec = hazard, message = "Při pokračujícím přibližování musí přijít ORANGE i při invalid TTC."),
                Expectation.MustNotEnterLevel(level = 2, message = "Bez strongTtc (TTC NaN) RED nemá vznikat."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 4, windowSec = 5f, message = "TTC invalid režim nesmí způsobit flapping alertů.")
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "stable follow",
                    distanceM = { _ -> 33f },
                    approachSpeedMps = { _ -> 1.2f },
                    ttcSec = { _ -> 9.5f }
                ),
                EngineOnlySegment(
                    tFromSec = hazard,
                    tToSec = 10f,
                    label = "ttc-invalid closing",
                    distanceM = { t -> max(4.8f, 33f - (t - hazard) * 6.3f) },
                    approachSpeedMps = { t -> minOf(8.6f, 6.2f + (t - hazard) * 0.5f) },
                    // Simulace ztráty spolehlivého TTC; rozhodování má dál držet dist+rel.
                    ttcSec = { t -> if (t < hazard + 1.0f) 3.8f else Float.NaN },
                    ttcSlopeSecPerSec = { _ -> -1.2f },
                    roiContainment = { _ -> 0.98f },
                    egoOffsetN = { _ -> 0.20f }
                )
            )
        )
    }

    private fun v2FollowNoFlap(): EngineOnlyScenario {
        val hazard = 3.5f
        return EngineOnlyScenario(
            id = "R2_V2_FOLLOW_STABLE_ORANGE",
            title = "Regrese: V2 follow musí vstoupit do ORANGE bez flappingu",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Regrese: následování s kontrolovaným přibližováním musí dát ORANGE stabilně (bez flappingu).",
                riskIfBroken = "Pokud ORANGE flappuje, UX je nepoužitelné. Pokud ORANGE nepřijde, je to FN.",
                alertLevelMax = 1,
                expectedState = "CAUTION",
                constraintWindowSec = 10f,
                allowedTransitions = "SAFE→CAUTION (stabilně)",
                regressionType = RegressionType.Stabilita,
                severity = Severity.MED,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.orangeOff",
                    "risk.ttcOrange",
                    "risk.relOrange"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 15f,
                riderSpeedConfidence = 0.90f,
                qualityWeight = 0.95f,
                roiContainment = 0.90f,
                egoOffsetN = 0.25f
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 2.6f, hazardTimeSec = hazard, message = "Follow scénář musí vstoupit do ORANGE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 3, windowSec = 6f, message = "Follow scénář musí být stabilní bez flappingu.")
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "safe follow",
                    distanceM = { _ -> 42f },
                    approachSpeedMps = { _ -> 1.0f },
                    ttcSec = { _ -> 12f }
                ),
                EngineOnlySegment(
                    tFromSec = hazard,
                    tToSec = 10f,
                    label = "controlled close-in",
                    distanceM = { t -> max(9.5f, 42f - (t - hazard) * 4.9f) },
                    approachSpeedMps = { _ -> 5.0f },
                    ttcSec = { t -> max(2.1f, 7.0f - (t - hazard) * 0.65f) },
                    ttcSlopeSecPerSec = { _ -> -0.9f },
                    roiContainment = { _ -> 0.92f },
                    egoOffsetN = { _ -> 0.24f }
                )
            )
        )
    }

    private fun cityRecedingHardSuppress(): EngineOnlyScenario {
        val hazard = 1.0f
        return EngineOnlyScenario(
            id = "C3_RECEDING_HARD_SUPPRESS",
            title = "Město: receding target musí být hard-suppressed",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Cílená regrese: target se vzdaluje (distance roste), i když je blízko a TTC může vypadat nízké.",
                riskIfBroken = "Falešné ORANGE/RED při receding situaci (např. auto vpředu zrychlí) → zbytečné alarmy.",
                alertLevelMax = 0,
                expectedState = "SAFE",
                constraintWindowSec = 7f,
                allowedTransitions = "Žádné (SAFE pouze)",
                regressionType = RegressionType.FalsePositive,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "suppress.receding.epsMps",
                    "suppress.receding.scale",
                    "risk.orangeOn",
                    "risk.relOrange"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 1,
                hz = 10,
                riderSpeedMps = 12f,
                qualityWeight = 1.0f,
                deriveRelFromDistance = true
            ),
            expectations = listOf(
                Expectation.MustNotEnterLevel(level = 1, message = "Receding hard suppress musí držet SAFE."),
                Expectation.MustNotEnterLevel(level = 2, message = "Receding hard suppress nesmí pustit RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 1, windowSec = 6f, message = "Bez blikání při receding supresi.")
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = 7f,
                    label = "receding despite close distance",
                    distanceM = { t -> 4.2f + t * 0.85f },
                    approachSpeedMps = { _ -> 6.0f },
                    ttcSec = { _ -> 1.1f },
                    ttcSlopeSecPerSec = { _ -> 0.2f }
                )
            )
        )
    }

    private fun highwaySteadyGapHardSuppress(): EngineOnlyScenario {
        val hazard = 1.2f
        return EngineOnlyScenario(
            id = "H3_STEADY_GAP_HARD_SUPPRESS",
            title = "Dálnice: steady gap hard suppress po 1.2s stability",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Steady gap: stabilní distance + téměř nulová derivace po delší dobu ⇒ hard suppress (SAFE).",
                riskIfBroken = "Falešné ORANGE při stabilní koloně / steady follow (i ve vyšší rychlosti).",
                alertLevelMax = 0,
                expectedState = "SAFE",
                constraintWindowSec = 6f,
                allowedTransitions = "Žádné (SAFE pouze)",
                regressionType = RegressionType.FalsePositive,
                severity = Severity.HIGH,
                criticalParams = listOf(
                    "risk.orangeOn",
                    "risk.orangeOff",
                    "suppress.approach.epsMps",
                    "ema.alphaApproach"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 2,
                hz = 10,
                riderSpeedMps = 27f,
                qualityWeight = 0.95f,
                deriveRelFromDistance = true
            ),
            expectations = listOf(
                Expectation.MustNotEnterLevel(level = 1, message = "Steady gap hard suppress nesmí pustit ORANGE."),
                Expectation.MustNotEnterLevel(level = 2, message = "Steady gap hard suppress nesmí pustit RED."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 1, windowSec = 8f, message = "Bez flappingu během steady suprese.")
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = 6f,
                    label = "long steady follow",
                    distanceM = { t -> 9.0f + (if ((t * 10f).toInt() % 2 == 0) 0.005f else -0.005f) },
                    approachSpeedMps = { _ -> 0.2f },
                    ttcSec = { _ -> 20f },
                    ttcSlopeSecPerSec = { _ -> 0f }
                )
            )
        )
    }

    private fun highwaySteadyToApproachUnsuppress(): EngineOnlyScenario {
        val hazard = 2.0f
        return EngineOnlyScenario(
            id = "H4_STEADY_TO_APPROACH_UNSUPPRESS",
            title = "Dálnice: steady suppress -> approach unsuppress po 300ms",
            domain = Domain.HIGHWAY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "Regrese pro přechod: steady suppress musí být vypnut (unsuppress) po potvrzeném přibližování (>=300ms).",
                riskIfBroken = "Pokud suppress drží příliš dlouho → pozdní ORANGE (FN). Pokud se uvolní moc brzo → flapping.",
                alertLevelMax = 1,
                expectedState = "CAUTION",
                constraintWindowSec = 6f,
                allowedTransitions = "SAFE→CAUTION po potvrzení approach (bez blikání)",
                regressionType = RegressionType.Stabilita,
                severity = Severity.MED,
                criticalParams = listOf(
                    "suppress.approach.epsMps",
                    "ema.alphaApproach",
                    "risk.orangeOn",
                    "risk.orangeOff"
                )
            ),
            config = ScenarioConfig(
                effectiveMode = 2,
                hz = 10,
                riderSpeedMps = 29f,
                qualityWeight = 0.95f,
                deriveRelFromDistance = true
            ),
            expectations = listOf(
                Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 3.0f, hazardTimeSec = hazard, message = "Po unsuppress potvrzení musí nastat ORANGE."),
                Expectation.MaxTransitionsInWindow(maxTransitions = 3, windowSec = 6f, message = "Unsuppress bez blikání.")
            ),
            segments = listOf(
                EngineOnlySegment(
                    tFromSec = 0f,
                    tToSec = hazard,
                    label = "steady suppressed",
                    distanceM = { t -> 8.8f + (if ((t * 10f).toInt() % 2 == 0) 0.005f else -0.005f) },
                    approachSpeedMps = { _ -> 0.2f },
                    ttcSec = { _ -> 20f },
                    ttcSlopeSecPerSec = { _ -> 0f }
                ),
                EngineOnlySegment(
                    tFromSec = hazard,
                    tToSec = 6f,
                    label = "confirmed approach",
                    distanceM = { t -> (8.8f - (t - hazard) * 2.2f).coerceAtLeast(4.8f) },
                    approachSpeedMps = { _ -> 6.5f },
                    ttcSec = { t -> (1.5f - (t - hazard) * 0.18f).coerceAtLeast(0.9f) },
                    ttcSlopeSecPerSec = { _ -> -0.6f },
                    brakeCueActive = { t -> t >= hazard + 0.6f },
                    brakeCueStrength = { _ -> 0.8f }
                )
            )
        )
    }

    private fun e2eTtcHeightInvalidWindowDuringClosing(): E2eScenario {
        val hazard = 3.0f
        return E2eScenario(
            id = "E2E_TTC_HEIGHT_INVALID_WINDOW_DURING_CLOSING",
                        title = "E2E: TTC height invalid window during closing",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "E2E: krátké okno invalid TTC (height) během přibližování nesmí oddálit ORANGE.",
                riskIfBroken = "Falešné zpoždění ORANGE kvůli krátkému výpadku TTC z height.",
                alertLevelMax = 1,
                expectedState = "CAUTION",
                constraintWindowSec = 9f,
                allowedTransitions = "SAFE→CAUTION (bez RED bez strong TTC)",
                regressionType = RegressionType.FalseNegative,
                severity = Severity.MED,
                criticalParams = listOf(
                    "ema.alphaApproach",
                    "risk.orangeOn",
                    "risk.orangeOff"
                )
            ),
            config = ScenarioConfig(hz = 10, riderSpeedMps = 14f, qualityWeight = 0.95f),
            expectations = listOf(
                Expectation.MustEnterLevelBy(1, 1.5f, hazard, "ORANGE deadline"),
                Expectation.MustNotEnterLevel(2, "No RED without strong TTC"),
                Expectation.MaxTransitionsInWindow(3, 5f, "Stable")
            ),
            segments = listOf(
                E2eSegment(0f, hazard, "follow", distM = { 34f }, boxHeightPx = { 80f }),
                E2eSegment(
                    tFromSec = hazard,
                    tToSec = 9f,
                    label = "closing",
                    distM = { t -> max(7f, 34f - (t - hazard) * 5.4f) },
                    boxHeightPx = { t -> if (t in (hazard + 0.7f)..(hazard + 1.2f)) 0f else 80f + (t - hazard) * 15f },
                    bottomOccluded = { t -> t in (hazard + 0.7f)..(hazard + 1.2f) },
                    occlusionConfirmed = { t -> t in (hazard + 0.7f)..(hazard + 1.2f) }
                )
            )
        )
    }

    private fun e2eRecedingWarmupNoBlink(): E2eScenario {
        return E2eScenario(
            id = "E2E_RECEDING_WARMUP_NO_BLINK",
                        title = "E2E: receding warmup no blink",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = doc(
                purpose = "E2E: receding od t=0 nesmí ani krátce zablikat ORANGE (warmup).",
                riskIfBroken = "Falešné bliknutí ORANGE po startu / krátká detekce receding target.",
                alertLevelMax = 0,
                expectedState = "SAFE",
                constraintWindowSec = 7f,
                allowedTransitions = "Žádné (SAFE pouze)",
                regressionType = RegressionType.FalsePositive,
                severity = Severity.MED,
                criticalParams = listOf(
                    "suppress.receding.epsMps",
                    "suppress.receding.scale",
                    "risk.orangeOn"
                )
            ),
            config = ScenarioConfig(hz = 10, riderSpeedMps = 12f, qualityWeight = 1.0f),
            expectations = listOf(
                Expectation.MustNotEnterLevel(1, "No ORANGE blink"),
                Expectation.MaxTransitionsInWindow(1, 6f, "No transitions")
            ),
            segments = listOf(
                E2eSegment(0f, 7f, "receding", distM = { t -> 4.3f + t * 0.9f }, boxHeightPx = { t -> (170f - t * 9f).coerceAtLeast(40f) })
            )
        )
    }


    private fun e2eR1TtcInvalidClosingContinues(): E2eScenario = E2eScenario(
        id = "R1_V1_TTC_INVALID_CLOSING_CONTINUES",
        title = "E2E R1 TTC invalid during closing",
        domain = Domain.CITY,
        vehicle = Vehicle.CAR,
        doc = doc(
            purpose = "E2E: TTC invalid okno během přibližování – pipeline nesmí hazard ztratit.",
            riskIfBroken = "Pipeline při invalid TTC pustí SAFE (FN) nebo způsobí flapping.",
            alertLevelMax = 1,
            expectedState = "CAUTION",
            constraintWindowSec = 6f,
            allowedTransitions = "SAFE→CAUTION (stabilně)",
            regressionType = RegressionType.Stabilita,
            severity = Severity.MED,
            criticalParams = listOf(
                "risk.orangeOn",
                "risk.orangeOff",
                "ema.alphaApproach"
            )
        ),
        config = ScenarioConfig(effectiveMode = 1, hz = 10, riderSpeedMps = 14f, qualityWeight = 1.0f),
        expectations = listOf(Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 2.5f, hazardTimeSec = 2f, message = "Closing should continue through TTC invalid windows.")),
        segments = listOf(
            E2eSegment(0f, 2f, "approach", distM = { t -> 32f - 3.2f * t }, boxHeightPx = { t -> 80f + 8f * t }),
            E2eSegment(2f, 6f, "ttc invalid", distM = { t -> 25.6f - 4.0f * (t - 2f) }, boxHeightPx = { _ -> 0f }, bottomOccluded = { true }, occlusionConfirmed = { true })
        )
    )

    private fun e2eR2FollowStableOrange(): E2eScenario = E2eScenario(
        id = "R2_V2_FOLLOW_STABLE_ORANGE",
        title = "E2E stable following orange",
        domain = Domain.HIGHWAY,
        vehicle = Vehicle.CAR,
        doc = doc(
            purpose = "E2E: follow s persistent approach musí dosáhnout ORANGE (stabilně).",
            riskIfBroken = "E2E pipeline/hold/deriv rozbije follow a ORANGE nepřijde nebo flappuje.",
            alertLevelMax = 1,
            expectedState = "CAUTION",
            constraintWindowSec = 10f,
            allowedTransitions = "SAFE→CAUTION (stabilně)",
            regressionType = RegressionType.Stabilita,
            severity = Severity.LOW,
            criticalParams = listOf(
                "risk.orangeOn",
                "risk.orangeOff"
            )
        ),
        config = ScenarioConfig(effectiveMode = 2, hz = 10, riderSpeedMps = 24f, qualityWeight = 0.95f),
        expectations = listOf(Expectation.MustEnterLevelBy(level = 1, latestSecAfterHazard = 3.0f, hazardTimeSec = 2f, message = "Should reach ORANGE.")),
        segments = listOf(E2eSegment(0f, 10f, "follow", distM = { t -> 34f - 2.8f * t }, boxHeightPx = { t -> 65f + 6.5f * t }))
    )

    private fun e2eC3RecedingHardSuppress(): E2eScenario = E2eScenario(
        id = "C3_RECEDING_HARD_SUPPRESS",
        title = "E2E receding must suppress immediately",
        domain = Domain.CITY,
        vehicle = Vehicle.CAR,
        doc = doc(
            purpose = "E2E: receding hard suppress od frame 0 (žádné ORANGE blink).",
            riskIfBroken = "Falešné ORANGE u receding targetů (FP).",
            alertLevelMax = 0,
            expectedState = "SAFE",
            constraintWindowSec = 7f,
            allowedTransitions = "Žádné (SAFE pouze)",
            regressionType = RegressionType.FalsePositive,
            severity = Severity.MED,
            criticalParams = listOf(
                "suppress.receding.epsMps",
                "suppress.receding.scale",
                "risk.orangeOn"
            )
        ),
        config = ScenarioConfig(effectiveMode = 1, hz = 10, riderSpeedMps = 10f, qualityWeight = 1.0f),
        expectations = listOf(Expectation.MustNotEnterLevel(level = 1, message = "Receding should be hard-suppressed immediately.")),
        segments = listOf(E2eSegment(0f, 7f, "receding", distM = { t -> 4.0f + 1.1f * t }, boxHeightPx = { t -> (180f - 12f * t).coerceAtLeast(30f) }))
    )
}
