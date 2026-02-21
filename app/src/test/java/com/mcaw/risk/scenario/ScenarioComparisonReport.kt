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
        val pass: Boolean,
        val maxLevel: Int,
        val firstOrangeSec: Float?,
        val firstRedSec: Float?,
        val transitionsTotal: Int,
        val maxTransitionsWindow: Int,
        val orangeCount: Int,
        val redCount: Int,
        val topReasonIds: List<Int>
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

            ScenarioSummary(
                scenarioId = run.scenario.id,
                domain = run.scenario.domain.name,
                vehicle = run.scenario.vehicle.name,
                pass = run.verdicts.all { it.ok },
                maxLevel = maxLevel,
                firstOrangeSec = firstOrangeSec,
                firstRedSec = firstRedSec,
                transitionsTotal = transitionsTotal,
                maxTransitionsWindow = maxTransitionsWindow,
                orangeCount = orangeCount,
                redCount = redCount,
                topReasonIds = topReasonIds
            )
        }
    }

    fun writeSummaryJson(summary: List<ScenarioSummary>, outFile: File) {
        outFile.parentFile?.mkdirs()
        val sb = StringBuilder(32_000)
        sb.append("{\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"scenarios\": [\n")
        for ((idx, s) in summary.withIndex()) {
            if (idx > 0) sb.append(",\n")
            sb.append("    {")
            sb.append("\"scenarioId\":\"").append(escape(s.scenarioId)).append("\"")
            sb.append(",\"domain\":\"").append(escape(s.domain)).append("\"")
            sb.append(",\"vehicle\":\"").append(escape(s.vehicle)).append("\"")
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

    private fun firstTimeAtOrAbove(run: ScenarioRun, level: Int): Float? {
        val idx = run.levels.indexOfFirst { it >= level }
        return if (idx >= 0 && idx < run.frames.size) run.frames[idx].tSec else null
    }

    private fun countTransitions(levels: List<Int>): Int {
        var transitions = 0
        for (i in 1 until levels.size) if (levels[i] != levels[i - 1]) transitions++
        return transitions
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

        return ScenarioSummary(
            scenarioId = scenarioId,
            domain = domain,
            vehicle = vehicle,
            pass = pass,
            maxLevel = maxLevel,
            firstOrangeSec = firstOrangeSec,
            firstRedSec = firstRedSec,
            transitionsTotal = transitionsTotal,
            maxTransitionsWindow = maxTransitionsWindow,
            orangeCount = orangeCount,
            redCount = redCount,
            topReasonIds = topReasonIds
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
