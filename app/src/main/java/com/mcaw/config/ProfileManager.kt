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
            calibrationRoiImpactLevel = AppPreferences.calibrationRoiImpactLevel,
            laneEgoMaxOffset = AppPreferences.laneEgoMaxOffset,
            roiTopY = roi.topY,
            roiBottomY = roi.bottomY,
            roiTopHalfW = roi.topHalfW,
            roiBottomHalfW = roi.bottomHalfW,
            roiCenterX = roi.centerX,
            calibrationRefRoiTopY = AppPreferences.calibrationRefRoiTopY,
            calibrationRefRoiBottomY = AppPreferences.calibrationRefRoiBottomY,
            calibrationRefRoiTopHalfW = AppPreferences.calibrationRefRoiTopHalfW,
            calibrationRefRoiBottomHalfW = AppPreferences.calibrationRefRoiBottomHalfW,
            calibrationRefRoiCenterX = AppPreferences.calibrationRefRoiCenterX,
            calibrationRefZoomRatio = AppPreferences.calibrationRefZoomRatio,
        )
    }

    /**
     * Overwrites an existing profile with current AppPreferences values and optionally renames it.
     * Returns the updated profile or null if the profile doesn't exist.
     */
    fun overwriteProfileFromCurrentPrefs(profileId: String, newName: String): MountProfile? {
        check(::prefs.isInitialized) { "ProfileManager not initialized" }
        val existing = findById(profileId) ?: return null
        val updated = buildProfileFromCurrentPrefs(id = existing.id, name = newName.ifBlank { existing.name })
        upsert(updated)
        return updated
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
        AppPreferences.calibrationRoiImpactLevel = p.calibrationRoiImpactLevel

        // ROI
        AppPreferences.setRoiTrapezoidNormalized(
            topY = p.roiTopY,
            bottomY = p.roiBottomY,
            topHalfW = p.roiTopHalfW,
            bottomHalfW = p.roiBottomHalfW,
            centerX = p.roiCenterX
        )
        AppPreferences.calibrationRefRoiTopY = p.calibrationRefRoiTopY
        AppPreferences.calibrationRefRoiBottomY = p.calibrationRefRoiBottomY
        AppPreferences.calibrationRefRoiTopHalfW = p.calibrationRefRoiTopHalfW
        AppPreferences.calibrationRefRoiBottomHalfW = p.calibrationRefRoiBottomHalfW
        AppPreferences.calibrationRefRoiCenterX = p.calibrationRefRoiCenterX
        AppPreferences.calibrationRefZoomRatio = p.calibrationRefZoomRatio
        return true
    }

    private fun persistList(list: List<MountProfile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(toJson(p))
        }
        prefs.edit().putString(KEY_LIST_JSON, arr.toString()).apply()
    }

    private fun safeJsonNumber(v: Float): Any =
        if (v.isFinite()) v.toDouble() else JSONObject.NULL

    private fun toJson(p: MountProfile): JSONObject {
        return JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("cameraHeightM", safeJsonNumber(p.cameraHeightM))
            put("cameraPitchDownDeg", safeJsonNumber(p.cameraPitchDownDeg))
            put("cameraZoomRatio", safeJsonNumber(p.cameraZoomRatio))
            put("distanceScale", safeJsonNumber(p.distanceScale))
            put("calibrationRmsM", safeJsonNumber(p.calibrationRmsM))
            put("calibrationMaxErrM", safeJsonNumber(p.calibrationMaxErrM))
            put("calibrationImuStdDeg", safeJsonNumber(p.calibrationImuStdDeg))
            put("calibrationSavedUptimeMs", p.calibrationSavedUptimeMs)
            put("calibrationQuality", p.calibrationQuality)
            put("calibrationGeomQuality", p.calibrationGeomQuality)
            put("calibrationImuQuality", p.calibrationImuQuality)
            put("calibrationImuExtraErrAt10m", safeJsonNumber(p.calibrationImuExtraErrAt10m))
            put("calibrationCombinedErrAt10m", safeJsonNumber(p.calibrationCombinedErrAt10m))
            put("calibrationRoiImpactLevel", p.calibrationRoiImpactLevel)
            put("laneEgoMaxOffset", safeJsonNumber(p.laneEgoMaxOffset))
            put("roiTopY", safeJsonNumber(p.roiTopY))
            put("roiBottomY", safeJsonNumber(p.roiBottomY))
            put("roiTopHalfW", safeJsonNumber(p.roiTopHalfW))
            put("roiBottomHalfW", safeJsonNumber(p.roiBottomHalfW))
            put("roiCenterX", safeJsonNumber(p.roiCenterX))
            put("calibrationRefRoiTopY", safeJsonNumber(p.calibrationRefRoiTopY))
            put("calibrationRefRoiBottomY", safeJsonNumber(p.calibrationRefRoiBottomY))
            put("calibrationRefRoiTopHalfW", safeJsonNumber(p.calibrationRefRoiTopHalfW))
            put("calibrationRefRoiBottomHalfW", safeJsonNumber(p.calibrationRefRoiBottomHalfW))
            put("calibrationRefRoiCenterX", safeJsonNumber(p.calibrationRefRoiCenterX))
            put("calibrationRefZoomRatio", safeJsonNumber(p.calibrationRefZoomRatio))
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
            calibrationRoiImpactLevel = o.optInt("calibrationRoiImpactLevel", 0),
            laneEgoMaxOffset = o.optDouble("laneEgoMaxOffset", 0.55).toFloat(),
            roiTopY = o.optDouble("roiTopY", 0.32).toFloat(),
            roiBottomY = o.optDouble("roiBottomY", 0.92).toFloat(),
            roiTopHalfW = o.optDouble("roiTopHalfW", 0.18).toFloat(),
            roiBottomHalfW = o.optDouble("roiBottomHalfW", 0.46).toFloat(),
            roiCenterX = o.optDouble("roiCenterX", 0.5).toFloat(),
            calibrationRefRoiTopY = o.optDouble("calibrationRefRoiTopY", Double.NaN).toFloat(),
            calibrationRefRoiBottomY = o.optDouble("calibrationRefRoiBottomY", Double.NaN).toFloat(),
            calibrationRefRoiTopHalfW = o.optDouble("calibrationRefRoiTopHalfW", Double.NaN).toFloat(),
            calibrationRefRoiBottomHalfW = o.optDouble("calibrationRefRoiBottomHalfW", Double.NaN).toFloat(),
            calibrationRefRoiCenterX = o.optDouble("calibrationRefRoiCenterX", Double.NaN).toFloat(),
            calibrationRefZoomRatio = o.optDouble("calibrationRefZoomRatio", Double.NaN).toFloat(),
        )
    }
}
