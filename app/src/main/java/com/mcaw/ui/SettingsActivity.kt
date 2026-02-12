package com.mcaw.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.activity.ComponentActivity
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mcaw.util.PublicLogWriter

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.ensureInit(this)
        setContentView(R.layout.activity_settings)
        writeSessionLog("Settings opened")

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

val swSoundOrange = findViewById<SwitchMaterial>(R.id.swSoundOrange)
val swSoundRed = findViewById<SwitchMaterial>(R.id.swSoundRed)
val swVoiceOrange = findViewById<SwitchMaterial>(R.id.swVoiceOrange)
val swVoiceRed = findViewById<SwitchMaterial>(R.id.swVoiceRed)
val etTtsOrange = findViewById<EditText>(R.id.etTtsOrange)
val etTtsRed = findViewById<EditText>(R.id.etTtsRed)

        val swDebug = findViewById<SwitchMaterial>(R.id.swDebug)
        val swLaneFilter = findViewById<SwitchMaterial>(R.id.swLaneFilter)
        val swRoiStrictContainment = findViewById<SwitchMaterial>(R.id.swRoiStrictContainment)

        val swBrakeCue = findViewById<SwitchMaterial>(R.id.swBrakeCue)
        val spBrakeCueSensitivity = findViewById<Spinner>(R.id.spBrakeCueSensitivity)

        val modeSelection = normalizeSelection(AppPreferences.detectionMode, spMode)
        val modelSelection = normalizeSelection(AppPreferences.selectedModel, spModel)
        if (modeSelection != AppPreferences.detectionMode) {
            AppPreferences.detectionMode = modeSelection
        }
        if (modelSelection != AppPreferences.selectedModel) {
            AppPreferences.selectedModel = modelSelection
        }

        spMode.setSelection(modeSelection)
        spModel.setSelection(modelSelection)

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
        swLaneFilter.isChecked = AppPreferences.laneFilter
        swRoiStrictContainment.isChecked = AppPreferences.roiStrictContainment

swSoundOrange.isChecked = AppPreferences.soundOrange
swSoundRed.isChecked = AppPreferences.soundRed
swVoiceOrange.isChecked = AppPreferences.voiceOrange
swVoiceRed.isChecked = AppPreferences.voiceRed


        swBrakeCue.isChecked = AppPreferences.brakeCueEnabled

        val brakeSensAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Nízká", "Standard", "Vysoká")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spBrakeCueSensitivity.adapter = brakeSensAdapter
        spBrakeCueSensitivity.setSelection(AppPreferences.brakeCueSensitivity.coerceIn(0, 2), false)

        swSound.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.sound = isChecked
        }
        swVibration.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.vibration = isChecked
        }
        swVoice.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.voice = isChecked
        }

swSoundOrange.setOnCheckedChangeListener { _, isChecked ->
    AppPreferences.soundOrange = isChecked
}
swSoundRed.setOnCheckedChangeListener { _, isChecked ->
    AppPreferences.soundRed = isChecked
}
swVoiceOrange.setOnCheckedChangeListener { _, isChecked ->
    AppPreferences.voiceOrange = isChecked
}
swVoiceRed.setOnCheckedChangeListener { _, isChecked ->
    AppPreferences.voiceRed = isChecked
}

        swDebug.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.debugOverlay = isChecked
        }
        swLaneFilter.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.laneFilter = isChecked
        }


swRoiStrictContainment.setOnCheckedChangeListener { _, isChecked ->
    AppPreferences.roiStrictContainment = isChecked
}

        swBrakeCue.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.brakeCueEnabled = isChecked
            writeSessionLog("BrakeCue enabled=$isChecked")
        }

        spBrakeCueSensitivity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppPreferences.brakeCueSensitivity = position.coerceIn(0, 2)
                writeSessionLog("BrakeCue sensitivity=${AppPreferences.brakeCueSensitivity}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
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
        
etTtsOrange.setText(AppPreferences.ttsTextOrange)
etTtsRed.setText(AppPreferences.ttsTextRed)

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

val ttsWatcher = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) {
        AppPreferences.ttsTextOrange = etTtsOrange.text?.toString() ?: AppPreferences.ttsTextOrange
        AppPreferences.ttsTextRed = etTtsRed.text?.toString() ?: AppPreferences.ttsTextRed
    }
}
etTtsOrange.addTextChangedListener(ttsWatcher)
etTtsRed.addTextChangedListener(ttsWatcher)

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

    private fun normalizeSelection(value: Int, spinner: Spinner): Int {
        val count = spinner.adapter?.count ?: 0
        if (count <= 0) return 0
        return value.coerceIn(0, count - 1)
    }

    private fun writeSessionLog(event: String) {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val content = buildString {
            append("event=")
            append(event)
            append('\n')
        }
        PublicLogWriter.writeTextFile(this, "mcaw_settings_$timestamp.txt", content)
    }
}
