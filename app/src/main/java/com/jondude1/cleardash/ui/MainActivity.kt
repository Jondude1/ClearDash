package com.jondude1.cleardash.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.jondude1.cleardash.R
import com.jondude1.cleardash.render.DashRenderer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.jondude1.cleardash.BuildConfig
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.update.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClearDashTheme {
                var intro by rememberSaveable { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AppRoot()
                    if (intro) IntroOverlay { intro = false }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DataHub.acquire()
    }

    override fun onStop() {
        DataHub.release()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { Updater.checkQuietly() }
    }
}

@Composable
private fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .safeDrawingPadding()
    ) {
        UpdateBanner()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ClearDash", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("v${BuildConfig.VERSION_NAME}", color = C.muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Segmented(listOf("Live", "Setup"), tab) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) LiveTab() else SetupTab()
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(C.card)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) C.cyan.copy(alpha = 0.18f) else C.card)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clickableNoRipple { onSelect(i) }
            ) {
                Text(label, color = if (on) C.cyan else C.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun UpdateBanner() {
    val state by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    when (val s = state) {
        is Updater.State.Available -> if (!Updater.dismissed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(C.cyan.copy(alpha = 0.14f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Update available — ${s.release.tag}", color = C.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { Updater.dismissed = true; scope.launch { Updater.check(quiet = true) } }) {
                    Text("Later", color = C.muted)
                }
                TextButton(onClick = { scope.launch { Updater.downloadAndInstall(ctx, s.release) } }) {
                    Text("Update", color = C.cyan)
                }
            }
        }
        is Updater.State.Downloading -> Column(
            Modifier
                .fillMaxWidth()
                .background(C.cyan.copy(alpha = 0.14f))
                .padding(16.dp, 10.dp)
        ) {
            Text("Downloading update… ${s.percent}%", color = C.text)
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        else -> {}
    }
}

/** Startup sequence on the phone — the same one the car plays. Tap to skip. */
@Composable
private fun IntroOverlay(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember {
        DashRenderer().apply {
            carPhoto = try { BitmapFactory.decodeResource(ctx.resources, R.drawable.cx5_blue) } catch (_: Exception) { null }
        }
    }
    val start = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(start) }
    LaunchedEffect(Unit) {
        while (now - start < DashRenderer.INTRO_MS) withFrameMillis { now = System.currentTimeMillis() }
        onDone()
    }
    val e = now - start
    val fade = if (e < DashRenderer.INTRO_HOLD_MS) 1f
    else 1f - (e - DashRenderer.INTRO_HOLD_MS) / (DashRenderer.INTRO_MS - DashRenderer.INTRO_HOLD_MS).toFloat()
    Canvas(
        Modifier
            .fillMaxSize()
            .alpha(fade.coerceIn(0f, 1f))
            .clickableNoRipple { onDone() }
    ) {
        drawIntoCanvas {
            renderer.drawIntro(it.nativeCanvas, size.width, size.height, e.coerceAtMost(DashRenderer.INTRO_HOLD_MS.toLong()))
        }
    }
}
