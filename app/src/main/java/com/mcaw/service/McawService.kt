package com.mcaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mcaw.ai.DetectionAnalyzer
import com.mcaw.ai.YoloOnnxDetector
import com.mcaw.ai.EfficientDetTFLiteDetector
import com.mcaw.config.AppPreferences

/**
 * McawService – čistý výpočetní engine
 * ------------------------------------
 * - přijímá zmenšené bitmapy (320×320) z PreviewActivity
 * - provádí detekci přes YOLO / EfficientDet
 * - počítá vzdálenost / rychlost / TTC
 * - výsledek odesílá zpět přes Broadcast Intent
 *
 * Kamera NEběží v této službě.
 */
class McawService : Service() {

    private lateinit var yolo: YoloOnnxDetector
    private lateinit var eff: EfficientDetTFLiteDetector
    private lateinit var analyzer: DetectionAnalyzer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        AppPreferences.init(this)
        startForegroundNotification()

        // inicializace modelů
        yolo = YoloOnnxDetector(
            context = this,
            modelName = "yolov8n.onnx"
        )
        eff = EfficientDetTFLiteDetector(
            ctx = this,
            modelName = "efficientdet_lite0.tflite"
        )

        analyzer = DetectionAnalyzer(this, yolo, eff)
    }

    // ---------------------------------------------------------
    // FOREGROUND notification
    // ---------------------------------------------------------
    private fun startForegroundNotification() {
        val channelId = "mcaw_fg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                "MCAW Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MCAW běží")
            .setContentText("Detekce aktivní…")
            .setOngoing(true)
            .build()

        startForeground(1, notif)
    }

    // ---------------------------------------------------------
    // PŘÍJEM RÁMEČKŮ OD PREVIEWACTIVITY
    // ---------------------------------------------------------
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == "MCawFrame") {
            val bmp = intent.getParcelableExtra<Bitmap>("frame")
            if (bmp != null) processFrame(bmp)
        }

        return START_STICKY
    }

    // ---------------------------------------------------------
    // DETEKCE + ODESLÁNÍ VÝSLEDKŮ
    // ---------------------------------------------------------
    private fun processFrame(bmp: Bitmap) {
        // detekce
        val result = analyzer.analyzeBitmap(bmp)

        if (result == null) return

        // --- výsledky pro PreviewActivity ---
        val intent = Intent("MCAW_DEBUG_UPDATE")

        intent.putExtra("left", result.box.x1)
        intent.putExtra("top", result.box.y1)
        intent.putExtra("right", result.box.x2)
        intent.putExtra("bottom", result.box.y2)

        intent.putExtra("dist", result.distance)
        intent.putExtra("speed", result.speed)
        intent.putExtra("ttc", result.ttc)

        sendBroadcast(intent)
    }
}
