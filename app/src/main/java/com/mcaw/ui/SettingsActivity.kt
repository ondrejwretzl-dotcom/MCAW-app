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
}


