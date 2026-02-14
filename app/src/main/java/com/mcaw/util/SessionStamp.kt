package com.mcaw.util

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Process-wide stable session stamp used for log filenames.
 * - one value per app process run
 * - avoids log file explosion caused by timestamp-per-event naming
 */
object SessionStamp {
    val value: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())
    }
}
