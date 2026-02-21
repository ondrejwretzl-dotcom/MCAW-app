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
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.google.android.material.slider.Slider
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
import com.mcaw.config.ProfileManager
import com.mcaw.config.CalibrationHealth
import com.mcaw.location.SpeedMonitor
import com.mcaw.location.SpeedProvider
import com.mcaw.util.LabelMapper
import com.mcaw.util.ReasonTextMapper
import com.mcaw.util.PublicLogWriter
import com.mcaw.util.SessionLogFile
import com.mcaw.util.SessionActivityLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_WIZARD_MODE = "extra_wizard_mode"
        const val EXTRA_WIZARD_ROI = "extra_wizard_roi"
    }


    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private var analyzer: DetectionAnalyzer? = null
    private lateinit var speedProvider: SpeedProvider
    private lateinit var speedMonitor: SpeedMonitor
    private lateinit var txtDetectionLabel: TextView
    private lateinit var btnRoi: TextView
    private lateinit var txtPreviewStatus: TextView
    private lateinit var txtActiveProfile: TextView
    private lateinit var txtCalibrationHealth: TextView
    private lateinit var btnSaveProfile: TextView

    // Wizard-only UI (ROI + guide line)
    private var wizardMode: Boolean = false
    private lateinit var sliderGuide: Slider
    private lateinit var txtWizardHint: TextView
    private lateinit var btnWizardDone: TextView
    private lateinit var btnWizardCancel: TextView

    // snapshot to restore on cancel
    private var snapRoiTopY: Float = Float.NaN
    private var snapRoiBottomY: Float = Float.NaN
    private var snapRoiTopHalfW: Float = Float.NaN
    private var snapRoiBottomHalfW: Float = Float.NaN
    private var snapRoiCenterX: Float = Float.NaN

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searching = true
    private var searchDots = 0

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isCameraBound: Boolean = false
    private var speedPausedByEdit: Boolean = false
    private var boundCamera: androidx.camera.core.Camera? = null

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
                overlay.roiMinDistM = Float.NaN
                overlay.roiBottomTouch = false
                overlay.speed = -1f
                overlay.objectSpeed = -1f
                overlay.riderSpeed = -1f
                overlay.riderSpeedSourceOrdinal = 0
                overlay.riderSpeedConfidence = 0f
                overlay.riderSpeedAgeMs = 0L
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
            val h = CalibrationHealth.evaluate()
            overlay.distance = if (h.distanceReliable) i.getFloatExtra("dist", -1f) else Float.NaN
            overlay.roiMinDistM = i.getFloatExtra("roi_min_dist_m", Float.NaN)
            overlay.roiBottomTouch = i.getBooleanExtra("roi_bottom_touch", false)
            overlay.speed = i.getFloatExtra("speed", -1f) // REL (approach)
            overlay.objectSpeed = i.getFloatExtra("object_speed", -1f) // OBJ
            overlay.riderSpeed = i.getFloatExtra("rider_speed", -1f) // RID
            overlay.riderSpeedSourceOrdinal = i.getIntExtra("rider_speed_src", 0)
            overlay.riderSpeedConfidence = i.getFloatExtra("rider_speed_conf", 0f)
            overlay.riderSpeedAgeMs = i.getLongExtra("rider_speed_age_ms", 0L)
            overlay.ttc = i.getFloatExtra("ttc", -1f)
            overlay.alertLevel = i.getIntExtra("alert_level", 0)
            val legacyReason = i.getStringExtra("alert_reason") ?: ""
            val reasonBits = i.getIntExtra("reason_bits", 0)
            overlay.alertReason = ReasonTextMapper.shortOrFallback(reasonBits, legacyReason)
            overlay.riskScore = i.getFloatExtra("risk_score", Float.NaN)
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
        ProfileManager.ensureInit(this)
        // Apply active profile (if any) before we load ROI and camera params.
        ProfileManager.applyActiveProfileToPreferences()
        AppPreferences.previewActive = true

        setContentView(R.layout.activity_preview)

        wizardMode = intent.getBooleanExtra(EXTRA_WIZARD_MODE, false)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        val txtPreviewBuild = findViewById<TextView>(R.id.txtPreviewBuild)
        txtDetectionLabel = findViewById(R.id.txtDetectionLabel)
        btnRoi = findViewById(R.id.btnRoi)
        txtPreviewStatus = findViewById(R.id.txtPreviewStatus)
        txtActiveProfile = findViewById(R.id.txtActiveProfile)
        txtCalibrationHealth = findViewById(R.id.txtCalibrationHealth)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)

        sliderGuide = findViewById(R.id.sliderGuide)
        txtWizardHint = findViewById(R.id.txtWizardHint)
        btnWizardDone = findViewById(R.id.btnWizardDone)
        btnWizardCancel = findViewById(R.id.btnWizardCancel)


        speedProvider = SpeedProvider(this)
        speedMonitor = SpeedMonitor(speedProvider)
        SessionLogFile.init(this)

        overlay.showTelemetry = AppPreferences.debugOverlay
        updateActiveProfileLabel()

        applyRoiFromPrefs()
        overlay.onRoiChanged = { topY, bottomY, topHalfW, bottomHalfW, centerX, isFinal ->
            if (isFinal) {
                AppPreferences.setRoiTrapezoidNormalized(topY, bottomY, topHalfW, bottomHalfW, centerX = centerX)
                logActivity("roi_set topY=$topY bottomY=$bottomY topHalfW=$topHalfW bottomHalfW=$bottomHalfW centerX=$centerX")
                applyZoomAndFocusIfPossible(reason = "roi")
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

        btnSaveProfile.setOnClickListener {
            showSaveProfileDialog()
        }

                if (wizardMode) {
            setupWizardMode()
            return
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
        if (wizardMode) {
            // Wizard ROI mode: no detection models, preview-only.
            analyzer = null
            startCamera()
            return
        }
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
        boundCamera = camera
        updateCameraCalibration(camera)
        applyZoomAndFocusIfPossible(reason = "bind_preview")
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
     * This is lightweight and safe to call repeatedly.
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
        updateCalibrationHealthUi()
        if (!wizardMode && !overlay.roiEditMode) {
            speedMonitor.start()
        }
    }

    override fun onStop() {
        if (!wizardMode) {
            speedMonitor.stop()
        }
        super.onStop()
    }

    private fun updateCalibrationHealthUi() {
        val h = com.mcaw.config.CalibrationHealth.evaluate()
        if (h.bannerText.isBlank()) {
            txtCalibrationHealth.visibility = View.GONE
            txtCalibrationHealth.text = ""
        } else {
            txtCalibrationHealth.visibility = View.VISIBLE
            txtCalibrationHealth.text = h.bannerText
        }
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
        SessionActivityLogger.log(msg)
    }


    private fun updateActiveProfileLabel() {
        val id = ProfileManager.getActiveProfileIdOrNull()
        val name = if (id == null) {
            "Default"
        } else {
            ProfileManager.findById(id)?.name ?: "?"
        }
        txtActiveProfile.text = "Profil: $name"
    }

    private fun setupWizardMode() {
        // Wizard ROI mode: no analyzer, no speed monitor, no profile saving UI.
        txtPreviewStatus.text = "Nastavení směru jízdy + ROI"
        txtDetectionLabel.visibility = android.view.View.GONE
        btnSaveProfile.visibility = android.view.View.GONE

        // Snapshot current ROI to restore on cancel.
        snapRoiTopY = AppPreferences.roiTrapTopY
        snapRoiBottomY = AppPreferences.roiTrapBottomY
        snapRoiTopHalfW = AppPreferences.roiTrapTopHalfW
        snapRoiBottomHalfW = AppPreferences.roiTrapBottomHalfW
        snapRoiCenterX = AppPreferences.roiTrapCenterX

        // Enable ROI edit + guide line.
        overlay.showGuideLine = true
        overlay.guideXNormalized = AppPreferences.roiTrapCenterX
        sliderGuide.valueFrom = 0f
        sliderGuide.valueTo = 1f
        sliderGuide.stepSize = 0f
        sliderGuide.value = overlay.guideXNormalized
        sliderGuide.visibility = android.view.View.VISIBLE
        txtWizardHint.visibility = android.view.View.VISIBLE
        txtWizardHint.text = "Posuň čáru na osu pruhu. Pak uprav trapezoid."

        btnWizardDone.visibility = android.view.View.VISIBLE
        btnWizardCancel.visibility = android.view.View.VISIBLE

        // Start in edit mode so user can draw trapezoid.
        setRoiEditMode(true)

        sliderGuide.addOnChangeListener { _, value, _ ->
            overlay.guideXNormalized = value
        }

        btnWizardDone.setOnClickListener {
            // Commit guide into ROI center (yaw proxy) and exit edit mode to store final ROI.
            AppPreferences.roiTrapCenterX = overlay.guideXNormalized
            overlay.roiCenterX = overlay.guideXNormalized
            setRoiEditMode(false)
            setResult(RESULT_OK)
            finish()
        }

        btnWizardCancel.setOnClickListener {
            // Restore snapshot (best-effort).
            AppPreferences.roiTrapTopY = snapRoiTopY
            AppPreferences.roiTrapBottomY = snapRoiBottomY
            AppPreferences.roiTrapTopHalfW = snapRoiTopHalfW
            AppPreferences.roiTrapBottomHalfW = snapRoiBottomHalfW
            AppPreferences.roiTrapCenterX = snapRoiCenterX
            applyRoiFromPrefs()

            setResult(RESULT_CANCELED)
            finish()
        }

        // Ensure we only bind preview.
        bindPreviewOnly()
    }

private fun showSaveProfileDialog() {
        val activeId = ProfileManager.getActiveProfileIdOrNull()
        if (!activeId.isNullOrBlank()) {
            val activeName = ProfileManager.findById(activeId)?.name ?: "?"
            val actions = arrayOf(
                "Přepsat aktivní profil: $activeName",
                "Uložit jako nový profil"
            )
            AlertDialog.Builder(this)
                .setTitle("Uložit profil")
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> {
                            val updated = ProfileManager.overwriteProfileFromCurrentPrefs(activeId)
                            if (updated == null) {
                                Toast.makeText(this, "Profil nebyl nalezen", Toast.LENGTH_SHORT).show()
                                logActivity("profile_overwrite_failed id=$activeId")
                            } else {
                                ProfileManager.setActiveProfileId(updated.id)
                                updateActiveProfileLabel()
                                Toast.makeText(this, "Profil přepsán: ${updated.name}", Toast.LENGTH_SHORT).show()
                                logActivity("profile_overwritten id=${updated.id} name=${updated.name}")
                            }
                        }
                        1 -> showSaveNewProfileDialog()
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
            return
        }

        showSaveNewProfileDialog()
    }

    private fun showSaveNewProfileDialog() {
        val input = EditText(this).apply {
            hint = "Název profilu"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Uložit profil")
            .setMessage("Uloží aktuální ROI + kalibraci (výška, sklon, zoom, distance scale, lane offset).")
            .setView(input)
            .setPositiveButton("Uložit") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                val p = ProfileManager.saveProfileFromCurrentPrefs(name)
                ProfileManager.setActiveProfileId(p.id)
                updateActiveProfileLabel()
                Toast.makeText(this, "Profil uložen: ${p.name}", Toast.LENGTH_SHORT).show()
                logActivity("profile_saved id=${p.id} name=${p.name}")
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

}
