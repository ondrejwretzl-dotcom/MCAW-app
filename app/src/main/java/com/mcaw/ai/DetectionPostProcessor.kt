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
            "car" to 0.35f,
            "van" to 0.35f,
            "bus" to 0.35f,
            "truck" to 0.35f,
            "motorcycle" to 0.40f,
            "bicycle" to 0.50f,
            "person" to 0.45f
        ),
        val defaultThreshold: Float = 0.40f,
        val nmsIouThreshold: Float = 0.45f,
        val minAreaRatio: Float = 0.002f,
        val maxAreaRatio: Float = 0.90f,
        val minAspect: Float = 0.2f,
        val maxAspect: Float = 4.5f,
        val edgeMarginRatio: Float = 0.07f,
        val edgeFilterEnabled: Boolean = true,
        val roi: RectNorm = RectNorm(0.45f, 0.10f, 1.0f, 0.95f),
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

    fun process(raw: List<Detection>, frameWidth: Float, frameHeight: Float): Result {
        val canonical = raw.mapNotNull { d ->
            val normalized = DetectionLabelMapper.toCanonical(d.label) ?: return@mapNotNull null
            d.copy(label = normalized)
        }
        val thresholded = canonical.filter { d ->
            val threshold = config.classThresholds[d.label] ?: config.defaultThreshold
            d.score >= threshold
        }
        val nms = classAwareNms(thresholded)

        val accepted = mutableListOf<Detection>()
        val rejected = mutableListOf<RejectedDetection>()
        for (det in nms) {
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
                "counts raw=${raw.size} threshold=${thresholded.size} nms=${nms.size} accepted=${accepted.size}"
            )
        }

        return Result(
            accepted = accepted,
            rejected = rejected,
            counts = Counts(raw.size, thresholded.size, nms.size, accepted.size)
        )
    }

    private fun rejectionReason(det: Detection, frameW: Float, frameH: Float): String? {
        if (frameW <= 0f || frameH <= 0f) return "invalid_frame"
        val b = det.box
        val areaRatio = b.area / (frameW * frameH)
        if (areaRatio < config.minAreaRatio || areaRatio > config.maxAreaRatio) return "area"
        val aspect = if (b.h > 0f) b.w / b.h else Float.POSITIVE_INFINITY
        if (aspect < config.minAspect || aspect > config.maxAspect) return "aspect"

        val cx = b.cx / frameW
        val cy = b.cy / frameH
        if (config.edgeFilterEnabled) {
            val m = config.edgeMarginRatio
            if (cx < m || cx > 1f - m || cy < m || cy > 1f - m) return "edge"
        }

        val roi = config.roi
        if (cx !in roi.left..roi.right || cy !in roi.top..roi.bottom) return "roi"
        return null
    }

    private fun classAwareNms(input: List<Detection>): List<Detection> {
        return input.groupBy { it.label ?: "unknown" }
            .values
            .flatMap { list ->
                val mutable = list.sortedByDescending { it.score }.toMutableList()
                val out = mutableListOf<Detection>()
                while (mutable.isNotEmpty()) {
                    val best = mutable.removeAt(0)
                    out.add(best)
                    val it = mutable.iterator()
                    while (it.hasNext()) {
                        if (iou(best.box, it.next().box) > config.nmsIouThreshold) it.remove()
                    }
                }
                out
            }
            .sortedByDescending { it.score }
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
