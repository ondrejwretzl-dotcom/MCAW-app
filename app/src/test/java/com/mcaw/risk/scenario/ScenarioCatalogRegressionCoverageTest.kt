package com.mcaw.risk.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioCatalogRegressionCoverageTest {

    @Test
    fun defaultCatalog_containsNewRegressionScenarios_andUniqueIds() {
        val catalog = ScenarioCatalogFactory.createDefaultCatalog()
        val ids = catalog.scenarios.map { it.id }

        assertTrue(ids.contains("R1_V1_TTC_INVALID_CLOSING_CONTINUES"))
        assertTrue(ids.contains("R2_V2_FOLLOW_STABLE_ORANGE"))
        assertTrue(ids.contains("C3_RECEDING_HARD_SUPPRESS"))
        assertTrue(ids.contains("H3_STEADY_GAP_HARD_SUPPRESS"))
        assertTrue(ids.contains("H4_STEADY_TO_APPROACH_UNSUPPRESS"))

        assertEquals("Scenario IDs must stay unique", ids.toSet().size, ids.size)
    }
}
