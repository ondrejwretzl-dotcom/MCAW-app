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

    @Volatile
    var cameraSensorWidthMm: Float = Float.NaN

    /**
     * Camera zoom ratio (framing). Stored as user/mount preference.
     * 1.0 = no zoom. Upper bound is intentionally conservative to reduce lens switching.
     */
    var cameraZoomRatio: Float
        get() = prefs.getFloat("camera_zoom_ratio", 1.0f).coerceIn(1.0f, 2.0f)
        set(v) = prefs.edit().putFloat("camera_zoom_ratio", v.coerceIn(1.0f, 2.0f)).apply()


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

    
/**
 * Výška kamery nad zemí (m) pro ground-plane odhad vzdálenosti.
 * Auto typicky ~1.1–1.5, moto často ~0.9–1.3 podle mountu.
 */
var cameraMountHeightM: Float
    get() = prefs.getFloat("calib_camera_height_m", 1.20f).coerceIn(0.30f, 3.00f)
    set(v) = prefs.edit().putFloat("calib_camera_height_m", v.coerceIn(0.30f, 3.00f)).apply()

/**
 * Náklon kamery (pitch) dolů v stupních. 0 = horizont, kladné hodnoty = dolů.
 * Typicky 3–10°.
 */
var cameraPitchDownDeg: Float
    get() = prefs.getFloat("calib_camera_pitch_deg", 6.0f).coerceIn(-10.0f, 25.0f)
    set(v) = prefs.edit().putFloat("calib_camera_pitch_deg", v.coerceIn(-10.0f, 25.0f)).apply()

/**
 * Poslední metriky kalibrace (audit/debug): RMS chyba v metrech.
 * 0 = neznámé / neprovedeno.
 */
var calibrationRmsM: Float
    get() = prefs.getFloat("calib_rms_m", 0f).coerceIn(0f, 50f)
    set(v) = prefs.edit().putFloat("calib_rms_m", v.coerceIn(0f, 50f)).apply()

/** Poslední max chyba kalibrace (|e|) v metrech. */
var calibrationMaxErrM: Float
    get() = prefs.getFloat("calib_max_err_m", 0f).coerceIn(0f, 200f)
    set(v) = prefs.edit().putFloat("calib_max_err_m", v.coerceIn(0f, 200f)).apply()

/**
 * Stabilita telefonu během kalibrace: směrodatná odchylka "tilt" jitteru (deg).
 * Nejde o absolutní pitch, ale o to, jak moc se telefon hýbal.
 */
var calibrationImuStdDeg: Float
    get() = prefs.getFloat("calib_imu_std_deg", 0f).coerceIn(0f, 45f)
    set(v) = prefs.edit().putFloat("calib_imu_std_deg", v.coerceIn(0f, 45f)).apply()

/** 0=unknown, 1=OK, 2=UNCERTAIN, 3=BAD */
var calibrationQuality: Int
    get() = prefs.getInt("calib_quality", 0).coerceIn(0, 3)
    set(v) = prefs.edit().putInt("calib_quality", v.coerceIn(0, 3)).apply()

/** 0=unknown, 1=OK, 2=UNCERTAIN, 3=BAD (geometry-only gating: RMS/Max). */
var calibrationGeomQuality: Int
    get() = prefs.getInt("calib_geom_quality", 0).coerceIn(0, 3)
    set(v) = prefs.edit().putInt("calib_geom_quality", v.coerceIn(0, 3)).apply()

/** 0=unknown, 1=OK, 2=UNCERTAIN, 3=BAD (device stability only). */
var calibrationImuQuality: Int
    get() = prefs.getInt("calib_imu_quality", 0).coerceIn(0, 3)
    set(v) = prefs.edit().putInt("calib_imu_quality", v.coerceIn(0, 3)).apply()

/** Estimated additional distance error at 10m caused by IMU jitter (meters). */
var calibrationImuExtraErrAt10m: Float
    get() = prefs.getFloat("calib_imu_extra_err_10m", 0f).coerceIn(0f, 50f)
    set(v) = prefs.edit().putFloat("calib_imu_extra_err_10m", v.coerceIn(0f, 50f)).apply()

/** Combined estimated error at 10m (meters): sqrt(rms^2 + imuExtra^2). */
var calibrationCombinedErrAt10m: Float
    get() = prefs.getFloat("calib_combined_err_10m", 0f).coerceIn(0f, 50f)
    set(v) = prefs.edit().putFloat("calib_combined_err_10m", v.coerceIn(0f, 50f)).apply()


    /** Estimated distance to the bottom edge of the active ROI crop (meters) at the time of calibration save. */
    var roiMinDistM: Float
        get() = prefs.getFloat("roi_min_dist_m", Float.NaN)
        set(v) = prefs.edit().putFloat("roi_min_dist_m", v).apply()

    /** Whether user confirmed that ROI bottom distance (dashboard cut) looks correct in calibration verify step. */
    var roiMinDistConfirmed: Boolean
        get() = prefs.getBoolean("roi_min_dist_ok", false)
        set(v) = prefs.edit().putBoolean("roi_min_dist_ok", v).apply()

/** Timestamp (uptime ms) when calibration was saved (best-effort). */
var calibrationSavedUptimeMs: Long
    get() = prefs.getLong("calib_saved_uptime_ms", 0L).coerceAtLeast(0L)
    set(v) = prefs.edit().putLong("calib_saved_uptime_ms", v.coerceAtLeast(0L)).apply()

/**
 * Quality gating: při špatné kvalitě (tma / motion blur) přepne alerting do konzervativního režimu.
 */
var qualityGatingEnabled: Boolean
    get() = prefs.getBoolean("quality_gating", true)
    set(v) = prefs.edit().putBoolean("quality_gating", v).apply()

/**
 * Cut-in ochrana: dočasně povolí větší ego offset, když cíl rychle roste a míří do středu.
 */
var cutInProtectionEnabled: Boolean
    get() = prefs.getBoolean("cutin_protection", true)
    set(v) = prefs.edit().putBoolean("cutin_protection", v).apply()

var cutInOffsetBoost: Float
    get() = prefs.getFloat("cutin_offset_boost", 0.25f).coerceIn(0.05f, 0.50f)
    set(v) = prefs.edit().putFloat("cutin_offset_boost", v.coerceIn(0.05f, 0.50f)).apply()

var cutInBoostMs: Long
    get() = prefs.getLong("cutin_boost_ms", 900L).coerceIn(200L, 2500L)
    set(v) = prefs.edit().putLong("cutin_boost_ms", v.coerceIn(200L, 2500L)).apply()

var cutInGrowthRatio: Float
    get() = prefs.getFloat("cutin_growth_ratio", 1.25f).coerceIn(1.05f, 2.00f)
    set(v) = prefs.edit().putFloat("cutin_growth_ratio", v.coerceIn(1.05f, 2.00f)).apply()

    private const val KEY_MIGRATED_MASTER_SWITCHES_V1 = "migrated_master_switches_v1"

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        migrateMasterSwitchesToPerLevelOnce()
    }

    /**
     * U1 migrace: rušíme globální přepínače zvuku/hlasu v UX.
     * Abychom zachovali chování pro existující uživatele:
     * - pokud měl global sound/voice vypnuto, promítneme to do per-level přepínačů
     * - a následně nastavíme global na true (kvůli starým gate podmínkám v kódu).
     */
    private fun migrateMasterSwitchesToPerLevelOnce() {
        if (prefs.getBoolean(KEY_MIGRATED_MASTER_SWITCHES_V1, false)) return
        val hadSoundOff = prefs.getBoolean("sound", true).not()
        val hadVoiceOff = prefs.getBoolean("voice", false).not()

        val e = prefs.edit()
        if (hadSoundOff) {
            e.putBoolean("sound_orange", false)
            e.putBoolean("sound_red", false)
            e.putBoolean("sound", true)
        }
        if (hadVoiceOff) {
            // Pozor: voice default bylo false. Pokud bylo false, udržíme "ticho" přes per-level.
            e.putBoolean("voice_orange", false)
            e.putBoolean("voice_red", false)
            e.putBoolean("voice", true)
        }
        e.putBoolean(KEY_MIGRATED_MASTER_SWITCHES_V1, true).apply()
    }


    fun ensureInit(ctx: Context) {
        if (!::prefs.isInitialized) {
            init(ctx)
        }
    }

    // MODE SETTINGS
    const val MODE_AUTO = 0
    const val MODE_CITY = 1
    const val MODE_SPORT = 2
    const val MODE_USER = 3

    private const val KEY_MODE = "mode"
    private const val KEY_MODE_MIGRATED_V2 = "mode_migrated_v2"

    /**
     * Detekční režim (vybraný uživatelem v UI):
     * 0 = Automat (default)
     * 1 = Město
     * 2 = Sport
     * 3 = Uživatel
     *
     * Pozn.: proběhne migrace starých hodnot (Město=0, Sport=1, Uživatel=2) -> +1.
     */
    var detectionMode: Int
        get() {
            val raw = prefs.getInt(KEY_MODE, MODE_AUTO)
            val migrated = prefs.getBoolean(KEY_MODE_MIGRATED_V2, false)
            if (!migrated) {
                // v1: 0=Město, 1=Sport, 2=Uživatel -> v2: +1 (0 je nově Automat)
                if (raw in 0..2) {
                    val v2 = (raw + 1).coerceIn(MODE_AUTO, MODE_USER)
                    prefs.edit().putInt(KEY_MODE, v2).putBoolean(KEY_MODE_MIGRATED_V2, true).apply()
                    return v2
                }
                prefs.edit().putBoolean(KEY_MODE_MIGRATED_V2, true).apply()
            }
            val v = raw.coerceIn(MODE_AUTO, MODE_USER)
            // U1: uživatelský režim (MODE_USER) se v produkčním UX nepoužívá.
            // Pokud někde zůstala stará hodnota, mapujeme ji na AUTO, aby nevzniklo skryté chování.
            return if (v == MODE_USER) MODE_AUTO else v
        }
        set(v) = prefs.edit().putInt(KEY_MODE, v.coerceIn(MODE_AUTO, MODE_USER)).apply()

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

/**
 * Hlasitost zvuku alertu – 4 úrovně (diskrétní), mapované na %:
 * 0=Normální (55%), 1=Silná (70%), 2=Velmi silná (85%), 3=Max (100%).
 *
 * Pozn.: audio focus (priorita) zůstává beze změny – jen škálujeme hlasitost MediaPlayeru.
 *
 * Kompatibilita:
 * - Staré ukládání v % (sound_*_volume_pct) migrujeme na nejbližší úroveň při prvním čtení levelu.
 */
private val alertVolumePcts = intArrayOf(55, 70, 85, 100)

private fun pctToAlertLevel(pct: Int): Int {
    val p = pct.coerceIn(0, 100)
    return when {
        p < 63 -> 0
        p < 78 -> 1
        p < 93 -> 2
        else -> 3
    }
}

private fun getOrMigrateAlertLevel(levelKey: String, pctKey: String, defaultLevel: Int, defaultPct: Int): Int {
    if (prefs.contains(levelKey)) {
        return prefs.getInt(levelKey, defaultLevel).coerceIn(0, 3)
    }
    val level = if (prefs.contains(pctKey)) {
        pctToAlertLevel(prefs.getInt(pctKey, defaultPct))
    } else {
        defaultLevel
    }
    prefs.edit().putInt(levelKey, level).apply()
    return level
}

var soundOrangeVolumeLevel: Int
    get() = getOrMigrateAlertLevel(
        levelKey = "sound_orange_volume_level",
        pctKey = "sound_orange_volume_pct",
        defaultLevel = 2, // ORANGE default: Velmi silná
        defaultPct = 75
    )
    set(v) = prefs.edit().putInt("sound_orange_volume_level", v.coerceIn(0, 3)).apply()

var soundRedVolumeLevel: Int
    get() = getOrMigrateAlertLevel(
        levelKey = "sound_red_volume_level",
        pctKey = "sound_red_volume_pct",
        defaultLevel = 3, // RED default: Max
        defaultPct = 100
    )
    set(v) = prefs.edit().putInt("sound_red_volume_level", v.coerceIn(0, 3)).apply()

val soundOrangeVolumeScalar: Float
    get() = (alertVolumePcts[soundOrangeVolumeLevel] / 100f).coerceIn(0f, 1f)

val soundRedVolumeScalar: Float
    get() = (alertVolumePcts[soundRedVolumeLevel] / 100f).coerceIn(0f, 1f)

/**
 * Legacy % getters/setters (0..100). Udržujeme kvůli kompatibilitě.
 * Setter zároveň aktualizuje i "level" tak, aby UI i zvuk byly konzistentní.
 */
var soundOrangeVolumePct: Int
    get() = prefs.getInt("sound_orange_volume_pct", 75).coerceIn(0, 100)
    set(v) {
        val p = v.coerceIn(0, 100)
        prefs.edit()
            .putInt("sound_orange_volume_pct", p)
            .putInt("sound_orange_volume_level", pctToAlertLevel(p))
            .apply()
    }

var soundRedVolumePct: Int
    get() = prefs.getInt("sound_red_volume_pct", 100).coerceIn(0, 100)
    set(v) {
        val p = v.coerceIn(0, 100)
        prefs.edit()
            .putInt("sound_red_volume_pct", p)
            .putInt("sound_red_volume_level", pctToAlertLevel(p))
            .apply()
    }

var voiceOrange: Boolean
    get() = prefs.getBoolean("voice_orange", true)
    set(v) = prefs.edit().putBoolean("voice_orange", v).apply()

var voiceRed: Boolean
    get() = prefs.getBoolean("voice_red", true)
    set(v) = prefs.edit().putBoolean("voice_red", v).apply()

var ttsTextOrange: String
    get() = prefs.getString("tts_orange_text", "Koukej") ?: "Koukej"
    set(v) = prefs.edit().putString("tts_orange_text", v).apply()

var ttsTextRed: String
    get() = prefs.getString("tts_red_text", "Varování") ?: "Varování"
    set(v) = prefs.edit().putString("tts_red_text", v).apply()

    var debugOverlay: Boolean
        get() = prefs.getBoolean("debugOverlay", false)
        set(v) = prefs.edit().putBoolean("debugOverlay", v).apply()

    /**
     * U2/M1: samostatný přepínač pro zobrazení debug-only nastavení.
     * Vypnutí musí vrátit všechny debug overrides na AUTO/recommended kvůli determinismu testů.
     */
    var debugSettingsEnabled: Boolean
        get() = prefs.getBoolean("debugSettingsEnabled", false)
        set(v) = prefs.edit().putBoolean("debugSettingsEnabled", v).apply()

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
    private const val ROI_TRAP_CENTER_X = "roi_trap_center_x_n"

    // Default (rozumný jízdní pruh – upravitelné v Preview ROI editoru)
    private const val ROI_TRAP_DEFAULT_TOP_Y = 0.32f
    private const val ROI_TRAP_DEFAULT_BOTTOM_Y = 0.92f
    private const val ROI_TRAP_DEFAULT_TOP_HALFW = 0.18f
    private const val ROI_TRAP_DEFAULT_BOTTOM_HALFW = 0.46f
    private const val ROI_TRAP_DEFAULT_CENTER_X = 0.50f

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

    var roiTrapCenterX: Float
        get() = prefs.getFloat(ROI_TRAP_CENTER_X, ROI_TRAP_DEFAULT_CENTER_X).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(ROI_TRAP_CENTER_X, v.coerceIn(0f, 1f)).apply()

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
    var centerX = roiTrapCenterX

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

    // clamp centerX so trapezoid stays inside [0..1]
    val maxHalfW = maxOf(topHalfW, bottomHalfW).coerceIn(0f, 0.5f)
    centerX = centerX.coerceIn(maxHalfW, 1f - maxHalfW)

    return RoiTrapN(topY, bottomY, topHalfW, bottomHalfW, centerX = centerX)
}

fun setRoiTrapezoidNormalized(topY: Float, bottomY: Float, topHalfW: Float, bottomHalfW: Float) {
    setRoiTrapezoidNormalized(topY, bottomY, topHalfW, bottomHalfW, centerX = roiTrapCenterX)
}

fun setRoiTrapezoidNormalized(
    topY: Float,
    bottomY: Float,
    topHalfW: Float,
    bottomHalfW: Float,
    centerX: Float
) {
    var ty = topY.coerceIn(0f, 1f)
    var by = bottomY.coerceIn(0f, 1f)
    var thw = topHalfW.coerceIn(0f, 0.5f)
    var bhw = bottomHalfW.coerceIn(0f, 0.5f)
    var cx = centerX.coerceIn(0f, 1f)

    if (by < ty) { val tmp = by; by = ty; ty = tmp }
    if (by - ty < ROI_TRAP_MIN_HEIGHT_N) by = (ty + ROI_TRAP_MIN_HEIGHT_N).coerceIn(0f, 1f)

    if (thw < ROI_TRAP_MIN_TOP_HALFW_N) thw = ROI_TRAP_MIN_TOP_HALFW_N
    if (bhw < ROI_TRAP_MIN_BOTTOM_HALFW_N) bhw = ROI_TRAP_MIN_BOTTOM_HALFW_N
    if (bhw < thw) bhw = thw

    // clamp centerX so trapezoid stays inside [0..1]
    val maxHalfW = maxOf(thw, bhw).coerceIn(0f, 0.5f)
    cx = cx.coerceIn(maxHalfW, 1f - maxHalfW)

    prefs.edit()
        .putFloat(ROI_TRAP_TOP_Y, ty)
        .putFloat(ROI_TRAP_BOTTOM_Y, by)
        .putFloat(ROI_TRAP_TOP_HALFW, thw)
        .putFloat(ROI_TRAP_BOTTOM_HALFW, bhw)
        .putFloat(ROI_TRAP_CENTER_X, cx)
        .apply()
}

fun resetRoiToDefault() {
        setRoiTrapezoidNormalized(
            ROI_TRAP_DEFAULT_TOP_Y,
            ROI_TRAP_DEFAULT_BOTTOM_Y,
            ROI_TRAP_DEFAULT_TOP_HALFW,
            ROI_TRAP_DEFAULT_BOTTOM_HALFW,
            centerX = ROI_TRAP_DEFAULT_CENTER_X
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
        val centerX = ((l + r) * 0.5f).coerceIn(0f, 1f)
        val halfW = maxOf(abs(centerX - l), abs(r - centerX)).coerceIn(ROI_TRAP_MIN_TOP_HALFW_N, 0.5f)
        setRoiTrapezoidNormalized(t, b, halfW, halfW, centerX = centerX)
    }


    /**
     * U1: Když se vypne debug, chceme deterministické chování a žádné skryté "tuning" hodnoty.
     *
     * DŮLEŽITÉ: NEresetujeme kalibraci kamery (distanceScale / height / pitch) ani ROI trapezoid,
     * protože tyto hodnoty mohou být nutné pro reálné uchycení telefonu (dodávka/moto/kabrio).
     */
    fun resetDebugOverridesToAutoRecommended() {
        // Always use auto mode in production UX
        detectionMode = MODE_AUTO

        // Model selection: normal mode uses conservative default (EfficientDet=1).
        selectedModel = 1

        // Debug-only toggles / tunings -> recommended
        qualityGatingEnabled = true
        cutInProtectionEnabled = true
        cutInOffsetBoost = 0.25f
        cutInBoostMs = 900L
        cutInGrowthRatio = 1.25f

        // Lane / ROI tunings
        laneFilter = true
        laneEgoMaxOffset = 0.55f
        roiStrictContainment = false

        // Brake cue
        brakeCueEnabled = true
        brakeCueSensitivity = 1
    }

    /**
     * U1: uživatelské prahy se v produkčním UX nepoužívají.
     * Pomocná funkce pro tvrdý reset na doporučené (pro případ starých hodnot).
     */
    fun resetUserThresholdsToDefault() {
        userTtcOrange = 3.0f
        userTtcRed = 1.5f
        userDistOrange = 16f
        userDistRed = 9f
        userSpeedOrange = 3f
        userSpeedRed = 5f
    }

}
