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

        /**
         * ROI filter:
         * - when enabled, detection must be mostly inside ROI (intersection / detArea >= roiMinContainment)
         * - ROI is normalized in frame coords (0..1) and can be updated per-frame via setRoiOverride()
         */
        val roiFilterEnabled: Boolean = false,
        val roi: RectNorm = RectNorm(0.15f, 0.15f, 0.85f, 0.85f),
        val roiMinContainment: Float = 0.80f,

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

    // Can be updated on the fly (e.g., from prefs)
    @Volatile
    private var roiOverride: RectNorm? = null

    fun setRoiOverride(roi: RectNorm?) {
        roiOverride = roi
    }

    fun process(raw: List<Detection>, frameWidth: Float, frameHeight: Float): Result {
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

        val accepted = mutableListOf<Detection>()
        for (det in nmsResult.accepted) {
            val reason = rejectionReason(det, frameWidth, frameHeight)
            if (reason == null) {
                accepted.add(det)
            } else {
                rejected.add(RejectedDetection(det, reason))
            }
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

    private fun rejectionReason(det: Detection, frameW: Float, frameH: Float): String? {
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

        if (config.roiFilterEnabled) {
            val roi = (roiOverride ?: config.roi).sanitized()
            val containment = roiContainmentRatio(b, roi, frameW, frameH)
            if (containment < config.roiMinContainment) return "outsideROI"
        }
        return null
    }

    private fun RectNorm.sanitized(): RectNorm {
        var l = left.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (r < l) { val tmp = r; r = l; l = tmp }
        if (b < t) { val tmp = b; b = t; t = tmp }
        // avoid degenerate ROI
        if (r - l < 0.01f) r = (l + 0.01f).coerceIn(0f, 1f)
        if (b - t < 0.01f) b = (t + 0.01f).coerceIn(0f, 1f)
        return RectNorm(l, t, r, b)
    }

    /**
     * Returns how much of the detection area lies inside ROI:
     * intersection(det, roi) / area(det)
     */
    private fun roiContainmentRatio(det: Box, roiN: RectNorm, frameW: Float, frameH: Float): Float {
        val roiLeft = roiN.left * frameW
        val roiTop = roiN.top * frameH
        val roiRight = roiN.right * frameW
        val roiBottom = roiN.bottom * frameH

        val detLeft = min(det.x1, det.x2)
        val detTop = min(det.y1, det.y2)
        val detRight = max(det.x1, det.x2)
        val detBottom = max(det.y1, det.y2)

        val detArea = max(0f, detRight - detLeft) * max(0f, detBottom - detTop)
        if (detArea <= 0f) return 0f

        val interLeft = max(detLeft, roiLeft)
        val interTop = max(detTop, roiTop)
        val interRight = min(detRight, roiRight)
        val interBottom = min(detBottom, roiBottom)

        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        return (interArea / detArea).coerceIn(0f, 1f)
    }

    private data class NmsResult(
        val accepted: List<Detection>,
        val rejected: List<RejectedDetection>
    )

    private fun classAwareNms(input: List<Detection>): NmsResult {
        val accepted = mutableListOf<Detection>()
        val rejected = mutableListOf<RejectedDetection>()

        input.groupBy { it.label ?: "unknown" }
            .values
            .forEach { list ->
                val mutable = list.sortedByDescending { it.score }.toMutableList()
                while (mutable.isNotEmpty()) {
                    val best = mutable.removeAt(0)
                    accepted.add(best)
                    val it = mutable.iterator()
                    while (it.hasNext()) {
                        val candidate = it.next()
                        if (iou(best.box, candidate.box) > config.nmsIouThreshold) {
                            rejected.add(RejectedDetection(candidate, "NMS"))
                            it.remove()
                        }
                    }
                }
            }

        return NmsResult(
            accepted = accepted.sortedByDescending { it.score },
            rejected = rejected
        )
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
