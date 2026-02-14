package com.mcaw.util

/**
 * Single per-run log file name.
 *
 * Goal: exactly ONE log file per app use (process lifetime), used by:
 * - service lifecycle logs (S, ...)
 * - ALWAYS-ON event CSV (E, ...)
 * - optional debug trace CSV (T, ...)
 *
 * File is created in Downloads/MCAW via PublicLogWriter.
 */
object SessionLogFile {
    // Keep stable across the whole process lifetime.
    val fileName: String by lazy { "mcaw_${SessionStamp.value}.csv" }
}
