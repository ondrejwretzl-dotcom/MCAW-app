package com.mcaw.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner
import androidx.activity.ComponentActivity
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.ensureInit(this)
        setContentView(R.layout.activity_settings)

        val spMode = findViewById<Spinner>(R.id.spMode)
        val spModel = findViewById<Spinner>(R.id.spModel)
        val groupUser = findViewById<View>(R.id.groupUserThresholds)
        val txtModeDetails = findViewById<android.widget.TextView>(R.id.txtModeDetails)

        val etTtcOrange = findViewById<EditText>(R.id.etTtcOrange)
        val etTtcRed = findViewById<EditText>(R.id.etTtcRed)
        val etDistOrange = findViewById<EditText>(R.id.etDistOrange)
        val etDistRed = findViewById<EditText>(R.id.etDistRed)
        val etSpeedOrange = findViewById<EditText>(R.id.etSpeedOrange)
        val etSpeedRed = findViewById<EditText>(R.id.etSpeedRed)

        val swSound = findViewById<SwitchMaterial>(R.id.swSound)
        val swVibration = findViewById<SwitchMaterial>(R.id.swVibration)
        val swVoice = findViewById<SwitchMaterial>(R.id.swVoice)
        val swDebug = findViewById<SwitchMaterial>(R.id.swDebug)

        spMode.setSelection(AppPreferences.detectionMode)
        spModel.setSelection(AppPreferences.selectedModel)

        groupUser.visibility = if (AppPreferences.detectionMode == 2) View.VISIBLE else View.GONE
        txtModeDetails.text = modeSummary(AppPreferences.detectionMode)

        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                AppPreferences.detectionMode = position
                groupUser.visibility = if (position == 2) View.VISIBLE else View.GONE
                txtModeDetails.text = modeSummary(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                AppPreferences.selectedModel = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        swSound.isChecked = AppPreferences.sound
        swVibration.isChecked = AppPreferences.vibration
        swVoice.isChecked = AppPreferences.voice
        swDebug.isChecked = AppPreferences.debugOverlay

        swSound.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.sound = isChecked
        }
        swVibration.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.vibration = isChecked
        }
        swVoice.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.voice = isChecked
        }
        swDebug.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.debugOverlay = isChecked
        }

        fun applyUserThresholds() {
            AppPreferences.userTtcOrange = readFloat(etTtcOrange, AppPreferences.userTtcOrange)
            AppPreferences.userTtcRed = readFloat(etTtcRed, AppPreferences.userTtcRed)
            AppPreferences.userDistOrange = readFloat(etDistOrange, AppPreferences.userDistOrange)
            AppPreferences.userDistRed = readFloat(etDistRed, AppPreferences.userDistRed)
            AppPreferences.userSpeedOrange = readFloat(etSpeedOrange, AppPreferences.userSpeedOrange)
            AppPreferences.userSpeedRed = readFloat(etSpeedRed, AppPreferences.userSpeedRed)
        }

        fun bindDefaults() {
            etTtcOrange.setText(AppPreferences.userTtcOrange.toString())
            etTtcRed.setText(AppPreferences.userTtcRed.toString())
            etDistOrange.setText(AppPreferences.userDistOrange.toString())
            etDistRed.setText(AppPreferences.userDistRed.toString())
            etSpeedOrange.setText(AppPreferences.userSpeedOrange.toString())
            etSpeedRed.setText(AppPreferences.userSpeedRed.toString())
        }

        bindDefaults()

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyUserThresholds()
            }
        }

        etTtcOrange.addTextChangedListener(watcher)
        etTtcRed.addTextChangedListener(watcher)
        etDistOrange.addTextChangedListener(watcher)
        etDistRed.addTextChangedListener(watcher)
        etSpeedOrange.addTextChangedListener(watcher)
        etSpeedRed.addTextChangedListener(watcher)
    }

    private fun readFloat(editText: EditText, fallback: Float): Float {
        val value = editText.text?.toString()?.trim()
        return value?.toFloatOrNull() ?: fallback
    }

    private fun modeSummary(mode: Int): String {
        return when (mode) {
            0 -> {
                "Město: OK > 4.0 s / 20 m / 3 m/s · " +
                    "Upozornění ≤ 3.0 s / 15 m / 3 m/s · " +
                    "Kritické ≤ 1.2 s / 6 m / 5 m/s"
            }
            1 -> {
                "Sport: OK > 5.0 s / 40 m / 5 m/s · " +
                    "Upozornění ≤ 4.0 s / 30 m / 5 m/s · " +
                    "Kritické ≤ 1.5 s / 12 m / 9 m/s"
            }
            else -> "Uživatel: nastavte vlastní prahy TTC, vzdálenosti a rychlosti."
        }
    }
}
