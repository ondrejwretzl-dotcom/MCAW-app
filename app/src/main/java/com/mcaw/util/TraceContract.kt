package com.mcaw.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * DEBUG trace log contract (CSV).
 *
 * Zásady:
 * - volitelné (zapíná se v Settings -> Debug trace log)
 * - stále throttled + mimo analyzer thread
 * - pole jsou stabilní, vhodné pro ladění target selection / lock switching
 */
object TraceContract {

    const val COL_TS_MS = "ts_ms"
    const val COL_KIND = "kind"            // 0=sample, 1=transition
    const val COL_LOCKED_ID = "locked_id"
    const val COL_BEST_ID = "best_id"
    const val COL_BEST_PRI = "best_pri"
    const val COL_LOCKED_PRI = "locked_pri"
    const val COL_CAND_ID = "cand_id"
    const val COL_CAND_COUNT = "cand_count"
    const val COL_ALERT_LEVEL = "alert_level"
    const val COL_MODE = "mode"

    val HEADER: String = buildString(128) {
        append(COL_TS_MS).append(',')
        append(COL_KIND).append(',')
        append(COL_LOCKED_ID).append(',')
        append(COL_BEST_ID).append(',')
        append(COL_BEST_PRI).append(',')
        append(COL_LOCKED_PRI).append(',')
        append(COL_CAND_ID).append(',')
        append(COL_CAND_COUNT).append(',')
        append(COL_ALERT_LEVEL).append(',')
        append(COL_MODE)
        append('\n')
    }

    fun appendTargetLine(
        sb: StringBuilder,
        tsMs: Long,
        kind: Int,
        lockedId: Long,
        bestId: Long,
        bestPri: Float,
        lockedPri: Float,
        candId: Long,
        candCount: Int,
        alertLevel: Int,
        mode: Int
    ) {
        sb.append(tsMs).append(',')
        sb.append(kind).append(',')
        sb.append(lockedId).append(',')
        sb.append(bestId).append(',')
        appendFloat(sb, bestPri, 3); sb.append(',')
        appendFloat(sb, lockedPri, 3); sb.append(',')
        sb.append(candId).append(',')
        sb.append(candCount).append(',')
        sb.append(alertLevel).append(',')
        sb.append(mode)
        sb.append('\n')
    }

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
        val fracStr = fracPart.toString()
        val zeros = d - fracStr.length
        repeat(zeros.coerceAtLeast(0)) { sb.append('0') }
        sb.append(fracStr)
    }
}
