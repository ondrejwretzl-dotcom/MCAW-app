package com.mcaw.ai

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.config.DetectionModePolicy
import com.mcaw.location.SpeedProvider
import com.mcaw.location.RiderImuMonitor
import com.mcaw.risk.RiskEngine
import com.mcaw.model.Box
import com.mcaw.model.Detection
import com.mcaw.util.PublicLogWriter
import com.mcaw.util.SessionLogFile
import com.mcaw.util.SessionEventLogger
import com.mcaw.util.SessionTraceLogger
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
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
        const val EXTRA_BRAKE_CUE = "extra_brake_cue"
        const val EXTRA_ALERT_REASON = "extra_alert_reason"
        const val EXTRA_REASON_BITS = "extra_reason_bits"
        const val EXTRA_RISK_SCORE = "extra_risk_score"
    }

    private val analyzerLogFileName: String = SessionLogFile.fileName

    // Single-session loggers (one file per app use).
    // Event logger is ALWAYS-ON; trace logger follows debugOverlay.
    private val eventLogger = SessionEventLogger(ctx, SessionLogFile.fileName)
    private var traceLogger: SessionTraceLogger? = null

    // Event sampling: keep low overhead.
    private val eventEveryNFrames: Long = 10L

    // Performance: stage timing (debugOverlay only)
    private data class PerfAgg(
        var frames: Long = 0,
        var preprocMs: Long = 0,
        var inferMs: Long = 0,
        var postMs: Long = 0,
        var totalMs: Long = 0
    )

    private val perf = PerfAgg()

    // C2: Smoothed quality weight (0..1) to avoid rapid toggling.
    private var qualityWeightEma: Float = 1f
    private var qualityWeightEmaValid: Boolean = false


    // Frame quality heuristics (cheap): low-light + low edge energy => likely blur/noise.
    // C2: quality is a weight (0..1), not a hard gate.
    private data class FrameQuality(
        val poor: Boolean,
        val meanLuma: Float,
        val meanGrad: Float,
        val weight: Float
    )

    private fun assessFrameQuality(image: ImageProxy): FrameQuality {
        if (!AppPreferences.qualityGatingEnabled) {
            return FrameQuality(poor = false, meanLuma = Float.NaN, meanGrad = Float.NaN, weight = 1f)
        }
        val plane = image.planes.firstOrNull() ?: return FrameQuality(false, Float.NaN, Float.NaN, 1f)
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val w = image.width
    val h = image.height
        if (w <= 0 || h <= 0 || rowStride <= 0) return FrameQuality(false, Float.NaN, Float.NaN, 1f)

    // sample a central window to avoid sky/dashboard; works regardless of rotation.
    val x0 = (w * 0.20f).toInt().coerceIn(0, w - 1)
    val x1 = (w * 0.80f).toInt().coerceIn(x0 + 1, w)
    val y0 = (h * 0.25f).toInt().coerceIn(0, h - 1)
    val y1 = (h * 0.85f).toInt().coerceIn(y0 + 1, h)

    val step = 10 // coarse subsample for speed
    var sumL = 0.0
    var sumG = 0.0
    var n = 0

    fun yAt(x: Int, y: Int): Int {
        val idx = y * rowStride + x
        return buf.get(idx).toInt() and 0xFF
    }

    try {
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1 - 1) {
                val p = yAt(x, y)
                val p2 = yAt(x + 1, y)
                val g = kotlin.math.abs(p2 - p)
                sumL += p
                sumG += g
                n++
                x += step
            }
            y += step
        }
        } catch (t: Throwable) {
            return FrameQuality(false, Float.NaN, Float.NaN, 1f)
        }

        if (n <= 0) return FrameQuality(false, Float.NaN, Float.NaN, 1f)
    val meanL = (sumL / n).toFloat()
    val meanG = (sumG / n).toFloat()

    // Thresholds tuned to be conservative on phones (Samsung A56).
        val lowLight = meanL < 35f
        val lowEdges = meanG < 4.5f

        // Weight mapping (conservative): low light and low edge energy reduce confidence smoothly.
        fun ramp01(x: Float, lo: Float, hi: Float): Float {
            if (!x.isFinite()) return 1f
            val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
            return t
        }
        // 0.6..1.0 range to avoid "muting" the system completely.
        val wLight = 0.60f + 0.40f * ramp01(meanL, lo = 20f, hi = 60f)
        val wEdges = 0.60f + 0.40f * ramp01(meanG, lo = 3.0f, hi = 8.0f)
        val wQ = minOf(wLight, wEdges).coerceIn(0.60f, 1.0f)

        val poor = (wQ < 0.75f) || lowLight || lowEdges
        return FrameQuality(poor = poor, meanLuma = meanL, meanGrad = meanG, weight = wQ)
}



    // Performance tuning (PACK1_THROTTLE_LOG_TRACK2)
    private var frameIndex: Long = 0L
    private var lastMetricsSentElapsedMs: Long = 0L
    private var lastOverlaySentElapsedMs: Long = 0L
    private var lastMetricsSentAlertLevel: Int = -1
    private var lastOverlaySentAlertLevel: Int = -1

    private val metricsIntervalMs: Long = 80L   // ~12.5 Hz
    private val overlayIntervalMs: Long = 80L   // ~12.5 Hz
    private val logEveryNFrames: Long = 10L     // log sampling when debug overlay enabled

    private val postProcessor = DetectionPostProcessor(
        DetectionPostProcessor.Config(debug = AppPreferences.debugOverlay)
    )

    private val tracker = TemporalTracker(minConsecutiveForAlert = 2)

    // Auto mode state (single source of truth in DetectionModePolicy)
    private val autoModeSwitcher = DetectionModePolicy.AutoModeSwitcher()

    private val imuMonitor = RiderImuMonitor(ctx)
    private val riskEngine = RiskEngine()

    // Target lock to avoid switching between objects (stability for TTC/alerts)
    private var lockedTrackId: Long? = null
    private var lockedPriority: Float = 0f
    private var switchCandidateId: Long? = null
    private var switchCandidateCount: Int = 0
    private var lastSelectedTrackId: Long = -1L

    // Lock timing (prevents rapid target switching / UI blinking)
    private var lockedSinceMs: Long = 0L


    // Distance history for sliding-window relative speed
    private data class DistSample(val tsMs: Long, val distM: Float)
    // TTC smoothing/hold (prevents blinking when switching TTC source)
    private var ttcEma: Float = Float.POSITIVE_INFINITY
    private var ttcEmaValid: Boolean = false
    private var lastTtcUpdateTsMs: Long = -1L

    private var lastTtcHeight: Float = Float.POSITIVE_INFINITY
    private var lastTtcHeightTsMs: Long = -1L

    // TTC slope (sec/sec) for trend detection (negative = TTC decreasing)
    private var lastTtcForSlope: Float = Float.NaN
    private var lastTtcForSlopeTsMs: Long = -1L
    private var ttcSlopeEma: Float = 0f
    private var ttcSlopeEmaValid: Boolean = false
    private val ttcHeightHoldMs: Long = 1200L

    private var distEma: Float = Float.NaN
    private var distEmaValid: Boolean = false

    // Rider speed smoothing (GPS speed is noisy, especially at low speeds)
    private var riderSpeedEma: Float = 0f
    private var riderSpeedEmaValid: Boolean = false

    // Brake cue (heuristika rozsvícených brzdových světel) – držíme krátkou historii pro stabilitu
    private var brakeRedRatioEma: Float = 0f
    private var brakeIntensityEma: Float = 0f
    private var brakePrevIntensityEma: Float = 0f
    private var brakeCueActiveFrames: Int = 0
    private var brakeCueLastActiveMs: Long = -1L
    private var brakeCueActive: Boolean = false
    private var brakeCueStrength: Float = 0f


    // BBox height history (alternative TTC from bbox growth)
    private var lastBoxHeightPx: Float = Float.NaN
    private var lastBoxHeightTimestampMs: Long = -1L

    private var lastAlertLevel = 0
    private var lastTtcLevel: Int = 0

    // --- Cut-in detection (dynamic ego offset boost) ---
    private var cutInPrevAreaNorm: Float = Float.NaN
    private var cutInPrevOffset: Float = Float.NaN
    private var cutInPrevTsMs: Long = -1L
    private var cutInBoostUntilMs: Long = -1L

private fun dynamicEgoMaxOffset(tsMs: Long): Float {
    val base = AppPreferences.laneEgoMaxOffset
    if (!AppPreferences.cutInProtectionEnabled) return base
    val boosted = if (cutInBoostUntilMs > 0L && tsMs <= cutInBoostUntilMs) {
        (base + AppPreferences.cutInOffsetBoost).coerceIn(0.20f, 1.00f)
    } else {
        base
    }
    return boosted
}

private fun updateCutInState(tsMs: Long, box: Box, frameW: Float, frameH: Float) {
    if (!AppPreferences.cutInProtectionEnabled) return
    if (frameW <= 0f || frameH <= 0f) return

    val areaNorm = (box.area / (frameW * frameH)).coerceIn(0f, 1f)
    val off = egoOffsetInRoiN(box, frameW, frameH)

    if (!cutInPrevAreaNorm.isFinite() || cutInPrevTsMs <= 0L) {
        cutInPrevAreaNorm = areaNorm
        cutInPrevOffset = off
        cutInPrevTsMs = tsMs
        return
    }

    val dtMs = (tsMs - cutInPrevTsMs).coerceAtLeast(0L)
    if (dtMs in 80L..450L) {
        val growth = if (cutInPrevAreaNorm > 0f) (areaNorm / cutInPrevAreaNorm) else 1f
        val movingToCenter = off < (cutInPrevOffset - 0.08f)

        if (growth.isFinite() && growth >= AppPreferences.cutInGrowthRatio && movingToCenter) {
            cutInBoostUntilMs = tsMs + AppPreferences.cutInBoostMs
            flog("cutin boost until=$cutInBoostUntilMs growth=%.2f off=%.2f->%.2f".format(growth, cutInPrevOffset, off), force = true)
        }
    }

    // update baseline
    cutInPrevAreaNorm = areaNorm
    cutInPrevOffset = off
    cutInPrevTsMs = tsMs
}


    // --- Per-track motion state (fix: prevent REL/TTC resets when tracker switches IDs) ---
    private data class MotionState(
        val distHistory: ArrayDeque<DistSample> = ArrayDeque(),
        var relSpeedEma: Float = 0f,
        var relSpeedEmaValid: Boolean = false,
        var lastRelSigned: Float = 0f,
        var lastRelTsMs: Long = 0L
    )

    private val motionByTrack: HashMap<Long, MotionState> = HashMap()
    private var activeMotionTrackId: Long = -1L

    // TTC hold: keep last finite TTC briefly when raw becomes invalid (prevents blinking)
    private var lastTtcFiniteTsMs: Long = -1L
    private val ttcInvalidHoldMs: Long = 900L


    private fun flog(msg: String, force: Boolean = false) {
        if (!AppPreferences.debugOverlay) return
        if (!force && (frameIndex % logEveryNFrames != 0L)) return
        // Debug-only: never do file IO from analyzer thread.
        val tsMs = System.currentTimeMillis()
        val clean = msg.replace("\n", " ").replace("\r", " ").trim()
        val escaped = "\"" + clean.replace("\"", "\"\"") + "\""

        // Route to trace logger (buffered + flushed off-thread) when available.
        // Fallback to logcat in the unlikely case trace logger isn't active.
        val line = "S,$tsMs,$escaped"
        traceLogger?.logLine(line) ?: android.util.Log.d("DetectionAnalyzer", line)
    }

    init {
        eventLogger.start()
        if (AppPreferences.debugOverlay) {
            traceLogger = SessionTraceLogger(ctx, SessionLogFile.fileName).also { it.start() }
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            frameIndex += 1
            val tsMs = System.currentTimeMillis()

            val t0Ns = SystemClock.elapsedRealtimeNanos()

            val rotation = image.imageInfo.rotationDegrees
            val (frameWRotI, frameHRotI) = ImagePreprocessor.rotatedFrameSize(image.width, image.height, rotation)
            val frameW = frameWRotI.toFloat()
            val frameH = frameHRotI.toFloat()

            val roiTrap = roiTrapezoidPx(frameW, frameH)
            val roiRect = android.graphics.Rect(
                roiTrap.bounds.left.toInt(),
                roiTrap.bounds.top.toInt(),
                roiTrap.bounds.right.toInt(),
                roiTrap.bounds.bottom.toInt()
            )

            flog(
                "frame proxy=${image.width}x${image.height} rot=$rotation rotFrame=${frameWRotI}x${frameHRotI} model=${AppPreferences.selectedModel}"
            )

            if (AppPreferences.debugOverlay) {
                Log.d(
                    "DetectionAnalyzer",
                    "frame imageProxy=${image.width}x${image.height} rot=$rotation rotFrame=${frameWRotI}x${frameHRotI}"
                )
            }
val riderSpeedRawMps = speedProvider.getCurrent().speedMps
            val riderSpeedMps = smoothRiderSpeed(riderSpeedRawMps)

// Frame quality assessment (fast heuristics on Y plane).
            val quality = assessFrameQuality(image)
            val qualityWeight: Float = run {
                val q = quality.weight.coerceIn(0f, 1f)
                // Drop fast (bad frames matter immediately), recover slower (avoid flicker).
                val a = if (!qualityWeightEmaValid) 1f else if (q < qualityWeightEma) 0.35f else 0.10f
                if (!qualityWeightEmaValid) {
                    qualityWeightEma = q
                    qualityWeightEmaValid = true
                } else {
                    qualityWeightEma += a * (q - qualityWeightEma)
                }
                qualityWeightEma.coerceIn(0f, 1f)
            }
            // Keep the legacy boolean for logging/debug, derived from the smoothed weight.
            val qualityPoor = AppPreferences.qualityGatingEnabled && (qualityWeight < 0.75f)
if (AppPreferences.debugOverlay) {
    flog("quality poor=${quality.poor} luma=%.1f grad=%.2f".format(quality.meanLuma, quality.meanGrad))
}


            // Run detector on ROI crop for performance + to avoid picking dashboard/edges.
            val tPreNs = SystemClock.elapsedRealtimeNanos()
            val rawDetections = when (AppPreferences.selectedModel) {
                0 -> yolo?.detect(image, roiRect, rotation).orEmpty()
                1 -> det?.detect(image, roiRect, rotation).orEmpty()
                else -> emptyList()
            }
            val tInferNs = SystemClock.elapsedRealtimeNanos()

            // ROI jako weight (MCAW 2.0): nehard-gate. Jen vyhoď úplně mimo ROI (0 area intersection),
            // aby se nezvyšoval šum mimo jízdní prostor.
            val softRoiFiltered = rawDetections.filter { d ->
                containmentRatioInTrapezoid(d.box, roiTrap.pts) > 0.00f
            }

            val tPostNs = SystemClock.elapsedRealtimeNanos()
            val post = postProcessor.process(softRoiFiltered, frameW, frameH)
            flog("counts raw=${post.counts.raw} thr=${post.counts.threshold} nms=${post.counts.nms} accepted=${post.counts.filters} (roiSoft=${softRoiFiltered.size})")

            if (AppPreferences.debugOverlay) {
                flog(
                    "roi trap n=${AppPreferences.getRoiTrapezoidNormalized()} boundsPx=[${roiTrap.bounds.left.toInt()},${roiTrap.bounds.top.toInt()},${roiTrap.bounds.right.toInt()},${roiTrap.bounds.bottom.toInt()}] " +
                        "raw=${rawDetections.size} roiSoft=${softRoiFiltered.size}"
                )
            }

            val tracked = tracker.update(post.accepted)
            val bestTrack = selectLockedTarget(tracked, frameW, frameH, roiTrap, tsMs)

            if (bestTrack == null) {
                lockedTrackId = null
                lockedSinceMs = 0L
                switchCandidateId = null
                switchCandidateCount = 0
                lastAlertLevel = 0
                AlertNotifier.stopInApp(ctx)

                // DEBUG trace: lock cleared
                traceLogger?.logTarget(
                    tsMs = tsMs,
                    kind = 1,
                    lockedId = -1L,
                    bestId = -1L,
                    bestPri = 0f,
                    lockedPri = 0f,
                    candId = -1L,
                    candCount = 0,
                    alertLevel = lastAlertLevel,
                    mode = AppPreferences.detectionMode
                )

                resetMotionState()
                sendOverlayClear()
                sendMetricsClear()
                return
            }

            // Reset history on target change (prevents TTC/REL jumps when switching trackId)
            if (lastSelectedTrackId != bestTrack.id) {
                lastSelectedTrackId = bestTrack.id
                resetMotionState(bestTrack.id)
            }

            val best0 = bestTrack.detection
            val bestBox = clampBox(best0.box, frameW, frameH)
            updateCutInState(tsMs, bestBox, frameW, frameH)
            val label = DetectionLabelMapper.toCanonical(best0.label) ?: (best0.label ?: "unknown")

            // --- ROI bottom handling ---
            // Detector runs on ROI crop, therefore any object parts below ROI.bottom are invisible.
            // Never interpret a bbox that is "touching" ROI bottom as having a reliable bottom contact point.
            val roiNNow = AppPreferences.getRoiTrapezoidNormalized()
            val roiBottomPx = (roiNNow.bottomY.coerceIn(0f, 1f) * frameH).coerceIn(0f, frameH)
            val roiTouchEpsPx = 8f
            val bboxTouchesRoiBottom = (roiBottomPx - bestBox.y2) <= roiTouchEpsPx

            
// Distance estimation: blend bbox-height monocular + ground-plane (height + pitch) for robustness.
val focalPx = estimateFocalLengthPx(frameHRotI)
val distFromHeight = DetectionPhysics.estimateDistanceMeters(
    bbox = bestBox,
    frameHeightPx = frameHRotI,
    focalPx = focalPx,
    realHeightM = if (label == "motorcycle" || label == "bicycle") 1.3f else 1.5f
)

// Ground-plane is NOT reliable when bbox bottom is clamped to ROI bottom.
val distFromGround = if (!bboxTouchesRoiBottom) {
    DetectionPhysics.estimateDistanceGroundPlaneMeters(
        bbox = bestBox,
        frameHeightPx = frameHRotI,
        focalPx = focalPx,
        camHeightM = AppPreferences.cameraMountHeightM,
        pitchDownDeg = AppPreferences.cameraPitchDownDeg
    )
} else {
    null
}

// Weight ground estimate more when bbox bottom is near the bottom of frame (likely on road).
val yBottomN = (bestBox.y2 / frameH).coerceIn(0f, 1f)
val wGround = ((yBottomN - 0.65f) / 0.35f).coerceIn(0f, 1f)
val distanceRaw = when {
    distFromHeight != null && distFromGround != null -> (distFromGround * wGround) + (distFromHeight * (1f - wGround))
    distFromGround != null -> distFromGround
    else -> distFromHeight
}

val distanceScaled =
    if (distanceRaw != null && distanceRaw.isFinite()) distanceRaw * AppPreferences.distanceScale else distanceRaw


            // If bbox is clamped to ROI bottom, distance/ttc signals get distorted.
            // Freeze distance briefly (use last EMA) instead of ingesting biased samples.
            val distanceInput = if (bboxTouchesRoiBottom && distEmaValid && distEma.isFinite()) distEma else (distanceScaled ?: Float.NaN)
            val distanceM = smoothDistance(distanceInput)

            // REL speed (signed internal) from sliding-window + EMA
            val relSpeedSigned = computeRelativeSpeedSignedWindow(bestTrack.id, distanceM, tsMs)

            // Brake cue (beta): jen pokud je zapnuté a jezdec jede
            val brakeCue = if (AppPreferences.brakeCueEnabled) {
                computeBrakeCue(
                    tsMs = tsMs,
                    image = image,
                    rotationDegrees = rotation,
                    box = bestBox,
                    label = label,
                    riderSpeedMps = riderSpeedMps,
                    relSpeedSigned = relSpeedSigned
                )
            } else {
                BrakeCueResult(false, 0f, 0f, 0f)
            }

            val approachSpeedFromDist = relSpeedSigned.coerceAtLeast(0f)

            // TTC from bbox growth tends to be more stable than distance derivative
            val boxHPx = (bestBox.y2 - bestBox.y1).coerceAtLeast(0f)
            // If bbox touches ROI bottom, height growth is biased (partial occlusion).
            // Don't update the height-derived TTC in this case; rely on hold-last / dist TTC.
            val ttcFromHeightsNow = if (!bboxTouchesRoiBottom) computeTtcFromBoxHeights(boxHPx, tsMs) else null

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
                    (ttcFromHeightsHeld * 0.85f) + (ttcFromDist * 0.15f)
                }
                ttcFromHeightsHeld != null && ttcFromHeightsHeld.isFinite() -> ttcFromHeightsHeld
                else -> ttcFromDist
            }

            val ttc = smoothTtc(ttcRaw, tsMs)

            // TTC slope (sec/sec). Robust + EMA to avoid noise.
            val ttcSlopeSecPerSec: Float = run {
                val prevTtc = lastTtcForSlope
                val prevTs = lastTtcForSlopeTsMs
                lastTtcForSlope = ttc
                lastTtcForSlopeTsMs = tsMs

                if (!ttc.isFinite() || !prevTtc.isFinite() || prevTs <= 0L) {
                    // reset / unknown
                    ttcSlopeEmaValid = false
                    0f
                } else {
                    val dtSec = ((tsMs - prevTs).coerceAtLeast(1L)).toFloat() / 1000f
                    // Clamp to limit single-frame spikes
                    val raw = ((ttc - prevTtc) / dtSec).coerceIn(-5f, 5f)
                    val a = 0.25f
                    if (!ttcSlopeEmaValid) {
                        ttcSlopeEma = raw
                        ttcSlopeEmaValid = true
                    } else {
                        ttcSlopeEma += a * (raw - ttcSlopeEma)
                    }
                    ttcSlopeEma
                }
            }

            // Approach speed used for alerting (derived from final smoothed TTC if available)
            val approachSpeedMps =
                if (ttc.isFinite() && ttc > 0f && distanceM.isFinite()) (distanceM / ttc).coerceIn(0f, 80f)
                else approachSpeedFromDist

            // OBJ = rider - relSigned (relSigned = rider - object)
            val objectSpeedMps =
                if (riderSpeedMps.isFinite()) (riderSpeedMps - relSpeedSigned) else Float.POSITIVE_INFINITY

            val modeRes = autoModeSwitcher.resolve(AppPreferences.detectionMode, riderSpeedMps, lastAlertLevel, tsMs)
            if (modeRes.changed) {
                flog("auto_mode effective=${'$'}{modeName(modeRes.effectiveMode)} reason=${'$'}{modeRes.reason}", force = true)
            }
            val riderSpeedKnown = riderSpeedMps.isFinite()
val riderStanding = riderSpeedKnown && riderSpeedMps <= (6.0f / 3.6f) // < 6 km/h (město/kolony)

// IMU: ego brzdění + náklon (volitelně; když není validní -> NaN/0)
val imu = imuMonitor.snapshot(tsMs)

// ROI weight (0..1) pro RiskEngine
val roiContainment = containmentRatioInTrapezoid(bestBox, roiTrap.pts).coerceIn(0f, 1f)
val egoOffset = egoOffsetInRoiN(bestBox, frameW, frameH).coerceIn(0f, 2f)

val risk = if (riderStanding) {
    riskEngine.standingResult(riderSpeedMps)
} else {
    riskEngine.evaluate(
        tsMs = tsMs,
        effectiveMode = modeRes.effectiveMode,
        distanceM = distanceM,
        approachSpeedMps = approachSpeedMps,
        ttcSec = ttc,
        ttcSlopeSecPerSec = ttcSlopeSecPerSec,
        roiContainment = roiContainment,
        egoOffsetN = egoOffset,
        cutInActive = (cutInBoostUntilMs > 0L && tsMs <= cutInBoostUntilMs),
        brakeCueActive = brakeCue.active,
        brakeCueStrength = brakeCue.strength,
	        qualityWeight = qualityWeight,
        riderSpeedMps = riderSpeedMps,
        egoBrakingConfidence = imu.brakeConfidence,
        leanDeg = imu.leanDeg
    )
}

val prevLevel = lastAlertLevel
val level = risk.level
val reasonBits = risk.reasonBits
val alertReason = RiskEngine.formatReasonShort(reasonBits)
lastAlertLevel = level

if (riderStanding) {
    AlertNotifier.stopInApp(ctx)
} else {
    AlertNotifier.handleInApp(ctx, level, risk)
}

// ALWAYS-ON event log (sampled + transitions), written off-thread.
if (level != prevLevel || frameIndex % eventEveryNFrames == 0L) {
    val lockedId = lockedTrackId ?: -1L
    eventLogger.logEvent(
        tsMs = tsMs,
        risk = risk.riskScore,
        level = level,
        state = risk.state,
        reasonBits = reasonBits,
        ttcSec = ttc,
        distM = distanceM,
        relV = approachSpeedMps,
        roi = roiContainment,
	        qualityPoor = qualityPoor,
        cutIn = (cutInBoostUntilMs > 0L && tsMs <= cutInBoostUntilMs),
        brake = brakeCue.active,
        egoBrake = imu.brakeConfidence,
        mode = modeRes.effectiveMode,
        lockedId = lockedId,
        label = label,
        detScore = best0.score
    )
}

// Log why alert level was decided (debugOverlay only; sampled)
if (AppPreferences.debugOverlay && (level != prevLevel || frameIndex % logEveryNFrames == 0L)) {
    flog("risk level=$level score=%.2f state=${'$'}{risk.state} bits=$reasonBits reason=$alertReason".format(risk.riskScore), force = (level != prevLevel))
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
                label = label,
                brakeCue = brakeCue.active,
                alertLevel = lastAlertLevel,
                alertReason = alertReason,
                reasonBits = reasonBits,
                riskScore = risk.riskScore,
                roiMinDistM = DetectionPhysics.estimateDistanceGroundPlaneMetersAtYPx(
                    yBottomPx = roiBottomPx,
                    frameHeightPx = frameHRotI,
                    focalPx = focalPx,
                    camHeightM = AppPreferences.cameraMountHeightM,
                    pitchDownDeg = AppPreferences.cameraPitchDownDeg
                ) ?: Float.NaN,
                roiBottomTouch = bboxTouchesRoiBottom
            )

            flog(
                "best id=${bestTrack.id} label=$label score=${best0.score} " +
                    "box=${bestBox.x1},${bestBox.y1},${bestBox.x2},${bestBox.y2} " +
                    "distRaw=${distanceRaw ?: Float.NaN} dist=$distanceM " +
                    "relSigned=$relSpeedSigned relApp=$approachSpeedFromDist " +
                    "riderRaw=$riderSpeedRawMps rider=$riderSpeedMps obj=$objectSpeedMps " +
                    "ttcH=${ttcFromHeightsHeld ?: Float.NaN} ttcD=$ttcFromDist ttc=$ttc"
            )

            sendMetricsUpdate(
                dist = distanceM,
                approachSpeed = approachSpeedMps,
                objectSpeed = objectSpeedMps,
                riderSpeed = riderSpeedMps,
                ttc = ttc,
                level = level,
                label = label,
                brakeCue = brakeCue.active,
                alertReason = alertReason,
                reasonBits = reasonBits,
                riskScore = risk.riskScore
            )

            
            // PERF aggregation (debugOverlay only)
            if (AppPreferences.debugOverlay) {
                val tEndNs = SystemClock.elapsedRealtimeNanos()
                val preMs = ((tPreNs - t0Ns) / 1_000_000L).coerceAtLeast(0L)
                val inferMs = ((tInferNs - tPreNs) / 1_000_000L).coerceAtLeast(0L)
                val postMs = ((tEndNs - tPostNs) / 1_000_000L).coerceAtLeast(0L)
                val totalMs = ((tEndNs - t0Ns) / 1_000_000L).coerceAtLeast(0L)

                perf.frames += 1
                perf.preprocMs += preMs
                perf.inferMs += inferMs
                perf.postMs += postMs
                perf.totalMs += totalMs

                if (perf.frames % logEveryNFrames == 0L) {
                    val rt = Runtime.getRuntime()
                    val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                    val totalMb = rt.totalMemory() / (1024 * 1024)
                    flog(
                        "perf avgMs pre=${perf.preprocMs / perf.frames} infer=${perf.inferMs / perf.frames} post=${perf.postMs / perf.frames} total=${perf.totalMs / perf.frames} mem=${usedMb}MB/${totalMb}MB",
                        force = true
                    )
                }
            }

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

    /** Release non-camera resources held by analyzer (IMU, MediaPlayer/TTS, etc.). */
    fun shutdown() {
        runCatching { imuMonitor.stop() }
        runCatching { AlertNotifier.shutdown(ctx) }
        runCatching { traceLogger?.close() }
        runCatching { eventLogger.close() }
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

    private fun sendOverlayUpdate(
        box: Box,
        frameW: Float,
        frameH: Float,
        dist: Float,
        approachSpeed: Float,
        objectSpeed: Float,
        riderSpeed: Float,
        ttc: Float,
        label: String,
        brakeCue: Boolean,
        alertLevel: Int,
        alertReason: String = "",
        reasonBits: Int = 0,
        riskScore: Float = Float.NaN,
        roiMinDistM: Float = Float.NaN,
        roiBottomTouch: Boolean = false,
        force: Boolean = false
    ) {
        if (!AppPreferences.debugOverlay) return

        val now = SystemClock.elapsedRealtime()
        val shouldSend = force || (now - lastOverlaySentElapsedMs >= overlayIntervalMs) || (alertLevel != lastOverlaySentAlertLevel)
        if (!shouldSend) return
        lastOverlaySentElapsedMs = now
        lastOverlaySentAlertLevel = alertLevel

        val roiN = AppPreferences.getRoiTrapezoidNormalized()
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
        i.putExtra("brake_cue", brakeCue)
        i.putExtra("alert_level", alertLevel)
        i.putExtra("alert_reason", alertReason)
        i.putExtra("reason_bits", reasonBits)
        i.putExtra("risk_score", riskScore)

        // ROI diagnostics (helps validate that ROI bottom starts e.g. 2-3m ahead due to dashboard crop).
        i.putExtra("roi_min_dist_m", roiMinDistM)
        i.putExtra("roi_bottom_touch", roiBottomTouch)

        // keep ROI always in preview overlay
        i.putExtra("roi_trap_top_y_n", roiN.topY)
        i.putExtra("roi_trap_bottom_y_n", roiN.bottomY)
        i.putExtra("roi_trap_top_halfw_n", roiN.topHalfW)
        i.putExtra("roi_trap_bottom_halfw_n", roiN.bottomHalfW)
        i.putExtra("roi_trap_center_x_n", roiN.centerX)

        ctx.sendBroadcast(i)
    }

    private fun sendOverlayClear() {
        lastTtcLevel = 0
        if (!AppPreferences.debugOverlay) return
        val roiN = AppPreferences.getRoiTrapezoidNormalized()
        val i = Intent("MCAW_DEBUG_UPDATE").setPackage(ctx.packageName)
        i.putExtra("clear", true)
        i.putExtra("risk_score", Float.NaN)
        i.putExtra("roi_trap_top_y_n", roiN.topY)
        i.putExtra("roi_trap_bottom_y_n", roiN.bottomY)
        i.putExtra("roi_trap_top_halfw_n", roiN.topHalfW)
        i.putExtra("roi_trap_bottom_halfw_n", roiN.bottomHalfW)
        i.putExtra("roi_trap_center_x_n", roiN.centerX)
        i.putExtra("roi_min_dist_m", Float.NaN)
        i.putExtra("roi_bottom_touch", false)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsUpdate(
        dist: Float,
        approachSpeed: Float,
        objectSpeed: Float,
        riderSpeed: Float,
        ttc: Float,
        level: Int,
        label: String,
        brakeCue: Boolean,
        alertReason: String = "",
        reasonBits: Int = 0,
        riskScore: Float = Float.NaN,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        val shouldSend = force || (now - lastMetricsSentElapsedMs >= metricsIntervalMs) || (level != lastMetricsSentAlertLevel)
        if (!shouldSend) return
        lastMetricsSentElapsedMs = now
        lastMetricsSentAlertLevel = level

        val i = Intent(ACTION_METRICS_UPDATE).setPackage(ctx.packageName)
        i.putExtra(EXTRA_DISTANCE, dist)
        i.putExtra(EXTRA_SPEED, approachSpeed)
        i.putExtra(EXTRA_OBJECT_SPEED, objectSpeed)
        i.putExtra(EXTRA_RIDER_SPEED, riderSpeed)
        i.putExtra(EXTRA_TTC, ttc)
        i.putExtra(EXTRA_LEVEL, level)
        i.putExtra(EXTRA_LABEL, label)
        i.putExtra(EXTRA_BRAKE_CUE, brakeCue)
        i.putExtra(EXTRA_ALERT_REASON, alertReason)
        i.putExtra(EXTRA_REASON_BITS, reasonBits)
        i.putExtra(EXTRA_RISK_SCORE, riskScore)
        ctx.sendBroadcast(i)
    }

    private fun sendMetricsClear() {
        lastTtcLevel = 0
        sendMetricsUpdate(
            dist = Float.POSITIVE_INFINITY,
            approachSpeed = Float.POSITIVE_INFINITY,
            objectSpeed = Float.POSITIVE_INFINITY,
            riderSpeed = Float.POSITIVE_INFINITY,
            ttc = Float.POSITIVE_INFINITY,
            level = 0,
            label = "",
            brakeCue = false,
            alertReason = "clear",
            reasonBits = 0,
            riskScore = Float.NaN,
            force = true
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

            else -> AlertThresholds(3.0f, 1.2f, 15f, 8f, 3f, 5f)
        }
    }





private data class AlertDecision(
    val level: Int,
    val reason: String
)

/**
 * Explain alert decision for debugging / tuning.
 * Keeps behavior consistent with [alertLevel] (same thresholds + hysteresis).
 */
private fun computeAlertDecision(
        distanceM: Float,
        approachSpeedMps: Float,
        ttc: Float,
        thresholds: AlertThresholds,
        qualityPoor: Boolean
    ): AlertDecision {

        // Quality gating: in low-light / blur, avoid noisy alerts (city lights, bumps).
        // Conservative mode:
        // - suppress ORANGE completely
        // - allow RED only if clearly critical (stricter thresholds)
        val conservative = AppPreferences.qualityGatingEnabled && qualityPoor

    val ttcLevel = ttcLevelWithHysteresis(ttc, thresholds)

    val distRed = distanceM.isFinite() && distanceM <= thresholds.distRed
    val distOrange = distanceM.isFinite() && distanceM <= thresholds.distOrange

    val spdRed = approachSpeedMps.isFinite() && approachSpeedMps >= thresholds.speedRed
    val spdOrange = approachSpeedMps.isFinite() && approachSpeedMps >= thresholds.speedOrange

    val level = when {
        distRed || spdRed || ttcLevel == 2 -> 2
        distOrange || spdOrange || ttcLevel == 1 -> 1
        else -> 0
    }

val effectiveLevel = if (conservative) {
    // Suppress ORANGE; RED only when truly critical (stricter by 20%).
    val strongRed = (ttc.isFinite() && ttc <= (thresholds.ttcRed * 0.80f)) ||
        (distanceM.isFinite() && distanceM <= (thresholds.distRed * 0.80f)) ||
        (approachSpeedMps.isFinite() && approachSpeedMps >= (thresholds.speedRed * 1.10f))
    if (strongRed) 2 else 0
} else {
    level
}

    // Reason (priority: TTC > DIST > REL), include current values + thresholds for quick tuning.
    val reasonCore = when (effectiveLevel) {
        2 -> when {
            ttcLevel == 2 -> "TTC<=RED (ttc=%.2fs thr=%.2fs)".format(ttc, thresholds.ttcRed)
            distRed -> "DIST<=RED (d=%.2fm thr=%.2fm)".format(distanceM, thresholds.distRed)
            spdRed -> "REL>=RED (rel=%.2fm/s thr=%.2fm/s)".format(approachSpeedMps, thresholds.speedRed)
            else -> "RED (unknown trigger)"
        }
        1 -> when {
            ttcLevel == 1 -> "TTC<=ORANGE (ttc=%.2fs thr=%.2fs)".format(ttc, thresholds.ttcOrange)
            distOrange -> "DIST<=ORANGE (d=%.2fm thr=%.2fm)".format(distanceM, thresholds.distOrange)
            spdOrange -> "REL>=ORANGE (rel=%.2fm/s thr=%.2fm/s)".format(approachSpeedMps, thresholds.speedOrange)
            else -> "ORANGE (unknown trigger)"
        }
        else -> "SAFE"
    }

    val summary = buildString {
        append(reasonCore)
        append(" | ")
        append("ttc="); append(if (ttc.isFinite()) "%.2f".format(ttc) else "INF")
        append(" d="); append(if (distanceM.isFinite()) "%.2f".format(distanceM) else "INF")
        append(" rel="); append(if (approachSpeedMps.isFinite()) "%.2f".format(approachSpeedMps) else "INF")
    }

    return AlertDecision(level = effectiveLevel, reason = summary + if (conservative) " | QCONSERV" else "")
}

private fun ttcLevelWithHysteresis(ttc: Float, t: AlertThresholds): Int {
    if (!ttc.isFinite()) {
        lastTtcLevel = 0
        return 0
    }

    val redOn = t.ttcRed
    val redOff = redOn + 0.6f // např. 1.2 -> 1.8
    val orangeOn = t.ttcOrange
    val orangeOff = maxOf(orangeOn + 0.9f, redOff + 0.2f)

    lastTtcLevel = when (lastTtcLevel) {
        2 -> {
            if (ttc >= redOff) {
                if (ttc <= orangeOn) 1 else 0
            } else 2
        }
        1 -> {
            when {
                ttc <= redOn -> 2
                ttc >= orangeOff -> 0
                else -> 1
            }
        }
        else -> {
            when {
                ttc <= redOn -> 2
                ttc <= orangeOn -> 1
                else -> 0
            }
        }
    }
    return lastTtcLevel
}

private fun alertLevel(distance: Float, approachSpeedMps: Float, ttc: Float, t: AlertThresholds): Int {

val ttcLevel = ttcLevelWithHysteresis(ttc, t)

val redDs =
    (distance.isFinite() && distance <= t.distRed) ||
        (approachSpeedMps.isFinite() && approachSpeedMps >= t.speedRed)
if (redDs || ttcLevel == 2) return 2

val orangeDs =
    (distance.isFinite() && distance <= t.distOrange) ||
        (approachSpeedMps.isFinite() && approachSpeedMps >= t.speedOrange)

return if (orangeDs || ttcLevel == 1) 1 else 0
    }

    private fun resetMotionState(newTrackId: Long? = null) {
        // Per-track motion history (REL from distance)
        if (newTrackId != null) {
            motionByTrack[newTrackId]?.distHistory?.clear()
            motionByTrack[newTrackId]?.relSpeedEmaValid = false
            motionByTrack[newTrackId]?.relSpeedEma = 0f
            motionByTrack[newTrackId]?.lastRelSigned = 0f
            motionByTrack[newTrackId]?.lastRelTsMs = 0L
            activeMotionTrackId = newTrackId
        } else {
            motionByTrack.clear()
            activeMotionTrackId = -1L
        }

        distEmaValid = false
        distEma = Float.NaN

        lastBoxHeightPx = Float.NaN
        lastBoxHeightTimestampMs = -1L

        brakeRedRatioEma = 0f
        brakeIntensityEma = 0f
        brakePrevIntensityEma = 0f
        brakeCueActiveFrames = 0
        brakeCueLastActiveMs = -1L
        brakeCueActive = false
        brakeCueStrength = 0f

        ttcEmaValid = false
        ttcEma = Float.POSITIVE_INFINITY
        lastTtcUpdateTsMs = -1L
        lastTtcFiniteTsMs = -1L
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
            return Float.NaN
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

        // If TTC becomes invalid, hold the last finite value briefly to avoid UI blinking.
        if (!raw.isFinite()) {
            if (ttcEmaValid && ttcEma.isFinite() && lastTtcFiniteTsMs > 0L && (tsMs - lastTtcFiniteTsMs) <= ttcInvalidHoldMs) {
                return ttcEma
            }
            ttcEmaValid = false
            ttcEma = Float.POSITIVE_INFINITY
            lastTtcUpdateTsMs = tsMs
            return Float.POSITIVE_INFINITY
        }

        // Remember last finite TTC timestamp
        lastTtcFiniteTsMs = tsMs

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
            minGrowthRatio = 1.02f,
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


    private fun egoOffsetInRoiN(box: Box, frameW: Float, frameH: Float): Float {
        if (frameW <= 0f || frameH <= 0f) return 1f
        val roi = AppPreferences.getRoiTrapezoidNormalized()
        val cxN = (box.cx / frameW).coerceIn(0f, 1f)
        val yBottomN = (box.y2 / frameH).coerceIn(0f, 1f)

        // If bottom is outside ROI vertical span, treat as outside.
        if (yBottomN < roi.topY || yBottomN > roi.bottomY) return 1f

        // Linear interpolate half-width between top and bottom at given y.
        val t = ((yBottomN - roi.topY) / (roi.bottomY - roi.topY)).coerceIn(0f, 1f)
        val halfW = (roi.topHalfW + (roi.bottomHalfW - roi.topHalfW) * t).coerceAtLeast(0.001f)

        val off = kotlin.math.abs(cxN - roi.centerX) / halfW
        // Normalize by maxOffset so priority can use it too if needed.
        return off.coerceIn(0f, 2f)
    }

    private fun selectLockedTarget(
        tracks: List<TemporalTracker.TrackedDetection>,
        frameW: Float,
        frameH: Float,
        _roiTrap: RoiTrapPx,
        tsMs: Long
    ): TemporalTracker.TrackedDetection? {
        if (tracks.isEmpty() || frameW <= 0f || frameH <= 0f) return null

        val gated = tracks.filter { it.alertGatePassed && it.misses == 0 }
        val pool = if (gated.isNotEmpty()) gated else tracks.filter { it.misses == 0 }
        if (pool.isEmpty()) return null

        // Optional ego-path gating inside ROI trapezoid (reduces side-object false positives in city).
        // Keeps behavior backward-compatible: if gating removes everything, fallback to original pool.
        val poolEgo = if (AppPreferences.laneFilter) {
            val maxOff = dynamicEgoMaxOffset(tsMs)
            val filtered = pool.filter { t ->
                egoOffsetInRoiN(t.detection.box, frameW, frameH) <= maxOff
            }
            if (filtered.isNotEmpty()) filtered else pool
        } else {
            pool
        }


        val roiN = AppPreferences.getRoiTrapezoidNormalized()
        val roiCenterX = roiN.centerX

        

        val focalPxForSel = estimateFocalLengthPx(frameH.toInt())
fun priority(t: TemporalTracker.TrackedDetection): Float {
    val d = t.detection
    val b = d.box
    val cxN = (b.cx / frameW).coerceIn(0f, 1f)
    val dx = abs(cxN - roiCenterX)

    // X-center alignment dominates: user ROI is aligned to lane/forward direction.
    val centerScore = (1f - (dx * 1.8f)).coerceIn(0f, 1f)
    val score = d.score.coerceIn(0f, 1f)

    // Distance proxy for prioritization only (do NOT treat as ground-truth long-range meters).
    // Use the monocular bbox-height estimate which is cheap and stable enough for ranking.
    val distM = DetectionPhysics.estimateDistanceMeters(
        bbox = b,
        frameHeightPx = frameH.toInt(),
        focalPx = focalPxForSel,
        realHeightM = 1.5f
    )
    // Convert distance to "closer is better" score. Clamp at 40m: beyond that, treat as equally far.
    val distanceScore = if (distM == null || !distM.isFinite() || distM <= 0f) {
        0f
    } else {
        (1f - (distM / 40f).coerceIn(0f, 1f))
    }

    // Stronger ROI influence for target selection (still soft; never a hard gate).
    // Rationale: user moves ROI above dashboard and aligns it with their lane.
    val roiContain = containmentRatioInTrapezoid(b, _roiTrap.pts).coerceIn(0f, 1f)
    val roiWeight = ((0.10f + 0.90f * roiContain).toDouble().pow(1.8)).toFloat()

    val egoScore = if (AppPreferences.laneFilter) {
        val maxOff = dynamicEgoMaxOffset(tsMs)
        val off = egoOffsetInRoiN(b, frameW, frameH)
        (1f - (off / maxOff)).coerceIn(0f, 1f)
    } else {
        0.5f
    }

    // Selection base: center + distance dominate, then model confidence + ego-path.
    // (Area is intentionally NOT a primary driver because ROI/occlusion can distort apparent size.)
    val base = (centerScore * 0.45f) + (distanceScore * 0.35f) + (score * 0.10f) + (egoScore * 0.10f)
    return (base * roiWeight).coerceIn(0f, 1f)
}

        val bestNow = poolEgo.maxByOrNull { priority(it) } ?: return null
        val bestNowPrio = priority(bestNow)

        val lockedId = lockedTrackId
        val locked = if (lockedId != null) poolEgo.firstOrNull { it.id == lockedId } else null

        if (locked == null) {
            lockedTrackId = bestNow.id
            lockedPriority = bestNowPrio
            lockedSinceMs = tsMs
            switchCandidateId = null
            switchCandidateCount = 0
            // DEBUG trace: lock changed
            traceLogger?.logTarget(
                tsMs = tsMs,
                kind = 1,
                lockedId = bestNow.id,
                bestId = bestNow.id,
                bestPri = bestNowPrio,
                lockedPri = bestNowPrio,
                candId = (switchCandidateId ?: -1L),
                candCount = switchCandidateCount,
                alertLevel = lastAlertLevel,
                mode = AppPreferences.detectionMode
            )
            return bestNow
        }

        val lockedPrio = priority(locked)
        lockedPriority = lockedPrio

        // Hard stickiness: keep current lock for a short time to avoid rapid switching.
        val minLockMs = if (lastAlertLevel > 0) 900L else 600L
        if (lockedSinceMs > 0L && (tsMs - lockedSinceMs) < minLockMs) {
            return locked
        }

        // Switching is harder during active alerts to keep TTC/WHY stable.
        val switchMargin = if (lastAlertLevel > 0) 1.85f else 1.45f
        val minAbsGain = if (lastAlertLevel > 0) 0.14f else 0.10f
        val framesNeeded = if (lastAlertLevel > 0) 8 else 5

        val eligibleSwitch =
            bestNow.id != locked.id &&
                (bestNowPrio >= lockedPrio * switchMargin || (bestNowPrio - lockedPrio) >= minAbsGain)

        if (eligibleSwitch) {
            if (switchCandidateId == bestNow.id) {
                switchCandidateCount += 1
            } else {
                switchCandidateId = bestNow.id
                switchCandidateCount = 1
            }

            if (switchCandidateCount >= framesNeeded) {
                lockedTrackId = bestNow.id
                lockedPriority = bestNowPrio
                lockedSinceMs = tsMs
                switchCandidateId = null
                switchCandidateCount = 0
            // DEBUG trace: lock changed
            traceLogger?.logTarget(
                tsMs = tsMs,
                kind = 1,
                lockedId = bestNow.id,
                bestId = bestNow.id,
                bestPri = bestNowPrio,
                lockedPri = bestNowPrio,
                candId = (switchCandidateId ?: -1L),
                candCount = switchCandidateCount,
                alertLevel = lastAlertLevel,
                mode = AppPreferences.detectionMode
            )
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
    private fun computeRelativeSpeedSignedWindow(
        trackId: Long,
        currentDistanceM: Float,
        tsMs: Long
    ): Float {
        val state = motionByTrack.getOrPut(trackId) { MotionState() }

        // Cleanup map to avoid unbounded growth (tracker IDs can drift).
        if (motionByTrack.size > 12) {
            val keepIds = setOf(lockedTrackId, switchCandidateId).filterNotNull().toSet()
            val it = motionByTrack.entries.iterator()
            while (it.hasNext() && motionByTrack.size > 8) {
                val e = it.next()
                if (!keepIds.contains(e.key)) it.remove()
            }
        }

        if (!currentDistanceM.isFinite() || currentDistanceM <= 0f) {
            state.distHistory.clear()
            state.relSpeedEmaValid = false
            state.relSpeedEma = 0f
            state.lastRelSigned = 0f
            state.lastRelTsMs = tsMs
            return 0f
        }

        state.distHistory.addLast(DistSample(tsMs, currentDistanceM))

        // Keep ~1.2s of history max
        val keepMs = 1200L
        while (state.distHistory.isNotEmpty() && tsMs - state.distHistory.first().tsMs > keepMs) {
            state.distHistory.removeFirst()
        }

        // Need at least 2 samples
        if (state.distHistory.size < 2) {
            return if ((tsMs - state.lastRelTsMs) <= 350L) state.lastRelSigned else 0f
        }

        val minAgeMs = 300L
        val maxAgeMs = 900L
        val targetAgeMs = 600L

        val newest = state.distHistory.last()
        val desiredTs = tsMs - targetAgeMs

        // Find sample closest to desiredTs within [minAgeMs, maxAgeMs]
        var chosen: DistSample? = null
        var bestAbs = Long.MAX_VALUE
        for (s in state.distHistory) {
            val age = tsMs - s.tsMs
            if (age < minAgeMs || age > maxAgeMs) continue
            val absd = kotlin.math.abs(s.tsMs - desiredTs)
            if (absd < bestAbs) {
                bestAbs = absd
                chosen = s
            }
        }

        val older = chosen ?: run {
            // Fallback: use the oldest within maxAgeMs
            state.distHistory.firstOrNull { (tsMs - it.tsMs) <= maxAgeMs } ?: state.distHistory.first()
        }

        val dtMs = (newest.tsMs - older.tsMs).coerceAtLeast(1L)
        // Guard: if dt is too small, derivative explodes. Hold last value briefly.
        if (dtMs < 180L) {
            return if ((tsMs - state.lastRelTsMs) <= 350L) state.lastRelSigned else 0f
        }

        val dtSec = dtMs.toFloat() / 1000f
        val relSignedRaw = (older.distM - newest.distM) / dtSec

        // Clamp unrealistic spikes (m/s). This protects TTC/alerts from 1-frame glitches.
        val relSignedClamped = relSignedRaw.coerceIn(-25f, 25f)

        // EMA smoothing
        val alpha = 0.30f
        state.relSpeedEma =
            if (!state.relSpeedEmaValid) relSignedClamped else (state.relSpeedEma + alpha * (relSignedClamped - state.relSpeedEma))
        state.relSpeedEmaValid = true

        // Store last signed rel for short hold
        state.lastRelSigned = state.relSpeedEma
        state.lastRelTsMs = tsMs

        return state.relSpeedEma
    }

        private data class RoiTrapPx(val pts: FloatArray, val bounds: RectF)

    /**
     * ROI trapezoid points in pixel coordinates (clockwise) + its bounding rect (for crop).
     * Points: TL, TR, BR, BL in rotated frame coords.
     */
    private fun roiTrapezoidPx(frameW: Float, frameH: Float): RoiTrapPx {
        val roiN = AppPreferences.getRoiTrapezoidNormalized()
        val cx = 0.5f

        val tlxN = (cx - roiN.topHalfW).coerceIn(0f, 1f)
        val trxN = (cx + roiN.topHalfW).coerceIn(0f, 1f)
        val blxN = (cx - roiN.bottomHalfW).coerceIn(0f, 1f)
        val brxN = (cx + roiN.bottomHalfW).coerceIn(0f, 1f)

        val tyN = roiN.topY.coerceIn(0f, 1f)
        val byN = roiN.bottomY.coerceIn(0f, 1f)

        val pts = floatArrayOf(
            tlxN * frameW, tyN * frameH,
            trxN * frameW, tyN * frameH,
            brxN * frameW, byN * frameH,
            blxN * frameW, byN * frameH
        )

        var minX = pts[0]
        var maxX = pts[0]
        var minY = pts[1]
        var maxY = pts[1]
        for (i in 0 until 4) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
        }

        val bounds = RectF(
            minX.coerceIn(0f, frameW),
            minY.coerceIn(0f, frameH),
            maxX.coerceIn(0f, frameW),
            maxY.coerceIn(0f, frameH)
        )

        // avoid degenerate ROI
        if (bounds.width() < 2f) bounds.right = (bounds.left + 2f).coerceAtMost(frameW)
        if (bounds.height() < 2f) bounds.bottom = (bounds.top + 2f).coerceAtMost(frameH)

        return RoiTrapPx(pts = pts, bounds = bounds)
    }

    /**
     * Returns ratio of bbox area that lies inside trapezoid ROI [0..1].
     * intersectionArea(bbox, trapezoid) / area(bbox)
     *
     * Implemented as convex polygon clipping: start with bbox-rect polygon, clip by each trapezoid edge.
     */
    private fun containmentRatioInTrapezoid(box: Box, trapPts: FloatArray): Float {
        val bx1 = min(box.x1, box.x2)
        val by1 = min(box.y1, box.y2)
        val bx2 = max(box.x1, box.x2)
        val by2 = max(box.y1, box.y2)

        val area = (bx2 - bx1).coerceAtLeast(0f) * (by2 - by1).coerceAtLeast(0f)
        if (area <= 1e-3f) return 0f

        // bbox polygon (clockwise)
        var poly = arrayListOf(
            PointF(bx1, by1),
            PointF(bx2, by1),
            PointF(bx2, by2),
            PointF(bx1, by2)
        )

        // trapezoid edges (clockwise): TL->TR->BR->BL->TL
        val tp = arrayOf(
            PointF(trapPts[0], trapPts[1]),
            PointF(trapPts[2], trapPts[3]),
            PointF(trapPts[4], trapPts[5]),
            PointF(trapPts[6], trapPts[7])
        )

        val sign = polygonSignedArea(tp).let { if (it >= 0f) 1f else -1f }

        for (i in 0 until 4) {
            val a = tp[i]
            val b = tp[(i + 1) % 4]
            poly = clipPolygonAgainstEdge(poly, a, b, sign)
            if (poly.isEmpty()) return 0f
        }

        val interArea = polygonArea(poly)
        return (interArea / area).coerceIn(0f, 1f)
    }

    private fun polygonSignedArea(p: Array<PointF>): Float {
        var s = 0f
        for (i in p.indices) {
            val j = (i + 1) % p.size
            s += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return s * 0.5f
    }

    private fun polygonArea(p: List<PointF>): Float {
        if (p.size < 3) return 0f
        var s = 0f
        for (i in p.indices) {
            val j = (i + 1) % p.size
            s += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return kotlin.math.abs(s) * 0.5f
    }

    private fun clipPolygonAgainstEdge(
        input: ArrayList<PointF>,
        a: PointF,
        b: PointF,
        sign: Float
    ): ArrayList<PointF> {
        if (input.isEmpty()) return arrayListOf()
        val out = arrayListOf<PointF>()

        fun inside(p: PointF): Boolean {
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            return sign * cross >= 0f
        }

        fun intersection(p1: PointF, p2: PointF): PointF {
            // Line p1->p2 with line a->b
            val x1 = p1.x; val y1 = p1.y
            val x2 = p2.x; val y2 = p2.y
            val x3 = a.x; val y3 = a.y
            val x4 = b.x; val y4 = b.y

            val den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
            if (kotlin.math.abs(den) < 1e-6f) return p2

            val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / den
            val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / den
            return PointF(px, py)
        }

        var prev = input.last()
        var prevInside = inside(prev)

        for (curr in input) {
            val currInside = inside(curr)
            if (currInside) {
                if (!prevInside) {
                    out.add(intersection(prev, curr))
                }
                out.add(curr)
            } else if (prevInside) {
                out.add(intersection(prev, curr))
            }
            prev = curr
            prevInside = currInside
        }
        return out
    }


    
    private fun estimateFocalLengthPx(frameHeightPx: Int): Float {
        val focalMm = AppPreferences.cameraFocalLengthMm
        val sensorHeightMm = AppPreferences.cameraSensorHeightMm
        if (focalMm.isFinite() && sensorHeightMm.isFinite() && sensorHeightMm > 0f) {
            return (focalMm / sensorHeightMm) * frameHeightPx
        }
        return 1000f
    }

    
    private data class BrakeCueResult(
        val active: Boolean,
        val strength: Float,
        val redRatio: Float,
        val intensityDelta: Float
    )

    private fun brakeCueParams(): Pair<Float, Float> {
        // NOTE: redRatio is usually small (tail lights are a small area). Keep thresholds realistic.
        // deltaThr is normalized red intensity delta (0..1).
        return when (AppPreferences.brakeCueSensitivity) {
            0 -> 0.35f to 0.030f // low sensitivity (fewer false positives)
            2 -> 0.22f to 0.018f // high sensitivity (catch brief brake taps)
            else -> 0.28f to 0.025f // standard
        }
    }

    private fun computeBrakeCue(
        tsMs: Long,
        image: ImageProxy,
        rotationDegrees: Int,
        box: Box,
        label: String,
        riderSpeedMps: Float,
        relSpeedSigned: Float
    ): BrakeCueResult {
        // Gate: stojíš => vypnout
        val minRideMps = 5f / 3.6f
        if (!riderSpeedMps.isFinite() || riderSpeedMps < minRideMps) {
            brakeCueActiveFrames = 0
            brakeCueActive = false
            brakeCueStrength = 0f
            return BrakeCueResult(false, 0f, 0f, 0f)
        }
        // Gate: objekt se nevzdaluje / musíme se přibližovat (jinak to pro alerting nemá smysl)
        if (!relSpeedSigned.isFinite() || relSpeedSigned <= 0.25f) {
            // necháme krátký hold, aby to neblikalo při šumu
            val holdMs = 400L
            val stillOn = brakeCueLastActiveMs > 0L && (tsMs - brakeCueLastActiveMs) <= holdMs
            brakeCueActive = stillOn
            brakeCueStrength = if (stillOn) brakeCueStrength else 0f
            return BrakeCueResult(brakeCueActive, brakeCueStrength, brakeRedRatioEma, 0f)
        }

        val supported =
            label == "car" || label == "truck" || label == "bus" || label == "motorcycle" || label == "bicycle"
        if (!supported) {
            brakeCueActiveFrames = 0
            brakeCueActive = false
            brakeCueStrength = 0f
            return BrakeCueResult(false, 0f, 0f, 0f)
        }

        val sample = sampleBrakeLightSignal(image, rotationDegrees, box)
        val redRatio = sample.first
        val intensity = sample.second

        // EMA
        val a = 0.35f
        brakeRedRatioEma = brakeRedRatioEma + a * (redRatio - brakeRedRatioEma)
        brakePrevIntensityEma = brakeIntensityEma
        brakeIntensityEma = brakeIntensityEma + a * (intensity - brakeIntensityEma)

        val delta = (brakeIntensityEma - brakePrevIntensityEma).coerceAtLeast(0f)

        val (thr, deltaThr) = brakeCueParams()

        // Strength: kombinuje "kolik červené" a "jak rychle to vyrostlo"
        val ratioScore = ((brakeRedRatioEma - (thr - 0.15f)) / 0.25f).coerceIn(0f, 1f)
        val deltaScore = ((delta - (deltaThr * 0.5f)) / (deltaThr)).coerceIn(0f, 1f)
        val strength = (ratioScore * 0.45f + deltaScore * 0.55f).coerceIn(0f, 1f)

        val isOn =
            (brakeRedRatioEma >= thr && strength >= 0.55f) ||
                (delta >= (deltaThr * 1.15f) && brakeRedRatioEma >= (thr - 0.05f))

        if (isOn) {
            brakeCueActiveFrames += 1
            brakeCueLastActiveMs = tsMs
        } else {
            brakeCueActiveFrames = 0.coerceAtLeast(brakeCueActiveFrames - 1)
        }

        // Stabilizace: chceme 2 po sobě jdoucí framy ON, pak držet ještě 400ms
        brakeCueActive = if (brakeCueActiveFrames >= 2) {
            true
        } else {
            val holdMs = 400L
            brakeCueLastActiveMs > 0L && (tsMs - brakeCueLastActiveMs) <= holdMs
        }
        brakeCueStrength = if (brakeCueActive) strength else 0f

        if (AppPreferences.debugOverlay) {
            flog("brakeCue enabled=1 redRatio=%.3f redEma=%.3f dI=%.3f thr=%.2f dThr=%.2f strength=%.2f active=%s".format(
                redRatio, brakeRedRatioEma, delta, thr, deltaThr, brakeCueStrength, brakeCueActive
            ))
        }

        return BrakeCueResult(brakeCueActive, brakeCueStrength, brakeRedRatioEma, delta)
    }

    /**
     * Heuristika:
     * - pracuje ve spodní části bbox (cca 40 % výšky)
     * - měří poměr "červených" pixelů + průměrnou intenzitu červené složky.
     * Sampling stride drží výkon.
     */
    private fun sampleBrakeLightSignal(image: ImageProxy, rotationDegrees: Int, box: Box): Pair<Float, Float> {
        val (frameWRot, frameHRot) = ImagePreprocessor.rotatedFrameSize(image.width, image.height, rotationDegrees)
        if (frameWRot <= 0 || frameHRot <= 0) return 0f to 0f

        val x1 = box.x1.toInt().coerceIn(0, frameWRot - 1)
        val y1 = box.y1.toInt().coerceIn(0, frameHRot - 1)
        val x2 = box.x2.toInt().coerceIn(0, frameWRot - 1)
        val y2 = box.y2.toInt().coerceIn(0, frameHRot - 1)
        val left = min(x1, x2)
        val right = max(x1, x2)
        val top = min(y1, y2)
        val bottom = max(y1, y2)

        val bw = (right - left).coerceAtLeast(2)
        val bh = (bottom - top).coerceAtLeast(2)

        val roiTop = (bottom - (bh * 0.40f)).toInt().coerceIn(top, bottom - 1)
        val roiBottom = bottom
        val insetX = (bw * 0.12f).toInt().coerceAtLeast(0)
        val roiLeft = (left + insetX).coerceIn(0, frameWRot - 1)
        val roiRight = (right - insetX).coerceIn(roiLeft + 1, frameWRot)

        val sampler = ImagePreprocessor.newSampler(image, rotationDegrees)

        val step = 2 // sensitivity vs performance
        var redCount = 0
        var total = 0
        var sumRed = 0L

        var yy = roiTop
        while (yy < roiBottom) {
            var xx = roiLeft
            while (xx < roiRight) {
                val c = ImagePreprocessor.sampleRgb8Bilinear(sampler, xx.toFloat(), yy.toFloat())
                val r = c.r
                val g = c.g
                val b = c.b

                val isRed = r > 60 && r > (g * 12 / 10) && r > (b * 12 / 10)
                if (isRed) redCount += 1
                sumRed += r.toLong()
                total += 1

                xx += step
            }
            yy += step
        }
        if (total <= 0) return 0f to 0f
        val redRatio = redCount.toFloat() / total.toFloat()
        val redIntensity = (sumRed.toFloat() / (total.toFloat() * 255f)).coerceIn(0f, 1f)
        return redRatio to redIntensity
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