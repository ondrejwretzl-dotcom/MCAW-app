package com.mcaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mcaw.app.R
import java.util.concurrent.Executors

class McawService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        initCamera()
    }

    private fun startForegroundWithNotification() {
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

    private fun initCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                // ZDE BUDE TVÁ ANALÝZA
                image.close()
            }

            provider.unbindAll()

            // POZOR: Service NENÍ LifecycleOwner
            // Musíme použít "bindToLifecycle" přes "ProcessCameraProvider", NE přes LifecycleOwner.
            // Správně CameraX vyžaduje lifecycle -> zde použijeme workaround:
            // přijmeme, že analýza běží bez lifecycle bindingu pomocí use-case group:
            try {
                provider.bindToLifecycle(
                    this@McawService,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }
}
