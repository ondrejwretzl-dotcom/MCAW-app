package com.mcaw.ai

import com.mcaw.model.Box
import com.mcaw.model.Detection
import kotlin.math.max
import kotlin.math.min

class TemporalTracker(
    private val minConsecutiveForAlert: Int = 2,
    private val iouMatchThreshold: Float = 0.2f, // puvodne 0.3
    private val maxMisses: Int = 3, // puvodne 2
    private val emaAlpha: Float = 0.25f, // puvodne 0.5
    private val lockGraceMs: Long = 400L,
    private val lockGraceMaxMissFrames: Int = 3,
    private val switchConfirmFrames: Int = 3,
    private val switchMargin: Float = 0.08f,
    private val minLockAgeFramesBeforeSwitch: Int = 3
) {
    enum class SwitchReason { NONE, GRACE_EXPIRED, BETTER_STABLE, LOST, OCCLUSION_MATCH }
    data class TrackedDetection(
        val id: Long,
        val detection: Detection,
        val consecutiveDetections: Int,
        val misses: Int,
        val alertGatePassed: Boolean
    )

    private data class Track(
        val id: Long,
        var detection: Detection,
        var consecutive: Int,
        var misses: Int
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1L
    private var lockedTrackId: Long? = null
    private var lockedAgeFrames: Int = 0
    private var lockedMissFrames: Int = 0
    private var lockMissingSinceMs: Long = -1L
    private var switchCandidateId: Long? = null
    private var switchCandidateCount: Int = 0
    private var lastSwitchReason: SwitchReason = SwitchReason.NONE
    private var lastOcclusionMatchUsed = false
    private var frameCounter: Long = 0L

    // Reuse buffer to avoid per-frame allocations (growth is rare).
    private var matchedFlags = BooleanArray(0)

    fun update(detections: List<Detection>, tsMs: Long = -1L, bottomOccluded: Boolean = false): List<TrackedDetection> {
        frameCounter += 1
        lastSwitchReason = SwitchReason.NONE
        lastOcclusionMatchUsed = false
        if (matchedFlags.size < detections.size) {
            matchedFlags = BooleanArray(max(detections.size, matchedFlags.size * 2))
        }
        // Reset only the used prefix.
        for (i in 0 until detections.size) matchedFlags[i] = false

        // Track update: prefer temporal continuity. Allow label changes inside VEHICLE group
        // to avoid lock drops when the detector toggles car/truck/unknown.
        for (track in tracks) {
            var bestIdx = -1
            var bestScore = 0f
            val prevBox = track.detection.box
            val prevLabel = track.detection.label

            for (i in 0 until detections.size) {
                if (matchedFlags[i]) continue
                val det = detections[i]
                if (!isCompatible(prevLabel, det.label)) continue

                val iouVal = iou(prevBox, det.box)
                val isLockedTrack = track.id == lockedTrackId
                val occlusionMatched = isLockedTrack && bottomOccluded && isOcclusionFallbackMatch(prevBox, det.box)
                if (iouVal < iouMatchThreshold && !occlusionMatched) continue

                // Small center proximity bonus keeps lock stable when IoU ties.
                val centerBonus = centerProximity(prevBox, det.box) * 0.12f
                val score = iouVal + centerBonus
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                    lastOcclusionMatchUsed = occlusionMatched
                }
            }

            if (bestIdx >= 0) {
                val best = detections[bestIdx]
                matchedFlags[bestIdx] = true
                track.detection = blend(track.detection, best)
                track.consecutive += 1
                track.misses = 0
            } else {
                // Keep track alive for a few frames; do not hard-drop on a single miss.
                track.consecutive = 0
                track.misses += 1
            }
        }

        // New tracks for unmatched detections.
        for (i in 0 until detections.size) {
            if (!matchedFlags[i]) {
                tracks.add(Track(nextId++, detections[i], 1, 0))
            }
        }

        tracks.removeAll { it.misses > maxMisses }
        updateLockState(tsMs)

        return tracks.map {
            TrackedDetection(
                id = it.id,
                detection = it.detection.copy(trackId = it.id),
                consecutiveDetections = it.consecutive,
                misses = it.misses,
                alertGatePassed = it.consecutive >= minConsecutiveForAlert
            )
        }
    }

    fun clear() {
        tracks.clear()
        lockedTrackId = null
        lockedAgeFrames = 0
        lockedMissFrames = 0
        lockMissingSinceMs = -1L
        switchCandidateId = null
        switchCandidateCount = 0
        lastSwitchReason = SwitchReason.NONE
        lastOcclusionMatchUsed = false
    }

    fun getLockedTrackId(): Long? = lockedTrackId
    fun getLockedAgeFrames(): Int = lockedAgeFrames
    fun getLockedMissFrames(): Int = lockedMissFrames
    fun isLockGraceActive(tsMs: Long): Boolean {
        val lockId = lockedTrackId ?: return false
        val track = tracks.firstOrNull { it.id == lockId } ?: return false
        if (track.misses <= 0) return false
        return graceActive(track.misses, tsMs)
    }
    fun getLastSwitchReason(): SwitchReason = lastSwitchReason
    fun wasLastMatchOcclusionFallback(): Boolean = lastOcclusionMatchUsed

    private enum class LabelGroup { VEHICLE, PERSON, OTHER, UNKNOWN }

    private fun groupOf(label: String?): LabelGroup {
        val l = label?.lowercase() ?: return LabelGroup.UNKNOWN
        return when (l) {
            "car", "truck", "bus", "van" -> LabelGroup.VEHICLE
            "person" -> LabelGroup.PERSON
            "unknown" -> LabelGroup.UNKNOWN
            else -> LabelGroup.OTHER
        }
    }

    private fun isCompatible(trackLabel: String?, detLabel: String?): Boolean {
        // Exact match always allowed.
        if (trackLabel == detLabel) return true

        val tg = groupOf(trackLabel)
        val dg = groupOf(detLabel)

        // Allow detector label toggling inside vehicle family.
        if (tg == LabelGroup.VEHICLE && dg == LabelGroup.VEHICLE) return true

        // Allow UNKNOWN to attach to an existing vehicle track (prevents lock drops).
        if (tg == LabelGroup.VEHICLE && dg == LabelGroup.UNKNOWN) return true

        return false
    }

    private fun blend(prev: Detection, curr: Detection): Detection {
        val b = Box(
            prev.box.x1 * (1 - emaAlpha) + curr.box.x1 * emaAlpha,
            prev.box.y1 * (1 - emaAlpha) + curr.box.y1 * emaAlpha,
            prev.box.x2 * (1 - emaAlpha) + curr.box.x2 * emaAlpha,
            prev.box.y2 * (1 - emaAlpha) + curr.box.y2 * emaAlpha
        )
        val s = prev.score * (1 - emaAlpha) + curr.score * emaAlpha
        return curr.copy(box = b, score = s)
    }

    private fun iou(a: Box, b: Box): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun centerProximity(a: Box, b: Box): Float {
        val ax = (a.x1 + a.x2) * 0.5f
        val ay = (a.y1 + a.y2) * 0.5f
        val bx = (b.x1 + b.x2) * 0.5f
        val by = (b.y1 + b.y2) * 0.5f
        // Normalized manhattan distance in [0..2], proximity in [0..1].
        val d = min(2f, kotlin.math.abs(ax - bx) + kotlin.math.abs(ay - by))
        return 1f - (d * 0.5f)
    }

    private fun isOcclusionFallbackMatch(prev: Box, curr: Box): Boolean {
        val minHeight = min(prev.y2 - prev.y1, curr.y2 - curr.y1)
        val minArea = min(prev.area, curr.area)
        if (minHeight < 36f || minArea < 1500f) return false

        val prevCx = (prev.x1 + prev.x2) * 0.5f
        val prevCy = (prev.y1 + prev.y2) * 0.5f
        val currCx = (curr.x1 + curr.x2) * 0.5f
        val currCy = (curr.y1 + curr.y2) * 0.5f
        val centerMaxPx = max(minHeight * 0.45f, 24f)
        val dx = kotlin.math.abs(currCx - prevCx)
        val dy = kotlin.math.abs(currCy - prevCy)
        if (dx > centerMaxPx || dy > centerMaxPx) return false

        val overlapX = max(0f, min(prev.x2, curr.x2) - max(prev.x1, curr.x1))
        val minWidth = min(prev.x2 - prev.x1, curr.x2 - curr.x1).coerceAtLeast(1f)
        return (overlapX / minWidth) >= 0.45f
    }

    private fun updateLockState(tsMs: Long) {
        val visibleTracks = tracks.filter { it.misses == 0 }
        val bestVisible = visibleTracks.maxByOrNull { trackScore(it) }
        val lockId = lockedTrackId
        val locked = if (lockId != null) tracks.firstOrNull { it.id == lockId } else null

        if (locked == null) {
            if (bestVisible != null) {
                lockedTrackId = bestVisible.id
                lockedAgeFrames = 1
                lockedMissFrames = 0
                lockMissingSinceMs = -1L
                switchCandidateId = null
                switchCandidateCount = 0
            }
            return
        }

        if (locked.misses == 0) {
            lockedAgeFrames += 1
            lockedMissFrames = 0
            lockMissingSinceMs = -1L
        } else {
            lockedMissFrames = locked.misses
            if (lockMissingSinceMs < 0L) lockMissingSinceMs = tsMs
            if (!graceActive(lockedMissFrames, tsMs)) {
                if (bestVisible != null && bestVisible.id != locked.id) {
                    lockedTrackId = bestVisible.id
                    lockedAgeFrames = 1
                    lockedMissFrames = 0
                    lockMissingSinceMs = -1L
                    switchCandidateId = null
                    switchCandidateCount = 0
                    lastSwitchReason = SwitchReason.GRACE_EXPIRED
                } else if (tracks.none { it.id == locked.id }) {
                    lockedTrackId = null
                    lockedAgeFrames = 0
                    lockedMissFrames = 0
                    switchCandidateId = null
                    switchCandidateCount = 0
                    lastSwitchReason = SwitchReason.LOST
                }
                return
            }
        }

        if (bestVisible == null || bestVisible.id == lockedTrackId) {
            switchCandidateId = null
            switchCandidateCount = 0
            return
        }

        val lockedScore = trackScore(locked)
        val bestScore = trackScore(bestVisible)
        val canSwitch =
            lockedAgeFrames >= minLockAgeFramesBeforeSwitch &&
                bestScore >= (lockedScore + switchMargin) &&
                !graceActive(lockedMissFrames, tsMs)

        if (!canSwitch) {
            switchCandidateId = null
            switchCandidateCount = 0
            return
        }

        if (switchCandidateId == bestVisible.id) {
            switchCandidateCount += 1
        } else {
            switchCandidateId = bestVisible.id
            switchCandidateCount = 1
        }

        if (switchCandidateCount >= switchConfirmFrames) {
            lockedTrackId = bestVisible.id
            lockedAgeFrames = 1
            lockedMissFrames = 0
            lockMissingSinceMs = -1L
            switchCandidateId = null
            switchCandidateCount = 0
            lastSwitchReason = SwitchReason.BETTER_STABLE
        }
    }

    // Keep score on the same [0..1] scale used by detector confidence.
    // Switch margin tuning (e.g., 0.08) relies on this stable scale.
    private fun trackScore(track: Track): Float = track.detection.score.coerceIn(0f, 1f)

    private fun graceActive(missFrames: Int, tsMs: Long): Boolean {
        if (missFrames <= 0) return false
        if (missFrames > lockGraceMaxMissFrames) return false
        if (tsMs < 0L || lockMissingSinceMs < 0L) return true
        return (tsMs - lockMissingSinceMs) <= lockGraceMs
    }
}
