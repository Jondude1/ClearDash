package com.jondude1.cleardash.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jondude1.cleardash.ClearDashApp
import com.jondude1.cleardash.R
import com.jondude1.cleardash.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** One finished drive. */
data class TripSummary(
    val startedAt: Long,
    val endedAt: Long,
    val distanceMi: Float,
    val movingSec: Int,
    val totalSec: Int,
    val topMph: Float,
    val overLimitSec: Int,
    val fuelUsedGal: Float?,
    val endLat: Double?,
    val endLon: Double?,
) {
    val avgMph: Float get() = if (movingSec > 30) distanceMi / (movingSec / 3600f) else 0f

    fun headline() = "%.1f mi · %s".format(distanceMi, fmtDuration(totalSec))

    fun lines(): List<Pair<String, String>> = buildList {
        add("Distance" to "%.1f mi".format(distanceMi))
        add("Drive time" to fmtDuration(totalSec) + if (movingSec in 1 until totalSec - 60) " (${fmtDuration(movingSec)} moving)" else "")
        add("Average" to "${avgMph.roundToInt()} mph moving")
        add("Top speed" to "${topMph.roundToInt()} mph")
        add("Over the limit" to if (overLimitSec < 5) "none" else fmtDuration(overLimitSec))
        fuelUsedGal?.let { add("Fuel used" to "≈ %.2f gal".format(it)) }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("start", startedAt).put("end", endedAt).put("mi", distanceMi.toDouble())
        .put("moving", movingSec).put("total", totalSec).put("top", topMph.toDouble())
        .put("over", overLimitSec).put("fuel", fuelUsedGal?.toDouble() ?: JSONObject.NULL)
        .put("lat", endLat ?: JSONObject.NULL).put("lon", endLon ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = TripSummary(
            o.optLong("start"), o.optLong("end"), o.optDouble("mi", 0.0).toFloat(),
            o.optInt("moving"), o.optInt("total"), o.optDouble("top", 0.0).toFloat(), o.optInt("over"),
            if (o.isNull("fuel")) null else o.optDouble("fuel").toFloat(),
            if (o.isNull("lat")) null else o.optDouble("lat"),
            if (o.isNull("lon")) null else o.optDouble("lon"),
        )

        fun fmtDuration(sec: Int): String {
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            return when {
                h > 0 -> "${h} h ${m} min"
                m > 0 -> "$m min"
                else -> "$s s"
            }
        }
    }
}

/** A saved place: where the car was parked, or a spot marked from the knob or by ATLAS. */
data class Spot(val lat: Double, val lon: Double, val at: Long, val note: String) {
    fun mapsUrl() = "https://maps.google.com/?q=%.6f,%.6f".format(lat, lon)
    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("at", at).put("note", note)
    fun whenText(): String = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(at))

    companion object {
        fun fromJson(o: JSONObject) = Spot(o.optDouble("lat"), o.optDouble("lon"), o.optLong("at"), o.optString("note"))
    }
}

/** Live numbers for the drive in progress. */
data class TripLive(
    val startedAt: Long,
    val distanceMi: Float = 0f,
    val movingSec: Int = 0,
    val topMph: Float = 0f,
    val overLimitSec: Int = 0,
    val fuelStartPct: Int? = null,
    val fuelNowPct: Int? = null,
) {
    val totalSec: Int get() = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
    val avgMph: Float get() = if (movingSec > 30) distanceMi / (movingSec / 3600f) else 0f
}

/**
 * Records each drive while ClearDash is open in the car: distance from GPS, moving time,
 * top speed, time over the posted limit and fuel used (from the OBD fuel level). When the
 * car disconnects it saves a debrief, remembers where you parked, posts a phone notification
 * and reports the trip to ATLAS.
 */
object Trips {
    private const val MIN_MILES = 0.2f
    private const val KEEP = 30

    private val _live = MutableStateFlow<TripLive?>(null)
    val live: StateFlow<TripLive?> = _live

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    val active: Boolean get() = _live.value != null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _live.value = TripLive(System.currentTimeMillis())
        lastLat = null; lastLon = null
        job = scope.launch {
            var lastTick = System.currentTimeMillis()
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val dt = ((now - lastTick) / 1000f).coerceIn(0f, 5f)
                lastTick = now
                tick(DataHub.state.value, dt)
            }
        }
    }

    private fun tick(s: VehicleState, dt: Float) {
        val t = _live.value ?: return
        Tuning.sample(s, dt)
        val spd = s.bestSpeed ?: 0f
        val moving = spd > 2f
        var dist = t.distanceMi
        if (s.gpsOk && s.lat != null && s.lon != null) {
            val pl = lastLat; val pn = lastLon
            if (pl != null && pn != null && moving) {
                val d = Geo.distanceM(pl, pn, s.lat, s.lon)
                if (d < 80.0 * dt + 30.0) dist += (d / 1609.344).toFloat()
            }
            lastLat = s.lat; lastLon = s.lon
        }
        val limit = s.speedLimitMph
        val fuel = if (s.obd.connected) s.obd.fuelPct else null
        _live.value = t.copy(
            distanceMi = dist,
            movingSec = t.movingSec + if (moving) dt.roundToInt() else 0,
            topMph = max(t.topMph, spd),
            overLimitSec = t.overLimitSec + if (moving && limit != null && spd > limit + 2) dt.roundToInt() else 0,
            fuelStartPct = t.fuelStartPct ?: fuel,
            fuelNowPct = fuel ?: t.fuelNowPct,
        )
    }

    /** Ends the drive. Returns the saved summary, or null if it was too short to keep. */
    @Synchronized
    fun finish(ctx: Context): TripSummary? {
        job?.cancel(); job = null
        Tuning.endDrive()
        val t = _live.value ?: return null
        _live.value = null
        val s = DataHub.state.value
        val endLat = s.lat ?: lastLat
        val endLon = s.lon ?: lastLon
        if (endLat != null && endLon != null) {
            Prefs.parkedJson = Spot(endLat, endLon, System.currentTimeMillis(), "Parked").toJson().toString()
        }
        if (endLat != null && endLon != null) scope.launch { PlaceNames.lookup(endLat, endLon) } // name it for the NAV panel
        if (t.distanceMi < MIN_MILES) return null
        val fuelUsed = run {
            val a = t.fuelStartPct; val b = t.fuelNowPct
            if (a != null && b != null && a > b) (a - b) / 100f * Prefs.tankGal else null
        }
        val sum = TripSummary(
            t.startedAt, System.currentTimeMillis(), t.distanceMi, t.movingSec, t.totalSec,
            t.topMph, t.overLimitSec, fuelUsed, endLat, endLon,
        )
        save(sum)
        Prefs.debriefPending = sum.endedAt // the phone plays the power-down + debrief next time it's opened
        notifyPhone(ctx, sum)
        reportToAtlas(ctx, sum)
        return sum
    }

    private fun save(sum: TripSummary) {
        val list = listOf(sum) + history()
        val a = JSONArray()
        list.take(KEEP).forEach { a.put(it.toJson()) }
        Prefs.tripsJson = a.toString()
    }

    fun history(): List<TripSummary> = try {
        val a = JSONArray(Prefs.tripsJson)
        (0 until a.length()).map { TripSummary.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun last(): TripSummary? = history().firstOrNull()

    fun parked(): Spot? = try {
        Prefs.parkedJson.takeIf { it.isNotBlank() }?.let { Spot.fromJson(JSONObject(it)) }
    } catch (_: Exception) {
        null
    }

    // ── marked spots ──

    fun spots(): List<Spot> = try {
        val a = JSONArray(Prefs.spotsJson)
        (0 until a.length()).map { Spot.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    /** Saves the car's current position. Returns null when there's no GPS fix. */
    fun markSpot(note: String = ""): Spot? {
        val s = DataHub.state.value
        val lat = s.lat ?: return null
        val lon = s.lon ?: return null
        val spot = Spot(lat, lon, System.currentTimeMillis(), note.ifBlank { "Marked spot" })
        val a = JSONArray()
        (listOf(spot) + spots()).take(20).forEach { a.put(it.toJson()) }
        Prefs.spotsJson = a.toString()
        scope.launch { PlaceNames.lookup(lat, lon) }
        return spot
    }

    fun deleteSpot(at: Long) {
        val a = JSONArray()
        spots().filter { it.at != at }.forEach { a.put(it.toJson()) }
        Prefs.spotsJson = a.toString()
    }

    // ── outputs ──

    private fun notifyPhone(ctx: Context, t: TripSummary) {
        try {
            val pi = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_DEBRIEF, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val body = t.lines().joinToString("\n") { (k, v) -> "$k: $v" }
            val n = NotificationCompat.Builder(ctx, ClearDashApp.CH_TRIPS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Trip debrief · ${t.headline()}")
                .setContentText("Avg ${t.avgMph.roundToInt()} mph · top ${t.topMph.roundToInt()} mph")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(4_200, n)
        } catch (_: SecurityException) {
        }
    }

    private fun reportToAtlas(ctx: Context, t: TripSummary) {
        if (!Prefs.atlasReports) return
        val body = buildString {
            append(t.lines().joinToString("; ") { (k, v) -> "$k $v" })
            if (t.endLat != null && t.endLon != null) append(". Parked at %.5f, %.5f".format(t.endLat, t.endLon))
        }
        Atlas.report(ctx, "Trip: ${t.headline()}", body, "low", "cleardash-trip-${t.endedAt}")
    }
}

/** ATLAS's report inbox: other apps on the phone report here and ATLAS forwards to the hub on Obelisk. */
object Atlas {
    const val PACKAGE = "com.jondude.anzu"

    fun report(ctx: Context, title: String, body: String, priority: String = "normal", key: String? = null) {
        try {
            val i = Intent("com.jondude.atlas.REPORT").setPackage(PACKAGE)
                .putExtra("app", "ClearDash").putExtra("title", title).putExtra("body", body)
                .putExtra("priority", priority)
            if (key != null) i.putExtra("key", key)
            ctx.sendBroadcast(i)
        } catch (_: Exception) {
        }
    }
}
