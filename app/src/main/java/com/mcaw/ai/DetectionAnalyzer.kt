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
import com.mcaw.app.MCAWApp
import com.mcaw.config.AppPreferences
import com.mcaw.location.SpeedProvider
import com.mcaw.model.Box
import com.mcaw.model.Detection
import java.util.Locale
import kotlin.math.abs
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

    
    private var lastFrameW: Float = 0f
    private var lastFrameH: Float = 0f
    private var lastRoiBox: Box? = null
private val analyzerLogFileName: String = "mcaw_analyzer_${System.currentTimeMillis()}.txt"

    private val postProcessor = DetectionPostProcessor(
        DetectionPostProcessor.Config(debug = AppPreferences.debugOverlay)
    )

    private val tracker = TemporalTracker(minConsecutiveForAlert = 3)

    private var lastDistance = Float.POSITIVE_INFINITY
    private var filteredRelSpeed = 0f
    private var lastRelSpeedTs = -1L
    private var lastBoxHeightPx = 0f
    private var lastBoxHeightTs = -1L
    private var filteredTtc = Float.POSITIVE_INFINITY
    private var lastDistanceTimestamp = -1L
    private var lastAlertLevel = 0

    private var tts: TextToSpeech? = null

    private fun flog(msg: String) {
        if (!AppPreferences.debugOverlay) return
        PublicLogWriter.appendLogLine(ctx, analyzerLogFileName, msg)
    }

    init {
        if (AppPreferences.voice) {
            // Reuse app-level TTS if available (avoid double init).
            tts = (ctx.applicationContext as? MCAWApp)?.getTts()
            if (tts == null) {
                runCatching {
                    tts = TextToSpeech(ctx) { status ->
                        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
                    }
                }
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val ts = System.currentTimeMillis()

            val rawBitmap = ImageUtils.imageProxyToBitmap(image) ?: run {
                sendOverlayClear()
                return
            }

            val rotation = image.imageInfo.rotationDegrees

            // ? Detekuj i kresli ve stejné orientaci (otočený bitmap)
            val bitmap = ImageUtils.rotateBitmap(rawBitmap, rotation)

            val frameW = bitmap.width.toFloat()
            val frameH = bitmap.height.toFloat()


            // ROI (zúžené zorné pole) pro rychlost a stabilitu: typicky střed + spodní část obrazu
            val roiEnabled = AppPreferences.roiEnabled
            val roiLeftPx = (frameW * AppPreferences.roiLeftNorm).coerceIn(0f, frameW - 2f)
            val roiTopPx = (frameH * AppPreferences.roiTopNorm).coerceIn(0f, frameH - 2f)
            val roiRightPx = (frameW * AppPreferences.roiRightNorm).coerceIn(roiLeftPx + 2f, frameW)
            val roiBottomPx = (frameH * AppPreferences.roiBottomNorm).coerceIn(roiTopPx + 2f, frameH)

            val roiBoxPx = Box(roiLeftPx, roiTopPx, roiRightPx, roiBottomPx)
            lastFrameW = frameW
            lastFrameH = frameH
            lastRoiBox = roiBoxPx

            val detectBitmap = bitmap
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

            val speed = speedProvider.getCurrent().speedMps

            val rawDetections = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(detectBitmap).orEmpty()
                1 -> det?.detect(detectBitmap).orEmpty()
                else -> emptyList()
            }

            
            val mappedDetections = rawDetections
// ? Postprocess ve stejném frame (otočený bitmap)
            val post = postProcessor.process(
                mappedDetections,
                frameW,
                frameH,
                roiNorm = if (roiEnabled) DetectionPostProcessor.RectNorm(
                    AppPreferences.roiLeftNorm,
                    AppPreferences.roiTopNorm,
                    AppPreferences.roiRightNorm,
                    AppPreferences.roiBottomNorm
                ) else null
            )
            flog("counts raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} accepted=${post.counts.filters}")

            val tracked = tracker.update(post.accepted)
            val bestTrack = tracked
                .filter { it.alertGatePassed }
                .maxByOrNull { priorityScore(it.detection, frameW, frameH) }

            if (bestTrack == null) {
                stopActiveAlerts()
                sendOverlayClear()
                sendMetricsClear()
                return
            }

            // ? Clamp + canonical label
            val best0 = bestTrack.detection
            val bestBox = clampBox(best0.box, frameW, frameH)
            val label = DetectionLabelMapper.toCanonical(best0.label) ?: (best0.label ?: "unknown")

            
val distance = DetectionPhysics.estimateDistanceMeters(
    bbox = bestBox,
    frameHeightPx = bitmap.height,
    focalPx = estimateFocalLengthPx(bitmap.height),
    realHeightM = if (label == "motorcycle" || label == "bicycle") 1.3f else 1.5f
) ?: Float.POSITIVE_INFINITY

val relSpeed = computeRelativeSpeed(distance, ts)

// TTC stabilization:
// Prefer TTC from bbox height growth (stable); fallback to distance/relSpeed only when clearly approaching.
val ttcFromHeights = computeTtcFromBoxHeights(bestBox, ts)
val ttcRaw = ttcFromHeights
    ?: DetectionPhysics.computeTtcFromDistanceMeters(distance, relSpeed)
    ?: Float.POSITIVE_INFINITY

filteredTtc = if (!ttcRaw.isFinite()) {
    Float.POSITIVE_INFINITY
} else {
    val alphaTtc = 0.18f
    if (!filteredTtc.isFinite() || filteredTtc == Float.POSITIVE_INFINITY) ttcRaw
    else alphaTtc * ttcRaw + (1f - alphaTtc) * filteredTtc
}

val ttc = filteredTtc
            val level = if (speed <= 0.01f) 0 else alertLevel(distance, relSpeed, ttc, thresholdsForMode(AppPreferences.detectionMode))

            if (speed <= 0.01f) {
                stopActiveAlerts()
            } else {
                handleAlerts(level)
            }

            sendOverlayUpdate(bestBox, frameW, frameH, distance, relSpeed, speed, ttc, label)
            flog("best label=$label score=${best0.score} box=${bestBox.x1},${bestBox.y1},${bestBox.x2},${bestBox.y2} dist=$distance rel=$relSpeed rider=$speed ttc=$ttc")

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


    /**
     * Prioritizace cílového objektu (středový pruh + spodní část obrazu).
     * Cíl: méně přeskakování mezi objekty => stabilnější TTC a včasnější alerty.
     */
    private fun priorityScore(d: Detection, frameW: Float, frameH: Float): Float {
        val b = d.box
        val w = frameW.coerceAtLeast(1f)
        val h = frameH.coerceAtLeast(1f)
        // 0..1: 1 = přesně uprostřed
        val centerDistNorm = (abs(b.cx - w / 2f) / (w / 2f)).coerceIn(0f, 1f)
        val centerBias = 1f - centerDistNorm

        // 0..1: 1 = dole (blíž), 0 = nahoře (dál / méně relevantní)
        val bottomBias = (b.y2 / h).coerceIn(0f, 1f)

        // 0..1: velikost boxu (větší = pravděpodobně blíž / relevantnější)
        val areaNorm = (b.area / (w * h)).coerceIn(0f, 1f)

        // kombinace: conf je stále primární, ale preferujeme střed + dole
        return (
            0.65f * d.score +
            0.22f * centerBias +
            0.10f * bottomBias +
            0.03f * areaNorm
        )
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
        relSpeed: Float,
        objectSpeed: Float,
        ttc: Float,
        label: String
    ) {
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", false)
        i.putExtra("frame_w", frameW)
        i.putExtra("frame_h", frameH)
        lastRoiBox?.let { r ->
            i.putExtra("roi_left", r.x1)
            i.putExtra("roi_top", r.y1)
            i.putExtra("roi_right", r.x2)
            i.putExtra("roi_bottom", r.y2)
        }
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
        i.putExtra("frame_w", lastFrameW)
        i.putExtra("frame_h", lastFrameH)
        lastRoiBox?.let { r ->
            i.putExtra("roi_left", r.x1)
            i.putExtra("roi_top", r.y1)
            i.putExtra("roi_right", r.x2)
            i.putExtra("roi_bottom", r.y2)
        }
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

    private fun alertLevel(distance: Float, relSpeed: Float, ttc: Float, t: AlertThresholds): Int {
        val red =
            (ttc.isFinite() && ttc <= t.ttcRed) ||
                (distance.isFinite() && distance <= t.distRed) ||
                (relSpeed.isFinite() && relSpeed >= t.speedRed)
        if (red) return 2

        val orange =
            (ttc.isFinite() && ttc <= t.ttcOrange) ||
                (distance.isFinite() && distance <= t.distOrange) ||
                (relSpeed.isFinite() && relSpeed >= t.speedOrange)

        return if (orange) 1 else 0
    }

    

private fun computeTtcFromBoxHeights(bestBox: Box, ts: Long): Float? {
    val hPx = bestBox.h.coerceAtLeast(0f)
    if (hPx <= 0f) return null

    if (lastBoxHeightTs <= 0L || lastBoxHeightPx <= 0f) {
        lastBoxHeightPx = hPx
        lastBoxHeightTs = ts
        return null
    }

    val dtSec = ((ts - lastBoxHeightTs).coerceAtLeast(1L)).toFloat() / 1000f
    val ttc = DetectionPhysics.computeTtcFromHeights(
        prevH = lastBoxHeightPx,
        currH = hPx,
        dtSec = dtSec
    )

    lastBoxHeightPx = hPx
    lastBoxHeightTs = ts
    return ttc
}

private fun computeRelativeSpeed(currentDistanceM: Float, ts: Long): Float {
    // Signed relative speed: + = approaching, - = pulling away.
    if (!currentDistanceM.isFinite()) {
        lastDistance = currentDistanceM
        lastDistanceTimestamp = ts
        filteredRelSpeed = 0f
        lastRelSpeedTs = ts
        return 0f
    }
    if (lastDistanceTimestamp <= 0L || !lastDistance.isFinite()) {
        lastDistance = currentDistanceM
        lastDistanceTimestamp = ts
        filteredRelSpeed = 0f
        lastRelSpeedTs = ts
        return 0f
    }

    val dtSec = ((ts - lastDistanceTimestamp).coerceAtLeast(1L)).toFloat() / 1000f
    val raw = (lastDistance - currentDistanceM) / dtSec

    // If gap is large (lag/pause), reset filter to avoid spikes.
    val gapMs = (ts - lastRelSpeedTs).coerceAtLeast(0L)
    val alpha = if (gapMs > 600L) 1.0f else 0.25f

    filteredRelSpeed = (alpha * raw) + ((1f - alpha) * filteredRelSpeed)

    lastDistance = currentDistanceM
    lastDistanceTimestamp = ts
    lastRelSpeedTs = ts
    return filteredRelSpeed
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
