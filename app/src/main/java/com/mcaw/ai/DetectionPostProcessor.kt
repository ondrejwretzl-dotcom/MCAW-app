package com.mcaw.ai

import android.util.Log
import com.mcaw.model.Box
import com.mcaw.model.Detection
import kotlin.math.max
import kotlin.math.min

class DetectionPostProcessor(
    private val config: Config = Config()
) {

    data class Config(
        val classThresholds: Map<String, Float> = mapOf(
            "car" to 0.30f,
            "van" to 0.30f,
            "bus" to 0.30f,
            "truck" to 0.30f,
            "motorcycle" to 0.35f,
            "bicycle" to 0.40f,
            "person" to 0.35f
        ),
        val defaultThreshold: Float = 0.25f,
        val nmsIouThreshold: Float = 0.45f,
        val minAreaRatio: Float = 0.0002f,
        val maxAreaRatio: Float = 0.95f,
        val minAspect: Float = 0.15f,
        val maxAspect: Float = 6.0f,
        val edgeMarginRatio: Float = 0.07f,
        val edgeFilterEnabled: Boolean = false,
        val debug: Boolean = false
    )

    data class RectNorm(val left: Float, val top: Float, val right: Float, val bottom: Float)

    data class Result(
        val accepted: List<Detection>,
        val rejected: List<RejectedDetection>,
        val counts: Counts
    )

    data class RejectedDetection(val detection: Detection, val reason: String)

    data class Counts(
        val raw: Int,
        val threshold: Int,
        val nms: Int,
        val filters: Int
    )

    /**
     * @param roiNorm If non-null, filters detections whose centers are outside ROI.
     */
    fun process(raw: List<Detection>, frameWidth: Float, frameHeight: Float, roiNorm: RectNorm? = null): Result {
        val rejected = mutableListOf<RejectedDetection>()

        val normalized = raw.map { detection ->
            val canonical = DetectionLabelMapper.toCanonical(detection.label)
            detection.copy(label = canonical ?: detection.label?.trim()?.lowercase() ?: "unknown")
        }

        val thresholded = normalized.filter { d ->
            val threshold = config.classThresholds[d.label] ?: config.defaultThreshold
            val passed = d.score >= threshold
            if (!passed) rejected.add(RejectedDetection(d, "lowScore"))
            passed
        }

        val nmsResult = classAwareNms(thresholded)
        rejected.addAll(nmsResult.rejected)

        val accepted = ArrayList<Detection>(nmsResult.accepted.size)
        for (det in nmsResult.accepted) {
            val reason = rejectionReason(det, frameWidth, frameHeight, roiNorm)
            if (reason == null) accepted.add(det) else rejected.add(RejectedDetection(det, reason))
        }

        if (config.debug) {
            Log.d(
                "DetectionPostProcessor",
                "counts raw=${raw.size} threshold=${thresholded.size} nms=${nmsResult.accepted.size} accepted=${accepted.size}"
            )
        }

        return Result(
            accepted = accepted,
            rejected = rejected,
            counts = Counts(raw.size, thresholded.size, nmsResult.accepted.size, accepted.size)
        )
    }

    private fun rejectionReason(det: Detection, frameW: Float, frameH: Float, roi: RectNorm?): String? {
        if (frameW <= 0f || frameH <= 0f) return "invalidFrame"
        val b = det.box

        val areaRatio = b.area / (frameW * frameH)
        if (areaRatio < config.minAreaRatio || areaRatio > config.maxAreaRatio) return "minArea"

        val aspect = if (b.h > 0f) b.w / b.h else Float.POSITIVE_INFINITY
        if (aspect < config.minAspect || aspect > config.maxAspect) return "aspect"

        val cx = b.cx / frameW
        val cy = b.cy / frameH

        if (config.edgeFilterEnabled) {
            val m = config.edgeMarginRatio
            if (cx < m || cx > 1f - m || cy < m || cy > 1f - m) return "edge"
        }

        if (roi != null) {
            val l = roi.left * frameW
            val t = roi.top * frameH
            val r = roi.right * frameW
            val btm = roi.bottom * frameH
            // Stricter: whole box must be inside ROI (not only center).
            if (b.x1 < l || b.y1 < t || b.x2 > r || b.y2 > btm) return "outsideROI"
        }
        return null
    }

    private data class NmsResult(
        val accepted: List<Detection>,
        val rejected: List<RejectedDetection>
    )

    /**
     * Less allocations than groupBy/sortedByDescending for each frame.
     */
    private fun classAwareNms(input: List<Detection>): NmsResult {
        val buckets = HashMap<String, MutableList<Detection>>(8)
        for (d in input) {
            val k = d.label ?: "unknown"
            (buckets[k] ?: ArrayList<Detection>(8).also { buckets[k] = it }).add(d)
        }

        val accepted = ArrayList<Detection>(input.size)
        val rejected = ArrayList<RejectedDetection>(input.size / 2)

        for (list in buckets.values) {
            list.sortByDescending { it.score }
            var i = 0
            while (i < list.size) {
                val best = list[i]
                accepted.add(best)
                var j = i + 1
                while (j < list.size) {
                    val cand = list[j]
                    if (iou(best.box, cand.box) > config.nmsIouThreshold) {
                        rejected.add(RejectedDetection(cand, "NMS"))
                        list.removeAt(j)
                    } else {
                        j++
                    }
                }
                i++
            }
        }

        accepted.sortByDescending { it.score }
        return NmsResult(accepted = accepted, rejected = rejected)
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
