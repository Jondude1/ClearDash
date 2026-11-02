package com.jondude1.cleardash.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/** GPS speed and position. */
class LocationSource(context: Context) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val staleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastFix > 5_000) {
                DataHub.update { it.copy(gpsOk = false) }
            }
            handler.postDelayed(this, 2_000)
        }
    }
    private var lastFix = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        handler.post {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w("Location", "No GPS permission yet: ${e.message}")
            }
            handler.postDelayed(staleCheck, 2_000)
        }
    }

    fun stop() {
        handler.removeCallbacks(staleCheck)
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        lastFix = System.currentTimeMillis()
        val mph = if (loc.hasSpeed()) loc.speed * 2.23694f else null
        DataHub.update {
            it.copy(
                gpsOk = true,
                lat = loc.latitude,
                lon = loc.longitude,
                speedMph = mph?.let { s -> if (s < 1.2f) 0f else s },
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        DataHub.update { it.copy(gpsOk = false) }
    }
}
