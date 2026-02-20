package com.mcaw.config

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight local profile storage.
 *
 * - Offline only
 * - No IO in frame loop
 * - Uses SharedPreferences JSON blob (small data)
 */
object ProfileManager {

    private const val PREF_NAME = "mcaw_profiles"
    private const val KEY_ACTIVE_ID = "active_profile_id"
    private const val KEY_LIST_JSON = "profiles_json"

    private lateinit var prefs: SharedPreferences

    fun ensureInit(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
        // Make sure AppPreferences is initialized too.
        AppPreferences.ensureInit(ctx)
    }

    fun getActiveProfileIdOrNull(): String? {
        if (!::prefs.isInitialized) return null
        val id = prefs.getString(KEY_ACTIVE_ID, null)
        return if (id.isNullOrBlank()) null else id
    }

    fun setActiveProfileId(id: String?) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }


    /** Back-compat convenience. */
    fun getActiveProfileId(): String? = getActiveProfileIdOrNull()

    fun getProfileNameById(id: String): String? = findById(id)?.name
    fun listProfiles(): List<MountProfile> {
        if (!::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY_LIST_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<MountProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseProfile(o)?.let(out::add)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

        fun saveProfileFromCurrentPrefs(name: String): MountProfile {
        check(::prefs.isInitialized) { "ProfileManager not initialized" }
        val id = "p_" + SystemClock.uptimeMillis().toString()
        val p = buildProfileFromCurrentPrefs(id = id, name = name.ifBlank { "Profil" })
        upsert(p)
        return p
    }

    /**
     * Overwrites an existing profile with current AppPreferences values.
     * Returns the updated profile or null if the profile doesn't exist.
     */
    fun overwriteProfileFromCurrentPrefs(profileId: String): MountProfile? {
        check(::prefs.isInitialized) { "ProfileManager not initialized" }
        val existing = findById(profileId) ?: return null
        val updated = buildProfileFromCurrentPrefs(id = existing.id, name = existing.name)
        upsert(updated)
        return updated
    }

    private fun buildProfileFromCurrentPrefs(id: String, name: String): MountProfile {
        val roi = AppPreferences.getRoiTrapezoidNormalized()
        return MountProfile(
            id = id,
            name = name,
            cameraHeightM = AppPreferences.cameraMountHeightM,
            cameraPitchDownDeg = AppPreferences.cameraPitchDownDeg,
            cameraZoomRatio = AppPreferences.cameraZoomRatio,
            distanceScale = AppPreferences.distanceScale,
            calibrationRmsM = AppPreferences.calibrationRmsM,
            calibrationMaxErrM = AppPreferences.calibrationMaxErrM,
            calibrationImuStdDeg = AppPreferences.calibrationImuStdDeg,
            calibrationSavedUptimeMs = AppPreferences.calibrationSavedUptimeMs,
            calibrationQuality = AppPreferences.calibrationQuality,
            calibrationGeomQuality = AppPreferences.calibrationGeomQuality,
            calibrationImuQuality = AppPreferences.calibrationImuQuality,
            calibrationImuExtraErrAt10m = AppPreferences.calibrationImuExtraErrAt10m,
            calibrationCombinedErrAt10m = AppPreferences.calibrationCombinedErrAt10m,
            laneEgoMaxOffset = AppPreferences.laneEgoMaxOffset,
            roiTopY = roi.topY,
            roiBottomY = roi.bottomY,
            roiTopHalfW = roi.topHalfW,
            roiBottomHalfW = roi.bottomHalfW,
            roiCenterX = roi.centerX,
        )
    }

    fun upsert(profile: MountProfile) {
        check(::prefs.isInitialized) { "ProfileManager not initialized" }
        val list = listProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persistList(list)
    }

    fun delete(profileId: String) {
        if (!::prefs.isInitialized) return
        val list = listProfiles().filterNot { it.id == profileId }
        persistList(list)
        if (getActiveProfileIdOrNull() == profileId) {
            setActiveProfileId(null)
        }
    }

    fun findById(profileId: String): MountProfile? {
        return listProfiles().firstOrNull { it.id == profileId }
    }

    /**
     * Applies active profile (if any) to AppPreferences.
     * Safe to call at start of Preview/Service.
     */
    fun applyActiveProfileToPreferences(): Boolean {
        if (!::prefs.isInitialized) return false
        val id = getActiveProfileIdOrNull() ?: return false
        val p = findById(id) ?: return false

        // Mount
        AppPreferences.cameraMountHeightM = p.cameraHeightM
        AppPreferences.cameraPitchDownDeg = p.cameraPitchDownDeg
        AppPreferences.cameraZoomRatio = p.cameraZoomRatio
        AppPreferences.distanceScale = p.distanceScale
        AppPreferences.laneEgoMaxOffset = p.laneEgoMaxOffset

        // Calibration metrics (optional)
        AppPreferences.calibrationRmsM = p.calibrationRmsM
        AppPreferences.calibrationMaxErrM = p.calibrationMaxErrM
        AppPreferences.calibrationImuStdDeg = p.calibrationImuStdDeg
        AppPreferences.calibrationSavedUptimeMs = p.calibrationSavedUptimeMs
        AppPreferences.calibrationQuality = p.calibrationQuality
        AppPreferences.calibrationGeomQuality = p.calibrationGeomQuality
        AppPreferences.calibrationImuQuality = p.calibrationImuQuality
        AppPreferences.calibrationImuExtraErrAt10m = p.calibrationImuExtraErrAt10m
        AppPreferences.calibrationCombinedErrAt10m = p.calibrationCombinedErrAt10m

        // ROI
        AppPreferences.setRoiTrapezoidNormalized(
            topY = p.roiTopY,
            bottomY = p.roiBottomY,
            topHalfW = p.roiTopHalfW,
            bottomHalfW = p.roiBottomHalfW,
            centerX = p.roiCenterX
        )
        return true
    }

    private fun persistList(list: List<MountProfile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(toJson(p))
        }
        prefs.edit().putString(KEY_LIST_JSON, arr.toString()).apply()
    }

    private fun toJson(p: MountProfile): JSONObject {
        return JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("cameraHeightM", p.cameraHeightM.toDouble())
            put("cameraPitchDownDeg", p.cameraPitchDownDeg.toDouble())
            put("cameraZoomRatio", p.cameraZoomRatio.toDouble())
            put("distanceScale", p.distanceScale.toDouble())
            put("calibrationRmsM", p.calibrationRmsM.toDouble())
            put("calibrationMaxErrM", p.calibrationMaxErrM.toDouble())
            put("calibrationImuStdDeg", p.calibrationImuStdDeg.toDouble())
            put("calibrationSavedUptimeMs", p.calibrationSavedUptimeMs)
            put("calibrationQuality", p.calibrationQuality)
            put("calibrationGeomQuality", p.calibrationGeomQuality)
            put("calibrationImuQuality", p.calibrationImuQuality)
            put("calibrationImuExtraErrAt10m", p.calibrationImuExtraErrAt10m.toDouble())
            put("calibrationCombinedErrAt10m", p.calibrationCombinedErrAt10m.toDouble())
            put("laneEgoMaxOffset", p.laneEgoMaxOffset.toDouble())
            put("roiTopY", p.roiTopY.toDouble())
            put("roiBottomY", p.roiBottomY.toDouble())
            put("roiTopHalfW", p.roiTopHalfW.toDouble())
            put("roiBottomHalfW", p.roiBottomHalfW.toDouble())
            put("roiCenterX", p.roiCenterX.toDouble())
        }
    }

    private fun parseProfile(o: JSONObject): MountProfile? {
        val id = o.optString("id")
        val name = o.optString("name")
        if (id.isNullOrBlank() || name.isNullOrBlank()) return null
        return MountProfile(
            id = id,
            name = name,
            cameraHeightM = o.optDouble("cameraHeightM", 1.2).toFloat(),
            cameraPitchDownDeg = o.optDouble("cameraPitchDownDeg", 6.0).toFloat(),
            cameraZoomRatio = o.optDouble("cameraZoomRatio", 1.0).toFloat(),
            distanceScale = o.optDouble("distanceScale", 1.0).toFloat(),
            calibrationRmsM = o.optDouble("calibrationRmsM", 0.0).toFloat(),
            calibrationMaxErrM = o.optDouble("calibrationMaxErrM", 0.0).toFloat(),
            calibrationImuStdDeg = o.optDouble("calibrationImuStdDeg", 0.0).toFloat(),
            calibrationSavedUptimeMs = o.optLong("calibrationSavedUptimeMs", 0L),
            calibrationQuality = o.optInt("calibrationQuality", 0),
            calibrationGeomQuality = o.optInt("calibrationGeomQuality", 0),
            calibrationImuQuality = o.optInt("calibrationImuQuality", 0),
            calibrationImuExtraErrAt10m = o.optDouble("calibrationImuExtraErrAt10m", 0.0).toFloat(),
            calibrationCombinedErrAt10m = o.optDouble("calibrationCombinedErrAt10m", 0.0).toFloat(),
            laneEgoMaxOffset = o.optDouble("laneEgoMaxOffset", 0.55).toFloat(),
            roiTopY = o.optDouble("roiTopY", 0.32).toFloat(),
            roiBottomY = o.optDouble("roiBottomY", 0.92).toFloat(),
            roiTopHalfW = o.optDouble("roiTopHalfW", 0.18).toFloat(),
            roiBottomHalfW = o.optDouble("roiBottomHalfW", 0.46).toFloat(),
            roiCenterX = o.optDouble("roiCenterX", 0.5).toFloat(),
        )
    }
}
test
