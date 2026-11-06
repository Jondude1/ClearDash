package com.jondude1.cleardash.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one place every data source writes to and every screen reads from.
 * Sources run while at least one client (car session, phone screen, service) holds it.
 */
object DataHub {
    private const val TAG = "DataHub"
    lateinit var app: Context
        private set

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state

    private val _alerts = MutableSharedFlow<CarAlert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<CarAlert> = _alerts

    private var refs = 0
    private var scope: CoroutineScope? = null
    private var location: LocationSource? = null
    private var tpms: TpmsSource? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun update(f: (VehicleState) -> VehicleState) = _state.update(f)

    fun emitAlert(a: CarAlert) {
        _alerts.tryEmit(a)
    }

    @Synchronized
    fun acquire() {
        refs++
        if (refs == 1) startAll()
    }

    @Synchronized
    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) stopAll()
    }

    val isRunning: Boolean get() = refs > 0

    /** Start the foreground service so data keeps flowing with the phone screen off. */
    fun startService(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DataService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service: ${e.message}")
        }
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, DataService::class.java))
    }

    private fun startAll() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        update { it.copy(running = true) }

        location = LocationSource(app).also { it.start() }
        tpms = TpmsSource(app).also { it.startBackground() }
        MediaSource.start(app)
        s.launch { SpeedLimitSource.run() }
        s.launch { ObdSource(app).run() }
        s.launch { AlertEngine.run() }
        s.launch {
            // Once per start: if the VePEAK or any wheel is still unconfigured, run the
            // check → select → verify auto-selector in the background, then re-arm the
            // filtered tire scan it borrowed the radio from.
            delay(3_000)
            if (AutoSelector.needsSetup()) {
                AutoSelector.scanAndSelect(app, 45_000)
                tpms?.stopBackground()
                tpms?.startBackground()
            }
        }
        s.launch {
            // Re-arm the tire scan filters when sensors are assigned or changed.
            var lastIds = Wheel.entries.map { Prefs.tireId(it) }
            Prefs.version.collect {
                val ids = Wheel.entries.map { w -> Prefs.tireId(w) }
                if (ids != lastIds) {
                    lastIds = ids
                    tpms?.stopBackground()
                    tpms?.startBackground()
                    update { st -> st.copy(tires = st.tires.filterKeys { w -> Prefs.tireId(w) != null }) }
                }
            }
        }
    }

    private fun stopAll() {
        location?.stop(); location = null
        tpms?.stopBackground(); tpms = null
        MediaSource.stop()
        scope?.cancel(); scope = null
        update {
            it.copy(
                running = false,
                gpsOk = false,
                speedMph = null,
                obd = it.obd.copy(connected = false, status = "Stopped"),
                tpmsScanning = false,
            )
        }
    }
}
