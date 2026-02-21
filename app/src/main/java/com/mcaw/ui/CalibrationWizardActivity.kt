package com.mcaw.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import com.mcaw.config.CalibrationHealth
import com.mcaw.config.ProfileManager

/**
 * Calibration Wizard (create/edit) – offline, UI-only flow.
 *
 * Flow:
 * 1) Name
 * 2) Camera setup (zoom + guide + ROI)
 * 3) Distance calibration
 * 4) Summary + save
 */
class CalibrationWizardActivity : ComponentActivity() {

    private enum class FlowMode { CREATE, EDIT }

    private enum class Step {
        NAME,
        CAMERA_SETUP,
        CALIBRATION,
        SUMMARY
    }

    private lateinit var txtTitle: TextView
    private lateinit var txtStep: TextView
    private lateinit var inputNameLayout: TextInputLayout
    private lateinit var inputName: TextInputEditText
    private lateinit var txtBody: TextView
    private lateinit var txtStepHint: TextView
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnSecondary: MaterialButton

    private var flowMode: FlowMode = FlowMode.CREATE
    private var step: Step = Step.NAME

    private var editProfileId: String? = null
    private var originalName: String = ""

    private var cameraSetupDone: Boolean = false
    private var calibDone: Boolean = false

    private val launchCameraSetup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            cameraSetupDone = true
            Toast.makeText(this, "Kamera setup uložen", Toast.LENGTH_SHORT).show()
            goTo(Step.CALIBRATION)
        } else {
            render()
        }
    }

    private val launchCalib = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            calibDone = true
            Toast.makeText(this, "Kalibrace vzdálenosti dokončena", Toast.LENGTH_SHORT).show()
            goTo(Step.SUMMARY)
        } else {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wizard is UI-only flow, but still needs profile/prefs initialized.
        // (SharedPreferences here is OK; no IO in frame pipeline.)
        AppPreferences.ensureInit(this)
        ProfileManager.ensureInit(this)

        setContentView(R.layout.activity_calibration_wizard)

        txtTitle = findViewById(R.id.txtTitle)
        txtStep = findViewById(R.id.txtStep)
        inputNameLayout = findViewById(R.id.inputProfileNameLayout)
        inputName = findViewById(R.id.inputProfileName)
        txtBody = findViewById(R.id.txtBody)
        txtStepHint = findViewById(R.id.txtStepHint)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)

        val activeId = ProfileManager.getActiveProfileIdOrNull()
        editProfileId = activeId
        originalName = activeId?.let { ProfileManager.getProfileNameById(it) }.orEmpty()

        showModePickerOrContinue(activeId)

        btnPrimary.setOnClickListener { onPrimary() }
        btnSecondary.setOnClickListener { onSecondary() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun showModePickerOrContinue(activeId: String?) {
        if (activeId.isNullOrBlank()) {
            flowMode = FlowMode.CREATE
            step = Step.NAME
            render()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Kalibrace")
            .setMessage("Co chceš udělat?")
            .setPositiveButton("Nový profil") { _, _ ->
                flowMode = FlowMode.CREATE
                editProfileId = null
                originalName = ""
                step = Step.NAME
                inputName.setText("")
                cameraSetupDone = false
                calibDone = false
                render()
            }
            .setNegativeButton("Upravit aktivní") { _, _ ->
                flowMode = FlowMode.EDIT
                step = Step.NAME
                inputName.setText(originalName)

                // Working copy: load active profile into prefs so user edits draft based on it.
                ProfileManager.applyActiveProfileToPreferences()

                cameraSetupDone = false
                calibDone = false
                render()
            }
            .setCancelable(true)
            .setOnCancelListener { finish() }
            .show()
    }

    private fun goTo(s: Step) {
        step = s
        render()
    }

    private fun render() {
        txtStepHint.visibility = View.GONE
        txtStepHint.text = ""
        when (step) {
            Step.NAME -> {
                txtStep.text = if (flowMode == FlowMode.CREATE) {
                    "Krok 1/4 – Název profilu"
                } else {
                    "Krok 1/4 – Název profilu (editace)"
                }
                inputNameLayout.visibility = View.VISIBLE
                txtBody.text = """
                    Pojmenuj profil pro tento držák telefonu.

                    Tip: Např. „Zrcátko vlevo“, „Kapotáž“ nebo „Auto – palubka“.
                """.trimIndent()
                btnPrimary.text = "Další"
                btnSecondary.text = "Zrušit"
            }

            Step.CAMERA_SETUP -> {
                txtStep.text = "Krok 2/4 – Kamera setup"
                inputNameLayout.visibility = View.GONE
                txtBody.text = """
                    Nastav:
                    • Zoom
                    • Střed jízdy (svislá čára)
                    • ROI (trapezoid)

                    Oblast pod spodní hranou ROI se nebude vůbec detekovat (kapota/palubka).
                """.trimIndent()
                // ROI is often adjusted more frequently than distance/pitch calibration.
                // Keep this step always editable.
                btnPrimary.text = "Otevřít setup"
                btnSecondary.text = if (cameraSetupDone) "Pokračovat" else "Zpět"
                if (!cameraSetupDone) {
                    txtStepHint.visibility = View.VISIBLE
                    txtStepHint.text = "Nejdřív otevři setup a potvrď hodnoty tlačítkem Hotovo."
                }
            }

            Step.CALIBRATION -> {
                txtStep.text = "Krok 3/4 – Kalibrace vzdálenosti"
                inputNameLayout.visibility = View.GONE
                txtBody.text = """
                    Teď provedeme kalibraci vzdálenosti.

                    Důležité: telefon musí být během kroků co nejstabilnější.
                """.trimIndent()
                // Calibration can be re-run; allow it even after completion.
                btnPrimary.text = "Spustit kalibraci"
                btnSecondary.text = if (calibDone) "Pokračovat" else "Zpět"
                if (!calibDone) {
                    txtStepHint.visibility = View.VISIBLE
                    txtStepHint.text = "Po kroku Kontrola musíš potvrdit ULOŽIT, jinak se krok neoznačí jako hotový."
                }
            }

            Step.SUMMARY -> {
                txtStep.text = "Krok 4/4 – Souhrn"
                inputNameLayout.visibility = View.GONE

                val h = CalibrationHealth.evaluate()
                val name = inputName.text?.toString()?.trim().orEmpty().ifBlank { "Profil" }

                val distanceLabel = when (h.state) {
                    CalibrationHealth.State.OK -> "Přesnost vzdálenosti: Dobrá"
                    CalibrationHealth.State.WARNING -> "Přesnost vzdálenosti: Střední"
                    CalibrationHealth.State.INVALID -> "Přesnost vzdálenosti: Špatná"
                }

                val stabilityLabel = when (AppPreferences.calibrationImuQuality) {
                    1 -> "Stabilita držáku: Dobrá"
                    2 -> "Stabilita držáku: Slabší (vibrace)"
                    3 -> "Stabilita držáku: Špatná (velké vibrace)"
                    else -> "Stabilita držáku: Neznámá"
                }

                txtBody.text = buildString {
                    append("Profil: ").append(name).append("\n\n")
                    append(distanceLabel).append("\n")
                    append(stabilityLabel).append("\n\n")
                    append(h.bannerText)
                }

                btnPrimary.text = "Uložit a aktivovat"
                btnSecondary.text = "Upravit kroky"
            }
        }
    }

    private fun onPrimary() {
        when (step) {
            Step.NAME -> {
                val name = inputName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    inputNameLayout.error = "Zadej název profilu"
                    return
                }
                inputNameLayout.error = null
                goTo(Step.CAMERA_SETUP)
            }

            Step.CAMERA_SETUP -> {
                launchCameraSetup.launch(Intent(this, CalibrationCameraSetupActivity::class.java))
            }

            Step.CALIBRATION -> {
                val i = Intent(this, CalibrationActivity::class.java).apply {
                    putExtra(CalibrationActivity.EXTRA_MODE, CalibrationActivity.MODE_FULL)
                }
                launchCalib.launch(i)
            }

            Step.SUMMARY -> {
                saveProfile(setActive = true)
            }
        }
    }

    private fun onSecondary() {
        when (step) {
            Step.NAME -> finish()
            Step.CAMERA_SETUP -> {
                if (cameraSetupDone) goTo(Step.CALIBRATION) else goTo(Step.NAME)
            }
            Step.CALIBRATION -> {
                if (calibDone) goTo(Step.SUMMARY) else goTo(Step.CAMERA_SETUP)
            }
            Step.SUMMARY -> {
                showEditStepsDialog()
            }
        }
    }

    private fun showEditStepsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Upravit kroky")
            .setItems(
                arrayOf(
                    "Kamera setup (zoom / střed / ROI)",
                    "Kalibrace vzdálenosti + pitch",
                    "Uložit jako draft"
                )
            ) { dlg, which ->
                when (which) {
                    0 -> {
                        dlg.dismiss()
                        goTo(Step.CAMERA_SETUP)
                        launchCameraSetup.launch(Intent(this, CalibrationCameraSetupActivity::class.java))
                    }
                    1 -> {
                        dlg.dismiss()
                        goTo(Step.CALIBRATION)
                        val i = Intent(this, CalibrationActivity::class.java).apply {
                            putExtra(CalibrationActivity.EXTRA_MODE, CalibrationActivity.MODE_FULL)
                        }
                        launchCalib.launch(i)
                    }
                    else -> {
                        dlg.dismiss()
                        saveProfile(setActive = false)
                    }
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun saveProfile(setActive: Boolean) {
        val name = inputName.text?.toString()?.trim().orEmpty().ifBlank { "Profil" }

        val health = CalibrationHealth.evaluate()
        val validForActive = health.state != CalibrationHealth.State.INVALID

        if (setActive && !validForActive) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Nelze uložit jako aktivní")
                .setMessage(
                    "Tento profil teď není spolehlivý.\n\n" +
                        health.bannerText +
                        "\n\nDokonči kalibraci/ROI a zkus to znovu.\n\nMůžeš ho uložit jako draft."
                )
                .setPositiveButton("Uložit jako draft") { _, _ -> saveProfile(setActive = false) }
                .setNegativeButton("Zpět") { _, _ -> }
                .show()
            return
        }

        val mode = flowMode
        val activeId = editProfileId

        val saved = when (mode) {
            FlowMode.CREATE -> {
                ProfileManager.saveProfileFromCurrentPrefs(name)
            }

            FlowMode.EDIT -> {
                if (activeId.isNullOrBlank()) {
                    ProfileManager.saveProfileFromCurrentPrefs(name)
                } else {
                    if (originalName.isNotBlank() && originalName != name) {
                        ProfileManager.overwriteProfileFromCurrentPrefs(activeId, name)
                            ?: ProfileManager.saveProfileFromCurrentPrefs(name)
                    } else {
                        ProfileManager.overwriteProfileFromCurrentPrefs(activeId, name)
                            ?: ProfileManager.saveProfileFromCurrentPrefs(name)
                    }
                }
            }
        }

        if (setActive) {
            ProfileManager.setActiveProfileId(saved.id)
        }

        finish()
    }
}
