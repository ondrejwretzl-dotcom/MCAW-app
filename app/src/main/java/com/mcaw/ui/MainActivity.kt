package com.mcaw.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.app.BuildConfig
import com.mcaw.app.R
import com.mcaw.location.SpeedMonitor
import com.mcaw.service.McawService
import com.mcaw.util.PublicLogWriter

class MainActivity : ComponentActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtTtc: TextView
    private lateinit var txtDistance: TextView
    private lateinit var txtSpeed: TextView
    private lateinit var txtObjectSpeed: TextView
    private lateinit var txtRiderSpeed: TextView
    private lateinit var txtActivityLog: TextView
    private lateinit var txtBuildInfo: TextView
    private var pendingAction: PendingAction? = null
    private lateinit var speedMonitor: SpeedMonitor
    private val logLines: ArrayDeque<String> = ArrayDeque()
    private val speedHandler = Handler(Looper.getMainLooper())
    private var activityLogFileName: String = ""

    private val metricsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent == null) return
            val ttc = intent.getFloatExtra(DetectionAnalyzer.EXTRA_TTC, Float.POSITIVE_INFINITY)
            val distance =
                intent.getFloatExtra(DetectionAnalyzer.EXTRA_DISTANCE, Float.POSITIVE_INFINITY)
            val speed = intent.getFloatExtra(DetectionAnalyzer.EXTRA_SPEED, Float.POSITIVE_INFINITY)
            val objectSpeed =
                intent.getFloatExtra(DetectionAnalyzer.EXTRA_OBJECT_SPEED, Float.POSITIVE_INFINITY)
            val level = intent.getIntExtra(DetectionAnalyzer.EXTRA_LEVEL, 0)

            txtTtc.text = if (ttc.isFinite()) "TTC: %.2f s".format(ttc) else "TTC: --.- s"
            txtDistance.text =
                if (distance.isFinite()) "Vzdálenost: %.2f m".format(distance) else
                    "Vzdálenost: --.- m"
            txtSpeed.text =
                if (speed.isFinite()) "Rychlost přiblížení: %.2f m/s".format(speed) else
                    "Rychlost přiblížení: --.- m/s"
            txtObjectSpeed.text =
                if (objectSpeed.isFinite()) "Rychlost objektu: %.2f m/s".format(objectSpeed) else
                    "Rychlost objektu: --.- m/s"

            val color = when (level) {
                2 -> android.graphics.Color.parseColor("#FF3B30")
                1 -> android.graphics.Color.parseColor("#FF9F0A")
                else -> android.graphics.Color.parseColor("#00E5A8")
            }
            txtTtc.setTextColor(color)
            addLog(
                "Metriky: TTC ${formatMetric(ttc, "s")} · " +
                    "Vzdálenost ${formatMetric(distance, "m")} · " +
                    "Rel ${formatMetric(speed, "m/s")}"
            )
            logActivity(
                "metrics ttc=${formatMetric(ttc, "s")} dist=${formatMetric(distance, "m")} " +
                    "rel=${formatMetric(speed, "m/s")} obj=${formatMetric(objectSpeed, "m/s")} " +
                    "level=$level"
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
        txtActivityLog = findViewById(R.id.txtActivityLog)
        txtBuildInfo = findViewById(R.id.txtBuildInfo)
        speedMonitor = SpeedMonitor(this)
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
            speedMonitor.start()
            addLog("Ověřeno oprávnění: vše v pořádku")
            logActivity("permissions_ok")
            return
        }
        pendingAction = action
        ActivityCompat.requestPermissions(this, requiredPerms, 1001)
        addLog("Žádost o oprávnění: kamera + poloha")
        logActivity("permissions_request")
    }

    private fun startEngine() {
        val intent = Intent(this, McawService::class.java)
        ContextCompat.startForegroundService(this, intent)
        txtStatus.text = "Služba: BĚŽÍ"
        addLog("Služba spuštěna")
        logActivity("service_start")
    }

    private fun stopEngine() {
        stopService(Intent(this, McawService::class.java))
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
            speedMonitor.start()
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
        if (requiredPerms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            speedMonitor.start()
            addLog("GPS monitor spuštěn")
            logActivity("gps_monitor_start")
        }
        startSpeedUpdates()
    }

    override fun onStop() {
        unregisterReceiver(metricsReceiver)
        speedMonitor.stop()
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

    private fun formatMetric(value: Float, unit: String): String {
        return if (value.isFinite()) "%.2f %s".format(value, unit) else "--.- $unit"
    }

    private fun startSpeedUpdates() {
        speedHandler.post(object : Runnable {
            override fun run() {
                val speedMps = com.mcaw.config.AppPreferences.lastSpeedMps
                val speedKmh = speedMps * 3.6f
                txtRiderSpeed.text = "Rychlost jezdce: %.1f km/h".format(speedKmh)
                speedHandler.postDelayed(this, 1000L)
            }
        })
    }

    private fun stopSpeedUpdates() {
        speedHandler.removeCallbacksAndMessages(null)
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
