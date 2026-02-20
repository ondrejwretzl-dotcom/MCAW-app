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
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.config.ProfileManager
import com.mcaw.config.MountProfile
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
        ProfileManager.ensureInit(this)
        setContentView(R.layout.activity_settings)
        writeSessionLog("Settings opened")


        // Profiles
        val spProfileSelect = findViewById<Spinner?>(R.id.spProfileSelect)
        val btnProfileCreate = findViewById<View?>(R.id.btnProfileCreate)
        val btnProfileDelete = findViewById<View?>(R.id.btnProfileDelete)
        val btnProfileOverwrite = findViewById<View?>(R.id.btnProfileOverwrite)
        val btnProfileClear = findViewById<View?>(R.id.btnProfileClear)
        var refreshMountUiFromPrefs: (() -> Unit)? = null

        fun refreshProfilesAndSelection() {
            val profiles = ProfileManager.listProfiles().sortedBy { it.name.lowercase() }
            val names = ArrayList<String>(profiles.size + 1)
            val ids = ArrayList<String?>(profiles.size + 1)
            names.add("Default (bez profilu)")
            ids.add(null)
            for (p in profiles) {
                names.add(p.name)
                ids.add(p.id)
            }
            spProfileSelect?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val activeId = ProfileManager.getActiveProfileIdOrNull()
            val sel = ids.indexOf(activeId).takeIf { it >= 0 } ?: 0
            spProfileSelect?.setSelection(sel, false)
            spProfileSelect?.tag = ids // store ids list for listener
        }

        spProfileSelect?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val ids = parent.tag as? List<*> ?: return
                val selectedId = ids.getOrNull(position) as? String
                // Avoid redundant apply
                if (selectedId == ProfileManager.getActiveProfileIdOrNull()) return
                ProfileManager.setActiveProfileId(selectedId)
                if (selectedId == null) {
                    Toast.makeText(this@SettingsActivity, "Použit Default (bez profilu)", Toast.LENGTH_SHORT).show()
                    writeSessionLog("Profile set to default")
                } else {
                    ProfileManager.applyActiveProfileToPreferences()
                    refreshMountUiFromPrefs?.invoke()
                    val name = ProfileManager.findById(selectedId)?.name ?: "?"
                    Toast.makeText(this@SettingsActivity, "Aktivní profil: $name", Toast.LENGTH_SHORT).show()
                    writeSessionLog("Profile set id=$selectedId name=$name")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        btnProfileCreate?.setOnClickListener {
            val input = EditText(this).apply { hint = "Název profilu"; setSingleLine() }
            AlertDialog.Builder(this)
                .setTitle("Uložit profil")
                .setMessage("Uloží aktuální ROI + kalibraci jako nový profil.")
                .setView(input)
                .setPositiveButton("Uložit") { _, _ ->
                    val name = input.text?.toString()?.trim().orEmpty()
                    val p = ProfileManager.saveProfileFromCurrentPrefs(name)
                    ProfileManager.setActiveProfileId(p.id)
                    ProfileManager.applyActiveProfileToPreferences()
                    refreshMountUiFromPrefs?.invoke()
                    refreshProfilesAndSelection()
                    Toast.makeText(this, "Profil uložen: ${p.name}", Toast.LENGTH_SHORT).show()
                    writeSessionLog("Profile saved id=${p.id} name=${p.name}")
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }

        btnProfileDelete?.setOnClickListener {
            val activeId = ProfileManager.getActiveProfileIdOrNull()
            if (activeId.isNullOrBlank()) {
                showInfo("Smazat profil", "Není vybraný žádný profil. Vyber profil ve spinneru.")
                return@setOnClickListener
            }
            val name = ProfileManager.findById(activeId)?.name ?: "?"
            AlertDialog.Builder(this)
                .setTitle("Smazat profil")
                .setMessage("Opravdu smazat profil „$name“?")
                .setPositiveButton("Smazat") { _, _ ->
                    ProfileManager.delete(activeId)
                    refreshProfilesAndSelection()
                    Toast.makeText(this, "Profil smazán", Toast.LENGTH_SHORT).show()
                    writeSessionLog("Profile deleted id=$activeId")
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }

        btnProfileOverwrite?.setOnClickListener {
            val activeId = ProfileManager.getActiveProfileIdOrNull()
            if (activeId.isNullOrBlank()) {
                showInfo("Přepsat profil", "Není vybraný žádný profil. Vyber profil ve spinneru.")
                return@setOnClickListener
            }
            val name = ProfileManager.findById(activeId)?.name ?: "?"
            AlertDialog.Builder(this)
                .setTitle("Přepsat aktivní profil")
                .setMessage(
                    "Přepsat profil „$name“ aktuálními hodnotami ROI + kalibrace?\n\n" +
                        "Použij, když jsi profil upravil a chceš ho uložit zpět (ne vytvářet nový)."
                )
                .setPositiveButton("Přepsat") { _, _ ->
                    val updated = ProfileManager.overwriteProfileFromCurrentPrefs(activeId)
                    if (updated == null) {
                        Toast.makeText(this, "Profil nebyl nalezen", Toast.LENGTH_SHORT).show()
                        writeSessionLog("Profile overwrite failed id=$activeId")
                    } else {
                        ProfileManager.setActiveProfileId(updated.id)
                        ProfileManager.applyActiveProfileToPreferences()
                        refreshMountUiFromPrefs?.invoke()
                        refreshProfilesAndSelection()
                        Toast.makeText(this, "Profil přepsán: ${updated.name}", Toast.LENGTH_SHORT).show()
                        writeSessionLog("Profile overwritten id=${updated.id} name=${updated.name}")
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }

        btnProfileClear?.setOnClickListener {
            ProfileManager.setActiveProfileId(null)
            refreshProfilesAndSelection()
            Toast.makeText(this, "Použit Default (bez profilu)", Toast.LENGTH_SHORT).show()
            writeSessionLog("Profile cleared to default")
        }

        refreshProfilesAndSelection()

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
        val groupMode = findViewById<View>(R.id.groupMode)
        val groupModel = findViewById<View>(R.id.groupModel)
        val groupDebugOptions = findViewById<View>(R.id.groupDebugOptions)
        val txtModeDetails = findViewById<TextView>(R.id.txtModeDetails)

        val etTtsOrange = findViewById<EditText>(R.id.etTtsOrange)
        val etTtsRed = findViewById<EditText>(R.id.etTtsRed)

        val swVibration = findViewById<SwitchMaterial>(R.id.swVibration)
        val swDebugOverlay = findViewById<SwitchMaterial>(R.id.swDebugOverlay)
        val swDebugSettings = findViewById<SwitchMaterial>(R.id.swDebugSettings)
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

        // Alert sound volumes (4-step like Lane Width)
        val sliderOrangeVolume = findViewById<Slider>(R.id.sliderOrangeVolume)
        val sliderRedVolume = findViewById<Slider>(R.id.sliderRedVolume)
        val txtOrangeVolumeValue = findViewById<TextView>(R.id.txtOrangeVolumeValue)
        val txtRedVolumeValue = findViewById<TextView>(R.id.txtRedVolumeValue)

        // Camera calibration
        val etCameraMountHeight: EditText? = findViewById(R.id.etCameraMountHeight)
        val etCameraPitchDownDeg: EditText? = findViewById(R.id.etCameraPitchDownDeg)

        // Smysluplné labely i bez úprav XML layoutu (hint přímo v inputu).
        // (Uživatel pak chápe jednotky a význam i když vedle není TextView.)
        etCameraMountHeight?.hint = "Výška kamery nad zemí (m)"
        etCameraPitchDownDeg?.hint = "Sklon kamery dolů (°)"

// Inicializace hodnot (aby "defaulty" byly vidět hned a slider/edit odpovídal uloženému stavu)
etCameraMountHeight?.setText(formatFloatForInput(AppPreferences.cameraMountHeightM))
etCameraPitchDownDeg?.setText(formatFloatForInput(AppPreferences.cameraPitchDownDeg))

// When profile changes, AppPreferences are updated but UI fields must reflect that.
        // When profile changes, AppPreferences are updated but UI fields must reflect that.
        refreshMountUiFromPrefs = {
            // Camera inputs
            etCameraMountHeight?.setText(formatFloatForInput(AppPreferences.cameraMountHeightM))
            etCameraPitchDownDeg?.setText(formatFloatForInput(AppPreferences.cameraPitchDownDeg))
            // Sliders that belong to mount/profile scope
            bindLaneWidthSlider(sliderLaneWidth, txtLaneWidthValue)
            bindDistanceScaleSlider(sliderDistanceScale, txtDistanceScaleValue)
        }




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

                // U1: Režim je pro uživatele vždy Automat. CITY/SPORT řeší AutoModeSwitcher interně.
        AppPreferences.detectionMode = AppPreferences.MODE_AUTO
        spMode.setSelection(AppPreferences.MODE_AUTO)
        spMode.visibility = View.GONE
        groupMode?.let {
            // necháme pouze vysvětlující text
        }
        txtModeDetails.text = modeSummary(AppPreferences.MODE_AUTO)


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

        fun applyDebugVisibility(debugOn: Boolean) {
            groupModel?.visibility = if (debugOn) View.VISIBLE else View.GONE
            groupDebugOptions?.visibility = if (debugOn) View.VISIBLE else View.GONE
        }



        fun syncAdvancedUiFromPrefs() {
            // Debug-only switches
            swLaneFilter?.isChecked = AppPreferences.laneFilter
            swRoiStrictContainment?.isChecked = AppPreferences.roiStrictContainment
            swQualityGating?.isChecked = AppPreferences.qualityGatingEnabled
            swCutInProtection?.isChecked = AppPreferences.cutInProtectionEnabled

            // Debug-only sliders (position + label)
            bindLaneWidthSlider(sliderLaneWidth, txtLaneWidthValue)
            // Calibration slider should always reflect prefs too
            bindDistanceScaleSlider(sliderDistanceScale, txtDistanceScaleValue)

            // Alert volume sliders (always visible)
            bindAlertVolumeSlider(sliderOrangeVolume, txtOrangeVolumeValue, isRed = false)
            bindAlertVolumeSlider(sliderRedVolume, txtRedVolumeValue, isRed = true)
        }

        // Alert switches
        fun bindSwitch(sw: SwitchMaterial, getter: () -> Boolean, setter: (Boolean) -> Unit) {
            sw.isChecked = getter()
            sw.setOnCheckedChangeListener { _, checked -> setter(checked) }
        }

                bindSwitch(swVibration, { AppPreferences.vibration }, { AppPreferences.vibration = it })

        swDebugOverlay.isChecked = AppPreferences.debugOverlay
        swDebugSettings.isChecked = AppPreferences.debugSettingsEnabled
        applyDebugVisibility(swDebugSettings.isChecked)

        swDebugOverlay.setOnCheckedChangeListener { _, checked ->
            // Pouze overlay v preview (bbox/debug info). Nemá měnit tuning.
            AppPreferences.debugOverlay = checked
        }

        swDebugSettings.setOnCheckedChangeListener { _, enabled ->
            AppPreferences.debugSettingsEnabled = enabled
            if (!enabled) {
                // Determinismus: vypnutí debug nastavení musí vrátit všechny overrides na doporučené hodnoty.
                AppPreferences.resetDebugOverridesToAutoRecommended()
                AppPreferences.resetUserThresholdsToDefault()
                // Sync UI po resetu, ať slider/switch odpovídá skutečným hodnotám.
                syncAdvancedUiFromPrefs()
            }
            applyDebugVisibility(enabled)
        }
bindSwitch(swLaneFilter, { AppPreferences.laneFilter }, { AppPreferences.laneFilter = it })
        bindSwitch(swRoiStrictContainment, { AppPreferences.roiStrictContainment }, { AppPreferences.roiStrictContainment = it })
        bindSwitch(swBrakeCue, { AppPreferences.brakeCueEnabled }, { AppPreferences.brakeCueEnabled = it })

        bindSwitch(swSoundOrange, { AppPreferences.soundOrange }, { AppPreferences.soundOrange = it })
        bindSwitch(swSoundRed, { AppPreferences.soundRed }, { AppPreferences.soundRed = it })
        bindSwitch(swVoiceOrange, { AppPreferences.voiceOrange }, { AppPreferences.voiceOrange = it })
        bindSwitch(swVoiceRed, { AppPreferences.voiceRed }, { AppPreferences.voiceRed = it })

        // TTS texts
        etTtsOrange.setText(AppPreferences.ttsTextOrange)
        etTtsRed.setText(AppPreferences.ttsTextRed)
        val ttsWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                AppPreferences.ttsTextOrange = etTtsOrange.text?.toString() ?: ""
                AppPreferences.ttsTextRed = etTtsRed.text?.toString() ?: ""
            }
        }
        etTtsOrange.addTextChangedListener(ttsWatcher)
        etTtsRed.addTextChangedListener(ttsWatcher)

        // Sliders
        bindLaneWidthSlider(sliderLaneWidth, txtLaneWidthValue)
        bindDistanceScaleSlider(sliderDistanceScale, txtDistanceScaleValue)
        bindAlertVolumeSlider(sliderOrangeVolume, txtOrangeVolumeValue, isRed = false)
        bindAlertVolumeSlider(sliderRedVolume, txtRedVolumeValue, isRed = true)

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

        slider.clearOnChangeListeners()

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

        slider.addOnChangeListener { _, v, fromUser ->
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

        slider.clearOnChangeListeners()

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

    private fun bindAlertVolumeSlider(slider: Slider?, valueView: TextView?, isRed: Boolean) {
        if (slider == null) return

        slider.valueFrom = 0f
        slider.valueTo = 3f
        slider.stepSize = 1f

        slider.clearOnChangeListeners()

        fun labelFor(level: Int): String = when (level.coerceIn(0, 3)) {
            0 -> "Normální"
            1 -> "Silná"
            2 -> "Velmi silná"
            else -> "Max"
        }

        val currentLevel = if (isRed) AppPreferences.soundRedVolumeLevel else AppPreferences.soundOrangeVolumeLevel
        slider.value = currentLevel.coerceIn(0, 3).toFloat()
        valueView?.text = labelFor(currentLevel)

        slider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val level = v.toInt().coerceIn(0, 3)
            if (isRed) {
                AppPreferences.soundRedVolumeLevel = level
            } else {
                AppPreferences.soundOrangeVolumeLevel = level
            }
            valueView?.text = labelFor(level)
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
        // U1: doporučené hodnoty pro běžné použití (bez zásahu do zvuku/hlasu).
        // Kalibrace vzdálenosti je součástí doporučených (vracíme na 1.0).
        AppPreferences.resetDebugOverridesToAutoRecommended()
        AppPreferences.distanceScale = 1.0f

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

    private fun formatFloatForInput(v: Float): String {
        // Stabilní zápis s tečkou jako desetinným oddělovačem (EditText numberDecimal očekává '.')
        return java.lang.String.format(java.util.Locale.US, "%.2f", v)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun modeSummary(mode: Int): String {
        return when (mode) {
            AppPreferences.MODE_AUTO -> {
                "Automat: do 55 km/h → Město, nad 55 km/h → Sport. " +
                    "Hystereze 53/57 km/h, nepřepíná při aktivním alertu."
            }
            AppPreferences.MODE_CITY -> {
                "Město: OK > 4.0 s / 20 m / 3 m/s · " +
                    "Upozornění ≤ 3.0 s / 15 m / 3 m/s · " +
                    "Kritické ≤ 1.2 s / 6 m / 5 m/s"
            }
            AppPreferences.MODE_SPORT -> {
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
        // Route settings events to unified per-run CSV log (no extra mcaw_settings_*.txt files).
        com.mcaw.util.SessionLogFile.init(this)
        val tsMs = System.currentTimeMillis()
        val clean = event.replace("\n", " ").replace("\r", " ").trim()
        val escaped = "\"" + clean.replace("\"", "\"\"") + "\""
        com.mcaw.util.PublicLogWriter.appendLogLine(this, com.mcaw.util.SessionLogFile.fileName, "S,$tsMs,$escaped")
    }
}