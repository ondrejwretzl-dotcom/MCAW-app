package com.mcaw.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
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
 * Calibration Wizard (create/edit):
 * Name -> Zoom -> Lane direction + ROI -> Calibration -> Human summary -> Save (draft/active).
 *
 * Reuses:
 * - CalibrationActivity (zoom-only + full calibration)
 * - PreviewActivity (ROI editor + guide line)
 * - ProfileManager/AppPreferences (storage)
 */
class CalibrationWizardActivity : ComponentActivity() {

    private enum class FlowMode { CREATE, EDIT }

    private enum class Step {
        NAME,
        ZOOM,
        ROI,
        CALIBRATION,
        SUMMARY
    }

    private lateinit var txtTitle: TextView
    private lateinit var txtStep: TextView
    private lateinit var inputNameLayout: TextInputLayout
    private lateinit var inputName: TextInputEditText
    private lateinit var txtBody: TextView
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnSecondary: MaterialButton

    private var flowMode: FlowMode = FlowMode.CREATE
    private var step: Step = Step.NAME

    private var editProfileId: String? = null
    private var originalName: String = ""

    private var zoomDone: Boolean = false
    private var roiDone: Boolean = false
    private var calibDone: Boolean = false

    private val launchZoom = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            zoomDone = true
            goTo(Step.ROI)
        }
    }

    private val launchRoi = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            roiDone = true
            goTo(Step.CALIBRATION)
        }
    }

    private val launchCalib = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            calibDone = true
            goTo(Step.SUMMARY)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration_wizard)

        txtTitle = findViewById(R.id.txtTitle)
        txtStep = findViewById(R.id.txtStep)
        inputNameLayout = findViewById(R.id.inputProfileNameLayout)
        inputName = findViewById(R.id.inputProfileName)
        txtBody = findViewById(R.id.txtBody)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)

        // Ensure prefs initialized (should already be from App).
        // Determine available modes.
        val activeId = ProfileManager.getActiveProfileIdOrNull()
        editProfileId = activeId
        originalName = activeId?.let { ProfileManager.getProfileNameById(it) }.orEmpty()

        showModePickerOrContinue(activeId)

        btnPrimary.setOnClickListener { onPrimary() }
        btnSecondary.setOnClickListener { onSecondary() }
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
                render()
            }
            .setNegativeButton("Upravit aktivní") { _, _ ->
                flowMode = FlowMode.EDIT
                step = Step.NAME
                inputName.setText(originalName)
                render()
            }
            .setCancelable(true)
            .setOnCancelListener {
                // If user cancels picker, just exit.
                finish()
            }
            .show()
    }

    private fun goTo(s: Step) {
        step = s
        render()
    }

    private fun render() {
        when (step) {
            Step.NAME -> {
                txtStep.text = if (flowMode == FlowMode.CREATE) "Krok 1/6 – Název profilu" else "Krok 1/6 – Název profilu (editace)"
                inputNameLayout.visibility = View.VISIBLE
                txtBody.text = "Pojmenuj profil pro tento držák telefonu.\n\nTip: Např. „Zrcátko vlevo“, „Kapotáž“ nebo „Auto – palubka“."
                btnPrimary.text = "Další"
                btnSecondary.text = "Zrušit"
            }
            Step.ZOOM -> {
                txtStep.text = "Krok 2/6 – Zoom"
                inputNameLayout.visibility = View.GONE
                txtBody.text = "Nastav záběr tak, aby bylo auto před tebou dobře vidět.\n\nNeřeš čísla – jde o to, aby kamera viděla dění před tebou."
                btnPrimary.text = if (zoomDone) "Pokračovat" else "Nastavit zoom"
                btnSecondary.text = "Zpět"
            }
            Step.ROI -> {
                txtStep.text = "Krok 3/6 – Směr jízdy + ROI"
                inputNameLayout.visibility = View.GONE
                txtBody.text = "Posuň svislou čáru na osu pruhu (směr jízdy).\nPak uprav trapezoid tak, aby pokryl oblast před tebou.\n\nTím vyřešíme natočení telefonu (yaw) bez úhlů."
                btnPrimary.text = if (roiDone) "Pokračovat" else "Nastavit směr + ROI"
                btnSecondary.text = "Zpět"
            }
            Step.CALIBRATION -> {
                txtStep.text = "Krok 4/6 – Kalibrace"
                inputNameLayout.visibility = View.GONE
                txtBody.text = "Teď provedeme kalibraci vzdálenosti.\n\nDůležité: Telefon musí být během kroků co nejstabilnější."
                btnPrimary.text = if (calibDone) "Pokračovat" else "Spustit kalibraci"
                btnSecondary.text = "Zpět"
            }
            Step.SUMMARY -> {
                txtStep.text = "Krok 5/6 – Souhrn"
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
                    3 -> "Stabilita držáku: Špatná"
                    else -> "Stabilita držáku: Neznámá"
                }

                val laneLabel = "Směr jízdy v obraze: nastavuješ v kroku ROI (svislá čára)"

                val hint = if (h.distanceTypicalErrorHint.isNotBlank()) "\n${h.distanceTypicalErrorHint}" else ""
                txtBody.text = "Profil: $name\n\n$distanceLabel$hint\n$stabilityLabel\n$laneLabel\n\n${h.detailText}\n\nPokračuj uložením."
                btnPrimary.text = "Uložit jako aktivní"
                btnSecondary.text = "Uložit jako draft"
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
                goTo(Step.ZOOM)
            }
            Step.ZOOM -> {
                if (zoomDone) {
                    goTo(Step.ROI)
                    return
                }
                val i = Intent(this, CalibrationActivity::class.java)
                i.putExtra(CalibrationActivity.EXTRA_MODE, CalibrationActivity.MODE_ZOOM_ONLY)
                launchZoom.launch(i)
            }
            Step.ROI -> {
                if (roiDone) {
                    goTo(Step.CALIBRATION)
                    return
                }
                val i = Intent(this, PreviewActivity::class.java)
                i.putExtra(PreviewActivity.EXTRA_WIZARD_MODE, true)
                launchRoi.launch(i)
            }
            Step.CALIBRATION -> {
                if (calibDone) {
                    goTo(Step.SUMMARY)
                    return
                }
                val i = Intent(this, CalibrationActivity::class.java)
                i.putExtra(CalibrationActivity.EXTRA_MODE, CalibrationActivity.MODE_FULL)
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
            Step.ZOOM -> goTo(Step.NAME)
            Step.ROI -> goTo(Step.ZOOM)
            Step.CALIBRATION -> goTo(Step.ROI)
            Step.SUMMARY -> saveProfile(setActive = false)
        }
    }

    private fun saveProfile(setActive: Boolean) {
        val name = inputName.text?.toString()?.trim().orEmpty().ifBlank { "Profil" }

        val health = CalibrationHealth.evaluate()
        val validForActive = health.state != CalibrationHealth.State.INVALID

        if (setActive && !validForActive) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Nelze uložit jako aktivní")
                .setMessage("Tento profil teď není spolehlivý.\n\n${health.bannerText}\n\nDokonči kalibraci/ROI a zkus to znovu.\n\nMůžeš ho uložit jako draft.")
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
                    // If name changed, ask user if overwrite or save as new.
                    if (originalName.isNotBlank() && originalName != name) {
                        // Default: overwrite + rename (edit profile)
                        val updated = ProfileManager.overwriteProfileFromCurrentPrefs(activeId, name)
                        updated ?: ProfileManager.saveProfileFromCurrentPrefs(name)
                    } else {
                        ProfileManager.overwriteProfileFromCurrentPrefs(activeId, name) ?: ProfileManager.saveProfileFromCurrentPrefs(name)
                    }
                }
            }
        }

        if (setActive) {
            ProfileManager.setActiveProfileId(saved.id)
        }

        finish()
    }

    override fun onResume() {
        super.onResume()
        render()
    }
}
