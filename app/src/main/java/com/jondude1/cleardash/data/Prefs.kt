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
    /** True when the saved adapter was found + ELM-verified by the auto-selector (not hand-picked). */
    var obdAutoSelected: Boolean
        get() = sp.getBoolean("obd_auto", false)
        set(v) = edit { putBoolean("obd_auto", v) }

    // Tires
    fun tireId(w: Wheel): String? = sp.getString("tire_${w.name}", null)
    fun setTireId(w: Wheel, id: String?) = edit {
        // a sensor can only live on one wheel
        if (id != null) Wheel.entries.forEach { o -> if (sp.getString("tire_${o.name}", null) == id) remove("tire_${o.name}") }
        if (id == null) remove("tire_${w.name}") else putString("tire_${w.name}", id)
    }
    fun wheelFor(id: String): Wheel? = Wheel.entries.firstOrNull { tireId(it) == id }

    /**
     * Reassigns caps between wheels (tire rotation). [newToOld] maps each NEW wheel to the
     * OLD wheel whose sensor moves to it. All current ids are read before any are written.
     */
    fun remapTires(newToOld: Map<Wheel, Wheel>) = edit {
        val current = Wheel.entries.associateWith { sp.getString("tire_${it.name}", null) }
        for ((newWheel, oldWheel) in newToOld) {
            val id = current[oldWheel]
            if (id == null) remove("tire_${newWheel.name}") else putString("tire_${newWheel.name}", id)
        }
    }

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

    // Auto-selector results
    /** Addresses of the tire caps that passed the auto-selector's stability/plausibility check. */
    var tpmsAutoSet: List<String>
        get() = (sp.getString("tpms_auto_set", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = edit { putString("tpms_auto_set", v.distinct().joinToString(",")) }
    var autoSelectLastRun: Long
        get() = sp.getLong("auto_sel_last", 0L)
        set(v) = edit { putLong("auto_sel_last", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("upd_check", 0L)
        set(v) { sp.edit().putLong("upd_check", v).apply() }
}
