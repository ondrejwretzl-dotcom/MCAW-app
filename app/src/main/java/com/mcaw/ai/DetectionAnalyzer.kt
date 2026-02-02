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
import java.util.Locale
import kotlin.math.max

class DetectionAnalyzer(
    private val ctx: Context,
    private val yolo: YoloOnnxDetector,
    private val det: EfficientDetTFLiteDetector
) : ImageAnalysis.Analyzer {

    private var lastBoxWidth = -1f
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

        // DETEKCE
        val detList = when (AppPreferences.selectedModel) {
            1 -> yolo.detect(bitmap)
            2 -> det.detect(bitmap)
            else -> emptyList()
        }

        if (detList.isEmpty()) {
            image.close()
            return
        }

        // Nejvìtší objekt = auto
        val best: com.mcaw.model.Detection? = detections.maxByOrNull { it.score }

        // Fyzikální distance (kalibrace FoV)
        val distance = DetectionPhysics.estimateDistance(best.box)

        // Rychlost pøibližování
        val width = best.box.width()
        var speed = 0f
        if (lastBoxWidth > 0) {
            val dt = (ts - lastTimestamp) / 1000f
            speed = (width - lastBoxWidth) / dt
        }

        // TTC (Time-to-collision)
        val ttc = DetectionPhysics.computeTTC(distance, speed)

        // Adaptivní prah TTC podle rychlosti vlastního auta (zatím 0)
        val userSpeed = 0f
        val ttcThreshold = DetectionPhysics.adaptiveTtcThreshold(userSpeed)

        // uložit
        lastBoxWidth = width
        lastTimestamp = ts

        // upozornìní podle fyzikálních hodnot
        handleAlerts(distance, speed, ttc, ttcThreshold)

        // debug overlay
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
        i.putExtra("left", box.left)
        i.putExtra("top", box.top)
        i.putExtra("right", box.right)
        i.putExtra("bottom", box.bottom)
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
