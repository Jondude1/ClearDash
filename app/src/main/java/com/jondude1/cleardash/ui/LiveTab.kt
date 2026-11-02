package com.jondude1.cleardash.ui

import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.compose.ui.platform.LocalContext
import com.jondude1.cleardash.R
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
    val renderer = remember {
        DashRenderer().apply {
            carPhoto = try { BitmapFactory.decodeResource(ctx.resources, R.drawable.cx5_blue) } catch (_: Exception) { null }
        }
    }
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
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, C.edge, RoundedCornerShape(18.dp))
                .clickableNoRipple { mode = DashMode.entries[(mode.ordinal + 1) % DashMode.entries.size] }
        ) {
            tick.let { } // redraw every frame for smooth needle motion
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
            Segmented(listOf("Dash", "Gauges", "Tires"), mode.ordinal) { mode = DashMode.entries[it] }
            Text("Exactly what your CX-5 shows", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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

        SectionCard("Sources") {
            StatusLine("GPS", if (state.gpsOk) "${(state.speedMph ?: 0f).roundToInt()} mph" else "No fix", state.gpsOk)
            StatusLine("Speed limit", state.speedLimitMph?.let { "$it mph" } ?: "Unknown", state.speedLimitMph != null)
            StatusLine("Turn-by-turn", state.nav?.let { it.distance.ifEmpty { "Active" } } ?: "No route", state.nav != null)
            StatusLine("OBD (Veepeak)", state.obd.status, state.obd.connected)
            val fresh = state.tires.values.count { System.currentTimeMillis() - it.seenAt < 120_000 }
            StatusLine("Tire sensors", if (state.tires.isEmpty()) "None heard yet" else "$fresh of 4 reporting", fresh > 0)
            StatusLine("Home Assistant", state.haStatus, state.haStatus == "Connected")
            StatusLine("Now playing", state.media?.appName ?: "Nothing playing", state.media != null)
        }
    }
}
