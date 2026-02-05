package com.mcaw.location

import com.mcaw.config.AppPreferences

class SpeedMonitor(private val speedProvider: SpeedProvider) {
    private var running = false

    fun start() {
        if (running) return
        running = true
        speedProvider.start()
    }

    fun stop() {
        if (!running) return
        speedProvider.stop()
        running = false
    }

    fun pollCurrentSpeedMps(): Float {
        val reading = speedProvider.getCurrent()
        AppPreferences.lastSpeedMps = reading.speedMps
        return reading.speedMps
    }
}
