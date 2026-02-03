package com.mcaw.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.mcaw.config.AppPreferences

class SpeedMonitor(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speed = if (location.hasSpeed()) location.speed else 0f
            AppPreferences.lastSpeedMps = speed
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening) return
        if (!hasLocationPermission()) return
        val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!hasGps && !hasNetwork) return
        listening = true
        if (hasGps) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0.5f,
                listener,
                Looper.getMainLooper()
            )
        }
        if (hasNetwork) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                1f,
                listener,
                Looper.getMainLooper()
            )
        }
    }

    fun stop() {
        if (!listening) return
        locationManager.removeUpdates(listener)
        listening = false
    }

    private fun hasLocationPermission(): Boolean {
        val perm = android.Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
