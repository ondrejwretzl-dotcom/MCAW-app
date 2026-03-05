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

        val failed = run.verdicts.filterNot { it.ok }

        val sb = StringBuilder(16_000)
        sb.append("# MCAW 2.0 – Report simulace scénáře\n\n")
        sb.append("- Vygenerováno: ").append(dt).append("\n")
        sb.append("- Scénář: **").append(s.id).append(" – ").append(s.title).append("**\n")
        sb.append("- Doména: ").append(s.domain).append(" | Vozidlo: ").append(s.vehicle).append("\n")
        sb.append("- Výsledek: ").append(if (pass) "✅ PROŠEL" else "❌ NEPROŠEL").append("\n\n")

        sb.append("## Rychlé shrnutí\n")
        if (pass) {
            sb.append("- Scénář splnil všechna očekávání.\n")
        } else {
            sb.append("- Scénář **nesplnil** ").append(failed.size).append(" očekávání.\n")
            sb.append("- Důvody (zkráceně):\n")
            for (v in failed.take(3)) {
                sb.append("  - ").append(shorten(v.details)).append("\n")
            }
            if (failed.size > 3) sb.append("  - …\n")
        }
        sb.append("\n")

        sb.append("## Spec (co tento test hlídá)\n")
        sb.append("- **Účel:** ").append(s.doc.purpose).append("\n")
        sb.append("- **Riziko při rozbití:** ").append(s.doc.riskIfBroken).append("\n")
        sb.append("- **Typ regrese:** ").append(s.doc.regressionType).append(" | **Závažnost:** ").append(s.doc.severity).append("\n")
        sb.append("- **Očekávání (shrnutí):** maxLevel=").append(s.doc.expected.alertLevelMax)
            .append(", state=").append(s.doc.expected.expectedState)
        s.doc.expected.constraintWindowSec?.let { sb.append(", okno=").append(fmt(it)).append("s") }
        sb.append("\n")
        sb.append("- **Povolené přechody:** ").append(s.doc.expected.allowedTransitions).append("\n")
        if (s.doc.notes.isNotBlank()) {
            sb.append("- **Pozn.:** ").append(s.doc.notes).append("\n")
        }
        sb.append("\n")

        sb.append("## Konfigurace scénáře (efektivní vstupy)\n")
        sb.append("- effectiveMode: **").append(s.config.effectiveMode).append("**\n")
        sb.append("- hz: ").append(s.config.hz).append("\n")
        sb.append("- riderSpeedMps: ").append(fmt(s.config.riderSpeedMps)).append("\n")
        sb.append("- qualityWeight (default): ").append(fmt(s.config.qualityWeight)).append("\n")
        sb.append("- roiContainment (default): ").append(fmt(s.config.roiContainment)).append("\n")
        sb.append("- egoOffsetN (default): ").append(fmt(s.config.egoOffsetN)).append("\n")
        sb.append("- leanDeg (default): ").append(if (s.config.leanDeg.isFinite()) fmt(s.config.leanDeg) else "NaN").append("\n")
        sb.append("- approachSpeed source: ").append(if (s.config.deriveRelFromDistance) "derived_from_distance_ema" else "segment_legacy").append("\n\n")

        sb.append("## Prahy enginu (odvozeno z kódu)\n")
        val d = run.derived
        sb.append("- TTC: ORANGE=").append(fmt(d.ttcOrange)).append("s RED=").append(fmt(d.ttcRed)).append("s\n")
        sb.append("- Vzdálenost: ORANGE=").append(fmt(d.distOrange)).append("m RED=").append(fmt(d.distRed)).append("m\n")
        sb.append("- Přibližování: ORANGE=").append(fmt(d.relOrange)).append("m/s RED=").append(fmt(d.relRed)).append("m/s\n")
        sb.append("- Hystereze risku: orangeOn=").append(fmt(d.orangeOn)).append(" orangeOff=").append(fmt(d.orangeOff))
            .append(" redOn=").append(fmt(d.redOn)).append(" redOff=").append(fmt(d.redOff)).append("\n")
        sb.append("- RED combo guard: slopeThr=").append(fmt(d.slopeThr)).append(" strongK=").append(fmt(d.strongK)).append(" midK=").append(fmt(d.midK)).append("\n\n")

        sb.append("## Kritické parametry (co může změnit ORANGE/RED)\n")
        val crit = RiskParamSnapshot.fmtCriticalParams(s.doc.criticalParams, d)
        if (crit.isEmpty()) {
            sb.append("- (Neuvedeno)\n\n")
        } else {
            sb.append("| klíč | hodnota (z kódu) |\n|---|---|\n")
            for ((k, v) in crit) {
                sb.append("|").append(k).append("|").append(v).append("|\n")
            }
            sb.append("\n")
        }

        sb.append("## Očekávání\n")
        for ((idx, v) in run.verdicts.withIndex()) {
            sb.append(idx + 1).append(") ").append(if (v.ok) "✅" else "❌")
                .append(" **").append(v.rule).append("**\n")
            sb.append("   - ").append(v.details).append("\n")
        }
        sb.append("\n")

        sb.append("## Proč (diagnostika při FAIL)\n")
        if (pass) {
            sb.append("- (Scénář prošel – diagnostika není potřeba.)\n\n")
        } else {
            val maxLevel = run.levels.maxOrNull() ?: 0
            val firstOrange = firstTimeAtOrAbove(run, 1)
            val firstRed = firstTimeAtOrAbove(run, 2)
            val lastEvent = run.events.lastOrNull { it.type == "ALERT_ENTER" || it.type == "ALERT_EXIT" }

            sb.append("- **Aktuální:** maxLevel=").append(maxLevel)
                .append(", firstORANGE=").append(fmtNullable(firstOrange))
                .append("s, firstRED=").append(fmtNullable(firstRed)).append("s\n")
            sb.append("- **Očekávané:** maxLevel<=").append(s.doc.expected.alertLevelMax)
                .append(", state=").append(s.doc.expected.expectedState).append("\n")

            val firstFail = failed.firstOrNull()
            if (firstFail != null) {
                sb.append("- **První nesplněné pravidlo:** ").append(firstFail.rule).append("\n")
                sb.append("  - detail: ").append(firstFail.details).append("\n")
            }

            val enter = run.events.firstOrNull { it.type == "ALERT_ENTER" && it.level > s.doc.expected.alertLevelMax }
            if (enter != null) {
                sb.append("- **Trigger event:** t=").append(fmt(enter.tSec)).append("s level=").append(enter.level)
                    .append(" risk=").append(fmt(enter.risk)).append(" reason=")
                    .append(RiskEngine.formatReasonShort(enter.reasonBits)).append("\n")
                val dist = enter.extra["distM"] as? Float
                val rel = enter.extra["relMps"] as? Float
                val ttc = enter.extra["ttcSec"] as? Float
                val slope = enter.extra["ttcSlope"] as? Float
                sb.append("  - vstupy: dist=").append(fmt(dist)).append("m rel=").append(fmt(rel)).append("m/s ttc=").append(fmt(ttc)).append("s slope=").append(fmt(slope)).append("\n")
                sb.append("  - prahy: orangeOn=").append(fmt(d.orangeOn)).append(" redOn=").append(fmt(d.redOn)).append("\n")
            } else if (lastEvent != null) {
                sb.append("- **Poslední přechod alertu:** t=").append(fmt(lastEvent.tSec)).append("s ").append(lastEvent.type)
                    .append(" level=").append(lastEvent.level)
                    .append(" reason=").append(RiskEngine.formatReasonShort(lastEvent.reasonBits)).append("\n")
            }

            sb.append("\n")
        }

        sb.append("## Klíčové přechody alertů\n")
        sb.append("| t (s) | událost | level | risk | důvod | segment | dist(m) | rel(m/s) | ttc(s) | slope | roi | qW |\n")
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

        if (transitionEvents.isEmpty()) {
            sb.append("- (Žádné přechody alertů v tomto běhu – tabulka je prázdná.)\n")
        }
        sb.append("\n")

        sb.append("## Přehled segmentů\n")
        for (seg in s.segmentsForReport().sortedBy { it.tFromSec }) {
            sb.append("- [").append(fmt(seg.tFromSec)).append("–").append(fmt(seg.tToSec)).append("s] ")
                .append(seg.name).append(" – ").append(seg.name).append("\n")
        }
        sb.append("\n")

        sb.append("## Poznámky pro ladění\n")
        sb.append("- Report je dual-use: stručný pro člověka, ale obsahuje prahy odvozené z kódu a reason bits pro audit.\n")
        sb.append("- Pokud upravíte některý z \"kritických parametrů\", očekávejte změnu ORANGE/RED a aktualizujte baseline vědomě.\n")
        sb.append("- JSONL eventy jsou strojově čitelné a kompatibilní se simulátorem (sim) i log_analyzer exportem.\n")

        file.writeText(sb.toString())
    }

    private fun firstTimeAtOrAbove(run: ScenarioRun, level: Int): Float? {
        for (i in run.levels.indices) {
            if (run.levels[i] >= level) return run.frames.getOrNull(i)?.tSec
        }
        return null
    }

    private fun fmtNullable(v: Float?): String {
        return if (v == null) "—" else fmt(v)
    }

    fun writeFrameTraceJsonl(run: ScenarioRun, file: File) {
        val sb = StringBuilder(64_000)
        for (e in run.frameTraceEvents) {
            sb.append("{")
            sb.append("\"scenario\":\"").append(run.scenario.id).append("\"")
            sb.append(",\"type\":\"").append(e.type).append("\"")
            sb.append(",\"tSec\":").append(fmt(e.tSec))

            sb.append(",\"in\":{")
            sb.append("\"effectiveMode\":").append(e.input.effectiveMode)
            sb.append(",\"distanceM\":").append(fmt(e.input.distanceM))
            sb.append(",\"distanceConfidence\":").append(fmt(e.input.distanceConfidence))
            sb.append(",\"approachSpeedMps\":").append(fmt(e.input.approachSpeedMps))
            sb.append(",\"ttcSec\":").append(fmt(e.input.ttcSec))
            sb.append(",\"ttcSlopeSecPerSec\":").append(fmt(e.input.ttcSlopeSecPerSec))
            sb.append(",\"roiContainment\":").append(fmt(e.input.roiContainment))
            sb.append(",\"egoOffsetN\":").append(fmt(e.input.egoOffsetN))
            sb.append(",\"cutInActive\":").append(if (e.input.cutInActive) "true" else "false")
            sb.append(",\"brakeCueActive\":").append(if (e.input.brakeCueActive) "true" else "false")
            sb.append(",\"brakeCueStrength\":").append(fmt(e.input.brakeCueStrength))
            sb.append(",\"occlusionCloseFactor\":").append(fmt(e.input.occlusionCloseFactor))
            sb.append(",\"occlusionCloseEligible\":").append(if (e.input.occlusionCloseEligible) "true" else "false")
            sb.append(",\"qualityWeight\":").append(fmt(e.input.qualityWeight))
            sb.append(",\"riderSpeedMps\":").append(fmt(e.input.riderSpeedMps))
            sb.append(",\"riderSpeedConfidence\":").append(fmt(e.input.riderSpeedConfidence))
            sb.append(",\"egoBrakingConfidence\":").append(fmt(e.input.egoBrakingConfidence))
            if (e.input.leanDeg.isFinite()) {
                sb.append(",\"leanDeg\":").append(fmt(e.input.leanDeg))
            } else {
                sb.append(",\"leanDeg\":null")
            }
            sb.append("}")

            sb.append(",\"out\":{")
            sb.append("\"level\":").append(e.output.level)
            sb.append(",\"riskScore\":").append(fmt(e.output.riskScore))
            sb.append(",\"reasonBits\":").append(e.output.reasonBits)
            sb.append("}")

            val d = e.derived
            if (d != null) {
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
            }

            sb.append("}\n")
        }
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

    private fun shorten(s: String): String {
        val t = s.trim().replace("\n", " ").replace("  ", " ")
        return if (t.length <= 180) t else t.take(177) + "…"
    }
}
