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

    // Stored records (no version bump: nothing needs to re-arm when these change)
    var tripsJson: String
        get() = sp.getString("trips", "[]") ?: "[]"
        set(v) { sp.edit().putString("trips", v).apply() }
    var spotsJson: String
        get() = sp.getString("spots", "[]") ?: "[]"
        set(v) { sp.edit().putString("spots", v).apply() }
    var placeNamesJson: String
        get() = sp.getString("place_names", "{}") ?: "{}"
        set(v) { sp.edit().putString("place_names", v).apply() }
    var parkedJson: String
        get() = sp.getString("parked", "") ?: ""
        set(v) { sp.edit().putString("parked", v).apply() }

    // ATLAS link
    /** Bearer token ATLAS uses for ClearDash's on-phone MCP server. Created on first use. */
    val mcpToken: String
        get() {
            sp.getString("mcp_token", null)?.let { return it }
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = java.security.SecureRandom()
            val t = (1..28).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            sp.edit().putString("mcp_token", t).apply()
            return t
        }
    fun resetMcpToken() = edit { remove("mcp_token") }
    var atlasLink: Boolean
        get() = sp.getBoolean("atlas_link", true)
        set(v) = edit { putBoolean("atlas_link", v) }
    var atlasReports: Boolean
        get() = sp.getBoolean("atlas_reports", true)
        set(v) = edit { putBoolean("atlas_reports", v) }

    // Spoken callouts
    var coVoice: Boolean
        get() = sp.getBoolean("co_voice", true)
        set(v) = edit { putBoolean("co_voice", v) }
    var coGreeting: Boolean
        get() = sp.getBoolean("co_greet", true)
        set(v) = edit { putBoolean("co_greet", v) }
    /** 0 = off, 1 = when the limit drops, 2 = every change. */
    var coLimit: Int
        get() = sp.getInt("co_limit", 1)
        set(v) = edit { putInt("co_limit", v) }
    var coFuel: Boolean
        get() = sp.getBoolean("co_fuel", true)
        set(v) = edit { putBoolean("co_fuel", v) }
    var coLongDrive: Boolean
        get() = sp.getBoolean("co_long", true)
        set(v) = edit { putBoolean("co_long", v) }
    var coSystems: Boolean
        get() = sp.getBoolean("co_sys", true)
        set(v) = edit { putBoolean("co_sys", v) }

    // Look
    /** Night red mode: 0 = off, 1 = auto (follows the car's night mode / sunset), 2 = always. */
    var nightMode: Int
        get() = sp.getInt("night_mode", 1)
        set(v) = edit { putInt("night_mode", v) }

    // Navigation shortcuts
    var homeAddr: String
        get() = sp.getString("home_addr", "") ?: ""
        set(v) = edit { putString("home_addr", v.trim()) }
    var workAddr: String
        get() = sp.getString("work_addr", "") ?: ""
        set(v) = edit { putString("work_addr", v.trim()) }

    /** endedAt of a trip whose shutdown + debrief hasn't been shown on the phone yet. */
    var debriefPending: Long
        get() = sp.getLong("debrief_pending", 0L)
        set(v) { sp.edit().putLong("debrief_pending", v).apply() }

    var lastUpdateCheck: Long
        get() = sp.getLong("upd_check", 0L)
        set(v) { sp.edit().putLong("upd_check", v).apply() }
}
