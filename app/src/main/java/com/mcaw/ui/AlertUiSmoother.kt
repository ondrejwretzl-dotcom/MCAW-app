package com.mcaw.ui

import android.os.SystemClock

/**
 * UI-side smoothing to prevent ORANGE/RED flicker caused by noisy upstream signals.
 *
 * - Upgrades (SAFE->ORANGE, ORANGE->RED) are immediate.
 * - Downgrades are delayed by a hold + stability window.
 * - WHY text is rate-limited and never cleared while alert stays active (to avoid blinking).
 *
 * This is UI-only: RiskEngine remains isolated and O(1) without UI dependencies.
 */
class AlertUiSmoother(
    private val holdOrangeMs: Long = 900L,
    private val holdRedMs: Long = 650L,
    private val downgradeStableMs: Long = 260L,
    private val reasonUpdateMinMs: Long = 250L
) {

    data class Display(val level: Int, val why: String)

    private var shownLevel: Int = 0
    private var holdUntilMs: Long = 0L

    private var pendingLevel: Int = 0
    private var pendingSinceMs: Long = 0L
    private var pendingActive: Boolean = false

    private var shownWhy: String = ""
    private var lastWhyUpdateMs: Long = 0L

    fun reset() {
        shownLevel = 0
        holdUntilMs = 0L
        pendingActive = false
        pendingLevel = 0
        pendingSinceMs = 0L
        shownWhy = ""
        lastWhyUpdateMs = 0L
    }

    fun update(rawLevelIn: Int, rawWhyIn: String, nowMs: Long = SystemClock.elapsedRealtime()): Display {
        val rawLevel = rawLevelIn.coerceIn(0, 2)
        val cleanWhy = rawWhyIn.trim()
            .replace("\n", " ")
            .replace("\r", " ")
            // Kotlin string literals need escaping for regex backslashes.
            .replace(Regex("\\s+"), " ")
            .take(90)

        // Level smoothing (hold + stability on downgrade)
        if (rawLevel > shownLevel) {
            // upgrade immediately
            shownLevel = rawLevel
            holdUntilMs = nowMs + holdForLevelMs(shownLevel)
            pendingActive = false
        } else if (rawLevel < shownLevel) {
            // downgrade only after hold and a short stable window
            if (nowMs >= holdUntilMs) {
                if (!pendingActive || pendingLevel != rawLevel) {
                    pendingActive = true
                    pendingLevel = rawLevel
                    pendingSinceMs = nowMs
                } else if (nowMs - pendingSinceMs >= downgradeStableMs) {
                    shownLevel = rawLevel
                    pendingActive = false
                    holdUntilMs = nowMs + holdForLevelMs(shownLevel)
                    if (shownLevel == 0) shownWhy = ""
                }
            }
        } else {
            // same level, no pending downgrade
            pendingActive = false
        }

        // WHY smoothing: keep stable while alert is active.
        if (shownLevel == 0) {
            shownWhy = ""
        } else if (cleanWhy.isNotBlank()) {
            val changed = cleanWhy != shownWhy
            val canUpdate = (nowMs - lastWhyUpdateMs) >= reasonUpdateMinMs
            if ((shownWhy.isBlank() || changed) && canUpdate) {
                shownWhy = cleanWhy
                lastWhyUpdateMs = nowMs
            }
        }

        return Display(shownLevel, shownWhy)
    }

    private fun holdForLevelMs(level: Int): Long {
        return when (level) {
            2 -> holdRedMs
            1 -> holdOrangeMs
            else -> 0L
        }
    }
}
