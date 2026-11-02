package com.jondude1.cleardash.car

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
import androidx.lifecycle.lifecycleScope
import com.jondude1.cleardash.R
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.data.MediaSource
import com.jondude1.cleardash.data.Prefs
import com.jondude1.cleardash.render.DashMode
import com.jondude1.cleardash.render.DashRenderer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln

/**
 * The main car screen: a custom-drawn dashboard.
 *
 * Built for the CX-5 commander knob, since Mazda locks the touchscreen while moving:
 *  • Nudge the knob to reach the side buttons (Drive / Gauges / Tires / Home) and the
 *    media buttons (back · play/pause · forward); press the knob to select.
 *  • Select the "move" button, then simply turn the knob to flip between views.
 *  • Parked, tapping the screen also flips views.
 */
class DashScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val renderer = DashRenderer()
    private val lock = Any()
    private var container: SurfaceContainer? = null
    @Volatile private var visible: Rect? = null
    @Volatile private var inPanMode = false
    private val thread = HandlerThread("dash-render").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var running = false
    private var introPlayed = false

    // knob gesture accumulation
    private var knobAcc = 0f
    private var lastStep = 0L

    private val frame = object : Runnable {
        override fun run() {
            drawFrame()
            if (running) handler.postDelayed(this, 40)
        }
    }

    init {
        lifecycle.addObserver(this)
        renderer.carPhoto = try {
            BitmapFactory.decodeResource(carContext.resources, R.drawable.cx5_blue)
        } catch (_: Exception) {
            null
        }
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
        // play the startup sequence once per drive, when the screen first appears
        if (!introPlayed) {
            introPlayed = true
            renderer.startIntro()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        synchronized(lock) { container = null }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    // ── knob + touch ──

    /** Knob rotation arrives as zoom while in pan mode: clockwise = next view. */
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        knobAcc += ln(scaleFactor)
        if (abs(knobAcc) > 0.04f) {
            step(if (knobAcc > 0) 1 else -1)
            knobAcc = 0f
        }
    }

    /** Knob nudges arrive as small scrolls while in pan mode. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val d = if (abs(distanceX) >= abs(distanceY)) distanceX else distanceY
        knobAcc += d
        if (abs(knobAcc) > 24f) {
            step(if (knobAcc > 0) 1 else -1)
            knobAcc = 0f
        }
    }

    /** Parked: a tap anywhere flips to the next view. */
    override fun onClick(x: Float, y: Float) = step(1)

    private fun step(dir: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStep < 280) return
        lastStep = now
        val modes = DashMode.entries
        currentMode = modes[(currentMode.ordinal + dir + modes.size) % modes.size]
    }

    private fun setMode(m: DashMode) {
        currentMode = m
    }

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
                    renderer.draw(canvas, area, sc.width, sc.height, DataHub.state.value, currentMode, inPanMode)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (e: Exception) {
                Log.w("DashScreen", "draw failed: ${e.message}")
            }
        }
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun action(res: Int, onClick: () -> Unit) = Action.Builder()
        .setIcon(icon(res))
        .setOnClickListener { onClick() }
        .build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_stat) { setMode(DashMode.DASH) })
            .addAction(action(R.drawable.ic_act_gauges) { setMode(DashMode.GAUGES) })
            .addAction(action(R.drawable.ic_act_tire) { setMode(DashMode.TIRES) })
            .addAction(action(R.drawable.ic_act_home) {
                if (Prefs.haConfigured) {
                    carContext.getCarService(ScreenManager::class.java).push(HomeScreen(carContext))
                } else {
                    CarToast.makeText(carContext, "Set up Home Assistant in the ClearDash phone app", CarToast.LENGTH_LONG).show()
                }
            })
            .build()
        val b = NavigationTemplate.Builder().setActionStrip(strip)

        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            // Pan mode is how the knob's rotation reaches us.
            b.setPanModeListener { isInPanMode -> inPanMode = isInPanMode }
            val map = ActionStrip.Builder()
            val media = DataHub.state.value.media
            if (media != null) {
                map.addAction(action(if (media.longForm) R.drawable.ic_media_rewind else R.drawable.ic_media_prev) { MediaSource.back() })
                map.addAction(action(if (media.playing) R.drawable.ic_media_pause else R.drawable.ic_ha_play) { MediaSource.playPause() })
                map.addAction(action(if (media.longForm) R.drawable.ic_media_ffwd else R.drawable.ic_media_next) { MediaSource.forward() })
            }
            map.addAction(Action.PAN)
            b.setMapActionStrip(map.build())
        }
        return b.build()
    }

    companion object {
        /** Remembered for the session so the car reopens on the last view. */
        @Volatile var currentMode = DashMode.DASH
    }
}
