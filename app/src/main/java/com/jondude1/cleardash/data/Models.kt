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
)

data class HaEntity(
    val id: String,
    val name: String,
    val state: String,
) {
    val domain: String get() = id.substringBefore('.')
    val isOn: Boolean
        get() = state in setOf("on", "open", "opening", "unlocked", "armed_away", "armed_home", "armed_night", "playing")
}

enum class Severity { INFO, WARN, CRITICAL }

data class CarAlert(
    val key: String,
    val title: String,
    val message: String,
    val severity: Severity,
    val at: Long = System.currentTimeMillis(),
)

data class VehicleState(
    val speedMph: Float? = null,
    val gpsOk: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedLimitMph: Int? = null,
    val nav: NavInfo? = null,
    val obd: ObdData = ObdData(),
    val tires: Map<Wheel, TireReading> = emptyMap(),
    val tpmsScanning: Boolean = false,
    val haStatus: String = "Not set up",
    val activeAlerts: List<CarAlert> = emptyList(),
    val running: Boolean = false,
) {
    /** Best speed: GPS when we have a fix, OBD otherwise. */
    val bestSpeed: Float? get() = if (gpsOk && speedMph != null) speedMph else obd.speedMph?.toFloat() ?: speedMph
}
