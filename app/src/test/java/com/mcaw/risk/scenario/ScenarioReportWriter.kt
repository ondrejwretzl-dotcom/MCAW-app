package com.mcaw.risk.scenario

import com.mcaw.risk.RiskEngine
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object ScenarioReportWriter {

    fun writeMarkdown(run: ScenarioRun, file: File) {
        val s = run.scenario
        val dt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val pass = run.verdicts.all { it.ok }

        val sb = StringBuilder(16_000)
        sb.append("# MCAW 2.0 – Scenario Simulation Report\n\n")
        sb.append("- Generated: ").append(dt).append("\n")
        sb.append("- Scenario: **").append(s.id).append(" – ").append(s.title).append("**\n")
        sb.append("- Domain: ").append(s.domain).append(" | Vehicle: ").append(s.vehicle).append("\n")
        sb.append("- Verdict: ").append(if (pass) "✅ PASS" else "❌ FAIL").append("\n\n")

        sb.append("## Story\n")
        sb.append(s.notes.trim()).append("\n\n")

        sb.append("## Scenario config (effective inputs)\n")
        sb.append("- effectiveMode: **").append(s.config.effectiveMode).append("**\n")
        sb.append("- hz: ").append(s.config.hz).append("\n")
        sb.append("- riderSpeedMps: ").append(fmt(s.config.riderSpeedMps)).append("\n")
        sb.append("- qualityWeight(default): ").append(fmt(s.config.qualityWeight)).append("\n")
        sb.append("- roiContainment(default): ").append(fmt(s.config.roiContainment)).append("\n")
        sb.append("- egoOffsetN(default): ").append(fmt(s.config.egoOffsetN)).append("\n")
        sb.append("- leanDeg(default): ").append(if (s.config.leanDeg.isFinite()) fmt(s.config.leanDeg) else "NaN").append("\n\n")

        sb.append("## Engine thresholds (derived from code)\n")
        val d = run.derived
        sb.append("- TTC: orange=").append(fmt(d.ttcOrange)).append("s red=").append(fmt(d.ttcRed)).append("s\n")
        sb.append("- Distance: orange=").append(fmt(d.distOrange)).append("m red=").append(fmt(d.distRed)).append("m\n")
        sb.append("- Closing speed: orange=").append(fmt(d.relOrange)).append("m/s red=").append(fmt(d.relRed)).append("m/s\n")
        sb.append("- Risk hysteresis: orangeOn=").append(fmt(d.orangeOn)).append(" orangeOff=").append(fmt(d.orangeOff))
            .append(" redOn=").append(fmt(d.redOn)).append(" redOff=").append(fmt(d.redOff)).append("\n")
        sb.append("- Red combo guard: slopeThr=").append(fmt(d.slopeThr)).append(" strongK=").append(fmt(d.strongK)).append(" midK=").append(fmt(d.midK)).append("\n\n")

        sb.append("## Expectations\n")
        for ((idx, v) in run.verdicts.withIndex()) {
            sb.append(idx + 1).append(") ").append(if (v.ok) "✅" else "❌")
                .append(" **").append(v.rule).append("**\n")
            sb.append("   - ").append(v.details).append("\n")
        }
        sb.append("\n")

        sb.append("## Key alert transitions\n")
        sb.append("| t (s) | event | level | risk | reason | seg | dist(m) | rel(m/s) | ttc(s) | slope | roi | qW |\n")
        sb.append("|---:|---|---:|---:|---|---|---:|---:|---:|---:|---:|---:|\n")

        val transitionEvents = run.events.filter { it.type == "ALERT_ENTER" || it.type == "ALERT_EXIT" }
        for (e in transitionEvents) {
            val seg = e.extra["segment"]?.toString() ?: ""
            val dist = e.extra["distM"] as? Float
            val rel = e.extra["relMps"] as? Float
            val ttc = e.extra["ttcSec"] as? Float
            val slope = e.extra["ttcSlope"] as? Float
            val roi = e.extra["roi"] as? Float
            val qW = e.extra["qW"] as? Float
            val reasonShort = RiskEngine.formatReasonShort(e.reasonBits)
            sb.append("|").append(fmt(e.tSec)).append("|")
                .append(e.type).append("|")
                .append(e.level).append("|")
                .append(fmt(e.risk)).append("|")
                .append(reasonShort.replace("|", "/")).append("|")
                .append(seg.replace("|", "/")).append("|")
                .append(fmt(dist)).append("|")
                .append(fmt(rel)).append("|")
                .append(fmt(ttc)).append("|")
                .append(fmt(slope)).append("|")
                .append(fmt(roi)).append("|")
                .append(fmt(qW)).append("|\n")
        }
        sb.append("\n")

        sb.append("## Segment overview\n")
        for (seg in s.segments.sortedBy { it.tFromSec }) {
            sb.append("- [").append(fmt(seg.tFromSec)).append("–").append(fmt(seg.tToSec)).append("s] ")
                .append(seg.label).append(" – ").append(seg.label).append("\n")
        }
        sb.append("\n")

        sb.append("## Debug notes\n")
        sb.append("- This report is intentionally dual-use: readable by PO, but also includes derived thresholds and reason bits for tuning.\n")
        sb.append("- JSONL file contains structured events and can be grepped / parsed for regression tracking.\n")

        file.writeText(sb.toString())
    }

    fun writeJsonl(run: ScenarioRun, file: File) {
        val sb = StringBuilder(32_000)
        for (e in run.events) {
            sb.append("{")
            sb.append("\"scenario\":\"").append(run.scenario.id).append("\"")
            sb.append(",\"type\":\"").append(e.type).append("\"")
            sb.append(",\"tSec\":").append(fmt(e.tSec))
            sb.append(",\"level\":").append(e.level)
            sb.append(",\"risk\":").append(fmt(e.risk))
            sb.append(",\"reasonBits\":").append(e.reasonBits)
            sb.append(",\"reasonId\":").append(e.reasonId)
            sb.append(",\"reasonShort\":\"").append(escape(RiskEngine.formatReasonShort(e.reasonBits))).append("\"")

            val d = e.derived
            sb.append(",\"derived\":{")
            sb.append("\"mode\":").append(d.mode)
            sb.append(",\"qW\":").append(fmt(d.qualityWeight))
            sb.append(",\"conserv\":").append(fmt(d.conserv))
            sb.append(",\"orangeOn\":").append(fmt(d.orangeOn))
            sb.append(",\"orangeOff\":").append(fmt(d.orangeOff))
            sb.append(",\"redOn\":").append(fmt(d.redOn))
            sb.append(",\"redOff\":").append(fmt(d.redOff))
            sb.append(",\"slopeThr\":").append(fmt(d.slopeThr))
            sb.append(",\"strongK\":").append(fmt(d.strongK))
            sb.append(",\"midK\":").append(fmt(d.midK))
            sb.append("}")

            if (e.extra.isNotEmpty()) {
                sb.append(",\"extra\":{")
                var first = true
                for ((k, v) in e.extra) {
                    if (!first) sb.append(",")
                    first = false
                    sb.append("\"").append(escape(k)).append("\":")
                    when (v) {
                        null -> sb.append("null")
                        is Number -> sb.append(fmtAny(v))
                        is Boolean -> sb.append(if (v) "true" else "false")
                        else -> sb.append("\"").append(escape(v.toString())).append("\"")
                    }
                }
                sb.append("}")
            }

            sb.append("}\n")
        }
        file.writeText(sb.toString())
    }

    private fun fmt(v: Float?): String = when {
        v == null -> ""
        !v.isFinite() -> "NaN"
        else -> String.format("%.3f", v)
    }

    private fun fmtAny(v: Number): String {
        val f = v.toDouble()
        return when {
            f.isNaN() -> "null"
            abs(f) >= 1_000_000 -> String.format("%.0f", f)
            else -> String.format("%.6f", f)
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
