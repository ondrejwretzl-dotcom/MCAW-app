package com.mcaw.ai

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.model.Box
import com.mcaw.model.Detection
import java.util.Locale
import kotlin.math.max

class DetectionAnalyzer(
    private val ctx: Context,
    private val yolo: YoloOnnxDetector?,
    private val det: EfficientDetTFLiteDetector?
) : ImageAnalysis.Analyzer {

    companion object {
        const val ACTION_METRICS_UPDATE = "MCAW_METRICS_UPDATE"
        const val EXTRA_TTC = "extra_ttc"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_OBJECT_SPEED = "extra_object_speed"
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_LABEL = "extra_label"
    }

    // U TTC pracujeme s výškou boxu › držíme si poslední hodnotu
    private var lastBoxHeight = -1f
    private var lastTimestamp = -1L
    private var lastDistance = Float.POSITIVE_INFINITY
    private var lastDistanceTimestamp = -1L
    private var lastRelativeSpeed = 0f
    private var lastAlertLevel = 0
    private var lastAlertTimestamp = 0L
    private val lastLogByEvent = mutableMapOf<String, Long>()
    private val sessionLogFileName = "mcaw_detection_${sessionStamp()}.txt"
    private var frameInfoLogged = false

    private var tts: TextToSpeech? = null

    init {
        logSessionStart()
        if (AppPreferences.voice) {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                }
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val ts = System.currentTimeMillis()
            val rawBitmap = ImageUtils.imageProxyToBitmap(image)
            if (rawBitmap == null) {
                sendOverlayClear()
                return
            }
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = ImageUtils.rotateBitmap(rawBitmap, rotation)
            if (!frameInfoLogged) {
                logDetection(
                    ts,
                    "frame_info",
                    null,
                    0f,
                    mapOf(
                        "frame_w" to bitmap.width.toString(),
                        "frame_h" to bitmap.height.toString(),
                        "rotation" to rotation.toString(),
                        "focal_px" to String.format(Locale.US, "%.1f", estimateFocalLengthPx(bitmap.height))
                    )
                )
                frameInfoLogged = true
            }

            // -------------------------
            // DETEKCE
            // -------------------------
            val detList: List<Detection> = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(bitmap) ?: det?.detect(bitmap).orEmpty()
                1 -> det?.detect(bitmap) ?: yolo?.detect(bitmap).orEmpty()
                else -> emptyList()
            }
            logDetection(
                ts,
                "detections_raw",
                null,
                detList.maxOfOrNull { it.score } ?: 0f,
                mapOf("count" to detList.size.toString())
            )

            val vehicleDetections = detList.filter { isVehicleLabel(it.label) }
            val frameW = bitmap.width.toFloat()
            val frameH = bitmap.height.toFloat()
            val filtered = if (AppPreferences.laneFilter) {
                vehicleDetections.filter { isRelevantForLane(it.box, frameW, frameH) }
            } else {
                vehicleDetections
            }
            logDetection(
                ts,
                "detections_filtered",
                null,
                filtered.maxOfOrNull { it.score } ?: 0f,
                mapOf(
                    "raw_count" to detList.size.toString(),
                    "vehicle_count" to vehicleDetections.size.toString(),
                    "filtered_count" to filtered.size.toString()
                )
            )

            if (filtered.isEmpty()) {
                logDetection(
                    ts,
                    "no_vehicle_detected",
                    null,
                    detList.maxOfOrNull { it.score } ?: 0f,
                    mapOf(
                        "raw_count" to detList.size.toString(),
                        "vehicle_count" to vehicleDetections.size.toString()
                    )
                )
                sendOverlayClear()
                sendMetricsClear()
                return
            }

            // Nejlepší (největší score)
            val best: Detection? = filtered.maxByOrNull { it.score }
            if (best == null) {
                logDetection(
                    ts,
                    "no_best_detection",
                    null,
                    0f,
                    mapOf("filtered_count" to filtered.size.toString())
                )
                sendOverlayClear()
                sendMetricsClear()
                return
            }

            // -------------------------
            // FYZIKA: vzdálenost + TTC
            // -------------------------
            val focalPxEstimate = estimateFocalLengthPx(bitmap.height)
            val distance: Float = DetectionPhysics.estimateDistanceMeters(
                bbox = best.box,
                frameHeightPx = bitmap.height,
                focalPx = focalPxEstimate,
                realHeightM = if (best.label == "motorcycle") 1.3f else 1.5f
            ) ?: Float.POSITIVE_INFINITY

            // TTC z růstu výšky boxu (logaritmická derivace)
            val currH = best.box.h
            var ttc = Float.POSITIVE_INFINITY
            if (lastBoxHeight > 0f && lastTimestamp > 0L) {
                val dtSec = ((ts - lastTimestamp).coerceAtLeast(1L)).toFloat() / 1000f
                DetectionPhysics.computeTtcFromHeights(lastBoxHeight, currH, dtSec)?.let { ttc = it }
            }

            // Rychlost jezdce z GPS (m/s)
            val riderSpeed = AppPreferences.lastSpeedMps
            val relSpeed = computeRelativeSpeed(distance, ts)
            val objectSpeed = (riderSpeed - relSpeed).takeIf { it.isFinite() } ?: 0f
            val thresholds = thresholdsForMode(AppPreferences.detectionMode)
            val level = alertLevel(distance, relSpeed, ttc, thresholds)

            // Uložit pro další snímek
            lastBoxHeight = currH
            lastTimestamp = ts

            // -------------------------
            // ALERTY
            // -------------------------
            handleAlerts(level, distance, ttc, relSpeed, objectSpeed)

            // -------------------------
            // DEBUG OVERLAY
            // -------------------------
            val label = best.label ?: "unknown"
            sendOverlayUpdate(best.box, frameW, frameH, distance, relSpeed, objectSpeed, ttc, label)

            sendMetricsUpdate(distance, relSpeed, objectSpeed, ttc, level, label)
            logDetection(
                ts,
                "detection",
                label,
                best.score,
                mapOf(
                    "box" to "%.1f,%.1f,%.1f,%.1f".format(
                        best.box.x1,
                        best.box.y1,
                        best.box.x2,
                        best.box.y2
                    ),
                    "distance" to formatMetric(distance),
                    "ttc" to formatMetric(ttc),
                    "rel_speed" to formatMetric(relSpeed),
                    "object_speed" to formatMetric(objectSpeed),
                    "alert_level" to level.toString()
                )
            )
        } catch (e: Exception) {
            Log.e("DetectionAnalyzer", "Detection failed", e)
            logDetection(
                System.currentTimeMillis(),
                "detection_error",
                e.javaClass.simpleName,
                0f,
                mapOf("message" to (e.message ?: ""))
            )
            sendOverlayClear()
            sendMetricsClear()
        } finally {
            image.close()
        }
    }

    // -------------------------------------------------------------------
    //   ALERT LOGIKA (sjednocená s OverlayView)
    // -------------------------------------------------------------------
    private fun handleAlerts(
        level: Int,
        distance: Float,
        ttc: Float,
        relSpeed: Float,
        objectSpeed: Float
    ) {
        if (level >= 1) {
            notifyAlert(level, distance, ttc, relSpeed, objectSpeed)
        }
        if (level >= 2) {
            if (AppPreferences.sound) playBeep()
            if (AppPreferences.vibration) vibrate()
            if (AppPreferences.voice) speak("Pozor! Auto se přibližuje!")
        }
    }

    // -------------------------------------------------------------------
    //   DEBUG OVERLAY BROADCAST
    // -------------------------------------------------------------------
    private fun sendOverlayUpdate(
        box: Box,
        frameW: Float,
        frameH: Float,
        dist: Float,
        relSpeed: Float,
        objectSpeed: Float,
        ttc: Float,
        label: String
    ) {
        val i = Intent("MCAW_DEBUG_UPDATE")
        i.putExtra("clear", false)
        i.putExtra("frame_w", frameW)
        i.putExtra("frame_h", frameH)
        i.putExtra("left", box.x1)
        i.putExtra("top", box.y1)
        i.putExtra("right", box.x2)
        i.putExtra("bottom", box.y2)
        i.putExtra("dist", dist)
        i.putExtra("speed", relSpeed)
        i.putExtra("object_speed", objectSpeed)
        i.putExtra("ttc", ttc)
        i.putExtra("label", label)
        ctx.sendBroadcast(i)
    }

    private fun sendOverlayClear() {
        val i = Intent("MCAW_DEBUG_UPDATE")
        i.putExtra("clear", true)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsUpdate(
        dist: Float,
        relSpeed: Float,
        objectSpeed: Float,
        ttc: Float,
        level: Int,
        label: String
    ) {
        val i = Intent(ACTION_METRICS_UPDATE)
        i.putExtra(EXTRA_DISTANCE, dist)
        i.putExtra(EXTRA_SPEED, relSpeed)
        i.putExtra(EXTRA_OBJECT_SPEED, objectSpeed)
        i.putExtra(EXTRA_TTC, ttc)
        i.putExtra(EXTRA_LEVEL, level)
        i.putExtra(EXTRA_LABEL, label)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsClear() {
        val i = Intent(ACTION_METRICS_UPDATE)
        i.putExtra(EXTRA_DISTANCE, Float.POSITIVE_INFINITY)
        i.putExtra(EXTRA_SPEED, Float.POSITIVE_INFINITY)
        i.putExtra(EXTRA_OBJECT_SPEED, Float.POSITIVE_INFINITY)
        i.putExtra(EXTRA_TTC, Float.POSITIVE_INFINITY)
        i.putExtra(EXTRA_LEVEL, 0)
        i.putExtra(EXTRA_LABEL, "")
        ctx.sendBroadcast(i)
    }

    // -------------------------------------------------------------------
    //   ALERTS
    // -------------------------------------------------------------------
    private fun playBeep() {
        MediaPlayer.create(ctx, R.raw.alert_beep)?.start()
    }

    private fun vibrate() {
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vib.hasVibrator()) return
        runCatching {
            vib.vibrate(VibrationEffect.createOneShot(200, 150))
        }
    }

    private fun speak(msg: String) {
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "tts_warn")
    }

    private fun thresholdsForMode(mode: Int): AlertThresholds {
        return when (mode) {
            0 -> AlertThresholds(3.0f, 1.2f, 15f, 6f, 3f, 5f)
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

    private fun alertLevel(
        distance: Float,
        relSpeed: Float,
        ttc: Float,
        thresholds: AlertThresholds
    ): Int {
        val red = (ttc.isFinite() && ttc <= thresholds.ttcRed) ||
            (distance.isFinite() && distance <= thresholds.distRed) ||
            (relSpeed.isFinite() && relSpeed >= thresholds.speedRed)
        if (red) return 2

        val orange = (ttc.isFinite() && ttc <= thresholds.ttcOrange) ||
            (distance.isFinite() && distance <= thresholds.distOrange) ||
            (relSpeed.isFinite() && relSpeed >= thresholds.speedOrange)
        return if (orange) 1 else 0
    }

    private fun isRelevantForLane(box: Box, frameW: Float, frameH: Float): Boolean {
        if (frameW <= 0f || frameH <= 0f) return false
        val centerX = (box.x1 + box.x2) / 2f
        val centerXNorm = centerX / frameW
        val width = max(0f, box.x2 - box.x1)
        val height = max(0f, box.y2 - box.y1)
        val area = width * height
        val minArea = frameW * frameH * 0.0035f

        val inLane = centerXNorm in 0.2f..0.8f
        val largeEnough = area >= minArea && height >= frameH * 0.06f
        return inLane && largeEnough
    }

    private fun isVehicleLabel(label: String?): Boolean {
        val normalized = label?.lowercase()?.trim() ?: return false
        return normalized in setOf(
            "bicycle",
            "car",
            "auto",
            "vehicle",
            "truck",
            "lorry",
            "van",
            "motorcycle",
            "motorbike",
            "bike",
            "person",
            "pedestrian",
            "chodec"
        )
    }

    private fun logDetection(
        timestamp: Long,
        event: String,
        label: String?,
        score: Float,
        extras: Map<String, String> = emptyMap()
    ) {
        val interval = when (event) {
            "detections_raw", "detections_filtered" -> 2000L
            "no_vehicle_detected", "no_best_detection", "detection" -> 800L
            "detection_error" -> 0L
            else -> 2000L
        }
        val lastLogged = lastLogByEvent[event] ?: 0L
        if (interval > 0 && timestamp - lastLogged < interval) return
        lastLogByEvent[event] = timestamp
        val content = buildString {
            append("ts=")
            append(timestamp)
            append(" event=")
            append(event)
            if (label != null) {
                append(" label=")
                append(label)
            }
            if (score > 0f) {
                append(" score=")
                append("%.3f".format(Locale.US, score))
            }
            extras.forEach { (key, value) ->
                append(" ")
                append(key)
                append("=")
                append(value)
            }
        }
        com.mcaw.util.PublicLogWriter.appendLogLine(
            ctx,
            sessionLogFileName,
            content
        )
    }

    private fun sessionStamp(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())
    }

    private fun logSessionStart() {
        val thresholds = thresholdsForMode(AppPreferences.detectionMode)
        val modelInfo = when (AppPreferences.selectedModel) {
            0 -> "yolo"
            1 -> "efficientdet"
            else -> "none"
        }
        val timestamp = System.currentTimeMillis()
        val content = buildString {
            append("ts=")
            append(timestamp)
            append(" ")
            append("event=session_start")
            append(" model=")
            append(modelInfo)
            append(" lane_filter=")
            append(AppPreferences.laneFilter)
            append(" mode=")
            append(AppPreferences.detectionMode)
            append(" thresholds=ttc(")
            append(thresholds.ttcOrange)
            append("/")
            append(thresholds.ttcRed)
            append(") dist(")
            append(thresholds.distOrange)
            append("/")
            append(thresholds.distRed)
            append(") speed(")
            append(thresholds.speedOrange)
            append("/")
            append(thresholds.speedRed)
            append(")")
            yolo?.let {
                append(" yolo=input")
                append(it.inputSize)
                append(" score=")
                append(it.scoreThreshold)
                append(" iou=")
                append(it.iouThreshold)
            }
            det?.let {
                append(" eff=input")
                append(it.inputSize)
                append(" score=")
                append(it.scoreThreshold)
                append(" iou=")
                append(it.iouThreshold)
            }
        }
        com.mcaw.util.PublicLogWriter.appendLogLine(ctx, sessionLogFileName, content)
    }

    private fun formatMetric(value: Float): String {
        return if (value.isFinite()) "%.2f".format(Locale.US, value) else "inf"
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

    private fun computeRelativeSpeed(distance: Float, now: Long): Float {
        if (!distance.isFinite()) return 0f
        val previousDistance = lastDistance
        val previousTs = lastDistanceTimestamp
        lastDistance = distance
        lastDistanceTimestamp = now
        if (!previousDistance.isFinite() || previousTs <= 0L) return 0f
        val dtSec = ((now - previousTs).coerceAtLeast(1L)).toFloat() / 1000f
        val raw = (previousDistance - distance) / dtSec
        val smoothed = (0.6f * raw) + (0.4f * lastRelativeSpeed)
        lastRelativeSpeed = smoothed.takeIf { it.isFinite() } ?: 0f
        return lastRelativeSpeed
    }

    private fun notifyAlert(
        level: Int,
        distance: Float,
        ttc: Float,
        relSpeed: Float,
        objectSpeed: Float
    ) {
        val now = System.currentTimeMillis()
        if (level < 1) return
        if (level == lastAlertLevel && now - lastAlertTimestamp < 2000L) return
        lastAlertLevel = level
        lastAlertTimestamp = now

        val distanceText =
            if (distance.isFinite()) "Vzdálenost ${"%.1f".format(distance)} m" else "Vzdálenost --"
        val ttcText = if (ttc.isFinite()) "TTC ${"%.1f".format(ttc)} s" else "TTC --"
        val relText = "Rel ${"%.1f".format(relSpeed)} m/s"
        val objText = "Obj ${"%.1f".format(objectSpeed)} m/s"
        val title = if (level >= 2) "MCAW: Vysoké riziko" else "MCAW: Upozornění"
        val text = "$distanceText · $ttcText · $relText · $objText"

        AlertNotifier.show(ctx, title, text, level)
    }
}
