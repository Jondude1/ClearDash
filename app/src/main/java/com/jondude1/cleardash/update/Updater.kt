package com.jondude1.cleardash.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jondude1.cleardash.BuildConfig
import com.jondude1.cleardash.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK and installs it. */
object Updater {
    private const val API = "https://api.github.com/repos/Jondude1/ClearDash/releases/latest"

    data class Release(val versionCode: Int, val tag: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val percent: Int) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    var dismissed = false

    /** Quiet check: at most every 30 minutes. */
    suspend fun checkQuietly() {
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < 30 * 60_000) return
        check(quiet = true)
    }

    suspend fun check(quiet: Boolean = false) {
        if (!quiet) _state.value = State.Checking
        Prefs.lastUpdateCheck = System.currentTimeMillis()
        try {
            val rel = withContext(Dispatchers.IO) { fetchLatest() }
            _state.value = if (rel != null && rel.versionCode > BuildConfig.VERSION_CODE) {
                State.Available(rel)
            } else {
                State.UpToDate
            }
        } catch (e: Exception) {
            if (!quiet) _state.value = State.Error(e.message ?: "Check failed")
        }
    }

    private fun fetchLatest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")
            val code = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(code, tag, a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    suspend fun downloadAndInstall(ctx: Context, rel: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = State.Available(rel)
            return
        }
        try {
            _state.value = State.Downloading(0)
            val file = withContext(Dispatchers.IO) { download(ctx, rel.apkUrl) }
            _state.value = State.Available(rel)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            _state.value = State.Error("Download failed: ${e.message}")
        }
    }

    private fun download(ctx: Context, start: String): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ClearDash.apk")
        var url = start
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect")
                c.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = State.Downloading(pct)
                            }
                        }
                    }
                }
            }
            c.disconnect()
            return out
        }
        throw IllegalStateException("Too many redirects")
    }
}
