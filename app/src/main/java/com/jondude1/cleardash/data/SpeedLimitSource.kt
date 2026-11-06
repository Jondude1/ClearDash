package com.jondude1.cleardash.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Speed limits from OpenStreetMap via the free Overpass API.
 * Queries only after moving ~80 m or every 20 s, so it stays polite.
 */
object SpeedLimitSource {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastQuery = 0L
    private var misses = 0

    suspend fun run() {
        while (true) {
            val s = DataHub.state.value
            val lat = s.lat
            val lon = s.lon
            if (s.gpsOk && lat != null && lon != null) {
                val moved = Geo.distanceM(lat, lon, lastLat, lastLon)
                val age = System.currentTimeMillis() - lastQuery
                if ((moved > 80 && age > 4_000) || age > 20_000) {
                    lastLat = lat; lastLon = lon; lastQuery = System.currentTimeMillis()
                    val limit = withContext(Dispatchers.IO) { lookup(lat, lon) }
                    if (limit != null) {
                        misses = 0
                        DataHub.update { it.copy(speedLimitMph = limit) }
                    } else if (++misses >= 3) {
                        // a few empty answers in a row: we've left the tagged road
                        DataHub.update { it.copy(speedLimitMph = null) }
                    }
                }
            }
            delay(2_000)
        }
    }

    private fun lookup(lat: Double, lon: Double): Int? = try {
        val q = "[out:json][timeout:6];way(around:22,$lat,$lon)[highway][maxspeed];out tags 3;"
        val r = Net.request("$ENDPOINT?data=${Net.enc(q)}", timeoutMs = 7_000)
        if (r.code != 200) null else {
            val els = JSONObject(r.body).optJSONArray("elements")
            var result: Int? = null
            if (els != null) {
                for (i in 0 until els.length()) {
                    val tags = els.getJSONObject(i).optJSONObject("tags") ?: continue
                    result = parse(tags.optString("maxspeed"))
                    if (result != null) break
                }
            }
            result
        }
    } catch (e: Exception) {
        Log.d("SpeedLimit", "lookup failed: ${e.message}")
        null
    }

    /** "45 mph" -> 45, "70" (km/h) -> 45 */
    fun parse(raw: String): Int? {
        val t = raw.trim().lowercase()
        val num = Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (t.contains("mph")) num else ((num * 0.621371) / 5.0).roundToInt() * 5
    }
}

