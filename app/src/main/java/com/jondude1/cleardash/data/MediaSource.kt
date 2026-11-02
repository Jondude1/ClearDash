package com.jondude1.cleardash.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Follows whatever app is playing audio (Spotify, Audiobookshelf, YouTube Music, podcasts…)
 * through Android's media sessions. Uses the same notification access as turn-by-turn.
 */
object MediaSource {
    private val longFormApps = setOf(
        "com.audiobookshelf.app",
        "com.audible.application",
        "com.google.android.apps.podcasts",
        "au.com.shiftyjelly.pocketcasts",
        "com.bambuna.podcastaddict",
        "fm.player",
        "de.danoeh.antennapod",
        "com.overdrive.mobile.android.libby",
        "app.grapheneos.podcasts",
    )

    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var current: MediaController? = null
    private val artCache = HashMap<String, Bitmap?>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val controllerCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            current = null
            refreshSessions()
        }
    }

    private val pollPlaying = object : Runnable {
        override fun run() {
            // A paused session may be overtaken by another app starting to play.
            refreshSessions()
            main.postDelayed(this, 5_000)
        }
    }

    fun start(context: Context) = main.post {
        ctx = context.applicationContext
        try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            try { m.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
            msm = m
            val comp = ComponentName(ctx, NavListenerService::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
            pick(m.getActiveSessions(comp))
        } catch (e: SecurityException) {
            Log.d("Media", "notification access not granted yet")
        }
        main.removeCallbacks(pollPlaying)
        main.postDelayed(pollPlaying, 5_000)
    }

    fun stop() = main.post {
        main.removeCallbacks(pollPlaying)
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        current?.unregisterCallback(controllerCb)
        current = null
        DataHub.update { it.copy(media = null) }
    }

    private fun refreshSessions() {
        try {
            val m = msm ?: return
            pick(m.getActiveSessions(ComponentName(ctx, NavListenerService::class.java)))
        } catch (_: SecurityException) {
        }
    }

    private fun pick(list: List<MediaController>) {
        val own = ctx.packageName
        val candidates = list.filter { it.packageName != own }
        val chosen = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { c -> current != null && c.sessionToken == current?.sessionToken }
            ?: candidates.firstOrNull { it.metadata != null }
        if (chosen?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(controllerCb)
            current = chosen
            chosen?.registerCallback(controllerCb, main)
        }
        publish()
    }

    private fun publish() {
        val c = current
        val md = c?.metadata
        if (c == null || md == null) {
            DataHub.update { it.copy(media = null) }
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val ps = c.playbackState
        val pkg = c.packageName
        DataHub.update {
            it.copy(
                media = MediaInfo(
                    packageName = pkg,
                    appName = appName(pkg),
                    title = title,
                    artist = artist,
                    art = art(md, "$pkg|$title"),
                    durationMs = duration,
                    positionMs = ps?.position ?: 0L,
                    positionAt = ps?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime(),
                    speed = ps?.playbackSpeed?.takeIf { s -> s > 0f } ?: 1f,
                    playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING,
                    longForm = pkg in longFormApps || duration > 20 * 60_000L,
                )
            )
        }
    }

    private fun appName(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun art(md: MediaMetadata, key: String): Bitmap? {
        md.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        if (artCache.containsKey(key)) return artCache[key]
        val bmp = try {
            if (uri.startsWith("content:") || uri.startsWith("android.resource:") || uri.startsWith("file:")) {
                ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            } else null
        } catch (_: Exception) {
            null
        }
        if (artCache.size > 20) artCache.clear()
        artCache[key] = bmp
        return bmp
    }

    // ── controls ──

    fun playPause() = main.post {
        val c = current ?: return@post
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun back() = main.post {
        val c = current ?: return@post
        val m = DataHub.state.value.media
        if (m?.longForm == true) c.transportControls.seekTo((m.livePosition() - 30_000).coerceAtLeast(0))
        else c.transportControls.skipToPrevious()
    }

    fun forward() = main.post {
        val c = current ?: return@post
        val m = DataHub.state.value.media
        if (m?.longForm == true) {
            val t = m.livePosition() + 30_000
            c.transportControls.seekTo(if (m.durationMs > 0) t.coerceAtMost(m.durationMs) else t)
        } else c.transportControls.skipToNext()
    }
}
