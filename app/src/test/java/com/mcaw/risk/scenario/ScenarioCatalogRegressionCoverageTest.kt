package com.mcaw.risk.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioCatalogRegressionCoverageTest {

    @Test
    fun defaultCatalog_containsNewRegressionScenarios_andUniqueIds() {
        val catalog = ScenarioCatalogFactory.createDefaultCatalog()
        val ids = catalog.scenarios.map { it.id }

        assertTrue(ids.contains("R1_V1_WHITE_VAN_BOTTOM_OCCLUSION"))
        assertTrue(ids.contains("R2_V2_FOLLOW_STABLE_ORANGE"))

        assertEquals("Scenario IDs must stay unique", ids.toSet().size, ids.size)
    }
}
