package com.mcaw.ai

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
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
    private val yolo: YoloOnnxDetector,
    private val det: EfficientDetTFLiteDetector
) : ImageAnalysis.Analyzer {

    companion object {
        const val ACTION_METRICS_UPDATE = "MCAW_METRICS_UPDATE"
        const val EXTRA_TTC = "extra_ttc"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_LEVEL = "extra_level"
    }

    // U TTC pracujeme s výškou boxu › držíme si poslední hodnotu
    private var lastBoxHeight = -1f
    private var lastTimestamp = -1L

    private var tts: TextToSpeech? = null

    init {
        if (AppPreferences.voice) {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                }
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        val ts = System.currentTimeMillis()
        val bitmap = ImageUtils.imageProxyToBitmap(image)
        if (bitmap == null) {
            image.close()
            return
        }

        // -------------------------
        // DETEKCE
        // -------------------------
        val detList: List<Detection> = when (AppPreferences.selectedModel) {
            0 -> yolo.detect(bitmap)
            1 -> det.detect(bitmap)
            else -> emptyList()
        }

        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()
        val filtered = detList.filter { isRelevantForLane(it.box, frameW, frameH) }

        if (filtered.isEmpty()) {
            image.close()
            return
        }

        // Nejlepší (největší score)
        val best: Detection? = filtered.maxByOrNull { it.score }
        if (best == null) {
            image.close()
            return
        }

        // -------------------------
        // FYZIKA: vzdálenost + TTC
        // -------------------------
        // Heuristický odhad fokální délky (px) – nahraď později kalibrací/FoV
        val focalPxEstimate = 1000f
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
        val speed = AppPreferences.lastSpeedMps
        val thresholds = thresholdsForMode(AppPreferences.detectionMode)
        val level = alertLevel(distance, speed, ttc, thresholds)

        // Uložit pro další snímek
        lastBoxHeight = currH
        lastTimestamp = ts

        // -------------------------
        // ALERTY
        // -------------------------
        handleAlerts(level)

        // -------------------------
        // DEBUG OVERLAY
        // -------------------------
        if (AppPreferences.debugOverlay) {
            sendOverlayUpdate(best.box, distance, speed, ttc)
        }

        sendMetricsUpdate(distance, speed, ttc, level)

        image.close()
    }

    // -------------------------------------------------------------------
    //   ALERT LOGIKA (sjednocená s OverlayView)
    // -------------------------------------------------------------------
    private fun handleAlerts(level: Int) {
        val shouldAlert = level >= 2

        if (!shouldAlert) return

        if (AppPreferences.sound) playBeep()
        if (AppPreferences.vibration) vibrate()
        if (AppPreferences.voice) speak("Pozor! Auto se přibližuje!")
    }

    // -------------------------------------------------------------------
    //   DEBUG OVERLAY BROADCAST
    // -------------------------------------------------------------------
    private fun sendOverlayUpdate(box: Box, dist: Float, speed: Float, ttc: Float) {
        val i = Intent("MCAW_DEBUG_UPDATE")
        i.putExtra("left", box.x1)
        i.putExtra("top", box.y1)
        i.putExtra("right", box.x2)
        i.putExtra("bottom", box.y2)
        i.putExtra("dist", dist)
        i.putExtra("speed", speed)
        i.putExtra("ttc", ttc)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsUpdate(dist: Float, speed: Float, ttc: Float, level: Int) {
        val i = Intent(ACTION_METRICS_UPDATE)
        i.putExtra(EXTRA_DISTANCE, dist)
        i.putExtra(EXTRA_SPEED, speed)
        i.putExtra(EXTRA_TTC, ttc)
        i.putExtra(EXTRA_LEVEL, level)
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
        vib.vibrate(VibrationEffect.createOneShot(200, 150))
    }

    private fun speak(msg: String) {
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "tts_warn")
    }

    private fun thresholdsForMode(mode: Int): AlertThresholds {
        return when (mode) {
            0 -> AlertThresholds(2.0f, 1.2f, 8f, 4f, 4f, 7f)
            1 -> AlertThresholds(2.6f, 1.6f, 14f, 8f, 7f, 12f)
            2 -> AlertThresholds(
                AppPreferences.userTtcOrange,
                AppPreferences.userTtcRed,
                AppPreferences.userDistOrange,
                AppPreferences.userDistRed,
                AppPreferences.userSpeedOrange,
                AppPreferences.userSpeedRed
            )
            else -> AlertThresholds(2.0f, 1.2f, 8f, 4f, 4f, 7f)
        }
    }

    private fun alertLevel(
        distance: Float,
        speed: Float,
        ttc: Float,
        thresholds: AlertThresholds
    ): Int {
        val red = (ttc.isFinite() && ttc <= thresholds.ttcRed) ||
            (distance.isFinite() && distance <= thresholds.distRed) ||
            (speed.isFinite() && speed >= thresholds.speedRed)
        if (red) return 2

        val orange = (ttc.isFinite() && ttc <= thresholds.ttcOrange) ||
            (distance.isFinite() && distance <= thresholds.distOrange) ||
            (speed.isFinite() && speed >= thresholds.speedOrange)
        return if (orange) 1 else 0
    }

    private fun isRelevantForLane(box: Box, frameW: Float, frameH: Float): Boolean {
        if (frameW <= 0f || frameH <= 0f) return false
        val centerX = (box.x1 + box.x2) / 2f
        val centerXNorm = centerX / frameW
        val width = max(0f, box.x2 - box.x1)
        val height = max(0f, box.y2 - box.y1)
        val area = width * height
        val minArea = frameW * frameH * 0.01f

        val inLane = centerXNorm in 0.3f..0.7f
        val largeEnough = area >= minArea && height >= frameH * 0.12f
        return inLane && largeEnough
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
