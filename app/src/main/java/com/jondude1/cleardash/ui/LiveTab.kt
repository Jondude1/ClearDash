package com.jondude1.cleardash.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import com.jondude1.cleardash.data.TripSummary
import com.jondude1.cleardash.data.Trips
import com.jondude1.cleardash.data.Tuning
import com.jondude1.cleardash.data.Sun
import com.jondude1.cleardash.data.Prefs
import android.graphics.RectF
import androidx.compose.ui.platform.LocalContext
import com.jondude1.cleardash.Features
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.data.MediaSource
import com.jondude1.cleardash.data.Severity
import com.jondude1.cleardash.render.DashMode
import com.jondude1.cleardash.render.DashRenderer
import kotlin.math.roundToInt

/** Live preview of exactly what the car screen shows. */
@Composable
fun LiveTab() {
    val state by DataHub.state.collectAsState()
    val ctx = LocalContext.current
    val renderer = remember { DashRenderer().loadAssets(ctx) }
    var mode by remember { mutableStateOf(DashMode.DASH) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { tick = it }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Canvas(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(5f / 3f) // CX-5 screen: 800×480
                .clipToBounds()
                .border(1.dp, C.edge.copy(alpha = 0.45f))
                .clickableNoRipple { if (!renderer.wake()) mode = DashMode.entries[(mode.ordinal + 1) % DashMode.entries.size] }
        ) {
            tick.let { } // redraw every frame for smooth needle motion
            renderer.night = when (Prefs.nightMode) {
                2 -> true
                1 -> Sun.isDark(state.lat, state.lon)
                else -> false
            }
            drawIntoCanvas {
                renderer.draw(it.nativeCanvas, RectF(0f, 0f, size.width, size.height), size.width.toInt(), size.height.toInt(), state, mode)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp, 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Segmented(listOf("Drive", "Route", "Power", "Systems"), mode.ordinal) { renderer.wake(); mode = DashMode.entries[it] }
            Text("LIVE · SAME AS YOUR CX-5", color = C.muted, fontFamily = HoloMono, fontSize = 11.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 8.dp))
        }

        if (state.activeAlerts.isNotEmpty()) {
            SectionCard("Alerts") {
                state.activeAlerts.forEach { a ->
                    val col = when (a.severity) { Severity.CRITICAL -> C.red; Severity.WARN -> C.amber; else -> C.cyan }
                    Column {
                        Text(a.title, color = col, fontWeight = FontWeight.Medium)
                        Text(a.message, color = C.text, fontSize = 14.sp)
                    }
                }
            }
        }

        state.media?.let { m ->
            SectionCard("Now playing", m.title.ifEmpty { m.appName }) {
                Hint(listOf(m.artist, m.appName).filter { it.isNotBlank() }.distinct().joinToString(" · "))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (m.longForm) "−30s" else "Previous") { MediaSource.back() }
                    Pill(if (m.playing) "Pause" else "Play", C.green) { MediaSource.playPause() }
                    Pill(if (m.longForm) "+30s" else "Next") { MediaSource.forward() }
                }
            }
        }

        TripCard()
        TuningCard()
        PlacesCard()

        SectionCard("Sources") {
            StatusLine("GPS", if (state.gpsOk) "${(state.speedMph ?: 0f).roundToInt()} mph" else "No fix", state.gpsOk)
            StatusLine("Speed limit", state.speedLimitMph?.let { "$it mph" } ?: "Unknown", state.speedLimitMph != null)
            StatusLine("Turn-by-turn", state.nav?.let { it.distance.ifEmpty { "Active" } } ?: "No route", state.nav != null)
            StatusLine("OBD (Veepeak)", state.obd.status, state.obd.connected)
            if (Features.TIRES) {
                val fresh = state.tires.values.count { System.currentTimeMillis() - it.seenAt < 120_000 }
                StatusLine("Tire sensors", if (state.tires.isEmpty()) "None heard yet" else "$fresh of 4 reporting", fresh > 0)
            }
            StatusLine("Now playing", state.media?.appName ?: "Nothing playing", state.media != null)
        }
    }
}

@Composable
private fun TripCard() {
    val live by Trips.live.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(10_000); tick++ } }
    val history = remember(live == null, tick) { Trips.history() }
    if (live == null && history.isEmpty()) return
    SectionCard("Trip debrief", live?.let { "Driving · %.1f mi · %s".format(it.distanceMi, TripSummary.fmtDuration(it.totalSec)) }
        ?: history.first().headline()) {
        val last = history.firstOrNull()
        if (live == null && last != null) {
            last.lines().forEach { (k, v) -> StatusLine(k, v, null) }
        } else if (live != null) {
            StatusLine("Average", "${live!!.avgMph.roundToInt()} mph moving", null)
            StatusLine("Top speed", "${live!!.topMph.roundToInt()} mph", null)
            StatusLine("Over the limit", if (live!!.overLimitSec < 5) "none" else TripSummary.fmtDuration(live!!.overLimitSec), null)
        }
        val older = if (live == null) history.drop(1) else history
        if (older.isNotEmpty()) {
            Text("EARLIER", color = C.cyan, fontFamily = HoloMono, fontSize = 11.sp, letterSpacing = 1.6.sp)
            older.take(5).forEach { t ->
                val day = java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(t.startedAt))
                StatusLine(day, t.headline(), null)
            }
        }
        Hint("Saved when the car disconnects. ATLAS gets a copy too.")
    }
}

/** What ClearDash has learned about this particular car, as engineering notes. */
@Composable
private fun TuningCard() {
    var version by remember { mutableIntStateOf(0) }
    val tips = remember(version) { Tuning.tips() }
    var confirm by remember { mutableStateOf(false) }
    SectionCard("Engineering notes", "Learned from ${Tuning.learnedMinutes} min of engine data") {
        tips.forEach { t ->
            Column {
                Text(t.title, color = when (t.level) { 2 -> C.amber; 1 -> C.cyan; else -> C.green }, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(t.detail, color = C.text, fontSize = 14.sp)
            }
        }
        Hint("Suggestions with the numbers behind them, not a diagnosis. ClearDash only reads from the car.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Refresh") { version++ }
            Pill(if (confirm) "Tap again to reset" else "Reset baseline", C.muted) {
                if (confirm) { Tuning.reset(); confirm = false; version++ } else confirm = true
            }
        }
    }
}

@Composable
private fun PlacesCard() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val parked = remember(version) { Trips.parked() }
    val spots = remember(version) { Trips.spots() }
    if (parked == null && spots.isEmpty()) return
    SectionCard("Places", "Where you parked and spots you saved") {
        (listOfNotNull(parked) + spots).take(8).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (s === parked) "Parked" else s.note, color = C.text, fontSize = 15.sp)
                    Text(s.whenText(), color = C.muted, fontSize = 12.sp)
                }
                Pill("Map") {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.mapsUrl()))) } catch (_: Exception) {}
                }
                if (s !== parked) {
                    Spacer(Modifier.width(6.dp))
                    Pill("✕", C.muted) { Trips.deleteSpot(s.at); version++ }
                }
            }
        }
    }
}
