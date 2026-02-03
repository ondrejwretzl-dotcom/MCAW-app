package com.mcaw.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import androidx.activity.ComponentActivity
import com.mcaw.app.R
import com.mcaw.config.AppPreferences

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val spMode = findViewById<Spinner>(R.id.spMode)
        val spModel = findViewById<Spinner>(R.id.spModel)
        val groupUser = findViewById<View>(R.id.groupUserThresholds)

        val etTtcOrange = findViewById<EditText>(R.id.etTtcOrange)
        val etTtcRed = findViewById<EditText>(R.id.etTtcRed)
        val etDistOrange = findViewById<EditText>(R.id.etDistOrange)
        val etDistRed = findViewById<EditText>(R.id.etDistRed)
        val etSpeedOrange = findViewById<EditText>(R.id.etSpeedOrange)
        val etSpeedRed = findViewById<EditText>(R.id.etSpeedRed)

        val swSound = findViewById<Switch>(R.id.swSound)
        val swVibration = findViewById<Switch>(R.id.swVibration)
        val swVoice = findViewById<Switch>(R.id.swVoice)
        val swDebug = findViewById<Switch>(R.id.swDebug)

        spMode.setSelection(AppPreferences.detectionMode)
        spModel.setSelection(AppPreferences.selectedModel)

        groupUser.visibility = if (AppPreferences.detectionMode == 2) View.VISIBLE else View.GONE

        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                AppPreferences.detectionMode = position
                groupUser.visibility = if (position == 2) View.VISIBLE else View.GONE
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
}
