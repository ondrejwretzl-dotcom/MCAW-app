package com.mcaw.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

object TraceContract {

    const val COL_TS_MS = "ts_ms"
    const val COL_KIND = "kind"
    const val KIND_TRACK = "TRACK"

    const val COL_LOCKED_ID = "locked_id"
    const val COL_LOCKED_AGE_FRAMES = "locked_age_frames"
    const val COL_LOCKED_MISSES = "locked_misses"
    const val COL_BEST_ID = "best_id"
    const val COL_BEST_SCORE = "best_score"
    const val COL_LOCKED_SCORE = "locked_score"
    const val COL_SWITCH_PENDING = "switch_pending"
    const val COL_SWITCH_COUNT = "switch_count"
    const val COL_GRACE_ACTIVE = "grace_active"
    const val COL_GRACE_REMAINING_MS = "grace_remaining_ms"
    const val COL_BOTTOM_OCCLUDED = "bottom_occluded"
    const val COL_MATCH_IOU = "match_iou"
    const val COL_MATCH_CENTER_DX = "match_center_dx"
    const val COL_MATCH_CENTER_DY = "match_center_dy"
    const val COL_SWITCH_REASON = "switch_reason"

    val HEADER: String = buildString(320) {
        append(COL_TS_MS).append(',')
        append(COL_KIND).append(',')
        append(COL_LOCKED_ID).append(',')
        append(COL_LOCKED_AGE_FRAMES).append(',')
        append(COL_LOCKED_MISSES).append(',')
        append(COL_BEST_ID).append(',')
        append(COL_BEST_SCORE).append(',')
        append(COL_LOCKED_SCORE).append(',')
        append(COL_SWITCH_PENDING).append(',')
        append(COL_SWITCH_COUNT).append(',')
        append(COL_GRACE_ACTIVE).append(',')
        append(COL_GRACE_REMAINING_MS).append(',')
        append(COL_BOTTOM_OCCLUDED).append(',')
        append(COL_MATCH_IOU).append(',')
        append(COL_MATCH_CENTER_DX).append(',')
        append(COL_MATCH_CENTER_DY).append(',')
        append(COL_SWITCH_REASON)
        append('\n')
    }

    fun appendTrackLine(
        sb: StringBuilder,
        tsMs: Long,
        lockedId: Long,
        lockedAgeFrames: Int,
        lockedMisses: Int,
        bestId: Long,
        bestScore: Float,
        lockedScore: Float,
        switchPending: Int,
        switchCount: Int,
        graceActive: Int,
        graceRemainingMs: Long,
        bottomOccluded: Int,
        matchIou: Float,
        matchCenterDx: Float,
        matchCenterDy: Float,
        switchReason: Int
    ) {
        sb.append(tsMs).append(',')
        sb.append(KIND_TRACK).append(',')
        sb.append(lockedId).append(',')
        sb.append(lockedAgeFrames).append(',')
        sb.append(lockedMisses).append(',')
        sb.append(bestId).append(',')
        appendFloat(sb, bestScore, 3); sb.append(',')
        appendFloat(sb, lockedScore, 3); sb.append(',')
        sb.append(switchPending).append(',')
        sb.append(switchCount).append(',')
        sb.append(graceActive).append(',')
        sb.append(graceRemainingMs).append(',')
        sb.append(bottomOccluded).append(',')
        appendFloat(sb, matchIou, 3); sb.append(',')
        appendFloat(sb, matchCenterDx, 3); sb.append(',')
        appendFloat(sb, matchCenterDy, 3); sb.append(',')
        sb.append(switchReason)
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
