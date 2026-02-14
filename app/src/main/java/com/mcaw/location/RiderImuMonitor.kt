package com.mcaw.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Lightweight IMU monitor:
 * - ego braking confidence (0..1) from linear accel (best-effort, orientation-agnostic)
 * - lean angle estimate (deg) from gravity (accelerometer low-pass)
 *
 * Designed to be safe + cheap (Samsung A56 5G).
 */
class RiderImuMonitor(context: Context) : SensorEventListener {

    data class Snapshot(
        val brakeConfidence: Float, // 0..1
        val leanDeg: Float          // deg, NaN if unknown
    )

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linAcc: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // gravity estimate (low-pass)
    private var gx = Float.NaN
    private var gy = Float.NaN
    private var gz = Float.NaN

    // braking signal EMA
    private var brakeEma = 0f
    private var brakeEmaValid = false

    @Volatile private var lastBrakeConfidence: Float = 0f
    @Volatile private var lastLeanDeg: Float = Float.NaN

    init {
        // Register with SENSOR_DELAY_GAME: good tradeoff for responsiveness vs power.
        // If sensor missing -> no-op.
        linAcc?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun snapshot(nowMs: Long): Snapshot {
        return Snapshot(
            brakeConfidence = lastBrakeConfidence.coerceIn(0f, 1f),
            leanDeg = lastLeanDeg
        )
    }

    fun stop() {
        try { sm.unregisterListener(this) } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Orientation-agnostic braking signal:
                // use the strongest negative axis component as "deceleration-like".
                val ax = event.values.getOrNull(0) ?: return
                val ay = event.values.getOrNull(1) ?: return
                val az = event.values.getOrNull(2) ?: return

                val neg = maxOf(0f, -ax, -ay, -az) // m/s^2
                // normalize: ~2.5 m/s^2 (0.25g) => strong braking on bike/car
                val raw = (neg / 2.5f).coerceIn(0f, 1f)

                val alpha = 0.18f
                brakeEma = if (!brakeEmaValid) raw else (brakeEma + alpha * (raw - brakeEma))
                brakeEmaValid = true
                lastBrakeConfidence = brakeEma.coerceIn(0f, 1f)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values.getOrNull(0) ?: return
                val ay = event.values.getOrNull(1) ?: return
                val az = event.values.getOrNull(2) ?: return

                // low-pass to estimate gravity
                val alpha = 0.08f
                if (!gx.isFinite()) {
                    gx = ax; gy = ay; gz = az
                } else {
                    gx += alpha * (ax - gx)
                    gy += alpha * (ay - gy)
                    gz += alpha * (az - gz)
                }

                val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-3f)
                val nx = gx / norm
                val ny = gy / norm
                val nz = gz / norm

                // Best-effort "lean magnitude": max(|roll|, |pitch|).
                val roll = atan2(nx, nz) * 57.29578f
                val pitch = atan2(ny, nz) * 57.29578f
                lastLeanDeg = maxOf(abs(roll), abs(pitch))
            }
        }
    }
}
