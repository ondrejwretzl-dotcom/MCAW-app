package com.mcaw.config

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREF_NAME = "mcaw_prefs"
    private lateinit var prefs: SharedPreferences

    @Volatile
    var lastSpeedMps: Float = 0f

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // MODE SETTINGS
    var detectionMode: Int
        get() = prefs.getInt("mode", 0)
        set(v) = prefs.edit().putInt("mode", v).apply()

    // MODEL SETTINGS
    var selectedModel: Int
        get() = prefs.getInt("model", 0)
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

    // USER THRESHOLDS (orange/red)
    var userTtcOrange: Float
        get() = prefs.getFloat("user_ttc_orange", 2.5f)
        set(v) = prefs.edit().putFloat("user_ttc_orange", v).apply()

    var userTtcRed: Float
        get() = prefs.getFloat("user_ttc_red", 1.5f)
        set(v) = prefs.edit().putFloat("user_ttc_red", v).apply()

    var userDistOrange: Float
        get() = prefs.getFloat("user_dist_orange", 12f)
        set(v) = prefs.edit().putFloat("user_dist_orange", v).apply()

    var userDistRed: Float
        get() = prefs.getFloat("user_dist_red", 6f)
        set(v) = prefs.edit().putFloat("user_dist_red", v).apply()

    var userSpeedOrange: Float
        get() = prefs.getFloat("user_speed_orange", 4f)
        set(v) = prefs.edit().putFloat("user_speed_orange", v).apply()

    var userSpeedRed: Float
        get() = prefs.getFloat("user_speed_red", 8f)
        set(v) = prefs.edit().putFloat("user_speed_red", v).apply()
}
