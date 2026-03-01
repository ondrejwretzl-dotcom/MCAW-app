package com.mcaw.risk.scenario

import com.mcaw.ai.pipeline.DetectionTuning
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class E2ePipelineSensitivityTest {

    @Test
    fun holdSensitivityTest_ttcInvalidHold_changesAtLeastOneScenario() {
        val byId = ScenarioCatalogFactory.createE2eCatalog().scenarios.associateBy { it.id }
        val scenarios = listOf(
            byId.getValue("R1_V1_TTC_INVALID_CLOSING_CONTINUES"),
            byId.getValue("E2E_TTC_HEIGHT_INVALID_WINDOW_DURING_CLOSING")
        )
        val changed = runSensitivity(scenarios, DetectionTuning.DEFAULT.copy(ttcInvalidHoldMs = DetectionTuning.DEFAULT.ttcInvalidHoldMs + 300L))
        assertTrue("Expected >=1 changed scenario for hold sensitivity.\n$changed", changed.lines().count { it.contains("changed=true") } >= 1)
    }

    @Test
    fun dropRateSensitivityTest_maxDropRate_changesAtLeastOneScenario() {
        val byId = ScenarioCatalogFactory.createE2eCatalog().scenarios.associateBy { it.id }
        val scenarios = listOf(
            byId.getValue("R2_V2_FOLLOW_STABLE_ORANGE"),
            byId.getValue("E2E_TTC_HEIGHT_INVALID_WINDOW_DURING_CLOSING")
        )
        val changed = runSensitivity(scenarios, DetectionTuning.DEFAULT.copy(maxDropRate = DetectionTuning.DEFAULT.maxDropRate * 0.7f, alphaDrop = (DetectionTuning.DEFAULT.alphaDrop * 0.85f).coerceAtLeast(0.1f)))
        assertTrue("Expected >=1 changed scenario for drop-rate sensitivity.\n$changed", changed.lines().count { it.contains("changed=true") } >= 1)
    }

    private fun runSensitivity(scenarios: List<E2eScenario>, changedTuning: DetectionTuning): String {
        val b = StringBuilder()
        for (s in scenarios) {
            val base = E2eScenarioRunner.metrics(E2eScenarioRunner.runScenario(s, DetectionTuning.DEFAULT))
            val mod = E2eScenarioRunner.metrics(E2eScenarioRunner.runScenario(s, changedTuning))
            val firstOrangeDelta = (mod.firstOrangeSec ?: -1f) - (base.firstOrangeSec ?: -1f)
            val riskAreaDelta = mod.riskArea - base.riskArea
            val changed = abs(firstOrangeDelta) >= 0.1f || abs(riskAreaDelta) >= 0.05f
            b.append("id=${s.id} baselineFirstOrange=${base.firstOrangeSec} mutatedFirstOrange=${mod.firstOrangeSec} delta=$firstOrangeDelta baselineRiskArea=${base.riskArea} mutatedRiskArea=${mod.riskArea} riskDelta=$riskAreaDelta changed=$changed\n")
        }
        return b.toString()
    }
}
