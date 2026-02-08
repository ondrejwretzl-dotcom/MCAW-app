package com.mcaw.ai

import android.content.Context
import android.content.Intent
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

            val roiN = AppPreferences.getRoiNormalized()
            val roiPx = roiToPx(roiN.left, roiN.top, roiN.right, roiN.bottom, bitmap.width, bitmap.height)
            val roiBitmap = if (roiPx.isFullFrame(bitmap.width, bitmap.height)) bitmap else {
                // V1: jednoduchý crop (alokace). Další krok: reuse buffer.
                android.graphics.Bitmap.createBitmap(bitmap, roiPx.x, roiPx.y, roiPx.w, roiPx.h)
            }

            flog(
                "frame proxy=${image.width}x${image.height} rot=$rotation " +
                    "bmpRaw=${rawBitmap.width}x${rawBitmap.height} bmpRot=${bitmap.width}x${bitmap.height} " +
                    "roiN=${roiN.left},${roiN.top},${roiN.right},${roiN.bottom} " +
                    "roiPx=${roiPx.x},${roiPx.y},${roiPx.w}x${roiPx.h} model=${AppPreferences.selectedModel}"
            )

            if (AppPreferences.debugOverlay) {
                Log.d(
                    "DetectionAnalyzer",
                    "frame imageProxy=${image.width}x${image.height} rot=$rotation bmpRot=${bitmap.width}x${bitmap.height} roi=${roiPx.w}x${roiPx.h}"
                )
            }

            val riderSpeedMps = speedProvider.getCurrent().speedMps

            val rawDetectionsRoi = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(roiBitmap).orEmpty()
                1 -> det?.detect(roiBitmap).orEmpty()
                else -> emptyList()
            }

            // Přemapuj ROI boxy do souřadnic celého frame + bezpečně ořízni na hranice ROI
            // (některé modely mohou vrátit koordináty mimo crop).
            val rawDetections = if (roiPx.isFullFrame(bitmap.width, bitmap.height)) {
                rawDetectionsRoi
            } else {
                val roiWf = roiPx.w.toFloat()
                val roiHf = roiPx.h.toFloat()
                rawDetectionsRoi.mapNotNull { d ->
                    // clamp do ROI bitmap prostoru
                    val x1 = d.box.x1.coerceIn(0f, roiWf)
                    val y1 = d.box.y1.coerceIn(0f, roiHf)
                    val x2 = d.box.x2.coerceIn(0f, roiWf)
                    val y2 = d.box.y2.coerceIn(0f, roiHf)
                    if (x2 <= x1 || y2 <= y1) return@mapNotNull null

                    val fx1 = x1 + roiPx.x
                    val fy1 = y1 + roiPx.y
                    val fx2 = x2 + roiPx.x
                    val fy2 = y2 + roiPx.y
                    val cxN = ((fx1 + fx2) * 0.5f) / bitmap.width.toFloat()
                    val cyN = ((fy1 + fy2) * 0.5f) / bitmap.height.toFloat()

                    // tvrdý gate: centrum boxu musí ležet v ROI
                    if (cxN !in roiN.left..roiN.right || cyN !in roiN.top..roiN.bottom) {
                        return@mapNotNull null
                    }

                    d.copy(box = Box(fx1, fy1, fx2, fy2))
                }
            }

            if (!roiPx.isFullFrame(bitmap.width, bitmap.height)) {
                flog("roiGate rawRoi=${rawDetectionsRoi.size} kept=${rawDetections.size}")
            }

            // Postprocess ve stejném frame (otočený bitmap)
            val post = postProcessor.process(rawDetections, frameW, frameH)
            flog("counts raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} accepted=${post.counts.filters}")

            val tracked = tracker.update(post.accepted)
            val bestTrack = tracked
                .filter { it.alertGatePassed }
                .maxByOrNull { it.detection.score }

            if (bestTrack == null) {
                stopActiveAlerts()
                sendOverlayClear(roiN)
                sendMetricsClear()
                return
            }

            val best0 = bestTrack.detection
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

            val approachSpeedMps = relSpeedSigned.coerceAtLeast(0f)

            val ttc = if (distanceM.isFinite() && approachSpeedMps > 0.05f) {
                (distanceM / approachSpeedMps).coerceAtMost(120f)
            } else {
                Float.POSITIVE_INFINITY
            }

            // If relSpeed = rider - object, then object = rider - rel
            val objectSpeedMps =
                if (riderSpeedMps.isFinite()) riderSpeedMps - relSpeedSigned else Float.POSITIVE_INFINITY

            val thresholds = thresholdsForMode(AppPreferences.detectionMode)
            val level =
                if (riderSpeedMps <= 0.01f) 0 else alertLevel(distanceM, approachSpeedMps, ttc, thresholds)

            if (riderSpeedMps <= 0.01f) stopActiveAlerts() else handleAlerts(level)

            sendOverlayUpdate(bestBox, frameW, frameH, distanceM, relSpeedSigned, objectSpeedMps, ttc, label, roiN)
            sendMetricsUpdate(distanceM, relSpeedSigned, objectSpeedMps, ttc, level, label)

        } catch (e: Exception) {
            Log.e("DetectionAnalyzer", "Detection failed", e)
            sendOverlayClear(AppPreferences.getRoiNormalized())
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
        distEma = if (!distEmaValid || !distEma.isFinite()) distanceM else (distEma + alpha * (distanceM - distEma))
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
        label: String,
        roiN: AppPreferences.RoiN
    ) {
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", false)
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

        i.putExtra("roi_left_n", roiN.left)
        i.putExtra("roi_top_n", roiN.top)
        i.putExtra("roi_right_n", roiN.right)
        i.putExtra("roi_bottom_n", roiN.bottom)

        ctx.sendBroadcast(i)
    }

    private fun sendOverlayClear(roiN: AppPreferences.RoiN) {
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", true)
        i.putExtra("roi_left_n", roiN.left)
        i.putExtra("roi_top_n", roiN.top)
        i.putExtra("roi_right_n", roiN.right)
        i.putExtra("roi_bottom_n", roiN.bottom)
        ctx.sendBroadcast(i)
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
        if (dtSec > 1.0f) {
            lastDistanceM = currentDistanceM
            lastDistanceTimestampMs = tsMs
            relSpeedEmaValid = false
            relSpeedEma = 0f
            return 0f
        }

        val dd = lastDistanceM - currentDistanceM // + when approaching
        val minDeltaM = 0.25f
        val raw = if (abs(dd) < minDeltaM) 0f else dd / dtSec
        val clamped = raw.coerceIn(-60f, 60f)

        val alpha = 0.30f
        relSpeedEma = if (!relSpeedEmaValid) clamped else (relSpeedEma + alpha * (clamped - relSpeedEma))
        relSpeedEmaValid = true

        lastDistanceM = currentDistanceM
        lastDistanceTimestampMs = tsMs

        return relSpeedEma
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

    private data class RoiPx(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun isFullFrame(frameW: Int, frameH: Int): Boolean =
            x == 0 && y == 0 && w == frameW && h == frameH
    }

    private fun roiToPx(l: Float, t: Float, r: Float, b: Float, frameW: Int, frameH: Int): RoiPx {
        val left = (l * frameW).toInt().coerceIn(0, frameW - 1)
        val top = (t * frameH).toInt().coerceIn(0, frameH - 1)
        val right = (r * frameW).toInt().coerceIn(left + 1, frameW)
        val bottom = (b * frameH).toInt().coerceIn(top + 1, frameH)
        return RoiPx(left, top, right - left, bottom - top)
    }
}
