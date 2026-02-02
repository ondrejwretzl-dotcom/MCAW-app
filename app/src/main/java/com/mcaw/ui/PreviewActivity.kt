package com.mcaw.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import com.mcaw.app.R
import com.mcaw.model.Box

class PreviewActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return

            val left = i.getFloatExtra("left", 0f)
            val top = i.getFloatExtra("top", 0f)
            val right = i.getFloatExtra("right", 0f)
            val bottom = i.getFloatExtra("bottom", 0f)

            overlay.box = Box(left, top, right, bottom)
            overlay.distance = i.getFloatExtra("dist", -1f)
            overlay.speed = i.getFloatExtra("speed", -1f)
            overlay.ttc = i.getFloatExtra("ttc", -1f)

            overlay.invalidate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)

        registerReceiver(receiver, IntentFilter("MCAW_DEBUG_UPDATE"))
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
