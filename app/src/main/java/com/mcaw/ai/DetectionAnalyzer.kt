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

    // Target lock to avoid switching between objects (stability for TTC/alerts)
    private var lockedTrackId: Long? = null
    private var lockedPriority: Float = 0f
    private var switchCandidateId: Long? = null
    private var switchCandidateCount: Int = 0
    private var lastSelectedTrackId: Long = -1L

    // Distance/time history (for signed relative speed)
    private var lastDistanceM: Float = Float.NaN
    private var lastDistanceTimestampMs: Long = -1L

    // BBox height history (alternative TTC from bbox growth)
    private var lastBoxHeightPx: Float = Float.NaN
    private var lastBoxHeightTimestampMs: Long = -1L

    // Rider speed smoothing (GPS speed is noisy, especially at low speeds)
    private var riderSpeedEma: Float = 0f
    private var riderSpeedEmaValid: Boolean = false

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

            // ROI in pixels (in the SAME coordinate system as rotated bitmap)
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
            // Then map detections back into full-frame coordinates.
            val rawDetectionsRoi = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(roiBitmap).orEmpty()
                1 -> det?.detect(roiBitmap).orEmpty()
                else -> emptyList()
            }

            val rawDetections = mapDetectionsFromRoiToFrame(rawDetectionsRoi, roiOffset)

            // Hard gate: keep only detections that are at least 80% inside ROI.
            // (prevents "auto" on dashboard when ROI is set to the road area)
            val gatedDetections = rawDetections.filter { d ->
                val inside = insideRatio(d.box, roiRectPx)
                inside >= 0.80f
            }

            // Postprocess ve stejném frame (otočený bitmap)
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

            // reset smoothing on target change
            if (lastSelectedTrackId != bestTrack.id) {
                lastSelectedTrackId = bestTrack.id
                resetMotionState()
            }

            // Clamp + canonical label
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
            val approachSpeedFromDist = relSpeedSigned.coerceAtLeast(0f)

            // TTC from bbox growth tends to be more stable than distance derivative
            val boxHPx = (bestBox.y2 - bestBox.y1).coerceAtLeast(0f)
            val ttcFromHeights = computeTtcFromBoxHeights(boxHPx, tsMs)

            val ttcFromDist = if (distanceM.isFinite() && approachSpeedFromDist > 0.30f) {
                (distanceM / approachSpeedFromDist).coerceAtMost(120f)
            } else {
                Float.POSITIVE_INFINITY
            }

            val ttc = ttcFromHeights ?: ttcFromDist

            // Use TTC (if available) to derive approach speed for alerting
            val approachSpeedMps =
                if (ttc.isFinite() && ttc > 0f && distanceM.isFinite()) (distanceM / ttc).coerceIn(0f, 80f)
                else approachSpeedFromDist

            // Object speed estimate (m/s). If relSpeed is "rider - object", then object = rider - rel.
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

            sendOverlayUpdate(bestBox, frameW, frameH, distanceM, relSpeedSigned, objectSpeedMps, ttc, label)
            flog(
                "best id=${bestTrack.id} label=$label score=${best0.score} " +
                    "box=${bestBox.x1},${bestBox.y1},${bestBox.x2},${bestBox.y2} " +
                    "distRaw=${distanceRaw ?: Float.NaN} dist=$distanceM " +
                    "rel=$relSpeedSigned aDist=$approachSpeedFromDist aUse=$approachSpeedMps " +
                    "riderRaw=$riderSpeedRawMps rider=$riderSpeedMps obj=$objectSpeedMps " +
                    "ttcH=${ttcFromHeights ?: Float.NaN} ttcD=$ttcFromDist ttc=$ttc"
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

    private fun resetMotionState() {
        lastDistanceM = Float.NaN
        lastDistanceTimestampMs = -1L
        distEmaValid = false
        distEma = Float.NaN
        relSpeedEmaValid = false
        relSpeedEma = 0f
        lastBoxHeightPx = Float.NaN
        lastBoxHeightTimestampMs = -1L
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
        // deadband: treat tiny speeds as 0 (standing / GPS drift)
        val deadband = 0.8f // ~2.9 km/h
        val v = if (speedRawMps < deadband) 0f else speedRawMps.coerceIn(0f, 80f)
        val alpha = 0.20f
        riderSpeedEma = if (!riderSpeedEmaValid) v else (riderSpeedEma + alpha * (v - riderSpeedEma))
        riderSpeedEmaValid = true
        return riderSpeedEma
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

        return ttc
    }

    private fun selectLockedTarget(
        tracks: List<TemporalTracker.TrackedDetection>,
        frameW: Float,
        frameH: Float
    ): TemporalTracker.TrackedDetection? {
        if (tracks.isEmpty() || frameW <= 0f || frameH <= 0f) return null

        // Prefer stable tracks (gate) but allow fallback when starting.
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
            val dy = abs(cyN - 0.55f) // slightly below center is typical road focus
            val centerScore = (1f - (dx * 1.4f + dy * 1.0f)).coerceIn(0f, 1f)
            val score = d.score.coerceIn(0f, 1f)
            // closeness (area) is most important, then center, then NN confidence
            return (areaNorm * 0.55f) + (centerScore * 0.30f) + (score * 0.15f)
        }

        val bestNow = pool.maxByOrNull { priority(it) } ?: return null
        val bestNowPrio = priority(bestNow)

        val lockedId = lockedTrackId
        val locked = if (lockedId != null) pool.firstOrNull { it.id == lockedId } else null

        // Lock acquisition
        if (locked == null) {
            lockedTrackId = bestNow.id
            lockedPriority = bestNowPrio
            switchCandidateId = null
            switchCandidateCount = 0
            return bestNow
        }

        val lockedPrio = priority(locked)
        lockedPriority = lockedPrio

        // Switching hysteresis: only switch if clearly better for a few frames
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
        // Deadband against monocular jitter: too large threshold kills TTC completely.
        // Make it small + slightly distance-dependent.
        val deadbandM = max(0.03f, currentDistanceM * 0.008f) // ~3 cm or ~0.8% of distance
        val raw = if (abs(dd) < deadbandM) 0f else dd / dtSec

        // Clamp to plausible range (monocular estimate can spike)
        val clamped = raw.coerceIn(-60f, 60f)

        val alpha = 0.30f
        relSpeedEma =
            if (!relSpeedEmaValid) clamped else (relSpeedEma + alpha * (clamped - relSpeedEma))
        relSpeedEmaValid = true

        lastDistanceM = currentDistanceM
        lastDistanceTimestampMs = tsMs

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
        // ensure at least 2px to avoid Bitmap.createBitmap crash
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
        // fast path: no crop
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
