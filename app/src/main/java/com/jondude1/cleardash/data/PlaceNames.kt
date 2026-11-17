package com.jondude1.cleardash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Friendly names for saved coordinates ("Sheetz · Meadville" instead of 41.64, -80.15),
 * from OpenStreetMap's free reverse geocoder. Looked up once per place and cached.
 */
object PlaceNames {
    private fun key(lat: Double, lon: Double) = "%.4f,%.4f".format(lat, lon)

    private fun all(): JSONObject = try { JSONObject(Prefs.placeNamesJson) } catch (_: Exception) { JSONObject() }

    fun cached(lat: Double, lon: Double): String? = all().optString(key(lat, lon)).takeIf { it.isNotBlank() }

    suspend fun lookup(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        cached(lat, lon)?.let { return@withContext it }
        try {
            val r = Net.request(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&lat=$lat&lon=$lon",
                headers = mapOf("Accept-Language" to "en"), timeoutMs = 8_000,
            )
            if (r.code != 200) return@withContext null
            val o = JSONObject(r.body)
            val a = o.optJSONObject("address") ?: JSONObject()
            val place = o.optString("name").takeIf { it.isNotBlank() }
                ?: listOf(a.optString("house_number"), a.optString("road")).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
            val town = listOf("city", "town", "village", "hamlet", "suburb").map { a.optString(it) }.firstOrNull { it.isNotBlank() }
            val label = listOfNotNull(place, town).distinct().joinToString(" · ").takeIf { it.isNotBlank() } ?: return@withContext null
            val m = all().put(key(lat, lon), label)
            Prefs.placeNamesJson = m.toString()
            label
        } catch (_: Exception) {
            null
        }
    }
}
