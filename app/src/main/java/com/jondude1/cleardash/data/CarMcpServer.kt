package com.jondude1.cleardash.data

import android.util.Log
import com.jondude1.cleardash.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * ClearDash's side of the ATLAS link: a tiny MCP server (Streamable HTTP, plain JSON replies)
 * that only listens on the phone itself, at http://127.0.0.1:8770/mcp, behind a bearer token.
 *
 * Add it in ATLAS under Settings → Connections and ATLAS can answer "how's the engine?",
 * "what's my ETA?", "skip this song", "where did I park?" from live car data — through the
 * earbuds or glasses you already talk to it with.
 */
object CarMcpServer {
    const val PORT = 8770
    const val URL = "http://127.0.0.1:$PORT/mcp"
    private const val TAG = "CarMcp"

    data class Status(val listening: Boolean = false, val error: String? = null, val lastCallAt: Long = 0L, val lastTool: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(3)

    @Synchronized
    fun start() {
        if (server != null) return
        try {
            val ss = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            server = ss
            _status.value = _status.value.copy(listening = true, error = null)
            Thread({
                while (!ss.isClosed) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handle(sock) }
                    } catch (_: Exception) {
                        if (ss.isClosed) break
                    }
                }
            }, "cleardash-mcp").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w(TAG, "can't listen: ${e.message}")
            _status.value = _status.value.copy(listening = false, error = e.message ?: "couldn't open the port")
        }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        _status.value = _status.value.copy(listening = false)
    }

    // ── HTTP ──

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 10_000
                val req = read(BufferedInputStream(s.getInputStream())) ?: return
                val out = s.getOutputStream()
                val path = req.path.substringBefore('?')
                when {
                    path != "/mcp" -> respond(out, 404, "text/plain", "not found")
                    req.method == "GET" || req.method == "DELETE" -> respond(out, 405, "text/plain", "method not allowed")
                    req.method != "POST" -> respond(out, 405, "text/plain", "method not allowed")
                    req.headers["authorization"]?.removePrefix("Bearer ")?.trim() != Prefs.mcpToken ->
                        respond(out, 401, "application/json", """{"error":"bad token"}""")
                    else -> rpc(out, req.body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "request failed: ${e.message}")
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 32_768) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 1_000_000) return null
        val body = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = input.read(body, got, len - got)
            if (n < 0) break
            got += n
        }
        return Request(first[0].uppercase(), first[1], headers, String(body, 0, got, Charsets.UTF_8))
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String, extra: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ── JSON-RPC ──

    private fun rpc(out: OutputStream, body: String) {
        val trimmed = body.trim()
        val headers = HashMap<String, String>()
        try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val replies = JSONArray()
                for (i in 0 until arr.length()) handleOne(arr.getJSONObject(i), headers)?.let { replies.put(it) }
                if (replies.length() == 0) respond(out, 202, "application/json", "", headers)
                else respond(out, 200, "application/json", replies.toString(), headers)
            } else {
                val reply = handleOne(JSONObject(trimmed), headers)
                if (reply == null) respond(out, 202, "application/json", "", headers)
                else respond(out, 200, "application/json", reply.toString(), headers)
            }
        } catch (e: Exception) {
            respond(out, 400, "application/json", error(JSONObject.NULL, -32700, "parse error").toString())
        }
    }

    private fun handleOne(msg: JSONObject, headers: MutableMap<String, String>): JSONObject? {
        val method = msg.optString("method")
        if (!msg.has("id")) return null // a notification
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> {
                    headers["Mcp-Session-Id"] = UUID.randomUUID().toString()
                    ok(id, JSONObject()
                        .put("protocolVersion", params.optString("protocolVersion").ifBlank { "2025-06-18" })
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "ClearDash").put("version", BuildConfig.VERSION_NAME))
                        .put("instructions", "Live data from Jon's 2018 Mazda CX-5 through ClearDash (Android Auto dashboard). " +
                            "Data is live only while ClearDash is running in the car; trips and parking are remembered after."))
                }
                "ping" -> ok(id, JSONObject())
                "tools/list" -> ok(id, JSONObject().put("tools", CarTools.definitions()))
                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    _status.value = _status.value.copy(lastCallAt = System.currentTimeMillis(), lastTool = name)
                    val (text, isError) = CarTools.call(name, args)
                    ok(id, JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                        .put("isError", isError))
                }
                else -> error(id, -32601, "method not found: $method")
            }
        } catch (e: Exception) {
            error(id, -32603, e.message ?: "internal error")
        }
    }

    private fun ok(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    private fun error(id: Any, code: Int, msg: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", msg))

    /** Setup's "Test" button: talks to the server exactly as ATLAS will. */
    fun selfTest(): String = try {
        val c = java.net.URL(URL).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3_000
        c.readTimeout = 5_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer ${Prefs.mcpToken}")
        c.outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""".toByteArray()) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code == 200) "Working — ${JSONObject(body).getJSONObject("result").getJSONArray("tools").length()} car tools ready for ATLAS."
        else "Server answered $code."
    } catch (e: Exception) {
        "Not reachable: ${e.message}"
    }
}

/** The tools ATLAS sees, and their plain-English answers. */
object CarTools {
    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).put(
            "inputSchema",
            JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
        )

    private fun enumProp(desc: String, vararg values: String) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))

    fun definitions(): JSONArray = JSONArray()
        .put(tool("car_status", "Everything live from Jon's CX-5 right now: speed, posted speed limit, heading, GPS position, engine readings, fuel, navigation, what's playing and the trip so far. Use for general 'how's the car' questions."))
        .put(tool("engine", "Live engine readings from the OBD2 adapter: RPM, coolant temperature, battery/charging voltage, engine load, intake air temperature, fuel level and estimated gallons left."))
        .put(tool("navigation", "The next turn and ETA from the route Google Maps is navigating, plus current speed and the posted limit."))
        .put(tool("now_playing", "What's playing in the car (Spotify, Audiobookshelf, YouTube Music, podcasts…): title, artist or author, app, position."))
        .put(tool("media_control", "Control the audio playing in the car. For audiobooks and podcasts, next/previous jump 30 seconds forward/back.",
            JSONObject().put("action", enumProp("What to do", "play", "pause", "toggle", "next", "previous")), listOf("action")))
        .put(tool("trip", "The drive in progress (distance, time, average and top speed, time over the limit, fuel used) and the last finished trip."))
        .put(tool("trip_history", "Recent finished drives, newest first.",
            JSONObject().put("count", JSONObject().put("type", "integer").put("description", "How many trips (1-30, default 5)"))))
        .put(tool("where_parked", "Where the car was parked at the end of the last drive, when, and a Google Maps link."))
        .put(tool("mark_spot", "Save the car's current location with a short note (e.g. 'good parking', 'pothole', 'fishing spot').",
            JSONObject().put("note", JSONObject().put("type", "string").put("description", "Short label for the spot"))))
        .put(tool("list_spots", "Places saved with mark_spot or from the car's quick actions, newest first, with Google Maps links."))
        .put(tool("engineering_notes", "What ClearDash has learned about this particular CX-5 from its engine data over time (fuel trims, idle drift, charging voltage, warm-up time, temperatures, most efficient cruising speed) as plain-English suggestions. Use for 'how's the car holding up', 'anything I should fix', 'what speed is most efficient'."))
        .put(tool("callouts", "ClearDash's spoken car callouts (speed-limit changes, low fuel, break reminders): mute or unmute them for the rest of this drive, repeat the last one, or check their status.",
            JSONObject().put("action", enumProp("What to do", "mute", "unmute", "repeat", "status")), listOf("action")))

    /** Returns the answer text and whether it's an error. */
    fun call(name: String, a: JSONObject): Pair<String, Boolean> {
        val s = DataHub.state.value
        val live = DataHub.isRunning
        val offline = "ClearDash isn't running in the car right now, so there's no live data."
        return when (name) {
            "car_status" -> if (!live) offline to false else listOf(speedText(s), engineText(s), navText(s), mediaText(s), tripLiveText()).joinToString("\n") to false
            "engine" -> if (!live) offline to false else engineText(s) to false
            "navigation" -> if (!live) offline to false else (speedText(s) + "\n" + navText(s)) to false
            "now_playing" -> mediaText(s) to false
            "media_control" -> mediaControl(a.optString("action"), s)
            "trip" -> (tripLiveText() + "\n" + (Trips.last()?.let { "Last trip (ended ${ago(it.endedAt)}): " + tripText(it) } ?: "No finished trips yet.")) to false
            "trip_history" -> {
                val n = a.optInt("count", 5).coerceIn(1, 30)
                val list = Trips.history().take(n)
                (if (list.isEmpty()) "No finished trips yet." else list.joinToString("\n") { "• ${ago(it.endedAt)}: ${tripText(it)}" }) to false
            }
            "where_parked" -> (Trips.parked()?.let { "Parked ${ago(it.at)} (${it.whenText()}) at %.5f, %.5f. Map: %s".format(it.lat, it.lon, it.mapsUrl()) }
                ?: "No parking spot saved yet — it's saved when ClearDash closes in the car.") to false
            "mark_spot" -> Trips.markSpot(a.optString("note"))?.let { "Saved \"${it.note}\" at %.5f, %.5f.".format(it.lat, it.lon) to false }
                ?: ("No GPS fix right now, so the spot couldn't be saved." to true)
            "list_spots" -> Trips.spots().let { l ->
                (if (l.isEmpty()) "No saved spots." else l.joinToString("\n") { "• ${it.note} — ${it.whenText()} — ${it.mapsUrl()}" }) to false
            }
            "engineering_notes" -> ("Learned from ${Tuning.learnedMinutes} min of engine data:\n" +
                Tuning.tips().joinToString("\n") { "• ${it.title}: ${it.detail}" }) to false
            "callouts" -> when (a.optString("action")) {
                "mute" -> { Callouts.mutedThisDrive = true; "Callouts muted for the rest of this drive." to false }
                "unmute" -> { Callouts.mutedThisDrive = false; "Callouts back on." to false }
                "repeat" -> if (Callouts.repeatLast()) "Repeating the last callout." to false else "Nothing has been said yet this drive." to false
                else -> ("Callouts are " + (if (!Prefs.coVoice) "switched off in Setup" else if (Callouts.mutedThisDrive) "muted for this drive" else "on") +
                    ". Last one: " + (Callouts.lastSpoken ?: "none") + ".") to false
            }
            else -> "Unknown tool $name." to true
        }
    }

    private fun speedText(s: VehicleState): String {
        val spd = s.bestSpeed
        val dirs = arrayOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        return buildString {
            append(if (spd == null) "Speed unknown (no GPS fix)." else "Speed ${spd.roundToInt()} mph.")
            s.speedLimitMph?.let { append(" Posted limit $it mph.") }
            s.bearing?.let { append(" Heading ${dirs[(((it + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]}.") }
            if (s.lat != null && s.lon != null) append(" Position %.5f, %.5f.".format(s.lat, s.lon))
        }
    }

    private fun engineText(s: VehicleState): String {
        val o = s.obd
        if (!o.connected) return "Engine data unavailable: OBD adapter ${o.status.lowercase()}."
        return listOfNotNull(
            o.rpm?.let { "RPM %,d".format(it) },
            o.coolantF?.let { "coolant ${it.roundToInt()}°F" },
            o.voltage?.let { "battery %.1f V".format(it) },
            o.load?.let { "engine load $it%" },
            o.throttle?.let { "throttle $it%" },
            o.maf?.let { "air flow %.1f g/s (≈ %.0f hp)".format(it, it * 1.32f) },
            o.intakeF?.let { "intake air ${it.roundToInt()}°F" },
            o.fuelPct?.let { "fuel $it% (about %.1f gallons of %.1f)".format(Prefs.tankGal * it / 100f, Prefs.tankGal) },
        ).joinToString(", ").ifEmpty { "Adapter connected; waiting for readings." }.replaceFirstChar { it.uppercase() } + "."
    }

    private fun navText(s: VehicleState): String {
        val n = s.nav?.takeIf { System.currentTimeMillis() - it.updatedAt < 5 * 60_000 } ?: return "No route is being navigated."
        return "Next: ${listOf(n.distance, n.instruction).filter { it.isNotBlank() }.joinToString(" — ")}." +
            if (n.eta.isNotBlank()) " ${n.eta}." else ""
    }

    private fun mediaText(s: VehicleState): String {
        val m = s.media ?: return "Nothing is playing."
        val pos = if (m.durationMs > 0) " — ${fmt(m.livePosition())} of ${fmt(m.durationMs)}" else ""
        return "${if (m.playing) "Playing" else "Paused"} in ${m.appName}: ${m.title}" + (if (m.artist.isNotBlank()) " by ${m.artist}" else "") + pos + "."
    }

    private fun mediaControl(action: String, s: VehicleState): Pair<String, Boolean> {
        val m = s.media ?: return "Nothing is playing to control." to true
        when (action) {
            "play" -> if (!m.playing) MediaSource.playPause()
            "pause" -> if (m.playing) MediaSource.playPause()
            "toggle" -> MediaSource.playPause()
            "next" -> MediaSource.forward()
            "previous" -> MediaSource.back()
            else -> return "Unknown action $action." to true
        }
        val what = when (action) {
            "next" -> if (m.longForm) "Jumped ahead 30 seconds" else "Skipped to the next track"
            "previous" -> if (m.longForm) "Went back 30 seconds" else "Went to the previous track"
            "play" -> "Playing"; "pause" -> "Paused"; else -> if (m.playing) "Paused" else "Playing"
        }
        return "$what in ${m.appName}." to false
    }

    private fun tripLiveText(): String {
        val t = Trips.live.value ?: return "No drive in progress."
        return "This drive: %.1f mi in %s, average %d mph, top %d mph".format(
            t.distanceMi, TripSummary.fmtDuration(t.totalSec), t.avgMph.roundToInt(), t.topMph.roundToInt()
        ) + (if (t.overLimitSec >= 5) ", ${TripSummary.fmtDuration(t.overLimitSec)} over the limit" else "") + "."
    }

    private fun tripText(t: TripSummary) = t.lines().joinToString(", ") { (k, v) -> "${k.lowercase()} $v" }

    private fun fmt(ms: Long): String {
        val t = ms / 1000; val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun ago(at: Long): String {
        val m = (System.currentTimeMillis() - at) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / 1440} days ago"
        }
    }
}
