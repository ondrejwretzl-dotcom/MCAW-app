package com.mcaw.config

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

object AppPreferences {

    private const val PREF_NAME = "mcaw_prefs"
    private lateinit var prefs: SharedPreferences

    @Volatile
    var lastSpeedMps: Float = 0f

    @Volatile
    var cameraFocalLengthMm: Float = Float.NaN

    @Volatile
    var cameraSensorHeightMm: Float = Float.NaN


    // ---- Calibration / robustness knobs ----
    /**
     * Násobek vzdálenosti z monokulárního odhadu (1.0 = beze změny).
     * Praktické pro doladění podle telefonu / FOV / EIS cropu bez rozbití pipeline.
     */
    var distanceScale: Float
        get() = prefs.getFloat("calib_distance_scale", 1.0f).coerceIn(0.50f, 2.00f)
        set(v) = prefs.edit().putFloat("calib_distance_scale", v.coerceIn(0.50f, 2.00f)).apply()

    /**
     * Maximální dovolený laterální offset cíle v rámci ROI trapezoidu (ego-path gating).
     * 0 = jen přesný střed, 1 = celý trapezoid. Default 0.55 = konzervativní město.
     */
    var laneEgoMaxOffset: Float
        get() = prefs.getFloat("lane_ego_max_offset", 0.55f).coerceIn(0.20f, 1.00f)
        set(v) = prefs.edit().putFloat("lane_ego_max_offset", v.coerceIn(0.20f, 1.00f)).apply()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun ensureInit(ctx: Context) {
        if (!::prefs.isInitialized) {
            init(ctx)
        }
    }

    // MODE SETTINGS
    var detectionMode: Int
        get() = prefs.getInt("mode", 0)
        set(v) = prefs.edit().putInt("mode", v).apply()

    // MODEL SETTINGS
    var selectedModel: Int
        get() = prefs.getInt("model", 1)
        set(v) = prefs.edit().putInt("model", v).apply()

    // ALERT SETTINGS
    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(v) = prefs.edit().putBoolean("vibration", v).apply()

    var voice: Boolean
        get() = prefs.getBoolean("voice", false)
        set(v) = prefs.edit().putBoolean("voice", v).apply()


// ALERT ROUTING (per level) - does NOT remove global switches above; it only refines behavior.
// Orange = WARNING (level 1), Red = CRITICAL (level 2)
var soundOrange: Boolean
    get() = prefs.getBoolean("sound_orange", true)
    set(v) = prefs.edit().putBoolean("sound_orange", v).apply()

var soundRed: Boolean
    get() = prefs.getBoolean("sound_red", true)
    set(v) = prefs.edit().putBoolean("sound_red", v).apply()

var voiceOrange: Boolean
    get() = prefs.getBoolean("voice_orange", true)
    set(v) = prefs.edit().putBoolean("voice_orange", v).apply()

var voiceRed: Boolean
    get() = prefs.getBoolean("voice_red", true)
    set(v) = prefs.edit().putBoolean("voice_red", v).apply()

var ttsTextOrange: String
    get() = prefs.getString("tts_orange_text", "Pozor, blížíš se k objektu") ?: "Pozor, blížíš se k objektu"
    set(v) = prefs.edit().putString("tts_orange_text", v).apply()

var ttsTextRed: String
    get() = prefs.getString("tts_red_text", "Kritické, okamžitě brzdi") ?: "Kritické, okamžitě brzdi"
    set(v) = prefs.edit().putString("tts_red_text", v).apply()

    var debugOverlay: Boolean
        get() = prefs.getBoolean("debugOverlay", false)
        set(v) = prefs.edit().putBoolean("debugOverlay", v).apply()

    var laneFilter: Boolean
        get() = prefs.getBoolean("laneFilter", false)
        set(v) = prefs.edit().putBoolean("laneFilter", v).apply()


/**
 * Přísné držení v ROI:
 * - true  => containment >= 0.80 (méně falešných mimo ROI, ale víc dropů)
 * - false => containment >= 0.65 (rychlejší náběh, lepší pro perspektivu)
 */
var roiStrictContainment: Boolean
    get() = prefs.getBoolean("roiStrictContainment", false)
    set(v) = prefs.edit().putBoolean("roiStrictContainment", v).apply()

fun roiContainmentThreshold(): Float = if (roiStrictContainment) 0.80f else 0.65f
    // BRAKE CUE (detekce rozsvícených brzdových světel – heuristika, default OFF)
    var brakeCueEnabled: Boolean
        get() = prefs.getBoolean("brake_cue_enabled", true)
        set(v) = prefs.edit().putBoolean("brake_cue_enabled", v).apply()

    /**
     * 0 = Nízká citlivost (méně false-positive), 1 = Standard, 2 = Vysoká (citlivější).
     */
    var brakeCueSensitivity: Int
        get() = prefs.getInt("brake_cue_sens", 1)
        set(v) = prefs.edit().putInt("brake_cue_sens", v.coerceIn(0, 2)).apply()


    var previewActive: Boolean
        get() = prefs.getBoolean("previewActive", false)
        set(v) = prefs.edit().putBoolean("previewActive", v).apply()

    // USER THRESHOLDS (orange/red)
    var userTtcOrange: Float
        get() = prefs.getFloat("user_ttc_orange", 3.0f)
        set(v) = prefs.edit().putFloat("user_ttc_orange", v).apply()

    var userTtcRed: Float
        get() = prefs.getFloat("user_ttc_red", 1.5f)
        set(v) = prefs.edit().putFloat("user_ttc_red", v).apply()

    var userDistOrange: Float
        get() = prefs.getFloat("user_dist_orange", 16f)
        set(v) = prefs.edit().putFloat("user_dist_orange", v).apply()

    var userDistRed: Float
        get() = prefs.getFloat("user_dist_red", 9f)
        set(v) = prefs.edit().putFloat("user_dist_red", v).apply()

    var userSpeedOrange: Float
        get() = prefs.getFloat("user_speed_orange", 3f)
        set(v) = prefs.edit().putFloat("user_speed_orange", v).apply()

    var userSpeedRed: Float
        get() = prefs.getFloat("user_speed_red", 5f)
        set(v) = prefs.edit().putFloat("user_speed_red", v).apply()

        // ---- ROI (Region of Interest) for detection (normalized 0..1) ----
    // Nově používáme PEVNÝ SYMETRICKÝ TRAPEZOID (perspektiva jízdního pruhu).
    //
    // Parametry jsou v normalizovaných souřadnicích rámce (0..1):
    // - topY / bottomY: svislé hranice trapezoidu
    // - topHalfW / bottomHalfW: polovina šířky od středu (centerX = 0.5)
    //
    // Trapezoid body (clockwise):
    //  TL = (0.5 - topHalfW, topY)
    //  TR = (0.5 + topHalfW, topY)
    //  BR = (0.5 + bottomHalfW, bottomY)
    //  BL = (0.5 - bottomHalfW, bottomY)

    private const val ROI_TRAP_TOP_Y = "roi_trap_top_y_n"
    private const val ROI_TRAP_BOTTOM_Y = "roi_trap_bottom_y_n"
    private const val ROI_TRAP_TOP_HALFW = "roi_trap_top_halfw_n"
    private const val ROI_TRAP_BOTTOM_HALFW = "roi_trap_bottom_halfw_n"

    // Default (rozumný jízdní pruh – upravitelné v Preview ROI editoru)
    private const val ROI_TRAP_DEFAULT_TOP_Y = 0.32f
    private const val ROI_TRAP_DEFAULT_BOTTOM_Y = 0.92f
    private const val ROI_TRAP_DEFAULT_TOP_HALFW = 0.18f
    private const val ROI_TRAP_DEFAULT_BOTTOM_HALFW = 0.46f

    private const val ROI_TRAP_MIN_HEIGHT_N = 0.10f
    private const val ROI_TRAP_MIN_TOP_HALFW_N = 0.06f
    private const val ROI_TRAP_MIN_BOTTOM_HALFW_N = 0.12f

    var roiTrapTopY: Float
        get() = prefs.getFloat(ROI_TRAP_TOP_Y, ROI_TRAP_DEFAULT_TOP_Y).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(ROI_TRAP_TOP_Y, v.coerceIn(0f, 1f)).apply()

    var roiTrapBottomY: Float
        get() = prefs.getFloat(ROI_TRAP_BOTTOM_Y, ROI_TRAP_DEFAULT_BOTTOM_Y).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(ROI_TRAP_BOTTOM_Y, v.coerceIn(0f, 1f)).apply()

    var roiTrapTopHalfW: Float
        get() = prefs.getFloat(ROI_TRAP_TOP_HALFW, ROI_TRAP_DEFAULT_TOP_HALFW).coerceIn(0f, 0.5f)
        set(v) = prefs.edit().putFloat(ROI_TRAP_TOP_HALFW, v.coerceIn(0f, 0.5f)).apply()

    var roiTrapBottomHalfW: Float
        get() = prefs.getFloat(ROI_TRAP_BOTTOM_HALFW, ROI_TRAP_DEFAULT_BOTTOM_HALFW).coerceIn(0f, 0.5f)
        set(v) = prefs.edit().putFloat(ROI_TRAP_BOTTOM_HALFW, v.coerceIn(0f, 0.5f)).apply()

    data class RoiTrapN(
        val topY: Float,
        val bottomY: Float,
        val topHalfW: Float,
        val bottomHalfW: Float,
        val centerX: Float = 0.5f
    ) {
        fun toPoints(): FloatArray {
            val tlx = (centerX - topHalfW)
            val trx = (centerX + topHalfW)
            val brx = (centerX + bottomHalfW)
            val blx = (centerX - bottomHalfW)
            return floatArrayOf(tlx, topY, trx, topY, brx, bottomY, blx, bottomY)
        }
    }

    fun getRoiTrapezoidNormalized(): RoiTrapN {
        var topY = roiTrapTopY
        var bottomY = roiTrapBottomY
        var topHalfW = roiTrapTopHalfW
        var bottomHalfW = roiTrapBottomHalfW

        if (bottomY < topY) {
            val tmp = bottomY; bottomY = topY; topY = tmp
        }
        if (bottomY - topY < ROI_TRAP_MIN_HEIGHT_N) {
            val mid = (topY + bottomY) * 0.5f
            topY = (mid - ROI_TRAP_MIN_HEIGHT_N * 0.5f).coerceIn(0f, 1f)
            bottomY = (topY + ROI_TRAP_MIN_HEIGHT_N).coerceIn(0f, 1f)
        }

        if (topHalfW < ROI_TRAP_MIN_TOP_HALFW_N) topHalfW = ROI_TRAP_MIN_TOP_HALFW_N
        if (bottomHalfW < ROI_TRAP_MIN_BOTTOM_HALFW_N) bottomHalfW = ROI_TRAP_MIN_BOTTOM_HALFW_N
        if (bottomHalfW < topHalfW) bottomHalfW = topHalfW

        topHalfW = topHalfW.coerceIn(0f, 0.5f)
        bottomHalfW = bottomHalfW.coerceIn(0f, 0.5f)
        topY = topY.coerceIn(0f, 1f)
        bottomY = bottomY.coerceIn(0f, 1f)

        return RoiTrapN(topY, bottomY, topHalfW, bottomHalfW, centerX = 0.5f)
    }

    fun setRoiTrapezoidNormalized(topY: Float, bottomY: Float, topHalfW: Float, bottomHalfW: Float) {
        var ty = topY.coerceIn(0f, 1f)
        var by = bottomY.coerceIn(0f, 1f)
        var thw = topHalfW.coerceIn(0f, 0.5f)
        var bhw = bottomHalfW.coerceIn(0f, 0.5f)

        if (by < ty) { val tmp = by; by = ty; ty = tmp }
        if (by - ty < ROI_TRAP_MIN_HEIGHT_N) by = (ty + ROI_TRAP_MIN_HEIGHT_N).coerceIn(0f, 1f)

        if (thw < ROI_TRAP_MIN_TOP_HALFW_N) thw = ROI_TRAP_MIN_TOP_HALFW_N
        if (bhw < ROI_TRAP_MIN_BOTTOM_HALFW_N) bhw = ROI_TRAP_MIN_BOTTOM_HALFW_N
        if (bhw < thw) bhw = thw

        prefs.edit()
            .putFloat(ROI_TRAP_TOP_Y, ty)
            .putFloat(ROI_TRAP_BOTTOM_Y, by)
            .putFloat(ROI_TRAP_TOP_HALFW, thw)
            .putFloat(ROI_TRAP_BOTTOM_HALFW, bhw)
            .apply()
    }

    fun resetRoiToDefault() {
        setRoiTrapezoidNormalized(
            ROI_TRAP_DEFAULT_TOP_Y,
            ROI_TRAP_DEFAULT_BOTTOM_Y,
            ROI_TRAP_DEFAULT_TOP_HALFW,
            ROI_TRAP_DEFAULT_BOTTOM_HALFW
        )
    }

    // ---- Legacy rectangle ROI API (ponecháno kvůli kompatibilitě / logům) ----
    // Pokud někde jinde v projektu zůstane volání getRoiNormalized(), mapujeme trapezoid na jeho bounding rect.
    data class RoiN(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun getRoiNormalized(): RoiN {
        val t = getRoiTrapezoidNormalized()
        val pts = t.toPoints()
        val left = minOf(pts[0], pts[6]).coerceIn(0f, 1f)
        val right = maxOf(pts[2], pts[4]).coerceIn(0f, 1f)
        val top = t.topY.coerceIn(0f, 1f)
        val bottom = t.bottomY.coerceIn(0f, 1f)
        return RoiN(left, top, right, bottom)
    }

    fun setRoiNormalized(left: Float, top: Float, right: Float, bottom: Float) {
        var l = left.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (r < l) { val tmp = r; r = l; l = tmp }
        if (b < t) { val tmp = b; b = t; t = tmp }
        val centerX = 0.5f
        val halfW = maxOf(abs(centerX - l), abs(r - centerX)).coerceIn(ROI_TRAP_MIN_TOP_HALFW_N, 0.5f)
        setRoiTrapezoidNormalized(t, b, halfW, halfW)
    }

}
