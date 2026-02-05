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
import com.mcaw.model.Detection
import java.util.Locale
import com.mcaw.util.PublicLogWriter


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
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_OBJECT_SPEED = "extra_object_speed"
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_LABEL = "extra_label"
    }

    private val postProcessor = DetectionPostProcessor(
        DetectionPostProcessor.Config(debug = AppPreferences.debugOverlay)
    )
    private val tracker = TemporalTracker(minConsecutiveForAlert = 3)

    private var lastDistance = Float.POSITIVE_INFINITY
    private var lastDistanceTimestamp = -1L
    private var lastAlertLevel = 0

    private var tts: TextToSpeech? = null

    init {
        if (AppPreferences.voice) {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val ts = System.currentTimeMillis()
            val rawBitmap = ImageUtils.imageProxyToBitmap(image, ctx) ?: run {
                sendOverlayClear()
                return
            }
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = ImageUtils.rotateBitmap(rawBitmap, rotation)
            if (AppPreferences.debugOverlay) {
                Log.d(
                    "DetectionAnalyzer",
                    "frame imageProxy=${image.width}x${image.height} rot=${image.imageInfo.rotationDegrees}"
                )
            }
            
            if (AppPreferences.debugOverlay) {
                Log.d("DetectionAnalyzer", "bitmap(afterRotate)=${bitmap.width}x${bitmap.height}")
            }
            val speed = speedProvider.getCurrent().speedMps

            val rawDetections = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(bitmap).orEmpty()
                1 -> det?.detect(bitmap).orEmpty()
                else -> emptyList()
            }

            val post = postProcessor.process(rawDetections, bitmap.width.toFloat(), bitmap.height.toFloat())
            val tracked = tracker.update(post.accepted)
            val bestTrack = tracked
                .filter { it.alertGatePassed }
                .maxByOrNull { it.detection.score }

            if (bestTrack == null) {
                stopActiveAlerts()
                sendOverlayClear()
                sendMetricsClear()
                return
            }

            val best = bestTrack.detection
            val distance = DetectionPhysics.estimateDistanceMeters(
                bbox = best.box,
                frameHeightPx = bitmap.height,
                focalPx = estimateFocalLengthPx(bitmap.height),
                realHeightM = if (best.label == "motorcycle" || best.label == "bicycle") 1.3f else 1.5f
            ) ?: Float.POSITIVE_INFINITY

            val relSpeed = computeRelativeSpeed(distance, ts)
            val ttc = if (speed > 0.01f && relSpeed > 0.01f) distance / relSpeed else Float.POSITIVE_INFINITY
            val level = if (speed <= 0.01f) 0 else alertLevel(distance, relSpeed, ttc, thresholdsForMode(AppPreferences.detectionMode))

            if (speed <= 0.01f) {
                stopActiveAlerts()
            } else {
                handleAlerts(level)
            }

            val label = best.label ?: "unknown"
            sendOverlayUpdate(best.box, bitmap.width.toFloat(), bitmap.height.toFloat(), distance, relSpeed, speed, ttc, label)
            sendMetricsUpdate(distance, relSpeed, speed, ttc, level, label)

            if (AppPreferences.debugOverlay) {
                Log.d(
                    "DetectionAnalyzer",
                    "pipeline raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} filters=${post.counts.filters} tracks=${tracked.size} gate=${tracked.count { it.alertGatePassed }}"
                )
                post.rejected.take(5).forEach {
                    Log.d("DetectionAnalyzer", "rejected reason=${it.reason} label=${it.detection.label} score=${it.detection.score}")
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
            if (AppPreferences.voice) tts?.speak("Pozor, objekt v pruhu", TextToSpeech.QUEUE_FLUSH, null, "tts_warn")
        }
    }

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
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
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
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", true)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsUpdate(dist: Float, relSpeed: Float, objectSpeed: Float, ttc: Float, level: Int, label: String) {
        val i = Intent(ACTION_METRICS_UPDATE).setPackage(ctx.packageName)
        i.putExtra(EXTRA_DISTANCE, dist)
        i.putExtra(EXTRA_SPEED, relSpeed)
        i.putExtra(EXTRA_OBJECT_SPEED, objectSpeed)
        i.putExtra(EXTRA_TTC, ttc)
        i.putExtra(EXTRA_LEVEL, level)
        i.putExtra(EXTRA_LABEL, label)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsClear() {
        sendMetricsUpdate(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 0, "")
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

    private fun alertLevel(distance: Float, relSpeed: Float, ttc: Float, t: AlertThresholds): Int {
        val red = (ttc.isFinite() && ttc <= t.ttcRed) || (distance.isFinite() && distance <= t.distRed) || (relSpeed.isFinite() && relSpeed >= t.speedRed)
        if (red) return 2
        val orange = (ttc.isFinite() && ttc <= t.ttcOrange) || (distance.isFinite() && distance <= t.distOrange) || (relSpeed.isFinite() && relSpeed >= t.speedOrange)
        return if (orange) 1 else 0
    }

    private fun computeRelativeSpeed(currentDistanceM: Float, ts: Long): Float {
        if (!currentDistanceM.isFinite()) {
            lastDistance = currentDistanceM
            lastDistanceTimestamp = ts
            return 0f
        }
        if (lastDistanceTimestamp <= 0L || !lastDistance.isFinite()) {
            lastDistance = currentDistanceM
            lastDistanceTimestamp = ts
            return 0f
        }
        val dtSec = ((ts - lastDistanceTimestamp).coerceAtLeast(1L)).toFloat() / 1000f
        val speedToward = ((lastDistance - currentDistanceM) / dtSec).coerceAtLeast(0f)
        lastDistance = currentDistanceM
        lastDistanceTimestamp = ts
        return speedToward
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
