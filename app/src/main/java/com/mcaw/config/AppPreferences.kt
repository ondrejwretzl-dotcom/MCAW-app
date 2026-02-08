package com.mcaw.config

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

object AppPreferences {

    private const val PREFS_NAME = "mcaw_prefs"

    private const val KEY_ROI_LEFT = "roi_left_n"
    private const val KEY_ROI_TOP = "roi_top_n"
    private const val KEY_ROI_RIGHT = "roi_right_n"
    private const val KEY_ROI_BOTTOM = "roi_bottom_n"

    private const val DEFAULT_MARGIN = 0.15f

    lateinit var prefs: SharedPreferences
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class RoiN(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    fun getRoiNormalized(): RoiN {
        return RoiN(
            prefs.getFloat(KEY_ROI_LEFT, DEFAULT_MARGIN),
            prefs.getFloat(KEY_ROI_TOP, DEFAULT_MARGIN),
            prefs.getFloat(KEY_ROI_RIGHT, 1f - DEFAULT_MARGIN),
            prefs.getFloat(KEY_ROI_BOTTOM, 1f - DEFAULT_MARGIN)
        )
    }

    fun setRoiNormalized(roi: RoiN) {
        val l = roi.left.coerceIn(0f, 0.9f)
        val t = roi.top.coerceIn(0f, 0.9f)
        val r = roi.right.coerceIn(l + 0.05f, 1f)
        val b = roi.bottom.coerceIn(t + 0.05f, 1f)

        prefs.edit()
            .putFloat(KEY_ROI_LEFT, l)
            .putFloat(KEY_ROI_TOP, t)
            .putFloat(KEY_ROI_RIGHT, r)
            .putFloat(KEY_ROI_BOTTOM, b)
            .apply()
    }

    fun resetRoiToDefault() {
        setRoiNormalized(
            RoiN(
                DEFAULT_MARGIN,
                DEFAULT_MARGIN,
                1f - DEFAULT_MARGIN,
                1f - DEFAULT_MARGIN
            )
        )
    }
}
