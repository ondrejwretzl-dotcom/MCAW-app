package com.mcaw.risk

import com.mcaw.risk.scenario.ScenarioCatalogFactory
import com.mcaw.risk.scenario.ScenarioRunner
import com.mcaw.risk.scenario.ScenarioComparisonReport
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MCAW 2.0 – Scénářové simulace s dual-use výstupy.
 *
 * Cíl:
 * - Vygenerovat čitelný report pro PO (MD) a strukturovaný debug log (JSONL)
 *   pro každý scénář – i když vše projde.
 *
 * Pozn.: build se defaultně NEblokuje; fail je opt-in přes -Dmcaw.failOnScenario=true
 * až po schválení očekávání jako regresních kontraktů.
 */
class ScenarioSimulationReportTest {

    @Test
    fun runScenarioCatalog_andGenerateReports() {
        val engineCatalog = ScenarioCatalogFactory.createEngineOnlyCatalog()
        val e2eCatalog = ScenarioCatalogFactory.createE2eCatalog()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val outDir = File("build/reports/mcaw_scenarios/$stamp")
        outDir.mkdirs()

        val indexMd = StringBuilder(12_000)
        indexMd.append("# MCAW 2.0 – Přehled simulací scénářů\n\n")
        indexMd.append("- EngineOnly katalog: **").append(engineCatalog.title).append("**\n")
        indexMd.append("- E2E katalog: **").append(e2eCatalog.title).append("**\n")
        indexMd.append("- Verze katalogu: ").append(engineCatalog.version).append("\n")
        indexMd.append("- Vygenerováno: ").append(stamp).append("\n\n")

        indexMd.append("## Shrnutí\n")
        indexMd.append("| Scénář | Doména | Vozidlo | Výsledek | Proč (zkráceně) | Report |\n")
        indexMd.append("|---|---|---|---|---|---|\n")

        var allOk = true
        var passCount = 0
        var failCount = 0
        val engineRuns = ArrayList<com.mcaw.risk.scenario.ScenarioRun>(engineCatalog.scenarios.size)
        val e2eRuns = ArrayList<com.mcaw.risk.scenario.ScenarioRun>(e2eCatalog.scenarios.size)

        for (s in engineCatalog.scenarios) {
            val run = ScenarioRunner.runScenario(s)
            ScenarioRunner.writeReports(run, outDir)
            engineRuns.add(run)

            val pass = run.verdicts.all { it.ok }
            allOk = allOk && pass
            if (pass) passCount++ else failCount++

            val shortWhy = run.verdicts.firstOrNull { !it.ok }?.details?.let { shorten(it) } ?: "—"

            indexMd.append("|")
                .append("**").append(s.id).append("**").append("|")
                .append(s.domain).append("|")
                .append(s.vehicle).append("|")
                .append(if (pass) "✅ PROŠEL" else "❌ NEPROŠEL").append("|")
                .append(shortWhy.replace("|", "/")).append("|")
                .append("[").append(s.id).append(".md](").append(s.id).append(".md)").append("|")
                .append("\n")
        }

        indexMd.append("\n")
        indexMd.append("**Souhrn:** ").append(passCount).append(" prošlo, ").append(failCount).append(" neprošlo.\n\n")
        indexMd.append("## Poznámky\n")
        indexMd.append("- Detail každého scénáře je v příslušném *.md souboru.\n")
        indexMd.append("- Pro ladění je ke každému scénáři i *.jsonl (strukturované eventy).\n")
        indexMd.append("- Cesta k reportům (lokálně): app/build/reports/mcaw_scenarios/").append(stamp).append("/\n")

        File(outDir, "INDEX.md").writeText(indexMd.toString())

        for (s in e2eCatalog.scenarios) {
            val run = com.mcaw.risk.scenario.E2eScenarioRunner.runScenario(s)
            ScenarioRunner.writeReports(run, outDir)
            e2eRuns.add(run)

            val pass = run.verdicts.all { it.ok }
            allOk = allOk && pass
        }

        val engineSummary = ScenarioComparisonReport.summarizeRuns(engineRuns)
        val e2eSummary = ScenarioComparisonReport.summarizeRuns(e2eRuns)
        val engineSummaryFile = File(outDir, "summary_engine_only.json")
        val e2eSummaryFile = File(outDir, "summary_e2e.json")
        ScenarioComparisonReport.writeSummaryJson(engineSummary, engineSummaryFile)
        ScenarioComparisonReport.writeSummaryJson(e2eSummary, e2eSummaryFile)

        val baselineEnginePath = (System.getProperty("mcaw.baselineSummaryEngineOnly") ?: "").trim()
        val baselineE2ePath = (System.getProperty("mcaw.baselineSummaryE2E") ?: "").trim()
        val baselineEngineFile = if (baselineEnginePath.isBlank()) null else File(baselineEnginePath)
        val baselineE2eFile = if (baselineE2ePath.isBlank()) null else File(baselineE2ePath)
        val baselineEngineSummary = if (baselineEngineFile != null && baselineEngineFile.exists()) ScenarioComparisonReport.readSummaryJson(baselineEngineFile) else emptyList()
        val baselineE2eSummary = if (baselineE2eFile != null && baselineE2eFile.exists()) ScenarioComparisonReport.readSummaryJson(baselineE2eFile) else emptyList()

        // Threshold defaults tuned to the current passing scenario catalog:
        // - hard latency: 0.60s (avoids noise while catching meaningful warning delays)
        // - soft latency: 0.25s (early signal for drift)
        // - hard transitions increase: +2
        // - soft transitions increase: +1
        val hardLatencySec = (System.getProperty("mcaw.diff.hardLatencySec") ?: "0.60").toFloatOrNull() ?: 0.60f
        val softLatencySec = (System.getProperty("mcaw.diff.softLatencySec") ?: "0.25").toFloatOrNull() ?: 0.25f
        val hardTransitionsInc = (System.getProperty("mcaw.diff.hardTransitionsInc") ?: "2").toIntOrNull() ?: 2
        val softTransitionsInc = (System.getProperty("mcaw.diff.softTransitionsInc") ?: "1").toIntOrNull() ?: 1

        val engineDiff = if (baselineEngineSummary.isNotEmpty()) {
            ScenarioComparisonReport.compare(
                baseline = baselineEngineSummary,
                current = engineSummary,
                hardLatencyRegressionSec = hardLatencySec,
                softLatencyRegressionSec = softLatencySec,
                hardTransitionsIncrease = hardTransitionsInc,
                softTransitionsIncrease = softTransitionsInc
            )
        } else null
        val e2eDiff = if (baselineE2eSummary.isNotEmpty()) {
            ScenarioComparisonReport.compare(
                baseline = baselineE2eSummary,
                current = e2eSummary,
                hardLatencyRegressionSec = hardLatencySec,
                softLatencyRegressionSec = softLatencySec,
                hardTransitionsIncrease = hardTransitionsInc,
                softTransitionsIncrease = softTransitionsInc
            )
        } else null

        if (engineDiff != null) {
            ScenarioComparisonReport.writeDiffJson(engineDiff, File(outDir, "diff_summary_engine_only.json"), baselineEngineFile?.absolutePath ?: "", engineSummaryFile.absolutePath)
        }
        if (e2eDiff != null) {
            ScenarioComparisonReport.writeDiffJson(e2eDiff, File(outDir, "diff_summary_e2e.json"), baselineE2eFile?.absolutePath ?: "", e2eSummaryFile.absolutePath)
        }

        ScenarioComparisonReport.writeHtmlIndexDual(
            outFile = File(outDir, "index.html"),
            engineSummary = engineSummary,
            e2eSummary = e2eSummary,
            engineDiff = engineDiff,
            e2eDiff = e2eDiff,
            reportsRelativePath = "."
        )

        val failOnHard = (System.getProperty("mcaw.failOnHardRegression") ?: "true").toBoolean()
        if (failOnHard) {
            val hard = (engineDiff?.hardRegressionCount ?: 0) + (e2eDiff?.hardRegressionCount ?: 0)
            assertTrue("Hard regression detected: $hard", hard == 0)
        }
        // Export runbook into report folder so artifact is self-contained.
        val runbookCandidates = listOf(
            File("docs/SCENARIO_BASELINE_RUNBOOK.md"),
            File("../docs/SCENARIO_BASELINE_RUNBOOK.md"),
            File("../../docs/SCENARIO_BASELINE_RUNBOOK.md")
        )
        val runbookSource = runbookCandidates.firstOrNull { it.exists() }
        runbookSource?.copyTo(File(outDir, "RUNBOOK.md"), overwrite = true)

        // Baseline update gate (opt-in): writes candidate baseline when quality gates pass.
        val baselineUpdateEnabled = (System.getProperty("mcaw.baseline.updateEnabled") ?: "false")
            .equals("true", ignoreCase = true)
        val baselineCandidatePath = (System.getProperty("mcaw.baseline.candidateOut") ?: "").trim()
        val baselineRequireAllPass = (System.getProperty("mcaw.baseline.requireAllPass") ?: "true")
            .equals("true", ignoreCase = true)
        val baselineMaxSoftRegressions = (System.getProperty("mcaw.baseline.maxSoftRegressions") ?: "0").toIntOrNull() ?: 0
        val baselineMinImproved = (System.getProperty("mcaw.baseline.minImproved") ?: "0").toIntOrNull() ?: 0

        if (baselineUpdateEnabled && baselineCandidatePath.isNotBlank()) {
            val decision = ScenarioComparisonReport.decideBaselineUpdate(
                hasBaseline = baselineEngineSummary.isNotEmpty() || baselineE2eSummary.isNotEmpty(),
                allScenariosPass = allOk,
                diff = engineDiff ?: e2eDiff,
                requireAllPass = baselineRequireAllPass,
                maxSoftRegressions = baselineMaxSoftRegressions,
                minImproved = baselineMinImproved
            )

            val decisionFile = File(outDir, "baseline_update_decision.txt")
            decisionFile.writeText(
                buildString {
                    append("shouldUpdate=").append(decision.shouldUpdate).append('\n')
                    for (r in decision.reasons) append("- ").append(r).append('\n')
                }
            )

            if (decision.shouldUpdate) {
                val candidate = File(baselineCandidatePath)
                candidate.parentFile?.mkdirs()
                ScenarioComparisonReport.writeSummaryJson(engineSummary, candidate)
            }
        }

        val failOnScenario = (System.getProperty("mcaw.failOnScenario") ?: "false").equals("true", ignoreCase = true)
        if (failOnScenario) {
            assertTrue(
                "Některé scénáře nesplnily očekávání. Viz build/reports/mcaw_scenarios/$stamp/INDEX.md",
                allOk
            )
        }

    }

    private fun shorten(s: String): String {
        val t = s.trim().replace("\n", " ").replace("  ", " ")
        return if (t.length <= 140) t else t.take(137) + "…"
    }
}
