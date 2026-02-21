package com.mcaw.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mcaw.ai.DetectionPhysics
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.util.SessionActivityLogger
import com.mcaw.config.ProfileManager
import com.mcaw.config.MountProfile
import androidx.camera.camera2.interop.Camera2CameraInfo
import java.util.concurrent.TimeUnit

/**
 * Camera distance calibration wizard (A1).
 *
 * Collects 3 ground points with known distances (3–6m, 7–12m, 13–20m),
 * fits (cameraHeightM, pitchDownDeg) for DetectionPhysics ground-plane model,
 * and then shows a final "sanity" step where the user verifies the estimated distance.
 */
class CalibrationActivity : ComponentActivity(), CalibrationOverlayView.Listener {

    companion object {
        const val EXTRA_MODE = "mcaw_calib_mode"
        const val MODE_FULL = "full"
        const val MODE_ZOOM_ONLY = "zoom_only"
    }


    private enum class Stage {
        INTRO,
        P1,
        P2,
        P3,
        RESULT,
        VERIFY
    }

    private enum class QualityLevel(val code: Int) {
        UNKNOWN(0),
        OK(1),
        UNCERTAIN(2),
        BAD(3)
    }

    private data class Sample(
        val xNorm: Float,
        val yNorm: Float,
        val distanceM: Float
    )

    private lateinit var previewView: PreviewView
    private lateinit var overlay: CalibrationOverlayView
    private lateinit var txtStep: TextView
    private lateinit var txtInstruction: TextView
    private lateinit var txtHint: TextView

    private lateinit var btnBack: com.google.android.material.button.MaterialButton
    private lateinit var btnConfirm: com.google.android.material.button.MaterialButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var boundCamera: Camera? = null

    private var zoomOnly: Boolean = false

    private lateinit var sliderZoom: Slider
    private lateinit var txtZoomValue: TextView

    private var stage: Stage = Stage.INTRO
    private val samples: ArrayList<Sample> = ArrayList(3)

    // Per-step IMU stability (std dev of tilt jitter) captured when user confirms the point.
    private val stepImuStdDeg: FloatArray = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)

    // Last fitted evaluation
    private var lastRmsM: Float = 0f
    private var lastMaxErrM: Float = 0f
    private var lastImuStdDeg: Float = 0f
    private var lastQuality: QualityLevel = QualityLevel.UNKNOWN
    // Split signals: geometry fit quality vs device stability quality.
    private var lastGeomQuality: QualityLevel = QualityLevel.UNKNOWN
    private var lastImuQuality: QualityLevel = QualityLevel.UNKNOWN
    // Conservative guidance numbers derived from IMU jitter.
    private var lastImuExtraErrAt10m: Float = 0f
    private var lastCombinedErrAt10m: Float = 0f
    private var worstStepIndex: Int = 0 // 0..2

    // Fitted values (preview only until user confirms save)
    private var fittedHeightM: Float = AppPreferences.cameraMountHeightM
    private var fittedPitchDeg: Float = AppPreferences.cameraPitchDownDeg

    // IMU sampler used only for stability gating during calibration (no risk-engine coupling).
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private val imuSampler = ImuStabilitySampler()
    private val imuListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            imuSampler.onAccel(event.values)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileManager.ensureInit(this)
        setContentView(R.layout.activity_calibration)

        zoomOnly = intent.getStringExtra(EXTRA_MODE) == MODE_ZOOM_ONLY

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        txtStep = findViewById(R.id.txtStep)
        txtInstruction = findViewById(R.id.txtInstruction)
        txtHint = findViewById(R.id.txtHint)

        // Zoom / framing control (stored in prefs; profile persistence handled elsewhere)
        txtZoomValue = findViewById(R.id.txtZoomValue)
        sliderZoom = findViewById(R.id.sliderZoom)
        sliderZoom.valueFrom = 1.0f
        sliderZoom.valueTo = 2.0f
        sliderZoom.stepSize = 0.1f
        val initialZoom = AppPreferences.cameraZoomRatio.coerceIn(1.0f, 2.0f)
        sliderZoom.value = initialZoom
        updateZoomLabel(initialZoom)
        sliderZoom.addOnChangeListener { _, v, fromUser ->
            val z = v.coerceIn(1.0f, 2.0f)
            updateZoomLabel(z)
            if (fromUser) {
                AppPreferences.cameraZoomRatio = z
                SessionActivityLogger.log("calib_zoom_set ratio=${"%.2f".format(z)}")
            }
            applyZoomAndFocusIfPossible()
        }
        btnBack = findViewById(R.id.btnBack)
        btnConfirm = findViewById(R.id.btnConfirm)

        overlay.listener = this
        overlay.crosshairEnabled = false

        btnBack.setOnClickListener { onBackClicked() }
        btnConfirm.setOnClickListener {
            if (zoomOnly) {
                // Zoom-only step: zoom is already saved to preferences via the slider.
                setResult(RESULT_OK)
                finish()
            } else {
                onConfirmClicked()
            }
        }

        updateUiForStage()
        startCamera()
    }

    private fun updateZoomLabel(z: Float) {
        txtZoomValue.text = "Záběr %.1f×".format(z)
    }

    private fun applyZoomAndFocusIfPossible() {
        val camera = boundCamera ?: return

        // Apply zoom
        runCatching {
            camera.cameraControl.setZoomRatio(AppPreferences.cameraZoomRatio.coerceIn(1.0f, 2.0f))
        }

        // Focus point derived from ROI (user intent). Prefer upper third of ROI to avoid dashboard.
        val roi = AppPreferences.getRoiTrapezoidNormalized()
        val xNorm = roi.centerX.coerceIn(0.05f, 0.95f)
        val yNorm = (roi.topY + 0.35f * (roi.bottomY - roi.topY)).coerceIn(0.05f, 0.95f)

        // PreviewView.meteringPointFactory expects pixel coordinates.
        // If the view isn't laid out yet, skip (never block calibration).
        if (previewView.width <= 0 || previewView.height <= 0) return
        val xPx = xNorm * previewView.width
        val yPx = yNorm * previewView.height

        val p: MeteringPoint = previewView.meteringPointFactory.createPoint(xPx, yPx)
        val action = FocusMeteringAction.Builder(p)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        runCatching { camera.cameraControl.startFocusAndMetering(action) }
            .onSuccess {
                SessionActivityLogger.log("calib_af_roi x=${"%.3f".format(xNorm)} y=${"%.3f".format(yNorm)}")
            }
    }

    private fun onBackClicked() {
        if (zoomOnly) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        when (stage) {
            Stage.RESULT -> {
                when {
                    lastGeomQuality == QualityLevel.BAD -> restartAll()
                    lastGeomQuality == QualityLevel.UNCERTAIN -> repeatWorstStep()
                    lastImuQuality != QualityLevel.OK -> restartAll() // repeat due to stability warning
                    else -> restartAll()
                }
            }
            else -> onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.let { sensorManager.registerListener(imuListener, it, SensorManager.SENSOR_DELAY_GAME) }
        imuSampler.reset()
    }

    override fun onPause() {
        try { sensorManager.unregisterListener(imuListener) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { cameraProvider?.unbindAll() }
        super.onDestroy()
    }

    override fun onPointChanged(xNorm: Float, yNorm: Float, fromUser: Boolean) {
        if (stage == Stage.VERIFY) {
            // Update estimate in header (no dialogs / no allocations).
            val est = estimateDistanceForPoint(yNorm)
            if (est != null) {
                txtHint.text = "Odhad aplikace: %.1f m".format(est)
            } else {
                txtHint.text = "Odhad aplikace: --"
            }
        }
    }

    private fun onConfirmClicked() {
        when (stage) {
            Stage.INTRO -> {
                samples.clear()
                stepImuStdDeg[0] = Float.NaN
                stepImuStdDeg[1] = Float.NaN
                stepImuStdDeg[2] = Float.NaN
                stage = Stage.P1
                overlay.crosshairEnabled = true
                overlay.setPointNormalized(0.5f, 0.78f)
                imuSampler.reset()
                updateUiForStage()
            }
            Stage.P1, Stage.P2, Stage.P3 -> {
                requestDistanceForCurrentStage()
            }
            Stage.RESULT -> {
                if (lastGeomQuality == QualityLevel.BAD) {
                    restartAll()
                } else {
                    stage = Stage.VERIFY
                    overlay.crosshairEnabled = true
                    overlay.setPointNormalized(0.5f, 0.70f)
                    overlay.postInvalidateOnAnimation()
                    updateUiForStage()
                    onPointChanged(overlay.xNorm, overlay.yNorm, fromUser = false)
                }
            }
            Stage.VERIFY -> {
                // Save fitted values and finish.
                applyCalibrationToPreferencesAndProfile()
                SessionActivityLogger.log("calibration_saved cam_h=${AppPreferences.cameraMountHeightM} pitch_deg=${AppPreferences.cameraPitchDownDeg} rms=${AppPreferences.calibrationRmsM} max=${AppPreferences.calibrationMaxErrM} imu_std=${AppPreferences.calibrationImuStdDeg}")
                Toast.makeText(this, "Kalibrace uložena", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onBackPressed() {
        when (stage) {
            Stage.INTRO -> super.onBackPressed()
            Stage.P1 -> {
                stage = Stage.INTRO
                overlay.crosshairEnabled = false
                updateUiForStage()
            }
            Stage.P2 -> {
                // step back to P1
                if (samples.isNotEmpty()) samples.removeAt(samples.size - 1)
                stage = Stage.P1
                imuSampler.reset()
                updateUiForStage()
            }
            Stage.P3 -> {
                if (samples.isNotEmpty()) samples.removeAt(samples.size - 1)
                stage = Stage.P2
                imuSampler.reset()
                updateUiForStage()
            }
            Stage.RESULT -> {
                // Default action = repeat all (safer)
                restartAll()
            }
            Stage.VERIFY -> {
                // User says it's wrong -> restart.
                restartAll()
            }
        }
    }

    private fun updateUiForStage() {
        when (stage) {
            Stage.INTRO -> {
                txtStep.text = "Kalibrace vzdálenosti"
                txtInstruction.text = "Upevni telefon do držáku a stůj na rovině. Kamera musí vidět vozovku."
                txtHint.text = "Během kalibrace s telefonem nehýbej. Vybírej vždy bod na ZEMI (kontakt se zemí)."
                btnBack.text = "ZRUŠIT"
                btnConfirm.text = "ZAČÍT"
            }
            Stage.P1 -> {
                txtStep.text = "Krok 1/3"
                txtInstruction.text = "Umísti modrý bod na zem ve vzdálenosti 3–6 m."
                txtHint.text = "Tip: měř co nejpřesněji (metr je nejlepší)."
                btnBack.text = "ZPĚT"
                btnConfirm.text = "POTVRDIT BOD"
            }
            Stage.P2 -> {
                txtStep.text = "Krok 2/3"
                txtInstruction.text = "Umísti modrý bod na zem ve vzdálenosti 7–12 m."
                txtHint.text = "Neoznačuj strom/zeď – jen bod na zemi."
                btnBack.text = "ZPĚT"
                btnConfirm.text = "POTVRDIT BOD"
            }
            Stage.P3 -> {
                txtStep.text = "Krok 3/3"
                txtInstruction.text = "Umísti modrý bod na zem ve vzdálenosti 13–20 m."
                txtHint.text = "Čím přesnější zadání, tím méně bude distance „halucinovat“." 
                btnBack.text = "ZPĚT"
                btnConfirm.text = "POTVRDIT BOD"
            }
            Stage.RESULT -> {
                val header = when {
                    lastGeomQuality == QualityLevel.BAD -> "Kalibrace špatná"
                    lastGeomQuality == QualityLevel.UNCERTAIN -> "Kalibrace nejistá"
                    lastImuQuality != QualityLevel.OK -> "Geometrie OK, stabilita nízká"
                    else -> "Kalibrace OK"
                }
                txtStep.text = header
                txtInstruction.text = (
                    "RMS: %.2f m, Max: %.2f m, Stabilita: %.2f°" +
                        " (≈ +%.2f m @10m)\nOdhad chyby @10m: ~%.2f m"
                    ).format(lastRmsM, lastMaxErrM, lastImuStdDeg, lastImuExtraErrAt10m, lastCombinedErrAt10m)

                txtHint.text = when {
                    lastGeomQuality == QualityLevel.BAD ->
                        "Geometrie nesedí. Zopakuj kalibraci – nejčastěji je to bod mimo zem nebo špatně zadaná vzdálenost."
                    lastGeomQuality == QualityLevel.UNCERTAIN ->
                        "Geometrie je nejistá. Doporučeno zopakovat krok %d (největší odchylka).".format(worstStepIndex + 1)
                    lastImuQuality != QualityLevel.OK ->
                        "Geometrie je přesná, ale telefon se pravděpodobně hýbal. Může to přidat chybu (viz odhad). Můžeš pokračovat a uložit, nebo zopakovat."
                    else ->
                        "Pokračuj na kontrolu (posuň bod a ověř odhad)."
                }

                btnBack.text = when {
                    lastGeomQuality == QualityLevel.BAD -> "ZOPAKOVAT"
                    lastGeomQuality == QualityLevel.UNCERTAIN -> "ZOPAKOVAT KROK %d".format(worstStepIndex + 1)
                    lastImuQuality != QualityLevel.OK -> "ZOPAKOVAT"
                    else -> "ZOPAKOVAT"
                }
                // Allow proceed to VERIFY whenever geometry is not BAD.
                btnConfirm.text = if (lastGeomQuality == QualityLevel.BAD) "ZOPAKOVAT VŠE" else "POKRAČOVAT"
            }
            Stage.VERIFY -> {
                txtStep.text = "Kontrola"
                txtInstruction.text = "Posuň bod na jiné místo na zemi. Zkontroluj, jestli odhad sedí."
                // txtHint is updated live by onPointChanged()
                btnBack.text = "ŠPATNĚ"
                btnConfirm.text = "ULOŽIT"
            }
        }
    }

    private fun requestDistanceForCurrentStage() {
        val (minM, maxM) = when (stage) {
            Stage.P1 -> 3.0f to 6.0f
            Stage.P2 -> 7.0f to 12.0f
            Stage.P3 -> 13.0f to 20.0f
            else -> 0.5f to 200f
        }

        val til = TextInputLayout(this).apply {
            hint = "Vzdálenost (m)"
            setPadding(0, 6, 0, 0)
        }
        val edit = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("")
        }
        til.addView(edit)

        var parsed: Float? = null
        fun validateNow(): Boolean {
            val raw = edit.text?.toString()?.trim().orEmpty().replace(',', '.')
            parsed = raw.toFloatOrNull()
            val v = parsed
            return if (v == null || !v.isFinite()) {
                til.error = "Zadej číslo v metrech"
                false
            } else if (v < minM || v > maxM) {
                til.error = "Doporučený rozsah je %.1f–%.1f m".format(minM, maxM)
                false
            } else {
                til.error = null
                true
            }
        }

        edit.doAfterTextChanged { validateNow() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Zadej vzdálenost")
            .setMessage("Zadej co nejpřesněji vzdálenost k označenému bodu na zemi.")
            .setView(til)
            .setNegativeButton("ZRUŠIT") { d, _ -> d.dismiss() }
            .setPositiveButton("OK") { d, _ ->
                if (!validateNow()) {
                    // Keep dialog open: MaterialAlertDialog doesn't support this directly.
                    // We just show a toast and let user re-open.
                    Toast.makeText(this, til.error ?: "Neplatná hodnota", Toast.LENGTH_SHORT).show()
                } else {
                    d.dismiss()
                    val dist = parsed ?: return@setPositiveButton
                    onDistanceConfirmed(dist)
                }
            }
            .show()
    }

    private fun onDistanceConfirmed(distanceM: Float) {
        // Capture stability for this step before we change stage.
        val imuStd = imuSampler.stdDevDeg()
        when (stage) {
            Stage.P1 -> stepImuStdDeg[0] = imuStd
            Stage.P2 -> stepImuStdDeg[1] = imuStd
            Stage.P3 -> stepImuStdDeg[2] = imuStd
            else -> Unit
        }

        val s = Sample(
            xNorm = overlay.xNorm,
            yNorm = overlay.yNorm,
            distanceM = distanceM
        )
        samples.add(s)

        when (stage) {
            Stage.P1 -> {
                stage = Stage.P2
                overlay.setPointNormalized(0.5f, 0.72f)
                imuSampler.reset()
                updateUiForStage()
            }
            Stage.P2 -> {
                stage = Stage.P3
                overlay.setPointNormalized(0.5f, 0.66f)
                imuSampler.reset()
                updateUiForStage()
            }
            Stage.P3 -> {
                // Fit parameters from 3 samples.
                if (!fitCalibration()) {
                    Toast.makeText(this, "Kalibrace selhala (bod je blízko horizontu?). Zkus znovu.", Toast.LENGTH_LONG).show()
                    restartAll()
                    return
                }
                evaluateCalibrationQuality()
                stage = Stage.RESULT
                overlay.crosshairEnabled = false
                updateUiForStage()
            }
            else -> Unit
        }
    }

    private fun restartAll() {
        samples.clear()
        stepImuStdDeg[0] = Float.NaN
        stepImuStdDeg[1] = Float.NaN
        stepImuStdDeg[2] = Float.NaN
        stage = Stage.P1
        overlay.crosshairEnabled = true
        overlay.setPointNormalized(0.5f, 0.78f)
        imuSampler.reset()
        updateUiForStage()
    }

    private fun repeatWorstStep() {
        val idx = worstStepIndex.coerceIn(0, 2)
        // Keep samples before idx, drop idx and later.
        while (samples.size > idx) samples.removeAt(samples.size - 1)
        when (idx) {
            0 -> {
                stage = Stage.P1
                overlay.setPointNormalized(0.5f, 0.78f)
            }
            1 -> {
                stage = Stage.P2
                overlay.setPointNormalized(0.5f, 0.72f)
            }
            else -> {
                stage = Stage.P3
                overlay.setPointNormalized(0.5f, 0.66f)
            }
        }
        overlay.crosshairEnabled = true
        imuSampler.reset()
        updateUiForStage()

        SessionActivityLogger.log("calibration_saved cam_h= pitch_deg= rms= max= imu_std= roiMin= roiMinOk=")
    }

    /**
     * Fits (cameraHeightM, pitchDownDeg) by coarse-to-fine grid search.
     * Keeps computation deterministic and small (runs only once per calibration).
     */
    private fun fitCalibration(): Boolean {
        if (samples.size < 3) return false
        val frameH = previewView.height.takeIf { it > 0 } ?: return false

        // Reuse the same focalPx estimation logic as DetectionAnalyzer.
        val focalMm = AppPreferences.cameraFocalLengthMm
        val sensorH = AppPreferences.cameraSensorHeightMm
        val focalPx = if (focalMm.isFinite() && sensorH.isFinite() && sensorH > 0f) {
            (focalMm / sensorH) * frameH
        } else {
            1000f
        }

        fun score(heightM: Float, pitchDeg: Float): Float {
            var sse = 0f
            var n = 0
            for (sm in samples) {
                val box = com.mcaw.model.Box(
                    x1 = 0f, y1 = 0f, x2 = 0f, y2 = sm.yNorm
                )
                val pred = DetectionPhysics.estimateDistanceGroundPlaneMeters(
                    bbox = box,
                    frameHeightPx = frameH,
                    focalPx = focalPx,
                    camHeightM = heightM,
                    pitchDownDeg = pitchDeg
                )
                if (pred != null && pred.isFinite()) {
                    val e = (pred - sm.distanceM)
                    sse += e * e
                    n++
                }
            }
            return if (n >= 2) sse / n.toFloat() else Float.POSITIVE_INFINITY
        }

        var bestH = AppPreferences.cameraMountHeightM.coerceIn(0.6f, 2.0f)
        var bestP = AppPreferences.cameraPitchDownDeg.coerceIn(0f, 20f)
        var bestScore = Float.POSITIVE_INFINITY

        // Coarse search
        run {
            var h = 0.7f
            while (h <= 2.0f) {
                var p = 0.0f
                while (p <= 20.0f) {
                    val sc = score(h, p)
                    if (sc < bestScore) {
                        bestScore = sc
                        bestH = h
                        bestP = p
                    }
                    p += 0.5f
                }
                h += 0.05f
            }
        }

        if (!bestScore.isFinite() || bestScore.isInfinite()) return false

        // Refinement around best
        run {
            val h0 = bestH
            val p0 = bestP
            var h = (h0 - 0.10f).coerceAtLeast(0.6f)
            while (h <= (h0 + 0.10f).coerceAtMost(2.2f)) {
                var p = (p0 - 1.5f).coerceAtLeast(-2f)
                while (p <= (p0 + 1.5f).coerceAtMost(25f)) {
                    val sc = score(h, p)
                    if (sc < bestScore) {
                        bestScore = sc
                        bestH = h
                        bestP = p
                    }
                    p += 0.1f
                }
                h += 0.01f
            }
        }

        // Basic sanity: pitch must be positive-ish and not extreme.
        if (bestP < -5f || bestP > 25f) return false

        fittedHeightM = bestH
        fittedPitchDeg = bestP
        return true
    }

    /**
     * Computes RMS/Max errors for the three samples and classifies the calibration quality.
     * Also identifies the worst step (largest |error|) for quick repetition.
     */
    private fun evaluateCalibrationQuality() {
        val frameH = previewView.height.takeIf { it > 0 } ?: run {
            lastQuality = QualityLevel.BAD
            lastGeomQuality = QualityLevel.BAD
            lastImuQuality = QualityLevel.UNKNOWN
            lastRmsM = 99f
            lastMaxErrM = 99f
            lastImuStdDeg = 9f
            lastImuExtraErrAt10m = 0f
            lastCombinedErrAt10m = 99f
            worstStepIndex = 0
            return
        }

        val focalMm = AppPreferences.cameraFocalLengthMm
        val sensorH = AppPreferences.cameraSensorHeightMm
        val focalPx = if (focalMm.isFinite() && sensorH.isFinite() && sensorH > 0f) {
            (focalMm / sensorH) * frameH
        } else {
            1000f
        }

        var sse = 0f
        var maxAbs = 0f
        var worstIdx = 0
        for (i in 0 until minOf(3, samples.size)) {
            val sm = samples[i]
            val box = com.mcaw.model.Box(0f, 0f, 0f, sm.yNorm)
            val pred = DetectionPhysics.estimateDistanceGroundPlaneMeters(
                bbox = box,
                frameHeightPx = frameH,
                focalPx = focalPx,
                camHeightM = fittedHeightM,
                pitchDownDeg = fittedPitchDeg
            )
            val e = if (pred != null && pred.isFinite()) (pred - sm.distanceM) else 99f
            val ae = kotlin.math.abs(e)
            sse += e * e
            if (ae > maxAbs) {
                maxAbs = ae
                worstIdx = i
            }
        }

        val rms = kotlin.math.sqrt((sse / 3f).toDouble()).toFloat()
        lastRmsM = rms
        lastMaxErrM = maxAbs
        worstStepIndex = worstIdx

        // IMU stability = max std across steps (deg). If NaN, treat as 0 (sensor missing).
        var imuStd = 0f
        for (v in stepImuStdDeg) {
            if (v.isFinite()) imuStd = maxOf(imuStd, v)
        }
        lastImuStdDeg = imuStd

        // Quality thresholds tuned for A1 distances (3–14m). Conservative and explainable.
        val byRms = when {
            rms <= 0.35f -> QualityLevel.OK
            rms <= 0.80f -> QualityLevel.UNCERTAIN
            else -> QualityLevel.BAD
        }
        val byMax = when {
            maxAbs <= 0.70f -> QualityLevel.OK
            maxAbs <= 1.50f -> QualityLevel.UNCERTAIN
            else -> QualityLevel.BAD
        }
        val byImu = when {
            imuStd <= 0.50f -> QualityLevel.OK
            imuStd <= 1.50f -> QualityLevel.UNCERTAIN
            else -> QualityLevel.BAD
        }

        // Geometry quality is derived ONLY from geometric consistency (RMS/Max).
        lastGeomQuality = when {
            byRms == QualityLevel.BAD || byMax == QualityLevel.BAD -> QualityLevel.BAD
            byRms == QualityLevel.UNCERTAIN || byMax == QualityLevel.UNCERTAIN -> QualityLevel.UNCERTAIN
            else -> QualityLevel.OK
        }
        // IMU quality is a separate signal. Never hard-fails a geometrically good calibration.
        lastImuQuality = byImu

        // Translate IMU jitter (deg) to a rough additional distance error at 10m.
        // Small-angle approximation: error ≈ d * tan(jitter).
        // This is intentionally conservative and only used for user guidance.
        lastImuExtraErrAt10m = (10.0 * kotlin.math.tan(Math.toRadians(imuStd.toDouble()))).toFloat().coerceAtLeast(0f)
        lastCombinedErrAt10m = kotlin.math.sqrt((rms * rms + lastImuExtraErrAt10m * lastImuExtraErrAt10m).toDouble()).toFloat()

        // Overall quality shown in UI:
        // - BAD only if geometry is BAD
        // - UNCERTAIN if geometry is UNCERTAIN OR IMU is UNCERTAIN/BAD
        // - OK only if both signals are OK
        lastQuality = when {
            lastGeomQuality == QualityLevel.BAD -> QualityLevel.BAD
            lastGeomQuality == QualityLevel.UNCERTAIN -> QualityLevel.UNCERTAIN
            lastImuQuality != QualityLevel.OK -> QualityLevel.UNCERTAIN
            else -> QualityLevel.OK
        }
    }

    private fun estimateDistanceForPoint(yNorm: Float): Float? {
        val frameH = previewView.height.takeIf { it > 0 } ?: return null
        val focalMm = AppPreferences.cameraFocalLengthMm
        val sensorH = AppPreferences.cameraSensorHeightMm
        val focalPx = if (focalMm.isFinite() && sensorH.isFinite() && sensorH > 0f) {
            (focalMm / sensorH) * frameH
        } else {
            1000f
        }
        val box = com.mcaw.model.Box(0f, 0f, 0f, yNorm)
        return DetectionPhysics.estimateDistanceGroundPlaneMeters(
            bbox = box,
            frameHeightPx = frameH,
            focalPx = focalPx,
            camHeightM = fittedHeightM,
            pitchDownDeg = fittedPitchDeg
        )
    }

    private fun applyCalibrationToPreferencesAndProfile() {
        // Apply to prefs
        AppPreferences.cameraMountHeightM = fittedHeightM
        AppPreferences.cameraPitchDownDeg = fittedPitchDeg
        AppPreferences.calibrationRmsM = lastRmsM
        AppPreferences.calibrationMaxErrM = lastMaxErrM
        AppPreferences.calibrationImuStdDeg = lastImuStdDeg
        AppPreferences.calibrationQuality = lastQuality.code
        AppPreferences.calibrationGeomQuality = lastGeomQuality.code
        AppPreferences.calibrationImuQuality = lastImuQuality.code
        AppPreferences.calibrationImuExtraErrAt10m = lastImuExtraErrAt10m
        AppPreferences.calibrationCombinedErrAt10m = lastCombinedErrAt10m
        AppPreferences.calibrationSavedUptimeMs = SystemClock.uptimeMillis()
        // Keep distanceScale unchanged unless user explicitly tunes it elsewhere.

        // If there is an active profile, persist to it as well (single source of truth).
        val activeId = ProfileManager.getActiveProfileIdOrNull()
        if (!activeId.isNullOrBlank()) {
            val p = ProfileManager.findById(activeId)
            if (p != null) {
                val updated = MountProfile(
                    id = p.id,
                    name = p.name,
                    cameraHeightM = AppPreferences.cameraMountHeightM,
                    cameraPitchDownDeg = AppPreferences.cameraPitchDownDeg,
                    cameraZoomRatio = AppPreferences.cameraZoomRatio,
                    distanceScale = AppPreferences.distanceScale,
                    calibrationRmsM = AppPreferences.calibrationRmsM,
                    calibrationMaxErrM = AppPreferences.calibrationMaxErrM,
                    calibrationImuStdDeg = AppPreferences.calibrationImuStdDeg,
                    calibrationSavedUptimeMs = AppPreferences.calibrationSavedUptimeMs,
                    calibrationQuality = AppPreferences.calibrationQuality,
                    calibrationGeomQuality = AppPreferences.calibrationGeomQuality,
                    calibrationImuQuality = AppPreferences.calibrationImuQuality,
                    calibrationImuExtraErrAt10m = AppPreferences.calibrationImuExtraErrAt10m,
                    calibrationCombinedErrAt10m = AppPreferences.calibrationCombinedErrAt10m,
                    laneEgoMaxOffset = p.laneEgoMaxOffset,
                    roiTopY = p.roiTopY,
                    roiBottomY = p.roiBottomY,
                    roiTopHalfW = p.roiTopHalfW,
                    roiBottomHalfW = p.roiBottomHalfW,
                    roiCenterX = p.roiCenterX
                )
                ProfileManager.upsert(updated)
            }
        }
        SessionActivityLogger.log(
            "calibration_saved cam_h=" + AppPreferences.cameraMountHeightM +
                " pitch_deg=" + AppPreferences.cameraPitchDownDeg +
                " zoom=" + AppPreferences.cameraZoomRatio +
                " rms=" + AppPreferences.calibrationRmsM +
                " max=" + AppPreferences.calibrationMaxErrM +
                " imu_std=" + AppPreferences.calibrationImuStdDeg +
                " roiMin=" + AppPreferences.roiMinDistM +
                " roiMinOk=" + AppPreferences.roiMinDistConfirmed
        )
    }

    /**
     * Sensor-based stability metric for calibration:
     * - uses accelerometer only (available almost everywhere)
     * - tracks jitter of gravity direction relative to a baseline
     * - provides std dev in degrees (0 = very stable)
     */
    private class ImuStabilitySampler {
        // low-pass gravity estimate
        private var gx = Float.NaN
        private var gy = Float.NaN
        private var gz = Float.NaN

        // baseline gravity unit vector
        private var bx = Float.NaN
        private var by = Float.NaN
        private var bz = Float.NaN

        // Welford running variance of angleDeg
        private var n = 0
        private var mean = 0.0
        private var m2 = 0.0

        fun reset() {
            gx = Float.NaN; gy = Float.NaN; gz = Float.NaN
            bx = Float.NaN; by = Float.NaN; bz = Float.NaN
            n = 0
            mean = 0.0
            m2 = 0.0
        }

        fun onAccel(values: FloatArray) {
            val ax = values.getOrNull(0) ?: return
            val ay = values.getOrNull(1) ?: return
            val az = values.getOrNull(2) ?: return

            val alpha = 0.10f
            if (!gx.isFinite()) {
                gx = ax; gy = ay; gz = az
            } else {
                gx += alpha * (ax - gx)
                gy += alpha * (ay - gy)
                gz += alpha * (az - gz)
            }

            val norm = kotlin.math.sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
                .coerceAtLeast(1e-3f)
            val nx = gx / norm
            val ny = gy / norm
            val nz = gz / norm

            if (!bx.isFinite()) {
                bx = nx; by = ny; bz = nz
                return
            }

            // angle between current and baseline gravity direction
            var dot = (bx * nx + by * ny + bz * nz).toDouble()
            if (dot > 1.0) dot = 1.0
            if (dot < -1.0) dot = -1.0
            val angleDeg = kotlin.math.acos(dot) * 57.29577951308232

            n++
            val delta = angleDeg - mean
            mean += delta / n.toDouble()
            val delta2 = angleDeg - mean
            m2 += delta * delta2
        }

        fun stdDevDeg(): Float {
            if (n < 4) return 0f
            val varDeg = m2 / (n - 1).toDouble()
            return kotlin.math.sqrt(varDeg).toFloat().coerceIn(0f, 45f)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindPreview()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            previewUseCase!!
        )
        boundCamera = camera
        updateCameraCalibration(camera)
        applyZoomAndFocusIfPossible()
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
            AppPreferences.cameraSensorWidthMm = sensorSize.width
        }
    }
}
