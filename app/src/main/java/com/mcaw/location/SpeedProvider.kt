package com.mcaw.location

import android.content.Context
import android.os.SystemClock
import com.mcaw.config.AppPreferences
import com.mcaw.location.sources.BluetoothSpeedSource
import com.mcaw.location.sources.LocationSpeedSource

class SpeedProvider(private val context: Context) {

    enum class Source { BLE, GPS, SENSOR, UNKNOWN }

    data class Reading(
        val speedMps: Float,
        val source: Source,
        val confidence: Float,
        val timestampMs: Long
    )

    private val gpsSource = LocationSpeedSource(context)
    private val bleSource = BluetoothSpeedSource(context)

    // IMPORTANT: speedMps is Float.NaN when speed is unknown (no fresh GPS/BLE).
    private var lastReading: Reading = Reading(Float.NaN, Source.UNKNOWN, 0f, 0L)

    fun start() {
        gpsSource.start()
        bleSource.start()
    }

    fun stop() {
        gpsSource.stop()
        bleSource.stop()
    }

    fun getCurrent(): Reading {
        val now = SystemClock.elapsedRealtime()
        val ble = bleSource.latest()
        val gps = gpsSource.latest()

        val selected = when {
            ble != null && now - ble.timestampMs <= 2000L ->
                Reading(ble.speedMps, Source.BLE, 0.95f, ble.timestampMs)

            gps != null && now - gps.timestampMs <= 2000L ->
                Reading(gps.speedMps, Source.GPS, 0.85f, gps.timestampMs)

            gps != null && now - gps.timestampMs <= 4000L ->
                Reading(gps.speedMps, Source.GPS, 0.55f, gps.timestampMs)

            else ->
                Reading(Float.NaN, Source.UNKNOWN, 0f, now)
        }

        // Very short hold to mask brief update jitter. NEVER hold UNKNOWN->0, because that disables alerts.
        val held = if (
            selected.source == Source.UNKNOWN &&
            lastReading.source != Source.UNKNOWN &&
            now - lastReading.timestampMs <= 1500L &&
            lastReading.speedMps.isFinite()
        ) {
            lastReading.copy(confidence = 0.2f)
        } else {
            selected
        }

        lastReading = held

        // Keep preferences stable; don't overwrite lastSpeedMps with NaN.
        if (held.speedMps.isFinite()) {
            AppPreferences.lastSpeedMps = held.speedMps
        }

        return held
    }
}
