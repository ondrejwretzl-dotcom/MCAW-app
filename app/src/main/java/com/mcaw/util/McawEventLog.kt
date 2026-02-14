package com.mcaw.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MCAW 2.0 – unified ALWAYS-ON event log (one session file).
 *
 * Format (one line):
 * ts_ms=<ms>;src=<SRC>;evt=<EVT>;k=v;...
 *
 * - stable keys (good for parsing + threshold tuning)
 * - cheap to write (append-only)
 */
object McawEventLog {

    private const val SRC_SERVICE = "SERVICE"
    private const val SRC_ANALYZER = "ANALYZER"

    @Volatile
    private var sessionFileName: String? = null

    fun initSession(context: Context) {
        if (sessionFileName != null) return
        synchronized(this) {
            if (sessionFileName != null) return
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())
            sessionFileName = "mcaw_events_${stamp}.txt"
            event(context, src = SRC_SERVICE, evt = "SESSION_START")
        }
    }

    fun event(
        context: Context,
        src: String,
        evt: String,
        fields: Map<String, Any?> = emptyMap()
    ) {
        val file = sessionFileName ?: run {
            initSession(context)
            sessionFileName
        } ?: return

        val ts = System.currentTimeMillis()
        val parts = ArrayList<String>(4 + fields.size)
        parts.add("ts_ms=$ts")
        parts.add("src=${sanitize(src)}")
        parts.add("evt=${sanitize(evt)}")
        for ((k, v) in fields) {
            if (k.isBlank() || v == null) continue
            parts.add("${sanitize(k)}=${sanitize(v.toString())}")
        }
        PublicLogWriter.appendLogLine(context, file, parts.joinToString(";"))
    }

    fun alert(
        context: Context,
        level: Int,
        riskScore: Float,
        reason: String,
        distanceM: Float,
        approachSpeedMps: Float,
        riderSpeedMps: Float,
        ttcSec: Float
    ) {
        val whyKey = extractWhyKey(reason)
        event(
            context,
            src = SRC_ANALYZER,
            evt = "ALERT_LEVEL",
            fields = mapOf(
                "lvl" to level,
                "score" to "%.3f".format(riskScore),
                "why" to whyKey,
                "ttc" to if (ttcSec.isFinite()) "%.2f".format(ttcSec) else null,
                "d" to if (distanceM.isFinite()) "%.1f".format(distanceM) else null,
                "rel" to if (approachSpeedMps.isFinite()) "%.2f".format(approachSpeedMps) else null,
                "rid" to if (riderSpeedMps.isFinite()) "%.2f".format(riderSpeedMps) else null
            )
        )
    }

    fun extractWhyKey(reason: String): String {
        // reason format from RiskEngine: key=...;k=v;...
        val idx = reason.indexOf("key=")
        if (idx < 0) return sanitize(reason.take(32))
        val end = reason.indexOf(';', startIndex = idx)
        val raw = if (end > idx) reason.substring(idx + 4, end) else reason.substring(idx + 4)
        return sanitize(raw.take(48))
    }

    private fun sanitize(s: String): String {
        // Prevent breaking the log schema; keep it readable.
        return s.replace('
', ' ')
            .replace('', ' ')
            .replace(';', ',')
            .replace('=', ':')
            .trim()
    }
}
