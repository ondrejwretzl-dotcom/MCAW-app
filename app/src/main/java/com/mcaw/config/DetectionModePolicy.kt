package com.mcaw.config

/**
 * Jediné místo pro logiku "Automat" režimu.
 *
 * Režimy (musí odpovídat položkám v R.array.modes):
 * 0 = Automat
 * 1 = Město
 * 2 = Sport
 * 3 = Uživatel
 */
object DetectionModePolicy {

    data class Resolution(
        val effectiveMode: Int,
        val changed: Boolean,
        val reason: String
    )

    /**
     * Stavový přepínač pro "Automat".
     *
     * - hysteresis: 53/57 km/h kolem hranice 55 km/h
     * - nepřepíná při aktivním alertu (orange/red)
     * - cooldown po přepnutí (aby to necvakalo při šumu GPS)
     */
    class AutoModeSwitcher(
        private val switchCenterKmh: Float = 55f,
        private val hysteresisKmh: Float = 2f,
        private val cooldownMs: Long = 2500L,
        private val blockDuringAlertMs: Long = 1200L
    ) {
        private var lastEffective: Int = AppPreferences.MODE_CITY
        private var lastSwitchMs: Long = 0L
        private var lastAlertMs: Long = 0L

        fun resolve(selectedMode: Int, riderSpeedMps: Float, lastAlertLevel: Int, nowMs: Long): Resolution {
            // Non-auto: direct mapping (city/sport/user)
            if (selectedMode != AppPreferences.MODE_AUTO) {
                val eff = selectedMode.coerceIn(AppPreferences.MODE_AUTO, AppPreferences.MODE_USER)
                val normalized = if (eff == AppPreferences.MODE_AUTO) AppPreferences.MODE_CITY else eff
                val changed = normalized != lastEffective
                if (changed) {
                    lastEffective = normalized
                    lastSwitchMs = nowMs
                }
                return Resolution(normalized, changed, "manual")
            }

            // Remember last alert timestamp to prevent toggling right after it clears.
            if (lastAlertLevel > 0) lastAlertMs = nowMs

            // If speed unknown, be conservative.
            val kmh = if (riderSpeedMps.isFinite()) riderSpeedMps * 3.6f else Float.NaN
            val up = switchCenterKmh + hysteresisKmh
            val down = switchCenterKmh - hysteresisKmh

            // Hard block during alerts / shortly after.
            val blockedByAlert =
                lastAlertLevel > 0 || (lastAlertMs > 0L && (nowMs - lastAlertMs) <= blockDuringAlertMs)

            if (blockedByAlert) {
                val eff = lastEffective
                return Resolution(eff, false, "blocked_by_alert")
            }

            // Cooldown after switching.
            if (lastSwitchMs > 0L && (nowMs - lastSwitchMs) < cooldownMs) {
                return Resolution(lastEffective, false, "cooldown")
            }

            val target = when {
                !kmh.isFinite() -> AppPreferences.MODE_CITY
                lastEffective == AppPreferences.MODE_CITY && kmh >= up -> AppPreferences.MODE_SPORT
                lastEffective == AppPreferences.MODE_SPORT && kmh <= down -> AppPreferences.MODE_CITY
                else -> lastEffective
            }

            val changed = target != lastEffective
            if (changed) {
                lastEffective = target
                lastSwitchMs = nowMs
            }
            val reason = when {
                !kmh.isFinite() -> "speed_unknown"
                changed && target == AppPreferences.MODE_SPORT -> "speed_up"
                changed && target == AppPreferences.MODE_CITY -> "speed_down"
                else -> "hold"
            }
            return Resolution(target, changed, reason)
        }

        fun getLastEffective(): Int = lastEffective
    }
}
