package com.mcaw.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.ai.EfficientDetTFLiteDetector
import com.mcaw.ai.YoloOnnxDetector
import com.mcaw.config.AppPreferences
import com.mcaw.util.PublicLogWriter
import java.util.Locale
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
    @Volatile
    private var analysisDesired = false
    private var serviceLogFileName: String = ""
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryAttempts = 0
    private var cameraLifecycleOwner: ServiceCameraLifecycleOwner? = null

    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        serviceLogFileName = "mcaw_service_${sessionStamp()}.txt"
        startForegroundNotification()
        isRunning = true
        logService("service_create")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1, notif)
        }
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
        logService("service_destroy")
        stopCameraAnalysis()
        analysisExecutor.shutdown()
        isRunning = false
        super.onDestroy()
    }

    private fun startCameraAnalysis() {
        if (analysisRunning) return
        retryHandler.removeCallbacksAndMessages(null)
        analysisDesired = true
        if (AppPreferences.previewActive) {
            logService("analysis_start_skipped preview_active")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            logService("analysis_start_denied missing_camera_permission")
            return
        }
        val yolo = runCatching { YoloOnnxDetector(this, "yolov8n.onnx") }.getOrNull()
        val eff = runCatching { EfficientDetTFLiteDetector(this, "efficientdet_lite0.tflite") }
            .getOrNull()
        if (yolo == null && eff == null) {
            logService("analysis_start_failed models_unavailable")
            return
        }
        analyzer = DetectionAnalyzer(this, yolo, eff)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                if (!analysisDesired) {
                    logService("analysis_start_ignored stale_request")
                    return@addListener
                }
                cameraProvider = provider
                provider.unbindAll()
                val lifecycleOwner = ServiceCameraLifecycleOwner().also {
                    it.start()
                    cameraLifecycleOwner = it
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(analysisExecutor, analyzer!!)
                    }
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
                updateCameraCalibration(camera)
                analysisRunning = true
                retryAttempts = 0
                logService("analysis_started")
            }.onFailure { err ->
                analysisRunning = false
                logService("analysis_start_failed ${err.javaClass.simpleName}:${err.message}")
                scheduleRetry()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraAnalysis() {
        if (!analysisRunning && cameraProvider == null) return
        retryHandler.removeCallbacksAndMessages(null)
        analysisDesired = false
        analysisRunning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer = null
        cameraLifecycleOwner?.stop()
        cameraLifecycleOwner = null
        logService("analysis_stopped")
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

    private fun logService(message: String) {
        val timestamp =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(System.currentTimeMillis())
        val content = "ts=$timestamp $message"
        PublicLogWriter.appendLogLine(this, serviceLogFileName, content)
    }

    private fun sessionStamp(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())
    }

    private fun scheduleRetry() {
        retryAttempts += 1
        val delayMs = (1000L * retryAttempts.coerceAtMost(5)).coerceAtMost(6000L)
        logService("analysis_retry_scheduled delay_ms=$delayMs attempt=$retryAttempts")
        retryHandler.postDelayed({ startCameraAnalysis() }, delayMs)
    }

    private class ServiceCameraLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun start() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
