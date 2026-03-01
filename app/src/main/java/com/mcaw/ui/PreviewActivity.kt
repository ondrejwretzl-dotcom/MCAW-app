package com.mcaw.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.ai.EfficientDetTFLiteDetector
import com.mcaw.ai.YoloOnnxDetector
import com.mcaw.app.BuildConfig
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.config.CalibrationHealth
import com.mcaw.config.ProfileManager
import com.mcaw.location.SpeedMonitor
import com.mcaw.location.SpeedProvider
import com.mcaw.util.LabelMapper
import com.mcaw.util.ReasonTextMapper
import com.mcaw.util.SessionActivityLogger
import com.mcaw.util.SessionLogFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * PreviewActivity = runtime-only live preview.
 * - No ROI/guide/zoom edit UI.
 * - Uses active profile values applied into AppPreferences.
 */
class PreviewActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView

    private var analyzer: DetectionAnalyzer? = null

    private lateinit var speedProvider: SpeedProvider
    private lateinit var speedMonitor: SpeedMonitor

    private lateinit var txtHudPrimary: TextView
    private lateinit var txtHudMetrics: TextView
    private lateinit var txtHudRisk: TextView

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searching = true
    private var searchDots = 0

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isCameraBound: Boolean = false
    private var boundCamera: androidx.camera.core.Camera? = null



    private fun format3(value: Float): String {
        if (!value.isFinite()) return "—"
        val rounded = (value * 1000f).roundToInt() / 1000f
        val sign = if (rounded < 0f) "-" else ""
        val absV = kotlin.math.abs(rounded)
        val intPart = absV.toInt()
        val frac = ((absV - intPart) * 1000f).roundToInt().coerceIn(0, 999)
        val fracTxt = when {
            frac < 10 -> "00$frac"
            frac < 100 -> "0$frac"
            else -> frac.toString()
        }
        return "$sign$intPart.$fracTxt"
    }

    private fun speedSourceLabel(): String {
        return runCatching {
            val values = SpeedProvider.Source.values()
            values.getOrNull(overlay.riderSpeedSourceOrdinal)?.name ?: "UNK"
        }.getOrDefault("UNK")
    }

    private fun format3WithUnit(value: Float, unit: String): String = "${format3(value)} $unit"

    private fun renderHudMetrics() {
        // Fixed line order to avoid jumping.
        val trackId = if (overlay.targetPresent && overlay.targetTrackId >= 0L) overlay.targetTrackId.toString() else "—"
        val ridMps = overlay.riderSpeed
        val ridKmh = if (ridMps.isFinite()) ridMps * 3.6f else Float.NaN
        val ridSrc = speedSourceLabel()
        val ridConf = overlay.riderSpeedConfidence
        val ridAge = overlay.riderSpeedAgeMs

        txtHudMetrics.visibility = View.VISIBLE
        txtHudMetrics.text = listOf(
            "Track ID: $trackId",
            "TTC(fused): ${format3WithUnit(overlay.ttc, "s")}",
            "TTC(H):    ${format3WithUnit(overlay.ttcHeight, "s")}",
            "TTC(D):    ${format3WithUnit(overlay.ttcDist, "s")}",
            "REL(signed): ${format3WithUnit(overlay.relSignedMps, "m/s")}",
            "REL(abs):    ${format3WithUnit(overlay.relAbsMps, "m/s")}",
            "DIST:      ${format3WithUnit(overlay.distance, "m")}",
            "RIDER:     ${format3WithUnit(ridMps, "m/s")} (${format3WithUnit(ridKmh, "km/h")}) src=$ridSrc conf=${format3(ridConf)} age=${ridAge}ms"
        ).joinToString("\n")
    }

    private fun clearTrackedHudValues(keepRider: Boolean) {
        val riderSpeed = overlay.riderSpeed
        val riderSrc = overlay.riderSpeedSourceOrdinal
        val riderConf = overlay.riderSpeedConfidence
        val riderAge = overlay.riderSpeedAgeMs

        overlay.box = null
        overlay.distance = Float.NaN
        overlay.roiMinDistM = Float.NaN
        overlay.roiBottomTouch = false
        overlay.speed = Float.NaN
        overlay.relAbsMps = Float.NaN
        overlay.relSignedMps = Float.NaN
        overlay.relDerivValid = false
        overlay.relInvalidReasonMask = 0
        overlay.trendState = DetectionAnalyzer.TREND_STEADY
        overlay.steadyMs = 0L
        overlay.approachMs = 0L
        overlay.steadySuppressActive = false
        overlay.reenterCooldownMs = 0L
        overlay.distSlopeEmaMps = Float.NaN
        overlay.distSource = DetectionAnalyzer.DIST_SOURCE_BOTTOM
        overlay.distConf = 0f
        overlay.objectSpeed = Float.NaN
        overlay.ttc = Float.NaN
        overlay.ttcHeight = Float.NaN
        overlay.ttcDist = Float.NaN
        overlay.label = ""
        overlay.targetPresent = false
        overlay.targetTrackId = -1L
        overlay.targetGroupLabel = "Unknown"
        overlay.targetRawLabel = null
        overlay.targetDetScore = Float.NaN
        overlay.alertLevel = 0
        overlay.brakeCueActive = false
        overlay.alertReason = ""
        overlay.riskScore = Float.NaN

        if (keepRider) {
            overlay.riderSpeed = riderSpeed
            overlay.riderSpeedSourceOrdinal = riderSrc
            overlay.riderSpeedConfidence = riderConf
            overlay.riderSpeedAgeMs = riderAge
        }

        renderHudMetrics()
        txtHudRisk.text = "Risk: —"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return

            // ROI values may be broadcast (debug/service). Preview uses them only for visualization.
            if (i.hasExtra("roi_trap_top_y_n")) {
                overlay.roiTopY = i.getFloatExtra("roi_trap_top_y_n", overlay.roiTopY)
                overlay.roiBottomY = i.getFloatExtra("roi_trap_bottom_y_n", overlay.roiBottomY)
                overlay.roiTopHalfW = i.getFloatExtra("roi_trap_top_halfw_n", overlay.roiTopHalfW)
                overlay.roiBottomHalfW = i.getFloatExtra("roi_trap_bottom_halfw_n", overlay.roiBottomHalfW)
                overlay.roiCenterX = i.getFloatExtra("roi_trap_center_x_n", overlay.roiCenterX)
            }

            if (i.getBooleanExtra("clear", false)) {
                overlay.riderSpeed = i.getFloatExtra("rider_speed", overlay.riderSpeed)
                overlay.riderSpeedSourceOrdinal = i.getIntExtra("rider_speed_src", overlay.riderSpeedSourceOrdinal)
                overlay.riderSpeedConfidence = i.getFloatExtra("rider_speed_conf", overlay.riderSpeedConfidence)
                overlay.riderSpeedAgeMs = i.getLongExtra("rider_speed_age_ms", overlay.riderSpeedAgeMs)
                clearTrackedHudValues(keepRider = true)
                searching = true
                updateSearchingLabel()
                logActivity("detection_clear")
                return
            }

            searching = false
            overlay.frameWidth = i.getFloatExtra("frame_w", 0f)
            overlay.frameHeight = i.getFloatExtra("frame_h", 0f)
            overlay.box = com.mcaw.model.Box(
                i.getFloatExtra("left", 0f),
                i.getFloatExtra("top", 0f),
                i.getFloatExtra("right", 0f),
                i.getFloatExtra("bottom", 0f)
            )

            val h = CalibrationHealth.evaluate()
            overlay.distance = if (h.distanceReliable) i.getFloatExtra("dist", -1f) else Float.NaN
            overlay.roiMinDistM = i.getFloatExtra("roi_min_dist_m", Float.NaN)
            overlay.roiBottomTouch = i.getBooleanExtra("roi_bottom_touch", false)
            overlay.speed = i.getFloatExtra("speed", -1f) // REL abs
            overlay.relSignedMps = i.getFloatExtra("rel_signed_mps", Float.NaN)
            overlay.relAbsMps = i.getFloatExtra("rel_abs_mps", Float.NaN)
            overlay.relDerivValid = i.getBooleanExtra(com.mcaw.ai.DetectionAnalyzer.EXTRA_REL_DERIV_VALID, true)
            overlay.relInvalidReasonMask = i.getIntExtra(com.mcaw.ai.DetectionAnalyzer.EXTRA_REL_INVALID_REASON_MASK, 0)
            overlay.trendState = i.getIntExtra("trend_state", DetectionAnalyzer.TREND_STEADY)
            overlay.steadyMs = i.getLongExtra("steady_ms", 0L)
            overlay.approachMs = i.getLongExtra("approach_ms", 0L)
            overlay.steadySuppressActive = i.getBooleanExtra("steady_suppress_active", false)
            overlay.reenterCooldownMs = i.getLongExtra("reenter_cooldown_ms", 0L)
            overlay.distSlopeEmaMps = i.getFloatExtra("dist_slope_ema_mps", Float.NaN)
            overlay.distSource = i.getIntExtra("dist_source", DetectionAnalyzer.DIST_SOURCE_BOTTOM)
            overlay.distConf = i.getFloatExtra("dist_conf", 0f)
            overlay.objectSpeed = i.getFloatExtra("object_speed", -1f) // OBJ
            overlay.riderSpeed = i.getFloatExtra("rider_speed", -1f) // RID
            overlay.riderSpeedSourceOrdinal = i.getIntExtra("rider_speed_src", 0)
            overlay.riderSpeedConfidence = i.getFloatExtra("rider_speed_conf", 0f)
            overlay.riderSpeedAgeMs = i.getLongExtra("rider_speed_age_ms", 0L)
            overlay.ttc = i.getFloatExtra("ttc", Float.NaN)
            overlay.ttcHeight = i.getFloatExtra("ttc_h", Float.NaN)
            overlay.ttcDist = i.getFloatExtra("ttc_d", Float.NaN)
            overlay.alertLevel = i.getIntExtra("alert_level", 0)

            val legacyReason = i.getStringExtra("alert_reason") ?: ""
            val reasonBits = i.getIntExtra("reason_bits", 0)
            overlay.alertReason = ReasonTextMapper.shortOrFallback(reasonBits, legacyReason)

            overlay.riskScore = i.getFloatExtra("risk_score", Float.NaN)
            overlay.brakeCueActive = i.getBooleanExtra("brake_cue", false)

            val targetGroupLabel = i.getStringExtra("target_group_label") ?: "Unknown"
            val targetRawLabel = i.getStringExtra("target_raw_label")
            val targetTrackId = i.getLongExtra("target_track_id", -1L)
            val targetDetScore = i.getFloatExtra("target_det_score", Float.NaN)

            overlay.targetPresent = i.getBooleanExtra("target_present", true)
            if (!overlay.targetPresent || targetTrackId < 0L) {
                clearTrackedHudValues(keepRider = true)
                searching = true
                updateSearchingLabel()
                logActivity("detection_clear")
                return
            }
            overlay.targetTrackId = targetTrackId
            overlay.targetGroupLabel = targetGroupLabel
            overlay.targetRawLabel = targetRawLabel
            overlay.targetDetScore = targetDetScore
            overlay.label = targetGroupLabel

            val mapped = if (AppPreferences.debugOverlay) LabelMapper.mapLabel(targetGroupLabel) else "Vehicle"
            val cal = CalibrationHealth.evaluate().state.name
            txtHudPrimary.text = "Stav: ${if (searching) "Hledám" else "Sledování"} · Kalibrace: $cal · Detekce: $mapped"
            renderHudMetrics()
            val riskText = if (overlay.riskScore.isFinite()) "Risk: ${format3(overlay.riskScore)} L${overlay.alertLevel} · ${overlay.alertReason}" else "Risk: —"
            txtHudRisk.text = riskText
            logActivity("detection_found group=$targetGroupLabel raw=${targetRawLabel ?: ""} id=$targetTrackId")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppPreferences.ensureInit(this)
        ProfileManager.ensureInit(this)
        // Apply active profile (if any) before we load ROI and camera params.
        ProfileManager.applyActiveProfileToPreferences()
        AppPreferences.previewActive = true

        setContentView(R.layout.activity_preview)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        overlay = findViewById(R.id.overlay)

        val txtPreviewBuild = findViewById<TextView>(R.id.txtPreviewBuild)
        txtHudPrimary = findViewById(R.id.txtHudPrimary)
        txtHudMetrics = findViewById(R.id.txtHudMetrics)
        txtHudRisk = findViewById(R.id.txtHudRisk)

        speedProvider = SpeedProvider(this)
        speedMonitor = SpeedMonitor(speedProvider)
        SessionLogFile.init(this)

        overlay.showTelemetry = AppPreferences.debugOverlay
        updateActiveProfileLabel()
        applyRoiFromPrefs()

        txtPreviewBuild.text =
            "MCAW ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_ID}"

        updateSearchingLabel()
        logActivity("preview_open build=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")

        val filter = IntentFilter("MCAW_DEBUG_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2001)
        } else {
            initAndStart()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 2001 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            logActivity("camera_permission_granted")
            initAndStart()
        } else {
            logActivity("camera_permission_denied")
            finish()
        }
    }

    private fun initAndStart() {
        val yolo = runCatching { YoloOnnxDetector(this, "yolov8n.onnx") }.getOrNull()
        val eff = runCatching { EfficientDetTFLiteDetector(this, "efficientdet_lite0.tflite") }.getOrNull()

        if (yolo == null && eff == null) {
            txtHudPrimary.text = "Stav: nelze načíst modely"
            logActivity("models_failed")
        } else {
            logActivity("models_loaded yolo=${yolo != null} efficient=${eff != null}")
        }

        analyzer = DetectionAnalyzer(this, yolo, eff, speedProvider)
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            isCameraBound = true
            bindPreviewAndAnalysis()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreviewAndAnalysis() {
        val provider = cameraProvider ?: return
        if (!isCameraBound) return

        provider.unbindAll()

        previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val a = analyzer
        analysisUseCase = if (a != null) {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, a) }
        } else {
            null
        }

        val useCases = arrayListOf<androidx.camera.core.UseCase>(previewUseCase!!)
        analysisUseCase?.let { useCases.add(it) }

        val camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray()
        )
        boundCamera = camera
        updateCameraCalibration(camera)
        applyZoomAndFocusIfPossible(reason = "bind_preview_analysis")
    }

    private fun updateCameraCalibration(camera: androidx.camera.core.Camera) {
        val camInfo = camera.cameraInfo
        val cam2 = Camera2CameraInfo.from(camInfo)
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cam2.cameraId
        val chars = manager.getCameraCharacteristics(id)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        if (focalLengths != null && focalLengths.isNotEmpty()) {
            AppPreferences.cameraFocalLengthMm = focalLengths[0]
        }
        if (sensorSize != null) {
            AppPreferences.cameraSensorHeightMm = sensorSize.height
        }
    }

    /**
     * Applies camera zoom and autofocus metering point derived from ROI (user intent).
     * Lightweight and safe to call repeatedly.
     */
    private fun applyZoomAndFocusIfPossible(reason: String) {
        val camera = boundCamera ?: return

        // Zoom (conservative upper bound to avoid lens switching)
        runCatching {
            camera.cameraControl.setZoomRatio(AppPreferences.cameraZoomRatio.coerceIn(1.0f, 2.0f))
        }

        // Focus point: centerX, and upper third within ROI vertical span (avoid dashboard)
        val roi = AppPreferences.getRoiTrapezoidNormalized()
        val xNorm = roi.centerX.coerceIn(0.05f, 0.95f)
        val yNorm = (roi.topY + 0.35f * (roi.bottomY - roi.topY)).coerceIn(0.05f, 0.95f)

        val factory = previewView.meteringPointFactory
        val p: MeteringPoint = factory.createPoint(xNorm, yNorm)
        val action = FocusMeteringAction.Builder(p)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        runCatching { camera.cameraControl.startFocusAndMetering(action) }
            .onSuccess {
                logActivity(
                    "cam_focus_roi reason=$reason x=${"%.3f".format(xNorm)} y=${"%.3f".format(yNorm)} z=${"%.2f".format(AppPreferences.cameraZoomRatio)}"
                )
            }
    }

    private fun applyRoiFromPrefs() {
        val roi = AppPreferences.getRoiTrapezoidNormalized()
        overlay.roiTopY = roi.topY
        overlay.roiBottomY = roi.bottomY
        overlay.roiTopHalfW = roi.topHalfW
        overlay.roiBottomHalfW = roi.bottomHalfW
        overlay.roiCenterX = roi.centerX
    }

    override fun onStart() {
        super.onStart()
        updateCalibrationHealthUi()
        speedMonitor.start()
    }

    override fun onStop() {
        speedMonitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        speedMonitor.stop()
        stopSearching()
        AppPreferences.previewActive = false
        runCatching { analyzer?.shutdown() }
        runCatching { analysisExecutor.shutdown() }
        super.onDestroy()
    }

    private fun updateCalibrationHealthUi() {
        val h = CalibrationHealth.evaluate()
        if (h.bannerText.isBlank()) {
            // calibration status now shown in unified HUD primary line
        }
    }

    private fun updateSearchingLabel() {
        if (!searching) {
            txtHudPrimary.text = "Živý náhled aktivní"
            stopSearching()
            return
        }
        searchDots = (searchDots + 1) % 4
        val dots = ".".repeat(searchDots)
        txtHudPrimary.text = "Hledám objekt$dots"
        searchHandler.postDelayed({ updateSearchingLabel() }, 500L)
    }

    private fun stopSearching() {
        searchHandler.removeCallbacksAndMessages(null)
    }

    private fun updateActiveProfileLabel() {
        val id = ProfileManager.getActiveProfileIdOrNull()
        val name = if (id == null) {
            "Default"
        } else {
            ProfileManager.findById(id)?.name ?: "?"
        }
        txtHudPrimary.text = "Profil: $name"
    }

    private fun logActivity(msg: String) {
        SessionActivityLogger.log(msg)
    }
}
