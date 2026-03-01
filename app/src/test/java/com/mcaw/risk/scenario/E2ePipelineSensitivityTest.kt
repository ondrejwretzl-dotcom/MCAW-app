package com.mcaw.risk.scenario

import com.mcaw.ai.pipeline.DetectionTuning
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class E2ePipelineSensitivityTest {

    @Test
    fun tuningChange_shiftsE2eMetrics_forAtLeastTwoScenarios() {
        val catalog = ScenarioCatalogFactory.createE2eCatalog().scenarios.take(4)
        val defaultTuning = DetectionTuning.DEFAULT
        val changed = defaultTuning.copy(ttcInvalidHoldMs = defaultTuning.ttcInvalidHoldMs + 300L)

        var changedCount = 0
        for (s in catalog) {
            val base = E2eScenarioRunner.metrics(E2eScenarioRunner.runScenario(s, defaultTuning))
            val mod = E2eScenarioRunner.metrics(E2eScenarioRunner.runScenario(s, changed))

            val orangeShift = abs((mod.firstOrangeSec ?: -1f) - (base.firstOrangeSec ?: -1f))
            val orangeTimeShift = abs(mod.orangeTimeSec - base.orangeTimeSec)
            val riskAreaShift = abs(mod.riskArea - base.riskArea)
            if (orangeShift >= 0.2f || orangeTimeShift >= 0.2f || riskAreaShift >= 0.2f) {
                changedCount++
            }
        }

        assertTrue("Expected tuning sensitivity in at least 2 E2E scenarios, got $changedCount", changedCount >= 2)
    }
}
