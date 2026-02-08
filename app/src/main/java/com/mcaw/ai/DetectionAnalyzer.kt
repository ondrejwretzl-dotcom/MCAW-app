package com.mcaw.ai

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.location.SpeedProvider
import com.mcaw.model.Box
import com.mcaw.model.Detection
import com.mcaw.util.PublicLogWriter
import java.util.Locale
import kotlin.math.abs

class DetectionAnalyzer(
    private val ctx: Context,
    private val yolo: YoloOnnxDetector?,
    private val det: EfficientDetTFLiteDetector?,
    private val speedProvider: SpeedProvider
) : ImageAnalysis.Analyzer {

    companion object {
        const val ACTION_METRICS_UPDATE = "MCAW_METRICS_UPDATE"
        const val EXTRA_TTC = "extra_ttc"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_SPEED = "extra_speed" // signed relative speed (approach +, receding -)
        const val EXTRA_OBJECT_SPEED = "extra_object_speed" // estimated object speed (m/s)
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_LABEL = "extra_label"
    }

    private val analyzerLogFileName: String = "mcaw_analyzer_${System.currentTimeMillis()}.txt"

    private val postProcessor = DetectionPostProcessor(
        DetectionPostProcessor.Config(debug = AppPreferences.debugOverlay)
    )

    private val tracker = TemporalTracker(minConsecutiveForAlert = 3)

    // Cache for ROI values used for both detection + overlay (normalized 0..1)
    private var lastRoiN: AppPreferences.RoiN = AppPreferences.RoiN(0.15f, 0.15f, 0.85f, 0.85f)

    // Target lock to reduce "jumping" between objects.
    private var lockedTrackId: Long = -1L
    private var lockedMetric: Float = 0f
    private var lockedLastSeenMs: Long = 0L



    private var switchCandidateId: Long? = null
    private var switchCandidateCount: Int = 0
    // Distance/time history (for signed relative speed)
    private var lastDistanceM: Float = Float.NaN
    private var lastDistanceTimestampMs: Long = -1L

    // EMA smoothing to reduce jitter (distance estimate is noisy)
    private var relSpeedEma: Float = 0f
    private var relSpeedEmaValid: Boolean = false
    private var distEma: Float = Float.NaN
    private var distEmaValid: Boolean = false

    private var lastAlertLevel = 0

    private var tts: TextToSpeech? = null

    private fun flog(msg: String) {
        if (!AppPreferences.debugOverlay) return
        PublicLogWriter.appendLogLine(ctx, analyzerLogFileName, msg)
    }

    init {
        if (AppPreferences.voice) {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            // Ensure prefs are available before reading ROI / debug flags.
            AppPreferences.ensureInit(ctx)
            val tsMs = System.currentTimeMillis()

            val rawBitmap = ImageUtils.imageProxyToBitmap(image, ctx) ?: run {
                sendOverlayClear()
                sendMetricsClear()
                return
            }

            val rotation = image.imageInfo.rotationDegrees

            // Detekuj i kresli ve stejné orientaci (otočený bitmap)
            val bitmap = ImageUtils.rotateBitmap(rawBitmap, rotation)

            val frameW = bitmap.width.toFloat()
            val frameH = bitmap.height.toFloat()

            flog(
                "frame proxy=${image.width}x${image.height} rot=$rotation " +
                    "bmpRaw=${rawBitmap.width}x${rawBitmap.height} bmpRot=${bitmap.width}x${bitmap.height} " +
                    "model=${AppPreferences.selectedModel}"
            )

            if (AppPreferences.debugOverlay) {
                Log.d(
                    "DetectionAnalyzer",
                    "frame imageProxy=${image.width}x${image.height} rot=$rotation bmpRot=${bitmap.width}x${bitmap.height}"
                )
            }

            val roiRectPx = currentRoiRectPx(bitmap)

            val riderSpeedMps = speedProvider.getCurrent().speedMps

            val rawDetections = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(bitmap, roiRectPx).orEmpty()
                1 -> det?.detect(bitmap, roiRectPx).orEmpty()
                else -> emptyList()
            }

            // Postprocess ve stejném frame (otočený bitmap)
            val post = postProcessor.process(rawDetections, frameW, frameH)
            flog("counts raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} accepted=${post.counts.filters}")


val tracked = tracker.update(post.accepted)

// Kandidáti pro výstrahy: musí projít gate (stabilita) a mít rozumné score.
val candidates = tracked
    .asSequence()
    .filter { it.alertGatePassed }
    .filter { it.detection.score >= 0.30f }
    .toList()

if (candidates.isEmpty()) {
    lockedTrackId = -1L
    lockedMetric = 0f
    lockedLastSeenMs = 0L
    switchCandidateId = null
    switchCandidateCount = 0
    resetMotionHistory()
    stopActiveAlerts()
    sendOverlayClear()
    sendMetricsClear()
    return
}

fun priorityMetric(td: TemporalTracker.TrackedDetection): Float {
    val d = td.detection
    val area = d.box.area
    val cxN = ((d.box.x1 + d.box.x2) * 0.5f) / frameW.toFloat()
    val centerWeightX = (1f - (kotlin.math.abs(cxN - 0.5f) * 2f)).coerceIn(0f, 1f)
    // Blízkost (area) * jistota (score) * prefer centrum (aby to drželo objekt před námi)
    return area * d.score * (0.6f + 0.4f * centerWeightX)
}

val best = candidates.maxByOrNull { priorityMetric(it) }!!
val locked = if (lockedTrackId > 0) candidates.firstOrNull { it.id == lockedTrackId } else null

val prevLockedId = lockedTrackId
val selected: TemporalTracker.TrackedDetection =
    if (locked != null) {
        val lockedM = priorityMetric(locked)
        val bestM = priorityMetric(best)

        lockedMetric = lockedM
        lockedLastSeenMs = tsMs

        if (best.id == locked.id) {
            switchCandidateId = null
            switchCandidateCount = 0
            locked
        } else {
            val switchRatio = 1.35f
            val qualifies = bestM > lockedM * switchRatio && best.consecutiveDetections >= locked.consecutiveDetections
            if (qualifies) {
                if (switchCandidateId == best.id) {
                    switchCandidateCount += 1
                } else {
                    switchCandidateId = best.id
                    switchCandidateCount = 1
                }

                val confirmFrames = 3
                if (switchCandidateCount >= confirmFrames) {
                    switchCandidateId = null
                    switchCandidateCount = 0
                    best
                } else {
                    locked
                }
            } else {
                switchCandidateId = null
                switchCandidateCount = 0
                locked
            }
        }
    } else {
        switchCandidateId = null
        switchCandidateCount = 0
        best
    }

lockedTrackId = selected.id

// Pokud jsme přepnuli cíl, musíme resetnout historii pro relSpeed/TTC, jinak budou skoky.
if (prevLockedId > 0 && selected.id != prevLockedId) {
    resetMotionHistory()
}

            // Clamp + canonical label
            val best0 = selected.detection
            val bestBox = clampBox(best0.box, frameW, frameH)
            val label = DetectionLabelMapper.toCanonical(best0.label) ?: (best0.label ?: "unknown")

            val distanceRaw = DetectionPhysics.estimateDistanceMeters(
                bbox = bestBox,
                frameHeightPx = bitmap.height,
                focalPx = estimateFocalLengthPx(bitmap.height),
                realHeightM = if (label == "motorcycle" || label == "bicycle") 1.3f else 1.5f
            )

            val distanceM = smoothDistance(distanceRaw ?: Float.NaN)
            val relSpeedSigned = computeRelativeSpeedSigned(distanceM, tsMs)

            // closing speed for TTC/alerts (only approaching counts)
            val approachSpeedMps = relSpeedSigned.coerceAtLeast(0f)

            val ttc = if (distanceM.isFinite() && approachSpeedMps > 0.30f) {
                (distanceM / approachSpeedMps).coerceAtMost(120f)
            } else {
                Float.POSITIVE_INFINITY
            }

            // Object speed estimate (m/s). If relSpeed is "rider - object", then object = rider - rel.
            val objectSpeedMps =
                if (riderSpeedMps.isFinite()) riderSpeedMps - relSpeedSigned else Float.POSITIVE_INFINITY

            val thresholds = thresholdsForMode(AppPreferences.detectionMode)
            val level =
                if (riderSpeedMps <= 0.01f) 0 else alertLevel(distanceM, approachSpeedMps, ttc, thresholds)

            if (riderSpeedMps <= 0.01f) {
                stopActiveAlerts()
            } else {
                handleAlerts(level)
            }

            sendOverlayUpdate(bestBox, frameW, frameH, distanceM, relSpeedSigned, objectSpeedMps, ttc, label)
            flog(
                "best label=$label score=${best0.score} box=${bestBox.x1},${bestBox.y1},${bestBox.x2},${bestBox.y2} " +
                    "dist=$distanceM rel=$relSpeedSigned approach=$approachSpeedMps rider=$riderSpeedMps obj=$objectSpeedMps ttc=$ttc"
            )

            sendMetricsUpdate(distanceM, relSpeedSigned, objectSpeedMps, ttc, level, label)

            if (AppPreferences.debugOverlay) {
                Log.d(
                    "DetectionAnalyzer",
                    "pipeline raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} filters=${post.counts.filters} tracks=${tracked.size} gate=${tracked.count { it.alertGatePassed }}"
                )
                post.rejected.take(5).forEach {
                    Log.d(
                        "DetectionAnalyzer",
                        "rejected reason=${it.reason} label=${it.detection.label} score=${it.detection.score}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DetectionAnalyzer", "Detection failed", e)
            sendOverlayClear()
            sendMetricsClear()
        } finally {
            image.close()
        }
    }

    private fun smoothDistance(distanceM: Float): Float {
        if (!distanceM.isFinite()) {
            distEmaValid = false
            return Float.POSITIVE_INFINITY
        }
        val alpha = 0.25f
        distEma =
            if (!distEmaValid || !distEma.isFinite()) distanceM else (distEma + alpha * (distanceM - distEma))
        distEmaValid = true
        return distEma
    }

    private fun clampBox(b: Box, w: Float, h: Float): Box {
        val x1 = b.x1.coerceIn(0f, w)
        val y1 = b.y1.coerceIn(0f, h)
        val x2 = b.x2.coerceIn(0f, w)
        val y2 = b.y2.coerceIn(0f, h)
        val left = minOf(x1, x2)
        val top = minOf(y1, y2)
        val right = maxOf(x1, x2)
        val bottom = maxOf(y1, y2)
        return Box(left, top, right, bottom)
    }

    private fun stopActiveAlerts() {
        lastAlertLevel = 0
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.abandonAudioFocus(null)
    }

    private fun handleAlerts(level: Int) {
        if (level <= 0 || level == lastAlertLevel) return
        lastAlertLevel = level
        if (level >= 2) {
            if (AppPreferences.sound) MediaPlayer.create(ctx, R.raw.alert_beep)?.start()
            if (AppPreferences.vibration) {
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (vib.hasVibrator()) vib.vibrate(VibrationEffect.createOneShot(200, 150))
            }
            if (AppPreferences.voice) {
                tts?.speak("Pozor, objekt v pruhu", TextToSpeech.QUEUE_FLUSH, null, "tts_warn")
            }
        }
    }

    private fun sendOverlayUpdate(
        box: Box,
        frameW: Float,
        frameH: Float,
        dist: Float,
        relSpeedSigned: Float,
        objectSpeed: Float,
        ttc: Float,
        label: String
    ) {
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", false)
        // Always include ROI so OverlayView can draw it even when detections are stable.
        i.putExtra("roi_left_n", lastRoiN.left)
        i.putExtra("roi_top_n", lastRoiN.top)
        i.putExtra("roi_right_n", lastRoiN.right)
        i.putExtra("roi_bottom_n", lastRoiN.bottom)
        i.putExtra("frame_w", frameW)
        i.putExtra("frame_h", frameH)
        i.putExtra("left", box.x1)
        i.putExtra("top", box.y1)
        i.putExtra("right", box.x2)
        i.putExtra("bottom", box.y2)
        i.putExtra("dist", dist)
        i.putExtra("speed", relSpeedSigned)
        i.putExtra("object_speed", objectSpeed)
        i.putExtra("ttc", ttc)
        i.putExtra("label", label)
        ctx.sendBroadcast(i)
    }

    private fun sendOverlayClear() {
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", true)
        // Keep ROI visible even when clearing detections.
        i.putExtra("roi_left_n", lastRoiN.left)
        i.putExtra("roi_top_n", lastRoiN.top)
        i.putExtra("roi_right_n", lastRoiN.right)
        i.putExtra("roi_bottom_n", lastRoiN.bottom)
        ctx.sendBroadcast(i)
    }

    /**
     * Current ROI in pixel coords for the given (already rotated) frame.
     * Returns null when ROI is not usable (should be rare due to prefs sanitation).
     */
    private fun currentRoiRectPx(bitmap: Bitmap): Rect? {
        lastRoiN = AppPreferences.getRoiNormalized()

        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return null

        val l = (lastRoiN.left * w).toInt().coerceIn(0, w - 1)
        val t = (lastRoiN.top * h).toInt().coerceIn(0, h - 1)
        val r = (lastRoiN.right * w).toInt().coerceIn(l + 1, w)
        val b = (lastRoiN.bottom * h).toInt().coerceIn(t + 1, h)

        if (r - l < 2 || b - t < 2) return null
        return Rect(l, t, r, b)
    }

    private fun sendMetricsUpdate(
        dist: Float,
        relSpeedSigned: Float,
        objectSpeed: Float,
        ttc: Float,
        level: Int,
        label: String
    ) {
        val i = Intent(ACTION_METRICS_UPDATE).setPackage(ctx.packageName)
        i.putExtra(EXTRA_DISTANCE, dist)
        i.putExtra(EXTRA_SPEED, relSpeedSigned)
        i.putExtra(EXTRA_OBJECT_SPEED, objectSpeed)
        i.putExtra(EXTRA_TTC, ttc)
        i.putExtra(EXTRA_LEVEL, level)
        i.putExtra(EXTRA_LABEL, label)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsClear() {
        sendMetricsUpdate(
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            0,
            ""
        )
    }

    private fun thresholdsForMode(mode: Int): AlertThresholds {
        return when (mode) {
            1 -> AlertThresholds(4.0f, 1.5f, 30f, 12f, 5f, 9f)
            2 -> AlertThresholds(
                AppPreferences.userTtcOrange,
                AppPreferences.userTtcRed,
                AppPreferences.userDistOrange,
                AppPreferences.userDistRed,
                AppPreferences.userSpeedOrange,
                AppPreferences.userSpeedRed
            )

            else -> AlertThresholds(3.0f, 1.2f, 15f, 6f, 3f, 5f)
        }
    }

    private fun alertLevel(distance: Float, approachSpeedMps: Float, ttc: Float, t: AlertThresholds): Int {
        val red =
            (ttc.isFinite() && ttc <= t.ttcRed) ||
                (distance.isFinite() && distance <= t.distRed) ||
                (approachSpeedMps.isFinite() && approachSpeedMps >= t.speedRed)
        if (red) return 2

        val orange =
            (ttc.isFinite() && ttc <= t.ttcOrange) ||
                (distance.isFinite() && distance <= t.distOrange) ||
                (approachSpeedMps.isFinite() && approachSpeedMps >= t.speedOrange)

        return if (orange) 1 else 0
    }

    /**
     * Signed relative speed from distance derivative.
     * + = approaching (distance decreasing)
     * - = receding (distance increasing)
     *
     * Uses jitter guards + EMA to reduce noise from monocular distance estimate.
     */
    private fun computeRelativeSpeedSigned(currentDistanceM: Float, tsMs: Long): Float {
        if (!currentDistanceM.isFinite()) {
            lastDistanceM = Float.NaN
            lastDistanceTimestampMs = tsMs
            relSpeedEmaValid = false
            relSpeedEma = 0f
            return 0f
        }

        if (lastDistanceTimestampMs <= 0L || !lastDistanceM.isFinite()) {
            lastDistanceM = currentDistanceM
            lastDistanceTimestampMs = tsMs
            relSpeedEmaValid = false
            relSpeedEma = 0f
            return 0f
        }

        val dtSec = ((tsMs - lastDistanceTimestampMs).coerceAtLeast(1L)).toFloat() / 1000f
        // Guard: if time step is too large, treat as re-sync (camera stalled / app backgrounded)
        if (dtSec > 1.0f) {
            lastDistanceM = currentDistanceM
            lastDistanceTimestampMs = tsMs
            relSpeedEmaValid = false
            relSpeedEma = 0f
            return 0f
        }

        val dd = lastDistanceM - currentDistanceM // + when approaching
        // Guard: ignore tiny delta that is likely just bbox jitter (distance estimate jitter)
        val minDeltaM = 0.25f
        val raw = if (abs(dd) < minDeltaM) 0f else dd / dtSec

        // Clamp to plausible range (monocular estimate can spike)
        val clamped = raw.coerceIn(-60f, 60f)

        val alpha = 0.30f
        relSpeedEma =
            if (!relSpeedEmaValid) clamped else (relSpeedEma + alpha * (clamped - relSpeedEma))
        relSpeedEmaValid = true

        lastDistanceM = currentDistanceM
        lastDistanceTimestampMs = tsMs

        // Deadband: po EMA odfiltruj kmitání kolem nuly (jinak TTC skáče mezi finite/Infinity)
        return if (kotlin.math.abs(relSpeedEma) < 0.15f) 0f else relSpeedEma
    }


private fun resetMotionHistory() {
    lastDistanceM = Float.NaN
    lastDistanceTimestampMs = -1L
    relSpeedEma = 0f
    relSpeedEmaValid = false
    distEma = Float.NaN
    distEmaValid = false
}

    private fun estimateFocalLengthPx(frameHeightPx: Int): Float {
        val focalMm = AppPreferences.cameraFocalLengthMm
        val sensorHeightMm = AppPreferences.cameraSensorHeightMm
        if (focalMm.isFinite() && sensorHeightMm.isFinite() && sensorHeightMm > 0f) {
            return (focalMm / sensorHeightMm) * frameHeightPx
        }
        return 1000f
    }

    private data class AlertThresholds(
        val ttcOrange: Float,
        val ttcRed: Float,
        val distOrange: Float,
        val distRed: Float,
        val speedOrange: Float,
        val speedRed: Float
    )
}
