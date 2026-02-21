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
        val catalog = ScenarioCatalogFactory.createDefaultCatalog()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val outDir = File("build/reports/mcaw_scenarios/$stamp")
        outDir.mkdirs()

        val indexMd = StringBuilder(12_000)
        indexMd.append("# MCAW 2.0 – Přehled simulací scénářů\n\n")
        indexMd.append("- Katalog: **").append(catalog.title).append("**\n")
        indexMd.append("- Verze katalogu: ").append(catalog.version).append("\n")
        indexMd.append("- Vygenerováno: ").append(stamp).append("\n\n")

        indexMd.append("## Shrnutí\n")
        indexMd.append("| Scénář | Doména | Vozidlo | Výsledek | Proč (zkráceně) | Report |\n")
        indexMd.append("|---|---|---|---|---|---|\n")

        var allOk = true
        var passCount = 0
        var failCount = 0
        val runs = ArrayList<com.mcaw.risk.scenario.ScenarioRun>(catalog.scenarios.size)

        for (s in catalog.scenarios) {
            val run = ScenarioRunner.runScenario(s)
            ScenarioRunner.writeReports(run, outDir)
            runs.add(run)

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

        val summary = ScenarioComparisonReport.summarizeRuns(runs)
        val summaryFile = File(outDir, "summary.json")
        ScenarioComparisonReport.writeSummaryJson(summary, summaryFile)

        val baselinePath = (System.getProperty("mcaw.baselineSummary") ?: "").trim()
        val baselineFile = if (baselinePath.isBlank()) null else File(baselinePath)
        val baselineSummary = if (baselineFile != null && baselineFile.exists()) {
            ScenarioComparisonReport.readSummaryJson(baselineFile)
        } else emptyList()

        // Threshold defaults tuned to the current passing scenario catalog:
        // - hard latency: 0.60s (avoids noise while catching meaningful warning delays)
        // - soft latency: 0.25s (early signal for drift)
        // - hard transitions increase: +2
        // - soft transitions increase: +1
        val hardLatencySec = (System.getProperty("mcaw.diff.hardLatencySec") ?: "0.60").toFloatOrNull() ?: 0.60f
        val softLatencySec = (System.getProperty("mcaw.diff.softLatencySec") ?: "0.25").toFloatOrNull() ?: 0.25f
        val hardTransitionsInc = (System.getProperty("mcaw.diff.hardTransitionsInc") ?: "2").toIntOrNull() ?: 2
        val softTransitionsInc = (System.getProperty("mcaw.diff.softTransitionsInc") ?: "1").toIntOrNull() ?: 1

        val diff = if (baselineSummary.isNotEmpty()) {
            ScenarioComparisonReport.compare(
                baseline = baselineSummary,
                current = summary,
                hardLatencyRegressionSec = hardLatencySec,
                softLatencyRegressionSec = softLatencySec,
                hardTransitionsIncrease = hardTransitionsInc,
                softTransitionsIncrease = softTransitionsInc
            )
        } else null

        if (diff != null) {
            ScenarioComparisonReport.writeDiffJson(
                diff = diff,
                outFile = File(outDir, "diff_summary.json"),
                baselinePath = baselineFile?.absolutePath ?: "",
                currentPath = summaryFile.absolutePath
            )
        }

        ScenarioComparisonReport.writeHtmlIndex(
            outFile = File(outDir, "index.html"),
            summary = summary,
            diff = diff,
            reportsRelativePath = "."
        )

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
                hasBaseline = baselineSummary.isNotEmpty(),
                allScenariosPass = allOk,
                diff = diff,
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
                ScenarioComparisonReport.writeSummaryJson(summary, candidate)
            }
        }

        val failOnScenario = (System.getProperty("mcaw.failOnScenario") ?: "false").equals("true", ignoreCase = true)
        if (failOnScenario) {
            assertTrue(
                "Některé scénáře nesplnily očekávání. Viz build/reports/mcaw_scenarios/$stamp/INDEX.md",
                allOk
            )
        }

        val failOnHardRegression = (System.getProperty("mcaw.failOnHardRegression") ?: "false").equals("true", ignoreCase = true)
        if (failOnHardRegression && diff != null) {
            assertTrue(
                "Nalezena tvrdá regrese proti baseline (count=${diff.hardRegressionCount}). Viz $outDir/index.html",
                diff.hardRegressionCount == 0
            )
        }
    }

    private fun shorten(s: String): String {
        val t = s.trim().replace("\n", " ").replace("  ", " ")
        return if (t.length <= 140) t else t.take(137) + "…"
    }
}
