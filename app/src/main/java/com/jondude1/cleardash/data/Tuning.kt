package com.jondude1.cleardash.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Self-tuning notes: ClearDash quietly learns what's normal for this particular CX-5 from the
 * OBD stream (fuel trims, idle speed, charging voltage, warm-up time, temperatures and fuel
 * use by speed), then turns what it learned into plain-English engineering notes.
 *
 * Everything is a suggestion with the numbers behind it, never a diagnosis. Read-only: it
 * never changes anything on the car.
 */
object Tuning {

    /** Cruising-speed bands (lower edge, mph) for fuel economy. */
    private val BANDS = intArrayOf(30, 40, 50, 60, 70)

    data class Tip(
        val key: String,
        val title: String,
        val detail: String,
        /** 0 = healthy, 1 = worth knowing, 2 = worth a look. */
        val level: Int,
    )

    /** A running average that survives restarts. */
    private class Acc(var sum: Double = 0.0, var n: Long = 0) {
        fun add(v: Double) { sum += v; n++ }
        val avg: Double get() = if (n == 0L) 0.0 else sum / n
        fun json() = JSONArray().put(sum).put(n)
        companion object {
            fun of(a: JSONArray?) = if (a == null || a.length() < 2) Acc() else Acc(a.optDouble(0, 0.0), a.optLong(1, 0))
        }
    }

    // ── learned state ──
    private var loaded = false
    private var cruiseTrim = Acc()
    private var idleTrim = Acc()
    private var idleBase = Acc()          // first ~10 min of warm idle: this car's normal
    private var idleRecent = 0.0          // slow moving average since then
    private var idleRecentN = 0L
    private var runVolt = Acc()
    private var lowVoltSec = 0L
    private var highVoltSec = 0L
    private var warmCruiseCool = Acc()    // coolant while cruising after 15 min of driving
    private var hotSec = 0L
    private val warmups = ArrayList<Pair<Float, Float>>()   // (minutes to 175°F, intake °F at start)
    private val bandMiles = DoubleArray(BANDS.size)
    private val bandGal = DoubleArray(BANDS.size)
    private var engineSec = 0L

    // ── this drive ──
    private var driveSec = 0f
    private var startCool: Float? = null
    private var startIntake: Float? = null
    private var warmDone = false
    private var dirty = 0f

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(Prefs.tuningJson)
            cruiseTrim = Acc.of(o.optJSONArray("ct"))
            idleTrim = Acc.of(o.optJSONArray("it"))
            idleBase = Acc.of(o.optJSONArray("ib"))
            idleRecent = o.optDouble("ir", 0.0)
            idleRecentN = o.optLong("irn", 0)
            runVolt = Acc.of(o.optJSONArray("rv"))
            lowVoltSec = o.optLong("lv", 0)
            highVoltSec = o.optLong("hv", 0)
            warmCruiseCool = Acc.of(o.optJSONArray("wc"))
            hotSec = o.optLong("hot", 0)
            engineSec = o.optLong("es", 0)
            o.optJSONArray("wu")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { warmups += it.optDouble(0).toFloat() to it.optDouble(1).toFloat() } }
            o.optJSONArray("bm")?.let { a -> for (i in BANDS.indices) bandMiles[i] = a.optDouble(i, 0.0) }
            o.optJSONArray("bg")?.let { a -> for (i in BANDS.indices) bandGal[i] = a.optDouble(i, 0.0) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun save() {
        if (!loaded) return
        val o = JSONObject()
            .put("ct", cruiseTrim.json()).put("it", idleTrim.json()).put("ib", idleBase.json())
            .put("ir", idleRecent).put("irn", idleRecentN)
            .put("rv", runVolt.json()).put("lv", lowVoltSec).put("hv", highVoltSec)
            .put("wc", warmCruiseCool.json()).put("hot", hotSec).put("es", engineSec)
            .put("wu", JSONArray().apply { warmups.takeLast(30).forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) } })
            .put("bm", JSONArray().apply { bandMiles.forEach { put(it) } })
            .put("bg", JSONArray().apply { bandGal.forEach { put(it) } })
        Prefs.tuningJson = o.toString()
        dirty = 0f
    }

    /** Called once a second during a drive. */
    @Synchronized
    fun sample(s: VehicleState, dt: Float) {
        load()
        val o = s.obd
        if (!o.connected) return
        val rpm = o.rpm ?: return
        if (rpm < 400) return
        engineSec += dt.roundToInt()
        driveSec += dt
        dirty += dt
        val spd = s.bestSpeed ?: 0f
        val cool = o.coolantF
        val warm = cool != null && cool >= 170f

        // warm-up time from a cold start
        if (startCool == null && cool != null) { startCool = cool; startIntake = o.intakeF }
        val sc = startCool
        if (!warmDone && sc != null && sc < 110f && cool != null && cool >= 175f) {
            warmDone = true
            warmups += (driveSec / 60f) to (startIntake ?: sc)
        }

        val trim = if (o.stft != null || o.ltft != null) ((o.stft ?: 0f) + (o.ltft ?: 0f)).toDouble() else null
        val load = o.load ?: 0
        if (warm && trim != null) {
            if (spd in 45f..80f && rpm > 1200 && load in 15..75) cruiseTrim.add(trim)
            if (spd < 1f && rpm in 500..1100) idleTrim.add(trim)
        }
        if (warm && spd < 1f && rpm in 500..1300) {
            if (idleBase.n < 600) idleBase.add(rpm.toDouble())
            else {
                idleRecent = if (idleRecentN == 0L) rpm.toDouble() else idleRecent + (rpm - idleRecent) * 0.004
                idleRecentN++
            }
        }
        o.voltage?.let { v ->
            if (rpm > 600 && v > 9f) {
                runVolt.add(v.toDouble())
                if (v < 13.0f) lowVoltSec++
                if (v > 15.0f) highVoltSec++
            }
        }
        if (cool != null) {
            if (cool >= 225f) hotSec++
            if (driveSec > 15 * 60 && spd > 35f) warmCruiseCool.add(cool.toDouble())
        }
        // fuel use by cruising speed, from mass air flow (stoichiometric petrol)
        val maf = o.maf
        if (maf != null && maf > 0.5f && warm) {
            val b = BANDS.indexOfFirst { spd >= it && spd < it + 10 }
            if (b >= 0) {
                bandMiles[b] += spd / 3600.0 * dt
                bandGal[b] += maf / 14.7 / 2798.7 * dt
            }
        }
        if (dirty > 60f) save()
    }

    @Synchronized
    fun endDrive() {
        load()
        save()
        driveSec = 0f; startCool = null; startIntake = null; warmDone = false
    }

    /** This car's learned normal warm idle, once known. */
    val idleBaseline: Int? get() { synchronized(this) { load(); return if (idleBase.n >= 600) idleBase.avg.roundToInt() else null } }

    /** Minutes of engine data learned so far. */
    val learnedMinutes: Int get() { synchronized(this) { load(); return (engineSec / 60).toInt() } }

    /** What ClearDash has learned, worth-a-look first. */
    @Synchronized
    fun tips(): List<Tip> {
        load()
        val out = ArrayList<Tip>()
        val ct = cruiseTrim.takeIf { it.n >= 300 }?.avg
        val idle = idleTrim.takeIf { a -> a.n >= 180 }?.avg
        when {
            ct != null && ct > 8 -> out += Tip("trim", "Running lean at highway speed",
                "Fuel trims average %+.0f%% while cruising — the computer is adding fuel to make up for extra air. Usual suspects: a dirty mass-airflow sensor (a \$10 can of MAF cleaner), a loose air-filter box, or a small intake leak.".format(ct), 2)
            idle != null && idle > 8 && (ct == null || ct < 5) -> out += Tip("trim", "Lean at idle only",
                "Fuel trims are %+.0f%% at idle but normal at speed. That pattern usually means a small vacuum leak — a cracked hose or the PCV valve.".format(idle), 2)
            ct != null && ct < -8 -> out += Tip("trim", "Running rich at highway speed",
                "Fuel trims average %+.0f%% while cruising — the computer is pulling fuel out. Worth checking the air filter and the upstream oxygen sensor.".format(ct), 2)
            ct != null -> out += Tip("trim", "Fuel mixture is healthy",
                "Fuel trims average %+.1f%% at cruise%s. Within ±5%% is where a healthy engine sits.".format(ct, idle?.let { v -> ", %+.1f%% at idle".format(v) } ?: ""), 0)
        }

        if (idleBase.n >= 600 && idleRecentN >= 300) {
            val base = idleBase.avg; val drift = idleRecent - base
            out += if (drift > 80) Tip("idle", "Idle is creeping up",
                "Warm idle is now about ${idleRecent.roundToInt()} rpm, ${drift.roundToInt()} higher than the ${base.roundToInt()} rpm ClearDash first learned. Carbon on the throttle body is a common cause; a throttle-body cleaning usually brings it back.", 1)
            else if (drift < -80) Tip("idle", "Idle is lower than it used to be",
                "Warm idle is about ${idleRecent.roundToInt()} rpm, down from ${base.roundToInt()}. If it ever feels rough at lights, a dirty throttle body or weak engine mounts are the usual reasons.", 1)
            else Tip("idle", "Idle is steady", "Warm idle holds at about ${idleRecent.roundToInt()} rpm, right where it was when ClearDash first learned it (${base.roundToInt()}).", 0)
        } else if (idleBase.n >= 600) {
            out += Tip("idle", "Idle baseline learned", "This engine's normal warm idle is about ${idleBase.avg.roundToInt()} rpm. ClearDash will flag it if that drifts.", 0)
        }

        if (runVolt.n >= 300) {
            val v = runVolt.avg
            val lowShare = lowVoltSec.toDouble() / runVolt.n
            out += when {
                v < 13.3 || lowShare > 0.15 -> Tip("volt", "Charging voltage is on the low side",
                    "The engine-running voltage averages %.1f V (13.5–14.7 V is normal). Mazda's i-ELOOP system lets it dip on purpose, but if this persists have the battery and alternator tested — it's free at most parts stores.".format(v), 2)
                highVoltSec > 60 -> Tip("volt", "Charging voltage spikes high",
                    "Voltage has gone over 15 V for ${highVoltSec / 60} min in total. Over-charging cooks batteries; worth a charging-system test.", 2)
                else -> Tip("volt", "Charging system is healthy", "Averaging %.1f V with the engine running.".format(v), 0)
            }
        }

        if (hotSec > 30) out += Tip("hot", "Engine has run hot",
            "Coolant has reached 225 °F or more for ${(hotSec / 60).coerceAtLeast(1)} min in total. Check the coolant level (engine cold) and that the radiator fan comes on.", 2)
        val wc = warmCruiseCool.takeIf { it.n >= 300 }?.avg
        if (wc != null && wc < 180) out += Tip("cool", "Engine is running cool",
            "Once warm, coolant only averages ${wc.roundToInt()} °F at cruise (about 195–210 °F is normal). A thermostat sticking open does this — it costs fuel economy and heater warmth.", 2)
        if (warmups.size >= 3) {
            val cold = warmups.filter { it.second < 40f }
            val mild = warmups.filter { it.second >= 40f }
            val parts = ArrayList<String>()
            if (mild.isNotEmpty()) parts += "%.0f min in mild weather".format(mild.map { it.first }.average())
            if (cold.isNotEmpty()) parts += "%.0f min below 40 °F".format(cold.map { it.first }.average())
            val slow = mild.isNotEmpty() && mild.map { it.first }.average() > 15
            out += Tip("warm", if (slow) "Warm-ups are slow" else "Warm-up profile",
                "From a cold start this engine reaches operating temperature in about ${parts.joinToString(", ")}." +
                    if (slow) " That's long — another sign of a lazy thermostat." else " Easy on the throttle until then.", if (slow) 1 else 0)
        }

        val bands = BANDS.indices.filter { bandMiles[it] >= 3.0 && bandGal[it] > 0.01 }
        if (bands.size >= 2) {
            val mpg = bands.associateWith { bandMiles[it] / bandGal[it] }
            val best = mpg.maxBy { it.value }
            val worst = mpg.minBy { it.value }
            val cost = ((best.value / worst.value - 1) * 100).roundToInt()
            out += Tip("mpg", "Most efficient cruise: ${BANDS[best.key]}–${BANDS[best.key] + 10} mph",
                "Measured %.0f mpg there. At ${BANDS[worst.key]}–${BANDS[worst.key] + 10} mph this car gets %.0f mpg — about $cost%% more fuel for the same distance."
                    .format(best.value, worst.value), 1)
        }

        if (out.isEmpty()) {
            out += Tip("learning", "Still learning this car",
                "${engineSec / 60} min of engine data so far. Notes appear once ClearDash has seen enough idling, cruising and cold starts — usually a few days of normal driving.", 1)
        }
        return out.sortedByDescending { it.level }
    }

    /** Wipes everything learned (e.g. after a repair, to set a new baseline). */
    @Synchronized
    fun reset() {
        loaded = true
        cruiseTrim = Acc(); idleTrim = Acc(); idleBase = Acc(); idleRecent = 0.0; idleRecentN = 0
        runVolt = Acc(); lowVoltSec = 0; highVoltSec = 0; warmCruiseCool = Acc(); hotSec = 0
        warmups.clear(); bandMiles.fill(0.0); bandGal.fill(0.0); engineSec = 0
        save()
    }

}
