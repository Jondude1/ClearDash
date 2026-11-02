package com.jondude1.cleardash.car

import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jondude1.cleardash.R
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.data.MediaSource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.jondude1.cleardash.data.Prefs
import com.jondude1.cleardash.render.DashMode
import com.jondude1.cleardash.render.DashRenderer

/** The main car screen: a full custom-drawn dashboard with an action strip to switch views. */
class DashScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    @Volatile private var mode = DashMode.DASH
    private val renderer = DashRenderer()
    private val lock = Any()
    private var container: SurfaceContainer? = null
    @Volatile private var visible: Rect? = null
    private val thread = HandlerThread("dash-render").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var running = false

    private val frame = object : Runnable {
        override fun run() {
            drawFrame()
            if (running) handler.postDelayed(this, 50)
        }
    }

    init {
        lifecycle.addObserver(this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        // Rebuild the buttons only when the media controls actually need to change.
        lifecycleScope.launch {
            DataHub.state
                .map { st -> st.media?.let { Pair(it.playing, it.longForm) } }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        running = true
        handler.removeCallbacks(frame)
        handler.post(frame)
    }

    override fun onStop(owner: LifecycleOwner) {
        running = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        running = false
        thread.quitSafely()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(lock) { container = surfaceContainer }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        synchronized(lock) { container = null }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    private fun drawFrame() {
        synchronized(lock) {
            val sc = container ?: return
            val surface = sc.surface ?: return
            if (!surface.isValid) return
            try {
                val canvas = surface.lockHardwareCanvas()
                try {
                    val v = visible
                    val area = if (v != null && !v.isEmpty) RectF(v) else RectF(0f, 0f, sc.width.toFloat(), sc.height.toFloat())
                    renderer.draw(canvas, area, sc.width, sc.height, DataHub.state.value, mode)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (e: Exception) {
                Log.w("DashScreen", "draw failed: ${e.message}")
            }
        }
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun modeAction(res: Int, m: DashMode) = Action.Builder()
        .setIcon(icon(res))
        .setOnClickListener {
            mode = m
        }
        .build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(modeAction(R.drawable.ic_stat, DashMode.DASH))
            .addAction(modeAction(R.drawable.ic_act_gauges, DashMode.GAUGES))
            .addAction(modeAction(R.drawable.ic_act_tire, DashMode.TIRES))
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_act_home))
                    .setOnClickListener {
                        if (Prefs.haConfigured) {
                            carContext.getCarService(ScreenManager::class.java).push(HomeScreen(carContext))
                        } else {
                            CarToast.makeText(carContext, "Set up Home Assistant in the ClearDash phone app", CarToast.LENGTH_LONG).show()
                        }
                    }
                    .build()
            )
            .build()
        val b = NavigationTemplate.Builder().setActionStrip(strip)
        val media = DataHub.state.value.media
        if (media != null && carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            val mediaStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder().setIcon(icon(if (media.longForm) R.drawable.ic_media_rewind else R.drawable.ic_media_prev))
                        .setOnClickListener { MediaSource.back() }.build()
                )
                .addAction(
                    Action.Builder().setIcon(icon(if (media.playing) R.drawable.ic_media_pause else R.drawable.ic_ha_play))
                        .setOnClickListener { MediaSource.playPause() }.build()
                )
                .addAction(
                    Action.Builder().setIcon(icon(if (media.longForm) R.drawable.ic_media_ffwd else R.drawable.ic_media_next))
                        .setOnClickListener { MediaSource.forward() }.build()
                )
                .build()
            b.setMapActionStrip(mediaStrip)
        }
        return b.build()
    }
}
