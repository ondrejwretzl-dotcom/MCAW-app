package com.mcaw.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mcaw.config.AppPreferences
import com.mcaw.config.AppPreferences.RoiN
import com.mcaw.app.R

class PreviewActivity : AppCompatActivity() {

    private lateinit var overlay: OverlayView
    private var roiEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        AppPreferences.init(this)

        overlay = findViewById(R.id.overlayView)

        overlay.setRoi(AppPreferences.getRoiNormalized())

        findViewById<View>(R.id.btnRoiEdit).setOnClickListener {
            roiEditMode = !roiEditMode
            overlay.setRoiEditMode(roiEditMode)
        }

        findViewById<View>(R.id.btnRoiEdit).setOnLongClickListener {
            AppPreferences.resetRoiToDefault()
            overlay.setRoi(AppPreferences.getRoiNormalized())
            true
        }

        overlay.onRoiChanged = { roi ->
            AppPreferences.setRoiNormalized(roi)
        }
    }
}
