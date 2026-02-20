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
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MeteringPoint
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.ai.EfficientDetTFLiteDetector
import com.mcaw.ai.YoloOnnxDetector
import com.mcaw.config.AppPreferences
import com.mcaw.config.ProfileManager
import com.mcaw.location.SpeedMonitor
import com.mcaw.location.SpeedProvider
import com.mcaw.util.PublicLogWriter
import com.mcaw.util.SessionLogFile
import com.mcaw.util.SessionActivityLogger
import com.mcaw.util.LogContract
import com.mcaw.util.TraceContract
import android.view.WindowManager
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McawService : LifecycleService() {

    companion object {
        const val ACTION_START_ANALYSIS = "com.mcaw.action.START_ANALYSIS"
        const val ACTION_STOP_ANALYSIS = "com.mcaw.action.STOP_ANALYSIS"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var analyzer: DetectionAnalyzer? = null
    private lateinit var speedProvider: SpeedProvider
    private lateinit var speedMonitor: SpeedMonitor
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor = Executors.newSingleThreadExecutor()
    private var analysisRunning = false

    @Volatile
    private var analysisDesired = false
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryAttempts = 0
    private var cameraLifecycleOwner: ServiceCameraLifecycleOwner? = null

    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        // Apply active mount profile (if any) for background service sessions.
        ProfileManager.ensureInit(this)
        ProfileManager.applyActiveProfileToPreferences()
        initUnifiedSessionLog()
        speedProvider = SpeedProvider(this)
        speedMonitor = SpeedMonitor(speedProvider)
        startForegroundNotification()
        isRunning = true
        logService("service_create")
    }

    
    private fun initUnifiedSessionLog() {
        // One unified session file per app use (process lifetime).
        SessionLogFile.init(this)

        // Initialize off-thread activity logger for sparse events (no IO on UI thread).
        SessionActivityLogger.init(this, SessionLogFile.fileName)

        // Log effective profile snapshot at session start (audit).
        val activeId = run { ProfileManager.ensureInit(this); ProfileManager.getActiveProfileIdOrNull() }
        val activeName = if (activeId != null) ProfileManager.getProfileNameById(activeId) else null
        val roi = AppPreferences.getRoiTrapezoidNormalized()
                SessionActivityLogger.log(
            "session_start active_profile_id=${activeId ?: "none"} " +
                "active_profile_name=${activeName ?: "none"} " +
                "cam_h=${AppPreferences.cameraMountHeightM} " +
                "pitch_deg=${AppPreferences.cameraPitchDownDeg} " +
                "dist_scale=${AppPreferences.distanceScale} " +
                "roi_center_x=${roi.centerX} roi_top_y=${roi.topY} roi_bottom_y=${roi.bottomY} " +
                "roi_top_halfw=${roi.topHalfW} roi_bottom_halfw=${roi.bottomHalfW} " +
                "cal_rms=${AppPreferences.calibrationRmsM} cal_max=${AppPreferences.calibrationMaxErrM} cal_imu_std=${AppPreferences.calibrationImuStdDeg}"
        )
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

        // Do not use ServiceInfo types here; some build environments lack the symbol.
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

        analyzer = DetectionAnalyzer(this, yolo, eff, speedProvider)

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
                applyZoomAndFocusFromPrefs(camera)
                speedMonitor.start()
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
        if (!analysisRunning && cameraProvider == null) {
            // Still release analyzer resources if present (IMU, in-app alerting).
            analyzer?.shutdown()
            analyzer = null
            return
        }

        retryHandler.removeCallbacksAndMessages(null)
        analysisDesired = false
        analysisRunning = false

        cameraProvider?.unbindAll()
        cameraProvider = null

        analyzer?.shutdown()
        analyzer = null

        speedMonitor.stop()

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

    /**
     * Autofocus is guided by ROI (user intent). Zoom ratio is applied from prefs.
     * This is best-effort and never blocks camera startup.
     */
    private fun applyZoomAndFocusFromPrefs(camera: androidx.camera.core.Camera) {
        // Zoom
        runCatching {
            camera.cameraControl.setZoomRatio(AppPreferences.cameraZoomRatio.coerceIn(1.0f, 2.0f))
        }

        // Focus point derived from ROI trapezoid. In service we don't have a PreviewView,
        // so use DisplayOrientedMeteringPointFactory best-effort.
        val roi = AppPreferences.getRoiTrapezoidNormalized()
        val xNorm = roi.centerX.coerceIn(0.05f, 0.95f)
        val yNorm = (roi.topY + 0.35f * (roi.bottomY - roi.topY)).coerceIn(0.05f, 0.95f)

        runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val display = wm?.defaultDisplay
            val dm = resources.displayMetrics
            if (display == null || dm.widthPixels <= 0 || dm.heightPixels <= 0) return@runCatching

            val factory = DisplayOrientedMeteringPointFactory(display, camera.cameraInfo, dm.widthPixels, dm.heightPixels)
            val xPx = xNorm * dm.widthPixels
            val yPx = yNorm * dm.heightPixels
            val p: MeteringPoint = factory.createPoint(xPx, yPx)
            val action = FocusMeteringAction.Builder(p)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            camera.cameraControl.startFocusAndMetering(action)

            logService(
                "cam_focus_roi svc x=${String.format(Locale.US, "%.3f", xNorm)} y=${String.format(Locale.US, "%.3f", yNorm)} z=${String.format(Locale.US, "%.2f", AppPreferences.cameraZoomRatio)}"
            )
        }
    }

    private fun logService(message: String) {
        val tsMs = System.currentTimeMillis()
        // Service events in unified session log: S,<ts_ms>,<message>
        PublicLogWriter.appendLogLine(this, SessionLogFile.fileName, "S,$tsMs,$message")
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
