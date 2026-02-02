package com.mcaw.ui

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import com.mcaw.app.R
import com.mcaw.config.AppPreferences

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.init(this)
        setContentView(R.layout.activity_settings)

        val mode = findViewById<Spinner>(R.id.spMode)
        val model = findViewById<Spinner>(R.id.spModel)

        val swSound = findViewById<Switch>(R.id.swSound)
        val swVibration = findViewById<Switch>(R.id.swVibration)
        val swVoice = findViewById<Switch>(R.id.swVoice)
        val swDebug = findViewById<Switch>(R.id.swDebug)

        // Load saved
        swSound.isChecked = AppPreferences.sound
        swVibration.isChecked = AppPreferences.vibration
        swVoice.isChecked = AppPreferences.voice
        swDebug.isChecked = AppPreferences.debugOverlay

        mode.setSelection(AppPreferences.detectionMode - 1)
        model.setSelection(AppPreferences.selectedModel - 1)

        // Save interactions
        swSound.setOnCheckedChangeListener { _, v -> AppPreferences.sound = v }
        swVibration.setOnCheckedChangeListener { _, v -> AppPreferences.vibration = v }
        swVoice.setOnCheckedChangeListener { _, v -> AppPreferences.voice = v }
        swDebug.setOnCheckedChangeListener { _, v -> AppPreferences.debugOverlay = v }

        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {}
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                pos: Int, id: Long
            ) {
                AppPreferences.detectionMode = pos + 1
            }
        }

        model.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {}
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                pos: Int, id: Long
            ) {
                AppPreferences.selectedModel = pos + 1
            }
        }
    }
}
