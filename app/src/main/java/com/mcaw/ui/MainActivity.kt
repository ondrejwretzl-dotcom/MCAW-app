package com.mcaw.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mcaw.app.R
import com.mcaw.service.McawService

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

        findViewById<Button>(R.id.btnStart).setOnClickListener { startEngineSafe() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopEngine() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }
    }

    private fun startEngineSafe() {
        if (!requiredPerms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, requiredPerms, 1001)
            return
        }
        startEngine()
    }

    private fun startEngine() {
        val intent = Intent(this, McawService::class.java)
        ContextCompat.startForegroundService(this, intent)
        txtStatus.text = "Služba: BÌŽÍ"
    }

    private fun stopEngine() {
        stopService(Intent(this, McawService::class.java))
        txtStatus.text = "Služba: ZASTAVENA"
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
            startEngine()
        }
    }
}

