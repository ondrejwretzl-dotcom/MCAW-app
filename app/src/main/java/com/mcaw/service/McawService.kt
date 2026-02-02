package com.mcaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import com.mcaw.app.R
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.ai.YoloOnnxDetector
import com.mcaw.ai.EfficientDetTFLiteDetector
import com.mcaw.config.AppPreferences
import java.util.concurrent.Executors

/**
 * McawService – finální verze
 * ---------------------------
 * - foreground service s kamerou
 * - načítá oba modely
 * - napojí DetectionAnalyzer
 * - běží i se zhaslou obrazovkou
 */
class McawService : Service() {

    private lateinit var yolo: YoloOnnxDetector
    private lateinit var eff: EfficientDetTFLiteDetector
    private lateinit var analyzer: DetectionAnalyzer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Inicializace nastavení
        AppPreferences.init(this)

        // Spuštění foreground notifikace
        startForegroundNotification()

        // Načtení obou modelů
        initModels()
        DetectionPhysics.focalLengthPx = computeFocalLengthPx()

        // Vytvoření analyzéru
        analyzer = DetectionAnalyzer(this, yolo, eff)

        // Start kamery
        startCamera()
    }

    // ---------------------------------------------------------
    //  NOTIFIKACE FOREGROUND SERVICE
    // ---------------------------------------------------------

    private fun startForegroundNotification() {
        val channelId = "mcaw_fg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                "MCAW",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("MCAW detekuje okolí")
            .setContentText("Služba běží…")
            .setOngoing(true)
            .build()

        startForeground(1, notif)
    }

    // ---------------------------------------------------------
    // MODEL LOADING
    // ---------------------------------------------------------

    private fun initModels() {
        yolo = YoloOnnxDetector(
            context = this,
            modelName = "yolov8n.onnx"
        )

        eff = EfficientDetTFLiteDetector(
            ctx = this,
            modelName = "efficientdet_lite0.tflite"
        )
    }

    // ---------------------------------------------------------
    // CAMERA + ANALYZER
    // ---------------------------------------------------------

    private fun startCamera() {

        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val executor = Executors.newSingleThreadExecutor()

            // Napoj detekční analyzér
            analysis.setAnalyzer(executor, analyzer)

            provider.unbindAll()

            provider.bindToLifecycle(
                androidx.lifecycle.ProcessLifecycleOwner.get(),
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )

        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun computeFocalLengthPx(): Float {
        val camManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val camId = camManager.cameraIdList.first()

        val chars = camManager.getCameraCharacteristics(camId)

        val focalLengths = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArray = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

        if (focalLengths == null || sensorSize == null || pixelArray == null)
            return 1000f // fallback

        val focalMm = focalLengths[0]
        val sensorWidthMm = sensorSize.width
        val imageWidthPx = pixelArray.width.toFloat()

        return (focalMm / sensorWidthMm) * imageWidthPx
    }
}

