package com.jondude1.cleardash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.jondude1.cleardash.data.CarMcpServer
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.data.Prefs

class ClearDashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        DataHub.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Live data", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Car alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIPS, "Trip debriefs", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (Prefs.atlasLink) CarMcpServer.start()
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_ALERTS = "alerts"
        const val CH_TRIPS = "trips"
    }
}
