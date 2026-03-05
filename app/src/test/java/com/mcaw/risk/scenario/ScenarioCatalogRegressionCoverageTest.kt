package com.mcaw.risk.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioCatalogRegressionCoverageTest {

    @Test
    fun suiteMembership_andUniqueIds() {
        val engineOnly = ScenarioCatalogFactory.createEngineOnlyCatalog()
        val e2e = ScenarioCatalogFactory.createE2eCatalog()

        val engineIds = engineOnly.scenarios.map { it.id }
        val e2eIds = e2e.scenarios.map { it.id }

        assertFalse(
            "EngineOnly catalog must exclude R1_V1_TTC_INVALID_CLOSING_CONTINUES (belongs to E2E suite).",
            engineIds.contains("R1_V1_TTC_INVALID_CLOSING_CONTINUES")
        )
        assertFalse(
            "EngineOnly catalog must exclude R2_V2_FOLLOW_STABLE_ORANGE (belongs to E2E suite).",
            engineIds.contains("R2_V2_FOLLOW_STABLE_ORANGE")
        )

        assertTrue(
            "E2E catalog must include R1_V1_TTC_INVALID_CLOSING_CONTINUES.",
            e2eIds.contains("R1_V1_TTC_INVALID_CLOSING_CONTINUES")
        )
        assertTrue(
            "E2E catalog must include R2_V2_FOLLOW_STABLE_ORANGE.",
            e2eIds.contains("R2_V2_FOLLOW_STABLE_ORANGE")
        )
        assertTrue(
            "E2E catalog must include E2E_TTC_HEIGHT_INVALID_WINDOW_DURING_CLOSING.",
            e2eIds.contains("E2E_TTC_HEIGHT_INVALID_WINDOW_DURING_CLOSING")
        )
        assertTrue(
            "E2E catalog must include E2E_RECEDING_WARMUP_NO_BLINK.",
            e2eIds.contains("E2E_RECEDING_WARMUP_NO_BLINK")
        )

        assertEquals(
            "EngineOnly scenario IDs must be unique.",
            engineIds.toSet().size,
            engineIds.size
        )
        assertEquals(
            "E2E scenario IDs must be unique.",
            e2eIds.toSet().size,
            e2eIds.size
        )
    }
}
