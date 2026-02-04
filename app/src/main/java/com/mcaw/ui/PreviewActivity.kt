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
import java.util.concurrent.Executors

class PreviewActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var analyzer: DetectionAnalyzer
    private lateinit var speedMonitor: SpeedMonitor
    private lateinit var txtDetectionLabel: TextView
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searching = true
    private var searchDots = 0
    private var activityLogFileName: String = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return
            if (i.getBooleanExtra("clear", false)) {
                overlay.box = null
                overlay.distance = -1f
                overlay.speed = -1f
                overlay.objectSpeed = -1f
                overlay.ttc = -1f
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
            overlay.speed = i.getFloatExtra("speed", -1f)
            overlay.objectSpeed = i.getFloatExtra("object_speed", -1f)
            overlay.ttc = i.getFloatExtra("ttc", -1f)
            val label = i.getStringExtra("label")?.ifBlank { null } ?: "neznámý objekt"
            val mapped = mapLabel(label)
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
        speedMonitor = SpeedMonitor(this)
        activityLogFileName = "mcaw_activity_${sessionStamp()}.txt"
        overlay.showTelemetry = AppPreferences.debugOverlay
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
        val eff = runCatching { EfficientDetTFLiteDetector(this, "efficientdet_lite0.tflite") }
            .getOrNull()
        if (yolo == null && eff == null) {
            txtDetectionLabel.text = "Detekce: nelze načíst modely"
            logActivity("models_failed")
        } else {
            logActivity("models_loaded yolo=${yolo != null} efficient=${eff != null}")
        }
        analyzer = DetectionAnalyzer(this, yolo, eff)
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

    override fun onDestroy() {
        unregisterReceiver(receiver)
        speedMonitor.stop()
        stopSearching()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        overlay.showTelemetry = AppPreferences.debugOverlay
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            speedMonitor.start()
        }
        startSearching()
    }

    override fun onStop() {
        speedMonitor.stop()
        stopSearching()
        super.onStop()
    }

    private fun mapLabel(label: String): String {
        return when (label.lowercase()) {
            "car", "auto", "vehicle" -> "auto"
            "motorcycle", "motorbike", "bike", "motorka" -> "motorka"
            "truck", "lorry", "nákladák", "nakladak" -> "nákladák"
            "van", "dodavka", "dodávka" -> "dodávka"
            "bus" -> "autobus"
            "unknown" -> "neznámý objekt"
            else -> label
        }
    }

    private fun updateCameraCalibration(camera: androidx.camera.core.Camera) {
        runCatching {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val focalLengths =
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val focalMm = focalLengths?.firstOrNull()
            val sensorHeightMm = sensorSize?.height
            AppPreferences.cameraFocalLengthMm = focalMm ?: Float.NaN
            AppPreferences.cameraSensorHeightMm = sensorHeightMm ?: Float.NaN
            logActivity(
                "camera_calibration id=$cameraId" +
                    " focal_mm=${focalMm ?: "?"}" +
                    " sensor_mm=${sensorSize?.width ?: "?"}x${sensorSize?.height ?: "?"}" +
                    " array_px=${sensorArray?.width ?: "?"}x${sensorArray?.height ?: "?"}"
            )
        }.onFailure { err ->
            logActivity("camera_calibration_failed ${err.javaClass.simpleName}:${err.message}")
        }
    }

    private fun startSearching() {
        searchHandler.post(searchRunnable)
    }

    private fun stopSearching() {
        searchHandler.removeCallbacksAndMessages(null)
    }

    private fun updateSearchingLabel() {
        val dots = ".".repeat(searchDots)
        txtDetectionLabel.text = "Hledám objekt$dots"
    }

    private val searchRunnable = object : Runnable {
        override fun run() {
            if (searching) {
                searchDots = (searchDots + 1) % 4
                updateSearchingLabel()
            }
            searchHandler.postDelayed(this, 500L)
        }
    }

    private fun logActivity(message: String) {
        val timestamp =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(System.currentTimeMillis())
        val content = "ts=$timestamp $message"
        com.mcaw.util.PublicLogWriter.appendLogLine(this, activityLogFileName, content)
    }

    private fun sessionStamp(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(System.currentTimeMillis())
    }
}
