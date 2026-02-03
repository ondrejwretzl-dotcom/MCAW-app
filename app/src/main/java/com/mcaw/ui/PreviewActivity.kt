package com.mcaw.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.mcaw.app.R
import java.util.concurrent.Executors

class PreviewActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var analyzer: DetectionAnalyzer

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return

            overlay.box = com.mcaw.model.Box(
                i.getFloatExtra("left", 0f),
                i.getFloatExtra("top", 0f),
                i.getFloatExtra("right", 0f),
                i.getFloatExtra("bottom", 0f)
            )
            overlay.distance = i.getFloatExtra("dist", -1f)
            overlay.speed = i.getFloatExtra("speed", -1f)
            overlay.ttc = i.getFloatExtra("ttc", -1f)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)

        registerReceiver(receiver, IntentFilter("MCAW_DEBUG_UPDATE"))

        if (hasCameraPermission()) {
            initAndStart()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                2001
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initAndStart()
        } else {
            finish() // bez kamery nemá smysl pokraèovat
        }
    }

    private fun initAndStart() {
        val yolo = YoloOnnxDetector(this, "yolov8n.onnx")
        val eff = EfficientDetTFLiteDetector(this, "efficientdet_lite0.tflite")
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
        super.onDestroy()
    }
}

