package com.mcaw.risk

import com.mcaw.risk.scenario.ScenarioCatalogFactory
import com.mcaw.risk.scenario.ScenarioRunner
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

        for (s in catalog.scenarios) {
            val run = ScenarioRunner.runScenario(s)
            ScenarioRunner.writeReports(run, outDir)

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
