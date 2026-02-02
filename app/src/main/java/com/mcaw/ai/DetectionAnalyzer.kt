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

class DetectionAnalyzer(
    private val ctx: Context,
    private val yolo: YoloOnnxDetector,
    private val det: EfficientDetTFLiteDetector
) : ImageAnalysis.Analyzer {

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
            1 -> yolo.detect(bitmap)
            2 -> det.detect(bitmap)
            else -> emptyList()
        }

        if (detList.isEmpty()) {
            image.close()
            return
        }

        // Nejlepší (nejvìtší score)
        val best: Detection? = detList.maxByOrNull { it.score }
        if (best == null) {
            image.close()
            return
        }

        // -------------------------
        // FYZIKA: vzdálenost + TTC
        // -------------------------
        // Heuristický odhad fokální délky (px) – nahraï pozdìji kalibrací/FoV
        val focalPxEstimate = 1000f
        val frameH = bitmap.height

        val distance: Float = DetectionPhysics.estimateDistanceMeters(
            bbox = best.box,
            frameHeightPx = frameH,
            focalPx = focalPxEstimate,
            realHeightM = if (best.label == "motorcycle") 1.3f else 1.5f
        ) ?: Float.POSITIVE_INFINITY

        // TTC z rùstu výšky boxu (logaritmická derivace)
        val currH = best.box.h
        var ttc = Float.POSITIVE_INFINITY
        if (lastBoxHeight > 0f && lastTimestamp > 0L) {
            val dtSec = ((ts - lastTimestamp).coerceAtLeast(1L)).toFloat() / 1000f
            DetectionPhysics.computeTtcFromHeights(lastBoxHeight, currH, dtSec)?.let { ttc = it }
        }

        // Volitelnì mùžeš dopoèítat i "speed" (tady necháme 0, TTC máme robustní)
        val speed = 0f

        // Adaptivní práh TTC – zatím bez napojení na rychlost vlastního vozidla
        val userSpeed = 0f
        val ttcThreshold: Float = DetectionPhysics.adaptiveTtcThreshold(userSpeed)

        // Uložit pro další snímek
        lastBoxHeight = currH
        lastTimestamp = ts

        // -------------------------
        // ALERTY
        // -------------------------
        handleAlerts(distance, speed, ttc, ttcThreshold)

        // -------------------------
        // DEBUG OVERLAY
        // -------------------------
        if (AppPreferences.debugOverlay) {
            sendOverlayUpdate(best.box, distance, speed, ttc)
        }

        image.close()
    }

    // -------------------------------------------------------------------
    //   ALERT LOGIKA (sjednocená s OverlayView)
    // -------------------------------------------------------------------
    private fun handleAlerts(distance: Float, speed: Float, ttc: Float, ttcTh: Float) {
        val mode = AppPreferences.detectionMode

        val shouldAlert = when (mode) {
            1 -> ttc < ttcTh
            2 -> distance < 1.0f
            3 -> speed > 1.2f
            4 -> (ttc < ttcTh || distance < 1.0f || speed > 1.2f)
            5 -> false
            else -> false
        }

        if (!shouldAlert) return

        if (AppPreferences.sound) playBeep()
        if (AppPreferences.vibration) vibrate()
        if (AppPreferences.voice) speak("Pozor! Auto se pøibližuje!")
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
}
