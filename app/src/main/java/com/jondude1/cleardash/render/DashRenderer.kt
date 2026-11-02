package com.jondude1.cleardash.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.jondude1.cleardash.data.MediaInfo
import com.jondude1.cleardash.data.Prefs
import com.jondude1.cleardash.data.Severity
import com.jondude1.cleardash.data.TireReading
import com.jondude1.cleardash.data.VehicleState
import com.jondude1.cleardash.data.Wheel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class DashMode(val title: String) { DASH("DRIVE"), GAUGES("GAUGES"), TIRES("TIRES") }

/**
 * Draws every ClearDash view onto a plain Canvas — the Android Auto surface and the
 * phone preview use this same code, so they look identical.
 *
 * Tuned for the 2018 CX-5's 7" 800×480 Mazda Connect screen: one restrained accent
 * (Soul Red), warm silver type, hairlines instead of heavy boxes, and nothing smaller
 * than is readable at arm's length.
 */
class DashRenderer {

    object Pal {
        val BG_TOP = Color.parseColor("#0C0D10")
        val BG_BOTTOM = Color.parseColor("#040405")
        val PANEL_TOP = Color.parseColor("#17191D")
        val PANEL_BOTTOM = Color.parseColor("#0E1013")
        val HAIRLINE = Color.parseColor("#2A2C31")
        val TRACK = Color.parseColor("#24262B")
        val TEXT = Color.parseColor("#F4F1EC")
        val TEXT2 = Color.parseColor("#CFCAC2")
        val SILVER = Color.parseColor("#9A968F")
        val DIM = Color.parseColor("#4A4B4F")
        val RED = Color.parseColor("#E0283A")     // Soul Red, bright
        val RED_DEEP = Color.parseColor("#7E0C17")  // Soul Red, shadow
        val AMBER = Color.parseColor("#F2A93B")
        val ALARM = Color.parseColor("#FF4D4D")
        val OK = Color.parseColor("#CFCAC2")
    }

    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val r2 = RectF()
    private val path = Path()
    private val clock = SimpleDateFormat("h:mm", Locale.US)
    private val ampm = SimpleDateFormat("a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.US)

    private var shownSpeed = 0f
    private var lastMode: DashMode? = null
    private var modeChangedAt = 0L
    private var u = 1f // one layout unit ≈ 1% of drawing height

    /** Cut-out photo of a blue 2018 CX-5 (set by whoever owns a Context). */
    var carPhoto: Bitmap? = null

    /** When set, the startup sequence plays from this moment (ms, System.currentTimeMillis). */
    var introStart = 0L

    fun startIntro() {
        introStart = System.currentTimeMillis()
    }

    val introRunning: Boolean get() = introStart > 0 && System.currentTimeMillis() - introStart < INTRO_MS

    fun draw(c: Canvas, area: RectF, fullW: Int, fullH: Int, s: VehicleState, mode: DashMode, knobHint: Boolean = false) {
        // background over the whole surface (also behind Android Auto's own chrome)
        fill.shader = LinearGradient(0f, 0f, 0f, fullH.toFloat(), Pal.BG_TOP, Pal.BG_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fullW.toFloat(), fullH.toFloat(), fill)
        fill.shader = null

        val target = s.bestSpeed ?: 0f
        shownSpeed += (target - shownSpeed) * 0.22f
        if (area.width() < 10 || area.height() < 10) return
        u = min(area.height() / 100f, area.width() / 172f)

        val ie = if (introStart > 0) System.currentTimeMillis() - introStart else Long.MAX_VALUE
        if (ie < INTRO_HOLD_MS) {
            drawIntro(c, fullW.toFloat(), fullH.toFloat(), ie)
            return
        }
        shownSpeed = if (ie < INTRO_MS) target else shownSpeed

        val now = System.currentTimeMillis()
        if (mode != lastMode) {
            if (lastMode != null) modeChangedAt = now
            lastMode = mode
        }

        val stripH = 9f * u
        drawTopStrip(c, RectF(area.left, area.top, area.right, area.top + stripH), s, mode, knobHint)
        val body = RectF(area.left, area.top + stripH, area.right, area.bottom)

        // gentle cross-fade when switching views
        val t = ((now - modeChangedAt) / 220f).coerceIn(0f, 1f)
        val save = if (t < 1f) c.saveLayerAlpha(body, (255 * t).toInt()) else -1
        when (mode) {
            DashMode.DASH -> drawDash(c, body, s)
            DashMode.GAUGES -> drawGauges(c, body, s)
            DashMode.TIRES -> drawTires(c, body, s)
        }
        if (save >= 0) c.restoreToCount(save)

        // the intro dissolves into the dashboard
        if (ie < INTRO_MS) {
            val a = 1f - (ie - INTRO_HOLD_MS) / (INTRO_MS - INTRO_HOLD_MS).toFloat()
            val l = c.saveLayerAlpha(0f, 0f, fullW.toFloat(), fullH.toFloat(), (255 * a.coerceIn(0f, 1f)).toInt())
            drawIntro(c, fullW.toFloat(), fullH.toFloat(), INTRO_HOLD_MS.toLong())
            c.restoreToCount(l)
        }
    }

    // ───────────────────────── STARTUP ─────────────────────────

    private fun easeOut(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) * (1f - t) }
    private fun phase(e: Long, start: Int, end: Int) = easeOut((e - start) / (end - start).toFloat())

    /**
     * Black stage → the blue CX-5 fades up under a soft spotlight → the name appears →
     * a Soul Red line draws outward. Works at any size (car screen or phone).
     */
    fun drawIntro(c: Canvas, w: Float, h: Float, e: Long) {
        c.drawColor(Color.BLACK)
        val unit = min(h / 100f, w / 100f)
        val carIn = phase(e, 0, 1100)
        val nameIn = phase(e, 700, 1500)
        val lineIn = phase(e, 1050, 1850)

        val photo = carPhoto
        val ar = if (photo != null) photo.width.toFloat() / photo.height else 1.95f
        val carW = min(w * 0.64f, h * 0.50f * ar)
        val carH = carW / ar
        val cx = w / 2
        val cy = h * 0.43f

        // spotlight
        fill.shader = RadialGradient(cx, cy, max(carW * 0.8f, 1f),
            withAlpha(Color.parseColor("#2A3550"), (150 * carIn).toInt()), withAlpha(Color.BLACK, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, carW * 0.8f, fill)
        fill.shader = null

        // ground shadow
        val gy = cy + carH * 0.47f
        c.save()
        c.scale(1f, 0.16f, cx, gy)
        fill.shader = RadialGradient(cx, gy, max(carW * 0.52f, 1f), withAlpha(Color.BLACK, (230 * carIn).toInt()), withAlpha(Color.BLACK, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, gy, carW * 0.52f, fill)
        fill.shader = null
        c.restore()

        if (photo != null) {
            val scale = 1.06f - 0.06f * carIn
            val dw = carW * scale; val dh = carH * scale
            r2.set(cx - dw / 2, cy - dh / 2 + (1f - carIn) * 2f * unit, cx + dw / 2, cy + dh / 2 + (1f - carIn) * 2f * unit)
            bmpPaint.colorFilter = null
            bmpPaint.alpha = (255 * carIn).toInt()
            c.drawBitmap(photo, null, r2, bmpPaint)
            bmpPaint.alpha = 255
        }

        // name
        val name = Prefs.carName.uppercase()
        text.textAlign = Paint.Align.CENTER
        text.typeface = light
        text.textSize = min(5.6f * unit, w * 0.05f)
        text.letterSpacing = 0.55f
        text.color = withAlpha(Pal.TEXT, (255 * nameIn).toInt())
        val ty = cy + carH * 0.5f + text.textSize * 2.2f
        // letterSpacing adds trailing space; nudge to optical center
        c.drawText(name, cx + text.letterSpacing * text.textSize / 2, ty, text)
        val nameW = text.measureText(name)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT

        // Soul Red line
        val lw = nameW * 1.1f * lineIn
        if (lw > 1f) {
            val ly = ty + text.textSize * 0.9f
            fill.shader = LinearGradient(cx - lw / 2, 0f, cx + lw / 2, 0f,
                intArrayOf(withAlpha(Pal.RED, 0), Pal.RED, withAlpha(Pal.RED, 0)), null, Shader.TileMode.CLAMP)
            c.drawRect(cx - lw / 2, ly, cx + lw / 2, ly + max(1.5f, unit * 0.35f), fill)
            fill.shader = null
        }
    }

    // ───────────────────────── top strip ─────────────────────────

    private fun drawTopStrip(c: Canvas, b: RectF, s: VehicleState, mode: DashMode, knobHint: Boolean) {
        val pad = 4.5f * u
        val y = b.centerY() + 1.6f * u
        text.typeface = medium
        text.textSize = 3.2f * u
        text.letterSpacing = 0.22f
        var x = b.left + pad
        if (knobHint) {
            text.color = Pal.TEXT
            c.drawText("TURN THE KNOB TO SWITCH VIEWS", x, y, text)
        } else {
            for (m in DashMode.entries) {
                val active = m == mode
                text.color = if (active) Pal.TEXT else withAlpha(Pal.SILVER, 150)
                c.drawText(m.title, x, y, text)
                val w = text.measureText(m.title)
                if (active) {
                    fill.color = Pal.RED
                    r.set(x, y + 1.6f * u, x + w - text.letterSpacing * text.textSize, y + 2.1f * u)
                    c.drawRoundRect(r, 0.3f * u, 0.3f * u, fill)
                }
                x += w + 4.5f * u
            }
        }

        // status, right aligned
        val items = listOf(
            "GPS" to s.gpsOk,
            "OBD" to s.obd.connected,
            "TIRES" to s.tires.values.any { System.currentTimeMillis() - it.seenAt < 120_000 },
            "HOME" to (s.haStatus == "Connected"),
        )
        text.typeface = medium
        text.textSize = 2.8f * u
        text.letterSpacing = 0.18f
        var rx = b.right - pad
        for ((label, ok) in items.reversed()) {
            val w = text.measureText(label)
            text.color = if (ok) Pal.SILVER else withAlpha(Pal.SILVER, 80)
            c.drawText(label, rx - w, y, text)
            fill.color = if (ok) Pal.OK else Pal.DIM
            c.drawCircle(rx - w - 1.8f * u, y - 0.95f * u, 0.65f * u, fill)
            rx -= w + 5.4f * u
        }
        text.letterSpacing = 0f
    }

    // ───────────────────────── DRIVE ─────────────────────────

    private fun drawDash(c: Canvas, a: RectF, s: VehicleState) {
        val p = 4.5f * u
        val inner = RectF(a.left + p, a.top + 1f * u, a.right - p, a.bottom - p)
        val ringSize = min(inner.height(), inner.width() * 0.43f)
        val ringBox = RectF(inner.left, inner.centerY() - ringSize / 2, inner.left + ringSize, inner.centerY() + ringSize / 2)
        drawSpeedDial(c, ringBox, s)

        val right = RectF(ringBox.right + p, inner.top, inner.right, inner.bottom)
        if (right.width() < 30 * u) return
        panel(c, right)

        val ip = 4f * u
        val content = RectF(right.left + ip, right.top + ip * 0.8f, right.right - ip, right.bottom - ip * 0.6f)

        // alert takes the top of the panel
        val alert = s.activeAlerts.maxByOrNull { it.severity.ordinal }
        var top = content.top
        if (alert != null) {
            val h = 9.5f * u
            drawAlert(c, RectF(content.left, top, content.right, top + h), alert.title, alert.message, alert.severity)
            top += h + 2.4f * u
        }

        val statsH = 17f * u
        val statsBox = RectF(content.left, content.bottom - statsH, content.right, content.bottom)
        val media = s.media
        val avail = statsBox.top - top - 2 * 2.4f * u
        val mediaH = if (media != null) min(avail * 0.44f, 22f * u) else 0f
        val navBox = RectF(content.left, top, content.right, top + avail - mediaH - (if (media != null) 2.4f * u else 0f))
        drawNav(c, navBox, s)

        if (media != null) {
            hairline(c, content.left, navBox.bottom + 1.2f * u, content.right)
            drawMedia(c, RectF(content.left, navBox.bottom + 2.4f * u, content.right, navBox.bottom + 2.4f * u + mediaH), media)
        }
        hairline(c, content.left, statsBox.top - 2.4f * u, content.right)
        drawStats(c, statsBox, s)
    }

    private fun drawSpeedDial(c: Canvas, box: RectF, s: VehicleState) {
        val cx = box.centerX()
        val cy = box.centerY() + box.height() * 0.03f
        val rad = box.width() / 2 - 3.5f * u
        val limit = s.speedLimitMph
        val speed = shownSpeed
        val maxSpeed = if (speed > 95) 140f else 100f
        val over = limit != null && speed > limit + 2
        val wayOver = limit != null && speed > limit + 9
        val hot = if (wayOver) Pal.ALARM else if (over) Pal.AMBER else Pal.RED

        // ambient glow behind the dial
        val glowA = (24 + min(speed, 80f) * 0.5f).toInt()
        fill.shader = RadialGradient(cx, cy, rad * 1.1f, withAlpha(hot, glowA), withAlpha(hot, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 1.1f, fill)
        fill.shader = null

        // inner disc
        fill.shader = RadialGradient(cx, cy - rad * 0.3f, rad, Color.parseColor("#1A1C20"), Color.parseColor("#08090B"), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 0.80f, fill)
        fill.shader = null
        stroke.strokeWidth = 0.25f * u
        stroke.color = Pal.HAIRLINE
        c.drawCircle(cx, cy, rad * 0.80f, stroke)

        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        stroke.strokeWidth = 1.0f * u
        stroke.color = Pal.TRACK
        c.drawArc(r, 150f, 240f, false, stroke)

        // ticks + numerals
        text.typeface = regular
        text.textSize = 2.9f * u
        text.textAlign = Paint.Align.CENTER
        val steps = (maxSpeed / 10).toInt()
        for (i in 0..steps) {
            val v = i * 10
            val ang = Math.toRadians(150.0 + 240.0 * v / maxSpeed)
            val major = (v % 20 == 0)
            val ro = rad - 2.6f * u
            val ri = rad - (if (major) 4.6f else 3.6f) * u
            stroke.strokeWidth = (if (major) 0.45f else 0.3f) * u
            stroke.color = if (major) Pal.SILVER else withAlpha(Pal.SILVER, 110)
            c.drawLine(cx + (ro * cos(ang)).toFloat(), cy + (ro * sin(ang)).toFloat(),
                cx + (ri * cos(ang)).toFloat(), cy + (ri * sin(ang)).toFloat(), stroke)
            if (major && v > 0 && v < maxSpeed) {
                val rn = rad - 8.0f * u
                text.color = withAlpha(Pal.SILVER, 170)
                c.drawText(v.toString(), cx + (rn * cos(ang)).toFloat(), cy + (rn * sin(ang)).toFloat() + 1.0f * u, text)
            }
        }

        // speed-limit notch on the bezel
        if (limit != null && limit <= maxSpeed) {
            val ang = Math.toRadians(150.0 + 240.0 * limit / maxSpeed)
            stroke.strokeWidth = 0.6f * u
            stroke.color = Pal.TEXT
            val ro = rad + 1.8f * u
            val ri = rad - 1.8f * u
            c.drawLine(cx + (ro * cos(ang)).toFloat(), cy + (ro * sin(ang)).toFloat(),
                cx + (ri * cos(ang)).toFloat(), cy + (ri * sin(ang)).toFloat(), stroke)
        }

        // progress arc: soft bloom underneath, crisp line on top, bright tip
        val sweep = (240f * (speed / maxSpeed)).coerceIn(0f, 240f)
        if (sweep > 0.3f) {
            stroke.color = withAlpha(hot, 55)
            stroke.strokeWidth = 4.2f * u
            c.drawArc(r, 150f, sweep, false, stroke)
            stroke.shader = LinearGradient(r.left, r.bottom, r.right, r.top,
                if (over) hot else Pal.RED_DEEP, hot, Shader.TileMode.CLAMP)
            stroke.strokeWidth = 1.6f * u
            c.drawArc(r, 150f, sweep, false, stroke)
            stroke.shader = null
            val ang = Math.toRadians(150.0 + sweep)
            val tx = cx + (rad * cos(ang)).toFloat()
            val ty = cy + (rad * sin(ang)).toFloat()
            fill.color = withAlpha(hot, 90)
            c.drawCircle(tx, ty, 2.4f * u, fill)
            fill.color = Color.WHITE
            c.drawCircle(tx, ty, 1.0f * u, fill)
        }

        // the number
        val shown = if (s.bestSpeed == null) "––" else speed.roundToInt().toString()
        text.typeface = thin
        text.textSize = rad * 0.70f
        text.color = if (wayOver) Pal.ALARM else if (over) Pal.AMBER else Pal.TEXT
        c.drawText(shown, cx, cy + text.textSize * 0.30f, text)
        text.typeface = medium
        text.textSize = 3.0f * u
        text.letterSpacing = 0.35f
        text.color = Pal.SILVER
        c.drawText("MPH", cx + text.letterSpacing * text.textSize / 2, cy + rad * 0.36f, text)
        text.letterSpacing = 0f

        // speed limit sign, nested in the dial's open bottom
        if (limit != null) {
            val w = 11f * u
            val h = 13.6f * u
            r2.set(cx - w / 2, cy + rad * 0.60f, cx + w / 2, cy + rad * 0.60f + h)
            if (r2.bottom > box.bottom + 3f * u) r2.offset(0f, box.bottom + 3f * u - r2.bottom)
            if (over) {
                fill.color = withAlpha(hot, 110)
                r.set(r2.left - 1f * u, r2.top - 1f * u, r2.right + 1f * u, r2.bottom + 1f * u)
                c.drawRoundRect(r, 2.2f * u, 2.2f * u, fill)
            }
            fill.color = Color.parseColor("#F7F5F0")
            c.drawRoundRect(r2, 1.5f * u, 1.5f * u, fill)
            stroke.strokeWidth = 0.4f * u
            stroke.color = Color.parseColor("#111111")
            r.set(r2.left + 0.8f * u, r2.top + 0.8f * u, r2.right - 0.8f * u, r2.bottom - 0.8f * u)
            c.drawRoundRect(r, 1.0f * u, 1.0f * u, stroke)
            text.color = Color.parseColor("#111111")
            text.typeface = condensed
            text.textSize = 2.2f * u
            c.drawText("SPEED", r2.centerX(), r2.top + 3.6f * u, text)
            c.drawText("LIMIT", r2.centerX(), r2.top + 5.9f * u, text)
            text.textSize = 6.4f * u
            c.drawText(limit.toString(), r2.centerX(), r2.bottom - 1.8f * u, text)
        } else if (!s.gpsOk) {
            text.typeface = medium
            text.textSize = 2.8f * u
            text.letterSpacing = 0.2f
            text.color = Pal.SILVER
            c.drawText("ACQUIRING GPS", cx, cy + rad * 0.66f, text)
            text.letterSpacing = 0f
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawNav(c: Canvas, b: RectF, s: VehicleState) {
        val nav = s.nav?.takeIf { System.currentTimeMillis() - it.updatedAt < 5 * 60_000 }
        val h = b.height()
        if (nav != null) {
            val d = min(h * 0.74f, 19f * u)
            val icx = b.left + d / 2
            val icy = b.top + min(d / 2 + 0.6f * u, h / 2)
            fill.color = Color.parseColor("#202227")
            c.drawCircle(icx, icy, d / 2, fill)
            stroke.strokeWidth = 0.45f * u
            stroke.color = Pal.RED
            c.drawCircle(icx, icy, d / 2, stroke)
            r.set(icx - d / 2, icy - d / 2, icx + d / 2, icy + d / 2)
            nav.icon?.let { drawTinted(c, it, r, 0.22f) }

            val tx = b.left + d + 4f * u
            val tw = b.right - tx
            val distSize = min(10.5f * u, h * 0.42f)
            text.typeface = light
            text.textSize = distSize
            text.color = Pal.TEXT
            val distBase = b.top + distSize * 0.86f
            c.drawText(fit(nav.distance.ifEmpty { "Next" }, tw), tx, distBase, text)
            text.typeface = regular
            text.textSize = 4.3f * u
            text.color = Pal.TEXT2
            val lines = if (h > 26f * u) 2 else 1
            wrap(c, nav.instruction, tx, distBase + 6.0f * u, tw, lines, 5.6f * u)
            if (nav.eta.isNotEmpty() && h > 22f * u) {
                caps(c, nav.eta.uppercase(), tx, b.bottom - 0.4f * u, tw, Pal.SILVER)
            }
        } else {
            val now = Date()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val greet = when (hour) { in 5..11 -> "GOOD MORNING"; in 12..16 -> "GOOD AFTERNOON"; else -> "GOOD EVENING" }
            caps(c, greet, b.left, b.top + 3.2f * u, b.width(), Pal.SILVER)
            val clockSize = min(14f * u, h * 0.52f)
            text.typeface = thin
            text.textSize = clockSize
            text.color = Pal.TEXT
            val base = b.top + 4.8f * u + clockSize * 0.84f
            val t = clock.format(now)
            c.drawText(t, b.left - 0.4f * u, base, text)
            val tw = text.measureText(t)
            text.typeface = medium
            text.textSize = 3.6f * u
            text.color = Pal.SILVER
            c.drawText(ampm.format(now), b.left + tw + 1.4f * u, base, text)
            if (h > 26f * u) {
                text.typeface = regular
                text.textSize = 3.8f * u
                text.color = Pal.TEXT2
                c.drawText(dateFmt.format(now), b.left, base + 6.0f * u, text)
            }
        }
    }

    private fun drawMedia(c: Canvas, b: RectF, m: MediaInfo) {
        val artSize = b.height()
        val art = RectF(b.left, b.top, b.left + artSize, b.top + artSize)
        val radius = 1.8f * u
        if (m.art != null) {
            c.save()
            path.reset()
            path.addRoundRect(art, radius, radius, Path.Direction.CW)
            c.clipPath(path)
            drawCover(c, m.art, art)
            c.restore()
        } else {
            fill.shader = LinearGradient(art.left, art.top, art.right, art.bottom, Pal.RED_DEEP, Color.parseColor("#2A0A0E"), Shader.TileMode.CLAMP)
            c.drawRoundRect(art, radius, radius, fill)
            fill.shader = null
            text.typeface = light
            text.textAlign = Paint.Align.CENTER
            text.textSize = artSize * 0.46f
            text.color = Pal.TEXT
            c.drawText(m.appName.take(1).uppercase(), art.centerX(), art.centerY() + text.textSize * 0.36f, text)
            text.textAlign = Paint.Align.LEFT
        }

        val tx = art.right + 3.2f * u
        val tw = b.right - tx - 6f * u
        caps(c, m.appName.uppercase(), tx, b.top + 2.6f * u, tw, Pal.SILVER)
        text.typeface = medium
        text.textSize = 4.4f * u
        text.color = Pal.TEXT
        c.drawText(fit(m.title.ifEmpty { m.appName }, tw), tx, b.top + 8.4f * u, text)
        if (b.height() > 15f * u && m.artist.isNotBlank()) {
            text.typeface = regular
            text.textSize = 3.6f * u
            text.color = Pal.TEXT2
            c.drawText(fit(m.artist, tw), tx, b.top + 13.2f * u, text)
        }

        // play state glyph
        val gx = b.right - 2.2f * u
        val gy = b.top + 7f * u
        fill.color = if (m.playing) Pal.TEXT else Pal.SILVER
        if (m.playing) {
            r2.set(gx - 1.5f * u, gy - 2.1f * u, gx - 0.55f * u, gy + 2.1f * u); c.drawRoundRect(r2, 0.3f * u, 0.3f * u, fill)
            r2.set(gx + 0.55f * u, gy - 2.1f * u, gx + 1.5f * u, gy + 2.1f * u); c.drawRoundRect(r2, 0.3f * u, 0.3f * u, fill)
        } else {
            path.reset()
            path.moveTo(gx - 1.4f * u, gy - 2.3f * u); path.lineTo(gx + 2.0f * u, gy); path.lineTo(gx - 1.4f * u, gy + 2.3f * u); path.close()
            c.drawPath(path, fill)
        }

        if (m.durationMs > 0) {
            val pos = m.livePosition()
            val by = b.bottom - 0.6f * u
            val timeTxt = if (m.longForm) "−" + fmtTime(m.durationMs - pos) else fmtTime(pos)
            text.typeface = medium
            text.textSize = 2.8f * u
            text.color = Pal.SILVER
            val timeW = text.measureText(timeTxt)
            val barRight = b.right - timeW - 2.4f * u
            c.drawText(timeTxt, b.right - timeW, by + 1.0f * u, text)
            stroke.shader = null
            stroke.strokeWidth = 0.55f * u
            stroke.color = Pal.TRACK
            c.drawLine(tx, by, barRight, by, stroke)
            val frac = (pos.toFloat() / m.durationMs).coerceIn(0f, 1f)
            if (frac > 0f) {
                stroke.color = Pal.RED
                c.drawLine(tx, by, tx + (barRight - tx) * frac, by, stroke)
            }
        }
    }

    private class Stat(val label: String, val value: String, val sub: String, val color: Int)

    private fun drawStats(c: Canvas, b: RectF, s: VehicleState) {
        val o = s.obd
        val now = System.currentTimeMillis()

        // tires: show the lowest one
        val paired = Wheel.entries.filter { Prefs.tireId(it) != null }
        val fresh = paired.mapNotNull { w -> s.tires[w]?.takeIf { now - it.seenAt < 10 * 60_000 }?.let { w to it } }
        val tire = when {
            paired.isEmpty() -> Stat("TIRES", "—", "NOT PAIRED", Pal.SILVER)
            fresh.isEmpty() -> Stat("TIRES", "—", "WAITING", Pal.SILVER)
            else -> {
                val (w, low) = fresh.minBy { it.second.psi }
                val col = tireColor(low)
                Stat("TIRES", low.psi.roundToInt().toString(),
                    if (col == Pal.TEXT) (if (fresh.size == paired.size) "ALL ${paired.size} OK" else "${fresh.size} OF ${paired.size}") else "${w.short} LOW",
                    col)
            }
        }
        val live = o.connected
        val coolant = o.coolantF
        val volts = o.voltage
        val rpm = o.rpm ?: 0
        val fuel = o.fuelPct
        val stats = listOf(
            tire,
            Stat("COOLANT", if (live && coolant != null) "${coolant.roundToInt()}°" else "—",
                when { !live -> "OBD OFF"; coolant == null -> ""; coolant < 160 -> "WARMING"; coolant >= 230 -> "HOT"; else -> "NORMAL" },
                if (live && coolant != null && coolant >= 230) Pal.ALARM else Pal.TEXT),
            Stat("BATTERY", if (live && volts != null) "%.1f".format(volts) else "—",
                when { !live || volts == null -> if (!live) "OBD OFF" else ""; rpm > 600 && volts < 13f -> "LOW CHARGE"; rpm > 600 -> "CHARGING"; volts < 11.9f -> "LOW"; else -> "RESTING" },
                if (live && volts != null && ((rpm > 600 && volts < 13f) || volts < 11.9f)) Pal.AMBER else Pal.TEXT),
            Stat("FUEL", if (live && fuel != null) "$fuel%" else "—",
                if (live && fuel != null) "≈ %.1f GAL".format(Prefs.tankGal * fuel / 100f) else if (!live) "OBD OFF" else "",
                if (live && fuel != null && fuel < 15) Pal.AMBER else Pal.TEXT),
        )
        val cw = b.width() / stats.size
        stats.forEachIndexed { i, st ->
            val x = b.left + cw * i + (if (i == 0) 0f else 2.6f * u)
            if (i > 0) {
                stroke.strokeWidth = 0.22f * u
                stroke.color = Pal.HAIRLINE
                c.drawLine(b.left + cw * i, b.top + 1f * u, b.left + cw * i, b.bottom - 0.5f * u, stroke)
            }
            val w = cw - 3.6f * u
            caps(c, st.label, x, b.top + 3.2f * u, w, Pal.SILVER)
            text.typeface = light
            text.textSize = min(7.4f * u, w * 0.36f)
            text.color = st.color
            c.drawText(fit(st.value, w), x - 0.3f * u, b.top + 11.2f * u, text)
            if (st.sub.isNotEmpty()) caps(c, st.sub, x, b.bottom - 0.4f * u, w, if (st.color == Pal.TEXT) withAlpha(Pal.SILVER, 200) else st.color, 2.5f)
        }
    }

    private fun drawAlert(c: Canvas, b: RectF, title: String, message: String, sev: Severity) {
        val col = when (sev) { Severity.CRITICAL -> Pal.ALARM; Severity.WARN -> Pal.AMBER; else -> Pal.TEXT2 }
        val pulse = if (sev == Severity.CRITICAL) (0.75f + 0.25f * sin(System.currentTimeMillis() / 180.0).toFloat()) else 1f
        fill.color = withAlpha(col, (40 * pulse).toInt())
        c.drawRoundRect(b, 2f * u, 2f * u, fill)
        fill.color = col
        r2.set(b.left, b.top, b.left + 1.0f * u, b.bottom)
        c.drawRoundRect(r2, 0.5f * u, 0.5f * u, fill)
        text.typeface = medium
        text.textSize = 3.8f * u
        text.color = col
        val x = b.left + 3.4f * u
        val by = b.centerY() + 1.3f * u
        val head = fit(title, b.width() * 0.48f)
        c.drawText(head, x, by, text)
        val hw = text.measureText(head)
        text.typeface = regular
        text.color = Pal.TEXT
        c.drawText(fit(message, b.right - x - hw - 5f * u), x + hw + 2.6f * u, by, text)
    }

    // ───────────────────────── GAUGES ─────────────────────────

    private class Gauge(
        val label: String, val value: Float?, val min: Float, val max: Float,
        val unit: String, val sub: String?, val format: (Float) -> String, val warn: (Float) -> Int?,
    )

    private fun drawGauges(c: Canvas, a: RectF, s: VehicleState) {
        val o = s.obd
        val gauges = listOf(
            // 2.5L Skyactiv-G: redline ≈ 6,500 rpm
            Gauge("ENGINE SPEED", o.rpm?.toFloat(), 0f, 7000f, "RPM", null, { "%,d".format(it.roundToInt()) }) { if (it > 6200) Pal.ALARM else null },
            Gauge("COOLANT", o.coolantF, 100f, 260f, "°F", null, { it.roundToInt().toString() }) { if (it >= 230) Pal.ALARM else if (it >= 222) Pal.AMBER else null },
            Gauge("BATTERY", o.voltage, 10f, 16f, "VOLTS", null, { "%.1f".format(it) }) { if (it < 12f || it > 15f) Pal.AMBER else null },
            Gauge("ENGINE LOAD", o.load?.toFloat(), 0f, 100f, "%", null, { it.roundToInt().toString() }) { null },
            Gauge("INTAKE AIR", o.intakeF, 0f, 160f, "°F", null, { it.roundToInt().toString() }) { if (it > 130) Pal.AMBER else null },
            Gauge("FUEL", o.fuelPct?.toFloat(), 0f, 100f, "%",
                o.fuelPct?.let { "≈ %.1f GAL".format(Prefs.tankGal * it / 100f) }, { it.roundToInt().toString() }) { if (it < 15) Pal.AMBER else null },
        )
        val p = 4.5f * u
        val box = RectF(a.left + p, a.top + 1f * u, a.right - p, a.bottom - p)
        panel(c, box)
        val cols = 3
        val rows = 2
        val cw = box.width() / cols
        val ch = box.height() / rows
        stroke.strokeWidth = 0.22f * u
        stroke.color = Pal.HAIRLINE
        for (i in 1 until cols) c.drawLine(box.left + cw * i, box.top + 3f * u, box.left + cw * i, box.bottom - 3f * u, stroke)
        c.drawLine(box.left + 3f * u, box.top + ch, box.right - 3f * u, box.top + ch, stroke)
        gauges.forEachIndexed { i, g ->
            val x = box.left + (i % cols) * cw
            val y = box.top + (i / cols) * ch
            drawGauge(c, RectF(x, y, x + cw, y + ch), g, o.connected, o.status)
        }
    }

    private fun drawGauge(c: Canvas, b: RectF, g: Gauge, live: Boolean, status: String) {
        caps(c, g.label, b.left + 3.6f * u, b.top + 5.4f * u, b.width() - 7f * u, Pal.SILVER)
        val cx = b.centerX()
        val rad = min(b.width() * 0.30f, b.height() * 0.33f)
        val cy = b.top + b.height() * 0.58f
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        stroke.shader = null
        stroke.strokeWidth = 0.8f * u
        stroke.color = Pal.TRACK
        c.drawArc(r, 135f, 270f, false, stroke)
        val v = g.value
        val warn = if (v != null) g.warn(v) else null
        if (live && v != null) {
            val frac = ((v - g.min) / (g.max - g.min)).coerceIn(0f, 1f)
            val col = warn ?: Pal.RED
            stroke.color = withAlpha(col, 55)
            stroke.strokeWidth = 3.2f * u
            if (frac > 0.005f) c.drawArc(r, 135f, 270f * frac, false, stroke)
            stroke.shader = LinearGradient(r.left, r.bottom, r.right, r.top, warn ?: Pal.RED_DEEP, col, Shader.TileMode.CLAMP)
            stroke.strokeWidth = 1.3f * u
            if (frac > 0.005f) c.drawArc(r, 135f, 270f * frac, false, stroke)
            stroke.shader = null
        }
        text.textAlign = Paint.Align.CENTER
        text.typeface = thin
        text.textSize = rad * 0.62f
        text.color = if (live && v != null) (warn ?: Pal.TEXT) else Pal.DIM
        c.drawText(if (live && v != null) g.format(v) else "—", cx, cy + text.textSize * 0.32f, text)
        val unitTxt = if (live) g.unit else status.uppercase()
        text.typeface = medium
        text.textSize = 2.7f * u
        text.letterSpacing = 0.2f
        text.color = Pal.SILVER
        c.drawText(fit(unitTxt, b.width() - 6f * u), cx, cy + rad * 0.62f, text)
        if (live && g.sub != null) {
            text.color = Pal.TEXT2
            c.drawText(g.sub, cx, cy + rad * 0.62f + 4.2f * u, text)
        }
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT
    }

    // ───────────────────────── TIRES ─────────────────────────

    private fun drawTires(c: Canvas, a: RectF, s: VehicleState) {
        val p = 4.5f * u
        val box = RectF(a.left + p, a.top + 1f * u, a.right - p, a.bottom - p)
        panel(c, box)
        carPhoto?.let {
            drawTiresPhoto(c, box, s, it)
            return
        }
        val cx = box.centerX()
        val cy = box.centerY() - 1.5f * u

        // CX-5 silhouette, top-down, nose up
        val bodyH = box.height() - 16f * u
        val bodyW = bodyH * 0.45f
        val L = cx - bodyW / 2
        val R = cx + bodyW / 2
        val T = cy - bodyH / 2
        val B = cy + bodyH / 2
        path.reset()
        path.moveTo(cx, T)
        path.cubicTo(R - bodyW * 0.08f, T, R, T + bodyH * 0.04f, R, T + bodyH * 0.14f)
        path.lineTo(R, B - bodyH * 0.10f)
        path.cubicTo(R, B - bodyH * 0.02f, R - bodyW * 0.10f, B, cx, B)
        path.cubicTo(L + bodyW * 0.10f, B, L, B - bodyH * 0.02f, L, B - bodyH * 0.10f)
        path.lineTo(L, T + bodyH * 0.14f)
        path.cubicTo(L, T + bodyH * 0.04f, L + bodyW * 0.08f, T, cx, T)
        path.close()
        fill.shader = LinearGradient(L, 0f, R, 0f,
            intArrayOf(Color.parseColor("#141619"), Color.parseColor("#22252A"), Color.parseColor("#141619")), null, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        stroke.strokeWidth = 0.3f * u
        stroke.color = Color.parseColor("#3A3D43")
        c.drawPath(path, stroke)
        // hood creases (Kodo lines)
        stroke.color = Color.parseColor("#2C2F34")
        c.drawLine(cx - bodyW * 0.22f, T + bodyH * 0.05f, cx - bodyW * 0.26f, T + bodyH * 0.22f, stroke)
        c.drawLine(cx + bodyW * 0.22f, T + bodyH * 0.05f, cx + bodyW * 0.26f, T + bodyH * 0.22f, stroke)
        // glass
        fill.color = Color.parseColor("#0A0B0D")
        path.reset()
        path.moveTo(L + bodyW * 0.14f, T + bodyH * 0.30f)
        path.lineTo(R - bodyW * 0.14f, T + bodyH * 0.30f)
        path.lineTo(R - bodyW * 0.20f, T + bodyH * 0.24f)
        path.lineTo(L + bodyW * 0.20f, T + bodyH * 0.24f)
        path.close()
        c.drawPath(path, fill)
        r2.set(L + bodyW * 0.16f, T + bodyH * 0.34f, R - bodyW * 0.16f, B - bodyH * 0.24f)
        fill.color = Color.parseColor("#101215")
        c.drawRoundRect(r2, 2f * u, 2f * u, fill)
        r2.set(L + bodyW * 0.18f, B - bodyH * 0.18f, R - bodyW * 0.18f, B - bodyH * 0.10f)
        fill.color = Color.parseColor("#0A0B0D")
        c.drawRoundRect(r2, 1.4f * u, 1.4f * u, fill)
        // mirrors
        fill.color = Color.parseColor("#1C1E22")
        r2.set(L - 2.2f * u, T + bodyH * 0.27f, L + 0.4f * u, T + bodyH * 0.31f); c.drawRoundRect(r2, 1f * u, 1f * u, fill)
        r2.set(R - 0.4f * u, T + bodyH * 0.27f, R + 2.2f * u, T + bodyH * 0.31f); c.drawRoundRect(r2, 1f * u, 1f * u, fill)

        val now = System.currentTimeMillis()
        for (w in Wheel.entries) {
            val left = w == Wheel.FL || w == Wheel.RL
            val front = w == Wheel.FL || w == Wheel.FR
            val wx = if (left) L - 0.6f * u else R + 0.6f * u
            val wy = if (front) T + bodyH * 0.19f else B - bodyH * 0.19f
            val t = s.tires[w]
            val paired = Prefs.tireId(w) != null
            val stale = t == null || now - t.seenAt > 10 * 60_000
            val col = when {
                !paired -> Pal.DIM
                stale -> Pal.SILVER
                else -> t?.let { tireColor(it) } ?: Pal.SILVER
            }
            // tire
            r2.set(wx - 1.8f * u, wy - 6.2f * u, wx + 1.8f * u, wy + 6.2f * u)
            if (paired && !stale && col != Pal.TEXT) {
                fill.color = withAlpha(col, 70)
                r.set(r2.left - 1.2f * u, r2.top - 1.2f * u, r2.right + 1.2f * u, r2.bottom + 1.2f * u)
                c.drawRoundRect(r, 2.4f * u, 2.4f * u, fill)
            }
            fill.color = if (col == Pal.TEXT) Color.parseColor("#5A5D63") else col
            c.drawRoundRect(r2, 1.6f * u, 1.6f * u, fill)

            // leader line + reading
            val gapX = 7f * u
            val lineEnd = if (left) wx - gapX else wx + gapX
            stroke.strokeWidth = 0.22f * u
            stroke.color = Pal.HAIRLINE
            c.drawLine(if (left) wx - 2.6f * u else wx + 2.6f * u, wy, lineEnd, wy, stroke)

            val align = if (left) Paint.Align.RIGHT else Paint.Align.LEFT
            val tx = if (left) lineEnd - 1.6f * u else lineEnd + 1.6f * u
            val maxW = if (left) tx - box.left - 4f * u else box.right - 4f * u - tx
            text.textAlign = align
            caps(c, w.label.uppercase(), tx, wy - 8.6f * u, maxW, Pal.SILVER)
            val psi = when {
                !paired -> "—"
                stale -> "––"
                else -> t?.psi?.roundToInt()?.toString() ?: "––"
            }
            text.textAlign = align
            text.typeface = thin
            text.textSize = 13f * u
            text.color = when { !paired -> Pal.DIM; stale -> Pal.SILVER; else -> col }
            val psiW = text.measureText(psi)
            c.drawText(psi, tx, wy + 3.6f * u, text)
            text.typeface = medium
            text.textSize = 2.8f * u
            text.letterSpacing = 0.2f
            text.color = Pal.SILVER
            text.textAlign = Paint.Align.LEFT
            val unitX = if (left) tx - psiW - text.measureText("PSI") - 1.2f * u else tx + psiW + 1.2f * u
            if (paired) c.drawText("PSI", unitX, wy + 3.6f * u, text)
            text.letterSpacing = 0f
            val detail = when {
                !paired -> "PAIR ON PHONE"
                t == null -> "WAITING"
                else -> buildList {
                    t.tempF?.let { add("${it.roundToInt()}°F") }
                    t.battery?.let { add("BATT $it%") }
                    val age = (now - t.seenAt) / 60_000
                    if (age >= 2) add("${age}M AGO")
                }.joinToString("  ·  ")
            }
            text.textAlign = align
            caps(c, detail, tx, wy + 9.4f * u, maxW, Pal.TEXT2, 2.6f)
            text.textAlign = Paint.Align.LEFT
        }

        text.textAlign = Paint.Align.CENTER
        caps(c, "${Prefs.carName.uppercase()}  ·  TARGET ${Prefs.targetPsi} PSI  ·  WARN BELOW ${Prefs.tireLowPsi}",
            cx, box.bottom - 3f * u, box.width() - 8f * u, Pal.SILVER, 2.6f)
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * Tires view built around the real photo: the blue CX-5 under a soft spotlight,
     * rear tires read on the left, front tires on the right (the car faces right),
     * with leader lines to the two wheels you can see.
     */
    private fun drawTiresPhoto(c: Canvas, box: RectF, s: VehicleState, photo: Bitmap) {
        val now = System.currentTimeMillis()
        val ar = photo.width.toFloat() / photo.height
        val colW = box.width() * 0.22f
        val maxW = box.width() - 2 * colW - 4f * u
        val maxH = box.height() - 14f * u
        val pw = min(maxW, maxH * ar)
        val ph = pw / ar
        val cx = box.centerX()
        val cy = box.top + 3f * u + maxH / 2
        val img = RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)

        // studio spotlight + floor shadow
        fill.shader = RadialGradient(cx, cy - ph * 0.05f, max(pw * 0.7f, 1f),
            withAlpha(Color.parseColor("#26324A"), 150), withAlpha(Color.parseColor("#26324A"), 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy - ph * 0.05f, pw * 0.7f, fill)
        fill.shader = null
        val gy = img.bottom - ph * 0.04f
        c.save()
        c.scale(1f, 0.14f, cx, gy)
        fill.shader = RadialGradient(cx, gy, max(pw * 0.55f, 1f), withAlpha(Color.BLACK, 220), withAlpha(Color.BLACK, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, gy, pw * 0.55f, fill)
        fill.shader = null
        c.restore()

        val rearX = img.left + pw * PHOTO_REAR_X
        val rearY = img.top + ph * PHOTO_REAR_Y
        val frontX = img.left + pw * PHOTO_FRONT_X
        val frontY = img.top + ph * PHOTO_FRONT_Y

        // status halos behind the visible wheels
        for ((w, wx, wy) in listOf(Triple(Wheel.RL, rearX, rearY), Triple(Wheel.FL, frontX, frontY))) {
            val t = s.tires[w] ?: continue
            if (now - t.seenAt > 10 * 60_000) continue
            val col = tireColor(t)
            if (col == Pal.TEXT) continue
            fill.shader = RadialGradient(wx, wy, max(ph * 0.32f, 1f), withAlpha(col, 140), withAlpha(col, 0), Shader.TileMode.CLAMP)
            c.drawCircle(wx, wy, ph * 0.32f, fill)
            fill.shader = null
        }

        bmpPaint.colorFilter = null
        bmpPaint.alpha = 255
        c.drawBitmap(photo, null, img, bmpPaint)

        // readings: far side on top, near side (the wheels you can see) below
        val leftX = box.left + 4.5f * u
        val rightX = box.right - 4.5f * u
        val topY = box.top + 9f * u
        val lowY = box.top + box.height() * 0.56f
        drawReading(c, Wheel.RR, s.tires[Wheel.RR], leftX, topY, Paint.Align.LEFT, colW, now)
        drawReading(c, Wheel.FR, s.tires[Wheel.FR], rightX, topY, Paint.Align.RIGHT, colW, now)
        drawReading(c, Wheel.RL, s.tires[Wheel.RL], leftX, lowY, Paint.Align.LEFT, colW, now)
        drawReading(c, Wheel.FL, s.tires[Wheel.FL], rightX, lowY, Paint.Align.RIGHT, colW, now)

        // leader lines to the visible wheels
        stroke.shader = null
        stroke.strokeWidth = 0.22f * u
        stroke.color = withAlpha(Pal.SILVER, 120)
        val lx = box.left + 4.5f * u + colW * 0.62f
        c.drawLine(lx, lowY + 5f * u, rearX - ph * 0.17f, rearY, stroke)
        fill.color = Pal.TEXT2
        c.drawCircle(rearX - ph * 0.17f, rearY, 0.6f * u, fill)
        val rx = box.right - 4.5f * u - colW * 0.62f
        c.drawLine(rx, lowY + 5f * u, frontX + ph * 0.19f, frontY, stroke)
        c.drawCircle(frontX + ph * 0.19f, frontY, 0.6f * u, fill)

        text.textAlign = Paint.Align.CENTER
        caps(c, "${Prefs.carName.uppercase()}  ·  TARGET ${Prefs.targetPsi} PSI  ·  WARN BELOW ${Prefs.tireLowPsi}",
            cx, box.bottom - 3f * u, box.width() - 8f * u, Pal.SILVER, 2.6f)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawReading(c: Canvas, w: Wheel, t: TireReading?, x: Float, top: Float, align: Paint.Align, maxW: Float, now: Long) {
        val paired = Prefs.tireId(w) != null
        val stale = t == null || now - t.seenAt > 10 * 60_000
        val col = when {
            !paired -> Pal.DIM
            stale -> Pal.SILVER
            else -> t?.let { tireColor(it) } ?: Pal.SILVER
        }
        text.textAlign = align
        caps(c, w.label.uppercase(), x, top, maxW, Pal.SILVER)
        val psi = when {
            !paired -> "—"
            stale -> "––"
            else -> t?.psi?.roundToInt()?.toString() ?: "––"
        }
        text.textAlign = align
        text.typeface = thin
        text.textSize = 12f * u
        text.color = when { !paired -> Pal.DIM; stale -> Pal.SILVER; else -> col }
        val base = top + 12.4f * u
        c.drawText(psi, x, base, text)
        val psiW = text.measureText(psi)
        if (paired) {
            text.typeface = medium
            text.textSize = 2.8f * u
            text.letterSpacing = 0.2f
            text.color = Pal.SILVER
            text.textAlign = Paint.Align.LEFT
            val unitW = text.measureText("PSI")
            val ux = if (align == Paint.Align.LEFT) x + psiW + 1.2f * u else x - psiW - 1.2f * u - unitW
            c.drawText("PSI", ux, base, text)
            text.letterSpacing = 0f
        }
        // status tick under the number
        if (paired && !stale) {
            fill.color = if (col == Pal.TEXT) Pal.RED else col
            val bx = if (align == Paint.Align.LEFT) x else x - 6f * u
            r2.set(bx, base + 1.8f * u, bx + 6f * u, base + 2.3f * u)
            c.drawRoundRect(r2, 0.25f * u, 0.25f * u, fill)
        }
        val detail = when {
            !paired -> "PAIR ON PHONE"
            t == null -> "WAITING"
            else -> buildList {
                t.tempF?.let { add("${it.roundToInt()}°F") }
                t.battery?.let { add("BATT $it%") }
                val age = (now - t.seenAt) / 60_000
                if (age >= 2) add("${age}M AGO")
            }.joinToString("  ·  ")
        }
        text.textAlign = align
        caps(c, detail, x, base + 6.4f * u, maxW, Pal.TEXT2, 2.5f)
        text.textAlign = Paint.Align.LEFT
    }

    private fun tireColor(t: TireReading): Int = when {
        t.psi < Prefs.tireLowPsi - 4 -> Pal.ALARM
        t.psi < Prefs.tireLowPsi || t.psi > Prefs.tireHighPsi -> Pal.AMBER
        else -> Pal.TEXT
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun panel(c: Canvas, b: RectF) {
        val rad = 3.4f * u
        fill.shader = LinearGradient(0f, b.top, 0f, b.bottom, Pal.PANEL_TOP, Pal.PANEL_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRoundRect(b, rad, rad, fill)
        fill.shader = null
        stroke.shader = null
        stroke.strokeWidth = 0.22f * u
        stroke.color = Pal.HAIRLINE
        c.drawRoundRect(b, rad, rad, stroke)
        // top highlight, like light catching a bezel
        stroke.color = withAlpha(Color.WHITE, 18)
        c.drawLine(b.left + rad, b.top + 0.3f * u, b.right - rad, b.top + 0.3f * u, stroke)
    }

    private fun hairline(c: Canvas, x1: Float, y: Float, x2: Float) {
        stroke.shader = null
        stroke.strokeWidth = 0.22f * u
        stroke.color = Pal.HAIRLINE
        c.drawLine(x1, y, x2, y, stroke)
    }

    /** Small-caps label with wide tracking, the Mazda Connect way. Respects the current text alignment. */
    private fun caps(c: Canvas, s: String, x: Float, y: Float, maxW: Float, color: Int, sizeU: Float = 2.8f) {
        text.typeface = medium
        text.textSize = sizeU * u
        text.letterSpacing = 0.2f
        text.color = color
        c.drawText(fit(s, maxW), x, y, text)
        text.letterSpacing = 0f
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
        const val INTRO_HOLD_MS = 2200
        const val INTRO_MS = 2800
        /** Where the two visible wheels sit in the photo (fractions of its width/height). */
        const val PHOTO_REAR_X = 0.085f
        const val PHOTO_REAR_Y = 0.62f
        const val PHOTO_FRONT_X = 0.52f
        const val PHOTO_FRONT_Y = 0.78f
    }
}
