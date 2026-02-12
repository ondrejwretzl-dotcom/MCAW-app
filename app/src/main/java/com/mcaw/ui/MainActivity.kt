package com.mcaw.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.app.BuildConfig
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.location.SpeedMonitor
import com.mcaw.location.SpeedProvider
import com.mcaw.service.McawService
import com.mcaw.util.PublicLogWriter
import com.mcaw.util.LabelMapper

class MainActivity : ComponentActivity() {

    private lateinit var txtStatus: TextView
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
    private lateinit var panelMetrics: View
    private lateinit var brakeLamp: TextView
    private lateinit var txtBrakeLamp: TextView
    private var lastBrakeLampUiState: Boolean = false

    private var pulseAnimator: ValueAnimator? = null
    private var pendingAction: PendingAction? = null
    private lateinit var speedProvider: SpeedProvider
    private lateinit var speedMonitor: SpeedMonitor
    private var speedMonitorStarted: Boolean = false

    private val logLines: ArrayDeque<String> = ArrayDeque()
    private val speedHandler = Handler(Looper.getMainLooper())
    private var activityLogFileName: String = ""

    // --- Rider speed UX stabilization ---
    // Metrics from DetectionAnalyzer should be the primary source (when camera/service runs).
    // SpeedMonitor polling stays as a fallback only when metrics haven't arrived recently.
    private var lastMetricsRiderSpeedUpdateMs: Long = 0L
    private var lastRiderSpeedText: String? = null

    private val metricsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent == null) return

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
            val label = intent.getStringExtra(DetectionAnalyzer.EXTRA_LABEL)
            val brakeCue =
                intent.getBooleanExtra("brake_cue", false) || intent.getBooleanExtra("extra_brake_cue", false)

            txtTtc.text = if (ttc.isFinite()) "TTC: %.2f s".format(ttc) else "TTC: --.- s"
            txtDistance.text =
                if (distance.isFinite()) "Vzdálenost: %.2f m".format(distance) else
                    "Vzdálenost: --.- m"

            // Rider speed from analyzer (primary)
            val riderText = formatRiderSpeedFromMps(riderSpeed)
            applyRiderSpeedText(riderText, fromMetrics = true)

            val speedKmh = if (speed.isFinite()) speed * 3.6f else Float.POSITIVE_INFINITY
            val objKmh = if (objectSpeed.isFinite()) objectSpeed * 3.6f else Float.POSITIVE_INFINITY

            txtSpeed.text =
                if (speedKmh.isFinite()) "Rychlost přiblížení: %.1f km/h".format(speedKmh) else
                    "Rychlost přiblížení: --.- km/h"
            txtObjectSpeed.text =
                if (objKmh.isFinite()) "Rychlost objektu: %.1f km/h".format(objKmh) else
                    "Rychlost objektu: --.- km/h"

            val mappedLabel = LabelMapper.mapLabel(label)
            txtDetectedObject.text = if (mappedLabel.isNotBlank()) {
                "Detekovaný objekt: $mappedLabel"
            } else {
                "Detekovaný objekt: --"
            }

            val overallColor = when (level) {
                2 -> android.graphics.Color.parseColor("#FF3B30")
                1 -> android.graphics.Color.parseColor("#FF9F0A")
                else -> android.graphics.Color.parseColor("#00E5A8")
            }

            // TTC barva má odpovídat zobrazené hodnotě TTC (ne "overall" levelu)
            val ttcLevel = ttcLevelForUi(ttc)
            val ttcColor = when (ttcLevel) {
                2 -> android.graphics.Color.parseColor("#FF3B30")
                1 -> android.graphics.Color.parseColor("#FF9F0A")
                else -> android.graphics.Color.parseColor("#00E5A8")
            }
            txtTtc.setTextColor(ttcColor)

            // Celkový stav (overall level) promítneme do statusu, aby bylo jasné, že může být horší než TTC
            txtStatus.setTextColor(overallColor)

            val riderKmhForUi = if (riderSpeed.isFinite()) riderSpeed * 3.6f else Float.POSITIVE_INFINITY
            applyVisualAlert(level, ttcLevel, riderKmhForUi)

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

    private val requiredPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtTtc = findViewById(R.id.txtTtc)
        txtDistance = findViewById(R.id.txtDistance)
        txtSpeed = findViewById(R.id.txtSpeed)
        txtObjectSpeed = findViewById(R.id.txtObjectSpeed)
        txtRiderSpeed = findViewById(R.id.txtRiderSpeed)
        txtDetectedObject = findViewById(R.id.txtDetectedObject)
        txtActivityLog = findViewById(R.id.txtActivityLog)
        txtBuildInfo = findViewById(R.id.txtBuildInfo)
        root = findViewById(R.id.root)
        panelMetrics = findViewById(R.id.panelMetrics)
        brakeLamp = findViewById(R.id.brakeLamp)
        txtBrakeLamp = findViewById(R.id.txtBrakeLamp)
        updateBrakeLamp(false)

        speedProvider = SpeedProvider(this)
        speedMonitor = SpeedMonitor(speedProvider)
        activityLogFileName = "mcaw_activity_${sessionStamp()}.txt"

        txtBuildInfo.text =
            "MCAW ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_ID}"
        addLog("Aplikace spuštěna")
        writeSessionLog("App start")
        logActivity("app_start build=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ensurePermissions(PendingAction.START_ENGINE)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopEngine() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            writeSessionLog("Open settings")
            logActivity("open_settings")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            ensurePermissions(PendingAction.OPEN_CAMERA)
        }
    }

    private fun writeSessionLog(event: String) {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val content = buildString {
            append("event=")
            append(event)
            append('\n')
            append("build_name=")
            append(BuildConfig.VERSION_NAME)
            append('\n')
            append("build_code=")
            append(BuildConfig.VERSION_CODE)
            append('\n')
            append("build_id=")
            append(BuildConfig.BUILD_ID)
            append('\n')
        }
        PublicLogWriter.writeTextFile(this, "mcaw_session_$timestamp.txt", content)
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
        txtStatus.text = "Služba: BĚŽÍ"
        addLog("Služba spuštěna")
        logActivity("service_start")
    }

    private fun stopEngine() {
        stopService(Intent(this, McawService::class.java))
        serviceRunning = false
        txtStatus.text = "Služba: ZASTAVENA"
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
        while (logLines.size > 6) {
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
        return when (AppPreferences.detectionMode) {
            1 -> AlertThresholds(ttcOrange = 4.0f, ttcRed = 1.5f)
            2 -> AlertThresholds(
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
        txtStatus.text = when {
            !serviceRunning -> "Služba: ZASTAVENA"
            riderStanding -> "Služba: AKTIVNÍ · stojíš (alert vypnut)"
            overallLevel == 2 -> "Služba: AKTIVNÍ · KRITICKÉ"
            overallLevel == 1 -> "Služba: AKTIVNÍ · VAROVÁNÍ"
            else -> "Služba: AKTIVNÍ · OK"
        }

        val bgColor = when (overallLevel) {
            2 -> android.graphics.Color.parseColor("#331B1B")
            1 -> android.graphics.Color.parseColor("#332514")
            else -> android.graphics.Color.parseColor("#1E222A")
        }
        txtStatus.setBackgroundColor(bgColor)

        val ttcBg = when (ttcLevel) {
            2 -> android.graphics.Color.parseColor("#331B1B")
            1 -> android.graphics.Color.parseColor("#332514")
            else -> android.graphics.Color.TRANSPARENT
        }
        txtTtc.setBackgroundColor(ttcBg)

        val shouldPulse = serviceRunning && !riderStanding && overallLevel > 0
        if (shouldPulse) startPulse(overallLevel) else stopPulse()
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
        if (!speedMps.isFinite() || speedMps < 0f) return "Rychlost jezdce: --.- km/h"

        // deadband (GPS jitter at low speeds)
        val deadbandMps = 0.5f // ~1.8 km/h
        val v = if (speedMps < deadbandMps) 0f else speedMps.coerceIn(0f, 80f)
        val kmh = v * 3.6f
        return "Rychlost jezdce: %.1f km/h".format(kmh)
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
        val timestamp =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(System.currentTimeMillis())
        val content = "ts=$timestamp $message"
        PublicLogWriter.appendLogLine(this, activityLogFileName, content)
    }

    private fun sessionStamp(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(System.currentTimeMillis())
    }

    private enum class PendingAction {
        START_ENGINE,
        OPEN_CAMERA
    }
}

