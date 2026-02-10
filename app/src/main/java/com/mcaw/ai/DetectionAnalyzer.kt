package com.mcaw.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
        const val EXTRA_SPEED = "extra_speed" // REL = approach speed (m/s), always >= 0
        const val EXTRA_OBJECT_SPEED = "extra_object_speed" // OBJ speed estimate (m/s) = rider - relSigned
        const val EXTRA_RIDER_SPEED = "extra_rider_speed" // RID speed (m/s)
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_LABEL = "extra_label"
    }

    private val analyzerLogFileName: String = "mcaw_analyzer_${System.currentTimeMillis()}.txt"

    private val postProcessor = DetectionPostProcessor(
        DetectionPostProcessor.Config(debug = AppPreferences.debugOverlay)
    )

    private val tracker = TemporalTracker(minConsecutiveForAlert = 3)

    // Target lock to avoid switching between objects (stability for TTC/alerts)
    private var lockedTrackId: Long? = null
    private var lockedPriority: Float = 0f
    private var switchCandidateId: Long? = null
    private var switchCandidateCount: Int = 0
    private var lastSelectedTrackId: Long = -1L

    // Distance history for sliding-window relative speed
    private data class DistSample(val tsMs: Long, val distM: Float)
    private val distHistory: ArrayDeque<DistSample> = ArrayDeque()

    // EMA smoothing
    private var relSpeedEma: Float = 0f
    private var relSpeedEmaValid: Boolean = false

    // TTC smoothing/hold (prevents blinking when switching TTC source)
    private var ttcEma: Float = Float.POSITIVE_INFINITY
    private var ttcEmaValid: Boolean = false
    private var lastTtcUpdateTsMs: Long = -1L

    private var lastTtcHeight: Float = Float.POSITIVE_INFINITY
    private var lastTtcHeightTsMs: Long = -1L
    private val ttcHeightHoldMs: Long = 800L

    private var distEma: Float = Float.NaN
    private var distEmaValid: Boolean = false

    // Rider speed smoothing (GPS speed is noisy, especially at low speeds)
    private var riderSpeedEma: Float = 0f
    private var riderSpeedEmaValid: Boolean = false

    // BBox height history (alternative TTC from bbox growth)
    private var lastBoxHeightPx: Float = Float.NaN
    private var lastBoxHeightTimestampMs: Long = -1L

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

            val frameW = bitmap.width.toFloat()
            val frameH = bitmap.height.toFloat()

            val roiRectPx = roiRectPx(frameW, frameH)
            val roiBitmapAndOffset = cropForRoi(bitmap, roiRectPx)
            val roiBitmap = roiBitmapAndOffset.first
            val roiOffset = roiBitmapAndOffset.second

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

            val riderSpeedRawMps = speedProvider.getCurrent().speedMps
            val riderSpeedMps = smoothRiderSpeed(riderSpeedRawMps)

            // Run detector on ROI crop for performance + to avoid picking dashboard/edges.
            val rawDetectionsRoi = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(roiBitmap).orEmpty()
                1 -> det?.detect(roiBitmap).orEmpty()
                else -> emptyList()
            }

            val rawDetections = mapDetectionsFromRoiToFrame(rawDetectionsRoi, roiOffset)

            // Hard gate: keep only detections that are at least 80% inside ROI.
            val gatedDetections = rawDetections.filter { d ->
                insideRatio(d.box, roiRectPx) >= 0.80f
            }

            val post = postProcessor.process(gatedDetections, frameW, frameH)
            flog("counts raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} accepted=${post.counts.filters}")

            if (AppPreferences.debugOverlay) {
                flog(
                    "roi n=${AppPreferences.getRoiNormalized()} px=[${roiRectPx.left.toInt()},${roiRectPx.top.toInt()},${roiRectPx.right.toInt()},${roiRectPx.bottom.toInt()}] " +
                        "crop=${roiBitmap.width}x${roiBitmap.height} offset=(${roiOffset.first},${roiOffset.second}) rawRoi=${rawDetectionsRoi.size} rawMapped=${rawDetections.size} gated=${gatedDetections.size}"
                )
            }

            val tracked = tracker.update(post.accepted)
            val bestTrack = selectLockedTarget(tracked, frameW, frameH)

            if (bestTrack == null) {
                lockedTrackId = null
                switchCandidateId = null
                switchCandidateCount = 0
                stopActiveAlerts()
                resetMotionState()
                sendOverlayClear()
                sendMetricsClear()
                return
            }

            // Reset history on target change (prevents TTC/REL jumps when switching trackId)
            if (lastSelectedTrackId != bestTrack.id) {
                lastSelectedTrackId = bestTrack.id
                resetMotionState()
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

            // REL speed (signed internal) from sliding-window + EMA
            val relSpeedSigned = computeRelativeSpeedSignedWindow(distanceM, tsMs)
            val approachSpeedFromDist = relSpeedSigned.coerceAtLeast(0f)

            // TTC from bbox growth tends to be more stable than distance derivative
            val boxHPx = (bestBox.y2 - bestBox.y1).coerceAtLeast(0f)
            val ttcFromHeightsNow = computeTtcFromBoxHeights(boxHPx, tsMs)

            // Hold-last-valid bbox TTC briefly to avoid blinking when bbox growth momentarily fails
            val ttcFromHeightsHeld = when {
                ttcFromHeightsNow != null && ttcFromHeightsNow.isFinite() -> ttcFromHeightsNow
                lastTtcHeight.isFinite() && lastTtcHeightTsMs > 0L && (tsMs - lastTtcHeightTsMs) <= ttcHeightHoldMs -> lastTtcHeight
                else -> null
            }

            val ttcFromDist = if (distanceM.isFinite() && approachSpeedFromDist > 0.30f) {
                (distanceM / approachSpeedFromDist).coerceIn(0.05f, 120f)
            } else {
                Float.POSITIVE_INFINITY
            }

            // Blend sources instead of hard switching (prevents sudden TTC jumps)
            val ttcRaw = when {
                ttcFromHeightsHeld != null && ttcFromHeightsHeld.isFinite() && ttcFromDist.isFinite() -> {
                    // Prefer bbox TTC, but keep a bit of dist TTC as sanity
                    (ttcFromHeightsHeld * 0.75f) + (ttcFromDist * 0.25f)
                }
                ttcFromHeightsHeld != null && ttcFromHeightsHeld.isFinite() -> ttcFromHeightsHeld
                else -> ttcFromDist
            }

            val ttc = smoothTtc(ttcRaw, tsMs)

            // Approach speed used for alerting (derived from final smoothed TTC if available)
            val approachSpeedMps =
                if (ttc.isFinite() && ttc > 0f && distanceM.isFinite()) (distanceM / ttc).coerceIn(0f, 80f)
                else approachSpeedFromDist

            // OBJ = rider - relSigned (relSigned = rider - object)
            val objectSpeedMps =
                if (riderSpeedMps.isFinite()) (riderSpeedMps - relSpeedSigned) else Float.POSITIVE_INFINITY

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
                frameW = frameW,
                frameH = frameH,
                dist = distanceM,
                approachSpeed = approachSpeedMps,
                objectSpeed = objectSpeedMps,
                riderSpeed = riderSpeedMps,
                ttc = ttc,
                label = label
            )

            flog(
                "best id=${bestTrack.id} label=$label score=${best0.score} " +
                    "box=${bestBox.x1},${bestBox.y1},${bestBox.x2},${bestBox.y2} " +
                    "distRaw=${distanceRaw ?: Float.NaN} dist=$distanceM " +
                    "relSigned=$relSpeedSigned relApp=$approachSpeedFromDist " +
                    "riderRaw=$riderSpeedRawMps rider=$riderSpeedMps obj=$objectSpeedMps " +
                    "ttcH=${ttcFromHeights ?: Float.NaN} ttcD=$ttcFromDist ttc=$ttc"
            )

            sendMetricsUpdate(
                dist = distanceM,
                approachSpeed = approachSpeedMps,
                objectSpeed = objectSpeedMps,
                riderSpeed = riderSpeedMps,
                ttc = ttc,
                level = level,
                label = label
            )

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
        val left = min(x1, x2)
        val top = min(y1, y2)
        val right = max(x1, x2)
        val bottom = max(y1, y2)
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
        approachSpeed: Float,
        objectSpeed: Float,
        riderSpeed: Float,
        ttc: Float,
        label: String
    ) {
        val roiN = AppPreferences.getRoiNormalized()
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", false)
        i.putExtra("frame_w", frameW)
        i.putExtra("frame_h", frameH)
        i.putExtra("left", box.x1)
        i.putExtra("top", box.y1)
        i.putExtra("right", box.x2)
        i.putExtra("bottom", box.y2)
        i.putExtra("dist", dist)
        i.putExtra("speed", approachSpeed) // REL (approach)
        i.putExtra("object_speed", objectSpeed) // OBJ
        i.putExtra("rider_speed", riderSpeed) // RID
        i.putExtra("ttc", ttc)
        i.putExtra("label", label)

        // keep ROI always in preview overlay
        i.putExtra("roi_left_n", roiN.left)
        i.putExtra("roi_top_n", roiN.top)
        i.putExtra("roi_right_n", roiN.right)
        i.putExtra("roi_bottom_n", roiN.bottom)

        ctx.sendBroadcast(i)
    }

    private fun sendOverlayClear() {
        val roiN = AppPreferences.getRoiNormalized()
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
        approachSpeed: Float,
        objectSpeed: Float,
        riderSpeed: Float,
        ttc: Float,
        level: Int,
        label: String
    ) {
        val i = Intent(ACTION_METRICS_UPDATE).setPackage(ctx.packageName)
        i.putExtra(EXTRA_DISTANCE, dist)
        i.putExtra(EXTRA_SPEED, approachSpeed)
        i.putExtra(EXTRA_OBJECT_SPEED, objectSpeed)
        i.putExtra(EXTRA_RIDER_SPEED, riderSpeed)
        i.putExtra(EXTRA_TTC, ttc)
        i.putExtra(EXTRA_LEVEL, level)
        i.putExtra(EXTRA_LABEL, label)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsClear() {
        sendMetricsUpdate(
            dist = Float.POSITIVE_INFINITY,
            approachSpeed = Float.POSITIVE_INFINITY,
            objectSpeed = Float.POSITIVE_INFINITY,
            riderSpeed = Float.POSITIVE_INFINITY,
            ttc = Float.POSITIVE_INFINITY,
            level = 0,
            label = ""
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

    private fun resetMotionState() {
        distHistory.clear()
        distEmaValid = false
        distEma = Float.NaN
        relSpeedEmaValid = false
        relSpeedEma = 0f
        lastBoxHeightPx = Float.NaN
        lastBoxHeightTimestampMs = -1L
    
        ttcEmaValid = false
        ttcEma = Float.POSITIVE_INFINITY
        lastTtcUpdateTsMs = -1L
        lastTtcHeight = Float.POSITIVE_INFINITY
        lastTtcHeightTsMs = -1L
    }

    /**
     * GPS speed is noisy at low speed and causes "object speed" to look wrong in stationary tests.
     * We apply a deadband + EMA and keep units in m/s internally.
     */
    private fun smoothRiderSpeed(speedRawMps: Float): Float {
        if (!speedRawMps.isFinite() || speedRawMps < 0f) {
            riderSpeedEmaValid = false
            riderSpeedEma = 0f
            return 0f
        }
        val deadband = 0.8f // ~2.9 km/h
        val v = if (speedRawMps < deadband) 0f else speedRawMps.coerceIn(0f, 80f)
        val alpha = 0.20f
        riderSpeedEma = if (!riderSpeedEmaValid) v else (riderSpeedEma + alpha * (v - riderSpeedEma))
        riderSpeedEmaValid = true
        return riderSpeedEma
    }


    /**
     * Stabilizes TTC to prevent UI flicker / alert jitter.
     * - clamps unrealistic jumps based on dt
     * - asymmetric EMA (faster when TTC decreases)
     */
    private fun smoothTtc(ttcRaw: Float, tsMs: Long): Float {
        val raw = if (ttcRaw.isFinite() && ttcRaw > 0f) ttcRaw.coerceIn(0.05f, 120f) else Float.POSITIVE_INFINITY

        if (!raw.isFinite()) {
            ttcEmaValid = false
            ttcEma = Float.POSITIVE_INFINITY
            lastTtcUpdateTsMs = tsMs
            return Float.POSITIVE_INFINITY
        }

        if (!ttcEmaValid || !ttcEma.isFinite() || lastTtcUpdateTsMs <= 0L) {
            ttcEma = raw
            ttcEmaValid = true
            lastTtcUpdateTsMs = tsMs
            return ttcEma
        }

        val dtSec = ((tsMs - lastTtcUpdateTsMs).coerceAtLeast(1L)).toFloat() / 1000f
        lastTtcUpdateTsMs = tsMs

        // Limit TTC change rate (seconds per second). Allow faster drops than rises.
        val maxDropRate = 6.0f   // TTC can drop by up to 6s per 1s
        val maxRiseRate = 3.0f   // TTC can rise by up to 3s per 1s
        val maxDrop = maxDropRate * dtSec
        val maxRise = maxRiseRate * dtSec

        val prev = ttcEma
        val clamped = when {
            raw < prev -> raw.coerceAtLeast(prev - maxDrop)
            else -> raw.coerceAtMost(prev + maxRise)
        }

        val alpha = if (clamped < prev) 0.45f else 0.20f
        ttcEma = prev + alpha * (clamped - prev)
        return ttcEma
    }

    /**
     * TTC estimated directly from bbox height growth (less sensitive to distance quantization).
     * Returns null if growth is too small/noisy.
     */
    private fun computeTtcFromBoxHeights(currHPx: Float, tsMs: Long): Float? {
        if (currHPx <= 0f || !currHPx.isFinite()) {
            lastBoxHeightPx = Float.NaN
            lastBoxHeightTimestampMs = tsMs
            return null
        }

        if (!lastBoxHeightPx.isFinite() || lastBoxHeightTimestampMs <= 0L) {
            lastBoxHeightPx = currHPx
            lastBoxHeightTimestampMs = tsMs
            return null
        }

        val dtSec = ((tsMs - lastBoxHeightTimestampMs).coerceAtLeast(1L)).toFloat() / 1000f
        if (dtSec > 1.0f) {
            lastBoxHeightPx = currHPx
            lastBoxHeightTimestampMs = tsMs
            return null
        }

        val ttc = DetectionPhysics.computeTtcFromHeights(
            prevH = lastBoxHeightPx,
            currH = currHPx,
            dtSec = dtSec,
            minDtSec = 0.05f,
            minGrowthRatio = 1.01f,
            minDeltaHPx = 1.0f,
            maxTtcSec = 120f
        )

        lastBoxHeightPx = currHPx
        lastBoxHeightTimestampMs = tsMs

        if (ttc != null && ttc.isFinite()) {
            lastTtcHeight = ttc
            lastTtcHeightTsMs = tsMs
        }

        return ttc
    }

    private fun selectLockedTarget(
        tracks: List<TemporalTracker.TrackedDetection>,
        frameW: Float,
        frameH: Float
    ): TemporalTracker.TrackedDetection? {
        if (tracks.isEmpty() || frameW <= 0f || frameH <= 0f) return null

        val gated = tracks.filter { it.alertGatePassed && it.misses == 0 }
        val pool = if (gated.isNotEmpty()) gated else tracks.filter { it.misses == 0 }
        if (pool.isEmpty()) return null

        fun priority(t: TemporalTracker.TrackedDetection): Float {
            val d = t.detection
            val b = d.box
            val areaNorm = (b.area / (frameW * frameH)).coerceIn(0f, 1f)
            val cxN = (b.cx / frameW).coerceIn(0f, 1f)
            val cyN = (b.cy / frameH).coerceIn(0f, 1f)
            val dx = abs(cxN - 0.5f)
            val dy = abs(cyN - 0.55f)
            val centerScore = (1f - (dx * 1.4f + dy * 1.0f)).coerceIn(0f, 1f)
            val score = d.score.coerceIn(0f, 1f)
            return (areaNorm * 0.55f) + (centerScore * 0.30f) + (score * 0.15f)
        }

        val bestNow = pool.maxByOrNull { priority(it) } ?: return null
        val bestNowPrio = priority(bestNow)

        val lockedId = lockedTrackId
        val locked = if (lockedId != null) pool.firstOrNull { it.id == lockedId } else null

        if (locked == null) {
            lockedTrackId = bestNow.id
            lockedPriority = bestNowPrio
            switchCandidateId = null
            switchCandidateCount = 0
            return bestNow
        }

        val lockedPrio = priority(locked)
        lockedPriority = lockedPrio

        val switchMargin = 1.25f
        if (bestNow.id != locked.id && bestNowPrio >= lockedPrio * switchMargin) {
            if (switchCandidateId == bestNow.id) {
                switchCandidateCount += 1
            } else {
                switchCandidateId = bestNow.id
                switchCandidateCount = 1
            }

            if (switchCandidateCount >= 3) {
                lockedTrackId = bestNow.id
                lockedPriority = bestNowPrio
                switchCandidateId = null
                switchCandidateCount = 0
                return bestNow
            }
        } else {
            switchCandidateId = null
            switchCandidateCount = 0
        }

        return locked
    }

    /**
     * Signed relative speed (m/s) from distance history:
     * relSigned = (dist_old - dist_now) / dt
     * + = approaching (distance decreasing)
     * - = receding (distance increasing)
     *
     * Uses sliding-window (0.3..0.9s) + EMA => stabilnější než 1-step derivace.
     */
    private fun computeRelativeSpeedSignedWindow(currentDistanceM: Float, tsMs: Long): Float {
        if (!currentDistanceM.isFinite() || currentDistanceM <= 0f) {
            distHistory.clear()
            relSpeedEmaValid = false
            relSpeedEma = 0f
            return 0f
        }

        distHistory.addLast(DistSample(tsMs, currentDistanceM))

        // Keep ~1.2s of history max
        val keepMs = 1200L
        while (distHistory.isNotEmpty() && tsMs - distHistory.first().tsMs > keepMs) {
            distHistory.removeFirst()
        }

        // Need at least 2 samples
        if (distHistory.size < 2) return 0f

        val minAgeMs = 300L
        val maxAgeMs = 900L
        val targetAgeMs = 600L

        // Pick the sample with age in [min,max] closest to targetAgeMs
        var best: DistSample? = null
        var bestErr = Long.MAX_VALUE
        for (s in distHistory) {
            val age = tsMs - s.tsMs
            if (age < minAgeMs || age > maxAgeMs) continue
            val err = kotlin.math.abs(age - targetAgeMs)
            if (err < bestErr) {
                bestErr = err
                best = s
            }
        }

        val ref = best ?: return 0f
        val dtSec = (tsMs - ref.tsMs).toFloat() / 1000f
        if (dtSec <= 0.05f || dtSec > 1.2f) return 0f

        val dd = ref.distM - currentDistanceM // + when approaching
        val deadbandM = max(0.05f, currentDistanceM * 0.010f) // 5 cm or 1% distance
        val raw = if (abs(dd) < deadbandM) 0f else (dd / dtSec)

        val clamped = raw.coerceIn(-60f, 60f)

        val alpha = 0.35f
        relSpeedEma = if (!relSpeedEmaValid) clamped else (relSpeedEma + alpha * (clamped - relSpeedEma))
        relSpeedEmaValid = true

        return relSpeedEma
    }

    /** ROI rect in pixel coordinates of the rotated bitmap/frame. */
    private fun roiRectPx(frameW: Float, frameH: Float): RectF {
        val roiN = AppPreferences.getRoiNormalized()
        val l = (roiN.left * frameW).coerceIn(0f, frameW)
        val t = (roiN.top * frameH).coerceIn(0f, frameH)
        val r = (roiN.right * frameW).coerceIn(0f, frameW)
        val b = (roiN.bottom * frameH).coerceIn(0f, frameH)
        val left = min(l, r)
        val top = min(t, b)
        val right = max(l, r)
        val bottom = max(t, b)
        val rr = if (right - left < 2f) (left + 2f).coerceAtMost(frameW) else right
        val bb = if (bottom - top < 2f) (top + 2f).coerceAtMost(frameH) else bottom
        return RectF(left, top, rr, bb)
    }

    /**
     * Crops bitmap to ROI for detector input. Returns (roiBitmap, offsetPx(left,top)).
     * If ROI is invalid, returns original bitmap and (0,0).
     */
    private fun cropForRoi(src: Bitmap, roiPx: RectF): Pair<Bitmap, Pair<Int, Int>> {
        val left = roiPx.left.roundToInt().coerceIn(0, src.width - 1)
        val top = roiPx.top.roundToInt().coerceIn(0, src.height - 1)
        val right = roiPx.right.roundToInt().coerceIn(left + 1, src.width)
        val bottom = roiPx.bottom.roundToInt().coerceIn(top + 1, src.height)
        val w = (right - left).coerceAtLeast(2)
        val h = (bottom - top).coerceAtLeast(2)
        if (left == 0 && top == 0 && w == src.width && h == src.height) {
            return src to (0 to 0)
        }
        val cropped = try {
            Bitmap.createBitmap(src, left, top, w, h)
        } catch (_: IllegalArgumentException) {
            src
        }
        return cropped to (left to top)
    }

    /** Map detection boxes from ROI-cropped bitmap coords back to full frame coords. */
    private fun mapDetectionsFromRoiToFrame(dets: List<Detection>, offset: Pair<Int, Int>): List<Detection> {
        val ox = offset.first.toFloat()
        val oy = offset.second.toFloat()
        if (ox == 0f && oy == 0f) return dets
        return dets.map { d ->
            val b = d.box
            d.copy(box = Box(b.x1 + ox, b.y1 + oy, b.x2 + ox, b.y2 + oy))
        }
    }

    /** Returns ratio of bbox area that lies inside ROI [0..1]. */
    private fun insideRatio(box: Box, roi: RectF): Float {
        val bx1 = min(box.x1, box.x2)
        val by1 = min(box.y1, box.y2)
        val bx2 = max(box.x1, box.x2)
        val by2 = max(box.y1, box.y2)
        val area = (bx2 - bx1).coerceAtLeast(0f) * (by2 - by1).coerceAtLeast(0f)
        if (area <= 1e-3f) return 0f
        val ix1 = max(bx1, roi.left)
        val iy1 = max(by1, roi.top)
        val ix2 = min(bx2, roi.right)
        val iy2 = min(by2, roi.bottom)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        return (inter / area).coerceIn(0f, 1f)
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
