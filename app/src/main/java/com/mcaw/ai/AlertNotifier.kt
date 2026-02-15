package com.mcaw.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.mcaw.app.R
import com.mcaw.config.AppPreferences
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import com.mcaw.app.MCAWApp
import com.mcaw.risk.RiskEngine

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


    // ---------------- MCAW 2.0 In-app alerting (sound/vibration/TTS) ----------------

    @Volatile private var lastInAppLevel: Int = 0
    @Volatile private var lastInAppPlayMs: Long = 0L

    // MediaPlayer is kept between plays for performance.
    private var alertPlayer: MediaPlayer? = null

    // Single TTS instance (avoid per-alert allocations). Optional.
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady: Boolean = false
    @Volatile private var ownsTts: Boolean = false

    private var audioFocusGranted: Boolean = false
    private var audioFocusRequest: Any? = null
    private var lastFocusGain: Int = -1
    private var lastFocusUsage: Int = -1

    fun handleInApp(context: Context, level: Int, _risk: RiskEngine.Result? = null) {
        if (level <= 0) return

        // Avoid spamming: only on level change or after short cooldown (e.g., persistent RED).
        val now = android.os.SystemClock.elapsedRealtime()
        val cooldownMs = if (level >= 2) 900L else 1400L
        val changed = level != lastInAppLevel
        if (!changed && (now - lastInAppPlayMs) < cooldownMs) return

        lastInAppLevel = level
        lastInAppPlayMs = now

        when (level) {
            1 -> {
                // ORANGE
                if (AppPreferences.sound && AppPreferences.soundOrange) {
                    playAlertSound(context, com.mcaw.app.R.raw.alert_beep, critical = false)
                }
                if (AppPreferences.voice && AppPreferences.voiceOrange) {
                    val text = AppPreferences.ttsTextOrange.trim()
                    if (text.isNotEmpty()) speak(context, text, "tts_orange")
                }
            }
            2 -> {
                // RED
                if (AppPreferences.sound && AppPreferences.soundRed) {
                    playAlertSound(context, com.mcaw.app.R.raw.red_alert, critical = true)
                }
                if (AppPreferences.vibration) {
                    val vib = getVibratorCompat(context)
                    if (vib?.hasVibrator() == true) {
                        vib.vibrate(VibrationEffect.createOneShot(220, 150))
                    }
                }
                if (AppPreferences.voice && AppPreferences.voiceRed) {
                    val text = AppPreferences.ttsTextRed.trim()
                    if (text.isNotEmpty()) speak(context, text, "tts_red")
                }
            }
        }

    }

    fun stopInApp(context: Context) {
        lastInAppLevel = 0
        runCatching {
            alertPlayer?.stop()
            alertPlayer?.reset()
        }
        // Do not shutdown TTS here (user might get another alert soon). Released in shutdown().
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        abandonAlertAudioFocus(am)
    }

    /** Release long-lived resources (call from Activity/Service onDestroy). */
    fun shutdown(context: Context) {
        stopInApp(context)
        runCatching { alertPlayer?.release() }
        alertPlayer = null
        val inst = tts
        if (inst != null && ownsTts) {
            runCatching { inst.shutdown() }
        }
        tts = null
        ttsReady = false
        ownsTts = false
    }

    private fun speak(context: Context, text: String, utteranceId: String) {
        try {
            val inst = ensureTts(context)
            if (inst != null && ttsReady) {
                inst.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun ensureTts(context: Context): TextToSpeech? {
        // Prefer global app-managed TTS (single source of truth)
        val appTts = runCatching { (context.applicationContext as? MCAWApp)?.getTts() }.getOrNull()
        if (appTts != null) {
            tts = appTts
            ttsReady = true
            ownsTts = false
            return appTts
        }

        if (tts != null) return tts
        synchronized(this) {
            if (tts != null) return tts
            if (!AppPreferences.voice) return null
            val appCtx = context.applicationContext
            var created: TextToSpeech? = null
            created = TextToSpeech(appCtx) { status ->
                ttsReady = (status == TextToSpeech.SUCCESS)
                if (ttsReady) {
                    runCatching { created?.language = java.util.Locale.getDefault() }
                }
            }
            tts = created
            ownsTts = true
            return created
        }
    }

    private fun playAlertSound(context: Context, resId: Int, critical: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Strategy from previous discussion: NAVIGATION_GUIDANCE + gain transient for better BT routing.
        val usage = android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        val gain = if (critical) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        requestAlertAudioFocus(am, gain = gain, usage = usage)

        try {
            val mp = alertPlayer ?: MediaPlayer().also { alertPlayer = it }
            mp.reset()
            val afd = context.resources.openRawResourceFd(resId)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()

            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )

            // Volume: user controls in Settings (0..1)
            val vol = if (critical) AppPreferences.soundRedVolumeScalar else AppPreferences.soundOrangeVolumeScalar
            mp.setVolume(vol, vol)

            mp.setOnCompletionListener {
                // release focus soon after playback
                abandonAlertAudioFocus(am)
            }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
            runCatching { alertPlayer?.release() }
            alertPlayer = null
            abandonAlertAudioFocus(am)
        }
    }

    private fun requestAlertAudioFocus(am: AudioManager, gain: Int, usage: Int) {
        if (audioFocusGranted && gain == lastFocusGain && usage == lastFocusUsage) return

        // If focus is already held with different params, release first.
        if (audioFocusGranted) abandonAlertAudioFocus(am)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val listener = AudioManager.OnAudioFocusChangeListener { /* ignore */ }
            val req = android.media.AudioFocusRequest.Builder(gain)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = req
            audioFocusGranted = (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            if (audioFocusGranted) {
                lastFocusGain = gain
                lastFocusUsage = usage
            }
        } else {
            @Suppress("DEPRECATION")
            audioFocusGranted = (am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                gain
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            if (audioFocusGranted) {
                lastFocusGain = gain
                lastFocusUsage = usage
            }
        }
    }
    private fun abandonAlertAudioFocus(am: AudioManager) {
        if (!audioFocusGranted) return
        audioFocusGranted = false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = audioFocusRequest as? android.media.AudioFocusRequest
            if (req != null) {
                am.abandonAudioFocusRequest(req)
            }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
