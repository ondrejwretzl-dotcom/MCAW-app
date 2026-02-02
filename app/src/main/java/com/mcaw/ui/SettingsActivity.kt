package com.mcaw.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

private const val PREFS = "mcaw_prefs"
private const val KEY_MODEL = "model"

/**
 * Minimal SettingsActivity, která nastaví/ète klíè modelu.
 * Pokud už SettingsActivity máš, pøenes si tyto konstanty a lazy "spModel".
 */
class SettingsActivity : AppCompatActivity() {

    // Pøedvolba modelu uložená v SharedPreferences (napø. "yolo" / "efficientdet")
    val spModel: String by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODEL, "yolo") ?: "yolo"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_settings) // pokud máš layout

        // Pøíklad uložení zmìny:
        // getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODEL, "yolo").apply()
    }
}
