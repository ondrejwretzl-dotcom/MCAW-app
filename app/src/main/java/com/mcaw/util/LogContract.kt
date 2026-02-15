package com.mcaw.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * MCAW 2.0 – LogContract (stable CSV schema)
 *
 * Zásady:
 * - reason_bits je primární auditovatelný kontrakt (stabilní napříč verzemi)
 * - textové WHY je odvozenina (typicky při čtení logu / debug overlay)
 * - CSV je "flat" pro snadnou analýzu a spojení s videem
 *
 * Pozn.: Commit A1 zavádí pouze kontrakt + helpery.
 * V commitu A2 se napojí sampled+transitions "event log" s buffered zápisem mimo frame loop.
 */
object LogContract {

    // --- CSV columns (stable order) ---
    const val COL_TS_MS = "ts_ms"            // wall time (ms since epoch)
    const val COL_RISK = "risk"              // 0..1
    const val COL_LEVEL = "level"            // 0/1/2
    const val COL_STATE = "state"            // SAFE/CAUTION/CRITICAL
    const val COL_REASON_BITS = "reason_bits"

    const val COL_TTC = "ttc"                // seconds
    const val COL_DIST = "dist"              // meters
    const val COL_REL_V = "rel_v"            // m/s (approach speed, >=0)

    const val COL_ROI = "roi"                // 0..1 (containment / weight)
    const val COL_QUALITY = "quality"        // 0/1 (0=ok, 1=poor)
    const val COL_CUTIN = "cutin"            // 0/1
    const val COL_BRAKE = "brake"            // 0/1 (brake cue)
    const val COL_EGO_BRAKE = "ego_brake"    // 0..1 (IMU confidence)

    const val COL_MODE = "mode"              // effective mode (Auto resolved before log)
    const val COL_LOCKED_ID = "locked_id"    // stable trackId / lock id

    // Optional but very useful for audit & sync with video: what object was actually used for risk.
    // Kept at the end to keep backward CSV compatibility for existing parsers.
    const val COL_LABEL = "label"            // canonical label of locked target (quoted CSV)
    const val COL_DET_SCORE = "det_score"    // detector confidence 0..1

    /**
     * Stabilní hlavička CSV – vždy první řádek souboru.
     */
    val HEADER: String = buildString(256) {
        append(COL_TS_MS).append(',')
        append(COL_RISK).append(',')
        append(COL_LEVEL).append(',')
        append(COL_STATE).append(',')
        append(COL_REASON_BITS).append(',')
        append(COL_TTC).append(',')
        append(COL_DIST).append(',')
        append(COL_REL_V).append(',')
        append(COL_ROI).append(',')
        append(COL_QUALITY).append(',')
        append(COL_CUTIN).append(',')
        append(COL_BRAKE).append(',')
        append(COL_EGO_BRAKE).append(',')
        append(COL_MODE).append(',')
        append(COL_LOCKED_ID).append(',')
        append(COL_LABEL).append(',')
        append(COL_DET_SCORE)
        append('\n')
    }

    /**
     * Append jednoho CSV řádku do existujícího StringBuilderu (pro re-use mimo frame loop).
     *
     * Formát:
     * - floaty: fixní desetinná místa (default 3), NaN/INF => prázdné pole
     * - bool: 0/1
     * - state: bez uvozovek (SAFE/CAUTION/CRITICAL)
     */
    fun appendEventLine(
        sb: StringBuilder,
        tsMs: Long,
        risk: Float,
        level: Int,
        state: String,
        reasonBits: Int,
        ttcSec: Float,
        distM: Float,
        relV: Float,
        roi: Float,
        qualityPoor: Boolean,
        cutIn: Boolean,
        brake: Boolean,
        egoBrake: Float,
        mode: Int,
        lockedId: Long,
        label: String,
        detScore: Float
    ) {
        sb.append(tsMs).append(',')
        appendFloat(sb, risk, 3); sb.append(',')
        sb.append(level.coerceIn(0, 2)).append(',')
        sb.append(state).append(',')
        sb.append(reasonBits).append(',')

        appendFloat(sb, ttcSec, 3); sb.append(',')
        appendFloat(sb, distM, 3); sb.append(',')
        appendFloat(sb, relV, 3); sb.append(',')

        appendFloat(sb, roi, 3); sb.append(',')
        sb.append(if (qualityPoor) 1 else 0).append(',')
        sb.append(if (cutIn) 1 else 0).append(',')
        sb.append(if (brake) 1 else 0).append(',')

        appendFloat(sb, egoBrake, 3); sb.append(',')
        sb.append(mode).append(',')
        sb.append(lockedId).append(',')
        appendCsvString(sb, label); sb.append(',')
        appendFloat(sb, detScore, 3)

        sb.append('\n')
    }

    private fun appendCsvString(sb: StringBuilder, value: String) {
        // Always quote. Escape quotes by doubling them. Replace CR/LF with spaces.
        sb.append('"')
        for (i in value.indices) {
            val c = value[i]
            when (c) {
                '"' -> sb.append("\"\"")
                '\n', '\r' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /**
     * Minimalistické, alokačně úsporné float formatování bez String.format().
     * - NaN/INF => prázdné pole
     * - jinak fixní decimals (0..6)
     */
    private fun appendFloat(sb: StringBuilder, v: Float, decimals: Int) {
        if (!v.isFinite()) return
        val d = decimals.coerceIn(0, 6)
        if (d == 0) {
            sb.append(v.roundToInt())
            return
        }

        val sign = if (v < 0f) "-" else ""
        val av = abs(v)
        val scale = 10f.pow(d)
        val scaled = (av * scale).roundToInt()

        val intPart = scaled / scale.toInt()
        val fracPart = scaled - intPart * scale.toInt()

        sb.append(sign)
        sb.append(intPart)

        sb.append('.')
        // leading zeros for fractional part
        val fracStr = fracPart.toString()
        val zeros = d - fracStr.length
        repeat(zeros.coerceAtLeast(0)) { sb.append('0') }
        sb.append(fracStr)
    }
}
