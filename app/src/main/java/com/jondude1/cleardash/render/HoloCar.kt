package com.jondude1.cleardash.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import com.jondude1.cleardash.data.VehicleState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Jon's 2018 Mazda CX-5 (KF) as a turning hologram, tied to live data.
 *
 * The body is lofted from six long curves (rocker → widest → shoulder → window sill →
 * roof edge → centre line) sampled at ~40 stations along the car, built from the real
 * dimensions: 4.55 m long, 1.84 m wide, 1.68 m tall, 2.70 m wheelbase, 225/55R19 tyres.
 * Surfaces are see-through glass, lit at the edges (rim light) so the shape reads; the true
 * outline is traced where the surface turns away from you. On top go the CX-5's tells: the
 * shield grille with its chrome wing sweeping into the slim headlights, the window line that
 * tapers to a point at the rear, the sloped hatch with its spoiler, wide-hipped rear fenders,
 * slim wrap-around taillights with twin rings, and five-twin-spoke wheels.
 *
 * Live: it turns on its projector (~28 s a lap), leans out of corners (GPS heading × speed),
 * glows on throttle, the taillights flare under hard braking and the wheels spin with speed.
 */
class HoloCar {

    // ───────────────────────── shape ─────────────────────────

    private class Curve(pts: FloatArray) {
        private val xs = FloatArray(pts.size / 2) { pts[it * 2] }
        private val ys = FloatArray(pts.size / 2) { pts[it * 2 + 1] }
        operator fun invoke(x: Float): Float {
            if (x <= xs[0]) return ys[0]
            for (i in 1 until xs.size) if (x <= xs[i]) {
                val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + (ys[i] - ys[i - 1]) * t
            }
            return ys.last()
        }
    }

    private fun c(vararg p: Float) = Curve(p)

    private val F = 2.275f
    private val R = -2.275f
    private val axF = 1.335f
    private val axR = -1.365f
    private val wr = 0.365f

    /**
     * The six long curves, bottom to top centre: y(x) and z(x). Heights and lengths were
     * measured off a side-on photo of a 2017 KF CX-5 (and checked by overlaying the model on it):
     * high beltline rising to the rear, side glass ending in a point just behind the rear axle,
     * windshield set well back, tall nose, high headlights and taillights.
     */
    private val ly = arrayOf(
        c(R, 0.40f, -2.1f, 0.36f, -1.8f, 0.32f, -1.0f, 0.29f, 1.0f, 0.29f, 1.8f, 0.31f, 2.1f, 0.32f, F, 0.34f),
        c(R, 0.62f, -1.4f, 0.70f, 1.4f, 0.66f, F, 0.60f),
        c(R, 0.98f, -2.1f, 1.10f, -1.6f, 1.19f, -0.4f, 1.14f, 0.65f, 1.11f, 1.5f, 0.99f, 2.05f, 0.90f, 2.2f, 0.87f, F, 0.84f),
        c(R, 1.03f, -2.20f, 1.15f, -2.0f, 1.24f, -1.6f, 1.27f, -1.27f, 1.255f, -0.4f, 1.215f, 0.65f, 1.18f, 1.0f, 1.12f, 1.5f, 1.04f, 1.8f, 0.99f, 2.05f, 0.955f, 2.2f, 0.93f, F, 0.89f),
        c(R, 1.07f, -2.20f, 1.20f, -2.08f, 1.30f, -1.85f, 1.50f, -1.69f, 1.60f, -1.5f, 1.62f, -1.0f, 1.65f, -0.5f, 1.655f, 0.0f, 1.645f, 0.13f, 1.625f, 0.40f, 1.42f, 0.65f, 1.185f, 1.0f, 1.125f, 1.5f, 1.05f, 1.8f, 1.00f, 2.05f, 0.96f, 2.2f, 0.935f, F, 0.89f),
        c(R, 1.10f, -2.20f, 1.22f, -2.08f, 1.32f, -1.85f, 1.52f, -1.69f, 1.635f, -1.5f, 1.655f, -1.0f, 1.68f, -0.5f, 1.685f, 0.0f, 1.675f, 0.15f, 1.655f, 0.45f, 1.48f, 0.85f, 1.14f, 1.3f, 1.08f, 1.8f, 1.01f, 2.1f, 0.96f, 2.22f, 0.93f, F, 0.90f),
    )
    private val lz = arrayOf(
        c(R, 0.74f, -2.1f, 0.84f, -1.6f, 0.87f, 1.6f, 0.87f, 2.1f, 0.84f, F, 0.74f),
        c(R, 0.80f, -2.15f, 0.90f, -1.4f, 0.925f, -0.6f, 0.905f, 0.6f, 0.905f, 1.35f, 0.92f, 2.1f, 0.89f, F, 0.80f),
        c(R, 0.72f, -2.15f, 0.86f, -1.5f, 0.905f, 1.35f, 0.905f, 2.0f, 0.87f, 2.2f, 0.80f, F, 0.66f),
        c(R, 0.62f, -2.15f, 0.76f, -1.9f, 0.83f, 0.65f, 0.85f, 1.0f, 0.82f, 1.8f, 0.78f, 2.2f, 0.66f, F, 0.52f),
        c(R, 0.50f, -2.1f, 0.56f, -1.85f, 0.62f, 0.0f, 0.68f, 0.13f, 0.70f, 0.65f, 0.70f, 1.0f, 0.50f, 2.2f, 0.42f, F, 0.34f),
        c(R, 0f, F, 0f),
    )

    private val stations: FloatArray = run {
        val base = (0 until 34).map { R + (F - R) * it / 33f }
        (base + listOf(R + 0.02f, R + 0.06f, F - 0.02f, F - 0.06f, 0.65f, 0.13f, -1.69f, -2.08f, 0.85f, 0.45f)).sorted().distinct().toFloatArray()
    }
    private val ns = stations.size

    /** World vertices: [station][curve][side 0 = +z, 1 = -z] → (x, y, z). */
    private val vw = Array(ns) { i -> Array(6) { j -> Array(2) { s ->
        val x = stations[i]; val sg = if (s == 0) 1f else -1f
        floatArrayOf(x, ly[j](x), lz[j](x) * sg)
    } } }
    private val vc = Array(ns) { Array(6) { Array(2) { FloatArray(3) } } }

    // the window graphic: on the side surface between sill and roof edge, pointed at the rear
    private val dloTop = floatArrayOf(0.64f, 1.185f, 0.40f, 1.43f, 0.13f, 1.615f, 0.0f, 1.625f, -0.43f, 1.63f, -1.10f, 1.585f, -1.41f, 1.49f, -1.515f, 1.45f)
    private val dloBot = floatArrayOf(-1.515f, 1.45f, -1.40f, 1.33f, -1.27f, 1.26f, -0.4f, 1.22f, 0.64f, 1.185f)

    private fun onSide(x: Float, y: Float): Float {
        val y3 = ly[3](x); val z3 = lz[3](x); val y4 = ly[4](x); val z4 = lz[4](x)
        val t = if (y4 - y3 < 1e-5f) 0f else ((y - y3) / (y4 - y3)).coerceIn(0f, 1f)
        return z3 + (z4 - z3) * t + 0.004f
    }

    private fun dlo(sg: Float): FloatArray {
        val out = ArrayList<Float>()
        fun walk(p: FloatArray, n: Int) {
            for (k in 0 until p.size / 2 - 1) for (t in 0 until n) {
                val f = t / n.toFloat()
                val x = p[k * 2] + (p[k * 2 + 2] - p[k * 2]) * f
                val y = p[k * 2 + 1] + (p[k * 2 + 3] - p[k * 2 + 1]) * f
                out.add(x); out.add(y); out.add(onSide(x, y) * sg)
            }
        }
        walk(dloTop, 5); walk(dloBot, 6)
        out.add(out[0]); out.add(out[1]); out.add(out[2])
        return out.toFloatArray()
    }

    /** Linear lookup in an (x, y, x, y, …) list, x ascending or descending. */
    private fun interp(p: FloatArray, x: Float): Float {
        for (k in 0 until p.size / 2 - 1) {
            val x0 = p[k * 2]; val x1 = p[k * 2 + 2]
            if ((x - x0) * (x - x1) <= 0f) {
                val t = if (x1 == x0) 0f else (x - x0) / (x1 - x0)
                return p[k * 2 + 1] + (p[k * 2 + 3] - p[k * 2 + 1]) * t
            }
        }
        return p[p.size - 1]
    }

    /** A detail line: points (x,y,z…), strength, colour kind, and which side it's on (0 = middle). */
    private class Feat(val pts: FloatArray, val st: Float, val kind: Int, val side: Int)

    private val feats = ArrayList<Feat>()
    private val glass = ArrayList<FloatArray>()

    private fun f(st: Float, kind: Int, side: Int, vararg p: Float) { feats.add(Feat(p, st, kind, side)) }

    private fun arc(cx: Float, cy: Float, r: Float, z: Float, a0: Double, a1: Double, n: Int): FloatArray {
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = a0 + (a1 - a0) * i / (n - 1)
            out[i * 3] = cx + r * cos(t).toFloat(); out[i * 3 + 1] = cy + r * sin(t).toFloat(); out[i * 3 + 2] = z
        }
        return out
    }

    /** Black wheel-arch and rocker cladding: (polygon, side). Drawn on the near side only. */
    private val cladding = ArrayList<Pair<FloatArray, Int>>()

    private fun arcPts(cx: Float, cy: Float, r: Float, z: Float, a0: Double, a1: Double, n: Int) = arc(cx, cy, r, z, a0, a1, n)

    init {
        for (s in intArrayOf(1, -1)) {
            val sg = s.toFloat()
            feats.add(Feat(dlo(sg), 1f, CHROME, s))
            for ((xb, xt) in listOf(-0.40f to -0.43f, -1.24f to -1.10f)) {   // B pillar, C divider
                val yb = interp(dloBot, xb); val yt = interp(dloTop, xt)
                f(0.9f, LINE, s, xb, yb, onSide(xb, yb) * sg, xt, yt, onSide(xt, yt) * sg)
            }
            // shoulder crease, full length
            val sh = FloatArray(40 * 3)
            for (k in 0 until 40) { val x = -2.15f + 4.3f * k / 39f; sh[k * 3] = x; sh[k * 3 + 1] = ly[2](x); sh[k * 3 + 2] = lz[2](x) * sg }
            feats.add(Feat(sh, 0.9f, LINE, s))
            // lower-door Kodo highlight rising into the rear haunch
            f(0.5f, LINE, s, 1.25f, 0.95f, 0.915f * sg, 0.6f, 0.86f, 0.91f * sg, -0.2f, 0.80f, 0.905f * sg, -0.9f, 0.86f, 0.91f * sg,
                -1.55f, 0.98f, 0.92f * sg, -2.0f, 1.02f, 0.88f * sg)
            // thick wheel-arch cladding + rocker band with its chrome strip
            for (ax in floatArrayOf(axF, axR)) {
                val inner = arcPts(ax, wr, 0.42f, 0.905f * sg, -0.25, PI + 0.25, 22)
                val outer = arcPts(ax, wr, 0.52f, 0.925f * sg, -0.25, PI + 0.25, 22)
                val band = FloatArray(inner.size + outer.size)
                System.arraycopy(inner, 0, band, 0, inner.size)
                for (k in 0 until 22) { val src = (21 - k) * 3; band[inner.size + k * 3] = outer[src]; band[inner.size + k * 3 + 1] = outer[src + 1]; band[inner.size + k * 3 + 2] = outer[src + 2] }
                cladding.add(band to s)
                feats.add(Feat(inner, 0.9f, LINE, s)); feats.add(Feat(outer, 0.6f, LINE, s))
            }
            cladding.add(floatArrayOf(-0.86f, 0.29f, 0.875f * sg, 0.86f, 0.29f, 0.875f * sg, 0.86f, 0.40f, 0.88f * sg, -0.86f, 0.40f, 0.88f * sg) to s)
            f(0.9f, CHROME, s, -0.86f, 0.40f, 0.885f * sg, 0.86f, 0.40f, 0.885f * sg)
            f(0.6f, LINE, s, -0.86f, 0.29f, 0.88f * sg, 0.86f, 0.29f, 0.88f * sg)
            // door cuts, handles, mirror
            f(0.45f, LINE, s, 0.775f, 0.42f, 0.89f * sg, 0.80f, 0.80f, 0.91f * sg, 0.70f, 1.17f, 0.86f * sg)
            f(0.45f, LINE, s, -0.40f, 0.42f, 0.89f * sg, -0.40f, 1.21f, 0.86f * sg)
            f(0.45f, LINE, s, -1.31f, 1.25f, 0.86f * sg, -1.32f, 0.90f, 0.91f * sg)
            f(0.7f, LINE, s, -0.09f, 1.01f, 0.91f * sg, -0.33f, 1.01f, 0.91f * sg)
            f(0.7f, LINE, s, -1.06f, 1.08f, 0.91f * sg, -1.30f, 1.08f, 0.91f * sg)
            f(0.9f, LINE, s, 0.60f, 1.20f, 0.86f * sg, 0.48f, 1.27f, 1.06f * sg, 0.30f, 1.24f, 1.05f * sg, 0.36f, 1.15f, 0.86f * sg)
            // hood creases into the grille corners
            f(0.6f, LINE, s, 0.85f, 1.13f, 0.40f * sg, 1.6f, 1.04f, 0.39f * sg, 2.2f, 0.93f, 0.37f * sg)
            // slim headlight, high on the fender
            f(1f, CHROME, s, 2.27f, 0.89f, 0.40f * sg, 2.21f, 0.945f, 0.62f * sg, 2.05f, 0.965f, 0.80f * sg, 1.80f, 0.955f, 0.875f * sg,
                1.86f, 0.92f, 0.88f * sg, 2.06f, 0.90f, 0.82f * sg, 2.22f, 0.855f, 0.62f * sg, 2.27f, 0.89f, 0.40f * sg)
            // chrome signature wing: up the grille side, out under the headlight
            f(1f, CHROME, s, 2.285f, 0.52f, 0.36f * sg, 2.282f, 0.64f, 0.45f * sg, 2.275f, 0.78f, 0.49f * sg, 2.24f, 0.84f, 0.60f * sg,
                2.14f, 0.87f, 0.74f * sg, 2.0f, 0.895f, 0.84f * sg)
            f(0.6f, LINE, s, 2.25f, 0.44f, 0.52f * sg, 2.19f, 0.50f, 0.70f * sg, 2.10f, 0.48f, 0.79f * sg)
            // taillight, high and slim, with twin rings
            f(1f, TAIL, s, -2.255f, 1.12f, 0.40f * sg, -2.22f, 1.17f, 0.62f * sg, -2.12f, 1.21f, 0.80f * sg, -1.92f, 1.215f, 0.87f * sg,
                -1.96f, 1.15f, 0.875f * sg, -2.10f, 1.10f, 0.82f * sg, -2.22f, 1.075f, 0.62f * sg, -2.255f, 1.12f, 0.40f * sg)
            for ((rx, rz) in listOf(-2.20f to 0.70f, -2.17f to 0.58f)) {
                val p = FloatArray(13 * 3)
                for (k in 0..12) { val t = 2 * PI * k / 12; p[k * 3] = rx; p[k * 3 + 1] = 1.14f + 0.024f * sin(t).toFloat(); p[k * 3 + 2] = (rz + 0.035f * cos(t).toFloat()) * sg }
                feats.add(Feat(p, 0.9f, TAIL, s))
            }
            // twin exhausts
            val ex = FloatArray(15 * 3)
            for (k in 0..14) { val t = 2 * PI * k / 14; ex[k * 3] = -2.27f; ex[k * 3 + 1] = 0.43f + 0.045f * sin(t).toFloat(); ex[k * 3 + 2] = (0.58f + 0.07f * cos(t).toFloat()) * sg }
            feats.add(Feat(ex, 0.7f, LINE, s))
            glass.add(dlo(sg))
        }
        // the shield grille: flat top, widest at the headlights, flat bottom
        f(1f, CHROME, 0, 2.272f, 0.88f, 0.38f, 2.272f, 0.88f, -0.38f, 2.275f, 0.76f, -0.47f, 2.28f, 0.56f, -0.43f, 2.284f, 0.50f, -0.34f,
            2.285f, 0.50f, 0.34f, 2.28f, 0.56f, 0.43f, 2.275f, 0.76f, 0.47f, 2.272f, 0.88f, 0.38f)
        for (gy in floatArrayOf(0.57f, 0.63f, 0.69f, 0.75f, 0.81f)) f(0.30f, LINE, 0, 2.279f, gy, 0.42f, 2.279f, gy, -0.42f)
        f(0.5f, LINE, 0, 2.27f, 0.40f, 0.48f, 2.27f, 0.40f, -0.48f)
        // windshield, rear glass, spoiler, plate, tail bar
        val ws = floatArrayOf(0.65f, ly[4](0.65f), lz[4](0.65f), 0.13f, ly[4](0.13f), lz[4](0.13f), 0.13f, ly[4](0.13f), -lz[4](0.13f), 0.65f, ly[4](0.65f), -lz[4](0.65f))
        val rg = floatArrayOf(-1.69f, 1.60f, 0.58f, -2.08f, 1.29f, 0.54f, -2.08f, 1.29f, -0.54f, -1.69f, 1.60f, -0.58f)
        glass.add(ws); glass.add(rg)
        feats.add(Feat(ws + ws.copyOfRange(0, 3), 0.7f, LINE, 0))
        feats.add(Feat(rg + rg.copyOfRange(0, 3), 0.8f, LINE, 0))
        f(0.8f, LINE, 0, -1.66f, 1.635f, 0.62f, -1.73f, 1.64f, 0f, -1.66f, 1.635f, -0.62f)
        f(0.5f, LINE, 0, -2.26f, 1.00f, 0.26f, -2.26f, 1.00f, -0.26f, -2.27f, 0.86f, -0.26f, -2.27f, 0.86f, 0.26f, -2.26f, 1.00f, 0.26f)
        f(0.6f, LINE, 0, -2.255f, 1.12f, 0.40f, -2.255f, 1.12f, -0.40f)
    }

    // ───────────────────────── live state ─────────────────────────

    private var lastT = 0L
    private var yaw = 55f
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
        wheelAngle = (wheelAngle + Math.toDegrees((min(spd, 25f) * 0.44704f / wr * dt).toDouble()).toFloat()) % 360f
    }

    // ───────────────────────── projection ─────────────────────────

    private val dist = 9f
    private val pitch = 0.16f
    private val cosP = cos(pitch)
    private val sinP = sin(pitch)
    private var cosY = 1f; private var sinY = 0f; private var cosL = 1f; private var sinL = 0f
    private var cx0 = 0f; private var cy0 = 0f; private var fl = 1f

    /** World → camera (X right, Y up, depth). */
    private fun cam(x: Float, y0: Float, z0: Float, leanBody: Boolean, out: FloatArray) {
        var y = y0; var z = z0
        if (leanBody) {
            val yy = y - 0.3f
            y = yy * cosL - z * sinL + 0.3f
            z = yy * sinL + z * cosL
        }
        val X = x * cosY + z * sinY
        val Z = -x * sinY + z * cosY
        val Y = y - 0.85f
        out[0] = X
        out[1] = Y * cosP + Z * sinP
        out[2] = -Y * sinP + Z * cosP + dist
    }

    private fun sx(c: FloatArray) = cx0 + c[0] * fl / c[2]
    private fun sy(c: FloatArray) = cy0 - c[1] * fl / c[2]
    private fun depthA(d: Float) = 1f - ((d - (dist - 1.8f)) / 3.0f).coerceIn(0f, 1f) * 0.85f

    // ───────────────────────── drawing ─────────────────────────

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val p0 = FloatArray(3)
    private val p1 = FloatArray(3)

    private val nq = (ns - 1) * 5 * 2
    private val qDepth = FloatArray(nq)
    private val qAlpha = FloatArray(nq)
    private val qFace = FloatArray(nq)
    private val order = Array(nq) { it }
    private val byDepth = Comparator<Int> { a, b -> qDepth[b].compareTo(qDepth[a]) }
    private fun qi(i: Int, j: Int, s: Int) = (i * 5 + j) * 2 + s

    fun draw(c: Canvas, box: RectF, u: Float, pal: DashRenderer.Palette = DashRenderer.DAY) {
        val accent = pal.CYAN
        val chrome = pal.TEXT
        val tail = pal.BRAKE
        cx0 = box.centerX()
        cy0 = box.centerY() + box.height() * 0.04f
        fl = dist * min(box.width() * 0.80f / 4.7f, box.height() * 0.84f / 2.4f)
        val yr = Math.toRadians(yaw.toDouble()); cosY = cos(yr).toFloat(); sinY = sin(yr).toFloat()
        val lr = Math.toRadians(lean.toDouble()); cosL = cos(lr).toFloat(); sinL = sin(lr).toFloat()

        // projector disc
        cam(0f, 0f, 0f, false, p0)
        val gx = sx(p0); val gy = sy(p0)
        val ringR = fl / dist * 2.75f
        c.save()
        c.translate(gx, gy)
        c.scale(1f, max(0.12f, sinP * 1.6f))
        fill.shader = RadialGradient(0f, 0f, max(ringR, 1f), withAlpha(accent, (55 + 90 * glow).toInt()), withAlpha(accent, 0), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, ringR, fill)
        fill.shader = null
        stroke.strokeWidth = 0.35f * u
        stroke.color = withAlpha(accent, 110); c.drawCircle(0f, 0f, ringR * 0.92f, stroke)
        stroke.color = withAlpha(accent, 45); c.drawCircle(0f, 0f, ringR * 0.62f, stroke)
        c.restore()

        // transform every body vertex once
        for (i in 0 until ns) for (j in 0..5) for (s in 0..1) {
            val w = vw[i][j][s]; cam(w[0], w[1], w[2], true, vc[i][j][s])
        }

        // surfaces: see-through glass lit at the edges, back to front
        val boost = 1f + 0.5f * glow
        for (i in 0 until ns - 1) for (j in 0..4) for (s in 0..1) {
            val k = qi(i, j, s)
            val a = vc[i][j][s]; val b = vc[i + 1][j][s]; val d = vc[i][j + 1][s]; val e = vc[i + 1][j + 1][s]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = d[0] - a[0]; val vy = d[1] - a[1]; val vz = d[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-9f) { qAlpha[k] = 0f; qDepth[k] = 0f; qFace[k] = 0f; continue }
            nx /= nl; ny /= nl; nz /= nl
            val mx = (a[0] + b[0] + d[0] + e[0]) / 4; val my = (a[1] + b[1] + d[1] + e[1]) / 4; val mz = (a[2] + b[2] + d[2] + e[2]) / 4
            val ml = sqrt(mx * mx + my * my + mz * mz)
            val dot = -(nx * mx + ny * my + nz * mz) / ml
            qFace[k] = dot * (if (s == 0) 1f else -1f)
            // a little light from above, for the hood and roof
            val wa = vw[i][j][s]; val wb = vw[i + 1][j][s]; val wd = vw[i][j + 1][s]
            val wux = wb[0] - wa[0]; val wuy = wb[1] - wa[1]; val wuz = wb[2] - wa[2]
            val wvx = wd[0] - wa[0]; val wvy = wd[1] - wa[1]; val wvz = wd[2] - wa[2]
            val wnx = wuy * wvz - wuz * wvy; val wny = wuz * wvx - wux * wvz; val wnz = wux * wvy - wuy * wvx
            val up = abs(wny) / max(1e-6f, sqrt(wnx * wnx + wny * wny + wnz * wnz))
            val rim = Math.pow((1f - abs(dot)).toDouble(), 1.6).toFloat()
            qAlpha[k] = ((6f + 150f * rim + 22f * up) * boost * depthA(mz)).coerceAtMost(220f)
            qDepth[k] = mz
        }
        order.sortWith(byDepth)
        for (k in order) {
            val al = qAlpha[k]
            if (al < 2f) continue
            val s = k % 2; val j = (k / 2) % 5; val i = k / 10
            val a = vc[i][j][s]; val b = vc[i + 1][j][s]; val e = vc[i + 1][j + 1][s]; val d = vc[i][j + 1][s]
            path.reset()
            path.moveTo(sx(a), sy(a)); path.lineTo(sx(b), sy(b)); path.lineTo(sx(e), sy(e)); path.lineTo(sx(d), sy(d)); path.close()
            fill.color = withAlpha(accent, al.toInt())
            c.drawPath(path, fill)
        }
        // end caps: front fascia and tailgate
        for (i in intArrayOf(0, ns - 1)) {
            path.reset()
            for (j in 0..5) { val v = vc[i][j][0]; if (j == 0) path.moveTo(sx(v), sy(v)) else path.lineTo(sx(v), sy(v)) }
            for (j in 5 downTo 0) { val v = vc[i][j][1]; path.lineTo(sx(v), sy(v)) }
            path.close()
            fill.color = withAlpha(accent, (40 * depthA(vc[i][3][0][2])).toInt())
            c.drawPath(path, fill)
        }
        // glass
        val glassCol = blendToWhite(accent, 0.55f)
        for (g in glass) {
            path.reset()
            var dsum = 0f
            val n = g.size / 3
            for (p in 0 until n) {
                cam(g[p * 3], g[p * 3 + 1], g[p * 3 + 2], true, p0)
                dsum += p0[2]
                if (p == 0) path.moveTo(sx(p0), sy(p0)) else path.lineTo(sx(p0), sy(p0))
            }
            path.close()
            val da = depthA(dsum / n)
            fill.color = withAlpha(glassCol, (70 * da * da).toInt())
            c.drawPath(path, fill)
        }

        // black cladding on the near side: wheel arches and rocker
        cam(0f, 0.6f, 1f, true, p0); cam(0f, 0.6f, 0f, true, p1)
        val near = if ((p0[0] - p1[0]) * -p1[0] + (p0[1] - p1[1]) * -p1[1] + (p0[2] - p1[2]) * -p1[2] > 0) 1 else -1
        for ((poly, side) in cladding) {
            if (side != near) continue
            path.reset()
            var dsum = 0f
            val n = poly.size / 3
            for (p in 0 until n) {
                cam(poly[p * 3], poly[p * 3 + 1], poly[p * 3 + 2], true, p0)
                dsum += p0[2]
                if (p == 0) path.moveTo(sx(p0), sy(p0)) else path.lineTo(sx(p0), sy(p0))
            }
            path.close()
            fill.color = withAlpha(pal.GROUND, (190 * depthA(dsum / n)).toInt())
            c.drawPath(path, fill)
        }

        // faint surface mesh
        stroke.strokeWidth = max(1f, 0.15f * u)
        for (j in 1..4) for (s in 0..1) for (i in 0 until ns - 1) {
            val a = vc[i][j][s]; val b = vc[i + 1][j][s]
            stroke.color = withAlpha(accent, (56 * depthA((a[2] + b[2]) / 2)).toInt())
            c.drawLine(sx(a), sy(a), sx(b), sy(b), stroke)
        }
        for (i in 0 until ns step 3) for (s in 0..1) for (j in 0..4) {
            val a = vc[i][j][s]; val b = vc[i][j + 1][s]
            stroke.color = withAlpha(accent, (40 * depthA((a[2] + b[2]) / 2)).toInt())
            c.drawLine(sx(a), sy(a), sx(b), sy(b), stroke)
        }

        // silhouette: where the surface turns away from you
        stroke.strokeWidth = 0.32f * u
        val sil = blendToWhite(accent, 0.6f)
        for (s in 0..1) {
            for (i in 0 until ns - 1) for (j in 0 until 4)
                if ((qFace[qi(i, j, s)] > 0) != (qFace[qi(i, j + 1, s)] > 0)) edge(c, vc[i][j + 1][s], vc[i + 1][j + 1][s], sil, 1f)
            for (i in 0 until ns - 2) for (j in 0..4)
                if ((qFace[qi(i, j, s)] > 0) != (qFace[qi(i + 1, j, s)] > 0)) edge(c, vc[i + 1][j][s], vc[i + 1][j + 1][s], sil, 1f)
        }
        for (i in 0 until ns - 1) {
            if ((qFace[qi(i, 4, 0)] > 0) != (qFace[qi(i, 4, 1)] > 0)) edge(c, vc[i][5][0], vc[i + 1][5][0], sil, 1f)
            for (s in 0..1) if (qFace[qi(i, 0, s)] > 0) edge(c, vc[i][0][s], vc[i + 1][0][s], sil, 0.8f)
        }

        // which side faces you (+z or -z)
        cam(0f, 0.6f, 1f, true, p0); cam(0f, 0.6f, 0f, true, p1)
        val nearSide = if ((p0[0] - p1[0]) * -p1[0] + (p0[1] - p1[1]) * -p1[1] + (p0[2] - p1[2]) * -p1[2] > 0) 1 else -1

        // details: a soft bloom pass under a crisp one; far-side details stay faint
        for (pass in 0..1) {
            stroke.strokeWidth = if (pass == 0) 1.1f * u else 0.3f * u
            for (ft in feats) {
                val farFade = if (ft.side != 0 && ft.side != nearSide) 0.18f else 1f
                val col = when (ft.kind) { CHROME -> chrome; TAIL -> tail; else -> accent }
                val tailBoost = if (ft.kind == TAIL) 0.55f + 0.45f * brake else 1f
                val base = if (pass == 0) 60f + 120f * (if (ft.kind == TAIL) brake else glow) else 255f
                val pts = ft.pts
                cam(pts[0], pts[1], pts[2], true, p0)
                for (p in 1 until pts.size / 3) {
                    cam(pts[p * 3], pts[p * 3 + 1], pts[p * 3 + 2], true, p1)
                    val a = depthA((p0[2] + p1[2]) / 2) * ft.st * farFade * tailBoost
                    stroke.color = withAlpha(col, (base * a).toInt())
                    c.drawLine(sx(p0), sy(p0), sx(p1), sy(p1), stroke)
                    p0[0] = p1[0]; p0[1] = p1[1]; p0[2] = p1[2]
                }
            }
        }

        drawWheels(c, u, accent, nearSide)

        // scan band sweeping up through the hologram every 3.5 s
        val t = (SystemClock.uptimeMillis() % 3_500L) / 3_500f
        val top = cy0 - fl / dist * 1.1f
        val bot = cy0 + fl / dist * 0.95f
        val y = bot - (bot - top + 6f * u) * t
        val hw = fl / dist * 2.6f
        if (y + 3f * u > top) {
            fill.shader = LinearGradient(0f, y, 0f, y + 3f * u, withAlpha(accent, (34 + 40 * glow).toInt()), withAlpha(accent, 0), Shader.TileMode.CLAMP)
            c.drawRect(cx0 - hw, max(y, top), cx0 + hw, min(y + 3f * u, bot), fill)
            fill.shader = null
        }
    }

    private fun edge(c: Canvas, a: FloatArray, b: FloatArray, col: Int, st: Float) {
        stroke.color = withAlpha(col, (255 * depthA((a[2] + b[2]) / 2) * st).toInt())
        c.drawLine(sx(a), sy(a), sx(b), sy(b), stroke)
    }

    private fun drawWheels(c: Canvas, u: Float, accent: Int, nearSide: Int) {
        val z = 0.84f * nearSide
        val zi = z - 0.22f * nearSide
        for (ax in floatArrayOf(axF, axR)) {
            stroke.strokeWidth = 0.3f * u
            wheelRing(c, ax, wr, z, 0.95f, accent, 28)
            wheelRing(c, ax, wr, zi, 0.3f, accent, 28)
            wheelRing(c, ax, wr * 0.66f, z, 0.8f, accent, 28)
            wheelRing(c, ax, 0.06f, z, 0.6f, accent, 12)
            stroke.strokeWidth = max(1f, 0.15f * u)
            for (k in 0 until 12) {  // tread
                val t = 2 * PI * k / 12
                cam(ax + wr * cos(t).toFloat(), wr + wr * sin(t).toFloat(), z, false, p0)
                cam(ax + wr * cos(t).toFloat(), wr + wr * sin(t).toFloat(), zi, false, p1)
                stroke.color = withAlpha(accent, (80 * depthA(p0[2])).toInt())
                c.drawLine(sx(p0), sy(p0), sx(p1), sy(p1), stroke)
            }
            stroke.strokeWidth = 0.28f * u
            for (k in 0 until 5) for (off in doubleArrayOf(-0.07, 0.07)) {  // five twin spokes
                val t = Math.toRadians((wheelAngle + k * 72f).toDouble()) + off
                cam(ax + 0.07f * cos(t).toFloat(), wr + 0.07f * sin(t).toFloat(), z, false, p0)
                cam(ax + wr * 0.66f * cos(t + off * 0.6).toFloat(), wr + wr * 0.66f * sin(t + off * 0.6).toFloat(), z, false, p1)
                stroke.color = withAlpha(accent, (180 * depthA(p0[2])).toInt())
                c.drawLine(sx(p0), sy(p0), sx(p1), sy(p1), stroke)
            }
        }
    }

    private fun wheelRing(c: Canvas, ax: Float, r: Float, zz: Float, st: Float, accent: Int, n: Int) {
        for (k in 0 until n) {
            val t0 = 2 * PI * k / n; val t1 = 2 * PI * (k + 1) / n
            cam(ax + r * cos(t0).toFloat(), wr + r * sin(t0).toFloat(), zz, false, p0)
            cam(ax + r * cos(t1).toFloat(), wr + r * sin(t1).toFloat(), zz, false, p1)
            stroke.color = withAlpha(accent, (255 * depthA(p0[2]) * st).toInt())
            c.drawLine(sx(p0), sy(p0), sx(p1), sy(p1), stroke)
        }
    }

    private fun blendToWhite(c: Int, t: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt(),
    )

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private companion object {
        const val LINE = 0
        const val CHROME = 1
        const val TAIL = 2
    }
}
