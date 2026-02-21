package com.mcaw.ui

import android.Manifest
import android.content.pm.PackageManager
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.mcaw.app.R
import com.mcaw.config.AppPreferences

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.ensureInit(this)

        setContentView(R.layout.activity_calibration_camera_setup)

        previewView = findViewById(R.id.previewView)
        // Match runtime preview behaviour for device compatibility.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

        overlay.onRoiChanged = { topY, bottomY, topHalfW, bottomHalfW, centerX, _ ->
            // Keep overlay + guide in sync. CenterX acts as lane center.
            overlay.guideXNormalized = centerX
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
            sliderGuide.value = snapToSliderStep(r.centerX, sliderGuide.valueFrom, sliderGuide.valueTo, sliderGuide.stepSize)
            updateGuideText(sliderGuide.value)
            true
        }
        overlay.isLongClickable = true

        // Zoom
        sliderZoom.value = snapToSliderStep(
            AppPreferences.cameraZoomRatio,
            sliderZoom.valueFrom,
            sliderZoom.valueTo,
            sliderZoom.stepSize
        )
        updateZoomText(sliderZoom.value)
        sliderZoom.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val snapped = snapToSliderStep(v, sliderZoom.valueFrom, sliderZoom.valueTo, sliderZoom.stepSize)
            AppPreferences.cameraZoomRatio = snapped
            updateZoomText(snapped)
            boundCamera?.cameraControl?.setZoomRatio(snapped)
        }

        // Guide (lane center) -> uses same value as ROI centerX (keeps ego-path signals consistent).
        sliderGuide.value = snapToSliderStep(roi.centerX, sliderGuide.valueFrom, sliderGuide.valueTo, sliderGuide.stepSize)
        updateGuideText(sliderGuide.value)
        sliderGuide.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val cx = snapToSliderStep(v, sliderGuide.valueFrom, sliderGuide.valueTo, sliderGuide.stepSize)
            overlay.guideXNormalized = cx
            overlay.roiCenterX = cx
            AppPreferences.setRoiTrapezoidNormalized(
                overlay.roiTopY,
                overlay.roiBottomY,
                overlay.roiTopHalfW,
                overlay.roiBottomHalfW,
                cx
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
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                // Ensure PreviewView has a valid surface before binding.
                previewView.post {
                    try {
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        previewUseCase = preview

                        boundCamera = provider.bindToLifecycle(this, selector, preview)
                        boundCamera?.cameraControl?.setZoomRatio(
                            snapToSliderStep(
                                AppPreferences.cameraZoomRatio,
                                sliderZoom.valueFrom,
                                sliderZoom.valueTo,
                                sliderZoom.stepSize
                            )
                        )
                    } catch (t: Throwable) {
                        showCameraErrorAndFinish(t)
                    }
                }
            } catch (t: Throwable) {
                showCameraErrorAndFinish(t)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showCameraErrorAndFinish(t: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Kamera nelze spustit")
            .setMessage(
                "Nepodařilo se spustit náhled kamery pro kalibraci.\n\n" +
                    (t.message ?: t.javaClass.simpleName)
            )
            .setPositiveButton("Zavřít") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun snapToSliderStep(value: Float, from: Float, to: Float, step: Float): Float {
        val clamped = value.coerceIn(from, to)
        if (step <= 0f) return clamped

        val lowerStepIndex = ((clamped - from) / step).toInt().toFloat()
        val lower = (from + lowerStepIndex * step).coerceIn(from, to)
        val upper = (lower + step).coerceIn(from, to)

        return if ((clamped - lower) <= (upper - clamped)) lower else upper
    }

    private fun updateZoomText(v: Float) {
        txtZoomValue.text = "Zoom: %.2f×".format(v)
    }

    private fun updateGuideText(v: Float) {
        txtGuideValue.text = "Střed jízdy: %.3f".format(v)
    }
}
