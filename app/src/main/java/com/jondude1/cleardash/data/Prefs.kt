package com.jondude1.cleardash.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** All user settings. Changes bump [version] so running sources can react. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun init(context: Context) {
        sp = context.getSharedPreferences("cleardash", Context.MODE_PRIVATE)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _version.value++
    }

    // OBD adapter
    var obdAddress: String?
        get() = sp.getString("obd_addr", null)
        set(v) = edit { putString("obd_addr", v) }
    var obdName: String?
        get() = sp.getString("obd_name", null)
        set(v) = edit { putString("obd_name", v) }
    var obdBle: Boolean
        get() = sp.getBoolean("obd_ble", false)
        set(v) = edit { putBoolean("obd_ble", v) }

    // Tires
    fun tireId(w: Wheel): String? = sp.getString("tire_${w.name}", null)
    fun setTireId(w: Wheel, id: String?) = edit {
        // a sensor can only live on one wheel
        if (id != null) Wheel.entries.forEach { o -> if (sp.getString("tire_${o.name}", null) == id) remove("tire_${o.name}") }
        if (id == null) remove("tire_${w.name}") else putString("tire_${w.name}", id)
    }
    fun wheelFor(id: String): Wheel? = Wheel.entries.firstOrNull { tireId(it) == id }

    var tireLowPsi: Int
        get() = sp.getInt("tire_low", 32)
        set(v) = edit { putInt("tire_low", v) }
    var tireHighPsi: Int
        get() = sp.getInt("tire_high", 42)
        set(v) = edit { putInt("tire_high", v) }

    // Vehicle (defaults: 2018 Mazda CX-5 — 36 PSI door placard, 14.8 gal FWD tank)
    var carName: String
        get() = sp.getString("car_name", "Mazda CX-5") ?: "Mazda CX-5"
        set(v) = edit { putString("car_name", v.trim().ifEmpty { "Mazda CX-5" }) }
    var targetPsi: Int
        get() = sp.getInt("target_psi", 36)
        set(v) = edit { putInt("target_psi", v) }
    var tankGal: Float
        get() = sp.getFloat("tank_gal", 14.8f)
        set(v) = edit { putFloat("tank_gal", v) }

    // Home Assistant
    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(v) = edit { putString("ha_url", v.trim().trimEnd('/')) }
    var haToken: String
        get() = sp.getString("ha_token", "") ?: ""
        set(v) = edit { putString("ha_token", v.trim()) }
    var haEntities: List<String>
        get() = (sp.getString("ha_entities", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = edit { putString("ha_entities", v.joinToString(",")) }
    val haConfigured: Boolean get() = haUrl.isNotBlank() && haToken.isNotBlank()

    var homeLat: Double?
        get() = sp.getString("home_lat", null)?.toDoubleOrNull()
        set(v) = edit { putString("home_lat", v?.toString()) }
    var homeLon: Double?
        get() = sp.getString("home_lon", null)?.toDoubleOrNull()
        set(v) = edit { putString("home_lon", v?.toString()) }
    var homeRadiusM: Int
        get() = sp.getInt("home_radius", 250)
        set(v) = edit { putInt("home_radius", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("upd_check", 0L)
        set(v) { sp.edit().putLong("upd_check", v).apply() }
}
