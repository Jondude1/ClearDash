package com.jondude1.cleardash.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.content.res.ResourcesCompat
import com.jondude1.cleardash.R
import com.jondude1.cleardash.data.MediaInfo
import com.jondude1.cleardash.data.Prefs
import com.jondude1.cleardash.data.VehicleState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The car screen's views, in knob order. */
enum class DashMode(val title: String) { DASH("DRIVE"), GAUGES("SYSTEMS") }

/**
 * Draws every ClearDash view onto a plain Canvas. The Android Auto surface and the phone
 * preview share this code, so they always look the same.
 *
 * Look: the ATLAS "Stark" hologram — cyan light on near-black teal glass, cut-corner panels
 * with bright corner brackets, a faint grid, segmented reactor-style gauges, Space Mono
 * readouts and Sora text. Tuned for the 2018 CX-5's 7" 800×480 screen: the speed and the
 * next turn are the biggest things on screen, every label stays at or above arm's-length
 * size, and motion is slow and quiet so nothing pulls the eye off the road.
 */
class DashRenderer {

    object Pal {
        val GROUND = Color.parseColor("#02070A")
        val GROUND_2 = Color.parseColor("#041118")
        val GLASS = Color.parseColor("#D9041A24")
        val CYAN = Color.parseColor("#00E5FF")
        val CYAN_DIM = Color.parseColor("#9900E5FF")
        val CYAN_FAINT = Color.parseColor("#3300E5FF")
        val CYAN_GHOST = Color.parseColor("#1400E5FF")
        val CELL_OFF = Color.parseColor("#2200E5FF")
        val TEXT = Color.parseColor("#E8FBFF")
        val TEXT2 = Color.parseColor("#B5D6DE")
        val MUTED = Color.parseColor("#7C9CA6")
        val ORANGE = Color.parseColor("#FF5C00")   // ATLAS accent: over the limit
        val ALARM = Color.parseColor("#FF3B30")    // well over the limit
    }

    // Fonts: Space Mono for readouts and labels, Sora for words. System fallbacks until loadAssets().
    private var mono: Typeface = Typeface.MONOSPACE
    private var monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private var sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var sansLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private var sansSemi: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val holoFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.05f, 0.18f, 0.02f, 0f, 0f,
                0.20f, 0.72f, 0.07f, 0f, 40f,
                0.22f, 0.78f, 0.08f, 0f, 60f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    private val r = RectF()
    private val r2 = RectF()
    private val path = Path()
    private val clock = SimpleDateFormat("h:mm", Locale.US)
    private val ampm = SimpleDateFormat("a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE · MMM d", Locale.US)

    private var shownSpeed = 0f
    private val holoCar = HoloCar()
    private var lastMode: DashMode? = null
    private var modeChangedAt = 0L
    private var u = 1f // one layout unit ≈ 1% of the drawing height

    /** Cut-out photo of a blue 2018 CX-5; drawn as a hologram in the boot sequence. */
    var carPhoto: Bitmap? = null

    /** When set, the boot sequence plays from this moment (System.currentTimeMillis). */
    var introStart = 0L

    fun startIntro() {
        introStart = System.currentTimeMillis()
    }

    val introRunning: Boolean get() = introStart > 0 && System.currentTimeMillis() - introStart < INTRO_MS

    /** Loads the bundled fonts and the CX-5 photo. Safe to call more than once. */
    fun loadAssets(ctx: Context): DashRenderer {
        fun font(id: Int, fallback: Typeface) = try { ResourcesCompat.getFont(ctx, id) ?: fallback } catch (_: Exception) { fallback }
        mono = font(R.font.space_mono_regular, mono)
        monoBold = font(R.font.space_mono_bold, monoBold)
        sans = font(R.font.sora_regular, sans)
        sansLight = font(R.font.sora_light, sansLight)
        sansSemi = font(R.font.sora_semibold, sansSemi)
        if (carPhoto == null) {
            carPhoto = try { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.cx5_blue) } catch (_: Exception) { null }
        }
        return this
    }

    fun draw(c: Canvas, area: RectF, fullW: Int, fullH: Int, s: VehicleState, mode: DashMode, knobHint: Boolean = false) {
        drawGround(c, fullW.toFloat(), fullH.toFloat())

        val target = s.bestSpeed ?: 0f
        shownSpeed += (target - shownSpeed) * 0.22f
        if (area.width() < 10 || area.height() < 10) return
        u = min(area.height() / 100f, area.width() / 172f)

        val ie = if (introStart > 0) System.currentTimeMillis() - introStart else Long.MAX_VALUE
        if (ie < INTRO_HOLD_MS) {
            drawIntro(c, fullW.toFloat(), fullH.toFloat(), ie, s)
            return
        }

        val now = System.currentTimeMillis()
        if (mode != lastMode) {
            if (lastMode != null) modeChangedAt = now
            lastMode = mode
        }

        val stripH = 9f * u
        drawTopStrip(c, RectF(area.left, area.top, area.right, area.top + stripH), s, mode, knobHint)
        val body = RectF(area.left, area.top + stripH, area.right, area.bottom)

        // switching views: a quick fade with a scan line wiping down
        val t = ((now - modeChangedAt) / 260f).coerceIn(0f, 1f)
        val save = if (t < 1f) c.saveLayerAlpha(body, (255 * t).toInt()) else -1
        when (mode) {
            DashMode.DASH -> drawDrive(c, body, s)
            DashMode.GAUGES -> drawSystems(c, body, s)
        }
        if (save >= 0) {
            c.restoreToCount(save)
            val y = body.top + body.height() * t
            stroke.shader = null
            stroke.strokeCap = Paint.Cap.BUTT
            stroke.strokeWidth = 0.5f * u
            stroke.color = Pal.CYAN_DIM
            c.drawLine(body.left, y, body.right, y, stroke)
        }

        // the boot sequence dissolves into the dashboard
        if (ie < INTRO_MS) {
            val a = 1f - (ie - INTRO_HOLD_MS) / (INTRO_MS - INTRO_HOLD_MS).toFloat()
            val l = c.saveLayerAlpha(0f, 0f, fullW.toFloat(), fullH.toFloat(), (255 * a.coerceIn(0f, 1f)).toInt())
            drawIntro(c, fullW.toFloat(), fullH.toFloat(), INTRO_HOLD_MS.toLong(), s)
            c.restoreToCount(l)
        }
    }

    // ───────────────────────── ground ─────────────────────────

    private fun drawGround(c: Canvas, w: Float, h: Float) {
        fill.shader = RadialGradient(w * 0.3f, h * 0.45f, max(w, h) * 0.75f, Pal.GROUND_2, Pal.GROUND, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        // faint blueprint grid
        val step = max(h / 100f, 1f) * 6f
        stroke.shader = null
        stroke.strokeWidth = 1f
        stroke.color = Pal.CYAN_GHOST
        var x = step
        while (x < w) { c.drawLine(x, 0f, x, h, stroke); x += step }
        var y = step
        while (y < h) { c.drawLine(0f, y, w, y, stroke); y += step }
    }

    // ───────────────────────── top strip ─────────────────────────

    private fun drawTopStrip(c: Canvas, b: RectF, s: VehicleState, mode: DashMode, knobHint: Boolean) {
        val pad = 4.5f * u
        val y = b.centerY() + 1.4f * u
        var x = b.left + pad

        // wordmark
        fill.color = Pal.CYAN
        path.reset()
        path.moveTo(x, y + 0.2f * u); path.lineTo(x + 2.4f * u, y + 0.2f * u); path.lineTo(x + 2.4f * u, y - 2.2f * u); path.close()
        c.drawPath(path, fill)
        x += 3.6f * u
        label(c, "CLEARDASH", x, y, Pal.CYAN, 3.0f, bold = true)
        x += textW("CLEARDASH", 3.0f, bold = true) + 5f * u

        if (knobHint) {
            val blink = 0.55f + 0.45f * sin(SystemClock.uptimeMillis() / 260.0).toFloat()
            label(c, "◀  TURN THE KNOB TO SWITCH VIEW  ▶", x, y, withAlpha(Pal.CYAN, (255 * blink).toInt()), 3.0f, bold = true)
        } else {
            for (m in DashMode.entries) {
                val on = m == mode
                label(c, m.title, x, y, if (on) Pal.TEXT else Pal.MUTED, 3.0f, bold = on)
                val w = textW(m.title, 3.0f, bold = on)
                if (on) {
                    r.set(x - 1.4f * u, b.top + 1.4f * u, x + w + 0.6f * u, b.bottom - 1.0f * u)
                    brackets(c, r, 1.6f * u, 0.3f * u, Pal.CYAN)
                }
                x += w + 5f * u
            }
        }

        // right: link status + clock
        var rx = b.right - pad
        val now = Date()
        val t = clock.format(now)
        val ap = ampm.format(now)
        label(c, ap, rx, y, Pal.MUTED, 2.6f, right = true)
        rx -= textW(ap, 2.6f) + 1.2f * u
        label(c, t, rx, y, Pal.TEXT, 3.4f, bold = true, right = true)
        rx -= textW(t, 3.4f, bold = true) + 4.5f * u

        val links = listOf(
            "AUDIO" to (s.media != null),
            "OBD" to s.obd.connected,
            "NAV" to (s.nav != null),
            "GPS" to s.gpsOk,
        )
        for ((name, ok) in links) {
            val w = textW(name, 2.6f)
            label(c, name, rx, y, if (ok) Pal.TEXT2 else withAlpha(Pal.MUTED, 150), 2.6f, right = true)
            val dx = rx - w - 1.9f * u
            if (ok) {
                fill.color = Pal.CYAN
                c.drawRect(dx - 0.6f * u, y - 1.6f * u, dx + 0.6f * u, y - 0.4f * u, fill)
            } else {
                stroke.shader = null
                stroke.strokeWidth = 0.22f * u
                stroke.color = Pal.MUTED
                c.drawRect(dx - 0.6f * u, y - 1.6f * u, dx + 0.6f * u, y - 0.4f * u, stroke)
            }
            rx -= w + 5.2f * u
        }

        // hairline under the strip, brighter in the middle
        fill.shader = LinearGradient(b.left, 0f, b.right, 0f,
            intArrayOf(withAlpha(Pal.CYAN, 0), Pal.CYAN_DIM, withAlpha(Pal.CYAN, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(b.left, b.bottom - 0.25f * u, b.right, b.bottom, fill)
        fill.shader = null
    }

    // ───────────────────────── DRIVE ─────────────────────────

    private fun drawDrive(c: Canvas, a: RectF, s: VehicleState) {
        val p = 4.5f * u
        val inner = RectF(a.left + p, a.top + 2f * u, a.right - p, a.bottom - p)
        val ringSize = min(inner.height(), inner.width() * 0.44f)
        val ringBox = RectF(inner.left, inner.centerY() - ringSize / 2, inner.left + ringSize, inner.centerY() + ringSize / 2)
        drawReactor(c, ringBox, s)

        val right = RectF(ringBox.right + p, inner.top, inner.right, inner.bottom)
        if (right.width() < 30 * u) return
        holoPanel(c, right)

        val ip = 4.2f * u
        val content = RectF(right.left + ip, right.top + ip, right.right - ip, right.bottom - ip * 0.8f)
        var top = content.top

        val alert = s.activeAlerts.firstOrNull()
        if (alert != null) {
            val h = 9f * u
            drawAlert(c, RectF(content.left, top, content.right, top + h), alert.title, alert.message)
            top += h + 2.6f * u
        }

        val telH = 15.5f * u
        val tel = RectF(content.left, content.bottom - telH, content.right, content.bottom)
        val media = s.media
        val gap = 3f * u
        val avail = tel.top - gap - top
        val mediaH = if (media != null) min(avail * 0.46f, 21f * u) else 0f
        val navBox = RectF(content.left, top, content.right, top + avail - mediaH - (if (media != null) gap else 0f))
        drawNav(c, navBox, s)
        if (media != null) {
            divider(c, content.left, navBox.bottom + gap / 2, content.right)
            drawMedia(c, RectF(content.left, navBox.bottom + gap, content.right, navBox.bottom + gap + mediaH), media)
        }
        divider(c, content.left, tel.top - gap / 2, content.right)
        drawTelemetry(c, tel, s)
    }

    /** Speed as an arc reactor: segmented ring, slow outer orbit, big mono number in the core. */
    private fun drawReactor(c: Canvas, box: RectF, s: VehicleState) {
        val cx = box.centerX()
        val cy = box.centerY()
        val rad = box.width() / 2 - 2.5f * u
        val limit = s.speedLimitMph
        val speed = shownSpeed
        val maxSpeed = if (speed > 95) 140f else 100f
        val over = limit != null && speed > limit + 2
        val wayOver = limit != null && speed > limit + 9
        val hot = when { wayOver -> Pal.ALARM; over -> Pal.ORANGE; else -> Pal.CYAN }
        val tms = SystemClock.uptimeMillis()

        // core glow
        fill.shader = RadialGradient(cx, cy, max(rad, 1f), withAlpha(hot, 46), withAlpha(hot, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, fill)
        fill.shader = null

        // slow orbit: a dashed ring turning once a minute
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = 0.3f * u
        stroke.color = Pal.CYAN_FAINT
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        val rot = (tms % 60_000L) / 60_000f * 360f
        for (i in 0 until 24) c.drawArc(r, rot + i * 15f, 9f, false, stroke)
        fill.color = Pal.CYAN_DIM
        for (k in 0 until 3) {
            val ang = Math.toRadians((rot + k * 120f).toDouble())
            c.drawCircle(cx + (rad * cos(ang)).toFloat(), cy + (rad * sin(ang)).toFloat(), 0.7f * u, fill)
        }

        // segmented speed ring (270°, opening at the bottom)
        val segR = rad - 4.2f * u
        val start = 135f
        val sweep = 270f
        val cells = 45
        val step = sweep / cells
        val lit = ((speed / maxSpeed) * cells).coerceIn(0f, cells.toFloat())
        r.set(cx - segR, cy - segR, cx + segR, cy + segR)
        stroke.strokeWidth = 3.4f * u
        for (i in 0 until cells) {
            val v = (i + 0.5f) / cells * maxSpeed
            val isLit = i < lit.toInt() || (i == lit.toInt() && lit - i > 0.4f)
            stroke.color = when {
                !isLit -> Pal.CELL_OFF
                limit != null && v > limit + 9 -> Pal.ALARM
                limit != null && v > limit + 2 -> Pal.ORANGE
                else -> Pal.CYAN
            }
            c.drawArc(r, start + i * step + step * 0.14f, step * 0.72f, false, stroke)
        }
        // ticks + numerals inside the ring
        val tickR = segR - 3.4f * u
        for (v in 0..maxSpeed.toInt() step 20) {
            val ang = Math.toRadians(start + sweep * v / maxSpeed.toDouble())
            stroke.strokeWidth = 0.3f * u
            stroke.color = Pal.CYAN_DIM
            c.drawLine(cx + (tickR * cos(ang)).toFloat(), cy + (tickR * sin(ang)).toFloat(),
                cx + ((tickR - 1.6f * u) * cos(ang)).toFloat(), cy + ((tickR - 1.6f * u) * sin(ang)).toFloat(), stroke)
            if (v > 0 && v < maxSpeed.toInt()) {
                val nr = tickR - 4.4f * u
                label(c, v.toString(), cx + (nr * cos(ang)).toFloat(), cy + (nr * sin(ang)).toFloat() + 0.9f * u, Pal.MUTED, 2.6f, center = true)
            }
        }
        // speed-limit marker: a bright notch across the ring
        if (limit != null && limit <= maxSpeed) {
            val ang = Math.toRadians(start + sweep * limit / maxSpeed.toDouble())
            stroke.strokeWidth = 0.6f * u
            stroke.color = Pal.TEXT
            val ro = segR + 2.6f * u
            val ri = segR - 2.6f * u
            c.drawLine(cx + (ro * cos(ang)).toFloat(), cy + (ro * sin(ang)).toFloat(),
                cx + (ri * cos(ang)).toFloat(), cy + (ri * sin(ang)).toFloat(), stroke)
        }

        // inner core ring
        val coreR = segR * 0.64f
        fill.color = withAlpha(Pal.GROUND, 200)
        c.drawCircle(cx, cy, coreR, fill)
        stroke.strokeWidth = 0.3f * u
        stroke.color = Pal.CYAN_DIM
        c.drawCircle(cx, cy, coreR, stroke)

        // the number
        val shown = if (s.bestSpeed == null) "--" else speed.roundToInt().toString()
        text.textAlign = Paint.Align.CENTER
        text.textSize = fitSize(shown, coreR * 1.55f, coreR * 0.78f, coreR * 0.4f, monoBold)
        text.typeface = monoBold
        text.color = if (over) hot else Pal.TEXT
        c.drawText(shown, cx, cy + text.textSize * 0.30f, text)
        text.textAlign = Paint.Align.LEFT
        label(c, "MPH", cx, cy + coreR * 0.56f, Pal.CYAN, 2.8f, bold = true, center = true)

        // speed limit readout in the ring's open bottom
        val by = cy + segR * 0.86f
        if (limit != null) {
            val w = 15f * u
            val h = 11.5f * u
            r2.set(cx - w / 2, by - h / 2, cx + w / 2, by + h / 2)
            fill.color = if (over) withAlpha(hot, 60) else withAlpha(Pal.GROUND, 230)
            c.drawRect(r2, fill)
            stroke.strokeWidth = 0.25f * u
            stroke.color = if (over) hot else Pal.CYAN_DIM
            c.drawRect(r2, stroke)
            val box2 = RectF(r2)
            brackets(c, box2, 1.6f * u, 0.4f * u, if (over) hot else Pal.CYAN)
            label(c, "LIMIT", cx, box2.top + 3.5f * u, if (over) hot else Pal.MUTED, 2.4f, center = true)
            text.typeface = monoBold
            text.textAlign = Paint.Align.CENTER
            text.textSize = 6.2f * u
            text.color = if (over) hot else Pal.TEXT
            c.drawText(limit.toString(), cx, box2.bottom - 1.6f * u, text)
            text.textAlign = Paint.Align.LEFT
        } else {
            label(c, if (s.gpsOk) "NO LIMIT DATA" else "ACQUIRING GPS", cx, by + 1f * u, Pal.MUTED, 2.6f, center = true)
        }
    }

    private fun drawNav(c: Canvas, b: RectF, s: VehicleState) {
        val nav = s.nav?.takeIf { System.currentTimeMillis() - it.updatedAt < 5 * 60_000 }
        val h = b.height()
        if (nav != null) {
            tickHeader(c, "NEXT MANEUVER", b.left, b.top + 2.6f * u)
            val top = b.top + 5f * u
            val avail = b.bottom - top
            val d = min(avail * 0.92f, 19f * u)
            val box = RectF(b.left, top, b.left + d, top + d)
            fill.color = withAlpha(Pal.CYAN, 26)
            c.drawRect(box, fill)
            brackets(c, box, 2.2f * u, 0.45f * u, Pal.CYAN)
            nav.icon?.let { drawTinted(c, it, box, 0.18f) }

            val tx = box.right + 4f * u
            val tw = b.right - tx
            val dist = nav.distance.ifEmpty { "NEXT" }
            text.textAlign = Paint.Align.LEFT
            text.textSize = fitSize(dist, tw, min(9.5f * u, avail * 0.42f), 5f * u, monoBold)
            text.typeface = monoBold
            text.color = Pal.TEXT
            val distBase = top + text.textSize * 0.86f
            c.drawText(dist, tx, distBase, text)
            text.typeface = sans
            text.textSize = 4.3f * u
            text.color = Pal.TEXT2
            val lines = if (avail > 24f * u) 2 else 1
            wrap(c, nav.instruction, tx, distBase + 5.8f * u, tw, lines, 5.4f * u)
            if (nav.eta.isNotEmpty() && avail > 20f * u) {
                label(c, nav.eta.uppercase(), tx, b.bottom - 0.6f * u, Pal.CYAN_DIM, 2.6f, maxW = tw)
            }
        } else if (h >= 22f * u) {
            drawVehicleHolo(c, b, s)
        } else {
            val now = Date()
            tickHeader(c, "SYSTEM TIME", b.left, b.top + 2.6f * u)
            val clockSize = min(14f * u, h * 0.5f)
            text.typeface = sansLight
            text.textAlign = Paint.Align.LEFT
            text.textSize = clockSize
            text.color = Pal.TEXT
            val base = b.top + 5.5f * u + clockSize * 0.82f
            val t = clock.format(now)
            c.drawText(t, b.left - 0.4f * u, base, text)
            val tw = text.measureText(t)
            label(c, ampm.format(now), b.left + tw + 1.6f * u, base, Pal.CYAN, 3.2f, bold = true)
            if (h > 26f * u) {
                label(c, dateFmt.format(now).uppercase(), b.left, base + 5.6f * u, Pal.TEXT2, 2.9f)
            }
            if (h > 32f * u) {
                label(c, "NO ACTIVE ROUTE · START ONE IN GOOGLE MAPS", b.left, b.bottom - 0.6f * u, Pal.MUTED, 2.5f, maxW = b.width())
            }
        }
    }

    /** Idle Drive panel: the CX-5 as a live hologram, with heading and g-force readouts. */
    private fun drawVehicleHolo(c: Canvas, b: RectF, s: VehicleState) {
        tickHeader(c, Prefs.carName.uppercase() + " // LIVE", b.left, b.top + 2.6f * u)
        val colW = min(24f * u, b.width() * 0.30f)
        val carBox = RectF(b.left + colW, b.top + 3.5f * u, b.right, b.bottom - 4f * u)
        holoCar.update(s)
        holoCar.draw(c, carBox, u)

        val hdg = s.bearing
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val rows = listOf(
            "HEADING" to (if (hdg == null) "--" else "%03d° %s".format(hdg.roundToInt() % 360, dirs[(((hdg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)])),
            "LATERAL" to "%.2f G".format(abs(holoCar.latG)),
            "THRUST" to when {
                holoCar.brake > 0.4f -> "BRAKING"
                holoCar.glow > 0.25f -> "ACCEL"
                (s.bestSpeed ?: 0f) < 1f -> "PARKED"
                else -> "CRUISE"
            },
        )
        var y = b.top + 9.5f * u
        val step = min(9f * u, (b.bottom - 5f * u - y) / rows.size)
        for ((k, v) in rows) {
            label(c, k, b.left, y, Pal.MUTED, 2.4f)
            val col = when (v) { "BRAKING" -> Pal.ALARM; "ACCEL" -> Pal.CYAN; else -> Pal.TEXT }
            label(c, v, b.left, y + 4.2f * u, col, 3.2f, bold = true, maxW = colW - 1f * u)
            y += step
        }
        label(c, "NO ACTIVE ROUTE · START ONE IN GOOGLE MAPS", b.left, b.bottom - 0.6f * u, Pal.MUTED, 2.4f, maxW = b.width())
    }

    private fun drawMedia(c: Canvas, b: RectF, m: MediaInfo) {
        val artSize = b.height()
        val art = RectF(b.left, b.top, b.left + artSize, b.top + artSize)
        if (m.art != null) {
            c.save()
            c.clipRect(art)
            drawCover(c, m.art, art)
            c.restore()
            // faint scanlines over the art so it sits in the hologram
            stroke.shader = null
            stroke.strokeWidth = 1f
            stroke.color = withAlpha(Pal.GROUND, 70)
            var y = art.top
            while (y < art.bottom) { c.drawLine(art.left, y, art.right, y, stroke); y += 0.9f * u }
        } else {
            fill.color = withAlpha(Pal.CYAN, 26)
            c.drawRect(art, fill)
            text.typeface = monoBold
            text.textAlign = Paint.Align.CENTER
            text.textSize = artSize * 0.42f
            text.color = Pal.CYAN
            c.drawText(m.appName.take(1).uppercase(), art.centerX(), art.centerY() + text.textSize * 0.34f, text)
            text.textAlign = Paint.Align.LEFT
        }
        brackets(c, art, 1.8f * u, 0.4f * u, Pal.CYAN)

        val tx = art.right + 3.4f * u
        val tw = b.right - tx
        label(c, (if (m.playing) "▶ " else "❚❚ ") + m.appName.uppercase(), tx, b.top + 2.8f * u, if (m.playing) Pal.CYAN else Pal.MUTED, 2.6f, maxW = tw)
        text.typeface = sansSemi
        text.textAlign = Paint.Align.LEFT
        text.textSize = 4.3f * u
        text.color = Pal.TEXT
        c.drawText(fit(m.title.ifEmpty { m.appName }, tw), tx, b.top + 8.6f * u, text)
        if (b.height() > 15f * u && m.artist.isNotBlank()) {
            text.typeface = sans
            text.textSize = 3.5f * u
            text.color = Pal.TEXT2
            c.drawText(fit(m.artist, tw), tx, b.top + 13.2f * u, text)
        }

        if (m.durationMs > 0) {
            val pos = m.livePosition()
            val timeTxt = if (m.longForm) "-" + fmtTime(m.durationMs - pos) else fmtTime(pos)
            val tW = textW(timeTxt, 2.6f)
            val by = b.bottom - 1.2f * u
            label(c, timeTxt, b.right, by + 1.1f * u, Pal.MUTED, 2.6f, right = true)
            segBar(c, RectF(tx, by - 0.5f * u, b.right - tW - 2.4f * u, by + 0.6f * u), 28, pos.toFloat() / m.durationMs)
        }
    }

    private class Readout(val label: String, val value: String, val unit: String, val frac: Float?)

    /** Four live engine readouts — values only, no warnings (OBD warnings are parked). */
    private fun drawTelemetry(c: Canvas, b: RectF, s: VehicleState) {
        val o = s.obd
        val live = o.connected
        val items = listOf(
            Readout("RPM", if (live && o.rpm != null) "%,d".format(o.rpm) else "--", "", o.rpm?.let { it / 7000f }),
            Readout("COOLANT", if (live && o.coolantF != null) "${o.coolantF.roundToInt()}" else "--", "°F", o.coolantF?.let { (it - 100f) / 160f }),
            Readout("BATTERY", if (live && o.voltage != null) "%.1f".format(o.voltage) else "--", "V", o.voltage?.let { (it - 10f) / 6f }),
            Readout("FUEL", if (live && o.fuelPct != null) "${o.fuelPct}" else "--", "%", o.fuelPct?.let { it / 100f }),
        )
        val cw = b.width() / items.size
        items.forEachIndexed { i, it ->
            val x = b.left + cw * i + (if (i == 0) 0f else 2.6f * u)
            val w = cw - (if (i == 0) 2.6f else 5.2f) * u
            if (i > 0) {
                stroke.shader = null
                stroke.strokeWidth = 0.22f * u
                stroke.color = Pal.CYAN_FAINT
                c.drawLine(b.left + cw * i, b.top + 1f * u, b.left + cw * i, b.bottom - 1f * u, stroke)
            }
            label(c, it.label, x, b.top + 2.8f * u, Pal.MUTED, 2.6f, maxW = w)
            text.textAlign = Paint.Align.LEFT
            text.textSize = fitSize(it.value, w * 0.78f, 6.6f * u, 3.6f * u, monoBold)
            text.typeface = monoBold
            text.color = if (live) Pal.TEXT else Pal.MUTED
            val vb = b.top + 10.4f * u
            c.drawText(it.value, x, vb, text)
            val vw = text.measureText(it.value)
            if (live && it.unit.isNotEmpty()) {
                label(c, it.unit, x + vw + 0.8f * u, vb, Pal.CYAN_DIM, 2.4f)
            }
            val frac = if (live) it.frac?.coerceIn(0f, 1f) ?: 0f else 0f
            segBar(c, RectF(x, b.bottom - 1.4f * u, x + w, b.bottom - 0.2f * u), 10, frac)
        }
        if (!live) {
            label(c, o.status.uppercase(), b.right, b.top + 2.8f * u, Pal.MUTED, 2.4f, right = true, maxW = b.width() * 0.4f)
        }
    }

    private fun drawAlert(c: Canvas, b: RectF, title: String, message: String) {
        fill.color = withAlpha(Pal.CYAN, 30)
        c.drawRect(b, fill)
        brackets(c, b, 1.6f * u, 0.4f * u, Pal.CYAN)
        val x = b.left + 2.8f * u
        val by = b.centerY() + 1.2f * u
        val head = title.uppercase()
        label(c, head, x, by, Pal.CYAN, 3.0f, bold = true, maxW = b.width() * 0.48f)
        val hw = min(textW(head, 3.0f, bold = true), b.width() * 0.48f)
        text.typeface = sans
        text.textSize = 3.6f * u
        text.color = Pal.TEXT
        c.drawText(fit(message, b.right - x - hw - 5f * u), x + hw + 2.6f * u, by, text)
    }

    // ───────────────────────── SYSTEMS ─────────────────────────

    private class Gauge(
        val label: String, val value: Float?, val min: Float, val max: Float,
        val unit: String, val sub: String?, val format: (Float) -> String,
    )

    private fun drawSystems(c: Canvas, a: RectF, s: VehicleState) {
        val o = s.obd
        val gauges = listOf(
            Gauge("ENGINE SPEED", o.rpm?.toFloat(), 0f, 7000f, "RPM", null) { "%,d".format(it.roundToInt()) },
            Gauge("COOLANT", o.coolantF, 100f, 260f, "°F", null) { it.roundToInt().toString() },
            Gauge("BATTERY", o.voltage, 10f, 16f, "VOLTS", null) { "%.1f".format(it) },
            Gauge("ENGINE LOAD", o.load?.toFloat(), 0f, 100f, "%", null) { it.roundToInt().toString() },
            Gauge("INTAKE AIR", o.intakeF, 0f, 160f, "°F", null) { it.roundToInt().toString() },
            Gauge("FUEL", o.fuelPct?.toFloat(), 0f, 100f, "%",
                o.fuelPct?.let { "≈ %.1f GAL".format(Prefs.tankGal * it / 100f) }) { it.roundToInt().toString() },
        )
        val p = 4.5f * u
        val box = RectF(a.left + p, a.top + 2f * u, a.right - p, a.bottom - p)
        holoPanel(c, box)
        val inner = RectF(box.left + 2f * u, box.top + 2f * u, box.right - 2f * u, box.bottom - 2f * u)
        val cols = 3
        val rows = 2
        val cw = inner.width() / cols
        val ch = inner.height() / rows
        stroke.shader = null
        stroke.strokeWidth = 0.22f * u
        stroke.color = Pal.CYAN_FAINT
        for (i in 1 until cols) c.drawLine(inner.left + cw * i, inner.top + 3f * u, inner.left + cw * i, inner.bottom - 3f * u, stroke)
        c.drawLine(inner.left + 3f * u, inner.top + ch, inner.right - 3f * u, inner.top + ch, stroke)
        gauges.forEachIndexed { i, g ->
            val x = inner.left + (i % cols) * cw
            val y = inner.top + (i / cols) * ch
            drawGauge(c, RectF(x, y, x + cw, y + ch), g, o.connected, o.status)
        }
    }

    private fun drawGauge(c: Canvas, b: RectF, g: Gauge, live: Boolean, status: String) {
        tickHeader(c, g.label, b.left + 3f * u, b.top + 4.6f * u)
        val cx = b.centerX()
        val rad = min(b.width() * 0.30f, b.height() * 0.32f)
        val cy = b.top + b.height() * 0.58f
        val v = g.value
        val frac = if (live && v != null) ((v - g.min) / (g.max - g.min)).coerceIn(0f, 1f) else 0f
        segArc(c, cx, cy, rad, 135f, 270f, 27, frac, 1.9f * u)
        stroke.strokeWidth = 0.25f * u
        stroke.color = Pal.CYAN_FAINT
        c.drawCircle(cx, cy, rad - 3f * u, stroke)

        val shown = if (live && v != null) g.format(v) else "--"
        text.textAlign = Paint.Align.CENTER
        text.textSize = fitSize(shown, rad * 1.5f, rad * 0.56f, rad * 0.3f, monoBold)
        text.typeface = monoBold
        text.color = if (live && v != null) Pal.TEXT else Pal.MUTED
        c.drawText(shown, cx, cy + text.textSize * 0.32f, text)
        text.textAlign = Paint.Align.LEFT
        label(c, if (live) g.unit else status.uppercase(), cx, cy + rad * 0.66f, Pal.CYAN_DIM, 2.5f, center = true, maxW = b.width() - 6f * u)
        if (live && g.sub != null) label(c, g.sub, cx, cy + rad * 0.66f + 3.8f * u, Pal.TEXT2, 2.5f, center = true)
    }

    // ───────────────────────── BOOT ─────────────────────────

    private fun easeOut(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) * (1f - t) }
    private fun phase(e: Long, start: Int, end: Int) = easeOut((e - start) / (end - start).toFloat())

    /**
     * Boot: a ring traces itself, the CX-5 projects in as a cyan hologram behind a scan line,
     * the name types out, and a systems check reports what's actually linked.
     */
    fun drawIntro(c: Canvas, w: Float, h: Float, e: Long, s: VehicleState? = null) {
        val saveU = u
        u = max(min(h / 100f, w / 172f), 0.1f)
        drawGround(c, w, h)

        val ringIn = phase(e, 0, 700)
        val carIn = phase(e, 350, 1350)
        val nameIn = phase(e, 900, 1500)
        val checkIn = phase(e, 1100, 2000)

        val cx = w * 0.38f
        val cy = h * 0.44f
        val photo = carPhoto
        val ar = if (photo != null) photo.width.toFloat() / photo.height else 1.95f
        val carW = min(w * 0.50f, h * 0.48f * ar)
        val carH = carW / ar

        // projector ring under the car
        val ringR = carW * 0.42f
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.BUTT
        c.save()
        c.translate(cx, cy + carH * 0.42f)
        c.scale(1f, 0.22f)
        stroke.strokeWidth = 1.2f * u
        stroke.color = Pal.CYAN_FAINT
        c.drawCircle(0f, 0f, ringR, stroke)
        stroke.strokeWidth = 2.2f * u
        stroke.color = Pal.CYAN
        r.set(-ringR, -ringR, ringR, ringR)
        c.drawArc(r, -90f, 360f * ringIn, false, stroke)
        fill.shader = RadialGradient(0f, 0f, max(ringR, 1f), withAlpha(Pal.CYAN, (90 * carIn).toInt()), withAlpha(Pal.CYAN, 0), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, ringR, fill)
        fill.shader = null
        c.restore()

        // projection beam
        if (carIn > 0f) {
            path.reset()
            path.moveTo(cx - ringR * 0.9f, cy + carH * 0.42f)
            path.lineTo(cx + ringR * 0.9f, cy + carH * 0.42f)
            path.lineTo(cx + carW * 0.52f, cy - carH * 0.5f)
            path.lineTo(cx - carW * 0.52f, cy - carH * 0.5f)
            path.close()
            fill.shader = LinearGradient(0f, cy + carH * 0.42f, 0f, cy - carH * 0.5f,
                withAlpha(Pal.CYAN, (40 * carIn).toInt()), withAlpha(Pal.CYAN, 0), Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
        }

        // the car, as a hologram, revealed top-down by a scan line
        if (photo != null && carIn > 0f) {
            val dst = RectF(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
            val revealY = dst.top + dst.height() * carIn
            c.save()
            c.clipRect(dst.left - 2f, dst.top - 2f, dst.right + 2f, revealY)
            val flicker = if (e < 1500) (0.82f + 0.18f * sin(e / 23.0).toFloat()) else 1f
            bmpPaint.colorFilter = holoFilter
            bmpPaint.alpha = (215 * flicker).toInt()
            c.drawBitmap(photo, null, dst, bmpPaint)
            bmpPaint.colorFilter = null
            bmpPaint.alpha = 255
            stroke.strokeWidth = 1f
            stroke.color = withAlpha(Pal.GROUND, 120)
            var y = dst.top
            while (y < dst.bottom) { c.drawLine(dst.left, y, dst.right, y, stroke); y += max(2f, 0.7f * u) }
            c.restore()
            if (carIn < 1f) {
                stroke.strokeWidth = 0.5f * u
                stroke.color = Pal.CYAN
                c.drawLine(dst.left - 2f * u, revealY, dst.right + 2f * u, revealY, stroke)
            }
            r2.set(dst.left - 3f * u, dst.top - 3f * u, dst.right + 3f * u, dst.bottom + 1f * u)
            brackets(c, RectF(r2), 3f * u * carIn, 0.4f * u, withAlpha(Pal.CYAN, (255 * carIn).toInt()))
        }

        // name
        val name = "CLEARDASH"
        val sub = Prefs.carName.uppercase() + "  //  HUD ONLINE"
        val ty = cy + carH * 0.5f + 11f * u
        val typed = (name.length * nameIn).toInt()
        text.typeface = monoBold
        text.textAlign = Paint.Align.CENTER
        text.textSize = 6f * u
        text.letterSpacing = 0.5f
        text.color = Pal.TEXT
        c.drawText(name.take(typed), cx + text.letterSpacing * text.textSize / 2, ty, text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT
        val subTyped = (sub.length * phase(e, 1200, 1900)).toInt()
        label(c, sub.take(subTyped), cx, ty + 5f * u, Pal.CYAN, 2.8f, center = true)

        // systems check, right side
        val checks = listOf(
            "GPS" to (if (s == null) "READY" else if (s.gpsOk) "LOCK" else "SEARCH"),
            "NAV LINK" to (if (s?.nav != null) "ROUTE" else "STANDBY"),
            "AUDIO BUS" to (if (s?.media != null) "ACTIVE" else "IDLE"),
            "OBD-II" to (if (s == null) "READY" else if (s.obd.connected) "LINKED" else "OFFLINE"),
            "DISPLAY" to "ONLINE",
        )
        val lx = w * 0.70f
        val lw = w * 0.25f
        var ly = cy - carH * 0.40f
        tickHeader(c, "SYSTEMS CHECK", lx, ly)
        ly += 6f * u
        checks.forEachIndexed { i, (k, v) ->
            val show = checkIn * checks.size - i
            if (show <= 0f) return@forEachIndexed
            val a = (show.coerceIn(0f, 1f) * 255).toInt()
            label(c, k, lx, ly, withAlpha(Pal.TEXT2, a), 2.8f)
            val ok = v !in setOf("SEARCH", "OFFLINE")
            label(c, v, lx + lw, ly, withAlpha(if (ok) Pal.CYAN else Pal.MUTED, a), 2.8f, bold = true, right = true)
            fill.color = withAlpha(Pal.CYAN_FAINT, a)
            val dx0 = lx + textW(k, 2.8f) + 1.5f * u
            val dx1 = lx + lw - textW(v, 2.8f, bold = true) - 1.5f * u
            var dx = dx0
            while (dx < dx1) { c.drawRect(dx, ly - 0.9f * u, dx + 0.35f * u, ly - 0.55f * u, fill); dx += 1.3f * u }
            ly += 5.2f * u
        }
        u = saveU
    }

    // ───────────────────────── holo primitives ─────────────────────────

    /** ATLAS glass: dark teal fill with cut corners, a dim cyan edge, bright brackets, a slow scan line. */
    private fun holoPanel(c: Canvas, b: RectF) {
        val cut = 3.2f * u
        path.reset()
        path.moveTo(b.left + cut, b.top); path.lineTo(b.right - cut, b.top); path.lineTo(b.right, b.top + cut)
        path.lineTo(b.right, b.bottom - cut); path.lineTo(b.right - cut, b.bottom); path.lineTo(b.left + cut, b.bottom)
        path.lineTo(b.left, b.bottom - cut); path.lineTo(b.left, b.top + cut); path.close()
        fill.shader = null
        fill.color = Pal.GLASS
        c.drawPath(path, fill)
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = 0.25f * u
        stroke.color = Pal.CYAN_DIM
        c.drawPath(path, stroke)

        // scan line: one slow pass every 6 s, very faint
        val t = (SystemClock.uptimeMillis() % 6_000L) / 6_000f
        val y = b.top + b.height() * t
        val y0 = max(b.top, y - 4f * u)
        if (y > y0) {
            fill.shader = LinearGradient(0f, y0, 0f, y, withAlpha(Pal.CYAN, 0), withAlpha(Pal.CYAN, 22), Shader.TileMode.CLAMP)
            c.drawRect(b.left + cut, y0, b.right - cut, y, fill)
            fill.shader = null
        }

        // corner brackets sitting on the cut corners
        stroke.strokeCap = Paint.Cap.SQUARE
        stroke.strokeWidth = 0.55f * u
        stroke.color = Pal.CYAN
        val l = 4.2f * u
        c.drawLine(b.left + cut, b.top, b.left + cut + l, b.top, stroke); c.drawLine(b.left, b.top + cut, b.left, b.top + cut + l, stroke)
        c.drawLine(b.right - cut - l, b.top, b.right - cut, b.top, stroke); c.drawLine(b.right, b.top + cut, b.right, b.top + cut + l, stroke)
        c.drawLine(b.left + cut, b.bottom, b.left + cut + l, b.bottom, stroke); c.drawLine(b.left, b.bottom - cut - l, b.left, b.bottom - cut, stroke)
        c.drawLine(b.right - cut - l, b.bottom, b.right - cut, b.bottom, stroke); c.drawLine(b.right, b.bottom - cut - l, b.right, b.bottom - cut, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun brackets(c: Canvas, b: RectF, len: Float, width: Float, color: Int) {
        if (len <= 0f) return
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.SQUARE
        stroke.strokeWidth = width
        stroke.color = color
        c.drawLine(b.left, b.top, b.left + len, b.top, stroke); c.drawLine(b.left, b.top, b.left, b.top + len, stroke)
        c.drawLine(b.right - len, b.top, b.right, b.top, stroke); c.drawLine(b.right, b.top, b.right, b.top + len, stroke)
        c.drawLine(b.left, b.bottom, b.left + len, b.bottom, stroke); c.drawLine(b.left, b.bottom - len, b.left, b.bottom, stroke)
        c.drawLine(b.right - len, b.bottom, b.right, b.bottom, stroke); c.drawLine(b.right, b.bottom - len, b.right, b.bottom, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /** Section header: a short cyan tick, then the label in spaced mono. */
    private fun tickHeader(c: Canvas, s: String, x: Float, y: Float) {
        fill.shader = null
        fill.color = Pal.CYAN
        c.drawRect(x, y - 1.3f * u, x + 2.6f * u, y - 0.8f * u, fill)
        label(c, s, x + 3.8f * u, y, Pal.CYAN, 2.6f, bold = true)
    }

    private fun segArc(c: Canvas, cx: Float, cy: Float, rad: Float, start: Float, sweep: Float, cells: Int, frac: Float, thick: Float) {
        val step = sweep / cells
        val lit = (frac * cells).roundToInt()
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = thick
        for (i in 0 until cells) {
            stroke.color = if (i < lit) Pal.CYAN else Pal.CELL_OFF
            c.drawArc(r, start + i * step + step * 0.15f, step * 0.7f, false, stroke)
        }
    }

    /** A row of small cells, filled left to right, like a reactor power readout. */
    private fun segBar(c: Canvas, b: RectF, cells: Int, frac: Float) {
        val gap = max(0.4f * u, 1f)
        val cw = (b.width() - gap * (cells - 1)) / cells
        if (cw <= 0f) return
        val lit = (frac.coerceIn(0f, 1f) * cells).roundToInt()
        fill.shader = null
        for (i in 0 until cells) {
            val l = b.left + i * (cw + gap)
            fill.color = if (i < lit) Pal.CYAN else Pal.CELL_OFF
            c.drawRect(l, b.top, l + cw, b.bottom, fill)
        }
    }

    private fun divider(c: Canvas, x1: Float, y: Float, x2: Float) {
        fill.shader = LinearGradient(x1, 0f, x2, 0f, intArrayOf(Pal.CYAN_DIM, Pal.CYAN_FAINT, withAlpha(Pal.CYAN, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(x1, y - 0.12f * u, x2, y + 0.12f * u, fill)
        fill.shader = null
    }

    /** Spaced uppercase mono label. */
    private fun label(
        c: Canvas, s: String, x: Float, y: Float, color: Int, sizeU: Float,
        bold: Boolean = false, center: Boolean = false, right: Boolean = false, maxW: Float = Float.MAX_VALUE,
    ) {
        text.typeface = if (bold) monoBold else mono
        text.textSize = sizeU * u
        text.letterSpacing = 0.14f
        text.color = color
        text.textAlign = when { center -> Paint.Align.CENTER; right -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        c.drawText(fit(s, maxW), x, y, text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT
    }

    private fun textW(s: String, sizeU: Float, bold: Boolean = false): Float {
        text.typeface = if (bold) monoBold else mono
        text.textSize = sizeU * u
        text.letterSpacing = 0.14f
        val w = text.measureText(s)
        text.letterSpacing = 0f
        return w
    }

    private fun drawTinted(c: Canvas, b: Bitmap, box: RectF, inset: Float) {
        val i = box.width() * inset
        r2.set(box.left + i, box.top + i, box.right - i, box.bottom - i)
        val ar = b.width.toFloat() / max(1, b.height)
        if (ar > 1) { val h = r2.width() / ar; val d = (r2.height() - h) / 2; r2.top += d; r2.bottom -= d }
        else { val w = r2.height() * ar; val d = (r2.width() - w) / 2; r2.left += d; r2.right -= d }
        bmpPaint.colorFilter = PorterDuffColorFilter(Pal.TEXT, PorterDuff.Mode.SRC_IN)
        c.drawBitmap(b, null, r2, bmpPaint)
        bmpPaint.colorFilter = null
    }

    private fun drawCover(c: Canvas, b: Bitmap, box: RectF) {
        val bw = b.width.toFloat(); val bh = b.height.toFloat()
        val scale = max(box.width() / bw, box.height() / bh)
        val w = bw * scale; val h = bh * scale
        r2.set(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
        bmpPaint.colorFilter = null
        c.drawBitmap(b, null, r2, bmpPaint)
    }

    private fun fmtTime(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun fit(s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (text.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    /** Largest text size (≤ start, ≥ min) at which [s] fits in [maxW]. Leaves the typeface set. */
    private fun fitSize(s: String, maxW: Float, start: Float, min: Float, tf: Typeface): Float {
        text.typeface = tf
        text.letterSpacing = 0f
        var size = start
        text.textSize = size
        while (size > min && text.measureText(s) > maxW) {
            size *= 0.93f
            text.textSize = size
        }
        return size.coerceAtLeast(min)
    }

    private fun wrap(c: Canvas, s: String, x: Float, y: Float, maxW: Float, maxLines: Int, lineH: Float) {
        val words = s.split(' ').filter { it.isNotEmpty() }
        var line = ""
        var n = 0
        var i = 0
        while (i < words.size && n < maxLines) {
            val trial = if (line.isEmpty()) words[i] else "$line ${words[i]}"
            if (text.measureText(trial) <= maxW || line.isEmpty()) {
                line = trial; i++
            } else {
                val last = n == maxLines - 1
                c.drawText(if (last) fit("$line ${words.drop(i).joinToString(" ")}", maxW) else line, x, y + n * lineH, text)
                n++; line = ""
                if (last) return
            }
        }
        if (line.isNotEmpty() && n < maxLines) c.drawText(fit(line, maxW), x, y + n * lineH, text)
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    companion object {
        const val INTRO_HOLD_MS = 2400
        const val INTRO_MS = 3000
    }
}
