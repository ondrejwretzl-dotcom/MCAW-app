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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.media.AudioDeviceInfo
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mcaw.app.MCAWApp
import com.mcaw.risk.RiskEngine
import com.mcaw.util.SessionActivityLogger

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

    // Low-latency pools (preloaded) for different routing strategies.
    // SoundPool has fixed AudioAttributes, so we keep separate pools.
    private var poolMedia: SoundPool? = null
    private var poolPrime: SoundPool? = null
    private var poolAlarm: SoundPool? = null

    private var sndBeepMedia: Int = 0
    private var sndRedMedia: Int = 0
    private var sndBeepPrime: Int = 0
    private var sndRedPrime: Int = 0
    private var sndBeepAlarm: Int = 0
    private var sndRedAlarm: Int = 0

    // Single TTS instance (avoid per-alert allocations). Optional.
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady: Boolean = false
    @Volatile private var ownsTts: Boolean = false

    private var audioFocusGranted: Boolean = false
    private var audioFocusRequest: Any? = null
    private var lastFocusGain: Int = -1
    private var lastFocusUsage: Int = -1

    // Temporary system-volume boost (restored after playback/TTS).
    private const val STREAM_NONE = -1
    private const val STREAM_MUSIC = AudioManager.STREAM_MUSIC
    private const val STREAM_RING = AudioManager.STREAM_RING
    private const val STREAM_ALARM = AudioManager.STREAM_ALARM
    @Volatile private var activeAudioHolds: Int = 0
    private var savedMusicVol: Int = -1
    private var savedRingVol: Int = -1
    private var savedAlarmVol: Int = -1

    // P1/M1: Adaptive routing policy.
    // - If BT A2DP is active and phone is playing music -> use MEDIA (matches car "BT audio")
    // - If BT A2DP is present but phone music is NOT active -> use RINGTONE-like route to "wake" headunit
    // - If no BT -> use ALARM on phone speaker (audible + predictable)
    private const val USAGE_MEDIA = android.media.AudioAttributes.USAGE_MEDIA
    private const val USAGE_PRIME = android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    private const val USAGE_ALARM = android.media.AudioAttributes.USAGE_ALARM
    private const val GAIN_MEDIA = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    private const val GAIN_PRIME = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
    private const val GAIN_ALARM = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE

    private const val ROUTE_BT_MEDIA = 1
    private const val ROUTE_BT_PRIME = 2
    private const val ROUTE_PHONE_ALARM = 3

    // Single listener instance to avoid per-alert allocations.
    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onError(utteranceId: String?) {
            val ctx = MCAWApp.instance
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            abandonAlertAudioFocus(am)
            endAudioHold(am)
        }
        override fun onDone(utteranceId: String?) {
            val ctx = MCAWApp.instance
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            abandonAlertAudioFocus(am)
            endAudioHold(am)
        }
    }

    // Handler for delayed focus/volume release after SoundPool playback.
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * Call once early (Application/Service) to reduce latency:
     * - Preload SoundPool variants (MEDIA/PRIME/ALARM)
     * - Warm-up TTS with a silent utterance
     */
    fun initAudio(context: Context) {
        val appCtx = context.applicationContext
        if (poolMedia != null) return
        synchronized(this) {
            if (poolMedia != null) return

            poolMedia = buildPool(appCtx, USAGE_MEDIA)
            poolPrime = buildPool(appCtx, USAGE_PRIME)
            poolAlarm = buildPool(appCtx, USAGE_ALARM)

            // Preload sounds (best-effort). IDs stay 0 if load fails.
            poolMedia?.let {
                sndBeepMedia = runCatching { it.load(appCtx, R.raw.alert_beep, 1) }.getOrDefault(0)
                sndRedMedia = runCatching { it.load(appCtx, R.raw.red_alert, 1) }.getOrDefault(0)
            }
            poolPrime?.let {
                sndBeepPrime = runCatching { it.load(appCtx, R.raw.alert_beep, 1) }.getOrDefault(0)
                sndRedPrime = runCatching { it.load(appCtx, R.raw.red_alert, 1) }.getOrDefault(0)
            }
            poolAlarm?.let {
                sndBeepAlarm = runCatching { it.load(appCtx, R.raw.alert_beep, 1) }.getOrDefault(0)
                sndRedAlarm = runCatching { it.load(appCtx, R.raw.red_alert, 1) }.getOrDefault(0)
            }
        }

        // Warm-up TTS to reduce first-utterance delay.
        if (AppPreferences.voice) {
            val inst = runCatching { ensureTts(appCtx) }.getOrNull()
            if (inst != null) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        inst.playSilentUtterance(1L, TextToSpeech.QUEUE_ADD, "tts_warmup")
                    }
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
        endAudioHold(am)
    }

    private fun getVibratorCompat(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Release long-lived resources (call from Activity/Service onDestroy). */
    fun shutdown(context: Context) {
        stopInApp(context)
        runCatching { alertPlayer?.release() }
        alertPlayer = null

        runCatching { poolMedia?.release() }
        runCatching { poolPrime?.release() }
        runCatching { poolAlarm?.release() }
        poolMedia = null
        poolPrime = null
        poolAlarm = null
        sndBeepMedia = 0
        sndRedMedia = 0
        sndBeepPrime = 0
        sndRedPrime = 0
        sndBeepAlarm = 0
        sndRedAlarm = 0
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
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val policy = chooseAudioPolicy(am)
                val focusOk = requestAlertAudioFocus(am, gain = policy.gain, usage = policy.usage)
                beginAudioHold(am, policy.stream, scalar = 1f, reason = "tts")
                logAudioEvent(
                    kind = "tts_req",
                    level = if (utteranceId.contains("red")) 2 else 1,
                    policy = policy,
                    focusOk = focusOk,
                    fallback = false,
                    fail = null
                )
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
            configureTtsAudio(appTts)
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
                    runCatching { created?.setOnUtteranceProgressListener(ttsListener) }
                    runCatching { created?.let { configureTtsAudio(it) } }
                }
            }
            tts = created
            ownsTts = true
            return created
        }
    }

    private fun configureTtsAudio(inst: TextToSpeech) {
        // TTS routing is chosen dynamically before each utterance; here we only keep it compatible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(USAGE_PRIME)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            runCatching { inst.setAudioAttributes(attrs) }
        }
        runCatching { inst.setOnUtteranceProgressListener(ttsListener) }
    }

    private fun playAlertSound(context: Context, resId: Int, critical: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val level = if (critical) 2 else 1
        val scalar = if (critical) AppPreferences.soundRedVolumeScalar else AppPreferences.soundOrangeVolumeScalar

        // Primary attempt.
        val p1 = chooseAudioPolicy(am)
        var focusOk = requestAlertAudioFocus(am, gain = p1.gain, usage = p1.usage)
        beginAudioHold(am, p1.stream, scalar = scalar, reason = if (critical) "red" else "orange")

        var played = playViaPool(resId, p1, scalar)

        // Fallback "proražení" for BOTH ORANGE and RED:
        // If BT is present and primary is MEDIA but focus denied or playback failed, retry with PRIME.
        var usedFallback = false
        if (p1.route == ROUTE_BT_MEDIA && (!focusOk || !played)) {
            val p2 = AudioPolicy(route = ROUTE_BT_PRIME, usage = USAGE_PRIME, gain = GAIN_PRIME, stream = STREAM_RING)
            // Release + reacquire focus with stronger params.
            abandonAlertAudioFocus(am)
            focusOk = requestAlertAudioFocus(am, gain = p2.gain, usage = p2.usage)
            beginAudioHold(am, p2.stream, scalar = scalar, reason = "fallback")
            played = playViaPool(resId, p2, scalar)
            usedFallback = true
            logAudioEvent(
                kind = "snd_fallback",
                level = level,
                policy = p2,
                focusOk = focusOk,
                fallback = true,
                fail = if (played) null else "play_failed"
            )
        }

        // SoundPool has no completion callback. Release focus/volume after a short, safe delay.
        scheduleSoundRelease(am, resId, usedFallback = usedFallback)

        logAudioEvent(
            kind = "snd_req",
            level = level,
            policy = p1,
            focusOk = focusOk,
            fallback = usedFallback,
            fail = if (played) null else "play_failed"
        )
    }

    private fun scheduleSoundRelease(am: AudioManager, resId: Int, usedFallback: Boolean) {
        val delayMs = when (resId) {
            R.raw.red_alert -> 1600L
            else -> 700L
        }
        // Best-effort: release focus and restore stream volume after we expect the sound to end.
        mainHandler.postDelayed({
            abandonAlertAudioFocus(am)
            endAudioHold(am)
            if (usedFallback) {
                SessionActivityLogger.log("audio_snd_release fallback=1")
            }
        }, delayMs)
    }

    private data class AudioPolicy(
        val route: Int,
        val usage: Int,
        val gain: Int,
        val stream: Int
    )

    private fun chooseAudioPolicy(am: AudioManager): AudioPolicy {
        val hasBtA2dp = hasBluetoothA2dpOutput(am)
        // If the phone is actively playing media, we should use MEDIA so headunit keeps BT audio route.
        val musicActive = runCatching { am.isMusicActive }.getOrDefault(false)

        return when {
            hasBtA2dp && musicActive -> AudioPolicy(
                route = ROUTE_BT_MEDIA,
                usage = USAGE_MEDIA,
                gain = GAIN_MEDIA,
                stream = STREAM_MUSIC
            )
            hasBtA2dp && !musicActive -> AudioPolicy(
                route = ROUTE_BT_PRIME,
                usage = USAGE_PRIME,
                gain = GAIN_PRIME,
                stream = STREAM_RING
            )
            else -> AudioPolicy(
                route = ROUTE_PHONE_ALARM,
                usage = USAGE_ALARM,
                gain = GAIN_ALARM,
                stream = STREAM_ALARM
            )
        }
    }

    private fun hasBluetoothA2dpOutput(am: AudioManager): Boolean {
        return try {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildPool(context: Context, usage: Int): SoundPool {
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()
    }

    private fun playViaPool(resId: Int, policy: AudioPolicy, scalar: Float): Boolean {
        val (pool, sid) = when (policy.route) {
            ROUTE_BT_MEDIA -> poolMedia to if (resId == R.raw.red_alert) sndRedMedia else sndBeepMedia
            ROUTE_BT_PRIME -> poolPrime to if (resId == R.raw.red_alert) sndRedPrime else sndBeepPrime
            else -> poolAlarm to if (resId == R.raw.red_alert) sndRedAlarm else sndBeepAlarm
        }
        val p = pool
        if (p == null || sid == 0) return false
        val v = scalar.coerceIn(0f, 1f)
        // Play returns streamId (0 == fail)
        val streamId = runCatching { p.play(sid, v, v, 1, 0, 1f) }.getOrDefault(0)
        return streamId != 0
    }

    private fun requestAlertAudioFocus(am: AudioManager, gain: Int, usage: Int): Boolean {
        if (audioFocusGranted && gain == lastFocusGain && usage == lastFocusUsage) return true

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
            return audioFocusGranted
        } else {
            @Suppress("DEPRECATION")
            audioFocusGranted = (am.requestAudioFocus(
                null,
                AudioManager.STREAM_RING,
                gain
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            if (audioFocusGranted) {
                lastFocusGain = gain
                lastFocusUsage = usage
            }
            return audioFocusGranted
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

    private fun beginAudioHold(am: AudioManager, stream: Int, scalar: Float, reason: String) {
        if (stream == STREAM_NONE) return
        // Increase a hold counter so multiple back-to-back alerts don't fight volume restore.
        activeAudioHolds += 1

        // Save original volume only once per stream.
        when (stream) {
            STREAM_MUSIC -> if (savedMusicVol < 0) savedMusicVol = am.getStreamVolume(STREAM_MUSIC)
            STREAM_RING -> if (savedRingVol < 0) savedRingVol = am.getStreamVolume(STREAM_RING)
            STREAM_ALARM -> if (savedAlarmVol < 0) savedAlarmVol = am.getStreamVolume(STREAM_ALARM)
        }

        // Make app volume actually meaningful:
        // - map slider (0..1) to system stream volume for the chosen route
        // - restore after playback/TTS ends
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val desired = (max * scalar.coerceIn(0.05f, 1f)).toInt().coerceIn(1, max)
        val cur = am.getStreamVolume(stream)
        if (desired != cur) {
            runCatching {
                am.setStreamVolume(stream, desired, /*flags=*/0)
            }
        }

        // Minimal always-on audit.
        SessionActivityLogger.log("audio_hold_begin reason=$reason stream=${streamName(stream)} cur=$cur desired=$desired max=$max")
    }

    private fun endAudioHold(am: AudioManager) {
        val left = (activeAudioHolds - 1).coerceAtLeast(0)
        activeAudioHolds = left
        if (left > 0) return

        fun restore(stream: Int, saved: Int) {
            if (saved >= 0) {
                runCatching { am.setStreamVolume(stream, saved, 0) }
            }
        }
        restore(STREAM_MUSIC, savedMusicVol)
        restore(STREAM_RING, savedRingVol)
        restore(STREAM_ALARM, savedAlarmVol)
        savedMusicVol = -1
        savedRingVol = -1
        savedAlarmVol = -1

        SessionActivityLogger.log("audio_hold_end")
    }

    private fun logAudioEvent(
        kind: String,
        level: Int,
        policy: AudioPolicy,
        focusOk: Boolean,
        fallback: Boolean,
        fail: String?
    ) {
        val hasBt = (policy.route == ROUTE_BT_MEDIA || policy.route == ROUTE_BT_PRIME)
        val msg = buildString(128) {
            append("audio_")
            append(kind)
            append(" lvl=")
            append(level)
            append(" route=")
            append(routeName(policy.route))
            append(" usage=")
            append(policy.usage)
            append(" gain=")
            append(policy.gain)
            append(" stream=")
            append(streamName(policy.stream))
            append(" bt=")
            append(if (hasBt) 1 else 0)
            append(" focus=")
            append(if (focusOk) "GRANTED" else "DENIED")
            if (fallback) append(" fallback=1")
            if (fail != null) {
                append(" fail=")
                append(fail)
            }
        }
        SessionActivityLogger.log(msg)
    }

    private fun routeName(route: Int): String = when (route) {
        ROUTE_BT_MEDIA -> "BT_MEDIA"
        ROUTE_BT_PRIME -> "BT_PRIME"
        ROUTE_PHONE_ALARM -> "PHONE"
        else -> "?"
    }

    private fun streamName(stream: Int): String = when (stream) {
        STREAM_MUSIC -> "MUSIC"
        STREAM_RING -> "RING"
        STREAM_ALARM -> "ALARM"
        else -> "?"
    }
}
