package com.mcaw.util

import android.content.Context

/**
 * Single per-run log file name + one-time unified header init.
 *
 * Goal: exactly ONE log file per app use (process lifetime), used by:
 * - service lifecycle logs (S, ...): S,<ts_ms>,<message>
 * - ALWAYS-ON event CSV (E, ...):   E,<LogContract columns...>
 * - optional debug trace CSV (T, ...): T,<TraceContract columns...>
 *
 * File is written to Downloads/MCAW via PublicLogWriter.
 */
object SessionLogFile {
    // Keep stable across the whole process lifetime.
    val fileName: String by lazy { "mcaw_${SessionStamp.value}.csv" }

    @Volatile
    private var initialized: Boolean = false

    /**
     * Writes unified header block once per process lifetime.
     * Safe to call multiple times (no-op after first).
     *
     * Important: uses appendLogLine to avoid creating duplicate MediaStore entries.
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        val tsMs = System.currentTimeMillis()
        PublicLogWriter.appendLogLine(context, fileName, "# MCAW unified session log")
        PublicLogWriter.appendLogLine(context, fileName, "# created_ts_ms=$tsMs")
        PublicLogWriter.appendLogLine(context, fileName, "# format: S=service, E=event, T=trace")
        PublicLogWriter.appendLogLine(context, fileName, "# S: S,ts_ms,message")
        // Prefixed CSV headers for easy single-file parsing:
        PublicLogWriter.appendLogLine(context, fileName, "E," + LogContract.HEADER.trimEnd())
        PublicLogWriter.appendLogLine(context, fileName, "T," + TraceContract.HEADER.trimEnd())
    }
}
