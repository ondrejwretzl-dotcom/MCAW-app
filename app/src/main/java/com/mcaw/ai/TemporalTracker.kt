package com.mcaw.ai

import com.mcaw.model.Box
import com.mcaw.model.Detection
import kotlin.math.max
import kotlin.math.min

class TemporalTracker(
    private val minConsecutiveForAlert: Int = 3,
    private val iouMatchThreshold: Float = 0.2f, // puvodne 0.3
    private val maxMisses: Int = 3, // puvodne 2
    private val emaAlpha: Float = 0.25f // puvodne 0.5
) {
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

    fun update(detections: List<Detection>): List<TrackedDetection> {
        val unmatched = detections.toMutableList()

        for (track in tracks) {
            var best: Detection? = null
            var bestIou = 0f

            for (cand in unmatched) {
                if (cand.label != track.detection.label) continue
                val iouVal = iou(track.detection.box, cand.box)
                if (iouVal > bestIou) {
                    bestIou = iouVal
                    best = cand
                }
            }

            if (best != null && bestIou >= iouMatchThreshold) {
                track.detection = blend(track.detection, best)
                track.consecutive += 1
                track.misses = 0
                unmatched.remove(best)
            } else {
                track.consecutive = 0
                track.misses += 1
            }
        }

        for (det in unmatched) {
            tracks.add(Track(nextId++, det, 1, 0))
        }

        tracks.removeAll { it.misses > maxMisses }

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

    fun clear() = tracks.clear()

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
}
