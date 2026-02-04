package com.mcaw.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.ai.EfficientDetTFLiteDetector
import com.mcaw.ai.YoloOnnxDetector
import com.mcaw.config.AppPreferences
import java.util.concurrent.Executors
class McawService : LifecycleService() {

    companion object {
        const val ACTION_START_ANALYSIS = "com.mcaw.action.START_ANALYSIS"
        const val ACTION_STOP_ANALYSIS = "com.mcaw.action.STOP_ANALYSIS"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var analyzer: DetectionAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor = Executors.newSingleThreadExecutor()
    private var analysisRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        startForegroundNotification()
        isRunning = true
    }

    private fun startForegroundNotification() {
        val channelId = "mcaw_fg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                "MCAW Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MCAW běží")
            .setContentText("Detekce aktivní...")
            .setOngoing(true)
            .build()

        startForeground(1, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP_ANALYSIS -> stopCameraAnalysis()
            ACTION_START_ANALYSIS, null -> startCameraAnalysis()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCameraAnalysis()
        analysisExecutor.shutdown()
        isRunning = false
        super.onDestroy()
    }

    private fun startCameraAnalysis() {
        if (analysisRunning) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val yolo = runCatching { YoloOnnxDetector(this, "yolov8n.onnx") }.getOrNull()
        val eff = runCatching { EfficientDetTFLiteDetector(this, "efficientdet_lite0.tflite") }
            .getOrNull()
        if (yolo == null && eff == null) {
            return
        }
        analysisRunning = true
        analyzer = DetectionAnalyzer(this, yolo, eff)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(analysisExecutor, analyzer!!)
                }
            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )
            updateCameraCalibration(camera)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraAnalysis() {
        analysisRunning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun updateCameraCalibration(camera: androidx.camera.core.Camera) {
        runCatching {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val focalLengths =
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalMm = focalLengths?.firstOrNull()
            val sensorHeightMm = sensorSize?.height
            AppPreferences.cameraFocalLengthMm = focalMm ?: Float.NaN
            AppPreferences.cameraSensorHeightMm = sensorHeightMm ?: Float.NaN
        }
    }
}
