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
import android.location.LocationManager
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jondude1.cleardash.data.DataService
import com.jondude1.cleardash.BuildConfig
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.data.HaEntity
import com.jondude1.cleardash.data.HomeAssistant
import com.jondude1.cleardash.data.Prefs
import com.jondude1.cleardash.data.SeenSensor
import com.jondude1.cleardash.data.TpmsRegistry
import com.jondude1.cleardash.data.TpmsSource
import com.jondude1.cleardash.data.Wheel
import com.jondude1.cleardash.update.Updater
import kotlinx.coroutines.launch
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
        ObdCard(prefsVersion)
        TiresCard(prefsVersion)
        HomeAssistantCard()
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
        StatusLine("Turn-by-turn from Google Maps", if (listener) "Allowed" else "Needed", listener)
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
    SectionCard("Android Auto", "One-time switch so Android Auto shows apps that aren't from the Play Store.") {
        val steps = listOf(
            "Open Settings → search \"Android Auto\" → open it.",
            "Scroll down and tap \"Version\" 10 times, then OK to enable developer settings.",
            "Tap ⋮ (top right) → Developer settings → turn on \"Unknown sources\".",
            "Connect to the car. ClearDash shows up in the app launcher — tap it once and Android Auto remembers it.",
        )
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}", color = C.cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                Text(s, color = C.text, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
        Hint("Google Maps keeps navigating in the background while ClearDash is on screen — its next turn appears on the dash.")
    }
}

// ───────────────────────── OBD ─────────────────────────

@Composable
private fun ObdCard(prefsVersion: Int) {
    val state by DataHub.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    SectionCard("Engine data", "Your Veepeak OBD2 adapter") {
        StatusLine("Adapter", Prefs.obdName ?: "Not chosen", Prefs.obdAddress != null)
        StatusLine("Status", state.obd.status, state.obd.connected)
        if (state.obd.connected) {
            val o = state.obd
            Hint(listOfNotNull(
                o.rpm?.let { "%,d rpm".format(it) },
                o.coolantF?.let { "coolant ${it.roundToInt()}°F" },
                o.voltage?.let { "%.1f V".format(it) },
            ).joinToString(" · "))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (Prefs.obdAddress == null) "Choose adapter" else "Change adapter") { picking = true }
            if (Prefs.obdAddress != null) Pill("Forget", C.muted) {
                Prefs.obdAddress = null; Prefs.obdName = null
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
    var low by remember { mutableFloatStateOf(Prefs.tireLowPsi.toFloat()) }
    var high by remember { mutableFloatStateOf(Prefs.tireHighPsi.toFloat()) }

    SectionCard("Tire pressure", "Bluetooth valve-cap sensors") {
        Wheel.entries.forEach { w ->
            val id = Prefs.tireId(w)
            val t = state.tires[w]
            val value = when {
                id == null -> "Not paired"
                t == null -> "Waiting…"
                else -> "${t.psi.roundToInt()} PSI" + (t.tempF?.let { " · ${it.roundToInt()}°F" } ?: "")
            }
            StatusLine(w.label, value, if (id == null) null else t != null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Find sensors") { scanning = true }
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

// ───────────────────────── Home Assistant ─────────────────────────

@Composable
private fun HomeAssistantCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DataHub.state.collectAsState()
    var url by remember { mutableStateOf(Prefs.haUrl) }
    var token by remember { mutableStateOf(Prefs.haToken) }
    var msg by remember { mutableStateOf<String?>(null) }
    var entities by remember { mutableStateOf<List<HaEntity>>(emptyList()) }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(Prefs.haEntities) } }

    SectionCard("Home Assistant", "Reached over Tailscale") {
        StatusLine("Status", state.haStatus, if (Prefs.haConfigured) state.haStatus == "Connected" else null)
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text("Address") },
            placeholder = { Text("http://100.x.y.z:8123") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            label = { Text("Long-lived access token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("Use your server's Tailscale IP or MagicDNS name with :8123. Make a token in Home Assistant → your profile → Security → Long-lived access tokens.")
        Pill("Connect & load devices") {
            Prefs.haUrl = url
            Prefs.haToken = token
            url = Prefs.haUrl
            msg = "Connecting…"
            scope.launch {
                msg = try {
                    entities = HomeAssistant.listControllable()
                    DataHub.update { it.copy(haStatus = "Connected") }
                    "Found ${entities.size} controllable devices. Pick what the car should show:"
                } catch (e: Exception) {
                    "Couldn't connect: ${e.message ?: "unreachable"}. Is Tailscale on?"
                }
            }
        }
        msg?.let { Text(it, color = C.text, fontSize = 14.sp) }
        if (entities.isNotEmpty()) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                entities.forEach { e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggle(chosen, e.id) }
                    ) {
                        Checkbox(checked = e.id in chosen, onCheckedChange = { toggle(chosen, e.id) },
                            colors = CheckboxDefaults.colors(checkedColor = C.cyan))
                        Column {
                            Text(e.name, color = C.text, fontSize = 14.sp)
                            Text(e.id, color = C.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        } else if (chosen.isNotEmpty()) {
            Hint("On the car: ${chosen.joinToString { it.substringAfter('.').replace('_', ' ') }}")
        }

        val home = Prefs.homeLat != null
        StatusLine("Home location", if (home) "Set — controls pop up when you arrive" else "Not set", if (home) true else null)
        Pill(if (home) "Reset home to here" else "Set home to where I am") {
            val s = DataHub.state.value
            var lat = s.lat
            var lon = s.lon
            if (lat == null || lon == null) {
                try {
                    @SuppressLint("MissingPermission")
                    val l = ctx.getSystemService(LocationManager::class.java)
                        .getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    lat = l?.latitude; lon = l?.longitude
                } catch (_: Exception) {
                }
            }
            if (lat != null && lon != null) {
                Prefs.homeLat = lat; Prefs.homeLon = lon
                msg = "Home saved."
            } else {
                msg = "No GPS fix yet — step outside for a moment and try again."
            }
        }
    }
}

private fun toggle(list: MutableList<String>, id: String) {
    if (id in list) list.remove(id) else list.add(id)
    Prefs.haEntities = list.toList()
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
    }
}
