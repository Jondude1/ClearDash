package com.jondude1.cleardash.data

import android.graphics.Bitmap

enum class Wheel(val label: String, val short: String) {
    FL("Front left", "FL"), FR("Front right", "FR"), RL("Rear left", "RL"), RR("Rear right", "RR")
}

data class NavInfo(
    val distance: String,
    val instruction: String,
    val eta: String,
    val icon: Bitmap?,
    val updatedAt: Long,
)

data class TireReading(
    val id: String,
    val psi: Float,
    val tempF: Float?,
    val battery: Int?,
    val alarm: Boolean,
    val seenAt: Long,
    /** Position number printed/encoded by some cap sets (1..4), when the broadcast carries it. */
    val sensorIndex: Int? = null,
)

/** One diagnostic trouble code (check-engine fault), stored or pending. */
data class Dtc(
    val code: String,
    val description: String,
    val pending: Boolean,
)

data class ObdData(
    val status: String = "Not set up",
    val connected: Boolean = false,
    val rpm: Int? = null,
    val speedMph: Int? = null,
    val coolantF: Float? = null,
    val voltage: Float? = null,
    val load: Int? = null,
    val intakeF: Float? = null,
    val fuelPct: Int? = null,
    val stft: Float? = null,
    val ltft: Float? = null,
    val dtcs: List<Dtc> = emptyList(),
)

enum class Severity { INFO, WARN, CRITICAL }

data class CarAlert(
    val key: String,
    val title: String,
    val message: String,
    val severity: Severity,
    val at: Long = System.currentTimeMillis(),
)

data class MediaInfo(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    val playing: Boolean,
    /** Audiobooks and podcasts: skip buttons jump 30 s instead of changing track. */
    val longForm: Boolean,
) {
    fun livePosition(now: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val p = if (playing) positionMs + ((now - positionAt) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

data class VehicleState(
    val media: MediaInfo? = null,
    val speedMph: Float? = null,
    val gpsOk: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedLimitMph: Int? = null,
    val nav: NavInfo? = null,
    val obd: ObdData = ObdData(),
    val tires: Map<Wheel, TireReading> = emptyMap(),
    val tpmsScanning: Boolean = false,
    val activeAlerts: List<CarAlert> = emptyList(),
    val running: Boolean = false,
) {
    /** Best speed: GPS when we have a fix, OBD otherwise. */
    val bestSpeed: Float? get() = if (gpsOk && speedMph != null) speedMph else obd.speedMph?.toFloat() ?: speedMph
}
