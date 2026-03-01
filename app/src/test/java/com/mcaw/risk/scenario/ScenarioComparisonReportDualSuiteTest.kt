package com.mcaw.risk.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScenarioComparisonReportDualSuiteTest {

    @Test
    fun mergeDiffs_aggregatesBothSuites() {
        val engine = ScenarioComparisonReport.DiffResult(
            baselinePath = "engine_base",
            currentPath = "engine_cur",
            hardRegressionCount = 1,
            softRegressionCount = 2,
            improvedCount = 3,
            unchangedCount = 4,
            entries = listOf(
                ScenarioComparisonReport.DiffEntry(
                    scenarioId = "C3_RECEDING_HARD_SUPPRESS",
                    baselinePass = true,
                    currentPass = false,
                    deltaFirstOrangeSec = 0.3f,
                    deltaFirstRedSec = null,
                    deltaTransitions = 1,
                    deltaMaxTransitionsWindow = 1,
                    status = "REGRESSED_HARD",
                    reasons = listOf("test")
                )
            )
        )
        val e2e = ScenarioComparisonReport.DiffResult(
            baselinePath = "e2e_base",
            currentPath = "e2e_cur",
            hardRegressionCount = 2,
            softRegressionCount = 1,
            improvedCount = 0,
            unchangedCount = 5,
            entries = listOf(
                ScenarioComparisonReport.DiffEntry(
                    scenarioId = "R1_V1_TTC_INVALID_CLOSING_CONTINUES",
                    baselinePass = true,
                    currentPass = false,
                    deltaFirstOrangeSec = 0.4f,
                    deltaFirstRedSec = null,
                    deltaTransitions = 1,
                    deltaMaxTransitionsWindow = 1,
                    status = "REGRESSED_HARD",
                    reasons = listOf("test")
                )
            )
        )

        val merged = ScenarioComparisonReport.mergeDiffs(engine, e2e)
        assertNotNull(merged)
        merged!!

        assertEquals(3, merged.hardRegressionCount)
        assertEquals(3, merged.softRegressionCount)
        assertEquals(3, merged.improvedCount)
        assertEquals(9, merged.unchangedCount)
        assertEquals(2, merged.entries.size)
        assertEquals("ENGINE_ONLY:C3_RECEDING_HARD_SUPPRESS", merged.entries[0].scenarioId)
        assertEquals("E2E:R1_V1_TTC_INVALID_CLOSING_CONTINUES", merged.entries[1].scenarioId)
    }
}
