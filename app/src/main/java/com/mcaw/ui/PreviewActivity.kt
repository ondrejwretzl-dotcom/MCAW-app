package com.mcaw.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return
            if (i.getBooleanExtra("clear", false)) {
                overlay.box = null
                overlay.distance = -1f
                overlay.speed = -1f
                overlay.objectSpeed = -1f
                overlay.ttc = -1f
                txtDetectionLabel.text = "Detekce: žádný objekt"
                return
            }
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
            txtDetectionLabel.text = "Detekce: ${mapLabel(label)}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        val txtPreviewBuild = findViewById<TextView>(R.id.txtPreviewBuild)
        txtDetectionLabel = findViewById(R.id.txtDetectionLabel)
        speedMonitor = SpeedMonitor(this)
        overlay.showTelemetry = AppPreferences.debugOverlay
        txtPreviewBuild.text =
            "MCAW ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_ID}"
        txtDetectionLabel.text = "Detekce: --"

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
            initAndStart()
        } else {
            finish()
        }
    }

    private fun initAndStart() {
        val yolo = runCatching { YoloOnnxDetector(this, "yolov8n.onnx") }.getOrNull()
        val eff = runCatching { EfficientDetTFLiteDetector(this, "efficientdet_lite0.tflite") }
            .getOrNull()
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

            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        speedMonitor.stop()
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
    }

    override fun onStop() {
        speedMonitor.stop()
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
}
