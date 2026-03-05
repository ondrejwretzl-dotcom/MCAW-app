package com.mcaw.risk.scenario

import java.io.File
import kotlin.math.max

/**
 * Snapshot + diff reporting for scenario simulations.
 *
 * Design goals:
 * - Deterministic machine-readable summary (summary.json)
 * - Optional baseline comparison (diff_summary.json)
 * - Human-friendly clickable HTML index
 */
object ScenarioComparisonReport {

    data class ScenarioSummary(
        val scenarioId: String,
        val domain: String,
        val vehicle: String,
        val regressionType: String,
        val severity: String,
        val expectedMaxLevel: Int,
        val expectedState: String,
        val pass: Boolean,
        val maxLevel: Int,
        val firstOrangeSec: Float?,
        val firstRedSec: Float?,
        val transitionsTotal: Int,
        val maxTransitionsWindow: Int,
        val orangeCount: Int,
        val redCount: Int,
        val topReasonIds: List<Int>,
        val topReasonShort: String?
    )

    data class DiffEntry(
        val scenarioId: String,
        val baselinePass: Boolean,
        val currentPass: Boolean,
        val deltaFirstOrangeSec: Float?,
        val deltaFirstRedSec: Float?,
        val deltaTransitions: Int,
        val deltaMaxTransitionsWindow: Int,
        val status: String,
        val reasons: List<String>
    )

    data class DiffResult(
        val baselinePath: String,
        val currentPath: String,
        val hardRegressionCount: Int,
        val softRegressionCount: Int,
        val improvedCount: Int,
        val unchangedCount: Int,
        val entries: List<DiffEntry>
    )

    data class BaselineUpdateDecision(
        val shouldUpdate: Boolean,
        val reasons: List<String>
    )

    fun summarizeRuns(runs: List<ScenarioRun>): List<ScenarioSummary> {
        return runs.map { run ->
            val levels = run.levels
            val maxLevel = levels.maxOrNull() ?: 0

            val firstOrangeSec = firstTimeAtOrAbove(run, 1)
            val firstRedSec = firstTimeAtOrAbove(run, 2)

            val transitionsTotal = countTransitions(levels)
            val maxTransitionsWindow = maxTransitionsInWindow(run, windowSec = 5f)

            val orangeCount = run.events.count { it.type == "ALERT_ENTER" && it.level == 1 }
            val redCount = run.events.count { it.type == "ALERT_ENTER" && it.level == 2 }

            val topReasonIds = run.events
                .asSequence()
                .filter { it.type == "ALERT_ENTER" && it.reasonId > 0 }
                .groupingBy { it.reasonId }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(5)
                .map { it.first }

            val topReasonShort = run.events
                .asSequence()
                .filter { it.type == "ALERT_ENTER" }
                .maxByOrNull { it.risk }
                ?.let { com.mcaw.risk.RiskEngine.formatReasonShort(it.reasonBits) }

            ScenarioSummary(
                scenarioId = run.scenario.id,
                domain = run.scenario.domain.name,
                vehicle = run.scenario.vehicle.name,
                regressionType = run.scenario.doc.regressionType.name,
                severity = run.scenario.doc.severity.name,
                expectedMaxLevel = run.scenario.doc.expected.alertLevelMax,
                expectedState = run.scenario.doc.expected.expectedState,
                pass = run.verdicts.all { it.ok },
                maxLevel = maxLevel,
                firstOrangeSec = firstOrangeSec,
                firstRedSec = firstRedSec,
                transitionsTotal = transitionsTotal,
                maxTransitionsWindow = maxTransitionsWindow,
                orangeCount = orangeCount,
                redCount = redCount,
                topReasonIds = topReasonIds,
                topReasonShort = topReasonShort
            )
        }
    }

    fun writeSummaryJson(summary: List<ScenarioSummary>, outFile: File) {
        outFile.parentFile?.mkdirs()
        val sb = StringBuilder(32_000)
        sb.append("{\n")
        sb.append("  \"version\": 2,\n")
        sb.append("  \"scenarios\": [\n")
        for ((idx, s) in summary.withIndex()) {
            if (idx > 0) sb.append(",\n")
            sb.append("    {")
            sb.append("\"scenarioId\":\"").append(escape(s.scenarioId)).append("\"")
            sb.append(",\"domain\":\"").append(escape(s.domain)).append("\"")
            sb.append(",\"vehicle\":\"").append(escape(s.vehicle)).append("\"")
            sb.append(",\"regressionType\":\"").append(escape(s.regressionType)).append("\"")
            sb.append(",\"severity\":\"").append(escape(s.severity)).append("\"")
            sb.append(",\"expectedMaxLevel\":").append(s.expectedMaxLevel)
            sb.append(",\"expectedState\":\"").append(escape(s.expectedState)).append("\"")
            sb.append(",\"pass\":").append(if (s.pass) "true" else "false")
            sb.append(",\"maxLevel\":").append(s.maxLevel)
            sb.append(",\"firstOrangeSec\":").append(fmtNullable(s.firstOrangeSec))
            sb.append(",\"firstRedSec\":").append(fmtNullable(s.firstRedSec))
            sb.append(",\"transitionsTotal\":").append(s.transitionsTotal)
            sb.append(",\"maxTransitionsWindow\":").append(s.maxTransitionsWindow)
            sb.append(",\"orangeCount\":").append(s.orangeCount)
            sb.append(",\"redCount\":").append(s.redCount)
            sb.append(",\"topReasonIds\":[")
            s.topReasonIds.forEachIndexed { i, id ->
                if (i > 0) sb.append(',')
                sb.append(id)
            }
            sb.append("]")
            sb.append(",\"topReasonShort\":").append(if (s.topReasonShort == null) "null" else "\"${escape(s.topReasonShort)}\"")
            sb.append("}")
        }
        sb.append("\n  ]\n")
        sb.append("}\n")
        outFile.writeText(sb.toString())
    }

    fun readSummaryJson(file: File): List<ScenarioSummary> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        val arrayStart = text.indexOf("\"scenarios\"")
        if (arrayStart < 0) return emptyList()

        val objects = extractScenarioObjects(text)
        return objects.mapNotNull { obj ->
            parseSummaryObject(obj)
        }
    }

    fun compare(
        baseline: List<ScenarioSummary>,
        current: List<ScenarioSummary>,
        hardLatencyRegressionSec: Float = 0.50f,
        softLatencyRegressionSec: Float = 0.20f,
        hardTransitionsIncrease: Int = 2,
        softTransitionsIncrease: Int = 1
    ): DiffResult {
        val baseById = baseline.associateBy { it.scenarioId }
        val entries = ArrayList<DiffEntry>()

        var hard = 0
        var soft = 0
        var improved = 0
        var unchanged = 0

        for (cur in current.sortedBy { it.scenarioId }) {
            val base = baseById[cur.scenarioId]
            if (base == null) {
                entries += DiffEntry(
                    scenarioId = cur.scenarioId,
                    baselinePass = false,
                    currentPass = cur.pass,
                    deltaFirstOrangeSec = null,
                    deltaFirstRedSec = null,
                    deltaTransitions = 0,
                    deltaMaxTransitionsWindow = 0,
                    status = "UNCHANGED",
                    reasons = listOf("No baseline scenario record; treated as neutral.")
                )
                unchanged++
                continue
            }

            val reasons = ArrayList<String>()
            var status = "UNCHANGED"

            val dOrange = delta(cur.firstOrangeSec, base.firstOrangeSec)
            val dRed = delta(cur.firstRedSec, base.firstRedSec)
            val dTransitions = cur.transitionsTotal - base.transitionsTotal
            val dMaxTransitionsWindow = cur.maxTransitionsWindow - base.maxTransitionsWindow

            // hard regressions
            if (base.pass && !cur.pass) {
                status = "REGRESSED_HARD"
                reasons += "PASS -> FAIL"
            }
            if ((dOrange ?: 0f) > hardLatencyRegressionSec) {
                status = "REGRESSED_HARD"
                reasons += "ORANGE latency +${fmt(dOrange)}s"
            }
            if ((dRed ?: 0f) > hardLatencyRegressionSec) {
                status = "REGRESSED_HARD"
                reasons += "RED latency +${fmt(dRed)}s"
            }
            if (dMaxTransitionsWindow >= hardTransitionsIncrease) {
                status = "REGRESSED_HARD"
                reasons += "maxTransitionsWindow +$dMaxTransitionsWindow"
            }

            if (status != "REGRESSED_HARD") {
                val softReasons = ArrayList<String>()
                if ((dOrange ?: 0f) > softLatencyRegressionSec) softReasons += "ORANGE latency +${fmt(dOrange)}s"
                if ((dRed ?: 0f) > softLatencyRegressionSec) softReasons += "RED latency +${fmt(dRed)}s"
                if (dMaxTransitionsWindow >= softTransitionsIncrease) softReasons += "maxTransitionsWindow +$dMaxTransitionsWindow"
                if (softReasons.isNotEmpty()) {
                    status = "REGRESSED_SOFT"
                    reasons += softReasons
                }
            }

            if (status == "UNCHANGED") {
                if (!base.pass && cur.pass) {
                    status = "IMPROVED"
                    reasons += "FAIL -> PASS"
                } else if ((dOrange ?: 0f) < -0.15f || (dRed ?: 0f) < -0.15f || dTransitions < 0) {
                    status = "IMPROVED"
                    if ((dOrange ?: 0f) < -0.15f) reasons += "ORANGE faster ${fmt(dOrange)}s"
                    if ((dRed ?: 0f) < -0.15f) reasons += "RED faster ${fmt(dRed)}s"
                    if (dTransitions < 0) reasons += "transitions $dTransitions"
                }
            }

            when (status) {
                "REGRESSED_HARD" -> hard++
                "REGRESSED_SOFT" -> soft++
                "IMPROVED" -> improved++
                else -> unchanged++
            }

            entries += DiffEntry(
                scenarioId = cur.scenarioId,
                baselinePass = base.pass,
                currentPass = cur.pass,
                deltaFirstOrangeSec = dOrange,
                deltaFirstRedSec = dRed,
                deltaTransitions = dTransitions,
                deltaMaxTransitionsWindow = dMaxTransitionsWindow,
                status = status,
                reasons = reasons.ifEmpty { listOf("No material change") }
            )
        }

        return DiffResult(
            baselinePath = "",
            currentPath = "",
            hardRegressionCount = hard,
            softRegressionCount = soft,
            improvedCount = improved,
            unchangedCount = unchanged,
            entries = entries
        )
    }

    fun writeDiffJson(diff: DiffResult, outFile: File, baselinePath: String, currentPath: String) {
        outFile.parentFile?.mkdirs()
        val sb = StringBuilder(32_000)
        sb.append("{\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"baselinePath\": \"").append(escape(baselinePath)).append("\",\n")
        sb.append("  \"currentPath\": \"").append(escape(currentPath)).append("\",\n")
        sb.append("  \"hardRegressionCount\": ").append(diff.hardRegressionCount).append(",\n")
        sb.append("  \"softRegressionCount\": ").append(diff.softRegressionCount).append(",\n")
        sb.append("  \"improvedCount\": ").append(diff.improvedCount).append(",\n")
        sb.append("  \"unchangedCount\": ").append(diff.unchangedCount).append(",\n")
        sb.append("  \"entries\": [\n")
        for ((i, e) in diff.entries.withIndex()) {
            if (i > 0) sb.append(",\n")
            sb.append("    {")
            sb.append("\"scenarioId\":\"").append(escape(e.scenarioId)).append("\"")
            sb.append(",\"baselinePass\":").append(if (e.baselinePass) "true" else "false")
            sb.append(",\"currentPass\":").append(if (e.currentPass) "true" else "false")
            sb.append(",\"deltaFirstOrangeSec\":").append(fmtNullable(e.deltaFirstOrangeSec))
            sb.append(",\"deltaFirstRedSec\":").append(fmtNullable(e.deltaFirstRedSec))
            sb.append(",\"deltaTransitions\":").append(e.deltaTransitions)
            sb.append(",\"deltaMaxTransitionsWindow\":").append(e.deltaMaxTransitionsWindow)
            sb.append(",\"status\":\"").append(escape(e.status)).append("\"")
            sb.append(",\"reasons\":[")
            e.reasons.forEachIndexed { idx, r ->
                if (idx > 0) sb.append(',')
                sb.append('"').append(escape(r)).append('"')
            }
            sb.append("]")
            sb.append("}")
        }
        sb.append("\n  ]\n")
        sb.append("}\n")
        outFile.writeText(sb.toString())
    }

    fun writeHtmlIndex(
        outFile: File,
        summary: List<ScenarioSummary>,
        diff: DiffResult?,
        reportsRelativePath: String = "."
    ) {
        outFile.parentFile?.mkdirs()
        val diffById = diff?.entries?.associateBy { it.scenarioId }.orEmpty()

        val hard = diff?.hardRegressionCount ?: 0
        val soft = diff?.softRegressionCount ?: 0
        val improved = diff?.improvedCount ?: 0

        val sb = StringBuilder(64_000)
        sb.append("""
            <!doctype html>
            <html lang="cs">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>MCAW Scenario Report</title>
              <style>
                body { font-family: Inter, system-ui, Arial, sans-serif; margin: 20px; color: #0f172a; }
                h1 { margin-bottom: 8px; }
                .cards { display:flex; gap:10px; margin: 12px 0 18px; flex-wrap: wrap; }
                .card { border-radius: 10px; padding: 10px 12px; background:#f1f5f9; min-width: 130px; }
                .ok { background:#dcfce7; } .warn { background:#fef9c3; } .bad { background:#fee2e2; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border-bottom: 1px solid #e2e8f0; text-align: left; padding: 8px; font-size: 14px; }
                th { background: #f8fafc; position: sticky; top: 0; }
                .pill { border-radius: 999px; padding: 2px 8px; font-size: 12px; }
                .pill-green { background:#dcfce7; } .pill-yellow { background:#fef9c3; } .pill-red { background:#fee2e2; }
              </style>
            </head>
            <body>
              <h1>MCAW scénářové reporty</h1>
        """.trimIndent())
        sb.append("<p>Souhrn scénářů + baseline porovnání (pokud je baseline dostupná).</p>")
        sb.append("<p><a href='RUNBOOK.md'>📘 Baseline runbook (co spouštět a kdy)</a></p>")

        sb.append("<div class=\"cards\">")
        sb.append("<div class=\"card ok\"><strong>PASS</strong><br>${summary.count { it.pass }}</div>")
        sb.append("<div class=\"card bad\"><strong>FAIL</strong><br>${summary.count { !it.pass }}</div>")
        sb.append("<div class=\"card bad\"><strong>REGRESSED_HARD</strong><br>$hard</div>")
        sb.append("<div class=\"card warn\"><strong>REGRESSED_SOFT</strong><br>$soft</div>")
        sb.append("<div class=\"card ok\"><strong>IMPROVED</strong><br>$improved</div>")
        sb.append("</div>")

        sb.append("<table><thead><tr>")
        sb.append("<th>Scénář</th><th>Doména</th><th>Vozidlo</th><th>Result</th><th>Diff</th><th>Δ ORANGE</th><th>Δ RED</th><th>Δ TRANS</th><th>Detail</th>")
        sb.append("</tr></thead><tbody>")

        for (s in summary.sortedBy { it.scenarioId }) {
            val d = diffById[s.scenarioId]
            val resultPill = if (s.pass) "<span class='pill pill-green'>PASS</span>" else "<span class='pill pill-red'>FAIL</span>"
            val diffPill = when (d?.status) {
                "REGRESSED_HARD" -> "<span class='pill pill-red'>REGRESSED_HARD</span>"
                "REGRESSED_SOFT" -> "<span class='pill pill-yellow'>REGRESSED_SOFT</span>"
                "IMPROVED" -> "<span class='pill pill-green'>IMPROVED</span>"
                else -> "<span class='pill'>UNCHANGED</span>"
            }

            sb.append("<tr>")
            sb.append("<td><strong>${escapeHtml(s.scenarioId)}</strong></td>")
            sb.append("<td>${escapeHtml(s.domain)}</td>")
            sb.append("<td>${escapeHtml(s.vehicle)}</td>")
            sb.append("<td>$resultPill</td>")
            sb.append("<td>$diffPill</td>")
            sb.append("<td>${fmtNullable(d?.deltaFirstOrangeSec)}</td>")
            sb.append("<td>${fmtNullable(d?.deltaFirstRedSec)}</td>")
            sb.append("<td>${d?.deltaTransitions ?: 0}</td>")
            sb.append("<td><a href='${reportsRelativePath}/${s.scenarioId}.md'>MD</a> · <a href='${reportsRelativePath}/${s.scenarioId}.jsonl'>JSONL</a></td>")
            sb.append("</tr>")
        }

        sb.append("</tbody></table>")

        if (diff != null) {
            sb.append("<h2>Diff poznámky</h2><ul>")
            for (e in diff.entries.filter { it.status != "UNCHANGED" }) {
                sb.append("<li><strong>${escapeHtml(e.scenarioId)}</strong>: ${escapeHtml(e.status)} – ${escapeHtml(e.reasons.joinToString("; "))}</li>")
            }
            sb.append("</ul>")
        }

        sb.append("</body></html>")
        outFile.writeText(sb.toString())
    }

    fun decideBaselineUpdate(
        hasBaseline: Boolean,
        allScenariosPass: Boolean,
        diff: DiffResult?,
        requireAllPass: Boolean,
        maxSoftRegressions: Int,
        minImproved: Int
    ): BaselineUpdateDecision {
        val reasons = ArrayList<String>()

        if (requireAllPass && !allScenariosPass) {
            reasons += "Not all scenarios passed."
        }

        if (!hasBaseline) {
            return if (reasons.isEmpty()) {
                BaselineUpdateDecision(true, listOf("No baseline exists; creating initial baseline."))
            } else {
                BaselineUpdateDecision(false, reasons + "Initial baseline creation blocked by gates.")
            }
        }

        val d = diff
        if (d == null) {
            reasons += "Diff is unavailable while baseline exists."
            return BaselineUpdateDecision(false, reasons)
        }

        if (d.hardRegressionCount > 0) {
            reasons += "Hard regressions present: ${d.hardRegressionCount}."
        }
        if (d.softRegressionCount > maxSoftRegressions) {
            reasons += "Soft regressions exceed limit: ${d.softRegressionCount} > $maxSoftRegressions."
        }
        if (d.improvedCount < minImproved) {
            reasons += "Improvements below gate: ${d.improvedCount} < $minImproved."
        }

        return if (reasons.isEmpty()) {
            BaselineUpdateDecision(
                true,
                listOf(
                    "Gates passed: hard=${d.hardRegressionCount}, soft=${d.softRegressionCount}, improved=${d.improvedCount}."
                )
            )
        } else {
            BaselineUpdateDecision(false, reasons)
        }
    }

    fun mergeDiffs(engineDiff: DiffResult?, e2eDiff: DiffResult?): DiffResult? {
        if (engineDiff == null && e2eDiff == null) return null
        val entries = ArrayList<DiffEntry>()
        if (engineDiff != null) {
            entries += engineDiff.entries.map { it.copy(scenarioId = "ENGINE_ONLY:${it.scenarioId}") }
        }
        if (e2eDiff != null) {
            entries += e2eDiff.entries.map { it.copy(scenarioId = "E2E:${it.scenarioId}") }
        }
        return DiffResult(
            baselinePath = listOfNotNull(engineDiff?.baselinePath, e2eDiff?.baselinePath).joinToString(";"),
            currentPath = listOfNotNull(engineDiff?.currentPath, e2eDiff?.currentPath).joinToString(";"),
            hardRegressionCount = (engineDiff?.hardRegressionCount ?: 0) + (e2eDiff?.hardRegressionCount ?: 0),
            softRegressionCount = (engineDiff?.softRegressionCount ?: 0) + (e2eDiff?.softRegressionCount ?: 0),
            improvedCount = (engineDiff?.improvedCount ?: 0) + (e2eDiff?.improvedCount ?: 0),
            unchangedCount = (engineDiff?.unchangedCount ?: 0) + (e2eDiff?.unchangedCount ?: 0),
            entries = entries
        )
    }

    private fun firstTimeAtOrAbove(run: ScenarioRun, level: Int): Float? {
        val idx = run.levels.indexOfFirst { it >= level }
        return if (idx >= 0 && idx < run.frames.size) run.frames[idx].tSec else null
    }

    private fun countTransitions(levels: List<Int>): Int {
        var transitions = 0
        for (i in 1 until levels.size) if (levels[i] != levels[i - 1]) transitions++
        return transitions
    }


    fun writeHtmlIndexDual(
        outFile: File,
        engineSummary: List<ScenarioSummary>,
        e2eSummary: List<ScenarioSummary>,
        engineDiff: DiffResult?,
        e2eDiff: DiffResult?,
        reportsRelativePath: String = "."
    ) {
        val combinedHard = (engineDiff?.hardRegressionCount ?: 0) + (e2eDiff?.hardRegressionCount ?: 0)
        val combinedSoft = (engineDiff?.softRegressionCount ?: 0) + (e2eDiff?.softRegressionCount ?: 0)
        val combinedFail = engineSummary.count { !it.pass } + e2eSummary.count { !it.pass }
        val combinedPass = engineSummary.count { it.pass } + e2eSummary.count { it.pass }
        val gateBadge = if (combinedHard == 0) "PASS" else "FAIL"

        val html = buildString(96_000) {
            append("""
            <!doctype html>
            <html lang="cs">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>MCAW scénářové reporty</title>
              <style>
                :root {
                  --bg: #f8fafc;
                  --ink: #0f172a;
                  --muted: #475569;
                  --line: #e2e8f0;
                  --pass-bg: #dcfce7;
                  --fail-bg: #fee2e2;
                  --hard-bg: #fecaca;
                  --soft-bg: #fef9c3;
                  --improved-bg: #ccfbf1;
                  --unchanged-bg: #e2e8f0;
                }
                * { box-sizing: border-box; }
                body { font-family: Inter, system-ui, Arial, sans-serif; margin: 0; background: var(--bg); color: var(--ink); }
                .container { max-width: 1300px; margin: 0 auto; padding: 24px 16px 40px; }
                h1 { margin: 0 0 8px; font-size: 44px; letter-spacing: -0.02em; }
                h2 { margin: 8px 0 12px; font-size: 22px; }
                p { margin: 8px 0; }
                .top-meta { color: var(--muted); margin-bottom: 14px; }
                .actions { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 18px; }
                .actions a { color: #0b4fd8; text-decoration: none; font-weight: 600; }
                .actions a:hover { text-decoration: underline; }
                .global-kpis, .suite-kpis { display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(148px, 1fr)); margin: 12px 0 18px; }
                .card { border-radius: 12px; padding: 12px 14px; border: 1px solid var(--line); background: #fff; }
                .card .label { font-size: 12px; font-weight: 700; letter-spacing: 0.03em; }
                .card .value { font-size: 28px; font-weight: 800; margin-top: 4px; }
                .pass { background: var(--pass-bg); }
                .fail { background: var(--fail-bg); }
                .hard { background: var(--hard-bg); }
                .soft { background: var(--soft-bg); }
                .improved { background: var(--improved-bg); }
                .unchanged { background: var(--unchanged-bg); }
                .chip { display: inline-block; border-radius: 999px; padding: 3px 10px; font-size: 12px; font-weight: 700; border: 1px solid #cbd5e1; background: #fff; }
                .chip.pass { border-color: #86efac; background: var(--pass-bg); }
                .chip.fail { border-color: #fca5a5; background: var(--fail-bg); }
                .chip.hard { border-color: #f87171; background: var(--hard-bg); }
                .chip.soft { border-color: #facc15; background: var(--soft-bg); }
                .chip.improved { border-color: #5eead4; background: var(--improved-bg); }
                .chip.unchanged { border-color: #94a3b8; background: var(--unchanged-bg); }
                .suite { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 14px; margin: 18px 0 22px; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04); }
                .suite-header { display: flex; justify-content: space-between; gap: 12px; align-items: baseline; flex-wrap: wrap; }
                .suite-header small { color: var(--muted); }
                .dataset-links { margin: 8px 0 14px; display: flex; gap: 10px; flex-wrap: wrap; }
                .dataset-links a { color: #0b4fd8; font-weight: 600; text-decoration: none; }
                .dataset-links a:hover { text-decoration: underline; }
                .table-wrap { overflow-x: auto; border: 1px solid var(--line); border-radius: 12px; }
                table { border-collapse: collapse; width: 100%; min-width: 1040px; }
                th, td { border-bottom: 1px solid var(--line); text-align: left; padding: 8px 10px; font-size: 14px; vertical-align: top; }
                th { background: #f1f5f9; position: sticky; top: 0; z-index: 1; }
                tbody tr:nth-child(even) { background: #fcfdff; }
                tbody tr:hover { background: #f8fafc; }
                .scenario { font-weight: 700; letter-spacing: 0.01em; }
                .links a { color: #0b4fd8; text-decoration: none; font-weight: 600; }
                .links a:hover { text-decoration: underline; }
                .muted { color: var(--muted); }
                @media (max-width: 900px) {
                  h1 { font-size: 34px; }
                }
              </style>
            </head>
            <body>
              <div class="container">
                <h1>MCAW scénářové reporty</h1>
                <p class="top-meta">Lidsky čitelný přehled regresních testů rozdělený na ENGINE_ONLY a E2E_PIPELINE.</p>
                <div class="actions">
                  <a href="RUNBOOK.md">📘 Baseline runbook</a>
                  <a href="summary_engine_only.json">📦 summary_engine_only.json</a>
                  <a href="summary_e2e.json">📦 summary_e2e.json</a>
                  <a href="diff_summary_engine_only.json">🧪 diff_summary_engine_only.json</a>
                  <a href="diff_summary_e2e.json">🧪 diff_summary_e2e.json</a>
                </div>

                <div class="global-kpis">
                  <div class="card ${if (gateBadge == "PASS") "pass" else "fail"}"><div class="label">COMBINED GATE</div><div class="value">$gateBadge</div></div>
                  <div class="card pass"><div class="label">PASS</div><div class="value">$combinedPass</div></div>
                  <div class="card fail"><div class="label">FAIL</div><div class="value">$combinedFail</div></div>
                  <div class="card hard"><div class="label">REGRESSED_HARD</div><div class="value">$combinedHard</div></div>
                  <div class="card soft"><div class="label">REGRESSED_SOFT</div><div class="value">$combinedSoft</div></div>
                </div>

            """.trimIndent())

            append(renderSuiteSection(
                suiteTitle = "ENGINE_ONLY",
                summary = engineSummary,
                diff = engineDiff,
                reportsRelativePath = reportsRelativePath,
                reportSubDir = "engine_only",
                summaryJson = "summary_engine_only.json",
                diffJson = "diff_summary_engine_only.json"
            ))

            append(renderSuiteSection(
                suiteTitle = "E2E_PIPELINE",
                summary = e2eSummary,
                diff = e2eDiff,
                reportsRelativePath = reportsRelativePath,
                reportSubDir = "e2e",
                summaryJson = "summary_e2e.json",
                diffJson = "diff_summary_e2e.json"
            ))

            append("""
              </div>
              <script>
                (function() {
                  function applyFilter(suiteKey, q) {
                    const query = (q || '').trim().toLowerCase();
                    const rows = document.querySelectorAll('tr[data-suite="' + suiteKey + '"]');
                    rows.forEach(function(r) {
                      const hay = (r.getAttribute('data-filter') || '');
                      r.style.display = (query.length === 0 || hay.indexOf(query) >= 0) ? '' : 'none';
                    });
                  }
                  const inputs = document.querySelectorAll('input.filterInput');
                  inputs.forEach(function(inp) {
                    const suite = inp.getAttribute('data-suite');
                    inp.addEventListener('input', function() { applyFilter(suite, inp.value); });
                  });
                })();
              </script>
            </body>
            </html>
            """.trimIndent())
        }
        outFile.parentFile?.mkdirs()
        outFile.writeText(html)
    }

    private fun renderSuiteSection(
        suiteTitle: String,
        summary: List<ScenarioSummary>,
        diff: DiffResult?,
        reportsRelativePath: String,
        reportSubDir: String,
        summaryJson: String,
        diffJson: String
    ): String {
        val diffById = diff?.entries?.associateBy { it.scenarioId }.orEmpty()
        val pass = summary.count { it.pass }
        val fail = summary.size - pass
        val hard = diff?.hardRegressionCount ?: 0
        val soft = diff?.softRegressionCount ?: 0
        val improved = diff?.improvedCount ?: 0
        val unchanged = diff?.unchangedCount ?: summary.size

        val sb = StringBuilder(48_000)
        sb.append("<section class=\"suite\">")
        sb.append("<div class=\"suite-header\"><h2>").append(suiteTitle).append("</h2><small>")
            .append("Scénářů: ").append(summary.size).append(" · Hard regressions: ").append(hard)
            .append("</small></div>")
        sb.append("<div class=\"dataset-links\">")
        sb.append("<a href=\"").append(escapeHtml(summaryJson)).append("\">📄 ").append(escapeHtml(summaryJson)).append("</a>")
        if (diff != null) {
            sb.append("<a href=\"").append(escapeHtml(diffJson)).append("\">📄 ").append(escapeHtml(diffJson)).append("</a>")
        } else {
            sb.append("<span class=\"muted\">📄 ").append(escapeHtml(diffJson)).append(" (není dostupné bez baseline)</span>")
        }
        sb.append("</div>")

        sb.append("<div class=\"suite-kpis\">")
        sb.append("<div class=\"card pass\"><div class=\"label\">PASS</div><div class=\"value\">$pass</div></div>")
        sb.append("<div class=\"card fail\"><div class=\"label\">FAIL</div><div class=\"value\">$fail</div></div>")
        sb.append("<div class=\"card hard\"><div class=\"label\">REGRESSED_HARD</div><div class=\"value\">$hard</div></div>")
        sb.append("<div class=\"card soft\"><div class=\"label\">REGRESSED_SOFT</div><div class=\"value\">$soft</div></div>")
        sb.append("<div class=\"card improved\"><div class=\"label\">IMPROVED</div><div class=\"value\">$improved</div></div>")
        sb.append("<div class=\"card unchanged\"><div class=\"label\">UNCHANGED</div><div class=\"value\">$unchanged</div></div>")
        sb.append("</div>")

        sb.append("<div style='display:flex; gap:10px; align-items:center; flex-wrap:wrap; margin: 8px 0 12px;'>")
        sb.append("<label class='muted' style='font-weight:600;'>Filtr:</label>")
        sb.append("<input class='filterInput' data-suite='").append(escapeHtml(reportSubDir)).append("' placeholder='ID / doména / typ regrese / reason…' style='padding:8px 10px; border:1px solid var(--line); border-radius:10px; min-width: 320px;' />")
        sb.append("<span class='muted'>Tip: napište např. <code>FalsePositive</code> nebo <code>CRITICAL</code>.</span>")
        sb.append("</div>")

        sb.append("<div class=\"table-wrap\"><table><thead><tr>")
        sb.append("<th>Scénář</th><th>Doména</th><th>Vozidlo</th><th>Spec</th><th>Result</th><th>Diff</th><th>Δ ORANGE</th><th>Δ RED</th><th>Δ TRANS</th><th>Top reason</th><th>Artefakty</th>")
        sb.append("</tr></thead><tbody>")

        for (s in summary.sortedBy { it.scenarioId }) {
            val d = diffById[s.scenarioId]
            val resultClass = if (s.pass) "pass" else "fail"
            val resultText = if (s.pass) "PASS" else "FAIL"
            val diffStatus = d?.status ?: if (diff == null) "N/A" else "UNCHANGED"
            val diffClass = when (diffStatus) {
                "REGRESSED_HARD" -> "hard"
                "REGRESSED_SOFT" -> "soft"
                "IMPROVED" -> "improved"
                else -> "unchanged"
            }

            val spec = "${escapeHtml(s.regressionType)}/${escapeHtml(s.severity)} exp<=${s.expectedMaxLevel} ${escapeHtml(s.expectedState)}"
            sb.append("<tr data-suite='").append(escapeHtml(reportSubDir)).append("' data-filter='")
                .append(escapeHtml((s.scenarioId + " " + s.domain + " " + s.vehicle + " " + s.regressionType + " " + s.severity + " " + s.expectedState + " " + (s.topReasonShort ?: "")).lowercase()))
                .append("'>")
            sb.append("<td class=\"scenario\">").append(escapeHtml(s.scenarioId)).append("</td>")
            sb.append("<td>").append(escapeHtml(s.domain)).append("</td>")
            sb.append("<td>").append(escapeHtml(s.vehicle)).append("</td>")
            sb.append("<td>").append(spec).append("<br><span class='muted'>act max=").append(s.maxLevel).append("</span></td>")
            sb.append("<td><span class=\"chip ").append(resultClass).append("\">").append(resultText).append("</span></td>")
            sb.append("<td><span class=\"chip ").append(diffClass).append("\">").append(escapeHtml(diffStatus)).append("</span></td>")
            sb.append("<td>").append(fmtNullable(d?.deltaFirstOrangeSec)).append("</td>")
            sb.append("<td>").append(fmtNullable(d?.deltaFirstRedSec)).append("</td>")
            sb.append("<td>").append(d?.deltaTransitions ?: 0).append("</td>")
            sb.append("<td>").append(escapeHtml(s.topReasonShort ?: "—")).append("</td>")
            sb.append("<td class=\"links\"><a href='${reportsRelativePath}/${reportSubDir}/${s.scenarioId}.md'>MD</a> · <a href='${reportsRelativePath}/${reportSubDir}/${s.scenarioId}.jsonl'>JSONL</a></td>")
            sb.append("</tr>")
        }

        if (summary.isEmpty()) {
            sb.append("<tr><td colspan=\"11\" class=\"muted\">Žádná data pro tuto sadu testů.</td></tr>")
        }

        sb.append("</tbody></table></div>")
        sb.append("</section>")
        return sb.toString()
    }

    private fun maxTransitionsInWindow(run: ScenarioRun, windowSec: Float): Int {
        if (run.frames.isEmpty()) return 0
        var best = 0
        val n = run.frames.size
        for (i in 0 until n) {
            val t0 = run.frames[i].tSec
            var local = 0
            var last = run.levels[i]
            var j = i + 1
            while (j < n && run.frames[j].tSec - t0 <= windowSec) {
                val lvl = run.levels[j]
                if (lvl != last) {
                    local++
                    last = lvl
                }
                j++
            }
            best = max(best, local)
        }
        return best
    }

    private fun extractScenarioObjects(text: String): List<String> {
        val out = ArrayList<String>()
        val start = text.indexOf("\"scenarios\"")
        if (start < 0) return out
        val arrStart = text.indexOf('[', start)
        if (arrStart < 0) return out
        var i = arrStart + 1
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] == ']') break
            if (text[i] != '{') { i++; continue }
            var depth = 0
            val objStart = i
            while (i < text.length) {
                val c = text[i]
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) {
                        out += text.substring(objStart, i + 1)
                        i++
                        break
                    }
                }
                i++
            }
        }
        return out
    }

    private fun parseSummaryObject(obj: String): ScenarioSummary? {
        fun str(k: String): String? = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(obj)?.groupValues?.get(1)
        fun int(k: String): Int? = Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull()
        fun bool(k: String): Boolean? = Regex("\"$k\"\\s*:\\s*(true|false)").find(obj)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        fun flt(k: String): Float? {
            val m = Regex("\"$k\"\\s*:\\s*(null|-?\\d+(?:\\.\\d+)?)").find(obj)?.groupValues?.get(1) ?: return null
            return if (m == "null") null else m.toFloatOrNull()
        }

        val scenarioId = str("scenarioId") ?: return null
        val domain = str("domain") ?: return null
        val vehicle = str("vehicle") ?: return null
        val pass = bool("pass") ?: return null
        val maxLevel = int("maxLevel") ?: 0
        val firstOrangeSec = flt("firstOrangeSec")
        val firstRedSec = flt("firstRedSec")
        val transitionsTotal = int("transitionsTotal") ?: 0
        val maxTransitionsWindow = int("maxTransitionsWindow") ?: 0
        val orangeCount = int("orangeCount") ?: 0
        val redCount = int("redCount") ?: 0

        val reasonBlock = Regex("\"topReasonIds\"\\s*:\\s*\\[(.*?)\\]").find(obj)?.groupValues?.get(1).orEmpty()
        val topReasonIds = reasonBlock.split(',').mapNotNull { it.trim().toIntOrNull() }

        // v2 fields (optional for backward compatibility)
        val regressionType = str("regressionType") ?: "NEUvedeno"
        val severity = str("severity") ?: "MED"
        val expectedMaxLevel = int("expectedMaxLevel") ?: 0
        val expectedState = str("expectedState") ?: "—"
        val topReasonShort = str("topReasonShort")

        return ScenarioSummary(
            scenarioId = scenarioId,
            domain = domain,
            vehicle = vehicle,
            regressionType = regressionType,
            severity = severity,
            expectedMaxLevel = expectedMaxLevel,
            expectedState = expectedState,
            pass = pass,
            maxLevel = maxLevel,
            firstOrangeSec = firstOrangeSec,
            firstRedSec = firstRedSec,
            transitionsTotal = transitionsTotal,
            maxTransitionsWindow = maxTransitionsWindow,
            orangeCount = orangeCount,
            redCount = redCount,
            topReasonIds = topReasonIds,
            topReasonShort = topReasonShort
        )
    }

    private fun delta(current: Float?, baseline: Float?): Float? {
        if (current == null || baseline == null) return null
        return current - baseline
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun fmt(v: Float?): String = if (v == null || !v.isFinite()) "null" else String.format("%.3f", v)
    private fun fmtNullable(v: Float?): String = if (v == null || !v.isFinite()) "null" else String.format("%.3f", v)
}
