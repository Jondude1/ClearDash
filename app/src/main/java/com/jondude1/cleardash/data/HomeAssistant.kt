package com.jondude1.cleardash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Home Assistant REST API over your Tailscale address. */
object HomeAssistant {
    val controllableDomains = listOf(
        "cover", "light", "switch", "lock", "alarm_control_panel",
        "scene", "script", "input_boolean", "fan", "button",
    )

    private fun headers(token: String = Prefs.haToken) = mapOf(
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json",
    )

    suspend fun ping(url: String = Prefs.haUrl, token: String = Prefs.haToken): Boolean = withContext(Dispatchers.IO) {
        try {
            Net.request("$url/api/", headers = headers(token), timeoutMs = 6_000).code == 200
        } catch (_: Exception) {
            false
        }
    }

    /** Everything that can be tapped, for the setup picker. */
    suspend fun listControllable(url: String = Prefs.haUrl, token: String = Prefs.haToken): List<HaEntity> =
        withContext(Dispatchers.IO) {
            val r = Net.request("$url/api/states", headers = headers(token), timeoutMs = 10_000)
            if (r.code == 401) throw IllegalStateException("Token rejected (401)")
            if (r.code != 200) throw IllegalStateException("Home Assistant answered ${r.code}")
            val arr = JSONArray(r.body)
            (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
                .filter { it.domain in controllableDomains }
                .sortedWith(compareBy({ controllableDomains.indexOf(it.domain) }, { it.name.lowercase() }))
        }

    /** Current state of the entities chosen for the car. */
    suspend fun fetchChosen(): List<HaEntity> = withContext(Dispatchers.IO) {
        Prefs.haEntities.mapNotNull { id ->
            try {
                val r = Net.request("${Prefs.haUrl}/api/states/$id", headers = headers(), timeoutMs = 6_000)
                if (r.code == 200) parse(JSONObject(r.body)) else HaEntity(id, id, "unavailable")
            } catch (_: Exception) {
                HaEntity(id, id.substringAfter('.').replace('_', ' '), "unavailable")
            }
        }
    }

    /** The natural "tap" action for each kind of entity. */
    suspend fun tap(e: HaEntity): Boolean = withContext(Dispatchers.IO) {
        val (domain, service) = when (e.domain) {
            "cover" -> "cover" to "toggle"
            "lock" -> "lock" to (if (e.state == "locked") "unlock" else "lock")
            "alarm_control_panel" -> "alarm_control_panel" to (if (e.state == "disarmed") "alarm_arm_away" else "alarm_disarm")
            "scene" -> "scene" to "turn_on"
            "script" -> "script" to "turn_on"
            "button" -> "button" to "press"
            else -> "homeassistant" to "toggle"
        }
        try {
            val body = JSONObject().put("entity_id", e.id).toString()
            Net.request("${Prefs.haUrl}/api/services/$domain/$service", "POST", headers(), body).code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun parse(o: JSONObject): HaEntity {
        val id = o.getString("entity_id")
        val name = o.optJSONObject("attributes")?.optString("friendly_name")?.takeIf { it.isNotBlank() }
            ?: id.substringAfter('.').replace('_', ' ')
        return HaEntity(id, name, o.optString("state"))
    }

    fun prettyState(e: HaEntity): String = when (e.state) {
        "on" -> "On"; "off" -> "Off"
        "open" -> "Open"; "closed" -> "Closed"; "opening" -> "Opening…"; "closing" -> "Closing…"
        "locked" -> "Locked"; "unlocked" -> "Unlocked"
        "disarmed" -> "Disarmed"; "armed_away" -> "Armed away"; "armed_home" -> "Armed home"; "armed_night" -> "Armed night"
        "unavailable" -> "Offline"
        else -> if (e.domain in setOf("scene", "script", "button")) "Tap to run" else e.state.replaceFirstChar { it.uppercase() }
    }
}
