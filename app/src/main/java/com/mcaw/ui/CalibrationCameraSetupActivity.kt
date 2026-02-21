package com.mcaw.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wizard step: camera setup (zoom + lane center guide + ROI trapezoid).
 *
 * Rules:
 * - Live camera (no black screens).
 * - Updates AppPreferences (draft). Final profile save happens in CalibrationWizardActivity.
 */
class CalibrationCameraSetupActivity : ComponentActivity() {

    companion object {
        private const val REQ_CAMERA = 11
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var sliderZoom: Slider
    private lateinit var txtZoomValue: TextView
    private lateinit var sliderGuide: Slider
    private lateinit var txtGuideValue: TextView
    private lateinit var btnDone: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var boundCamera: Camera? = null

    // Executor is only for CameraX internals here (no frame analysis).
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.ensureInit(this)

        setContentView(R.layout.activity_calibration_camera_setup)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        sliderZoom = findViewById(R.id.sliderZoom)
        txtZoomValue = findViewById(R.id.txtZoomValue)
        sliderGuide = findViewById(R.id.sliderGuide)
        txtGuideValue = findViewById(R.id.txtGuideValue)
        btnDone = findViewById(R.id.btnDone)
        btnCancel = findViewById(R.id.btnCancel)

        // Overlay setup: show ROI + guide, allow edit.
        overlay.showTelemetry = false
        overlay.roiEditMode = true
        overlay.showGuideLine = true

        val roi = AppPreferences.getRoiTrapezoidNormalized()
        overlay.roiTopY = roi.topY
        overlay.roiBottomY = roi.bottomY
        overlay.roiTopHalfW = roi.topHalfW
        overlay.roiBottomHalfW = roi.bottomHalfW
        overlay.roiCenterX = roi.centerX
        overlay.guideXNormalized = roi.centerX

        overlay.onRoiChanged = { topY, bottomY, topHalfW, bottomHalfW, centerX, isFinal ->
            // Keep overlay + guide in sync. CenterX acts as lane center.
            overlay.guideXNormalized = centerX
            // Always write to prefs: this is wizard draft; cost is tiny (UI only).
            AppPreferences.setRoiTrapezoidNormalized(topY, bottomY, topHalfW, bottomHalfW, centerX)
        }

        overlay.setOnLongClickListener {
            AppPreferences.resetRoiToDefault()
            val r = AppPreferences.getRoiTrapezoidNormalized()
            overlay.roiTopY = r.topY
            overlay.roiBottomY = r.bottomY
            overlay.roiTopHalfW = r.topHalfW
            overlay.roiBottomHalfW = r.bottomHalfW
            overlay.roiCenterX = r.centerX
            overlay.guideXNormalized = r.centerX
            sliderGuide.value = r.centerX
            updateGuideText(r.centerX)
            true
        }
        overlay.isLongClickable = true

        // Zoom
        sliderZoom.value = AppPreferences.cameraZoomRatio
        updateZoomText(sliderZoom.value)
        sliderZoom.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            AppPreferences.cameraZoomRatio = v
            updateZoomText(v)
            boundCamera?.cameraControl?.setZoomRatio(v)
        }

        // Guide (lane center) -> uses same value as ROI centerX (keeps ego-path signals consistent).
        sliderGuide.value = roi.centerX
        updateGuideText(sliderGuide.value)
        sliderGuide.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val cx = v.coerceIn(0f, 1f)
            overlay.guideXNormalized = cx
            overlay.roiCenterX = cx
            AppPreferences.setRoiTrapezoidNormalized(
                overlay.roiTopY, overlay.roiBottomY, overlay.roiTopHalfW, overlay.roiBottomHalfW, cx
            )
            updateGuideText(cx)
        }

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnDone.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }

        ensureCameraPermissionAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }

    private fun ensureCameraPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) startCamera() else finish()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            previewUseCase = preview

            boundCamera = provider.bindToLifecycle(this, selector, preview)
            boundCamera?.cameraControl?.setZoomRatio(AppPreferences.cameraZoomRatio)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateZoomText(v: Float) {
        txtZoomValue.text = "Zoom: %.2f×".format(v)
    }

    private fun updateGuideText(v: Float) {
        txtGuideValue.text = "Střed jízdy: %.3f".format(v)
    }
}
