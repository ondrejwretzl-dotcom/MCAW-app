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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
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

    private var lastDistanceM: Float = Float.NaN
    private var lastDistanceTimestampMs: Long = -1L

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
            val bitmap = ImageUtils.rotateBitmap(rawBitmap, rotation)

            val frameW = bitmap.width
            val frameH = bitmap.height

            // ROI (normalizované) -> pixely v otočeném frame
            val roiN = AppPreferences.getRoiN()
            val roiLeftPx = (roiN.left * frameW).roundToInt().coerceIn(0, frameW - 2)
            val roiTopPx = (roiN.top * frameH).roundToInt().coerceIn(0, frameH - 2)
            val roiRightPx = (roiN.right * frameW).roundToInt().coerceIn(roiLeftPx + 2, frameW)
            val roiBottomPx = (roiN.bottom * frameH).roundToInt().coerceIn(roiTopPx + 2, frameH)

            val roiW = (roiRightPx - roiLeftPx).coerceAtLeast(2)
            val roiH = (roiBottomPx - roiTopPx).coerceAtLeast(2)

            if (AppPreferences.debugOverlay) {
                flog("roiN l=${roiN.left} t=${roiN.top} r=${roiN.right} b=${roiN.bottom} roiPx=[$roiLeftPx,$roiTopPx,$roiRightPx,$roiBottomPx] frame=${frameW}x${frameH}")
            }

            // Crop vstupu pro model (zrychlení)
            val roiBitmap = try {
                android.graphics.Bitmap.createBitmap(bitmap, roiLeftPx, roiTopPx, roiW, roiH)
            } catch (e: Exception) {
                // fallback: když by crop selhal, použij plný frame
                flog("roiCropFail ${e.message}")
                bitmap
            }

            val riderSpeedMps = speedProvider.getCurrent().speedMps

            val rawDetectionsRoi = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(roiBitmap).orEmpty()
                1 -> det?.detect(roiBitmap).orEmpty()
                else -> emptyList()
            }

            // Přemapování boxů z ROI do full-frame souřadnic + hard-gate do ROI
            val gatedDetections = rawDetectionsRoi.mapNotNull { d ->
                val b = d.box
                // clamp na ROI bitmap bounds (model občas vrátí mimo)
                val x1 = b.x1.coerceIn(0f, roiW.toFloat())
                val y1 = b.y1.coerceIn(0f, roiH.toFloat())
                val x2 = b.x2.coerceIn(0f, roiW.toFloat())
                val y2 = b.y2.coerceIn(0f, roiH.toFloat())
                val left = minOf(x1, x2)
                val top = minOf(y1, y2)
                val right = maxOf(x1, x2)
                val bottom = maxOf(y1, y2)

                val cxFull = roiLeftPx + (left + right) * 0.5f
                val cyFull = roiTopPx + (top + bottom) * 0.5f

                val inside =
                    cxFull >= roiLeftPx && cxFull <= roiRightPx &&
                        cyFull >= roiTopPx && cyFull <= roiBottomPx

                if (!inside) null
                else d.copy(
                    box = Box(
                        left + roiLeftPx,
                        top + roiTopPx,
                        right + roiLeftPx,
                        bottom + roiTopPx
                    )
                )
            }

            if (AppPreferences.debugOverlay) {
                flog("roiGate rawRoi=${rawDetectionsRoi.size} kept=${gatedDetections.size}")
            }

            val post = postProcessor.process(gatedDetections, frameW.toFloat(), frameH.toFloat())
            flog("counts raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} accepted=${post.counts.filters}")

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

            val best0 = bestTrack.detection
            val bestBox = clampBox(best0.box, frameW.toFloat(), frameH.toFloat())
            val label = DetectionLabelMapper.toCanonical(best0.label) ?: (best0.label ?: "unknown")

            val distanceRaw = DetectionPhysics.estimateDistanceMeters(
                bbox = bestBox,
                frameHeightPx = frameH,
                focalPx = estimateFocalLengthPx(frameH),
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

            sendOverlayUpdate(
                box = bestBox,
                frameW = frameW.toFloat(),
                frameH = frameH.toFloat(),
                dist = distanceM,
                relSpeed = relSpeedSigned,
                objectSpeed = objectSpeedMps,
                ttc = ttc,
                label = label,
                roiN = roiN
            )
            sendMetricsUpdate(distanceM, relSpeedSigned, objectSpeedMps, ttc, level, label)

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
        relSpeed: Float,
        objectSpeed: Float,
        ttc: Float,
        label: String,
        roiN: AppPreferences.RoiN? = null
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

        // ROI pro overlay (pokud podporuje)
        val r = roiN ?: AppPreferences.getRoiN()
        i.putExtra("roi_left_n", r.left)
        i.putExtra("roi_top_n", r.top)
        i.putExtra("roi_right_n", r.right)
        i.putExtra("roi_bottom_n", r.bottom)

        ctx.sendBroadcast(i)
    }

    private fun sendOverlayClear() {
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
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
}
