package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScenarioReportWriterTest {

    @Test
    fun writeMarkdown_engineOnlyAndE2e_segmentsAppear() {
        val derived = RiskEngine().debugDerivedThresholds(effectiveMode = 1, qualityWeight = 1f)

        val engineScenario = EngineOnlyScenario(
            id = "TEST_ENGINE",
            title = "Engine scenario",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = ScenarioDoc(
                purpose = "test",
                riskIfBroken = "test",
                expected = ExpectedBehaviorDoc(
                    expectedAlertLevelMax = 0,
                    expectedRiskState = "SAFE",
                    constraintWindow = "nikdy"
                ),
                regressionType = RegressionType.STABILITY,
                severity = Severity.MED
            ),
            config = ScenarioConfig(),
            expectations = emptyList(),
            segments = listOf(
                EngineOnlySegment(0f, 1f, "segA", { 10f }, { 1f }, { 10f }),
                EngineOnlySegment(1f, 2f, "segB", { 9f }, { 1f }, { 9f })
            )
        )
        val e2eScenario = E2eScenario(
            id = "TEST_E2E",
            title = "E2E scenario",
            domain = Domain.CITY,
            vehicle = Vehicle.CAR,
            doc = ScenarioDoc(
                purpose = "test",
                riskIfBroken = "test",
                expected = ExpectedBehaviorDoc(
                    expectedAlertLevelMax = 0,
                    expectedRiskState = "SAFE",
                    constraintWindow = "nikdy"
                ),
                regressionType = RegressionType.STABILITY,
                severity = Severity.MED
            ),
            config = ScenarioConfig(),
            expectations = emptyList(),
            segments = listOf(
                E2eSegment(0f, 1f, "e2eSeg", distM = { 10f }, boxHeightPx = { 100f })
            )
        )

        val runEngine = ScenarioRun(engineScenario, derived, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val runE2e = ScenarioRun(e2eScenario, derived, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        val fileEngine = File.createTempFile("scenario-engine", ".md")
        val fileE2e = File.createTempFile("scenario-e2e", ".md")
        try {
            ScenarioReportWriter.writeMarkdown(runEngine, fileEngine)
            ScenarioReportWriter.writeMarkdown(runE2e, fileE2e)

            val txtEngine = fileEngine.readText()
            val txtE2e = fileE2e.readText()

            // Human-readable report structure checks (new intent of tests)
            assertTrue("Engine report must contain section 'Účel testu'.", txtEngine.contains("## 1. Účel testu"))
            assertTrue("Engine report must contain section 'Očekávání'.", txtEngine.contains("## 2. Očekávání"))
            assertTrue("Engine report must contain section 'Analýza selhání'.", txtEngine.contains("## 7. Analýza selhání"))
            assertTrue("Engine report must contain section 'Klíčové přechody alertů'.", txtEngine.contains("## 8. Klíčové přechody alertů"))

            assertTrue("Engine report should include segment segA in 'Přehled segmentů'.", txtEngine.contains("segA"))
            assertTrue("Engine report should include segment segB in 'Přehled segmentů'.", txtEngine.contains("segB"))
            assertTrue("E2E report should include segment e2eSeg in 'Přehled segmentů'.", txtE2e.contains("e2eSeg"))
        } finally {
            fileEngine.delete()
            fileE2e.delete()
        }
    }
}
