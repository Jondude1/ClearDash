package com.jondude1.cleardash.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class DashMode { DASH, GAUGES, TIRES }

/**
 * Draws every ClearDash view onto a plain Canvas. Used by the Android Auto
 * surface and by the live preview on the phone, so both look identical.
 */
class DashRenderer {

    object Pal {
        val BG = Color.parseColor("#06080B")
        val CARD = Color.parseColor("#0D1218")
        val CARD_EDGE = Color.parseColor("#18212B")
        val TEXT = Color.parseColor("#F2F6FA")
        val MUTED = Color.parseColor("#7F8C9B")
        val DIM = Color.parseColor("#1C2631")
        val CYAN = Color.parseColor("#39D0FF")
        val TEAL = Color.parseColor("#2FE6C8")
        val GREEN = Color.parseColor("#3DDC97")
        val AMBER = Color.parseColor("#FFB547")
        val RED = Color.parseColor("#FF5468")
    }

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
    private val clipPath = Path()
    private val clock = SimpleDateFormat("h:mm", Locale.US)
    private val ampm = SimpleDateFormat("a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEEE, MMM d", Locale.US)

    private var shownSpeed = 0f
    private var u = 1f // one layout unit = 1% of the drawing height

    fun draw(c: Canvas, area: RectF, fullW: Int, fullH: Int, s: VehicleState, mode: DashMode) {
        c.drawColor(Pal.BG)
        val target = s.bestSpeed ?: 0f
        shownSpeed += (target - shownSpeed) * 0.25f
        if (area.width() < 10 || area.height() < 10) return
        u = min(area.height() / 100f, area.width() / 170f)
        when (mode) {
            DashMode.DASH -> drawDash(c, area, s)
            DashMode.GAUGES -> drawGauges(c, area, s)
            DashMode.TIRES -> drawTires(c, area, s)
        }
    }

    // ───────────────────────── DASH ─────────────────────────

    private fun drawDash(c: Canvas, a: RectF, s: VehicleState) {
        val p = 4 * u
        val inner = RectF(a.left + p, a.top + p, a.right - p, a.bottom - p)
        val ringSize = min(inner.height(), inner.width() * 0.42f)
        val ringBox = RectF(inner.left, inner.centerY() - ringSize / 2, inner.left + ringSize, inner.centerY() + ringSize / 2)
        drawSpeedRing(c, ringBox, s)

        val right = RectF(ringBox.right + p, inner.top, inner.right, inner.bottom)
        if (right.width() < 30 * u) return
        val gap = p * 0.75f
        val media = s.media
        val navH = right.height() * (if (media != null) 0.40f else 0.56f)
        val navBox = RectF(right.left, right.top, right.right, right.top + navH)
        drawNavCard(c, navBox, s)

        var rowTop = navBox.bottom + gap
        if (media != null) {
            val mh = right.height() * 0.25f
            drawMediaCard(c, RectF(right.left, rowTop, right.right, rowTop + mh), media)
            rowTop += mh + gap
        }
        val half = (right.width() - gap) / 2
        drawTireMini(c, RectF(right.left, rowTop, right.left + half, right.bottom), s)
        drawEngineMini(c, RectF(right.right - half, rowTop, right.right, right.bottom), s)
    }

    private fun drawMediaCard(c: Canvas, b: RectF, m: MediaInfo) {
        card(c, b)
        val pad = 2.4f * u
        val artSize = b.height() - 2 * pad
        val art = RectF(b.left + pad, b.top + pad, b.left + pad + artSize, b.top + pad + artSize)
        val radius = 2.2f * u
        if (m.art != null) {
            c.save()
            clipPath.reset()
            clipPath.addRoundRect(art, radius, radius, Path.Direction.CW)
            c.clipPath(clipPath)
            drawCover(c, m.art, art)
            c.restore()
        } else {
            fill.color = withAlpha(Pal.CYAN, 34)
            c.drawRoundRect(art, radius, radius, fill)
            text.typeface = medium
            text.textAlign = Paint.Align.CENTER
            text.textSize = artSize * 0.42f
            text.color = Pal.CYAN
            c.drawText(m.appName.take(1).uppercase(), art.centerX(), art.centerY() + text.textSize * 0.36f, text)
            text.textAlign = Paint.Align.LEFT
        }

        // play state glyph on the right
        val gx = b.right - 5.5f * u
        val gy = b.centerY() - 1.6f * u
        fill.color = if (m.playing) Pal.CYAN else Pal.MUTED
        if (m.playing) {
            r2.set(gx - 1.6f * u, gy - 2.4f * u, gx - 0.5f * u, gy + 2.4f * u); c.drawRoundRect(r2, 0.4f * u, 0.4f * u, fill)
            r2.set(gx + 0.5f * u, gy - 2.4f * u, gx + 1.6f * u, gy + 2.4f * u); c.drawRoundRect(r2, 0.4f * u, 0.4f * u, fill)
        } else {
            clipPath.reset()
            clipPath.moveTo(gx - 1.6f * u, gy - 2.6f * u)
            clipPath.lineTo(gx + 2.2f * u, gy)
            clipPath.lineTo(gx - 1.6f * u, gy + 2.6f * u)
            clipPath.close()
            c.drawPath(clipPath, fill)
        }

        val tx = art.right + 3f * u
        val tw = gx - 4f * u - tx
        val compact = b.height() < 20f * u
        text.typeface = medium
        text.textSize = if (compact) 4.2f * u else 4.8f * u
        text.color = Pal.TEXT
        c.drawText(fit(m.title.ifEmpty { m.appName }, tw), tx, art.top + text.textSize * 0.95f, text)
        val titleBottom = art.top + text.textSize * 0.95f
        text.typeface = regular
        text.textSize = if (compact) 3.4f * u else 3.8f * u
        text.color = Pal.MUTED
        val sub = listOf(m.artist, m.appName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        c.drawText(fit(sub, tw), tx, titleBottom + text.textSize * 1.35f, text)

        // progress
        if (m.durationMs > 0) {
            val pos = m.livePosition()
            val by = art.bottom - 1.2f * u
            val timeTxt = if (m.longForm) "-" + fmtTime(m.durationMs - pos) else fmtTime(pos) + " / " + fmtTime(m.durationMs)
            text.typeface = medium
            text.textSize = 2.9f * u
            text.color = Pal.MUTED
            val timeW = text.measureText(timeTxt)
            c.drawText(timeTxt, tx + tw - timeW, by + 1.1f * u, text)
            val barRight = tx + tw - timeW - 2.4f * u
            stroke.shader = null
            stroke.strokeWidth = 0.9f * u
            stroke.color = Pal.DIM
            c.drawLine(tx, by, barRight, by, stroke)
            val frac = (pos.toFloat() / m.durationMs).coerceIn(0f, 1f)
            stroke.color = Pal.CYAN
            if (frac > 0f) c.drawLine(tx, by, tx + (barRight - tx) * frac, by, stroke)
        }
    }

    private fun drawCover(c: Canvas, b: Bitmap, box: RectF) {
        // center-crop into the square
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

    private fun drawSpeedRing(c: Canvas, box: RectF, s: VehicleState) {
        val cx = box.centerX()
        val cy = box.centerY() + box.height() * 0.02f
        val rad = box.width() / 2 - 4 * u
        val limit = s.speedLimitMph
        val speed = shownSpeed
        val maxSpeed = if (speed > 95) 140f else 100f
        val over = limit != null && speed > limit + 2
        val wayOver = limit != null && speed > limit + 9

        // soft halo
        fill.shader = null
        fill.color = withAlpha(if (wayOver) Pal.RED else if (over) Pal.AMBER else Pal.CYAN, 18)
        c.drawCircle(cx, cy, rad * 0.86f, fill)

        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        stroke.shader = null
        stroke.strokeWidth = 3.4f * u
        stroke.color = Pal.DIM
        c.drawArc(r, 150f, 240f, false, stroke)

        // ticks
        stroke.strokeWidth = 0.5f * u
        for (i in 0..(maxSpeed / 10).toInt()) {
            val ang = Math.toRadians(150.0 + 240.0 * (i * 10) / maxSpeed)
            val r1 = rad - 4.2f * u
            val r2l = rad - (if (i % 2 == 0) 6.4f else 5.2f) * u
            stroke.color = if (i % 2 == 0) Pal.MUTED else withAlpha(Pal.MUTED, 110)
            c.drawLine(cx + (r1 * cos(ang)).toFloat(), cy + (r1 * sin(ang)).toFloat(),
                cx + (r2l * cos(ang)).toFloat(), cy + (r2l * sin(ang)).toFloat(), stroke)
        }

        // limit marker on the ring
        if (limit != null && limit <= maxSpeed) {
            val ang = Math.toRadians(150.0 + 240.0 * limit / maxSpeed)
            fill.color = Pal.TEXT
            c.drawCircle(cx + (rad * cos(ang)).toFloat(), cy + (rad * sin(ang)).toFloat(), 1.5f * u, fill)
        }

        // progress
        val sweep = (240f * (speed / maxSpeed)).coerceIn(0f, 240f)
        if (sweep > 0.5f) {
            val end = if (wayOver) Pal.RED else if (over) Pal.AMBER else Pal.CYAN
            val start = if (over) Pal.AMBER else Pal.TEAL
            stroke.shader = LinearGradient(r.left, r.bottom, r.right, r.top, start, end, Shader.TileMode.CLAMP)
            stroke.strokeWidth = 3.4f * u
            c.drawArc(r, 150f, sweep, false, stroke)
            stroke.shader = null
        }

        // number
        val shown = if (s.bestSpeed == null) "--" else speed.roundToInt().toString()
        text.typeface = light
        text.textAlign = Paint.Align.CENTER
        text.textSize = rad * 0.62f
        text.color = if (wayOver) Pal.RED else Pal.TEXT
        c.drawText(shown, cx, cy + text.textSize * 0.30f, text)
        text.typeface = medium
        text.textSize = 4.2f * u
        text.letterSpacing = 0.25f
        text.color = Pal.MUTED
        c.drawText("MPH", cx, cy + rad * 0.40f, text)
        text.letterSpacing = 0f

        // speed limit sign between the ring's ends
        if (limit != null) {
            val w = 13f * u
            val h = 15.5f * u
            r2.set(cx - w / 2, cy + rad * 0.62f, cx + w / 2, cy + rad * 0.62f + h)
            if (r2.bottom > box.bottom + 2 * u) r2.offset(0f, box.bottom + 2 * u - r2.bottom)
            if (over) {
                fill.color = withAlpha(if (wayOver) Pal.RED else Pal.AMBER, 90)
                r.set(r2.left - 1.2f * u, r2.top - 1.2f * u, r2.right + 1.2f * u, r2.bottom + 1.2f * u)
                c.drawRoundRect(r, 2.6f * u, 2.6f * u, fill)
            }
            fill.color = Color.WHITE
            c.drawRoundRect(r2, 1.8f * u, 1.8f * u, fill)
            stroke.strokeWidth = 0.55f * u
            stroke.color = Color.BLACK
            r.set(r2.left + 0.9f * u, r2.top + 0.9f * u, r2.right - 0.9f * u, r2.bottom - 0.9f * u)
            c.drawRoundRect(r, 1.2f * u, 1.2f * u, stroke)
            text.color = Color.BLACK
            text.typeface = condensed
            text.textSize = 2.5f * u
            c.drawText("SPEED", r2.centerX(), r2.top + 4.0f * u, text)
            c.drawText("LIMIT", r2.centerX(), r2.top + 6.6f * u, text)
            text.textSize = 7.2f * u
            c.drawText(limit.toString(), r2.centerX(), r2.bottom - 2.0f * u, text)
        } else if (!s.gpsOk) {
            text.typeface = regular
            text.textSize = 3.2f * u
            text.color = Pal.MUTED
            c.drawText("Waiting for GPS", cx, cy + rad * 0.72f, text)
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawNavCard(c: Canvas, b: RectF, s: VehicleState) {
        card(c, b)
        val p = 4f * u
        val nav = s.nav?.takeIf { System.currentTimeMillis() - it.updatedAt < 5 * 60_000 }
        val alert = s.activeAlerts.maxByOrNull { it.severity.ordinal }
        val alertH = if (alert != null) 10f * u else 0f
        val content = RectF(b.left + p, b.top + p, b.right - p, b.bottom - p - alertH)

        val ch = content.height()
        val compact = ch < 30f * u
        if (nav != null) {
            val iconSize = min(ch * (if (compact) 0.95f else 0.62f), 22f * u)
            r.set(content.left, content.top, content.left + iconSize, content.top + iconSize)
            fill.color = withAlpha(Pal.CYAN, 40)
            c.drawRoundRect(r, 3f * u, 3f * u, fill)
            nav.icon?.let { drawTinted(c, it, r, 0.16f) }
            val tx = r.right + p
            val tw = content.right - tx
            val distSize = if (compact) min(10f * u, ch * 0.44f) else 11f * u
            text.typeface = medium
            text.color = Pal.TEXT
            text.textSize = distSize
            val dist = nav.distance.ifEmpty { "Next" }
            c.drawText(fit(dist, tw - 18f * u), tx, content.top + distSize * 0.85f, text)
            text.typeface = regular
            text.textSize = if (compact) 4.4f * u else 5.2f * u
            text.color = Color.parseColor("#C9D3DD")
            val instrY = content.top + distSize + (if (compact) 4.8f else 6.5f) * u
            wrap(c, nav.instruction, tx, instrY, tw, if (compact) 1 else 2, 6.6f * u)
            if (nav.eta.isNotEmpty()) {
                text.textSize = 3.6f * u
                text.color = Pal.MUTED
                if (compact) {
                    if (ch > instrY - content.top + 5f * u) c.drawText(fit(nav.eta, tw), tx, content.bottom, text)
                } else {
                    c.drawText(fit(nav.eta, content.width()), content.left, content.bottom, text)
                }
            }
        } else {
            val now = Date()
            val clockSize = if (compact) min(17f * u, ch * 0.62f) else 17f * u
            val base = content.top + clockSize * 0.82f
            text.typeface = light
            text.textSize = clockSize
            text.color = Pal.TEXT
            val t = clock.format(now)
            c.drawText(t, content.left, base, text)
            val tw = text.measureText(t)
            text.typeface = medium
            text.textSize = 4.5f * u
            text.color = Pal.MUTED
            val ap = ampm.format(now)
            c.drawText(ap, content.left + tw + 1.5f * u, base, text)
            text.typeface = regular
            text.textSize = 4.4f * u
            text.color = Color.parseColor("#C9D3DD")
            if (ch >= 24f * u) {
                c.drawText(dateFmt.format(now), content.left, base + 7.5f * u, text)
            }
            if (!compact) {
                text.textSize = 3.6f * u
                text.color = Pal.MUTED
                c.drawText(fit("Start a route in Google Maps to see turns here", content.width()), content.left, content.bottom, text)
            }
        }

        // status chips, top-right
        drawStatus(c, b.right - p, b.top + p + 1.6f * u, s)

        if (alert != null) {
            val col = when (alert.severity) { Severity.CRITICAL -> Pal.RED; Severity.WARN -> Pal.AMBER; else -> Pal.CYAN }
            r.set(b.left + 1.6f * u, b.bottom - alertH - 0.4f * u, b.right - 1.6f * u, b.bottom - 1.6f * u)
            fill.color = withAlpha(col, 46)
            c.drawRoundRect(r, 2.2f * u, 2.2f * u, fill)
            fill.color = col
            c.drawCircle(r.left + 3.4f * u, r.centerY(), 1.3f * u, fill)
            text.typeface = medium
            text.textSize = 3.9f * u
            text.color = col
            val head = alert.title
            c.drawText(fit(head, r.width() * 0.5f), r.left + 6.4f * u, r.centerY() + 1.4f * u, text)
            val hw = min(text.measureText(head), r.width() * 0.5f)
            text.typeface = regular
            text.color = Pal.TEXT
            c.drawText(fit(alert.message, r.width() - hw - 10f * u), r.left + 8.4f * u + hw, r.centerY() + 1.4f * u, text)
        }
    }

    private fun drawStatus(c: Canvas, rightX: Float, y: Float, s: VehicleState) {
        val items = listOf(
            "GPS" to s.gpsOk,
            "OBD" to s.obd.connected,
            "TIRES" to s.tires.values.any { System.currentTimeMillis() - it.seenAt < 120_000 },
            "HOME" to (s.haStatus == "Connected"),
        )
        text.typeface = medium
        text.textSize = 2.5f * u
        text.letterSpacing = 0.12f
        var x = rightX
        for ((label, ok) in items.reversed()) {
            val w = text.measureText(label)
            text.color = if (ok) Pal.MUTED else withAlpha(Pal.MUTED, 90)
            c.drawText(label, x - w, y + 0.9f * u, text)
            fill.color = if (ok) Pal.GREEN else Pal.DIM
            c.drawCircle(x - w - 1.6f * u, y, 0.8f * u, fill)
            x -= w + 5f * u
        }
        text.letterSpacing = 0f
    }

    private fun drawTireMini(c: Canvas, b: RectF, s: VehicleState) {
        card(c, b)
        label(c, "TIRES", b.left + 3.4f * u, b.top + 5.4f * u)
        val bodyW = b.width() * 0.16f
        val bodyH = b.height() * 0.62f
        val cx = b.centerX()
        val cy = b.centerY() + 2.4f * u
        r.set(cx - bodyW / 2, cy - bodyH / 2, cx + bodyW / 2, cy + bodyH / 2)
        stroke.strokeWidth = 0.5f * u
        stroke.color = Pal.MUTED
        c.drawRoundRect(r, bodyW * 0.4f, bodyW * 0.4f, stroke)
        val gapX = bodyW / 2 + 3.2f * u
        val spots = mapOf(
            Wheel.FL to Pair(cx - gapX, cy - bodyH * 0.22f), Wheel.FR to Pair(cx + gapX, cy - bodyH * 0.22f),
            Wheel.RL to Pair(cx - gapX, cy + bodyH * 0.34f), Wheel.RR to Pair(cx + gapX, cy + bodyH * 0.34f),
        )
        for ((w, pos) in spots) {
            val t = s.tires[w]
            val (txt, col) = tireText(w, t)
            text.typeface = medium
            text.textSize = min(6f * u, b.height() * 0.2f)
            text.color = col
            text.textAlign = if (w == Wheel.FL || w == Wheel.RL) Paint.Align.RIGHT else Paint.Align.LEFT
            c.drawText(txt, pos.first, pos.second, text)
            fill.color = col
            val dotX = if (text.textAlign == Paint.Align.RIGHT) cx - bodyW / 2 - 0.6f * u else cx + bodyW / 2 + 0.6f * u
            r2.set(dotX - 0.7f * u, pos.second - 4.6f * u, dotX + 0.7f * u, pos.second + 0.4f * u)
            c.drawRoundRect(r2, 0.7f * u, 0.7f * u, fill)
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun tireText(w: Wheel, t: TireReading?): Pair<String, Int> {
        if (Prefs.tireId(w) == null) return "—" to Pal.DIM
        if (t == null || System.currentTimeMillis() - t.seenAt > 10 * 60_000) return "--" to Pal.MUTED
        return t.psi.roundToInt().toString() to tireColor(t)
    }

    private fun tireColor(t: TireReading): Int = when {
        t.psi < Prefs.tireLowPsi - 4 -> Pal.RED
        t.psi < Prefs.tireLowPsi || t.psi > Prefs.tireHighPsi -> Pal.AMBER
        else -> Pal.TEXT
    }

    private fun drawEngineMini(c: Canvas, b: RectF, s: VehicleState) {
        card(c, b)
        label(c, "ENGINE", b.left + 3.4f * u, b.top + 5.4f * u)
        val o = s.obd
        if (!o.connected) {
            text.typeface = regular
            text.textSize = 3.6f * u
            text.color = Pal.MUTED
            c.drawText(fit(o.status, b.width() - 6.8f * u), b.left + 3.4f * u, b.centerY() + 3f * u, text)
            return
        }
        val rows = listOf(
            Triple("Coolant", o.coolantF?.let { "${it.roundToInt()}°" } ?: "--",
                if ((o.coolantF ?: 0f) >= 230f) Pal.RED else Pal.TEXT),
            Triple("Battery", o.voltage?.let { "%.1f V".format(it) } ?: "--",
                if (o.voltage != null && ((o.rpm ?: 0) > 600 && o.voltage < 13f)) Pal.AMBER else Pal.TEXT),
            Triple("RPM", o.rpm?.let { "%,d".format(it) } ?: "--", Pal.TEXT),
        )
        if (b.height() < 34f * u) {
            // short card: three columns side by side
            val colW = (b.width() - 6.8f * u) / rows.size
            rows.forEachIndexed { i, (lbl, v, col) ->
                val x = b.left + 3.4f * u + colW * i
                text.typeface = medium
                text.textSize = min(5.2f * u, colW * 0.24f)
                text.color = col
                c.drawText(v, x, b.bottom - 7.2f * u, text)
                text.typeface = regular
                text.textSize = 3.0f * u
                text.color = Pal.MUTED
                c.drawText(lbl, x, b.bottom - 3.0f * u, text)
            }
            return
        }
        val top = b.top + 9.5f * u
        val step = (b.bottom - 2.6f * u - top) / rows.size
        rows.forEachIndexed { i, (lbl, v, col) ->
            val y = top + step * (i + 0.72f)
            text.typeface = regular
            text.textSize = 3.6f * u
            text.color = Pal.MUTED
            c.drawText(lbl, b.left + 3.4f * u, y, text)
            text.typeface = medium
            text.textSize = 5.2f * u
            text.color = col
            text.textAlign = Paint.Align.RIGHT
            c.drawText(v, b.right - 3.4f * u, y, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    // ───────────────────────── GAUGES ─────────────────────────

    private class Gauge(
        val label: String, val value: Float?, val min: Float, val max: Float,
        val unit: String, val format: (Float) -> String, val warn: (Float) -> Int?,
    )

    private fun drawGauges(c: Canvas, a: RectF, s: VehicleState) {
        val o = s.obd
        val gauges = listOf(
            Gauge("RPM", o.rpm?.toFloat(), 0f, 7000f, "rpm", { "%,d".format(it.roundToInt()) }) { if (it > 6200) Pal.RED else null },
            Gauge("COOLANT", o.coolantF, 100f, 260f, "°F", { it.roundToInt().toString() }) { if (it >= 230) Pal.RED else if (it >= 220) Pal.AMBER else null },
            Gauge("BATTERY", o.voltage, 10f, 16f, "volts", { "%.1f".format(it) }) { if (it < 12f || it > 15f) Pal.AMBER else null },
            Gauge("ENGINE LOAD", o.load?.toFloat(), 0f, 100f, "%", { it.roundToInt().toString() }) { null },
            Gauge("INTAKE AIR", o.intakeF, 0f, 160f, "°F", { it.roundToInt().toString() }) { if (it > 130) Pal.AMBER else null },
            Gauge("FUEL", o.fuelPct?.toFloat(), 0f, 100f, "%", { it.roundToInt().toString() }) { if (it < 15) Pal.AMBER else null },
        )
        val p = 4 * u
        val gap = 2.6f * u
        val cols = 3
        val rows = 2
        val cw = (a.width() - 2 * p - gap * (cols - 1)) / cols
        val ch = (a.height() - 2 * p - gap * (rows - 1)) / rows
        gauges.forEachIndexed { i, g ->
            val x = a.left + p + (i % cols) * (cw + gap)
            val y = a.top + p + (i / cols) * (ch + gap)
            drawGauge(c, RectF(x, y, x + cw, y + ch), g, o.connected, o.status)
        }
    }

    private fun drawGauge(c: Canvas, b: RectF, g: Gauge, live: Boolean, status: String) {
        card(c, b)
        label(c, g.label, b.left + 3f * u, b.top + 5f * u)
        val cx = b.centerX()
        val rad = min(b.width() * 0.34f, b.height() * 0.38f)
        val cy = b.top + b.height() * 0.58f
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        stroke.shader = null
        stroke.strokeWidth = 2.4f * u
        stroke.color = Pal.DIM
        c.drawArc(r, 140f, 260f, false, stroke)
        val v = g.value
        val col = if (v != null) g.warn(v) ?: Pal.CYAN else Pal.DIM
        if (live && v != null) {
            val frac = ((v - g.min) / (g.max - g.min)).coerceIn(0f, 1f)
            stroke.color = col
            c.drawArc(r, 140f, 260f * frac, false, stroke)
        }
        text.textAlign = Paint.Align.CENTER
        text.typeface = light
        text.textSize = rad * 0.62f
        text.color = if (live && v != null) (g.warn(v) ?: Pal.TEXT) else Pal.MUTED
        c.drawText(if (live && v != null) g.format(v) else "--", cx, cy + text.textSize * 0.32f, text)
        text.typeface = medium
        text.textSize = 3.2f * u
        text.color = Pal.MUTED
        c.drawText(if (live) g.unit else fit(status, b.width() - 4 * u), cx, cy + rad * 0.78f, text)
        text.textAlign = Paint.Align.LEFT
    }

    // ───────────────────────── TIRES ─────────────────────────

    private fun drawTires(c: Canvas, a: RectF, s: VehicleState) {
        val p = 4 * u
        val cx = a.centerX()
        val cy = a.centerY()
        // car body
        val bodyH = a.height() - 2 * p - 6 * u
        val bodyW = bodyH * 0.44f
        r.set(cx - bodyW / 2, cy - bodyH / 2, cx + bodyW / 2, cy + bodyH / 2)
        fill.color = Pal.CARD
        c.drawRoundRect(r, bodyW * 0.42f, bodyW * 0.42f, fill)
        stroke.strokeWidth = 0.5f * u
        stroke.color = Pal.CARD_EDGE
        c.drawRoundRect(r, bodyW * 0.42f, bodyW * 0.42f, stroke)
        // windshield + rear glass
        fill.color = Pal.DIM
        r2.set(r.left + bodyW * 0.14f, r.top + bodyH * 0.22f, r.right - bodyW * 0.14f, r.top + bodyH * 0.36f)
        c.drawRoundRect(r2, 2f * u, 2f * u, fill)
        r2.set(r.left + bodyW * 0.16f, r.bottom - bodyH * 0.24f, r.right - bodyW * 0.16f, r.bottom - bodyH * 0.14f)
        c.drawRoundRect(r2, 2f * u, 2f * u, fill)
        // wheels
        for (w in Wheel.entries) {
            val left = w == Wheel.FL || w == Wheel.RL
            val front = w == Wheel.FL || w == Wheel.FR
            val wx = if (left) r.left - 1.2f * u else r.right + 1.2f * u
            val wy = if (front) r.top + bodyH * 0.2f else r.bottom - bodyH * 0.2f
            val t = s.tires[w]
            val col = when {
                Prefs.tireId(w) == null -> Pal.DIM
                t == null || System.currentTimeMillis() - t.seenAt > 10 * 60_000 -> Pal.MUTED
                else -> tireColor(t).let { if (it == Pal.TEXT) Pal.GREEN else it }
            }
            fill.color = col
            r2.set(wx - 1.6f * u, wy - 6f * u, wx + 1.6f * u, wy + 6f * u)
            c.drawRoundRect(r2, 1.4f * u, 1.4f * u, fill)

            // reading card
            val cw = min((a.width() - bodyW) / 2 - 3 * p, 46f * u)
            val chh = bodyH * 0.40f
            val bx = if (left) r.left - 5f * u - cw else r.right + 5f * u
            val by = wy - chh / 2
            val box = RectF(bx, by, bx + cw, by + chh)
            card(c, box, if (col == Pal.RED || col == Pal.AMBER) col else null)
            label(c, w.label.uppercase(), box.left + 3f * u, box.top + 5f * u)
            val (psiTxt, psiCol) = tireText(w, t)
            text.typeface = light
            text.textSize = min(chh * 0.42f, 18f * u)
            text.color = if (psiCol == Pal.DIM) Pal.MUTED else psiCol
            val numY = box.top + 5f * u + text.textSize * 1.0f
            c.drawText(psiTxt, box.left + 3f * u, numY, text)
            val nw = text.measureText(psiTxt)
            text.typeface = medium
            text.textSize = 3.4f * u
            text.color = Pal.MUTED
            c.drawText("PSI", box.left + 4f * u + nw, numY, text)
            text.typeface = regular
            text.textSize = 3.5f * u
            val detail = when {
                Prefs.tireId(w) == null -> "Not paired — set up on phone"
                t == null -> "Waiting for sensor"
                else -> buildString {
                    t.tempF?.let { append("${it.roundToInt()}°F") }
                    t.battery?.let { if (isNotEmpty()) append("  ·  "); append("Batt $it%") }
                    val age = (System.currentTimeMillis() - t.seenAt) / 1000
                    if (age > 90) { if (isNotEmpty()) append("  ·  "); append("${age / 60}m ago") }
                }
            }
            c.drawText(fit(detail, cw - 6f * u), box.left + 3f * u, box.bottom - 3.2f * u, text)
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun card(c: Canvas, b: RectF, edge: Int? = null) {
        fill.shader = null
        fill.color = Pal.CARD
        c.drawRoundRect(b, 3.2f * u, 3.2f * u, fill)
        stroke.shader = null
        stroke.strokeWidth = if (edge != null) 0.7f * u else 0.35f * u
        stroke.color = edge ?: Pal.CARD_EDGE
        c.drawRoundRect(b, 3.2f * u, 3.2f * u, stroke)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float) {
        text.typeface = medium
        text.textSize = 2.7f * u
        text.letterSpacing = 0.16f
        text.color = Pal.MUTED
        c.drawText(s, x, y, text)
        text.letterSpacing = 0f
    }

    private fun drawTinted(c: Canvas, b: Bitmap, box: RectF, inset: Float) {
        val i = box.width() * inset
        r2.set(box.left + i, box.top + i, box.right - i, box.bottom - i)
        // keep aspect
        val ar = b.width.toFloat() / max(1, b.height)
        if (ar > 1) { val h = r2.width() / ar; val d = (r2.height() - h) / 2; r2.top += d; r2.bottom -= d }
        else { val w = r2.height() * ar; val d = (r2.width() - w) / 2; r2.left += d; r2.right -= d }
        bmpPaint.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        c.drawBitmap(b, null, r2, bmpPaint)
    }

    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    private fun wrap(c: Canvas, s: String, x: Float, y: Float, maxW: Float, maxLines: Int, lineH: Float) {
        val words = s.split(' ')
        var line = ""
        var n = 0
        var i = 0
        while (i < words.size && n < maxLines) {
            val trial = if (line.isEmpty()) words[i] else "$line ${words[i]}"
            if (text.measureText(trial) <= maxW || line.isEmpty()) {
                line = trial; i++
            } else {
                val last = n == maxLines - 1
                c.drawText(if (last) fit(words.drop(i).joinToString(" ").let { "$line $it" }, maxW) else line, x, y + n * lineH, text)
                n++; line = ""
                if (last) return
            }
        }
        if (line.isNotEmpty() && n < maxLines) c.drawText(fit(line, maxW), x, y + n * lineH, text)
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
}
