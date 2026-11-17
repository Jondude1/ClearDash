package com.jondude1.cleardash.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import com.jondude1.cleardash.data.VehicleState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A wireframe CX-5 drawn as a turning hologram, tied to live data:
 *  • it turns slowly on its projector (one lap every ~28 s),
 *  • leans out of corners from the GPS heading change × speed (lateral g),
 *  • glows brighter while accelerating, and its tail lights flare red under hard braking,
 *  • its wheels spin with road speed.
 *
 * The mesh is a hand-built low-poly CX-5 (KF) in metres: 4.55 m long, 1.84 m wide, 1.68 m tall.
 */
class HoloCar {

    // ── mesh ──
    private val body = ArrayList<FloatArray>()   // [x1,y1,z1,x2,y2,z2]
    private val tail = ArrayList<FloatArray>()
    private val wheelCenters = listOf(
        floatArrayOf(1.35f, 0.36f, 0.93f), floatArrayOf(1.35f, 0.36f, -0.93f),
        floatArrayOf(-1.35f, 0.36f, 0.93f), floatArrayOf(-1.35f, 0.36f, -0.93f),
    )

    init { buildMesh() }

    private fun seg(list: MutableList<FloatArray>, a: FloatArray, b: FloatArray) {
        list += floatArrayOf(a[0], a[1], a[2], b[0], b[1], b[2])
    }

    private fun poly(list: MutableList<FloatArray>, pts: List<FloatArray>, closed: Boolean = false) {
        for (i in 0 until pts.size - 1) seg(list, pts[i], pts[i + 1])
        if (closed && pts.size > 2) seg(list, pts.last(), pts.first())
    }

    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun buildMesh() {
        for (s in listOf(1f, -1f)) {
            val w = 0.92f * s
            // lower body loop with wheel arches: tall blunt nose, rising beltline
            val lower = ArrayList<FloatArray>()
            lower += p(2.18f, 0.36f, w); lower += p(2.30f, 0.66f, w); lower += p(2.22f, 0.90f, w)
            lower += p(1.00f, 1.04f, w); lower += p(-2.02f, 1.14f, w); lower += p(-2.27f, 1.00f, w)
            lower += p(-2.24f, 0.40f, w); lower += p(-1.83f, 0.38f, w)
            lower += archPts(-1.35f, w)
            lower += p(-0.87f, 0.38f, w); lower += p(0.87f, 0.38f, w)
            lower += archPts(1.35f, w)
            lower += p(1.83f, 0.38f, w)
            poly(body, lower, closed = true)

            // greenhouse
            poly(body, listOf(p(1.00f, 1.06f, 0.86f * s), p(0.02f, 1.62f, 0.70f * s), p(-1.60f, 1.60f, 0.70f * s),
                p(-2.12f, 1.30f, 0.80f * s), p(-2.02f, 1.14f, 0.90f * s)))
            seg(body, p(0.90f, 1.08f, 0.88f * s), p(-1.95f, 1.16f, 0.88f * s))          // window sill
            seg(body, p(-0.50f, 1.11f, 0.89f * s), p(-0.52f, 1.61f, 0.70f * s))         // B pillar
            seg(body, p(-1.48f, 1.14f, 0.89f * s), p(-1.60f, 1.60f, 0.70f * s))         // C pillar
            seg(body, p(0.0f, 1.66f, 0.55f * s), p(-1.55f, 1.64f, 0.55f * s))           // roof rail
            seg(body, p(1.98f, 0.84f, 0.93f * s), p(-2.16f, 0.98f, 0.93f * s))          // character line
            seg(body, p(0.05f, 0.44f, 0.93f * s), p(0.05f, 1.08f, 0.93f * s))           // door seams
            seg(body, p(-1.00f, 0.44f, 0.93f * s), p(-1.00f, 1.11f, 0.93f * s))
            poly(body, listOf(p(0.86f, 1.09f, 0.92f * s), p(0.74f, 1.14f, 1.08f * s), p(0.66f, 1.07f, 0.93f * s)))  // mirror
            seg(body, p(2.20f, 0.91f, 0.40f * s), p(1.03f, 1.06f, 0.48f * s))           // hood crease
            poly(body, listOf(p(2.26f, 0.84f, 0.52f * s), p(2.10f, 0.92f, 0.86f * s), p(2.20f, 0.80f, 0.88f * s)))  // headlight
            seg(body, p(2.29f, 0.80f, 0.50f * s), p(2.29f, 0.50f, 0.40f * s))           // grille side
            seg(body, p(-2.12f, 1.30f, 0.80f * s), p(-2.18f, 1.16f, 0.70f * s))         // rear glass side
            seg(tail, p(-2.25f, 1.06f, 0.88f * s), p(-2.22f, 1.08f, 0.42f * s))         // tail light
            seg(tail, p(-2.25f, 1.06f, 0.88f * s), p(-2.10f, 1.04f, 0.92f * s))
        }
        // cross members
        for ((x, y, z) in listOf(
            Triple(2.30f, 0.66f, 0.92f), Triple(2.22f, 0.90f, 0.92f), Triple(1.00f, 1.06f, 0.86f),
            Triple(0.02f, 1.62f, 0.70f), Triple(-1.60f, 1.60f, 0.70f), Triple(-2.12f, 1.30f, 0.80f),
            Triple(-2.27f, 1.00f, 0.92f), Triple(-2.24f, 0.40f, 0.92f), Triple(2.18f, 0.36f, 0.92f),
            Triple(-2.18f, 1.16f, 0.70f),
        )) seg(body, p(x, y, z), p(x, y, -z))
        seg(body, p(2.29f, 0.80f, 0.50f), p(2.29f, 0.80f, -0.50f))   // grille top
        seg(body, p(2.29f, 0.50f, 0.40f), p(2.29f, 0.50f, -0.40f))   // grille bottom
    }

    private fun archPts(cx: Float, z: Float): List<FloatArray> =
        listOf(150.0, 120.0, 90.0, 60.0, 30.0).map { a ->
            p(cx + 0.48f * cos(Math.toRadians(a)).toFloat(), 0.36f + 0.48f * sin(Math.toRadians(a)).toFloat(), z)
        }

    // ── live state ──
    private var lastT = 0L
    private var yaw = 35f
    private var lean = 0f
    private var accel = 0f
    private var lastSpd = 0f
    private var lastSpdAt = 0L
    private var lastBearing: Float? = null
    private var lastBearingAt = 0L
    private var wheelAngle = 0f

    /** Smoothed glow 0..1 from acceleration. */
    var glow = 0f
        private set
    /** Smoothed lateral acceleration in g (positive = turning right). */
    var latG = 0f
        private set
    /** 0..1 brake-light intensity. */
    var brake = 0f
        private set

    fun update(s: VehicleState) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.04f else ((now - lastT) / 1000f).coerceIn(0f, 0.5f)
        lastT = now
        yaw = (yaw + dt * 360f / 28f) % 360f

        val spd = s.bestSpeed ?: 0f
        if (lastSpdAt == 0L) { lastSpd = spd; lastSpdAt = now }
        if (now - lastSpdAt >= 400) {
            val a = (spd - lastSpd) / ((now - lastSpdAt) / 1000f)
            accel += (a - accel) * 0.35f
            lastSpd = spd
            lastSpdAt = now
        }
        val k = min(1f, dt * 3f)
        glow += ((accel / 5f).coerceIn(0f, 1f) - glow) * k
        brake += ((if (accel < -3.5f) 1f else 0f) - brake) * min(1f, dt * (if (accel < -3.5f) 8f else 2f))

        val b = s.bearing
        if (b != null && spd > 3f && now - lastBearingAt >= 450) {
            val prev = lastBearing
            val secs = (now - lastBearingAt) / 1000f
            if (prev != null && secs in 0.2f..3f) {
                var d = b - prev
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                val yawRate = Math.toRadians((d / secs).toDouble()).toFloat()
                val g = (spd * 0.44704f * yawRate) / 9.81f
                latG += (g.coerceIn(-1f, 1f) - latG) * 0.5f
            }
            lastBearing = b
            lastBearingAt = now
        } else if (spd <= 3f) {
            latG *= 0.9f
        }
        lean += ((-latG * 22f).coerceIn(-7f, 7f) - lean) * min(1f, dt * 2.5f)

        wheelAngle = (wheelAngle + Math.toDegrees((min(spd, 25f) * 0.44704f / 0.36f * dt).toDouble()).toFloat()) % 360f
    }

    // ── drawing ──
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val out = FloatArray(3)
    private val a2 = FloatArray(3)
    private val bbox = RectF()

    private var cy0 = 0f; private var cx0 = 0f; private var f = 1f
    private val dist = 9f
    private val pitch = 0.36f
    private var cosY = 1f; private var sinY = 0f; private var cosL = 1f; private var sinL = 0f
    private val cosP = cos(pitch); private val sinP = sin(pitch)

    /** Projects a body point; returns depth in out[2]. */
    private fun project(x: Float, y0: Float, z0: Float, leanBody: Boolean, dst: FloatArray) {
        var y = y0; var z = z0
        if (leanBody) {
            val yy = y - 0.3f
            y = yy * cosL - z * sinL + 0.3f
            z = yy * sinL + z * cosL
        }
        val X = x * cosY + z * sinY
        val Z = -x * sinY + z * cosY
        val Y = y - 0.8f
        val y2 = Y * cosP + Z * sinP
        val z2 = -Y * sinP + Z * cosP
        val depth = z2 + dist
        dst[0] = cx0 + X * f / depth
        dst[1] = cy0 - y2 * f / depth
        dst[2] = depth
    }

    private fun alphaFor(depth: Float) = (255 - ((depth - (dist - 1.6f)) / 3.2f).coerceIn(0f, 1f) * 190).toInt()

    fun draw(c: Canvas, box: RectF, u: Float) {
        cx0 = box.centerX()
        cy0 = box.centerY() + box.height() * 0.02f
        f = dist * min(box.width() * 0.78f / 4.7f, box.height() * 0.80f / 2.9f)
        cosY = cos(Math.toRadians(yaw.toDouble())).toFloat(); sinY = sin(Math.toRadians(yaw.toDouble())).toFloat()
        cosL = cos(Math.toRadians(lean.toDouble())).toFloat(); sinL = sin(Math.toRadians(lean.toDouble())).toFloat()

        // projector disc under the car
        project(0f, 0f, 0f, false, out)
        val gx = out[0]; val gy = out[1]
        val ringR = f / dist * 2.7f
        c.save()
        c.translate(gx, gy)
        c.scale(1f, sinP * 1.15f)
        fill.shader = RadialGradient(0f, 0f, max(ringR, 1f), withAlpha(CYAN, (60 + 90 * glow).toInt()), withAlpha(CYAN, 0), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, ringR, fill)
        fill.shader = null
        stroke.strokeWidth = 0.35f * u
        stroke.color = withAlpha(CYAN, 110)
        c.drawCircle(0f, 0f, ringR * 0.92f, stroke)
        stroke.color = withAlpha(CYAN, 50)
        c.drawCircle(0f, 0f, ringR * 0.62f, stroke)
        c.restore()

        bbox.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        // bloom pass, then crisp pass
        val bloomA = (26 + 70 * glow).toInt()
        for (pass in 0..1) {
            stroke.strokeWidth = if (pass == 0) 1.4f * u else 0.26f * u
            for (e in body) drawEdge(c, e, true, if (pass == 0) bloomA else -1, CYAN, pass == 1)
            val tailCol = if (brake > 0.05f) blend(CYAN_DIM_RGB, BRAKE, brake) else CYAN
            for (e in tail) drawEdge(c, e, true, if (pass == 0) (bloomA + (brake * 150).toInt()).coerceAtMost(255) else -1, tailCol, pass == 1)
        }
        drawWheels(c, u)

        // scan band sweeping up through the hologram every 3.5 s
        if (bbox.width() > 0f) {
            val t = (SystemClock.uptimeMillis() % 3_500L) / 3_500f
            val y = bbox.bottom - (bbox.height() + 6f * u) * t
            fill.shader = LinearGradient(0f, y, 0f, y + 3f * u, withAlpha(CYAN, (40 + 40 * glow).toInt()), withAlpha(CYAN, 0), Shader.TileMode.CLAMP)
            c.drawRect(bbox.left - 2f * u, max(y, bbox.top - 2f * u), bbox.right + 2f * u, min(y + 3f * u, bbox.bottom + 2f * u), fill)
            fill.shader = null
        }
    }

    private fun drawEdge(c: Canvas, e: FloatArray, leanBody: Boolean, fixedAlpha: Int, color: Int, track: Boolean) {
        project(e[0], e[1], e[2], leanBody, out)
        project(e[3], e[4], e[5], leanBody, a2)
        val a = if (fixedAlpha >= 0) fixedAlpha else alphaFor((out[2] + a2[2]) / 2f)
        stroke.color = withAlpha(color, a)
        c.drawLine(out[0], out[1], a2[0], a2[1], stroke)
        if (track) {
            bbox.left = min(bbox.left, min(out[0], a2[0])); bbox.right = max(bbox.right, max(out[0], a2[0]))
            bbox.top = min(bbox.top, min(out[1], a2[1])); bbox.bottom = max(bbox.bottom, max(out[1], a2[1]))
        }
    }

    private fun drawWheels(c: Canvas, u: Float) {
        val segs = 14
        stroke.strokeWidth = 0.26f * u
        for (wc in wheelCenters) {
            for (ring in listOf(0.36f, 0.21f)) {
                for (i in 0 until segs) {
                    val t0 = Math.PI * 2 * i / segs
                    val t1 = Math.PI * 2 * (i + 1) / segs
                    project(wc[0] + ring * cos(t0).toFloat(), wc[1] + ring * sin(t0).toFloat(), wc[2], false, out)
                    project(wc[0] + ring * cos(t1).toFloat(), wc[1] + ring * sin(t1).toFloat(), wc[2], false, a2)
                    stroke.color = withAlpha(CYAN, alphaFor((out[2] + a2[2]) / 2f) * (if (ring < 0.3f) 3 else 4) / 4)
                    c.drawLine(out[0], out[1], a2[0], a2[1], stroke)
                }
            }
            // five spokes, turning with road speed
            for (k in 0 until 5) {
                val t = Math.toRadians((wheelAngle + k * 72f).toDouble())
                project(wc[0] + 0.06f * cos(t).toFloat(), wc[1] + 0.06f * sin(t).toFloat(), wc[2], false, out)
                project(wc[0] + 0.21f * cos(t).toFloat(), wc[1] + 0.21f * sin(t).toFloat(), wc[2], false, a2)
                stroke.color = withAlpha(CYAN, alphaFor(out[2]) * 2 / 3)
                c.drawLine(out[0], out[1], a2[0], a2[1], stroke)
            }
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt(),
        )
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private companion object {
        val CYAN = Color.parseColor("#00E5FF")
        val CYAN_DIM_RGB = Color.parseColor("#00E5FF")
        val BRAKE = Color.parseColor("#FF2A2A")
    }
}
