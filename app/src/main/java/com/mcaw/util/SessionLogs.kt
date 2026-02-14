package com.mcaw.util

import android.content.Context
import com.mcaw.config.AppPreferences

/**
 * Centralized per-process session logging.
 *
 * Goals:
 * - exactly one ALWAYS-ON CSV event log file per app run (session)
 * - optional trace log file (debug)
 * - no file churn from UI/service helpers
 * - loggers are created once and reused (camera/service restarts won't create new files)
 */
object SessionLogs {

    @Volatile
    private var initialized = false

    // stable session stamp for this process
    val sessionStamp: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(System.currentTimeMillis())
    }

    private var eventLogger: SessionEventLogger? = null
    private var traceLogger: SessionTraceLogger? = null

    val eventFileName: String get() = "mcaw_event_${sessionStamp}.csv"
    val traceFileName: String get() = "mcaw_trace_${sessionStamp}.csv"

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext

            eventLogger = SessionEventLogger(appCtx, eventFileName).also { it.start() }

            traceLogger = if (AppPreferences.debugTrace) {
                SessionTraceLogger(appCtx, traceFileName).also { it.start() }
            } else null

            initialized = true
        }
    }

    fun close() {
        synchronized(this) {
            eventLogger?.close()
            eventLogger = null
            traceLogger?.close()
            traceLogger = null
            initialized = false
        }
    }

    /**
     * Analyzer-safe: no allocations besides passing primitives.
     */
    fun logEvent(
        tsMs: Long,
        risk: Float,
        level: Int,
        state: com.mcaw.risk.RiskEngine.State,
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
        lockedId: Long
    ) {
        eventLogger?.logEvent(
            tsMs,
            risk,
            level,
            state,
            reasonBits,
            ttcSec,
            distM,
            relV,
            roi,
            qualityPoor,
            cutIn,
            brake,
            egoBrake,
            mode,
            lockedId
        )
    }

    fun logTarget(
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
        traceLogger?.logTarget(
            tsMs,
            kind,
            lockedId,
            bestId,
            bestPri,
            lockedPri,
            candId,
            candCount,
            alertLevel,
            mode
        )
    }
}
