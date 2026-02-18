package com.mcaw.risk

import com.mcaw.risk.scenario.ScenarioCatalogFactory
import com.mcaw.risk.scenario.ScenarioRunner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MCAW 2.0 – Scenario simulations with dual-use reports.
 *
 * This is NOT a typical "IT unit test" output.
 * The goal is to generate a human-readable report (MD) + a debug-parseable report (JSONL)
 * for each scenario, even when everything passes.
 */
class ScenarioSimulationReportTest {

    @Test
    fun runScenarioCatalog_andGenerateReports() {
        val catalog = ScenarioCatalogFactory.createDefaultCatalog()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val outDir = File("build/reports/mcaw_scenarios/$stamp")
        outDir.mkdirs()

        val indexMd = StringBuilder(8_000)
        indexMd.append("# MCAW 2.0 – Scenario Catalog Report\n\n")
        indexMd.append("- Catalog: **").append(catalog.title).append("**\n")
        indexMd.append("- Catalog version: ").append(catalog.version).append("\n")
        indexMd.append("- Generated: ").append(stamp).append("\n\n")
        indexMd.append("## Scenarios\n")

        var allOk = true
        for (s in catalog.scenarios) {
            val run = ScenarioRunner.runScenario(s)
            ScenarioRunner.writeReports(run, outDir)

            val pass = run.verdicts.all { it.ok }
            allOk = allOk && pass

            indexMd.append("- ")
                .append(if (pass) "✅" else "❌")
                .append(" **").append(s.id).append("** – ")
                .append(s.title)
                .append("  (domain=").append(s.domain).append(", vehicle=").append(s.vehicle).append(")\n")
            indexMd.append("  - MD: ").append(s.id).append(".md\n")
            indexMd.append("  - JSONL: ").append(s.id).append(".jsonl\n")
        }

        File(outDir, "INDEX.md").writeText(indexMd.toString())

        // Keep the build useful: fail only if a scenario violates its explicit expectations.
        // This is by design: PO-approved expectations become regression contracts.
        assertTrue(
            "One or more scenario expectations failed. See build/reports/mcaw_scenarios/$stamp/INDEX.md",
            allOk
        )
    }
}
