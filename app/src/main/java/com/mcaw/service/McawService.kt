package com.mcaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mcaw.app.R
import java.util.concurrent.Executors

class McawService : LifecycleService() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        startCamera()
    }

    private fun startForegroundNotification() {
        val channelId = "mcaw_fg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "MCAW",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MCAW běží")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()

        startForeground(1, notif)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(
                Executors.newSingleThreadExecutor()
            ) { img ->
                img.close()
            }

            provider.unbindAll()

            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )

        }, ContextCompat.getMainExecutor(this))
    }
}
