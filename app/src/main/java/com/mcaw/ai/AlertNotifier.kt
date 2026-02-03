package com.mcaw.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.mcaw.app.R
import com.mcaw.config.AppPreferences

object AlertNotifier {

    private const val CHANNEL_SILENT = "mcaw_alert_silent"
    private const val CHANNEL_SOUND = "mcaw_alert_sound"
    private const val CHANNEL_VIBRATE = "mcaw_alert_vibrate"
    private const val CHANNEL_SOUND_VIBRATE = "mcaw_alert_sound_vibrate"
    private const val NOTIFICATION_ID = 2001

    fun show(context: Context, title: String, text: String, level: Int) {
        val channelId = channelIdForPrefs()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager, channelId)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(
                if (level >= 2) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun channelIdForPrefs(): String {
        return when {
            AppPreferences.sound && AppPreferences.vibration -> CHANNEL_SOUND_VIBRATE
            AppPreferences.sound -> CHANNEL_SOUND
            AppPreferences.vibration -> CHANNEL_VIBRATE
            else -> CHANNEL_SILENT
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager, channelId: String) {
        if (manager.getNotificationChannel(channelId) != null) return
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, "MCAW Alerts", importance)

        val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.alert_beep}")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        when (channelId) {
            CHANNEL_SOUND_VIBRATE -> {
                channel.setSound(soundUri, attrs)
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(0, 250, 120, 250)
            }
            CHANNEL_SOUND -> {
                channel.setSound(soundUri, attrs)
                channel.enableVibration(false)
            }
            CHANNEL_VIBRATE -> {
                channel.setSound(null, null)
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(0, 250, 120, 250)
            }
            else -> {
                channel.setSound(null, null)
                channel.enableVibration(false)
            }
        }

        manager.createNotificationChannel(channel)
    }
}
