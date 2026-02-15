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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import com.mcaw.location.SpeedMonitor
import com.mcaw.location.SpeedProvider
import com.mcaw.util.LabelMapper
import com.mcaw.util.PublicLogWriter
import com.mcaw.util.SessionLogFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PreviewActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private var analyzer: DetectionAnalyzer? = null
    private lateinit var speedProvider: SpeedProvider
    private lateinit var speedMonitor: SpeedMonitor
    private lateinit var txtDetectionLabel: TextView
    private lateinit var btnRoi: TextView
    private lateinit var txtPreviewStatus: TextView

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searching = true
    private var searchDots = 0

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isCameraBound: Boolean = false
    private var speedPausedByEdit: Boolean = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return

            // During ROI edit: ignore detection/ROI updates to prevent "fight" with UI editing.
            // (Analyzer may still have in-flight broadcast, or service might still be running elsewhere.)
            if (overlay.roiEditMode) {
                return
            }

            // ROI always (even on clear)
            if (i.hasExtra("roi_trap_top_y_n")) {
                overlay.roiTopY = i.getFloatExtra("roi_trap_top_y_n", overlay.roiTopY)
                overlay.roiBottomY = i.getFloatExtra("roi_trap_bottom_y_n", overlay.roiBottomY)
                overlay.roiTopHalfW = i.getFloatExtra("roi_trap_top_halfw_n", overlay.roiTopHalfW)
                overlay.roiBottomHalfW = i.getFloatExtra("roi_trap_bottom_halfw_n", overlay.roiBottomHalfW)
                overlay.roiCenterX = i.getFloatExtra("roi_trap_center_x_n", overlay.roiCenterX)
            }

            if (i.getBooleanExtra("clear", false)) {
                overlay.box = null
                overlay.distance = -1f
                overlay.speed = -1f
                overlay.objectSpeed = -1f
                overlay.riderSpeed = -1f
                overlay.ttc = -1f
                overlay.label = ""
                overlay.alertLevel = 0
                overlay.brakeCueActive = false
                overlay.alertReason = ""
                overlay.riskScore = Float.NaN
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
            overlay.distance = i.getFloatExtra("dist", -1f)
            overlay.speed = i.getFloatExtra("speed", -1f) // REL (approach)
            overlay.objectSpeed = i.getFloatExtra("object_speed", -1f) // OBJ
            overlay.riderSpeed = i.getFloatExtra("rider_speed", -1f) // RID
            overlay.ttc = i.getFloatExtra("ttc", -1f)
            overlay.alertLevel = i.getIntExtra("alert_level", 0)
            overlay.alertReason = i.getStringExtra("alert_reason") ?: ""
            overlay.brakeCueActive = i.getBooleanExtra("brake_cue", false)
            val mapped = LabelMapper.mapLabel(i.getStringExtra("label"))
            overlay.label = mapped
            txtDetectionLabel.text = "Detekce: $mapped"
            logActivity("detection_found label=$mapped")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.ensureInit(this)
        AppPreferences.previewActive = true

        setContentView(R.layout.activity_preview)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        val txtPreviewBuild = findViewById<TextView>(R.id.txtPreviewBuild)
        txtDetectionLabel = findViewById(R.id.txtDetectionLabel)
        btnRoi = findViewById(R.id.btnRoi)
        txtPreviewStatus = findViewById(R.id.txtPreviewStatus)

        speedProvider = SpeedProvider(this)
        speedMonitor = SpeedMonitor(speedProvider)
        SessionLogFile.init(this)

        overlay.showTelemetry = AppPreferences.debugOverlay

        applyRoiFromPrefs()
        overlay.onRoiChanged = { topY, bottomY, topHalfW, bottomHalfW, centerX, isFinal ->
            if (isFinal) {
                AppPreferences.setRoiTrapezoidNormalized(topY, bottomY, topHalfW, bottomHalfW, centerX = centerX)
                logActivity("roi_set topY=$topY bottomY=$bottomY topHalfW=$topHalfW bottomHalfW=$bottomHalfW centerX=$centerX")
            }
        }

        btnRoi.setOnClickListener {
            val newState = !overlay.roiEditMode
            setRoiEditMode(newState)
        }

        btnRoi.setOnLongClickListener {
            if (overlay.roiEditMode) {
                // v edit módu reset -> rovnou přepíš i overlay
                AppPreferences.resetRoiToDefault()
                applyRoiFromPrefs()
                logActivity("roi_reset_default")
                return@setOnLongClickListener true
            }
            AppPreferences.resetRoiToDefault()
            applyRoiFromPrefs()
            logActivity("roi_reset_default")
            true
        }

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

    private fun setRoiEditMode(enabled: Boolean) {
        if (overlay.roiEditMode == enabled) return
        overlay.roiEditMode = enabled
        btnRoi.text = if (enabled) "ROI: UPRAVIT ✓" else "ROI: UPRAVIT"

        if (enabled) {
            // Senior UX: ROI edit nesmí soupeřit s detekcí -> zastavit analýzu + broadcasty.
            pauseDetectionForRoiEdit()
        } else {
            // Uložené hodnoty už jsou v prefs (onRoiChanged isFinal).
            // Pro jistotu znovu načti (sanitizace + jednotný zdroj pravdy).
            applyRoiFromPrefs()
            resumeDetectionAfterRoiEdit()
        }
    }

    private fun pauseDetectionForRoiEdit() {
        logActivity("roi_edit_start")

        // Zastav analyzátor (ponech preview obraz).
        bindPreviewOnly()

        // Vizuálně vyčisti detekci a přepni status.
        overlay.box = null
        overlay.alertLevel = 0
        overlay.brakeCueActive = false
        txtDetectionLabel.text = "Detekce: pozastaveno (edit ROI)"
        txtPreviewStatus.text = "EDIT ROI · detekce pozastavena"

        // Speed monitor v edit módu nepotřebujeme (šetří CPU/GPS).
        speedPausedByEdit = true
        speedMonitor.stop()

        searching = false
        stopSearching()
    }

    private fun resumeDetectionAfterRoiEdit() {
        logActivity("roi_edit_stop")

        // Obnov analýzu.
        bindPreviewAndAnalysis()

        // Status UI zpět.
        searching = true
        updateSearchingLabel()

        if (speedPausedByEdit) {
            speedPausedByEdit = false
            speedMonitor.start()
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
            txtDetectionLabel.text = "Detekce: nelze načíst modely"
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

            // Default state: preview + analysis (unless user is editing ROI)
            if (overlay.roiEditMode) {
                bindPreviewOnly()
            } else {
                bindPreviewAndAnalysis()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreviewOnly() {
        val provider = cameraProvider ?: return
        if (!isCameraBound) return

        provider.unbindAll()

        previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            previewUseCase
        )
        updateCameraCalibration(camera)
        analysisUseCase = null
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
        updateCameraCalibration(camera)
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

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        speedMonitor.stop()
        stopSearching()
        AppPreferences.previewActive = false
        runCatching { analyzer?.shutdown() }
        runCatching { analysisExecutor.shutdown() }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (!overlay.roiEditMode) {
            speedMonitor.start()
        }
    }

    override fun onStop() {
        speedMonitor.stop()
        super.onStop()
    }

    private fun updateSearchingLabel() {
        if (overlay.roiEditMode) return

        if (!searching) {
            txtPreviewStatus.text = "Živý náhled aktivní"
            stopSearching()
            return
        }
        searchDots = (searchDots + 1) % 4
        val dots = ".".repeat(searchDots)
        txtPreviewStatus.text = "Hledám objekt$dots"
        searchHandler.postDelayed({ updateSearchingLabel() }, 500L)
    }

    private fun stopSearching() {
        searchHandler.removeCallbacksAndMessages(null)
    }

        private fun logActivity(msg: String) {
        // Unified session log line (no extra preview/activity log file).
        // S,<ts_ms>,<message>
        val tsMs = System.currentTimeMillis()
        val clean = msg.replace("\n", " ").replace("\r", " ").trim()
        val escaped = "\"" + clean.replace("\"", "\"\"") + "\""
        PublicLogWriter.appendLogLine(this, SessionLogFile.fileName, "S,$tsMs,$escaped")
    }
