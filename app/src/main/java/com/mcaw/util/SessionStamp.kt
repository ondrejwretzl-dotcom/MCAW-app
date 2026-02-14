package com.mcaw.util

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Stable per-process/session stamp used for naming log files.
 * Lazy init to avoid eager work during class loading.
 */
object SessionStamp {
    val value: String by lazy {
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())
    }
}
