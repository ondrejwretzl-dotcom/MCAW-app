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

        findViewById<Button>(R.id.btnStart).setOnClickListener { startEngine() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopEngine() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }
    }

    private fun startEngine() {
        if (!requiredPerms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, requiredPerms, 1001)
            return
        }
        startService(Intent(this, McawService::class.java))
        txtStatus.text = "Služba: BÌŽÍ"
    }

    private fun stopEngine() {
        stopService(Intent(this, McawService::class.java))
        txtStatus.text = "Služba: ZASTAVENA"
    }
}
