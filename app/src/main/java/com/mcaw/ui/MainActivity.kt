package com.mcaw.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mcaw.app.R
import com.mcaw.service.McawService

/**
 * MainActivity – hlavní øídící obrazovka
 * --------------------------------------
 * - Start/Stop Foreground služby (McawService) – ML engine
 * - Spuštìní PreviewActivity, kde bìží kamera
 * - Permission handling (CAMERA + GPS)
 * - Zobrazení stavu služby
 */
class MainActivity : ComponentActivity() {

    private lateinit var txtStatus: TextView

    private val requiredPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)

        // Tlaèítka
        findViewById<Button>(R.id.btnStart).setOnClickListener { startEngine() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopEngine() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Kamera (PreviewActivity)
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            val i = Intent(this, PreviewActivity::class.java)
            startActivity(i)
        }

        updateStatus(false)
    }

    // -------------------------------------------------------------
    // START ENGINE (Foreground Service)
    // -------------------------------------------------------------
    private fun startEngine() {
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPerms, 1001)
            return
        }

        val i = Intent(this, McawService::class.java)
        ContextCompat.startForegroundService(this, i)
        updateStatus(true)
    }

    // -------------------------------------------------------------
    // STOP ENGINE
    // -------------------------------------------------------------
    private fun stopEngine() {
        stopService(Intent(this, McawService::class.java))
        updateStatus(false)
    }

    // -------------------------------------------------------------
    // STATUS TEXT
    // -------------------------------------------------------------
    private fun updateStatus(running: Boolean) {
        txtStatus.text = if (running) {
            "Služba: BÌŽÍ"
        } else {
            "Služba: ZASTAVENA"
        }
    }

    // -------------------------------------------------------------
    // PERMISSIONS
    // -------------------------------------------------------------
    private fun hasAllPermissions(): Boolean =
        requiredPerms.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1001 &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startEngine()
        }
    }
}
