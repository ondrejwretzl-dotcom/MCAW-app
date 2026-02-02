package com.mcaw.config

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREF_NAME = "mcaw_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // MODE SETTINGS
    var detectionMode: Int
        get() = prefs.getInt("mode", 1)
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
}
