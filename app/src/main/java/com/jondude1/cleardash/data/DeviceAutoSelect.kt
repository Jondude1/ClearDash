package com.jondude1.cleardash.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Device identification heuristics — the CHECK half of the auto-selector.
 * Scores only rank candidates; nothing is saved until verification passes.
 */
object DeviceClassifier {

    /**
     * How likely a Bluetooth device is Jon's OBD adapter.
     * VePEAK BLE units advertise as "VEEPEAK" (newer batches "VEEPEAK-<batch>")
     * and expose the ELM serial service 0000fff0 (notify fff1 / write fff2).
     */
    fun obdScore(name: String?, serviceUuids: List<String>, ble: Boolean, paired: Boolean): Int {
        val n = name?.uppercase().orEmpty()
        var score = 0
        when {
            n.contains("VEEPEAK") -> score += 100
            n.contains("OBDCHECK") -> score += 95
        }
        if (serviceUuids.any { it.lowercase().startsWith("0000fff0") }) score += 40
        if (serviceUuids.any { it.lowercase().startsWith("0000ffe0") }) score += 35
        if (score == 0 && listOf("OBD", "VLINK", "V-LINK", "ELM").any { n.contains(it) }) score += 50
        if (paired) score += 5
        return score
    }

    const val OBD_MIN_SCORE = 50

    /** How likely a scanned device is one of the tire caps. A decoded reading is the strong signal. */
    fun tpmsScore(s: SeenSensor): Int {
        var score = 0
        if (s.decoded != null) score += 60
        val n = s.name?.uppercase().orEmpty()
        if (n.startsWith("TPMS") || n.contains("TPMS") || n.contains("TIRE") || n.contains("TYRE")) score += 25
        // Signal strength: -50 dBm (right next to the car) earns the full bonus, -100 earns none.
        score += ((s.avgRssi + 100f) / 5f).toInt().coerceIn(0, 10)
        // Persistence: caps that keep broadcasting are real; one-off blips are not.
        score += (s.seenCount / 2).coerceAtMost(10)
        return score
    }
}

data class AutoSelectState(
    val running: Boolean = false,
    val phase: String = "",
    val obdFound: String? = null,
    val obdVerified: Boolean = false,
    val tpmsFound: Int = 0,
    val tpmsVerified: Int = 0,
    val message: String = "",
    val lastRun: Long = 0,
)

/**
 * CHECK → SELECT → VERIFY for the two Bluetooth device families ClearDash needs:
 *
 *  • Tire caps are verified passively — a candidate must produce at least 3 decoded
 *    readings during the scan, all at a plausible car-tire pressure, with a spread of
 *    at most 2 PSI (a mounted cap is stable; random decode noise is not). No two
 *    selected caps may claim the same embedded cap number.
 *  • The VePEAK is verified actively — ClearDash connects and runs the ELM327
 *    handshake (ATZ/ATI must answer as an ELM, ATRV must return a sane voltage,
 *    0100 is probed for engine data). Only a verified adapter is saved, and a
 *    hand-picked adapter is never overwritten.
 */
object AutoSelector {
    private const val TAG = "AutoSelect"

    private val _state = MutableStateFlow(AutoSelectState())
    val state: StateFlow<AutoSelectState> = _state

    fun needsSetup(): Boolean =
        Prefs.obdAddress == null || Wheel.entries.count { Prefs.tireId(it) != null } < 4

    @SuppressLint("MissingPermission")
    suspend fun scanAndSelect(context: Context, scanMs: Long = 45_000) {
        if (_state.value.running) return
        val app = context.applicationContext
        _state.update { it.copy(running = true, phase = "Scanning…", message = "", obdVerified = false) }
        val scanStart = System.currentTimeMillis()
        val notes = mutableListOf<String>()
        var discovery: TpmsSource? = null
        try {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                _state.update { it.copy(running = false, phase = "Bluetooth is off", message = "Turn Bluetooth on, then run the check again.") }
                return
            }

            // ── CHECK: bonded snapshot + unfiltered BLE discovery ──
            val bonded: List<BluetoothDevice> = try { adapter.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }
            val bondedAddrs = bonded.map { it.address }.toSet()
            discovery = TpmsSource(app).also { it.startDiscovery() }
            delay(scanMs)
            discovery?.stopDiscovery(); discovery = null

            val now = System.currentTimeMillis()
            val seen = TpmsRegistry.seen.value

            // ── SELECT + VERIFY: tire caps (passive stability check) ──
            _state.update { it.copy(phase = "Verifying tire caps…") }
            val candidates = seen.values.filter {
                it.decoded != null && it.seenCount >= 3 && now - it.lastSeen <= 15_000
            }
            _state.update { it.copy(tpmsFound = candidates.size) }

            data class CapVerdict(val sensor: SeenSensor, val spread: Float, val score: Int)
            val verdicts = mutableListOf<CapVerdict>()
            for (c in candidates) {
                val readings = TpmsRegistry.readingsFor(c.address).filter { it.seenAt >= scanStart }
                if (readings.size < 3) continue
                if (readings.any { it.psi !in 5f..80f }) continue
                if (readings.any { r -> r.tempF != null && (r.tempF < -40f || r.tempF > 130f) }) continue
                val spread = (readings.maxOf { it.psi } - readings.minOf { it.psi })
                if (spread > 2.0f) continue
                verdicts += CapVerdict(c, spread, DeviceClassifier.tpmsScore(c))
            }
            // Rank by score, most stable first on ties; one cap per embedded cap number.
            val usedIndexes = mutableSetOf<Int>()
            val selected = mutableListOf<SeenSensor>()
            for (v in verdicts.sortedWith(compareByDescending<CapVerdict> { it.score }.thenBy { it.spread })) {
                if (selected.size >= 4) break
                val idx = v.sensor.decoded?.sensorIndex
                if (idx != null && !usedIndexes.add(idx)) continue // duplicate cap number: stronger one already kept
                selected += v.sensor
            }
            if (selected.isNotEmpty()) {
                val merged = (selected.map { it.address } + Prefs.tpmsAutoSet).distinct().take(4)
                // Keep this run's verified caps first; top up from the previous verified set.
                Prefs.tpmsAutoSet = if (selected.size >= Prefs.tpmsAutoSet.size) selected.map { it.address } else merged
            }
            _state.update { it.copy(tpmsVerified = selected.size) }
            notes += when {
                selected.size >= 4 -> "All 4 tire caps verified ✓"
                selected.isEmpty() -> "No tire caps passed verification — screw the caps on to wake them, stand by the car and re-run."
                else -> "Found ${selected.size} of 4 verified caps — the rest didn't report a stable pressure during the scan."
            }

            // ── SELECT + VERIFY: VePEAK OBD adapter (active ELM handshake) ──
            _state.update { it.copy(phase = "Verifying VePEAK…") }
            data class ObdCandidate(val device: BluetoothDevice, val name: String, val ble: Boolean, val score: Int)
            val obdCandidates = mutableMapOf<String, ObdCandidate>()
            for (s in seen.values) {
                val score = DeviceClassifier.obdScore(s.name, s.serviceUuids, ble = true, paired = s.address in bondedAddrs)
                if (score >= DeviceClassifier.OBD_MIN_SCORE) {
                    val dev = try { adapter.getRemoteDevice(s.address) } catch (_: Exception) { null }
                    if (dev != null) obdCandidates[s.address] = ObdCandidate(dev, s.name ?: s.address, true, score)
                }
            }
            for (d in bonded) {
                if (d.address in obdCandidates) continue
                val name = try { d.name } catch (_: SecurityException) { null } ?: continue
                val type = try { d.type } catch (_: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
                val ble = type != BluetoothDevice.DEVICE_TYPE_CLASSIC
                val score = DeviceClassifier.obdScore(name, emptyList(), ble, paired = true)
                if (score >= DeviceClassifier.OBD_MIN_SCORE) {
                    obdCandidates[d.address] = ObdCandidate(d, name, ble, score)
                }
            }
            val ranked = obdCandidates.values.sortedByDescending { it.score }
            val top = ranked.firstOrNull()
            if (top != null) _state.update { it.copy(obdFound = top.name) }

            val manualPick = Prefs.obdAddress != null && !Prefs.obdAutoSelected
            if (top == null) {
                notes += "No VePEAK-like adapter was heard — plug it in and turn the car on, then re-run."
            } else if (manualPick) {
                notes += "Found ${top.name}, but you picked your adapter by hand — left unchanged."
            } else {
                var verified: ObdCandidate? = null
                var engineData = false
                var attempts = 0
                for (cand in ranked) {
                    if (attempts >= 3 || verified != null) break
                    attempts++
                    _state.update { it.copy(phase = "Verifying ${cand.name}…") }
                    val link: ElmLink = if (cand.ble) BleLink(app, cand.device) else SppLink(cand.device)
                    try {
                        val result = withTimeoutOrNull(30_000) {
                            link.connect()
                            val atz = link.send("ATZ", 5_000)
                            if (!atz.contains("ELM", ignoreCase = true)) return@withTimeoutOrNull null
                            delay(300)
                            val ati = link.send("ATI", 3_000)
                            val atrv = link.send("ATRV", 3_000)
                            val voltage = Regex("""(\d{1,2}(?:\.\d+)?)\s*[Vv]""").find(atrv)?.groupValues?.get(1)?.toFloatOrNull()
                                ?: Regex("""(\d{1,2}\.\d+)""").find(atrv)?.value?.toFloatOrNull()
                            if (voltage == null || voltage !in 5f..20f) return@withTimeoutOrNull null
                            if (!ati.contains("ELM", ignoreCase = true)) Log.d(TAG, "ATI unexpected: $ati")
                            val probe = link.send("0100", 8_000)
                            val hasEngineData = probe.replace(Regex("[^0-9A-Fa-f]"), "").contains("4100")
                            Pair(true, hasEngineData)
                        }
                        if (result != null) {
                            verified = cand
                            engineData = result.second
                        } else {
                            Log.d(TAG, "Verification failed for ${cand.name}; trying next candidate")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Verification error for ${cand.name}: ${e.message}")
                    } finally {
                        link.close()
                    }
                }
                if (verified != null) {
                    Prefs.obdAddress = verified.device.address
                    Prefs.obdName = verified.name
                    Prefs.obdBle = verified.ble
                    Prefs.obdAutoSelected = true
                    _state.update { it.copy(obdFound = verified.name, obdVerified = true) }
                    notes += if (engineData) {
                        "${verified.name} verified ✓ — it answered the ELM327 check and reported engine data."
                    } else {
                        "${verified.name} verified ✓ — it answered the ELM327 check; engine data will confirm on next start (car may be off)."
                    }
                } else if (ranked.isNotEmpty()) {
                    notes += "Heard ${ranked.size} adapter candidate(s) but none passed the ELM327 check — is the car on?"
                }
            }

            Prefs.autoSelectLastRun = System.currentTimeMillis()
            _state.update {
                it.copy(
                    running = false,
                    phase = "Done",
                    message = notes.joinToString(" "),
                    lastRun = Prefs.autoSelectLastRun,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "auto-select failed: ${e.message}")
            _state.update { it.copy(running = false, phase = "Check failed", message = e.message ?: "Unknown error — try again.") }
        } finally {
            try { discovery?.stopDiscovery() } catch (_: Exception) {}
            if (_state.value.running) _state.update { it.copy(running = false) }
        }
    }
}
