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
import java.util.concurrent.Executors

class PreviewActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var analyzer: DetectionAnalyzer
    private lateinit var speedProvider: SpeedProvider
    private lateinit var speedMonitor: SpeedMonitor
    private lateinit var txtDetectionLabel: TextView
    private lateinit var btnRoi: TextView

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searching = true
    private var searchDots = 0
    private var activityLogFileName: String = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return

            // ROI always (even on clear)
            if (i.hasExtra("roi_left_n")) {
                overlay.roiLeftN = i.getFloatExtra("roi_left_n", overlay.roiLeftN)
                overlay.roiTopN = i.getFloatExtra("roi_top_n", overlay.roiTopN)
                overlay.roiRightN = i.getFloatExtra("roi_right_n", overlay.roiRightN)
                overlay.roiBottomN = i.getFloatExtra("roi_bottom_n", overlay.roiBottomN)
            }

            if (i.getBooleanExtra("clear", false)) {
                overlay.box = null
                overlay.distance = -1f
                overlay.speed = -1f
                overlay.objectSpeed = -1f
                overlay.riderSpeed = -1f
                overlay.ttc = -1f
                overlay.label = ""
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
        setContentView(R.layout.activity_preview)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        val txtPreviewBuild = findViewById<TextView>(R.id.txtPreviewBuild)
        txtDetectionLabel = findViewById(R.id.txtDetectionLabel)
        btnRoi = findViewById(R.id.btnRoi)

        speedProvider = SpeedProvider(this)
        speedMonitor = SpeedMonitor(speedProvider)
        activityLogFileName = "mcaw_activity_${sessionStamp()}.txt"

        overlay.showTelemetry = AppPreferences.debugOverlay

        applyRoiFromPrefs()
        overlay.onRoiChanged = { l, t, r, b, isFinal ->
            if (isFinal) {
                AppPreferences.setRoiNormalized(l, t, r, b)
                logActivity("roi_set l=$l t=$t r=$r b=$b")
            }
        }

        btnRoi.setOnClickListener {
            overlay.roiEditMode = !overlay.roiEditMode
            btnRoi.text = if (overlay.roiEditMode) "ROI: UPRAVIT ✓" else "ROI: UPRAVIT"
            if (!overlay.roiEditMode) {
                applyRoiFromPrefs()
            }
        }

        btnRoi.setOnLongClickListener {
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

    private fun applyRoiFromPrefs() {
        val roi = AppPreferences.getRoiNormalized()
        overlay.roiLeftN = roi.left
        overlay.roiTopN = roi.top
        overlay.roiRightN = roi.right
        overlay.roiBottomN = roi.bottom
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
            provider.unbindAll()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
                }

            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            updateCameraCalibration(camera)
        }, ContextCompat.getMainExecutor(this))
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
        unregisterReceiver(receiver)
        speedMonitor.stop()
        stopSearching()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        speedMonitor.start()
    }

    override fun onStop() {
        speedMonitor.stop()
        super.onStop()
    }

    private fun updateSearchingLabel() {
        val status = findViewById<TextView>(R.id.txtPreviewStatus)
        if (!searching) {
            status.text = "Živý náhled aktivní"
            stopSearching()
            return
        }
        searchDots = (searchDots + 1) % 4
        val dots = ".".repeat(searchDots)
        status.text = "Hledám objekt$dots"
        searchHandler.postDelayed({ updateSearchingLabel() }, 500L)
    }

    private fun stopSearching() {
        searchHandler.removeCallbacksAndMessages(null)
    }

    private fun logActivity(msg: String) {
        try {
            com.mcaw.util.PublicLogWriter.appendLogLine(this, activityLogFileName, msg)
        } catch (_: Exception) {
        }
    }

    private fun sessionStamp(): String = System.currentTimeMillis().toString()
}
