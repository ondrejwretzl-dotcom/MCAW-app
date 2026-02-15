package com.mcaw.util

import android.content.Context

/**
 * Single unified per-run CSV log file name.
 *
 * Goals:
 * - one file per app "run" (process lifetime) for easy analysis
 * - stable name accessible from any module (UI/service/analyzer)
 * - no IO here; only naming
 */
object SessionLogFile {

    @Volatile
    private var inited = false

    @Volatile
    var fileName: String = "mcaw_${System.currentTimeMillis()}.csv"
        private set

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            // Use stable human-readable stamp for syncing with video and log browsing.
            fileName = "mcaw_${SessionStamp.value}.csv"
            inited = true
        }
    }
}
