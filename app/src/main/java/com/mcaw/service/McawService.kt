package com.mcaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import com.mcaw.app.R
import java.util.concurrent.Executors

class McawService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        startCamera()
    }

    private fun startForegroundNotification() {
        val channelId = "mcaw_fg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(channelId, "MCAW", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("MCAW běží")
            .setOngoing(true)
            .build()

        startForeground(1, notif)
    }

    private fun startCamera() {
        val lifecycleOwner = ProcessLifecycleOwner.get()   // globální LifecycleOwner
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                // tvoje analýza
                image.close()
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,                          // ísto `this`
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }
}
