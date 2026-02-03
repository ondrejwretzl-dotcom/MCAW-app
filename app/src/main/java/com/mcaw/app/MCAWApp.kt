package com.mcaw.app

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import com.mcaw.config.AppPreferences
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Plnohodnotná App tøída:
 * - Init AppPreferences
 * - Globální IO executor
 * - TTS (volitelnì dle nastavení)
 * - Uncaught crash handler (log do files/logs/)
 */
class MCAWApp : Application(), TextToSpeech.OnInitListener {

    companion object {
        lateinit var instance: MCAWApp
            private set

        lateinit var ioExecutor: ExecutorService
            private set

        /** Spus úlohu na IO vláknì. */
        fun runIO(task: Runnable) = ioExecutor.execute(task)
    }

    private var tts: TextToSpeech? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Preferences
        AppPreferences.init(this)

        // IO executor
        ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mcaw-io").apply { isDaemon = true }
        }

        // Crash handler
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        // TTS (pokud je povoleno)
        if (AppPreferences.voice) {
            try {
                tts = TextToSpeech(this, this)
            } catch (e: Exception) {
                Log.e("MCAWApp", "TTS init failed", e)
            }
        }
    }

    override fun onTerminate() {
        try {
            tts?.shutdown()
            ioExecutor.shutdown()
        } catch (_: Exception) {
        }
        super.onTerminate()
    }

    override fun onLowMemory() {
        Log.w("MCAWApp", "onLowMemory")
        super.onLowMemory()
    }

    override fun onInit(status: Int) {
        // Mùžeš nastavit jazyk, rychlost atd. Dle potøeby.
        // tts?.language = Locale.getDefault()
    }

    /** Bezpeèné použití TTS z app vrstvy. */
    fun speakNow(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "app_say")
        } catch (_: Exception) { /* ignore */ }
    }

    fun getTts(): TextToSpeech? = tts

    private fun logCrash(thread: Thread, t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val stack = sw.toString()

            Log.e("MCAWApp", "Uncaught exception in ${thread.name}:\n$stack")

            val dir = File(filesDir, "logs").apply { if (!exists()) mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(System.currentTimeMillis())
            File(dir, "crash_$stamp.txt").writeText(stack)
        } catch (_: Exception) {
            // ignore
        }
    }
}
