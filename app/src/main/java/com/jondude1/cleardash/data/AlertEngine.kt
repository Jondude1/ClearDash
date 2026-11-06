package com.jondude1.cleardash.data

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jondude1.cleardash.ClearDashApp
import com.jondude1.cleardash.R
import com.jondude1.cleardash.ui.MainActivity
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Watches every source and raises one consistent set of alerts. */
object AlertEngine {
    private val lastRaised = HashMap<String, Long>()
    private val psiHistory = HashMap<Wheel, ArrayDeque<Pair<Long, Float>>>()
    private const val REPEAT_MS = 15 * 60_000L

    suspend fun run() {
        while (true) {
            evaluate(DataHub.state.value)
            delay(1_000)
        }
    }

    private fun evaluate(s: VehicleState) {
        val now = System.currentTimeMillis()
        val active = mutableListOf<CarAlert>()

        // Tires
        for ((wheel, t) in s.tires) {
            if (now - t.seenAt > 10 * 60_000) continue
            val hist = psiHistory.getOrPut(wheel) { ArrayDeque() }
            hist.addLast(now to t.psi)
            while (hist.isNotEmpty() && now - hist.first().first > 3 * 60_000) hist.removeFirst()
            val drop = (hist.maxOfOrNull { it.second } ?: t.psi) - t.psi

            when {
                drop >= 3f -> active += CarAlert("tire_leak_$wheel", "${wheel.label} losing air",
                    "Down ${drop.roundToInt()} PSI in a few minutes — now ${t.psi.roundToInt()} PSI", Severity.CRITICAL)
                t.psi < Prefs.tireLowPsi -> active += CarAlert("tire_low_$wheel", "Low tire: ${wheel.label}",
                    "${t.psi.roundToInt()} PSI (warning below ${Prefs.tireLowPsi})", Severity.WARN)
                t.psi > Prefs.tireHighPsi -> active += CarAlert("tire_high_$wheel", "High pressure: ${wheel.label}",
                    "${t.psi.roundToInt()} PSI", Severity.WARN)
            }
            t.tempF?.let { if (it > 158f) active += CarAlert("tire_hot_$wheel", "Hot tire: ${wheel.label}", "${it.roundToInt()}°F", Severity.WARN) }
            t.battery?.let { if (it in 1..10) active += CarAlert("tire_batt_$wheel", "Tire sensor battery low", "${wheel.label} sensor at $it%", Severity.INFO) }
        }

        // Engine
        val o = s.obd
        if (o.connected) {
            o.coolantF?.let { if (it >= 230f) active += CarAlert("coolant", "Engine running hot", "Coolant ${it.roundToInt()}°F", Severity.CRITICAL) }
            val v = o.voltage
            val rpm = o.rpm ?: 0
            if (v != null) {
                if (rpm > 600 && v < 13.0f) active += CarAlert("charging", "Charging voltage low", "%.1f V with the engine running".format(v), Severity.WARN)
                if (rpm < 100 && v < 11.9f) active += CarAlert("battery", "Battery low", "%.1f V".format(v), Severity.WARN)
            }
            // Check-engine fault codes: each new code raises once (the repeat/forget
            // machinery below re-raises a code only if it clears and comes back).
            for (d in o.dtcs) {
                active += CarAlert(
                    "dtc_${d.code}", "Check engine: ${d.code}",
                    d.description + if (d.pending) " (pending)" else "",
                    Severity.WARN,
                )
            }
        }

        DataHub.update { it.copy(activeAlerts = active) }

        // Raise new ones (and repeat lingering ones every 15 minutes)
        for (a in active) {
            val last = lastRaised[a.key]
            if (last == null || now - last > REPEAT_MS) {
                lastRaised[a.key] = now
                DataHub.emitAlert(a)
                notifyPhone(a)
            }
        }
        // Forget cleared ones so they alert again if they come back
        lastRaised.keys.retainAll(active.map { it.key }.toSet())
    }

    private fun notifyPhone(a: CarAlert) {
        val ctx = DataHub.app
        try {
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, ClearDashApp.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(a.title)
                .setContentText(a.message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(a.key.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
