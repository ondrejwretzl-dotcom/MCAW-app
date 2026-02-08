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
        get() = prefs.getBoolean("voice", true)
        set(v) = prefs.edit().putBoolean("voice", v).apply()

    var debugOverlay: Boolean
        get() = prefs.getBoolean("debugOverlay", false)
        set(v) = prefs.edit().putBoolean("debugOverlay", v).apply()

    var laneFilter: Boolean
        get() = prefs.getBoolean("laneFilter", false)
        set(v) = prefs.edit().putBoolean("laneFilter", v).apply()

    var previewActive: Boolean
        get() = prefs.getBoolean("previewActive", false)
        set(v) = prefs.edit().putBoolean("previewActive", v).apply()

    // USER THRESHOLDS (orange/red)
    var userTtcOrange: Float
        get() = prefs.getFloat("user_ttc_orange", 3.0f)
        set(v) = prefs.edit().putFloat("user_ttc_orange", v).apply()

    var userTtcRed: Float
        get() = prefs.getFloat("user_ttc_red", 1.2f)
        set(v) = prefs.edit().putFloat("user_ttc_red", v).apply()

    var userDistOrange: Float
        get() = prefs.getFloat("user_dist_orange", 15f)
        set(v) = prefs.edit().putFloat("user_dist_orange", v).apply()

    var userDistRed: Float
        get() = prefs.getFloat("user_dist_red", 6f)
        set(v) = prefs.edit().putFloat("user_dist_red", v).apply()

    var userSpeedOrange: Float
        get() = prefs.getFloat("user_speed_orange", 3f)
        set(v) = prefs.edit().putFloat("user_speed_orange", v).apply()

    var userSpeedRed: Float
        get() = prefs.getFloat("user_speed_red", 5f)
        set(v) = prefs.edit().putFloat("user_speed_red", v).apply()

    // ---- ROI (Region of Interest) for detection (normalized 0..1) ----
    // Default: crop 15% on each side => [0.15,0.15]-[0.85,0.85]
    private const val ROI_LEFT = "roi_left_n"
    private const val ROI_TOP = "roi_top_n"
    private const val ROI_RIGHT = "roi_right_n"
    private const val ROI_BOTTOM = "roi_bottom_n"

    private const val ROI_DEFAULT_LEFT = 0.15f
    private const val ROI_DEFAULT_TOP = 0.15f
    private const val ROI_DEFAULT_RIGHT = 0.85f
    private const val ROI_DEFAULT_BOTTOM = 0.85f

    private const val ROI_MIN_SIZE_N = 0.10f

    var roiLeftN: Float
        get() = prefs.getFloat(ROI_LEFT, ROI_DEFAULT_LEFT).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(ROI_LEFT, v.coerceIn(0f, 1f)).apply()

    var roiTopN: Float
        get() = prefs.getFloat(ROI_TOP, ROI_DEFAULT_TOP).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(ROI_TOP, v.coerceIn(0f, 1f)).apply()

    var roiRightN: Float
        get() = prefs.getFloat(ROI_RIGHT, ROI_DEFAULT_RIGHT).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(ROI_RIGHT, v.coerceIn(0f, 1f)).apply()

    var roiBottomN: Float
        get() = prefs.getFloat(ROI_BOTTOM, ROI_DEFAULT_BOTTOM).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(ROI_BOTTOM, v.coerceIn(0f, 1f)).apply()

    data class RoiN(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun getRoiNormalized(): RoiN {
        // sanitize ordering and min size
        var l = roiLeftN
        var t = roiTopN
        var r = roiRightN
        var b = roiBottomN

        if (r < l) {
            val tmp = r; r = l; l = tmp
        }
        if (b < t) {
            val tmp = b; b = t; t = tmp
        }

        if (r - l < ROI_MIN_SIZE_N) {
            val mid = (l + r) * 0.5f
            l = (mid - ROI_MIN_SIZE_N * 0.5f).coerceIn(0f, 1f)
            r = (l + ROI_MIN_SIZE_N).coerceIn(0f, 1f)
        }
        if (b - t < ROI_MIN_SIZE_N) {
            val mid = (t + b) * 0.5f
            t = (mid - ROI_MIN_SIZE_N * 0.5f).coerceIn(0f, 1f)
            b = (t + ROI_MIN_SIZE_N).coerceIn(0f, 1f)
        }
        // ensure bounds
        l = l.coerceIn(0f, 1f); r = r.coerceIn(0f, 1f)
        t = t.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        return RoiN(l, t, r, b)
    }

    fun setRoiNormalized(left: Float, top: Float, right: Float, bottom: Float) {
        // sanitize once before persisting
        var l = left.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)

        if (r < l) { val tmp = r; r = l; l = tmp }
        if (b < t) { val tmp = b; b = t; t = tmp }

        if (r - l < ROI_MIN_SIZE_N) r = (l + ROI_MIN_SIZE_N).coerceIn(0f, 1f)
        if (b - t < ROI_MIN_SIZE_N) b = (t + ROI_MIN_SIZE_N).coerceIn(0f, 1f)

        prefs.edit()
            .putFloat(ROI_LEFT, l)
            .putFloat(ROI_TOP, t)
            .putFloat(ROI_RIGHT, r)
            .putFloat(ROI_BOTTOM, b)
            .apply()
    }

    fun resetRoiToDefault() {
        setRoiNormalized(ROI_DEFAULT_LEFT, ROI_DEFAULT_TOP, ROI_DEFAULT_RIGHT, ROI_DEFAULT_BOTTOM)
    }
}
