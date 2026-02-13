package com.mcaw.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.util.PublicLogWriter

class SettingsActivity : ComponentActivity() {

    private fun openActivitySafely(intent: Intent, title: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showInfo(
                title = title,
                msg = "Tato obrazovka není v aplikaci dostupná (není zaregistrovaná v manifestu nebo chybí v build variantě).\n\n" +
                    "Pokud tohle vidíš po update, zkontroluj prosím AndroidManifest.xml a že jsou Activity přidané."
            )
        } catch (t: Throwable) {
            showInfo(
                title = title,
                msg = "Nepodařilo se otevřít obrazovku: ${t.javaClass.simpleName}: ${t.message}".trim()
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.ensureInit(this)
        setContentView(R.layout.activity_settings)
        writeSessionLog("Settings opened")

        // Top actions
        findViewById<View>(R.id.btnOpenHelp)?.setOnClickListener {
            writeSessionLog("Open help")
            openActivitySafely(Intent(this, HelpActivity::class.java), title = "Návod")
        }
        findViewById<View>(R.id.btnOpenLegal)?.setOnClickListener {
            writeSessionLog("Open legal")
            openActivitySafely(Intent(this, LegalActivity::class.java), title = "Upozornění a podmínky")
        }
        findViewById<View>(R.id.btnResetRecommended)?.setOnClickListener { confirmResetRecommended() }

        val spMode = findViewById<Spinner>(R.id.spMode)
        val spModel = findViewById<Spinner>(R.id.spModel)
        val groupUser = findViewById<View>(R.id.groupUserThresholds)
        val txtModeDetails = findViewById<TextView>(R.id.txtModeDetails)

        val etTtcOrange = findViewById<EditText>(R.id.etTtcOrange)
        val etTtcRed = findViewById<EditText>(R.id.etTtcRed)
        val etDistOrange = findViewById<EditText>(R.id.etDistOrange)
        val etDistRed = findViewById<EditText>(R.id.etDistRed)
        val etSpeedOrange = findViewById<EditText>(R.id.etSpeedOrange)
        val etSpeedRed = findViewById<EditText>(R.id.etSpeedRed)

        val etTtsOrange = findViewById<EditText>(R.id.etTtsOrange)
        val etTtsRed = findViewById<EditText>(R.id.etTtsRed)

        val swSound = findViewById<SwitchMaterial>(R.id.swSound)
        val swVibration = findViewById<SwitchMaterial>(R.id.swVibration)
        val swVoice = findViewById<SwitchMaterial>(R.id.swVoice)
        val swDebug = findViewById<SwitchMaterial>(R.id.swDebug)
        val swLaneFilter = findViewById<SwitchMaterial>(R.id.swLaneFilter)
        val swRoiStrictContainment = findViewById<SwitchMaterial>(R.id.swRoiStrictContainment)
        val swBrakeCue = findViewById<SwitchMaterial>(R.id.swBrakeCue)

        val spBrakeCueSensitivity = findViewById<Spinner>(R.id.spBrakeCueSensitivity)

        val swSoundOrange = findViewById<SwitchMaterial>(R.id.swSoundOrange)
        val swSoundRed = findViewById<SwitchMaterial>(R.id.swSoundRed)
        val swVoiceOrange = findViewById<SwitchMaterial>(R.id.swVoiceOrange)
        val swVoiceRed = findViewById<SwitchMaterial>(R.id.swVoiceRed)

        // New advanced controls
        val swQualityGating = findViewById<SwitchMaterial>(R.id.swQualityGating)
        val swCutInProtection = findViewById<SwitchMaterial>(R.id.swCutInProtection)
        val sliderLaneWidth = findViewById<Slider>(R.id.sliderLaneWidth)
        val sliderDistanceScale = findViewById<Slider>(R.id.sliderDistanceScale)
        val txtLaneWidthValue = findViewById<TextView>(R.id.txtLaneWidthValue)
        val txtDistanceScaleValue = findViewById<TextView>(R.id.txtDistanceScaleValue)

        // Camera calibration
        val etCameraMountHeight: EditText? = findViewById(R.id.etCameraMountHeight)
        val etCameraPitchDownDeg: EditText? = findViewById(R.id.etCameraPitchDownDeg)

        // Info icons (optional)
        findViewById<View>(R.id.btnInfoLaneFilter)?.setOnClickListener {
            showInfo(
                title = "Omezit detekci na jízdní dráhu",
                msg = "Omezuje detekci jen na objekty v jízdní dráze (trapezoid ROI). " +
                    "Snižuje falešné poplachy (boky, protisměr, parkovaná auta). " +
                    "Nevýhoda: může krátce přehlédnout vozidlo rychle najíždějící ze strany."
            )
        }
        findViewById<View>(R.id.btnInfoLaneWidth)?.setOnClickListener {
            showInfo(
                title = "Šířka pruhu (tolerance do stran)",
                msg = "Určuje, jak moc může být cíl vychýlený od středu ROI a stále se považuje za „před tebou“. " +
                    "Menší = přesnější, méně rušení. Větší = zachytí i cut‑in, ale může brát bokové cíle."
            )
        }
        findViewById<View>(R.id.btnInfoDistanceScale)?.setOnClickListener {
            showInfo(
                title = "Kalibrace vzdálenosti",
                msg = "Doladění odhadu vzdálenosti podle telefonu a uchycení. " +
                    "Pokud aplikace hlásí vzdálenost systematicky kratší/delší, uprav toto."
            )
        }

        findViewById<View>(R.id.btnInfoCameraMountHeight)?.setOnClickListener {
            showInfo(
                title = "Výška kamery (m)",
                msg = """Výška čočky kamery nad zemí. Používá se pro přesnější odhad vzdálenosti.

• Auto typicky 1.1–1.5 m
• Moto často 0.9–1.3 m (podle držáku)

Pokud aplikace systematicky přeceňuje/podceňuje vzdálenost, zkontroluj i tento parametr.""".trimIndent()
            )
        }
        findViewById<View>(R.id.btnInfoCameraPitchDownDeg)?.setOnClickListener {
            showInfo(
                title = "Sklon kamery dolů (°)",
                msg = """Náklon kamery směrem k zemi. 0° = horizont, kladné hodnoty = dolů.

Typicky 3–10°. Příliš velký sklon může zkrátit dohled; příliš malý může zkreslit odhad vzdálenosti.""".trimIndent()
            )
        }
        findViewById<View>(R.id.btnInfoQualityGating)?.setOnClickListener {
            showInfo(
                title = "Omezit falešné alarmy v noci/rozmazání",
                msg = "Když je obraz moc tmavý nebo rozmazaný (vibrace), aplikace zpřísní varování, aby zbytečně nerušila."
            )
        }
        findViewById<View>(R.id.btnInfoCutIn)?.setOnClickListener {
            showInfo(
                title = "Ochrana proti cut‑in",
                msg = "Pomáhá zachytit auta rychle najíždějící do tvé dráhy i když jsou ještě bokem."
            )
        }

        // Mode spinner
        ArrayAdapter.createFromResource(
            this,
            R.array.modes,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spMode.adapter = adapter
        }

        spMode.setSelection(normalizeSelection(AppPreferences.detectionMode, spMode))
        txtModeDetails.text = modeSummary(AppPreferences.detectionMode)

        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                AppPreferences.detectionMode = position
                txtModeDetails.text = modeSummary(position)
                groupUser.visibility = if (position == 2) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        // Model spinner
        ArrayAdapter.createFromResource(
            this,
            R.array.models,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spModel.adapter = adapter
        }

        spModel.setSelection(normalizeSelection(AppPreferences.selectedModel, spModel))
        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                AppPreferences.selectedModel = position
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        // Alert switches
        fun bindSwitch(sw: SwitchMaterial, getter: () -> Boolean, setter: (Boolean) -> Unit) {
            sw.isChecked = getter()
            sw.setOnCheckedChangeListener { _, checked -> setter(checked) }
        }

        bindSwitch(swSound, { AppPreferences.sound }, { AppPreferences.sound = it })
        bindSwitch(swVibration, { AppPreferences.vibration }, { AppPreferences.vibration = it })
        bindSwitch(swVoice, { AppPreferences.voice }, { AppPreferences.voice = it })
        bindSwitch(swDebug, { AppPreferences.debugOverlay }, { AppPreferences.debugOverlay = it })
        bindSwitch(swLaneFilter, { AppPreferences.laneFilter }, { AppPreferences.laneFilter = it })
        bindSwitch(swRoiStrictContainment, { AppPreferences.roiStrictContainment }, { AppPreferences.roiStrictContainment = it })
        bindSwitch(swBrakeCue, { AppPreferences.brakeCueEnabled }, { AppPreferences.brakeCueEnabled = it })

        bindSwitch(swSoundOrange, { AppPreferences.soundOrange }, { AppPreferences.soundOrange = it })
        bindSwitch(swSoundRed, { AppPreferences.soundRed }, { AppPreferences.soundRed = it })
        bindSwitch(swVoiceOrange, { AppPreferences.voiceOrange }, { AppPreferences.voiceOrange = it })
        bindSwitch(swVoiceRed, { AppPreferences.voiceRed }, { AppPreferences.voiceRed = it })

        // New advanced toggles
        if (swQualityGating != null) bindSwitch(swQualityGating, { AppPreferences.qualityGatingEnabled }, { AppPreferences.qualityGatingEnabled = it })
        if (swCutInProtection != null) bindSwitch(swCutInProtection, { AppPreferences.cutInProtectionEnabled }, { AppPreferences.cutInProtectionEnabled = it })

        // Brake cue sensitivity spinner (existing logic)
        val brakeItems = arrayOf("Nízká", "Střední", "Vysoká")
        spBrakeCueSensitivity.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, brakeItems)
        spBrakeCueSensitivity.setSelection(AppPreferences.brakeCueSensitivity.coerceIn(0, brakeItems.size - 1))
        spBrakeCueSensitivity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                AppPreferences.brakeCueSensitivity = position
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        // User thresholds
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

            // Camera calibration
            etCameraMountHeight?.setText(AppPreferences.cameraMountHeightM.toString())
            etCameraPitchDownDeg?.setText(AppPreferences.cameraPitchDownDeg.toString())

            // Advanced sliders
            bindLaneWidthSlider(sliderLaneWidth, txtLaneWidthValue)
            bindDistanceScaleSlider(sliderDistanceScale, txtDistanceScaleValue)

            // User thresholds group visibility based on mode
            groupUser.visibility = if (AppPreferences.detectionMode == 2) View.VISIBLE else View.GONE
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

        // Camera calibration inputs
        val cameraWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Store only when fields exist; AppPreferences clamps ranges.
                AppPreferences.cameraMountHeightM =
                    readFloatNullable(etCameraMountHeight, AppPreferences.cameraMountHeightM)
                AppPreferences.cameraPitchDownDeg =
                    readFloatNullable(etCameraPitchDownDeg, AppPreferences.cameraPitchDownDeg)
            }
        }
        etCameraMountHeight?.addTextChangedListener(cameraWatcher)
        etCameraPitchDownDeg?.addTextChangedListener(cameraWatcher)

    }

    private fun bindLaneWidthSlider(slider: Slider?, valueView: TextView?) {
        if (slider == null) return
        slider.valueFrom = 0f
        slider.valueTo = 2f
        slider.stepSize = 1f

        val idx = when {
            AppPreferences.laneEgoMaxOffset < 0.50f -> 0f
            AppPreferences.laneEgoMaxOffset < 0.65f -> 1f
            else -> 2f
        }
        slider.value = idx
        valueView?.text = when (idx.toInt()) {
            0 -> "Úzký"
            1 -> "Střední"
            else -> "Široký"
        }

        slider.addOnChangeListener { s, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val mapped = when (v.toInt()) {
                0 -> 0.45f
                1 -> 0.55f
                else -> 0.70f
            }
            AppPreferences.laneEgoMaxOffset = mapped
            valueView?.text = when (v.toInt()) {
                0 -> "Úzký"
                1 -> "Střední"
                else -> "Široký"
            }
        }
    }

    private fun bindDistanceScaleSlider(slider: Slider?, valueView: TextView?) {
        if (slider == null) return
        slider.valueFrom = 0f
        slider.valueTo = 2f
        slider.stepSize = 1f

        val idx = when {
            AppPreferences.distanceScale < 0.95f -> 0f
            AppPreferences.distanceScale > 1.05f -> 2f
            else -> 1f
        }
        slider.value = idx
        valueView?.text = when (idx.toInt()) {
            0 -> "Méně"
            1 -> "OK"
            else -> "Více"
        }

        slider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val mapped = when (v.toInt()) {
                0 -> 0.90f
                1 -> 1.00f
                else -> 1.10f
            }
            AppPreferences.distanceScale = mapped
            valueView?.text = when (v.toInt()) {
                0 -> "Méně"
                1 -> "OK"
                else -> "Více"
            }
        }
    }

    private fun confirmResetRecommended() {
        AlertDialog.Builder(this)
            .setTitle("Reset na doporučené")
            .setMessage(
                """Vrátí vybrané volby na doporučené hodnoty (nezmění zvuk/hlas, model ani režim).

• Omezit detekci na jízdní dráhu
• Šířka pruhu (tolerance)
• Kalibrace vzdálenosti
• Omezit falešné alarmy v noci/rozmazání
• Ochrana proti cut‑in""".trimIndent()
            )
            .setNegativeButton("Zrušit", null)
            .setPositiveButton("Resetovat", DialogInterface.OnClickListener { _: DialogInterface, _: Int ->
                resetRecommended()
                writeSessionLog("Reset recommended")
            })
            .show()
    }

private fun resetRecommended() {
        // defaults tuned for "city" / typical use, safe for Samsung A56
        AppPreferences.laneFilter = true
        AppPreferences.laneEgoMaxOffset = 0.55f
        AppPreferences.distanceScale = 1.0f
        AppPreferences.qualityGatingEnabled = true
        AppPreferences.cutInProtectionEnabled = true

        // Refresh UI (simple approach: recreate)
        recreate()
    }

    private fun showInfo(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }


    private fun readFloat(editText: EditText, fallback: Float): Float {
        val value = editText.text?.toString()?.trim()
        return value?.toFloatOrNull() ?: fallback
    }

    private fun readFloatNullable(editText: EditText?, fallback: Float): Float {
        if (editText == null) return fallback
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
