package com.jondude1.cleardash.data

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jondude1.cleardash.ClearDashApp
import com.jondude1.cleardash.R
import com.jondude1.cleardash.ui.MainActivity

/** Keeps the data hub alive while the phone screen is off. */
class DataService : Service() {
    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(1, n, types)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // Missing permission or background-start restriction: keep running without it.
            Log.w("DataService", "startForeground failed: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                }
            } catch (_: Exception) {
            }
        }
        active = true
        if (!holding) {
            holding = true
            DataHub.acquire()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        if (holding) {
            holding = false
            DataHub.release()
        }
        super.onDestroy()
    }

    companion object {
        @Volatile var active = false
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ClearDashApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ClearDash is live")
            .setContentText("Reading GPS, OBD and tires")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
