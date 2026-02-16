package com.mcaw.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import android.widget.TextView
import android.widget.ProgressBar
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.app.BuildConfig
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.location.SpeedMonitor
import com.mcaw.location.SpeedProvider
import com.mcaw.service.McawService
import com.mcaw.util.PublicLogWriter
import com.mcaw.util.SessionLogFile
import com.mcaw.util.LabelMapper
import com.mcaw.util.ReasonTextMapper

class MainActivity : ComponentActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtAlert: TextView
    private lateinit var panelAlert: com.google.android.material.card.MaterialCardView
    private var serviceRunning: Boolean = false
    private lateinit var txtTtc: TextView
    private lateinit var txtDistance: TextView
    private lateinit var txtSpeed: TextView
    private lateinit var txtObjectSpeed: TextView
    private lateinit var txtRiderSpeed: TextView
    private lateinit var txtDetectedObject: TextView
    private lateinit var txtActivityLog: TextView
    private lateinit var txtBuildInfo: TextView
    private lateinit var root: View
    private lateinit var panelMetrics: MaterialCardView
    private lateinit var brakeLamp: View
    private lateinit var txtBrakeLamp: TextView
    private lateinit var txtWhy: TextView
    private lateinit var progressAlive: ProgressBar
    private lateinit var dotService: View
    private lateinit var panelMiniPreview: com.google.android.material.card.MaterialCardView
    private lateinit var previewThumb: PreviewView

    // Single power control
    private lateinit var powerOuter: com.google.android.material.card.MaterialCardView
    private lateinit var powerInner: com.google.android.material.card.MaterialCardView
    private lateinit var imgPower: ImageView
    private lateinit var txtPower: TextView
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var miniPreviewBound: Boolean = false
    private var lastBrakeLampUiState: Boolean = false

    private var pulseAnimator: ValueAnimator? = null
    private var alertTransitionAnimator: ValueAnimator? = null
    private var pendingAction: PendingAction? = null
    private val alertSmoother = AlertUiSmoother()
    private lateinit var speedProvider: SpeedProvider
    private lateinit var speedMonitor: SpeedMonitor
    private var speedMonitorStarted: Boolean = false

    private val logLines: ArrayDeque<String> = ArrayDeque()
    private val speedHandler = Handler(Looper.getMainLooper())
    private val metricsWatchdogHandler = Handler(Looper.getMainLooper())
    private var metricsWatchdogPosted: Boolean = false
    private val metricsWatchdog = object : Runnable {
        override fun run() {
            // If service is running but we haven't received fresh metrics recently,
            // clear the UI to avoid "stuck" values when the target disappears.
            if (serviceRunning) {
                val ageMs = SystemClock.elapsedRealtime() - lastMetricsUpdateMs
                if (ageMs > 1500L) {
                    applyNoTargetUi()
                }
            }
            metricsWatchdogHandler.postDelayed(this, 500L)
        }
    }

    // --- Rider speed UX stabilization ---
    // Metrics from DetectionAnalyzer should be the primary source (when camera/service runs).
    // SpeedMonitor polling stays as a fallback only when metrics haven't arrived recently.
    private var lastMetricsRiderSpeedUpdateMs: Long = 0L
    private var lastRiderSpeedText: String? = null

    private var lastOverallLevelUi: Int = -1
    private var lastTtcLevelUi: Int = -1

    private var lastMetricsUpdateMs: Long = 0L

    private val colorBgSafe = android.graphics.Color.parseColor("#1E222A")
    private val colorBgOrange = android.graphics.Color.parseColor("#332514")
    private val colorBgRed = android.graphics.Color.parseColor("#331B1B")

    private val colorAccentSafe = android.graphics.Color.parseColor("#00E5A8")
    private val colorAccentOrange = android.graphics.Color.parseColor("#FF9F0A")
    private val colorAccentRed = android.graphics.Color.parseColor("#FF3B30")

    // UI-only: neutrální šedá pro vypnutý stav (tlačítko START/STOP)
    private val colorStrokeMuted = android.graphics.Color.parseColor("#8B97A6")

    private val metricsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent == null) return

            lastMetricsUpdateMs = SystemClock.elapsedRealtime()

            val ttc = intent.getFloatExtra(DetectionAnalyzer.EXTRA_TTC, Float.POSITIVE_INFINITY)
            val distance =
                intent.getFloatExtra(DetectionAnalyzer.EXTRA_DISTANCE, Float.POSITIVE_INFINITY)
            val speed = intent.getFloatExtra(DetectionAnalyzer.EXTRA_SPEED, Float.POSITIVE_INFINITY)
            val objectSpeed =
                intent.getFloatExtra(DetectionAnalyzer.EXTRA_OBJECT_SPEED, Float.POSITIVE_INFINITY)
            val riderSpeed =
                intent.getFloatExtra(DetectionAnalyzer.EXTRA_RIDER_SPEED, Float.POSITIVE_INFINITY)
            val level = intent.getIntExtra(DetectionAnalyzer.EXTRA_LEVEL, 0)
            val alertReason = intent.getStringExtra(DetectionAnalyzer.EXTRA_ALERT_REASON) ?: ""
            val reasonBits = intent.getIntExtra(DetectionAnalyzer.EXTRA_REASON_BITS, 0)
            val riskScore = intent.getFloatExtra(DetectionAnalyzer.EXTRA_RISK_SCORE, Float.NaN)
            val reasonText = ReasonTextMapper.shortOrFallback(reasonBits, alertReason)
            val alertWhy = if (riskScore.isFinite()) "RISK %.2f · %s".format(riskScore, reasonText) else reasonText
            val disp = alertSmoother.update(level, alertWhy, SystemClock.elapsedRealtime())
            val label = intent.getStringExtra(DetectionAnalyzer.EXTRA_LABEL)
            val brakeCue =
                intent.getBooleanExtra("brake_cue", false) || intent.getBooleanExtra("extra_brake_cue", false)

            val mappedLabel = LabelMapper.mapLabel(label)
            val hasTarget = ttc.isFinite() || distance.isFinite() || objectSpeed.isFinite() || !mappedLabel.isNullOrBlank()
            if (!hasTarget) {
                applyNoTargetUi()
            } else {
                // U2: v mřížce chceme reálné hodnoty (bez dlouhých popisků). Popisky jsou v layoutu.
                txtTtc.text = if (ttc.isFinite()) "%.2f s".format(ttc) else "--.- s"
                txtDistance.text = if (distance.isFinite()) "%.1f m".format(distance) else "--.- m"
            }

            // Rider speed from analyzer (primary)
            val riderText = formatRiderSpeedFromMps(riderSpeed)
            applyRiderSpeedText(riderText, fromMetrics = true)

            val speedKmh = if (speed.isFinite()) speed * 3.6f else Float.POSITIVE_INFINITY
            val objKmh = if (objectSpeed.isFinite()) objectSpeed * 3.6f else Float.POSITIVE_INFINITY

            if (hasTarget) {
                txtSpeed.text = if (speedKmh.isFinite()) "%.1f km/h".format(speedKmh) else "--.- km/h"
                txtObjectSpeed.text = if (objKmh.isFinite()) "%.1f km/h".format(objKmh) else "--.- km/h"
                txtDetectedObject.text = if (mappedLabel.isNotBlank()) mappedLabel else "--"
            }

            // UI pravidlo U2: TTC se nemá barvit podle alertů. Vždy klidná, neutrální barva.
            val ttcLevel = ttcLevelForUi(ttc)
            txtTtc.setTextColor(android.graphics.Color.parseColor("#C9D1D9"))

            val riderKmhForUi = if (riderSpeed.isFinite()) riderSpeed * 3.6f else Float.POSITIVE_INFINITY
            applyVisualAlert(disp.level, ttcLevel, riderKmhForUi)

            updateBrakeLamp(brakeCue)
            updateWhy(disp.level, disp.why)

            val relKmhLog = if (speed.isFinite()) speed * 3.6f else Float.POSITIVE_INFINITY
            val objKmhLog = if (objectSpeed.isFinite()) objectSpeed * 3.6f else Float.POSITIVE_INFINITY
            addLog(
                "Metriky: TTC ${formatMetric(ttc, "s")} · " +
                    "Vzdálenost ${formatMetric(distance, "m")} · " +
                    "Rel ${formatMetric(relKmhLog, "km/h")} · " +
                    "Objekt ${mappedLabel.ifBlank { "--" }}" +
                    (if (AppPreferences.debugOverlay && alertReason.isNotBlank()) " · WHY $alertReason" else "")
            )
            logActivity(
                "metrics ttc=${formatMetric(ttc, "s")} dist=${formatMetric(distance, "m")} " +
                    "rel=${formatMetric(relKmhLog, "km/h")} obj=${formatMetric(objKmhLog, "km/h")} " +
                    "level=$level brakeCue=$brakeCue reason=${alertReason.replace("\n"," ").take(120)}"
            )
        }
    }

    private fun applyNoTargetUi() {
        txtTtc.text = "--.- s"
        txtDistance.text = "--.- m"
        txtSpeed.text = "--.- km/h"
        txtObjectSpeed.text = "--.- km/h"
        txtDetectedObject.text = "--"
    }

    private val requiredPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtAlert = findViewById(R.id.txtAlert)
        panelAlert = findViewById(R.id.panelAlert)
        txtTtc = findViewById(R.id.txtTtc)
        txtDistance = findViewById(R.id.txtDistance)
        txtSpeed = findViewById(R.id.txtSpeed)
        txtObjectSpeed = findViewById(R.id.txtObjectSpeed)
        txtRiderSpeed = findViewById(R.id.txtRiderSpeed)
        txtDetectedObject = findViewById(R.id.txtDetectedObject)
        txtActivityLog = findViewById(R.id.txtActivityLog)
        txtActivityLog.movementMethod = ScrollingMovementMethod()
        txtBuildInfo = findViewById(R.id.txtBuildInfo)
        root = findViewById(R.id.root)
        panelMetrics = findViewById(R.id.panelMetrics)
        brakeLamp = findViewById(R.id.brakeLamp)
        txtBrakeLamp = findViewById(R.id.txtBrakeLamp)
        txtWhy = findViewById(R.id.txtWhy)
        progressAlive = findViewById(R.id.progressAlive)
        dotService = findViewById(R.id.dotService)
        panelMiniPreview = findViewById(R.id.panelMiniPreview)
        previewThumb = findViewById(R.id.previewThumb)

        powerOuter = findViewById(R.id.powerOuter)
        powerInner = findViewById(R.id.powerInner)
        imgPower = findViewById(R.id.imgPower)
        txtPower = findViewById(R.id.txtPower)
        updateWhy(0, "")
        updateBrakeLamp(false)

        setupServiceDot()
        setupMiniPreview()

        // Jemný vizuální rám metrik – stabilní, bez zásahů do výpočtu risk.
        panelMetrics.strokeWidth = dpToPx(2)
        panelMetrics.strokeColor = android.graphics.Color.TRANSPARENT

        speedProvider = SpeedProvider(this)
        speedMonitor = SpeedMonitor(speedProvider)
        SessionLogFile.init(this)

        txtBuildInfo.text =
            "MCAW ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_ID}"
        addLog("Aplikace spuštěna")
        logActivity("app_start build=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")

        powerOuter.setOnClickListener {
            // Single toggle: barva a text dle stavu služby, NIKOLI dle alertu.
            if (serviceRunning) {
                stopEngine()
            } else {
                // Prevent camera conflicts: stop mini preview before starting the service.
                stopMiniPreview()
                ensurePermissions(PendingAction.START_ENGINE)
            }
        }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            logActivity("open_settings")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener {
            ensurePermissions(PendingAction.OPEN_CAMERA)
        }

        findViewById<MaterialButton>(R.id.btnHelp).setOnClickListener {
            logActivity("open_help")
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnLegal).setOnClickListener {
            logActivity("open_legal")
            startActivity(Intent(this, LegalActivity::class.java))
        }

    }

    override fun onResume() {
        super.onResume()
        // Mini preview is debug-oriented and must never compete with the running service.
        refreshMiniPreviewVisibility()
        maybeStartMiniPreview()
        startMetricsWatchdog()
    }

    override fun onPause() {
        super.onPause()
        stopMiniPreview()
        stopMetricsWatchdog()
    }

    private fun ensurePermissions(action: PendingAction) {
        if (requiredPerms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            runAction(action)
            startSpeedMonitorIfPermitted()
            addLog("Ověřeno oprávnění: vše v pořádku")
            logActivity("permissions_ok")
            return
        }
        pendingAction = action
        ActivityCompat.requestPermissions(this, requiredPerms, 1001)
        addLog("Žádost o oprávnění: kamera + poloha")
        logActivity("permissions_request")
    }

    private fun startSpeedMonitorIfPermitted() {
        if (requiredPerms.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) return

        if (!speedMonitorStarted) {
            speedMonitor.start()
            speedMonitorStarted = true
        }
    }

    private fun stopSpeedMonitor() {
        if (speedMonitorStarted) {
            speedMonitor.stop()
            speedMonitorStarted = false
        }
    }

    private fun startEngine() {
        val intent = Intent(this, McawService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        txtStatus.text = "BĚŽÍ"
        updateServiceDot(true)
        updatePowerUi(true)
        refreshMiniPreviewVisibility()
        // UI-only: SAFE alive indikace se může zobrazit ještě před prvními metrikami.
        updateAliveIndicator(running = true, riderStanding = false, overallLevel = 0)
        addLog("Služba spuštěna")
        logActivity("service_start")
    }

    private fun stopEngine() {
        stopService(Intent(this, McawService::class.java))
        serviceRunning = false
        txtStatus.text = "ZASTAVENA"
        updateServiceDot(false)
        updatePowerUi(false)
        updateAliveIndicator(running = false, riderStanding = false, overallLevel = 0)
        stopPulse()
        alertTransitionAnimator?.cancel()
        panelMetrics.strokeColor = android.graphics.Color.TRANSPARENT
        refreshMiniPreviewVisibility()
        maybeStartMiniPreview()
        addLog("Služba zastavena")
        logActivity("service_stop")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 1001 &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            val action = pendingAction
            pendingAction = null
            if (action != null) {
                runAction(action)
            }
            startSpeedMonitorIfPermitted()
            addLog("Oprávnění udělena")
            logActivity("permissions_granted")
        } else {
            addLog("Oprávnění zamítnuta")
            logActivity("permissions_denied")
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter(DetectionAnalyzer.ACTION_METRICS_UPDATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(metricsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(metricsReceiver, filter)
        }

        startSpeedMonitorIfPermitted()
        if (speedMonitorStarted) {
            addLog("GPS monitor spuštěn")
            logActivity("gps_monitor_start")
        }

        startSpeedUpdates()
    }

    override fun onStop() {
        unregisterReceiver(metricsReceiver)
        stopPulse()
        stopSpeedMonitor()
        addLog("GPS monitor zastaven")
        logActivity("gps_monitor_stop")
        stopSpeedUpdates()
        super.onStop()
    }

    private fun runAction(action: PendingAction) {
        when (action) {
            PendingAction.START_ENGINE -> startEngine()
            PendingAction.OPEN_CAMERA -> {
                addLog("Otevírám kameru")
                logActivity("open_camera")
                startActivity(Intent(this, PreviewActivity::class.java))
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(System.currentTimeMillis())
        logLines.addFirst("[$timestamp] $message")
        while (logLines.size > 80) {
            logLines.removeLast()
        }
        txtActivityLog.text = buildString {
            append("Aktivity:\n")
            logLines.forEach { line ->
                append("• ")
                append(line)
                append('\n')
            }
        }.trimEnd()
        logActivity("ui_log $message")
    }

    private data class AlertThresholds(
        val ttcOrange: Float,
        val ttcRed: Float
    )

    private fun thresholdsForModeUi(): AlertThresholds {
        val mode = AppPreferences.detectionMode
        val resolved = when (mode) {
            AppPreferences.MODE_AUTO -> {
                val kmh = AppPreferences.lastSpeedMps * 3.6f
                if (kmh.isFinite() && kmh > 55f) AppPreferences.MODE_SPORT else AppPreferences.MODE_CITY
            }
            else -> mode
        }

        return when (resolved) {
            AppPreferences.MODE_SPORT -> AlertThresholds(ttcOrange = 4.0f, ttcRed = 1.5f)
            AppPreferences.MODE_USER -> AlertThresholds(
                ttcOrange = AppPreferences.userTtcOrange,
                ttcRed = AppPreferences.userTtcRed
            )
            else -> AlertThresholds(ttcOrange = 3.0f, ttcRed = 1.2f)
        }
    }

    private fun ttcLevelForUi(ttc: Float): Int {
        val t = thresholdsForModeUi()
        if (!ttc.isFinite()) return 0
        return when {
            ttc <= t.ttcRed -> 2
            ttc <= t.ttcOrange -> 1
            else -> 0
        }
    }

    private fun applyVisualAlert(overallLevel: Int, ttcLevel: Int, riderKmh: Float) {
        val riderStanding = riderKmh.isFinite() && riderKmh < 2.0f
        // Stav služby = nedominantní; alert má vlastní banner.
        txtStatus.text = when {
            !serviceRunning -> "ZASTAVENA"
            riderStanding -> "BĚŽÍ · stojíš (alert vypnut)"
            else -> "BĚŽÍ"
        }

        updateServiceDot(serviceRunning)

        // Power button je indikátor stavu služby (zelená = běží, šedá = vypnuto).
        updatePowerUi(serviceRunning)

        // SAFE "alive" indikace: jen když služba běží, jezdec nestojí a overall je SAFE.
        updateAliveIndicator(serviceRunning, riderStanding, overallLevel)

        // Jemný přechod mezi úrovněmi – bez blikání.
        animateAlertTransitionIfNeeded(overallLevel, ttcLevel)

        // U2: žádné blikání / pulzování (alert musí být stabilní). Plynulé crossfade stačí.
        stopPulse()
    }

    private fun updatePowerUi(running: Boolean) {
        val ring = if (running) colorAccentSafe else colorStrokeMuted
        powerOuter.strokeColor = ring
        powerInner.strokeColor = ring

        val label = if (running) "STOP" else "START"
        txtPower.text = label
        txtPower.setTextColor(ring)
        imgPower.setColorFilter(ring)
    }

    private fun animateAlertTransitionIfNeeded(overallLevel: Int, ttcLevel: Int) {
        val newAlertBg = when (overallLevel) {
            2 -> colorBgRed
            1 -> colorBgOrange
            else -> colorBgSafe
        }

        val newAlertText = when (overallLevel) {
            2 -> AppPreferences.ttsTextRed.ifBlank { "BRZDI" }
            1 -> AppPreferences.ttsTextOrange.ifBlank { "POZOR" }
            else -> "SAFE"
        }

        // SAFE má být klidný (menší), ORANGE/RED dominantní (větší).
        val newAlertTextSp = when (overallLevel) {
            2 -> 60f
            1 -> 60f
            else -> 44f
        }
        val newAlertPaddingV = when (overallLevel) {
            2 -> dpToPx(22)
            1 -> dpToPx(22)
            else -> dpToPx(14)
        }

        val newMetricsStroke = when (overallLevel) {
            2 -> colorAccentRed
            1 -> colorAccentOrange
            else -> android.graphics.Color.TRANSPARENT
        }

        val needsOverallAnim = lastOverallLevelUi != overallLevel
        // TTC UI je klidné a nebarví se, proto ho neanimujeme.
        val needsTtcAnim = false

        // Pokud se nic nezměnilo, nedělej žádnou animaci (žádné micro-flicker).
        if (!needsOverallAnim && !needsTtcAnim) {
            return
        }

        // Start barvy z aktuálního UI (fallback na SAFE).
        val alertStart = (txtAlert.background as? android.graphics.drawable.ColorDrawable)?.color ?: colorBgSafe
        val strokeStart = panelMetrics.strokeColor

        alertTransitionAnimator?.cancel()
        alertTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                if (needsOverallAnim) {
                    // Crossfade banner background + jemný fade textu.
                    txtAlert.setBackgroundColor(lerpColor(alertStart, newAlertBg, t))
                    panelMetrics.strokeColor = lerpColor(strokeStart, newMetricsStroke, t)
                }
            }
            // Text banneru měníme jednorázově s krátkým alpha přechodem.
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    if (needsOverallAnim) {
                        txtAlert.animate().cancel()
                        txtAlert.animate().alpha(0.0f).setDuration(90L).withEndAction {
                            txtAlert.text = newAlertText
                            txtAlert.setTextSize(TypedValue.COMPLEX_UNIT_SP, newAlertTextSp)
                            txtAlert.setPadding(txtAlert.paddingLeft, newAlertPaddingV, txtAlert.paddingRight, newAlertPaddingV)
                            txtAlert.animate().alpha(1.0f).setDuration(120L).start()
                        }.start()
                    }
                }
            })
            start()
        }

        lastOverallLevelUi = overallLevel
        lastTtcLevelUi = 0
    }

    private fun updateAliveIndicator(running: Boolean, riderStanding: Boolean, overallLevel: Int) {
        val show = running && !riderStanding && overallLevel == 0
        progressAlive.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (show) {
            // Jemná indikace: zelená pro SAFE (bez blikání).
            progressAlive.indeterminateTintList = android.content.res.ColorStateList.valueOf(colorAccentSafe)
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val a = android.graphics.Color.alpha(from) + ((android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t).toInt()
        val r = android.graphics.Color.red(from) + ((android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t).toInt()
        val g = android.graphics.Color.green(from) + ((android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t).toInt()
        val b = android.graphics.Color.blue(from) + ((android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupServiceDot() {
        // dotService is a simple view in XML; render it as a circle.
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.parseColor("#3A3F4A"))
        }
        dotService.background = bg
    }

    private fun updateServiceDot(running: Boolean) {
        val color = if (running) colorAccentSafe else android.graphics.Color.parseColor("#3A3F4A")
        val bg = (dotService.background as? android.graphics.drawable.GradientDrawable)
            ?: android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        bg.setColor(color)
        dotService.background = bg
    }

    private fun setupMiniPreview() {
        // Debug-only mini preview: tap opens the full PreviewActivity.
        panelMiniPreview.setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }
    }

    private fun refreshMiniPreviewVisibility() {
        // Mini preview panel is part of the main UI layout.
        // IMPORTANT: we must NOT contend for the camera while the service is running.
        // Live CameraX binding is therefore limited to debug overlay mode AND only when service is NOT running.
        panelMiniPreview.visibility = View.VISIBLE

        if (AppPreferences.debugOverlay && !serviceRunning) {
            maybeStartMiniPreview()
        } else {
            stopMiniPreview()
        }
    }

    private fun maybeStartMiniPreview() {
        if (panelMiniPreview.visibility != View.VISIBLE) return
        // Live binding only in debug overlay and only when the service is NOT running.
        if (!AppPreferences.debugOverlay || serviceRunning) return
        if (!hasCameraPermission()) return
        if (miniPreviewBound) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindMiniPreview()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindMiniPreview() {
        val provider = cameraProvider ?: return
        if (serviceRunning) return
        try {
            provider.unbindAll()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(previewThumb.surfaceProvider)
            }
            provider.bindToLifecycle(this, selector, previewUseCase)
            miniPreviewBound = true
        } catch (t: Throwable) {
            miniPreviewBound = false
            // Fail silently: mini preview is optional and must never break main UX.
        }
    }

    private fun stopMiniPreview() {
        if (!miniPreviewBound) return
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
            // ignore
        }
        miniPreviewBound = false
        previewUseCase = null
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun startMetricsWatchdog() {
        if (metricsWatchdogPosted) return
        metricsWatchdogPosted = true
        metricsWatchdogHandler.post(metricsWatchdog)
    }

    private fun stopMetricsWatchdog() {
        if (!metricsWatchdogPosted) return
        metricsWatchdogPosted = false
        metricsWatchdogHandler.removeCallbacks(metricsWatchdog)
    }

    private fun startPulse(level: Int) {
        val targetMinAlpha = if (level >= 2) 0.72f else 0.82f
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(1f, targetMinAlpha).apply {
            duration = if (level >= 2) 420L else 620L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val a = anim.animatedValue as Float
                panelMetrics.alpha = a
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        panelMetrics.alpha = 1f
    }

    private fun updateBrakeLamp(active: Boolean) {
        val color =
            if (active) android.graphics.Color.parseColor("#FF2D2D") else android.graphics.Color.parseColor("#3A3F4A")
        val bg = (brakeLamp.background as? android.graphics.drawable.GradientDrawable)
            ?: android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
        bg.setColor(color)
        brakeLamp.background = bg
        txtBrakeLamp.text = if (active) "BRZDA" else "NEBRZDÍ"

        if (active != lastBrakeLampUiState) {
            lastBrakeLampUiState = active
            val msg = if (active) "brake_cue ON" else "brake_cue OFF"
            addLog(msg)
        }
    }

    private fun updateWhy(level: Int, alertReason: String) {
        // MCAW UX: varování dominantní; WHY (reason) musí být srozumitelné a krátké.
        // Zobrazuj pouze při alertu, aby UI nebylo zahlcené.
        val why = alertReason.trim()
            .replace("\n", " ")
            .replace("\r", " ")
            .take(90)

        val show = level > 0 && why.isNotBlank()
        txtWhy.text = if (show) why else ""
        txtWhy.visibility = if (show) View.VISIBLE else View.GONE

        val c = when (level) {
            2 -> android.graphics.Color.parseColor("#FF3B30")
            1 -> android.graphics.Color.parseColor("#FF9F0A")
            else -> android.graphics.Color.parseColor("#AAB4BE")
        }
        txtWhy.setTextColor(c)
    }

    private fun formatMetric(value: Float, unit: String): String {
        return if (value.isFinite()) "%.2f %s".format(value, unit) else "--.- $unit"
    }

    private fun startSpeedUpdates() {
        speedHandler.post(object : Runnable {
            override fun run() {
                // Fallback speed update only if analyzer metrics haven't updated recently.
                val now = SystemClock.elapsedRealtime()
                val allowFallback = (now - lastMetricsRiderSpeedUpdateMs) > 1500L

                if (allowFallback) {
                    val speedMps = speedMonitor.pollCurrentSpeedMps()
                    val text = formatRiderSpeedFromMps(speedMps)
                    applyRiderSpeedText(text, fromMetrics = false)
                }

                speedHandler.postDelayed(this, 1000L)
            }
        })
    }

    private fun stopSpeedUpdates() {
        speedHandler.removeCallbacksAndMessages(null)
    }

    private fun formatRiderSpeedFromMps(speedMps: Float): String {
        if (!speedMps.isFinite() || speedMps < 0f) return "--.- km/h"

        // deadband (GPS jitter at low speeds)
        val deadbandMps = 0.5f // ~1.8 km/h
        val v = if (speedMps < deadbandMps) 0f else speedMps.coerceIn(0f, 80f)
        val kmh = v * 3.6f
        return "%.1f km/h".format(kmh)
    }

    private fun applyRiderSpeedText(text: String, fromMetrics: Boolean) {
        if (fromMetrics) {
            lastMetricsRiderSpeedUpdateMs = SystemClock.elapsedRealtime()
        }
        if (lastRiderSpeedText == text) return
        txtRiderSpeed.text = text
        lastRiderSpeedText = text
    }

    private fun logActivity(message: String) {
        // Unified session log line (no extra per-activity files).
        // S,<ts_ms>,<message>
        val tsMs = System.currentTimeMillis()
        val clean = message.replace("\n", " ").replace("\r", " ").trim()
        val escaped = "\"" + clean.replace("\"", "\"\"") + "\""
        PublicLogWriter.appendLogLine(this, SessionLogFile.fileName, "S,$tsMs,$escaped")
    }

    private enum class PendingAction {
        START_ENGINE,
        OPEN_CAMERA
    }
}
