package com.mcaw.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mcaw.app.R

private const val PREFS = "mcaw_prefs"
private const val KEY_MODEL = "model"

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
    }

    fun getSelectedModel(): String {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_MODEL, "yolo") ?: "yolo"
    }

    fun setSelectedModel(model: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL, model)
            .apply()
    }
}

