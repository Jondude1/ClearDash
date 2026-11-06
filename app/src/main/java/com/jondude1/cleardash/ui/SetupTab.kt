package com.jondude1.cleardash.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jondude1.cleardash.data.DataService
import com.jondude1.cleardash.BuildConfig
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.data.AutoSelector
import com.jondude1.cleardash.data.Prefs
import com.jondude1.cleardash.data.SeenSensor
import com.jondude1.cleardash.data.TpmsRegistry
import com.jondude1.cleardash.data.TpmsSource
import com.jondude1.cleardash.data.Wheel
import com.jondude1.cleardash.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private fun basePermissions(): Array<String> {
    val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
        p += Manifest.permission.BLUETOOTH_SCAN
        p += Manifest.permission.BLUETOOTH_CONNECT
    }
    if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
    return p.toTypedArray()
}

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun SetupTab() {
    val ctx = LocalContext.current
    val prefsVersion by Prefs.version.collectAsState()
    var recheck by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        recheck++
        onPauseOrDispose { }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        PermissionsCard(recheck) { recheck++ }
        AndroidAutoCard()
        KnobCard()
        VehicleCard(prefsVersion)
        DevicesAutoCard(prefsVersion)
        VerifyAllCard(prefsVersion)
        ObdCard(prefsVersion)
        TiresCard(prefsVersion)
        SettingsBackupCard(prefsVersion)
        UpdatesCard()
    }
}

@Composable
private fun PermissionsCard(recheck: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val state by DataHub.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(recheck) { refresh++ }

    val allBase = remember(refresh, recheck) { basePermissions().all { granted(ctx, it) } }
    val bgLoc = remember(refresh, recheck) { granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val listener = remember(refresh, recheck, state.nav) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    SectionCard("Get started", "Grant access once, then everything runs on its own when your phone connects to the car.") {
        StatusLine("Location, Bluetooth, alerts", if (allBase) "Allowed" else "Needed", allBase)
        StatusLine("Location with screen off", if (bgLoc) "Allowed" else "Recommended", if (bgLoc) true else null)
        StatusLine("Turn info & now playing", if (listener) "Allowed" else "Needed", listener)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!allBase) Pill("Allow access") { launcher.launch(basePermissions()) }
            if (allBase && !bgLoc) Pill("Allow all the time") { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            if (!listener) Pill("Turn info") {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        if (allBase && !bgLoc) Hint("On the next screen pick \"Allow all the time\" so speed and limits keep working with the phone screen off.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep live data running", color = C.text, fontSize = 15.sp)
                Hint("Starts automatically when Android Auto opens ClearDash.")
            }
            var on by remember { mutableStateOf(DataService.active) }
            Switch(checked = on, onCheckedChange = {
                on = it
                if (it) DataHub.startService(ctx) else DataHub.stopService(ctx)
            })
        }
    }
}

@Composable
private fun AndroidAutoCard() {
    SectionCard("Android Auto", "ClearDash is a Car App Library app — Android Auto only shows those when they were installed from a trusted source.") {
        val steps = listOf(
            "Install the ClearDash APK with KingInstaller (free) — it records Google Play as the installer, which is what Android Auto checks. A Play test-track install works too.",
            "Open Android Auto → Settings → \"Customize launcher\" and tick ClearDash.",
            "Connect to the car and pick ClearDash in the Android Auto app launcher — Android Auto remembers it.",
        )
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}", color = C.cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                Text(s, color = C.text, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
        Hint("The \"Unknown sources\" developer toggle alone does not make Car App Library apps appear — the trusted-installer step above is the one that matters.")
        Hint("Google Maps keeps navigating in the background while ClearDash is on screen — its next turn appears on the dash.")
    }
}

@Composable
private fun KnobCard() {
    SectionCard("Commander knob", "Mazda locks the touchscreen while you drive, so everything works from the knob.") {
        val tips = listOf(
            "Nudge" to "Move between the side buttons — Drive, Gauges, Tires — and the media buttons.",
            "Press" to "Select the highlighted button.",
            "Turn" to "Select the ✥ move button, then turn the knob to flip between Drive, Gauges and Tires.",
            "Back" to "The Back button by the knob returns to the previous screen.",
        )
        tips.forEach { (k, v) ->
            Row {
                Text(k, color = C.cyan, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.width(56.dp))
                Text(v, color = C.text, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
        Hint("Parked, you can also tap the dashboard to flip views.")
    }
}

@Composable
private fun VehicleCard(prefsVersion: Int) {
    var name by remember { mutableStateOf(Prefs.carName) }
    var target by remember { mutableFloatStateOf(Prefs.targetPsi.toFloat()) }
    SectionCard("Vehicle", "Tuned for the 2018 Mazda CX-5") {
        OutlinedTextField(
            value = name, onValueChange = { name = it; Prefs.carName = it }, singleLine = true,
            label = { Text("Name shown on the dash") }, modifier = Modifier.fillMaxWidth(),
        )
        Text("Door-sticker tire pressure: ${target.roundToInt()} PSI", color = C.text, fontSize = 14.sp)
        Slider(value = target, onValueChange = { target = it }, onValueChangeFinished = { Prefs.targetPsi = target.roundToInt() },
            valueRange = 30f..44f, steps = 13)
        Text("Fuel tank", color = C.text, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(14.8f to "14.8 gal · FWD", 15.3f to "15.3 gal · AWD").forEach { (gal, label) ->
                val on = Prefs.tankGal == gal
                Pill(label, if (on) C.cyan else C.muted) { Prefs.tankGal = gal }
            }
        }
        Hint("Used for the gallons-left estimate. Check the sticker on your driver's door jamb for tire pressure.")
    }
}


// ───────────────────────── Devices auto-select ─────────────────────────

@Composable
private fun DevicesAutoCard(prefsVersion: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auto by AutoSelector.state.collectAsState()
    var wizard by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") prefsVersion // recompose when any pref changes

    SectionCard("Devices", "ClearDash finds and checks your VePEAK and tire caps itself") {
        val obdLabel = when {
            auto.obdFound != null -> auto.obdFound!! + if (auto.obdVerified) " · verified ✓" else ""
            Prefs.obdAddress != null -> (Prefs.obdName ?: "Adapter saved") + if (Prefs.obdAutoSelected) " · verified ✓" else ""
            auto.running -> "Searching…"
            else -> "Not found yet"
        }
        StatusLine("VePEAK OBD2", obdLabel, if (auto.obdVerified || Prefs.obdAddress != null) true else null)
        val capsVerified = if (auto.lastRun > 0) auto.tpmsVerified else Prefs.tpmsAutoSet.size
        StatusLine("Tire caps", "$capsVerified of 4 caps verified", if (capsVerified >= 4) true else null)
        if (auto.running && auto.phase.isNotBlank()) Hint(auto.phase)
        if (auto.message.isNotBlank()) Hint(auto.message)
        val unassigned = Prefs.tpmsAutoSet.filter { Prefs.wheelFor(it) == null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (auto.running) "Checking…" else if (Prefs.obdAddress == null && Prefs.tpmsAutoSet.isEmpty()) "Find my devices" else "Re-run check") {
                if (!auto.running) scope.launch { AutoSelector.scanAndSelect(ctx, 60_000) }
            }
            if (unassigned.isNotEmpty() && !auto.running) Pill("Assign wheels") { wizard = true }
        }
        Hint("How verification works: each cap must report a stable, plausible pressure several times during the scan, and the VePEAK must pass a live ELM327 check (ATZ / voltage / engine-data probe) before anything is saved. Nothing is guessed.")
    }
    if (wizard) WheelAssignWizard { wizard = false }
}

/**
 * Assigns the verified caps to wheels by proof-of-change: for each wheel, the user lets a
 * little air out (pressure drops ≥ 1 PSI) or wakes a sleeping cap (unscrew / re-screw) —
 * the sensor that changes is the one on that wheel, so it is assigned automatically.
 */
@Composable
private fun WheelAssignWizard(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val seen by TpmsRegistry.seen.collectAsState()
    val prefsVersion by Prefs.version.collectAsState()
    @Suppress("UNUSED_EXPRESSION") prefsVersion
    var tick by remember { mutableIntStateOf(0) }
    val skipped = remember { mutableStateListOf<Wheel>() }
    var stepWheel by remember { mutableStateOf(Wheel.entries.firstOrNull { Prefs.tireId(it) == null }) }

    fun liveCandidates(): List<SeenSensor> {
        val m = TpmsRegistry.seen.value
        val auto = Prefs.tpmsAutoSet
        val pool = if (auto.isNotEmpty()) m.values.filter { it.address in auto }
        else m.values.filter { it.decoded != null }
        return pool.filter { Prefs.wheelFor(it.address) == null }
            .sortedByDescending { it.rssi }
    }

    DisposableEffect(Unit) {
        val src = TpmsSource(ctx)
        src.startDiscovery()
        onDispose { src.stopDiscovery() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(500); tick++ }
    }

    // Verify-by-change watcher for the current wheel.
    LaunchedEffect(stepWheel) {
        val w = stepWheel ?: return@LaunchedEffect
        val start = System.currentTimeMillis()
        val initial = liveCandidates()
        val baseline = initial.mapNotNull { s -> s.decoded?.let { s.address to it.psi } }.toMap()
        val silent = mutableSetOf<String>()
        initial.filter { start - it.lastSeen > 15_000 }.forEach { silent += it.address }
        Prefs.tpmsAutoSet.filter { Prefs.wheelFor(it) == null && TpmsRegistry.seen.value[it] == null }
            .forEach { silent += it }
        while (stepWheel == w) {
            delay(500)
            var hit: String? = null
            for (s in liveCandidates()) {
                val d = s.decoded ?: continue
                if (s.address in silent && s.lastSeen >= start) { hit = s.address; break }
                val base = baseline[s.address]
                if (base != null && base - d.psi >= 1.0f) { hit = s.address; break }
            }
            if (hit != null) {
                Prefs.setTireId(w, hit)
                stepWheel = Wheel.entries.firstOrNull { it != w && Prefs.tireId(it) == null && it !in skipped }
            }
        }
    }

    val assignedCount = Wheel.entries.count { Prefs.tireId(it) != null }
    val w = stepWheel
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.card,
        title = { Text("Assign wheels", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Suppress("UNUSED_EXPRESSION") tick // live refresh
                if (w == null) {
                    Text(
                        if (assignedCount >= 4) "All 4 wheels verified ✓ — each cap proved which tire it's on by changing."
                        else "$assignedCount of 4 wheels assigned. You can finish the rest any time from the Devices card.",
                        color = C.text, fontSize = 15.sp,
                    )
                } else {
                    Text("Wheel ${assignedCount + skipped.size + 1} of 4: ${w.label}", color = C.cyan, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Let a little air out of the ${w.label.lowercase()} tire (about 2 PSI), or unscrew and re-screw its cap. " +
                            "The sensor whose reading changes gets assigned to this wheel automatically — that's the proof it's really on that tire.",
                        color = C.text, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                    val cands = liveCandidates()
                    if (cands.isEmpty()) {
                        Hint("Listening for your verified caps… stand next to the car.")
                    } else {
                        Hint("Or tap the right one if you already know it:")
                        cands.forEach { s ->
                            val d = s.decoded
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(C.bg)
                                    .clickable {
                                        Prefs.setTireId(w, s.address)
                                        stepWheel = Wheel.entries.firstOrNull { it != w && Prefs.tireId(it) == null && it !in skipped }
                                    }
                                    .padding(10.dp)
                            ) {
                                Text(
                                    (s.name ?: "Tire cap") + (d?.sensorIndex?.let { " · cap #$it" } ?: ""),
                                    color = C.text, fontSize = 14.sp,
                                )
                                Text(
                                    if (d != null) "${"%.1f".format(d.psi)} PSI" + (d.tempF?.let { " · ${it.roundToInt()}°F" } ?: "") + " · ${s.address}"
                                    else "No reading yet · ${s.address}",
                                    color = C.muted, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(if (w == null) "Done" else "Close", color = C.cyan) } },
        dismissButton = {
            if (w != null) TextButton(onClick = {
                skipped += w
                stepWheel = Wheel.entries.firstOrNull { Prefs.tireId(it) == null && it !in skipped }
            }) { Text("Skip this wheel", color = C.muted) }
        },
    )
}

// ───────────────────────── OBD ─────────────────────────

@Composable
private fun ObdCard(prefsVersion: Int) {
    val state by DataHub.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    SectionCard("Engine data", "Your Veepeak OBD2 adapter") {
        StatusLine("Adapter", Prefs.obdName ?: "Not chosen", Prefs.obdAddress != null)
        if (Prefs.obdAddress != null && Prefs.obdAutoSelected) Hint("Auto-found and verified by ClearDash (ELM327 check passed).")
        StatusLine("Status", state.obd.status, state.obd.connected)
        if (state.obd.connected) {
            val o = state.obd
            Hint(listOfNotNull(
                o.rpm?.let { "%,d rpm".format(it) },
                o.coolantF?.let { "coolant ${it.roundToInt()}°F" },
                o.voltage?.let { "%.1f V".format(it) },
            ).joinToString(" · "))
        }
        // Check-engine fault codes (read-only — ClearDash never clears codes)
        if (state.obd.dtcs.isNotEmpty()) {
            Text("Fault codes", color = C.text, fontSize = 15.sp)
            state.obd.dtcs.forEach { d ->
                Column {
                    Text(d.code + if (d.pending) " (pending)" else "", color = if (d.pending) C.amber else C.red, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(d.description, color = C.text, fontSize = 13.sp)
                }
            }
        } else if (state.obd.connected) {
            Hint("No fault codes")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (Prefs.obdAddress == null) "Choose adapter" else "Change adapter") { picking = true }
            if (Prefs.obdAddress != null) Pill("Forget", C.muted) {
                Prefs.obdAddress = null; Prefs.obdName = null; Prefs.obdAutoSelected = false
            }
        }
        Hint("Classic Bluetooth Veepeaks must be paired in Android's Bluetooth settings first (PIN is usually 1234). BLE models show up in the scan.")
    }
    if (picking) ObdPicker { picking = false }
}

private data class BtChoice(val address: String, val name: String, val ble: Boolean, val paired: Boolean)

@SuppressLint("MissingPermission")
@Composable
private fun ObdPicker(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val choices = remember { mutableStateListOf<BtChoice>() }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            adapter?.bondedDevices?.forEach { d ->
                choices += BtChoice(d.address, d.name ?: d.address, d.type == BluetoothDevice.DEVICE_TYPE_LE, true)
            }
        } catch (_: SecurityException) {
        }
    }

    val cb = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try { result.device.name } catch (_: SecurityException) { null } ?: result.scanRecord?.deviceName ?: return
                if (choices.none { it.address == result.device.address }) {
                    choices += BtChoice(result.device.address, name, true, false)
                }
            }
        }
    }
    DisposableEffect(scanning) {
        if (scanning) try { adapter?.bluetoothLeScanner?.startScan(cb) } catch (_: Exception) {}
        onDispose { try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {} }
    }

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.card,
        title = { Text("Choose your OBD adapter", color = C.text) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                val sorted = choices.sortedByDescending { looksLikeObd(it.name) }
                items(sorted, key = { it.address }) { c ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                Prefs.obdAddress = c.address
                                Prefs.obdName = c.name
                                Prefs.obdBle = c.ble
                                Prefs.obdAutoSelected = false
                                onDone()
                            }
                            .padding(10.dp)
                    ) {
                        Text(c.name, color = if (looksLikeObd(c.name)) C.cyan else C.text, fontSize = 15.sp)
                        Text("${if (c.ble) "Bluetooth LE" else "Classic Bluetooth"} · ${if (c.paired) "paired" else "nearby"}",
                            color = C.muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scanning = !scanning }) { Text(if (scanning) "Stop scan" else "Scan for BLE adapters", color = C.cyan) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Close", color = C.muted) } },
    )
}

private fun looksLikeObd(n: String): Boolean {
    val s = n.uppercase()
    return listOf("OBD", "VEEPEAK", "VLINK", "ELM", "V-LINK", "IOS-VLINK", "ANDROID-VLINK").any { s.contains(it) }
}

// ───────────────────────── Tires ─────────────────────────

@Composable
private fun TiresCard(prefsVersion: Int) {
    val state by DataHub.state.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var rotateChoice by remember { mutableStateOf<String?>(null) }
    var relearn by remember { mutableStateOf(false) }
    var low by remember { mutableFloatStateOf(Prefs.tireLowPsi.toFloat()) }
    var high by remember { mutableFloatStateOf(Prefs.tireHighPsi.toFloat()) }

    SectionCard("Tire pressure", "Bluetooth valve-cap sensors") {
        Wheel.entries.forEach { w ->
            val id = Prefs.tireId(w)
            val t = state.tires[w]
            val value = when {
                id == null -> "Not paired"
                t == null -> "Waiting…"
                else -> "${t.psi.roundToInt()} PSI" + (t.tempF?.let { " · ${it.roundToInt()}°F" } ?: "") +
                    (t.sensorIndex?.let { " · cap #$it" } ?: "")
            }
            StatusLine(w.label, value, if (id == null) null else t != null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Find sensors (manual)") { scanning = true }
        }
        // Tire rotation: the caps move with the tires, so the assignments must follow.
        val allAssigned = Wheel.entries.all { Prefs.tireId(it) != null }
        if (allAssigned || Wheel.entries.any { Prefs.tireId(it) != null } || Prefs.tpmsAutoSet.isNotEmpty()) {
            Text("I rotated my tires", color = C.text, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allAssigned) {
                    Pill("Front ↔ back") { rotateChoice = "frontback" }
                    Pill("Cross pattern") { rotateChoice = "cross" }
                }
                Pill("Re-learn wheels", C.muted) {
                    Wheel.entries.forEach { w -> Prefs.setTireId(w, null) }
                    relearn = true
                }
            }
            if (!allAssigned) Hint("The one-tap rotation patterns appear once all 4 wheels are assigned.")
        }
        Text("Warn below ${low.roundToInt()} PSI", color = C.text, fontSize = 14.sp)
        Slider(value = low, onValueChange = { low = it }, onValueChangeFinished = { Prefs.tireLowPsi = low.roundToInt() },
            valueRange = 20f..40f, steps = 19)
        Text("Warn above ${high.roundToInt()} PSI", color = C.text, fontSize = 14.sp)
        Slider(value = high, onValueChangeFinished = { Prefs.tireHighPsi = high.roundToInt() }, onValueChange = { high = it },
            valueRange = 36f..60f, steps = 23)
        Hint("Your Mazda's door sticker lists the right pressure (usually 36 PSI). Set the low warning a few PSI under it.")
    }
    if (scanning) TireScanner { scanning = false }
    if (relearn) WheelAssignWizard { relearn = false }
    rotateChoice?.let { choice ->
        val cross = choice == "cross"
        AlertDialog(
            onDismissRequest = { rotateChoice = null },
            containerColor = C.card,
            title = { Text("Apply rotation?", color = C.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (cross) "Cross pattern (right for a front-wheel-drive CX-5): the front caps move straight back, and the rear caps cross over to the opposite front corners."
                        else "Front ↔ back: each front cap swaps with the rear cap on the same side.",
                        color = C.text, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                    Text("The dashboard assignments follow the caps, so pressures stay on the correct corners.", color = C.muted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.remapTires(
                        if (cross) mapOf(Wheel.FL to Wheel.RR, Wheel.FR to Wheel.RL, Wheel.RL to Wheel.FL, Wheel.RR to Wheel.FR)
                        else mapOf(Wheel.FL to Wheel.RL, Wheel.FR to Wheel.RR, Wheel.RL to Wheel.FL, Wheel.RR to Wheel.FR)
                    )
                    DataHub.update { it.copy(tires = emptyMap()) } // readings refill on the caps' next broadcasts
                    rotateChoice = null
                }) { Text("Apply", color = C.cyan) }
            },
            dismissButton = { TextButton(onClick = { rotateChoice = null }) { Text("Cancel", color = C.muted) } },
        )
    }
}

@Composable
private fun TireScanner(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val seen by TpmsRegistry.seen.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<SeenSensor?>(null) }

    DisposableEffect(Unit) {
        val src = TpmsSource(ctx)
        src.startDiscovery()
        onDispose { src.stopDiscovery() }
    }

    val now = System.currentTimeMillis()
    val list = seen.values
        .filter { now - it.lastSeen < 60_000 }
        .filter { showAll || it.decoded != null || isTireName(it.name) || Prefs.wheelFor(it.address) != null }
        .sortedWith(compareByDescending<SeenSensor> { it.decoded != null }.thenByDescending { it.rssi })

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.card,
        title = { Text("Find tire sensors", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Screw the caps on (that wakes them up), then stand next to the car. Let a little air out of one tire to see which sensor changes.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showAll, onCheckedChange = { showAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = C.cyan))
                    Text("Show every Bluetooth device", color = C.muted, fontSize = 13.sp)
                }
                if (list.isEmpty()) Text("Listening…", color = C.muted)
                LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.address }) { s ->
                        val wheel = Prefs.wheelFor(s.address)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(C.bg)
                                .clickable { assigning = s }
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name ?: "Unnamed", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                if (wheel != null) Text(wheel.short, color = C.cyan, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                Text("${s.rssi} dBm", color = C.muted, fontSize = 12.sp)
                            }
                            val d = s.decoded
                            if (d != null) {
                                Text("${"%.1f".format(d.psi)} PSI" + (d.tempF?.let { " · ${it.roundToInt()}°F" } ?: "") +
                                    (d.battery?.let { " · batt $it%" } ?: ""), color = C.green, fontSize = 14.sp)
                            } else {
                                Text("Format not decoded yet — tap to send me its data", color = C.amber, fontSize = 13.sp)
                            }
                            Text(s.address, color = C.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done", color = C.cyan) } },
    )

    assigning?.let { s ->
        AlertDialog(
            onDismissRequest = { assigning = null },
            containerColor = C.card,
            title = { Text(s.name ?: s.address, color = C.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Which tire is this sensor on?", color = C.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Wheel.entries.forEach { w ->
                            Pill(w.short) { Prefs.setTireId(w, s.address); assigning = null }
                        }
                    }
                    if (Prefs.wheelFor(s.address) != null) {
                        Pill("Unpair", C.muted) { Prefs.wheelFor(s.address)?.let { Prefs.setTireId(it, null) }; assigning = null }
                    }
                    Hint("Share the raw data if the pressure isn't showing, so the decoder for this brand can be added.")
                    Pill("Share raw data", C.amber) {
                        val body = "ClearDash ${BuildConfig.VERSION_NAME} sensor log\naddress=${s.address} name=${s.name}\n\n" +
                            TpmsRegistry.logFor(s.address)
                        ctx.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "Share sensor data"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assigning = null }) { Text("Cancel", color = C.muted) } },
        )
    }
}

private fun isTireName(n: String?): Boolean {
    val s = n?.uppercase() ?: return false
    return listOf("TPMS", "TIRE", "TYRE", "BR_", "SENSOR").any { s.contains(it) }
}


// ───────────────────────── Verify all ─────────────────────────

@Composable
private fun VerifyAllCard(prefsVersion: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DataHub.state.collectAsState()
    val auto by AutoSelector.state.collectAsState()
    var recheck by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        recheck++
        onPauseOrDispose { }
    }
    @Suppress("UNUSED_EXPRESSION") prefsVersion

    SectionCard("Verify all", "Each row is checked against live state — a pass means it's proven, not assumed") {
        val permsOk = remember(recheck) { basePermissions().all { granted(ctx, it) } }
        StatusLine("Permissions", if (permsOk) "Location + Bluetooth allowed" else "Location / Bluetooth missing", permsOk)
        val listenerOk = remember(recheck, state.nav) {
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        }
        StatusLine("Turn info", if (listenerOk) "Notification listener on" else "Notification listener off", listenerOk)
        StatusLine("GPS", if (state.gpsOk) "${(state.speedMph ?: 0f).roundToInt()} mph — fix live" else "No fix yet", state.gpsOk)

        val obdPass = Prefs.obdAddress != null && (state.obd.connected || Prefs.obdAutoSelected || auto.obdVerified)
        val obdValue = when {
            Prefs.obdAddress == null -> "No adapter saved"
            state.obd.connected -> "${Prefs.obdName ?: "Adapter"} — connected now"
            obdPass -> "${Prefs.obdName ?: "Adapter"} — ELM-verified ✓"
            else -> "${Prefs.obdName ?: "Adapter"} — saved, not verified yet"
        }
        StatusLine("VePEAK", obdValue, if (Prefs.obdAddress == null) null else obdPass)

        val nowMs = System.currentTimeMillis()
        val assigned = Wheel.entries.count { Prefs.tireId(it) != null }
        val fresh = Wheel.entries.count { w ->
            val tr = state.tires[w]
            tr != null && nowMs - tr.seenAt < 120_000
        }
        val capsPass = assigned == 4 && fresh == 4
        StatusLine(
            "Tire caps",
            "$fresh of 4 wheels reporting fresh" + if (assigned < 4) " ($assigned of 4 assigned)" else "",
            if (assigned == 0) null else capsPass,
        )

        if (auto.running) {
            Hint(auto.phase.ifBlank { "Running full verification…" })
        } else if (auto.lastRun > 0) {
            Hint(
                "Last full check: VePEAK ${if (auto.obdVerified) "verified ✓" else "not verified"} · " +
                    "${auto.tpmsVerified} of 4 caps verified." +
                    if (auto.message.isNotBlank()) " ${auto.message}" else ""
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (auto.running) "Verifying…" else "Run full verification") {
                if (!auto.running) scope.launch { AutoSelector.scanAndSelect(ctx, 60_000) }
            }
        }
        Hint("The full check re-runs the device scan (about a minute, car on, standing by the car) and the rows above update with the result.")
    }
}

// ───────────────────────── Settings backup / restore ─────────────────────────

private fun buildSettingsJson(): String {
    val o = JSONObject()
    Prefs.obdAddress?.let { o.put("obdAddress", it) }
    Prefs.obdName?.let { o.put("obdName", it) }
    o.put("obdBle", Prefs.obdBle)
    o.put("obdAutoSelected", Prefs.obdAutoSelected)
    val tires = JSONObject()
    Wheel.entries.forEach { w -> Prefs.tireId(w)?.let { tires.put(w.name, it) } }
    o.put("tires", tires)
    o.put("tpmsAutoSet", JSONArray(Prefs.tpmsAutoSet))
    o.put("targetPsi", Prefs.targetPsi)
    o.put("tireLowPsi", Prefs.tireLowPsi)
    o.put("tireHighPsi", Prefs.tireHighPsi)
    o.put("tankGal", Prefs.tankGal.toDouble())
    o.put("carName", Prefs.carName)
    return o.toString(2)
}

private val MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

/** Applies an imported settings block defensively; returns a summary or throws with a reason. */
private fun applySettingsJson(textIn: String): String {
    val o = JSONObject(textIn)
    var applied = 0
    if (o.has("obdAddress")) {
        val v = o.optString("obdAddress")
        if (MAC_RE.matches(v)) { Prefs.obdAddress = v; applied++ }
    }
    if (o.has("obdName")) {
        val v = o.optString("obdName")
        if (v.isNotBlank()) { Prefs.obdName = v; applied++ }
    }
    if (o.has("obdBle")) { Prefs.obdBle = o.optBoolean("obdBle"); applied++ }
    if (o.has("obdAutoSelected")) { Prefs.obdAutoSelected = o.optBoolean("obdAutoSelected"); applied++ }
    o.optJSONObject("tires")?.let { tj ->
        Wheel.entries.forEach { w ->
            val v = tj.optString(w.name)
            if (MAC_RE.matches(v)) { Prefs.setTireId(w, v); applied++ }
        }
    }
    o.optJSONArray("tpmsAutoSet")?.let { a ->
        val list = (0 until a.length()).mapNotNull { a.optString(it) }.filter { MAC_RE.matches(it) }
        if (list.isNotEmpty()) { Prefs.tpmsAutoSet = list; applied++ }
    }
    if (o.has("targetPsi")) { val v = o.optInt("targetPsi"); if (v in 20..60) { Prefs.targetPsi = v; applied++ } }
    if (o.has("tireLowPsi")) { val v = o.optInt("tireLowPsi"); if (v in 10..50) { Prefs.tireLowPsi = v; applied++ } }
    if (o.has("tireHighPsi")) { val v = o.optInt("tireHighPsi"); if (v in 20..70) { Prefs.tireHighPsi = v; applied++ } }
    if (o.has("tankGal")) { val v = o.optDouble("tankGal", Double.NaN); if (v in 5.0..30.0) { Prefs.tankGal = v.toFloat(); applied++ } }
    if (o.has("carName")) {
        val v = o.optString("carName").trim()
        if (v.isNotBlank()) { Prefs.carName = v.take(40); applied++ }
    }
    if (applied == 0) throw IllegalArgumentException("no recognisable settings in that text")
    return "Settings restored — $applied value(s) applied ✓"
}

@Composable
private fun SettingsBackupCard(prefsVersion: Int) {
    val ctx = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val exportText = remember(prefsVersion) { buildSettingsJson() }

    SectionCard("Settings backup", "An app reinstall wipes device assignments (the v1.0.6 signing-key change did) — keep a copy of this somewhere safe") {
        OutlinedTextField(
            value = exportText, onValueChange = { }, readOnly = true,
            label = { Text("Your settings — tap to select & copy") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Export settings") {
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportText),
                    "Share ClearDash settings"))
            }
            Pill("Import settings", C.muted) { importText = ""; importing = true }
        }
        msg?.let { Text(it, color = C.text, fontSize = 14.sp) }
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            containerColor = C.card,
            title = { Text("Import settings", color = C.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("Paste an exported settings block. Only values that look right (Bluetooth addresses, sane ranges) are applied — anything else is ignored.")
                    OutlinedTextField(
                        value = importText, onValueChange = { importText = it },
                        label = { Text("Settings JSON") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    msg = try { applySettingsJson(importText) }
                    catch (e: Exception) { "Couldn't import: ${e.message ?: "that text isn't settings JSON"}" }
                    importing = false
                }) { Text("Apply", color = C.cyan) }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel", color = C.muted) } },
        )
    }
}

// ───────────────────────── Updates ─────────────────────────

@Composable
private fun UpdatesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    SectionCard("Updates", "Installed: v${BuildConfig.VERSION_NAME}") {
        val line = when (val s = st) {
            Updater.State.Checking -> "Checking…"
            Updater.State.UpToDate -> "You're on the latest version."
            is Updater.State.Available -> "${s.release.tag} is ready."
            is Updater.State.Downloading -> "Downloading… ${s.percent}%"
            is Updater.State.Error -> s.message
            Updater.State.Idle -> "Updates install from GitHub automatically."
        }
        Hint(line)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Check for updates") { scope.launch { Updater.check() } }
            (st as? Updater.State.Available)?.let { a ->
                Pill("Install ${a.release.tag}", C.green) { scope.launch { Updater.downloadAndInstall(ctx, a.release) } }
            }
        }
        Hint("CX-5 photo by Ghostofakina, Wikimedia Commons, CC BY-SA 4.0 — background removed, plate blurred. ClearDash is not affiliated with Mazda.")
    }
}
