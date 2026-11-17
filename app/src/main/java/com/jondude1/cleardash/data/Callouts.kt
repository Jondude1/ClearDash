package com.jondude1.cleardash.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * J.A.R.V.I.S.-style spoken callouts while ClearDash is open in the car.
 *
 * Short, one line each: the startup greeting, speed-limit changes, low fuel, a break
 * reminder on long drives, and system events (engine telemetry online, route acquired).
 * Every type has its own switch. Callouts duck the music, never talk over a phone call or a
 * Google Maps voice prompt (they wait up to 20 s, then drop), and can be muted for a drive.
 */
object Callouts {
    private const val TAG = "Callouts"
    private const val WAIT_MS = 20_000L

    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val pending = ArrayDeque<Pair<String, Long>>()
    private var focus: AudioFocusRequest? = null

    @Volatile var mutedThisDrive = false
    @Volatile var lastSpoken: String? = null
        private set

    private val speech: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ── lifecycle ──

    /** Called when ClearDash opens in the car. */
    @Synchronized
    fun start(ctx: Context) {
        app = ctx.applicationContext
        mutedThisDrive = false
        ensureTts()
        job?.cancel()
        job = scope.launch { watch() }
    }

    /** Called when the car disconnects. */
    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        synchronized(pending) { pending.clear() }
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS unavailable ($status)")
                return@TextToSpeech
            }
            val t = tts ?: return@TextToSpeech
            t.language = Locale.US
            t.setAudioAttributes(speech)
            t.setSpeechRate(1.0f)
            t.setPitch(0.92f)
            bestVoice(t)?.let { t.voice = it }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = releaseFocus()
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = releaseFocus()
            })
            ready = true
        }
    }

    /** Highest-quality offline US English voice, so callouts work with no signal. */
    private fun bestVoice(t: TextToSpeech): Voice? = try {
        t.voices
            ?.filter { it.locale.language == "en" && it.locale.country == "US" && !it.isNetworkConnectionRequired }
            ?.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            ?.maxByOrNull { it.quality }
    } catch (_: Exception) {
        null
    }

    // ── speaking ──

    /** Queues a callout. [force] skips the master switch and the per-drive mute (used for tests and "repeat"). */
    fun say(text: String, force: Boolean = false) {
        if (!force && (!Prefs.coVoice || mutedThisDrive)) return
        synchronized(pending) { pending.addLast(text to System.currentTimeMillis()) }
        scope.launch { drain() }
    }

    fun repeatLast(): Boolean {
        val t = lastSpoken ?: return false
        say(t, force = true)
        return true
    }

    /** Speaks a sample from the phone's Setup screen. */
    fun test(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        ensureTts()
        scope.launch {
            var waited = 0
            while (!ready && waited < 30) { delay(100); waited++ }
            say("ClearDash online. Speed limit 45. Fuel at 15 percent.", force = true)
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(pending) {
                val now = System.currentTimeMillis()
                while (pending.isNotEmpty() && now - pending.first().second > WAIT_MS) pending.removeFirst()
                pending.firstOrNull()
            } ?: return
            if (!ready || busy()) return // the watcher retries every second
            synchronized(pending) { if (pending.firstOrNull() == next) pending.removeFirst() }
            speakNow(next.first)
        }
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        val am = app.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speech)
            .build()
        focus = req
        am.requestAudioFocus(req)
        lastSpoken = text
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "cd-${System.nanoTime()}")
    }

    private fun releaseFocus() {
        val f = focus ?: return
        if (tts?.isSpeaking == true) return
        try { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(f) } catch (_: Exception) {}
        focus = null
    }

    /** True while a call is on or another app is speaking directions or a voice assistant is talking. */
    private fun busy(): Boolean = try {
        val am = app.getSystemService(AudioManager::class.java)
        val mode = am.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
            true
        } else {
            val quietFor = setOf(
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.USAGE_ASSISTANT,
            )
            (tts?.isSpeaking == true) || am.activePlaybackConfigurations.any { it.audioAttributes.usage in quietFor }
        }
    } catch (_: Exception) {
        false
    }

    // ── the rules ──

    private suspend fun watch() {
        delay(2_800) // let the boot sequence finish
        if (Prefs.coGreeting) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val hello = when (hour) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
            say("$hello. ClearDash online.")
        }

        var stableLimit: Int? = null
        var candidate: Int? = null
        var candidateSince = 0L
        val fuelSaid = HashSet<Int>()
        var obdWasOn = false
        var navWasOn = false
        var breaksSaid = 0

        while (true) {
            val s = DataHub.state.value
            val now = System.currentTimeMillis()
            val spd = s.bestSpeed ?: 0f

            // speed limit: announce once it's been steady for 3 s, only while moving
            val lim = s.speedLimitMph
            if (lim != candidate) { candidate = lim; candidateSince = now }
            if (lim != null && lim != stableLimit && now - candidateSince >= 3_000) {
                val prev = stableLimit
                stableLimit = lim
                val mode = Prefs.coLimit
                if (prev != null && spd > 10f && (mode == 2 || (mode == 1 && lim < prev))) {
                    say("Speed limit $lim.")
                }
            }

            // low fuel, once per threshold per drive
            if (Prefs.coFuel && s.obd.connected) {
                s.obd.fuelPct?.let { pct ->
                    for (th in listOf(15, 10)) {
                        if (pct <= th && th !in fuelSaid) {
                            fuelSaid += th
                            fuelSaid += listOf(15, 10).filter { it > th }
                            val gal = Prefs.tankGal * pct / 100f
                            say("Fuel at $pct percent. About ${"%.1f".format(gal)} gallons left.")
                            break
                        }
                    }
                }
            }

            // long drive: every 2 hours
            val total = Trips.live.value?.totalSec ?: 0
            if (Prefs.coLongDrive && total / 7200 > breaksSaid) {
                breaksSaid = total / 7200
                val hrs = breaksSaid * 2
                say("You've been driving for $hrs hours. Good time for a break.")
            }

            // system events
            if (s.obd.connected && !obdWasOn) {
                obdWasOn = true
                if (Prefs.coSystems) say("Engine telemetry online.")
            }
            val navOn = s.nav != null
            if (navOn && !navWasOn && Prefs.coSystems) say("Route acquired.")
            navWasOn = navOn

            drain()
            delay(1_000)
        }
    }

    fun limitModeLabel(m: Int) = when (m) { 0 -> "Off"; 2 -> "Every change"; else -> "When it drops" }
}
