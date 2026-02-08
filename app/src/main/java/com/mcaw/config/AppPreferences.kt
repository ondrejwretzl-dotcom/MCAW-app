package com.mcaw.config

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREF_NAME = "mcaw_prefs"
    private lateinit var prefs: SharedPreferences

    @Volatile
    var lastSpeedMps: Float = 0f

    @Volatile
    var cameraFocalLengthMm: Float = Float.NaN

    @Volatile
    var cameraSensorHeightMm: Float = Float.NaN
    // ROI (Region of Interest) pro detekci – normalizované hranice 0..1 vůči otočenému frame
    // Default: 15 % ořez ze všech stran.
    private const val KEY_ROI_LEFT_N = "roi_left_n"
    private const val KEY_ROI_TOP_N = "roi_top_n"
    private const val KEY_ROI_RIGHT_N = "roi_right_n"
    private const val KEY_ROI_BOTTOM_N = "roi_bottom_n"

    private const val ROI_DEFAULT_MARGIN = 0.15f

    var roiLeftN: Float
        get() = prefs.getFloat(KEY_ROI_LEFT_N, ROI_DEFAULT_MARGIN).coerceIn(0f, 0.49f)
        set(v) = prefs.edit().putFloat(KEY_ROI_LEFT_N, v.coerceIn(0f, 0.49f)).apply()

    var roiTopN: Float
        get() = prefs.getFloat(KEY_ROI_TOP_N, ROI_DEFAULT_MARGIN).coerceIn(0f, 0.49f)
        set(v) = prefs.edit().putFloat(KEY_ROI_TOP_N, v.coerceIn(0f, 0.49f)).apply()

    var roiRightN: Float
        get() = prefs.getFloat(KEY_ROI_RIGHT_N, 1f - ROI_DEFAULT_MARGIN).coerceIn(0.51f, 1f)
        set(v) = prefs.edit().putFloat(KEY_ROI_RIGHT_N, v.coerceIn(0.51f, 1f)).apply()

    var roiBottomN: Float
        get() = prefs.getFloat(KEY_ROI_BOTTOM_N, 1f - ROI_DEFAULT_MARGIN).coerceIn(0.51f, 1f)
        set(v) = prefs.edit().putFloat(KEY_ROI_BOTTOM_N, v.coerceIn(0.51f, 1f)).apply()

    fun resetRoiDefault() {
        roiLeftN = ROI_DEFAULT_MARGIN
        roiTopN = ROI_DEFAULT_MARGIN
        roiRightN = 1f - ROI_DEFAULT_MARGIN
        roiBottomN = 1f - ROI_DEFAULT_MARGIN
    }

    data class RoiN(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun getRoiN(): RoiN {
        val l = roiLeftN
        val t = roiTopN
        val r = roiRightN
        val b = roiBottomN
        // ensure valid
        val left = l.coerceIn(0f, 0.49f)
        val top = t.coerceIn(0f, 0.49f)
        val right = maxOf(r, left + 0.05f).coerceIn(0.51f, 1f)
        val bottom = maxOf(b, top + 0.05f).coerceIn(0.51f, 1f)
        return RoiN(left, top, right, bottom)
    }

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
}