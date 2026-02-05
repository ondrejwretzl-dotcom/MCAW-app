package com.mcaw.location.sources

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

class LocationSpeedSource(private val context: Context) {
    data class Sample(val speedMps: Float, val timestampMs: Long)

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile
    private var latestSample: Sample? = null
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speed = if (location.hasSpeed()) location.speed else 0f
            latestSample = Sample(speed, android.os.SystemClock.elapsedRealtime())
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !hasFineLocation()) return
        started = true
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0.2f, listener, Looper.getMainLooper())
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0.3f, listener, Looper.getMainLooper())
        }
    }

    fun stop() {
        if (!started) return
        manager.removeUpdates(listener)
        started = false
    }

    fun latest(): Sample? = latestSample

    private fun hasFineLocation(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
