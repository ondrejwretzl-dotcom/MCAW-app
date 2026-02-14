package com.mcaw.ai

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.PointF
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
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
        const val EXTRA_BRAKE_CUE = "extra_brake_cue"
        const val EXTRA_ALERT_REASON = "extra_alert_reason"
        const val EXTRA_RISK_SCORE = "extra_risk_score"
    }

    // Performance: stage timing (debugOverlay only)
    private data class Perf(
        var t0: Long = 0L,
        var t1: Long = 0L,
        var t2: Long = 0L,
        var t3: Long = 0L,
        var t4: Long = 0L,
        var t5: Long = 0L,
        var t6: Long = 0L,
        var t7: Long = 0L,
        var t8: Long = 0L
    )

    private val perf = Perf()

    private var frameIndex = 0L
    private val logEveryNFrames = 15L

    private var tts: TextToSpeech? = null

    private var alertPlayer: MediaPlayer? = null
    private var audioFocusGranted = false
    private var lastAlertLevel = 0

    private var lastOverlaySentMs = 0L
    private val overlaySendMinIntervalMs = 33L

    private var lastNotifiedLevel = 0
    private var lastNotifiedMs = 0L
    private val notifyMinIntervalMs = 1500L

    private val tracker = TemporalTracker()
    private val imuMonitor = RiderImuMonitor(ctx)

    private val autoModeSwitcher = DetectionModePolicy.AutoModeSwitcher()

    // Target lock to avoid switching between objects (stability for TTC/alerts)
    private var lockedTrackId: Long? = null
    private var lockedPriority: Float = 0f
    private var switchCandidateId: Long? = null
    private var switchCandidateCount: Int = 0
    private var lastSelectedTrackId: Long = -1L

    // Lock timing (prevents rapid target switching / UI blinking)
    private var lockedSinceMs: Long = 0L

    // Distance history for sliding-window derivative of approach speed
    private val distHistory = FloatArray(12)
    private val distTsHistory = LongArray(12)
    private var distHistSize = 0
    private var distHistIdx = 0

    // Distance EMA for smoother TTC
    private var distEma = Float.POSITIVE_INFINITY
    private var distEmaValid = false

    // Brake cue state
    private data class BrakeCue(var active: Boolean = false, var strength: Float = 0f)

    private var brakeCueUntilMs = 0L
    private var brakeCueStrength = 0f

    // Cut-in boost window
    private var cutInBoostUntilMs = 0L

    // RiskEngine (predictive)
    private val riskEngine = RiskEngine()

    // ALWAYS-ON event log (sampled + transitions) – written off the analyzer thread.
    private val eventLogFileName: String = "mcaw_event_${sessionStamp()}.csv"
    private val eventLogger = com.mcaw.util.SessionEventLogger(ctx, eventLogFileName).also { it.start() }

    // DEBUG trace log (volitelně) – více detailů, stále throttled a mimo analyzer thread.
    private val traceLogFileName: String = "mcaw_trace_${sessionStamp()}.csv"
    private val traceLogger = if (AppPreferences.debugTrace) {
        com.mcaw.util.SessionTraceLogger(ctx, traceLogFileName).also { it.start() }
    } else null

    private var lastTraceSampleTsMs: Long = 0L
    private var lastTraceLockedId: Long = Long.MIN_VALUE

    // Event log sampling/tracking
    private var lastLoggedLevel: Int = -1
    private var lastLoggedState: RiskEngine.State = RiskEngine.State.SAFE
    private var lastSampleTsMs: Long = 0L

    private data class Quality(
        val poor: Boolean,
        val dark: Boolean,
        val motionBlur: Boolean
    )

    private fun estimateQuality(image: ImageProxy): Quality {
        // Super-light heuristic: avoid expensive ops, no bitmap/allocations.
        // (v2: could use luminance sampling from Y plane.)
        val w = image.width
        val h = image.height
        val small = (w * h) < (640 * 360)
        val dark = false
        val motionBlur = false
        val poor = small || dark || motionBlur
        return Quality(poor = poor, dark = dark, motionBlur = motionBlur)
    }

    private fun sessionStamp(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())
    }

    private fun flog(msg: String, force: Boolean = false) {
        if (!AppPreferences.debugOverlay) return
        if (!force && (frameIndex % logEveryNFrames != 0L)) return
        Log.d("MCAW", "f=$frameIndex $msg")
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
            frameIndex += 1

            val tsMs = System.currentTimeMillis()
            if (AppPreferences.debugOverlay) perf.t0 = SystemClock.elapsedRealtimeNanos()

            // Avoid running heavy analysis when preview is active.
            if (AppPreferences.previewActive) {
                image.close()
                return
            }

            // Get rider speed (m/s) – may be NaN if unknown
            val riderSpeedMps = speedProvider.getCurrent().speedMps

            // Detection mode resolution (auto switcher) – avoid toggling during alert
            val modeRes = autoModeSwitcher.resolve(
                selectedMode = AppPreferences.detectionMode,
                riderSpeedMps = riderSpeedMps,
                lastAlertLevel = lastAlertLevel,
                nowMs = tsMs
            )

            // IMU snapshot (lean/brake)
            val riderStanding = riderSpeedMps.isFinite() && riderSpeedMps <= (6.0f / 3.6f)

            // Get detections
            val detections = mutableListOf<Detection>()
            val bitmap = ImageUtils.imageProxyToBitmap(image)

            if (AppPreferences.debugOverlay) perf.t1 = SystemClock.elapsedRealtimeNanos()
            yolo?.let {
                detections += it.detect(bitmap)
            }
            if (AppPreferences.debugOverlay) perf.t2 = SystemClock.elapsedRealtimeNanos()

            det?.let {
                detections += it.detect(bitmap)
            }
            if (AppPreferences.debugOverlay) perf.t3 = SystemClock.elapsedRealtimeNanos()

            // Track temporally for stability
            val tracked = tracker.update(detections)
            if (AppPreferences.debugOverlay) perf.t4 = SystemClock.elapsedRealtimeNanos()

            // No detections -> clear overlays + alerts
            if (tracked.isEmpty()) {
                lockedTrackId = null
                lockedPriority = 0f
                switchCandidateId = null
                switchCandidateCount = 0
                lastSelectedTrackId = -1L
                stopActiveAlerts()
                sendOverlayUpdate(
                    box = null,
                    frameW = image.width.toFloat(),
                    frameH = image.height.toFloat(),
                    dist = Float.POSITIVE_INFINITY,
                    speedRel = 0f,
                    objectSpeed = 0f,
                    riderSpeed = riderSpeedMps,
                    label = "",
                    brakeCue = false,
                    alertLevel = 0,
                    alertReason = "SAFE",
                    reasonBits = 0,
                    riskScore = 0f
                )
                image.close()
                return
            }

            // Quality heuristic
            val quality = estimateQuality(image)

            // Select best target (with lock stability)
            val frameW = image.width.toFloat()
            val frameH = image.height.toFloat()

            // ROI trapezoid (ego-path)
            val roiTrap = buildRoiTrapezoid(frameW, frameH)

            // Filter candidates
            val candidates = tracked
                .filter { it.alertGatePassed }
                .map { it }

            // Choose best candidate by priority
            var bestTrack: TemporalTracker.TrackedDetection? = null
            var bestPriority = -1f

            for (t in candidates) {
                val b0 = clampBox(t.detection.box, frameW, frameH)
                val roiContainment = containmentRatioInTrapezoid(b0, roiTrap.pts).coerceIn(0f, 1f)
                val offset = egoOffsetInRoiN(b0, frameW, frameH, 1.0f).coerceIn(0f, 2f)

                // Priority (simple): ROI containment + size + center bias
                val area = ((b0.x2 - b0.x1) * (b0.y2 - b0.y1)) / (frameW * frameH)
                val centerBias = (1.0f - offset.coerceIn(0f, 1f))
                val p = (roiContainment * 0.6f) + (area * 0.3f) + (centerBias * 0.1f)

                if (p > bestPriority) {
                    bestPriority = p
                    bestTrack = t
                }
            }

            if (bestTrack == null) {
                image.close()
                return
            }

            // Lock handling: prevent rapid switching
            val bestId = bestTrack.id
            val nowMs = tsMs

            if (lockedTrackId == null) {
                lockedTrackId = bestId
                lockedPriority = bestPriority
                lockedSinceMs = nowMs
                switchCandidateId = null
                switchCandidateCount = 0
            } else if (lockedTrackId != bestId) {
                // candidate switching logic
                val lockAgeMs = nowMs - lockedSinceMs
                val canSwitch = lockAgeMs > 800L && lastAlertLevel == 0

                if (canSwitch) {
                    if (switchCandidateId == bestId) {
                        switchCandidateCount += 1
                    } else {
                        switchCandidateId = bestId
                        switchCandidateCount = 1
                    }

                    // require a few consecutive frames for switch
                    if (switchCandidateCount >= 3) {
                        lockedTrackId = bestId
                        lockedPriority = bestPriority
                        lockedSinceMs = nowMs
                        switchCandidateId = null
                        switchCandidateCount = 0
                    }
                }
            } else {
                lockedPriority = bestPriority
                switchCandidateId = null
                switchCandidateCount = 0
            }

            lastSelectedTrackId = lockedTrackId ?: bestId

            // DEBUG trace: selection/lock switching (sampled + transitions)
            traceLogger?.let { tl ->
                val lockedId = lockedTrackId ?: -1L
                val candId = switchCandidateId ?: -1L
                val transitioned = lockedId != lastTraceLockedId
                val activeCtx = (lockedId != -1L) || (lastAlertLevel > 0)
                val sampleDue = activeCtx && (tsMs - lastTraceSampleTsMs >= 500L)

                if (transitioned || sampleDue) {
                    if (transitioned) lastTraceLockedId = lockedId
                    if (sampleDue) lastTraceSampleTsMs = tsMs

                    tl.logTarget(
                        tsMs = tsMs,
                        kind = if (transitioned) 1 else 0,
                        lockedId = lockedId,
                        bestId = bestId,
                        bestPri = bestPriority,
                        lockedPri = lockedPriority,
                        candId = candId,
                        candCount = switchCandidateCount,
                        alertLevel = lastAlertLevel,
                        mode = modeRes.effectiveMode
                    )
                }
            }

            val bestBox = clampBox(bestTrack.detection.box, frameW, frameH)
            val label = bestTrack.detection.label ?: ""

            // Estimate distance (mono heuristics)
            val rawDistanceM = estimateDistanceMeters(bestBox, frameH)
            val distanceM = smoothDistance(rawDistanceM * AppPreferences.distanceScale)

            // Sliding window derivative for approach speed (REL, always >= 0)
            val approachSpeedMps = estimateApproachSpeed(distanceM, tsMs)

            // TTC (seconds)
            val ttc = if (approachSpeedMps > 0.1f && distanceM.isFinite() && distanceM > 0f) {
                (distanceM / approachSpeedMps).coerceIn(0f, 99f)
            } else {
                Float.POSITIVE_INFINITY
            }

            // Brake cue (simple) – triggered by sharp approach speed increase
            val brakeCue = updateBrakeCue(tsMs, approachSpeedMps)

            // Cut-in detection (very light heuristic): sudden ROI containment rise
            updateCutInBoost(tsMs, bestBox, roiTrap.pts)

            // IMU snapshot (brake + lean)
            val imu = imuMonitor.snapshot(tsMs)

            // ROI weight (0..1) for RiskEngine
            val roiContainment = containmentRatioInTrapezoid(bestBox, roiTrap.pts).coerceIn(0f, 1f)
            val egoOffset = egoOffsetInRoiN(bestBox, frameW, frameH, 1.0f).coerceIn(0f, 2f)

            val risk = if (riderStanding) {
                riskEngine.standingResult(riderSpeedMps)
            } else {
                riskEngine.evaluate(
                    tsMs = tsMs,
                    effectiveMode = modeRes.effectiveMode,
                    distanceM = distanceM,
                    approachSpeedMps = approachSpeedMps,
                    ttcSec = ttc,
                    roiContainment = roiContainment,
                    egoOffsetN = egoOffset,
                    cutInActive = (cutInBoostUntilMs > 0L && tsMs <= cutInBoostUntilMs),
                    brakeCueActive = brakeCue.active,
                    brakeCueStrength = brakeCue.strength,
                    qualityPoor = quality.poor,
                    riderSpeedMps = riderSpeedMps,
                    egoBrakingConfidence = imu.brakeConfidence,
                    leanDeg = imu.leanDeg
                )
            }

            val prevLevel = lastAlertLevel
            val level = risk.level
            val reasonBits = risk.reasonBits

            // Text WHY only when needed (alerts / debug overlay). For logs we use reasonBits.
            val alertReason =
                if (level > 0 || AppPreferences.debugOverlay) RiskEngine.formatReasonShort(reasonBits) else "SAFE"

            lastAlertLevel = level

            // --- ALWAYS-ON event log (sampled + transitions) ---
            val state = risk.state
            val transitioned = (level != lastLoggedLevel) || (state != lastLoggedState)
            val activeContext = (level > 0) || (lockedTrackId != null) || (risk.riskScore >= 0.15f)
            val sampleDue = activeContext && (tsMs - lastSampleTsMs >= 500L)

            if (transitioned || sampleDue) {
                if (transitioned) {
                    lastLoggedLevel = level
                    lastLoggedState = state
                }
                if (sampleDue) lastSampleTsMs = tsMs

                eventLogger.logEvent(
                    tsMs = tsMs,
                    risk = risk.riskScore,
                    level = level,
                    state = state,
                    reasonBits = reasonBits,
                    ttcSec = ttc,
                    distM = distanceM,
                    relV = approachSpeedMps,
                    roi = roiContainment,
                    qualityPoor = quality.poor,
                    cutIn = (cutInBoostUntilMs > 0L && tsMs <= cutInBoostUntilMs),
                    brake = brakeCue.active,
                    egoBrake = imu.brakeConfidence,
                    mode = modeRes.effectiveMode,
                    lockedId = lockedTrackId ?: -1L
                )
            }

            if (riderStanding) {
                AlertNotifier.stopInApp(ctx)
            } else {
                AlertNotifier.handleInApp(ctx, level, risk)
            }

            // Debug log (no IO)
            if (AppPreferences.debugOverlay && (frameIndex % logEveryNFrames == 0L)) {
                flog(
                    "risk level=$level score=${"%.2f".format(risk.riskScore)} state=${risk.state} bits=$reasonBits reason=$alertReason",
                    force = (level != prevLevel)
                )
            }

            sendOverlayUpdate(
                box = bestBox,
                frameW = frameW,
                frameH = frameH,
                dist = distanceM,
                speedRel = approachSpeedMps,
                objectSpeed = if (riderSpeedMps.isFinite()) max(0f, riderSpeedMps - approachSpeedMps) else 0f,
                riderSpeed = riderSpeedMps,
                label = label,
                brakeCue = brakeCue.active,
                alertLevel = lastAlertLevel,
                alertReason = alertReason,
                reasonBits = reasonBits,
                riskScore = risk.riskScore
            )

            if (AppPreferences.debugOverlay) perf.t8 = SystemClock.elapsedRealtimeNanos()

            image.close()
        } catch (e: Throwable) {
            runCatching { image.close() }
        }
    }

    fun shutdown() {
        runCatching { eventLogger.close() }
        runCatching { traceLogger?.close() }
        runCatching { imuMonitor.stop() }
        runCatching { AlertNotifier.shutdown(ctx) }
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
        runCatching { alertPlayer?.stop() }
        runCatching { alertPlayer?.release() }
        alertPlayer = null
        abandonAlertAudioFocus(am)
        AlertNotifier.stopInApp(ctx)
    }

    private fun requestAlertAudioFocus(am: AudioManager): Boolean {
        if (audioFocusGranted) return true
        val res = am.requestAudioFocus(
            { },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
        audioFocusGranted = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return audioFocusGranted
    }

    private fun abandonAlertAudioFocus(am: AudioManager) {
        if (!audioFocusGranted) return
        runCatching { am.abandonAudioFocus(null) }
        audioFocusGranted = false
    }

    private fun playAlert(level: Int) {
        if (!AppPreferences.sound) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!requestAlertAudioFocus(am)) return
        val resId = if (level >= 2) R.raw.red_alert else R.raw.alert_beep
        runCatching {
            alertPlayer?.stop()
            alertPlayer?.release()
        }
        alertPlayer = MediaPlayer.create(ctx, resId)?.apply {
            isLooping = false
            start()
        }
    }

    private fun vibrateAlert(level: Int) {
        if (!AppPreferences.vibration) return
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val effect = if (level >= 2) {
            VibrationEffect.createOneShot(220L, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { vib.vibrate(effect) }
    }

    private fun speakAlert(level: Int) {
        if (!AppPreferences.voice) return
        val msg = if (level >= 2) AppPreferences.ttsTextRed else AppPreferences.ttsTextOrange
        runCatching { tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "mcaw_alert") }
    }

    private fun maybeNotify(level: Int, reason: String) {
        val now = System.currentTimeMillis()
        if (level == 0) {
            lastNotifiedLevel = 0
            return
        }
        if (level == lastNotifiedLevel && (now - lastNotifiedMs) < notifyMinIntervalMs) return
        lastNotifiedLevel = level
        lastNotifiedMs = now
        AlertNotifier.show(ctx, "MCAW", reason, level)
    }

    private fun sendOverlayUpdate(
        box: Box?,
        frameW: Float,
        frameH: Float,
        dist: Float,
        speedRel: Float,
        objectSpeed: Float,
        riderSpeed: Float,
        label: String,
        brakeCue: Boolean,
        alertLevel: Int,
        alertReason: String,
        reasonBits: Int,
        riskScore: Float
    ) {
        val now = System.currentTimeMillis()
        if (!AppPreferences.debugOverlay && (now - lastOverlaySentMs) < overlaySendMinIntervalMs) return
        lastOverlaySentMs = now

        val i = Intent(ACTION_METRICS_UPDATE).apply {
            putExtra(EXTRA_TTC, if (speedRel > 0.1f && dist.isFinite() && dist > 0f) dist / speedRel else Float.POSITIVE_INFINITY)
            putExtra(EXTRA_DISTANCE, dist)
            putExtra(EXTRA_SPEED, speedRel)
            putExtra(EXTRA_OBJECT_SPEED, objectSpeed)
            putExtra(EXTRA_RIDER_SPEED, riderSpeed)
            putExtra(EXTRA_LEVEL, alertLevel)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_BRAKE_CUE, brakeCue)
            putExtra(EXTRA_ALERT_REASON, alertReason)
            putExtra(EXTRA_RISK_SCORE, riskScore)
            putExtra("extra_reason_bits", reasonBits)
            putExtra("extra_frame_w", frameW)
            putExtra("extra_frame_h", frameH)
            if (box != null) {
                putExtra("extra_x1", box.x1)
                putExtra("extra_y1", box.y1)
                putExtra("extra_x2", box.x2)
                putExtra("extra_y2", box.y2)
            }
        }
        ctx.sendBroadcast(i)
    }

    // ---------- Helpers (distance, ROI, etc.) ----------

    private fun estimateDistanceMeters(box: Box, frameH: Float): Float {
        // Existing heuristic. Keep as-is.
        val pixelH = abs(box.y2 - box.y1).coerceAtLeast(1f)
        val focal = AppPreferences.cameraFocalLengthMm
        val sensorH = AppPreferences.cameraSensorHeightMm
        val mountH = AppPreferences.cameraMountHeightM
        val pitch = AppPreferences.cameraPitchDownDeg

        // Fallback if camera calibration unknown
        if (!focal.isFinite() || !sensorH.isFinite()) {
            val k = 1.6f
            return (k * frameH / pixelH).coerceIn(0.5f, 120f)
        }

        // Very rough; keep stable.
        val k = (focal / sensorH) * 0.9f
        val est = (k * frameH / pixelH) * mountH
        return est.coerceIn(0.5f, 200f)
    }

    private fun estimateApproachSpeed(distanceM: Float, tsMs: Long): Float {
        if (!distanceM.isFinite()) return 0f
        distHistory[distHistIdx] = distanceM
        distTsHistory[distHistIdx] = tsMs
        distHistIdx = (distHistIdx + 1) % distHistory.size
        distHistSize = min(distHistSize + 1, distHistory.size)

        if (distHistSize < 4) return 0f

        // Use oldest vs newest in window
        val newestIdx = (distHistIdx - 1 + distHistory.size) % distHistory.size
        val oldestIdx = (distHistIdx - distHistSize + distHistory.size) % distHistory.size

        val dNew = distHistory[newestIdx]
        val tNew = distTsHistory[newestIdx]
        val dOld = distHistory[oldestIdx]
        val tOld = distTsHistory[oldestIdx]

        val dt = (tNew - tOld).coerceAtLeast(1L).toFloat() / 1000f
        val dd = (dOld - dNew) // decreasing distance => positive approach
        val v = (dd / dt).coerceAtLeast(0f)
        return v
    }

    private fun updateBrakeCue(tsMs: Long, approachSpeedMps: Float): BrakeCue {
        // Simple: trigger when approach is strong
        if (approachSpeedMps > 6.0f) {
            brakeCueUntilMs = tsMs + 700L
            brakeCueStrength = ((approachSpeedMps - 6.0f) / 6.0f).coerceIn(0f, 1f)
        }
        val active = tsMs <= brakeCueUntilMs
        return BrakeCue(active = active, strength = if (active) brakeCueStrength else 0f)
    }

    private fun updateCutInBoost(tsMs: Long, bestBox: Box, trap: Array<PointF>) {
        // Light heuristic: if target enters deeper in ROI quickly, boost for short period.
        val contain = containmentRatioInTrapezoid(bestBox, trap).coerceIn(0f, 1f)
        if (contain > 0.85f) {
            cutInBoostUntilMs = tsMs + 900L
        }
    }

    private data class RoiTrap(val pts: Array<PointF>)

    private fun buildRoiTrapezoid(w: Float, h: Float): RoiTrap {
        // Existing ROI trapezoid. Keep stable.
        val topY = h * 0.55f
        val botY = h * 0.95f
        val topHalf = w * 0.12f
        val botHalf = w * 0.38f
        val cx = w * 0.5f

        val p1 = PointF(cx - topHalf, topY)
        val p2 = PointF(cx + topHalf, topY)
        val p3 = PointF(cx + botHalf, botY)
        val p4 = PointF(cx - botHalf, botY)
        return RoiTrap(arrayOf(p1, p2, p3, p4))
    }

    private fun containmentRatioInTrapezoid(box: Box, pts: Array<PointF>): Float {
        // Approx via bounding rectangle overlap with trapezoid bbox (cheap).
        val trapRect = RectF(
            min(min(pts[0].x, pts[1].x), min(pts[2].x, pts[3].x)),
            min(min(pts[0].y, pts[1].y), min(pts[2].y, pts[3].y)),
            max(max(pts[0].x, pts[1].x), max(pts[2].x, pts[3].x)),
            max(max(pts[0].y, pts[1].y), max(pts[2].y, pts[3].y))
        )
        val boxRect = RectF(box.x1, box.y1, box.x2, box.y2)
        val inter = RectF()
        val ok = inter.setIntersect(trapRect, boxRect)
        if (!ok) return 0f
        val interArea = inter.width() * inter.height()
        val boxArea = (boxRect.width() * boxRect.height()).coerceAtLeast(1f)
        return (interArea / boxArea).coerceIn(0f, 1f)
    }

    private fun egoOffsetInRoiN(box: Box, w: Float, h: Float, scale: Float): Float {
        val cx = (box.x1 + box.x2) * 0.5f
        val center = w * 0.5f
        val dx = abs(cx - center)
        val norm = (dx / (w * 0.5f)).coerceIn(0f, 2f)
        return norm * scale
    }
}
