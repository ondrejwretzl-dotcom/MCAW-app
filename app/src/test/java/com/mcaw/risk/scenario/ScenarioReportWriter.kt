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
        val doc = s.doc ?: deriveDocFromLegacy(s)

        // Snapshot critical params from code. Keep deterministic: scenario default qualityWeight.
        val dynEnabled = s.config.dynamicDistanceEnabled ?: com.mcaw.config.AppPreferences.dynamicDistanceThresholdEnabled
        val dynRedSec = s.config.dynamicDistanceRedSec ?: com.mcaw.config.AppPreferences.dynamicDistanceRedSec
        val dynOrangeSec = s.config.dynamicDistanceOrangeSec ?: com.mcaw.config.AppPreferences.dynamicDistanceOrangeSec
        val engine = RiskEngine()
        val snap = RiskParamSnapshot.snapshot(
            engine = engine,
            effectiveMode = s.config.effectiveMode,
            qualityWeight = s.config.qualityWeight,
            dynamicDistanceEnabled = dynEnabled,
            dynamicDistanceOrangeSec = dynOrangeSec,
            dynamicDistanceRedSec = dynRedSec
        )

        val expectedMaxLevel = doc.expected.expectedAlertLevelMax

        val sb = StringBuilder(22_000)
        sb.append("# MCAW 2.0 – Report simulace scénáře\n\n")
        sb.append("- Vygenerováno: ").append(dt).append("\n")
        sb.append("- Scénář: **").append(s.id).append(" – ").append(s.title).append("**\n")
        sb.append("- Doména: ").append(s.domain).append(" | Vozidlo: ").append(s.vehicle).append("\n")
        sb.append("- Výsledek: ").append(if (pass) "✅ PROŠEL" else "❌ NEPROŠEL").append("\n\n")

        sb.append("## 1. Účel testu\n")
        sb.append("- ").append(doc.purpose.trim()).append("\n")
        sb.append("- Riziko při rozbití: ").append(doc.riskIfBroken.trim()).append("\n\n")

        sb.append("## 2. Očekávání\n")
        sb.append("| Položka | Očekávání |\n")
        sb.append("|---|---|\n")
        sb.append("| max alertLevel | ").append(doc.expected.expectedAlertLevelMax).append(" |\n")
        sb.append("| riskState | ").append(doc.expected.expectedRiskState).append(" |\n")
        sb.append("| okno platnosti | ").append(escapePipes(doc.expected.constraintWindow)).append(" |\n")
        sb.append("| regrese | ").append(doc.regressionType).append(" |\n")
        sb.append("| závažnost | ").append(doc.severity).append(" |\n\n")

        sb.append("## 3. Vstupy scénáře\n")
        sb.append("| Parametr | Hodnota |\n")
        sb.append("|---|---:|\n")
        sb.append("| effectiveMode | ").append(s.config.effectiveMode).append(" |\n")
        sb.append("| hz | ").append(s.config.hz).append(" |\n")
        sb.append("| riderSpeedMps | ").append(fmt(s.config.riderSpeedMps)).append(" |\n")
        sb.append("| qualityWeight | ").append(fmt(s.config.qualityWeight)).append(" |\n")
        sb.append("| roiContainment | ").append(fmt(s.config.roiContainment)).append(" |\n")
        sb.append("| egoOffsetN | ").append(fmt(s.config.egoOffsetN)).append(" |\n")
        sb.append("| leanDeg | ").append(if (s.config.leanDeg.isFinite()) fmt(s.config.leanDeg) else "NaN").append(" |\n")
        sb.append("| approachSpeed source | ").append(if (s.config.deriveRelFromDistance) "derived_from_distance_ema" else "segment_legacy").append(" |\n\n")

        sb.append("## 4. Kritické parametry RiskEngine\n")
        sb.append("| Klíč | Hodnota (snapshot) | Poznámka |\n")
        sb.append("|---|---:|---|\n")
        val critical = if (s.criticalParams.isNotEmpty()) s.criticalParams else defaultCriticalParams()
        for (p in critical) {
            sb.append("|").append(escapePipes(p.key)).append("|")
                .append(escapePipes(RiskParamSnapshot.valueOrNa(snap, p.key))).append("|")
                .append(escapePipes(p.note)).append("|\n")
        }
        sb.append("\n")

        sb.append("## 5. Skutečný výstup enginu\n")
        val maxLevel = run.levels.maxOrNull() ?: 0
        val firstOrange = run.events.firstOrNull { it.type == "ALERT_ENTER" && it.level >= 1 }
        val firstRed = run.events.firstOrNull { it.type == "ALERT_ENTER" && it.level >= 2 }
        sb.append("| Field | Hodnota |\n")
        sb.append("|---|---|\n")
        sb.append("| maxLevel | ").append(maxLevel).append(" |\n")
        sb.append("| firstOrangeSec | ").append(firstOrange?.tSec?.let { fmt(it) } ?: "—").append(" |\n")
        sb.append("| firstRedSec | ").append(firstRed?.tSec?.let { fmt(it) } ?: "—").append(" |\n")
        sb.append("| topReason (first enter) | ").append(firstOrange?.let { RiskEngine.formatReasonShort(it.reasonBits) } ?: "—").append(" |\n\n")

        sb.append("## 6. Kontroly (expectations)\n")
        for ((idx, v) in run.verdicts.withIndex()) {
            sb.append(idx + 1).append(") ").append(if (v.ok) "✅" else "❌")
                .append(" **").append(v.rule).append("**\n")
            sb.append("   - ").append(v.details).append("\n")
        }
        sb.append("\n")

        sb.append("## 7. Analýza selhání\n")
        if (pass) {
            sb.append("- OK (žádné selhání).\n\n")
        } else {
            val primary = failed.first()
            sb.append("- Neplatí: ").append(primary.rule).append("\n")
            sb.append("- Detail: ").append(primary.details).append("\n")
            sb.append("- Očekávání: maxLevel ≤ ").append(expectedMaxLevel).append("\n")
            sb.append("- Skutečnost: maxLevel = ").append(maxLevel).append("\n")

            val trigger = run.events.firstOrNull { it.type == "ALERT_ENTER" && it.level > expectedMaxLevel }
            if (trigger != null) {
                val dist = trigger.extra["distM"] as? Float
                val rel = trigger.extra["relMps"] as? Float
                val ttc = trigger.extra["ttcSec"] as? Float
                val slope = trigger.extra["ttcSlope"] as? Float
                val roi = trigger.extra["roi"] as? Float
                val qW = trigger.extra["qW"] as? Float

                sb.append("\nTrigger (první porušení):\n")
                sb.append("- tSec=").append(fmt(trigger.tSec))
                    .append(" level=").append(trigger.level)
                    .append(" risk=").append(fmt(trigger.risk))
                    .append(" reason=").append(RiskEngine.formatReasonShort(trigger.reasonBits))
                    .append("\n")
                sb.append("- distM=").append(fmt(dist))
                    .append(" relMps=").append(fmt(rel))
                    .append(" ttcSec=").append(fmt(ttc))
                    .append(" slope=").append(fmt(slope))
                    .append(" roi=").append(fmt(roi))
                    .append(" qW=").append(fmt(qW))
                    .append("\n")

                // Threshold cue (explicit numbers)
                sb.append("\nPrahy relevantní k triggeru:\n")
                sb.append("- thr.ttc.orangeSec=").append(RiskParamSnapshot.valueOrNa(snap, "thr.ttc.orangeSec"))
                    .append(" thr.ttc.redSec=").append(RiskParamSnapshot.valueOrNa(snap, "thr.ttc.redSec")).append("\n")
                sb.append("- thr.dist.orangeM=").append(RiskParamSnapshot.valueOrNa(snap, "thr.dist.orangeM"))
                    .append(" thr.dist.redM=").append(RiskParamSnapshot.valueOrNa(snap, "thr.dist.redM")).append("\n")
                sb.append("- thr.approach.orangeMps=").append(RiskParamSnapshot.valueOrNa(snap, "thr.approach.orangeMps"))
                    .append(" thr.approach.redMps=").append(RiskParamSnapshot.valueOrNa(snap, "thr.approach.redMps")).append("\n")
            }
            sb.append("\nKlasifikace regrese:\n")
            sb.append("- ").append(doc.regressionType).append(" (závažnost: ").append(doc.severity).append(")\n\n")
        }

        sb.append("## 8. Klíčové přechody alertů\n")
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

        sb.append("## 9. Přehled segmentů\n")
        for (seg in s.segmentsForReport().sortedBy { it.tFromSec }) {
            sb.append("- [").append(fmt(seg.tFromSec)).append("–").append(fmt(seg.tToSec)).append("s] ")
                .append(seg.name).append(" – ").append(seg.name).append("\n")
        }
        sb.append("\n")

        sb.append("## 10. Poznámky\n")
        sb.append("- Report je deterministický a auditovatelný: obsahuje snapshot prahů z kódu a trigger při FAIL.\n")
        sb.append("- JSONL eventy jsou určené pro grep/parsing a baseline diff (CI).\n")

        file.writeText(sb.toString())
    }

    private fun deriveDocFromLegacy(s: ScenarioMeta): ScenarioDoc {
        val group = s.id.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val expectedMaxLevel = deriveExpectedMaxLevel(s.expectations)
        val expectedState = when (expectedMaxLevel) {
            0 -> "SAFE"
            1 -> "CAUTION"
            else -> "CRITICAL"
        }
        val window = s.expectations.filterIsInstance<Expectation.MaxTransitionsInWindow>().firstOrNull()
            ?.let { "okno ${fmt(it.windowSec)}s" }
            ?: "nikdy"

        val (type, sev) = when (group) {
            "A" -> RegressionType.FALSE_POSITIVE to Severity.HIGH
            "B" -> RegressionType.FALSE_NEGATIVE to Severity.HIGH
            "C" -> RegressionType.FALSE_POSITIVE to Severity.HIGH
            "D" -> RegressionType.FALSE_NEGATIVE to Severity.HIGH
            "E" -> RegressionType.STABILITY to Severity.MED
            else -> RegressionType.STABILITY to Severity.MED
        }

        val purpose = if (s.title.isNotBlank()) s.title.trim() else s.notes.trim().lineSequence().firstOrNull().orEmpty()
        val riskIfBroken = when (type) {
            RegressionType.FALSE_POSITIVE -> "Falešné varování (zhoršení UX / ztráta důvěry)"
            RegressionType.FALSE_NEGATIVE -> "Pozdní/žádné varování (riziko kolize)"
            RegressionType.STABILITY -> "Nestabilní přechody (blikání / cvakání ORANGE/RED)"
            RegressionType.PERFORMANCE -> "Zhoršení výkonu (nežádoucí režie)"
        }

        return ScenarioDoc(
            purpose = purpose,
            riskIfBroken = riskIfBroken,
            expected = ExpectedBehaviorDoc(
                expectedAlertLevelMax = expectedMaxLevel,
                expectedRiskState = expectedState,
                constraintWindow = window
            ),
            regressionType = type,
            severity = sev
        )
    }

    private fun deriveExpectedMaxLevel(expectations: List<Expectation>): Int {
        // Nejčastější kontrakt: "nesmí vstoupit do level X".
        val mustNot = expectations.filterIsInstance<Expectation.MustNotEnterLevel>().map { it.level }
        return when {
            mustNot.contains(1) -> 0
            mustNot.contains(2) -> 1
            else -> 2
        }
    }

    private fun defaultCriticalParams(): List<CriticalParamRef> {
        return listOf(
            CriticalParamRef("thr.ttc.orangeSec"),
            CriticalParamRef("thr.ttc.redSec"),
            CriticalParamRef("thr.dist.orangeM"),
            CriticalParamRef("thr.dist.redM"),
            CriticalParamRef("thr.approach.orangeMps"),
            CriticalParamRef("thr.approach.redMps"),
            CriticalParamRef("thr.risk.orangeOn"),
            CriticalParamRef("thr.risk.orangeOff"),
            CriticalParamRef("thr.risk.redOn"),
            CriticalParamRef("thr.risk.redOff"),
            CriticalParamRef("guard.redCombo.slopeThr"),
            CriticalParamRef("guard.redCombo.strongK"),
            CriticalParamRef("guard.redCombo.midK")
        )
    }

    private fun escapePipes(s: String): String = s.replace("|", "/")

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
