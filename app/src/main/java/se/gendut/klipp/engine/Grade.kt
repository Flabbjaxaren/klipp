package se.gendut.klipp.engine

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * Resolve-style color grading: each clip has a chain of (serial) nodes. A node has the wheels
 * Lift/Gamma/Gain/Offset (see [GradeNode.primaries]), contrast with pivot, color temperature/tint, saturation, hue, curves (Y + R/G/B)
 * and a mix (how much of the node is applied). The same math exists twice: on the CPU
 * (preview, scopes and the tests) and as a GLSL shader (playback and export).
 */

/** A color wheel: the puck position (x, y in the unit circle) and the master control (luminance, −1..1). */
data class Wheel(val x: Float = 0f, val y: Float = 0f, val master: Float = 0f) {
    val isNeutral get() = x == 0f && y == 0f && master == 0f

    /** The puck's color direction as an RGB offset summing to 0 (angle = hue, distance = strength). */
    fun rgb(): FloatArray {
        val m = min(1f, hypot(x, y))
        if (m < 1e-6f) return floatArrayOf(0f, 0f, 0f)
        val th = atan2(y, x)
        val k = m * 2f / 3f
        return floatArrayOf(k * cos(th), k * cos(th - 2 * PI.toFloat() / 3), k * cos(th + 2 * PI.toFloat() / 3))
    }

    fun toJson(): JSONArray = JSONArray().put(x.toDouble()).put(y.toDouble()).put(master.toDouble())

    companion object {
        fun fromJson(a: JSONArray?): Wheel = if (a == null || a.length() < 3) Wheel() else Wheel(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat())
        /** The wheel's color at an angle (to draw the wheel so it matches [rgb]). */
        fun hueColor(th: Float): FloatArray = floatArrayOf(
            0.5f + 0.5f * cos(th), 0.5f + 0.5f * cos(th - 2 * PI.toFloat() / 3), 0.5f + 0.5f * cos(th + 2 * PI.toFloat() / 3),
        )
    }
}

/** A curve: points (x, y) in 0..1, sorted by x. Two points (0,0)-(1,1) = straight. */
data class Curve(val points: List<Pair<Float, Float>> = IDENTITY) {
    val isIdentity get() = points == IDENTITY

    /** Smooth monotone curve through the points (Fritsch–Carlson), as 256 values. */
    fun table(): FloatArray {
        val p = points.sortedBy { it.first }
        val n = p.size
        val out = FloatArray(256)
        if (n < 2) { for (i in 0 until 256) out[i] = i / 255f; return out }
        val xs = FloatArray(n) { p[it].first }
        val ys = FloatArray(n) { p[it].second }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-6f, xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val t = 3f / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        for (k in 0 until 256) {
            val x = k / 255f
            val v = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    var i = 0
                    while (i < n - 2 && x > xs[i + 1]) i++
                    val h = xs[i + 1] - xs[i]
                    // points with the same x: no division by zero (0/0 = NaN throughout the table)
                    val t = if (h > 1e-6f) (x - xs[i]) / h else 0f
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                }
            }
            out[k] = if (v.isNaN()) x else v.coerceIn(0f, 1f)
        }
        return out
    }

    fun toJson(): JSONArray = JSONArray().also { a -> points.forEach { a.put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) } }

    companion object {
        val IDENTITY = listOf(0f to 0f, 1f to 1f)
        fun fromJson(a: JSONArray?): Curve =
            if (a == null || a.length() < 2) Curve() else Curve((0 until a.length()).map { a.getJSONArray(it).let { p -> p.optDouble(0).toFloat() to p.optDouble(1).toFloat() } }.sortedBy { it.first })
    }
}

/**
 * HSL qualifier (Resolve: Qualifier): selects pixels of the node's input by hue, saturation and luminance.
 * Hue and width in degrees (width 360 = all hues), the rest 0..1. [soft] softens all edges.
 */
data class Qualifier(
    val on: Boolean = false,
    val hue: Float = 0f,
    val hueWidth: Float = 360f,
    val satLo: Float = 0f,
    val satHi: Float = 1f,
    val lumLo: Float = 0f,
    val lumHi: Float = 1f,
    val soft: Float = 0.1f,
    val invert: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("on", on).put("h", hue.toDouble()).put("hw", hueWidth.toDouble())
        .put("sl", satLo.toDouble()).put("sh", satHi.toDouble()).put("ll", lumLo.toDouble()).put("lh", lumHi.toDouble())
        .put("soft", soft.toDouble()).put("inv", invert)

    companion object {
        fun fromJson(o: JSONObject?): Qualifier = if (o == null) Qualifier() else Qualifier(
            o.optBoolean("on"), o.optDouble("h", 0.0).toFloat(), o.optDouble("hw", 360.0).toFloat(),
            o.optDouble("sl", 0.0).toFloat(), o.optDouble("sh", 1.0).toFloat(), o.optDouble("ll", 0.0).toFloat(), o.optDouble("lh", 1.0).toFloat(),
            o.optDouble("soft", 0.1).toFloat(), o.optBoolean("inv"),
        )

        /** Qualifier around a picked color (the eyedropper): hue ±18°, saturation and luminance ±0.25. */
        fun around(r: Float, g: Float, b: Float): Qualifier {
            val (h, sat, l) = hsl(r, g, b)
            return Qualifier(true, h * 360f, 36f, (sat - 0.25f).coerceAtLeast(0f), (sat + 0.25f).coerceAtMost(1f), (l - 0.25f).coerceAtLeast(0f), (l + 0.25f).coerceAtMost(1f), 0.08f)
        }

        /** Hue (0..1), saturation and lightness (HSL). Same as the shader's toHsl. */
        fun hsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val l = (mx + mn) * 0.5f
            val d = mx - mn
            if (d <= 1e-5f) return Triple(0f, 0f, l)
            val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            var h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h /= 6f
            return Triple(h, s, l)
        }
    }
}

/** Window (Resolve: Power Window): circle or rectangle, in image coordinates 0..1 (y down). */
data class Window(
    /** 0 = off, 1 = circle, 2 = rectangle */
    val shape: Int = 0,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val rx: Float = 0.3f,
    val ry: Float = 0.3f,
    val soft: Float = 0.3f,
    val invert: Boolean = false,
    /** Tracking: the window follows an object (offset/scale per frame in source time). */
    val track: WindowTrack? = null,
    /** Rotation in degrees, clockwise on screen (the tracking rotation is added). */
    val angle: Float = 0f,
) {
    val on get() = shape != 0

    /** The window at source time [src] (seconds in the clip's file); unchanged without tracking (or time). */
    fun at(src: Double?): Window {
        val t = track ?: return this
        if (src == null) return copy(track = null)
        val m = t.at(src)
        return copy(cx = cx + m[0], cy = cy + m[1], rx = rx * m[2], ry = ry * m[2], angle = angle + Math.toDegrees(m[3].toDouble()).toFloat(), track = null)
    }

    fun toJson(): JSONObject = JSONObject().put("shape", shape).put("cx", cx.toDouble()).put("cy", cy.toDouble())
        .put("rx", rx.toDouble()).put("ry", ry.toDouble()).put("soft", soft.toDouble()).put("inv", invert)
        .apply { track?.let { put("track", it.toJson()) }; if (angle != 0f) put("ang", angle.toDouble()) }

    companion object {
        fun fromJson(o: JSONObject?): Window = if (o == null) Window() else Window(
            o.optInt("shape"), o.optDouble("cx", 0.5).toFloat(), o.optDouble("cy", 0.5).toFloat(),
            o.optDouble("rx", 0.3).toFloat(), o.optDouble("ry", 0.3).toFloat(), o.optDouble("soft", 0.3).toFloat(), o.optBoolean("inv"),
            WindowTrack.fromJson(o.optJSONObject("track")), o.optDouble("ang", 0.0).toFloat(),
        )
    }
}

/**
 * A tracked window's motion: for each frame (source time [t0] + i·[dt]) the window center's offset
 * (fraction of the image width/height) and scale, relative to the reference frame where the window was placed.
 * [q] = how confident the tracking was (0..1). [smooth] = smoothing (0 = raw data, 1 = very smooth), computed when needed.
 */
class WindowTrack(
    val t0: Double, val dt: Double,
    val dx: FloatArray, val dy: FloatArray, val s: FloatArray, val q: FloatArray,
    val smooth: Float = 0.3f,
    /** Rotation in radians (clockwise), relative to the reference frame. */
    val r: FloatArray = FloatArray(dx.size),
) {
    val size get() = dx.size
    val end get() = t0 + dt * (size - 1).coerceAtLeast(0)
    /** Frames where tracking was uncertain (object occluded, out of frame …). */
    val weak get() = q.count { it < 0.3f }

    private val smoothed: Array<FloatArray> by lazy {
        val sigma = smooth * smooth * 8f // frames; quadratic so the low values become fine steps
        arrayOf(gauss(dx, sigma), gauss(dy, sigma), gauss(s, sigma), gauss(r, sigma))
    }

    /** [dx, dy, scale, rotation] at source time [src] (linear between frames, holds the end values outside). */
    fun at(src: Double): FloatArray {
        if (size == 0) return floatArrayOf(0f, 0f, 1f, 0f)
        val (x, y, k, rr) = smoothed
        val f = ((src - t0) / dt).coerceIn(0.0, (size - 1).toDouble())
        val i = f.toInt().coerceAtMost(size - 1)
        val j = (i + 1).coerceAtMost(size - 1)
        val a = (f - i).toFloat()
        return floatArrayOf(x[i] + (x[j] - x[i]) * a, y[i] + (y[j] - y[i]) * a, k[i] + (k[j] - k[i]) * a, rr[i] + (rr[j] - rr[i]) * a)
    }

    fun withSmooth(v: Float) = WindowTrack(t0, dt, dx, dy, s, q, v.coerceIn(0f, 1f), r)

    /** Index of the frame nearest source time [src]. */
    fun indexAt(src: Double): Int = ((src - t0) / dt).roundToLong().toInt().coerceIn(0, (size - 1).coerceAtLeast(0))

    /**
     * Manual correction: the offset [ddx], [ddy] (fraction of the image) is applied from source time [src] onward,
     * with a short ramp backward so the window doesn't jump.
     */
    fun shiftFrom(src: Double, ddx: Float, ddy: Float, ramp: Int = 4): WindowTrack {
        if (size == 0) return this
        val i0 = indexAt(src)
        val nx = dx.copyOf(); val ny = dy.copyOf()
        for (i in 0 until size) {
            val w = when {
                i >= i0 -> 1f
                i >= i0 - ramp -> (i - (i0 - ramp - 1)).toFloat() / (ramp + 1)
                else -> 0f
            }
            nx[i] += ddx * w; ny[i] += ddy * w
        }
        return WindowTrack(t0, dt, nx, ny, s, q, smooth, r)
    }

    /**
     * Replaces everything from [other]'s start onward with [other] (continue tracking from here). [other] is relative
     * to the same reference as this track (its first value is aligned with this track's value there).
     */
    fun replaceFrom(other: WindowTrack): WindowTrack {
        if (size == 0) return other
        if (other.size == 0) return this
        // frames before the new tracking's start are kept (by time, not index: [other] may start after this
        // track's end, and its frame interval may differ slightly – so it is resampled onto this grid)
        var keep = 0
        while (keep < size && t0 + keep * dt < other.t0 - 1e-9) keep++
        val gridStart = if (keep > 0) t0 + keep * dt else other.t0
        val base = if (keep > 0) at(other.t0) else floatArrayOf(0f, 0f, 1f, 0f)
        val o0 = other.at(other.t0)
        val m = (kotlin.math.ceil((other.end - gridStart) / dt - 1e-6).toInt() + 1).coerceAtLeast(1)
        val n = keep + m
        val nx = FloatArray(n); val ny = FloatArray(n); val ns = FloatArray(n); val nq = FloatArray(n); val nr = FloatArray(n)
        for (i in 0 until keep) { nx[i] = dx[i]; ny[i] = dy[i]; ns[i] = s[i]; nq[i] = q[i]; nr[i] = r[i] }
        for (k in 0 until m) {
            // [other]'s raw values at the grid time (before its start: its first; after its end: its last)
            val f = ((gridStart + k * dt - other.t0) / other.dt).coerceIn(0.0, (other.size - 1).toDouble())
            val a = f.toInt().coerceAtMost(other.size - 1); val b = (a + 1).coerceAtMost(other.size - 1); val u = (f - a).toFloat()
            fun lerp(arr: FloatArray) = arr[a] + (arr[b] - arr[a]) * u
            val i = keep + k
            nx[i] = lerp(other.dx) - o0[0] + base[0]
            ny[i] = lerp(other.dy) - o0[1] + base[1]
            ns[i] = lerp(other.s) / o0[2].coerceAtLeast(1e-4f) * base[2]
            nq[i] = if (u < 0.5f) other.q[a] else other.q[b]
            nr[i] = lerp(other.r) - o0[3] + base[3]
        }
        return WindowTrack(if (keep > 0) t0 else gridStart, dt, nx, ny, ns, nq, smooth, nr)
    }

    fun toJson(): JSONObject = JSONObject().put("t0", t0).put("dt", dt).put("sm", smooth.toDouble())
        .put("x", arr(dx)).put("y", arr(dy)).put("s", arr(s)).put("q", arr(q)).put("r", arr(r))

    override fun equals(other: Any?) = other is WindowTrack && t0 == other.t0 && dt == other.dt && smooth == other.smooth &&
        dx.contentEquals(other.dx) && dy.contentEquals(other.dy) && s.contentEquals(other.s) && q.contentEquals(other.q) && r.contentEquals(other.r)

    override fun hashCode() = listOf<Any>(t0, dt, smooth, dx.contentHashCode(), dy.contentHashCode(), s.contentHashCode(), r.contentHashCode()).hashCode()

    companion object {
        private fun arr(a: FloatArray) = JSONArray().also { j -> a.forEach { j.put(Math.round(it * 100000.0) / 100000.0) } }
        private fun floats(a: JSONArray?) = FloatArray(a?.length() ?: 0) { a!!.optDouble(it, 0.0).toFloat() }

        fun fromJson(o: JSONObject?): WindowTrack? {
            if (o == null) return null
            val dx = floats(o.optJSONArray("x")); val dy = floats(o.optJSONArray("y")); val s = floats(o.optJSONArray("s"))
            if (dx.isEmpty() || dy.size != dx.size || s.size != dx.size) return null
            val q = floats(o.optJSONArray("q")).takeIf { it.size == dx.size } ?: FloatArray(dx.size) { 1f }
            val r = floats(o.optJSONArray("r")).takeIf { it.size == dx.size } ?: FloatArray(dx.size)
            val dt = o.optDouble("dt", 0.0)
            if (dt <= 0.0) return null
            return WindowTrack(o.optDouble("t0", 0.0), dt, dx, dy, s, q, o.optDouble("sm", 0.3).toFloat(), r)
        }

        /**
         * Gaussian smoothing without phase shift. The ends are point-reflected (2·end value − mirrored value), so a
         * steady motion continues straight all the way out – plain mirroring bends it toward the ends.
         */
        fun gauss(v: FloatArray, sigma: Float): FloatArray {
            if (sigma < 0.3f || v.size < 3) return v
            val r = (sigma * 3).toInt().coerceAtLeast(1)
            val w = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); kotlin.math.exp(-d * d / (2 * sigma * sigma)) }
            val n = v.size
            fun at(j: Int): Float = when {
                j < 0 -> 2 * v[0] - v[(-j).coerceAtMost(n - 1)]
                j >= n -> 2 * v[n - 1] - v[(2 * (n - 1) - j).coerceAtLeast(0)]
                else -> v[j]
            }
            return FloatArray(n) { i ->
                var acc = 0f; var ws = 0f
                for (k in -r..r) { acc += at(i + k) * w[k + r]; ws += w[k + r] }
                acc / ws
            }
        }
    }
}

data class GradeNode(
    val id: Int,
    val label: String = "",
    val enabled: Boolean = true,
    val lift: Wheel = Wheel(),
    val gamma: Wheel = Wheel(),
    val gain: Wheel = Wheel(),
    val offset: Wheel = Wheel(),
    val contrast: Float = 1f,
    val pivot: Float = 0.435f,
    val sat: Float = 1f,
    val hue: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val curveY: Curve = Curve(),
    val curveR: Curve = Curve(),
    val curveG: Curve = Curve(),
    val curveB: Curve = Curve(),
    /** Hue vs Hue/Sat/Lum, Lum vs Sat, Sat vs Sat, Sat vs Lum. */
    val hsl: HslCurves = HslCurves(),
    val mix: Float = 1f,
    /** LUT in the node (name in [LutStore]) – applied after the node's other adjustments, as in Resolve. */
    val lut: String? = null,
    val lutMix: Float = 1f,
    val qual: Qualifier = Qualifier(),
    val win: Window = Window(),
    /** Blur (0..1, radius up to 4 % of the image width) – e.g. a face in a tracked window. */
    val blur: Float = 0f,
    /** Pixelation (0..1, blocks up to 6 % of the image width). */
    val pixelate: Float = 0f,
    /** OpenDRT (display rendering transform from log to display) after the node's LUT – baked together with it. */
    val drt: Drt? = null,
    /** Film (spektrafilm: negative, print, scan) last in the node – baked together with the LUT and OpenDRT. */
    val film: Film? = null,
    /**
     * Node cache: the image up to and including this node is pre-rendered (full resolution, 10-bit when the phone
     * supports it) and playback reads it instead of computing the nodes – see [NodeCache].
     */
    val cache: Boolean = false,
    /** Noise reduction (0..1): an edge-keeping filter over the 5 × 5 neighbors ([Denoise]) before the node's color. */
    val denoise: Float = 0f,
) {
    val hasCurves get() = !(curveY.isIdentity && curveR.isIdentity && curveG.isIdentity && curveB.isIdentity)
    val hasHsl get() = !hsl.isNeutral
    val hasLut get() = lut != null && lutMix > 0.0001f
    /** The node affects only part of the image (qualifier and/or window). */
    val hasKey get() = qual.on || win.on
    val hasBlur get() = blur > 0.0001f || pixelate > 0.0001f || denoise > 0.0001f
    val isNeutral get() = !enabled || mix <= 0.0001f || (lift.isNeutral && gamma.isNeutral && gain.isNeutral && offset.isNeutral &&
        contrast == 1f && sat == 1f && hue == 0f && temp == 0f && tint == 0f && !hasCurves && !hasHsl && !hasLut && !hasBlur && drt == null && film == null)

    /** Uniform values (shared by the CPU path and the shader). */
    class Params(
        val lift: FloatArray, val gammaExp: FloatArray, val gain: FloatArray, val offset: FloatArray,
        val contrast: Float, val pivot: Float, val wb: FloatArray, val sat: Float, val hueMat: FloatArray, val mix: Float,
        val curves: Array<FloatArray>?,
        val lut: CubeLut? = null, val lutMix: Float = 1f,
        val qual: Qualifier? = null, val win: Window? = null,
        /** Blur radius and pixel block side, as a fraction of the image width (0 = off). */
        val blur: Float = 0f, val pixel: Float = 0f,
        /** The film (spektrafilm) after the node, on the whole image ([FilmEffect] / [FilmFx]). */
        val film: Film? = null,
        /** The HSL curves' tables ([HslCurves.tables]), null = none. */
        val hsl: Array<FloatArray>? = null,
        /**
         * The luminance side of the wheels (black, slope, gamma exponent – the master values only): the RGB result keeps
         * its color but takes this luminance (see [primaries]). null = RGB only.
         */
        val ySide: FloatArray? = null,
        /** Noise reduction 0..1 (see [Denoise]); with blur or pixelation it is left out (they smooth more). */
        val denoise: Float = 0f,
    ) {
        /** The node reads its neighbors (blur, pixelation, noise reduction): it needs a finished image to read from. */
        val neighbors get() = blur > 0f || pixel > 0f || denoise > 0f
    }

    /**
     * The uniform values – computed once per node (curve tables, LUT, OpenDRT baking) and reused: a node never
     * changes (every edit is a new node). Declared in the body, so it is not part of equals/hashCode/copy.
     */
    private val paramsMemo by lazy(LazyThreadSafetyMode.PUBLICATION) { computeParams() }

    fun params(): Params = paramsMemo

    private fun computeParams(): Params {
        val l = lift.rgb(); val g = gamma.rgb(); val gn = gain.rgb(); val o = offset.rgb()
        // the wheels as lift/gain/gamma values (master: all channels alike, puck: the color), then the primaries
        // (see [primaries]): lift and gain as one linear step, offset, gamma last
        val liftC = FloatArray(3); val gainC = FloatArray(3); val gammaExp = FloatArray(3)
        for (i in 0..2) {
            val (a, b, e) = primaries(l[i] * 0.25f + lift.master * 0.5f, (1f + gn[i] * 0.5f + gain.master).coerceAtLeast(0f), g[i] * 0.5f + gamma.master)
            liftC[i] = a; gainC[i] = b; gammaExp[i] = e
        }
        val offC = FloatArray(3) { o[it] * 0.2f + offset.master * 0.25f }
        val ySide = primaries(lift.master * 0.5f, (1f + gain.master).coerceAtLeast(0f), gamma.master).let { (a, b, e) -> floatArrayOf(a, b, e) }
        val wb = floatArrayOf(1f + 0.18f * temp, 1f - 0.12f * tint, 1f - 0.18f * temp)
        // hue rotation around the gray axis
        val a = hue * PI.toFloat() / 180f
        val c = cos(a); val s = sin(a)
        val k = 1f / 3f; val sq = kotlin.math.sqrt(k)
        val hm = floatArrayOf(
            c + (1 - c) * k, k * (1 - c) - sq * s, k * (1 - c) + sq * s,
            k * (1 - c) + sq * s, c + k * (1 - c), k * (1 - c) - sq * s,
            k * (1 - c) - sq * s, k * (1 - c) + sq * s, c + k * (1 - c),
        )
        val curves = if (hasCurves) {
            val y = curveY.table(); val r = curveR.table(); val gg = curveG.table(); val b = curveB.table()
            fun comp(ch: FloatArray) = FloatArray(256) { i -> sample(ch, y[i]) }
            arrayOf(comp(r), comp(gg), comp(b))
        } else null
        val base = if (hasLut) lut?.let { runCatching { LutStore.get(it) }.getOrNull() } else null
        // with OpenDRT: the node's LUT (with its mix) and the transform in one single LUT, fully on
        val l2 = drt?.let { d -> runCatching { OpenDrt.composite(base, lutMix, d) }.getOrNull() } ?: base
        val l2Mix = if (drt != null && l2 !== base) 1f else lutMix.coerceIn(0f, 1f)
        // the film is applied after the node on the whole image (grain, halation and diffusion need the neighbors – it
        // doesn't fit in the node's LUT). The tables are prepared here (FilmEditor computes the parameters on a background thread).
        val f = film?.takeIf { fl -> runCatching { FilmFx.warm(fl) }.onFailure { se.gendut.klipp.CrashReport.note("spektrafilm: ${it.message}") }.isSuccess }
        return Params(liftC, gammaExp, gainC, offC, contrast, pivot, wb, sat, hm, mix.coerceIn(0f, 1f), curves,
            l2, l2Mix, qual.takeIf { it.on }, win.takeIf { it.on },
            blur.coerceIn(0f, 1f) * 0.04f, pixelate.coerceIn(0f, 1f) * 0.06f, f, hsl.tables(), ySide, denoise.coerceIn(0f, 1f))
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("on", enabled)
        .put("lift", lift.toJson()).put("gamma", gamma.toJson()).put("gain", gain.toJson()).put("offset", offset.toJson())
        .put("contrast", contrast.toDouble()).put("pivot", pivot.toDouble()).put("sat", sat.toDouble()).put("hue", hue.toDouble())
        .put("temp", temp.toDouble()).put("tint", tint.toDouble()).put("mix", mix.toDouble())
        .put("cY", curveY.toJson()).put("cR", curveR.toJson()).put("cG", curveG.toJson()).put("cB", curveB.toJson())
        .apply { if (hasHsl) put("hsl", hsl.toJson()) }
        .put("lut", lut ?: JSONObject.NULL).put("lutMix", lutMix.toDouble()).put("qual", qual.toJson()).put("win", win.toJson())
        .apply { if (blur > 0f) put("blur", blur.toDouble()); if (pixelate > 0f) put("pix", pixelate.toDouble()); drt?.let { put("drt", it.toJson()) }; film?.let { put("film", it.toJson()) }; if (cache) put("cache", true); if (denoise > 0f) put("dn", denoise.toDouble()) }

    companion object {
        fun fromJson(o: JSONObject) = GradeNode(
            o.optInt("id"), o.optString("label"), o.optBoolean("on", true),
            Wheel.fromJson(o.optJSONArray("lift")), Wheel.fromJson(o.optJSONArray("gamma")), Wheel.fromJson(o.optJSONArray("gain")), Wheel.fromJson(o.optJSONArray("offset")),
            o.optDouble("contrast", 1.0).toFloat(), o.optDouble("pivot", 0.435).toFloat(), o.optDouble("sat", 1.0).toFloat(), o.optDouble("hue", 0.0).toFloat(),
            o.optDouble("temp", 0.0).toFloat(), o.optDouble("tint", 0.0).toFloat(),
            Curve.fromJson(o.optJSONArray("cY")), Curve.fromJson(o.optJSONArray("cR")), Curve.fromJson(o.optJSONArray("cG")), Curve.fromJson(o.optJSONArray("cB")),
            HslCurves.fromJson(o.optJSONObject("hsl")),
            o.optDouble("mix", 1.0).toFloat(),
            if (o.isNull("lut")) null else o.optString("lut").ifBlank { null }, o.optDouble("lutMix", 1.0).toFloat(),
            Qualifier.fromJson(o.optJSONObject("qual")), Window.fromJson(o.optJSONObject("win")),
            o.optDouble("blur", 0.0).toFloat(), o.optDouble("pix", 0.0).toFloat(),
            Drt.fromJson(o.optJSONObject("drt")),
            Film.fromJson(o.optJSONObject("film")),
            o.optBoolean("cache", false),
            o.optDouble("dn", 0.0).toFloat(),
        )

        /**
         * The primaries, per channel: lift and gain are one linear step, y = gain·(lift + x·(1 − lift)) / (1 + lift·(gain − 1))
         * – gain scales, lift raises the blacks, and the white end is normalized when both are used; then offset is added;
         * then gamma, x^(1/(1 + gamma)) above 0 and x^(1 − gamma) below. Returns the linear step's (black, slope) and the
         * gamma exponent; the shaders do `pow(max(black + slope·x + offset, 0), exponent)` (without the clamp when the
         * exponent is 1).
         *
         * Per channel with the wheels' colors, and once more on the pixel's Rec. 709 luma with the master values alone
         * (the offset's luma added): the channels keep their color differences and take that luminance. So the master
         * wheels move brightness the same on every color, and the color pucks shift color without changing brightness.
         */
        fun primaries(lift: Float, gain: Float, gamma: Float): Triple<Float, Float, Float> {
            // (the denominator can reach 0 with strongly lowered lift and raised gain – kept away from it)
            val d = max(1f + lift * (gain - 1f), 0.05f)
            val e = if (gamma >= 0f) 1f / (1f + gamma) else 1f - gamma
            return Triple(gain * lift / d, gain * (1f - lift) / d, e)
        }

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun band(v: Float, lo: Float, hi: Float, soft: Float): Float {
            val s = max(soft, 0.001f)
            return smooth(lo - s, lo, v) * (1f - smooth(hi, hi + s, v))
        }

        /** The key (0..1): how much of the node applies to a pixel with input [r],[g],[b] at position [x],[y] (0..1, y down). */
        fun key(p: Params, r: Float, g: Float, b: Float, x: Float, y: Float, aspect: Float = 16f / 9f): Float {
            var k = 1f
            p.qual?.let { q ->
                val (h, s, l) = Qualifier.hsl(r, g, b)
                val half = q.hueWidth / 720f
                val kh = if (half >= 0.5f) 1f else {
                    val c = q.hue / 360f
                    val dh = kotlin.math.abs(((h - c + 0.5f) - kotlin.math.floor(h - c + 0.5f)) - 0.5f)
                    1f - smooth(half, half + max(q.soft, 0.001f), dh)
                }
                var kq = kh * band(s, q.satLo, q.satHi, q.soft) * band(l, q.lumLo, q.lumHi, q.soft)
                if (q.invert) kq = 1f - kq
                k *= kq
            }
            p.win?.let { w ->
                // rotated window: the point is rotated back around the center (in pixels, hence the aspect ratio)
                var ex = (x - w.cx) * aspect; var ey = y - w.cy
                if (w.angle != 0f) {
                    val a = Math.toRadians(w.angle.toDouble()); val c = cos(a).toFloat(); val sn = sin(a).toFloat()
                    val rx = ex * c + ey * sn; val ry = -ex * sn + ey * c
                    ex = rx; ey = ry
                }
                val dx = ex / aspect / max(w.rx, 1e-4f); val dy = ey / max(w.ry, 1e-4f)
                val d = if (w.shape == 2) max(kotlin.math.abs(dx), kotlin.math.abs(dy)) else kotlin.math.sqrt(dx * dx + dy * dy)
                val s = max(w.soft * 0.5f, 0.001f)
                var kw = 1f - smooth(1f - s, 1f + s, d)
                if (w.invert) kw = 1f - kw
                k *= kw
            }
            return k
        }

        /**
         * White balance with the eyedropper: temperature and tint for the node so that the color [rgb] (the node's input)
         * becomes neutral (gray) after the node. The node's other values stay; window/qualifier are ignored (the color
         * applies where you tapped). Two-stage search over the full control range – robust however the node is otherwise set.
         */
        fun whiteBalance(n: GradeNode, rgb: FloatArray): Pair<Float, Float> {
            val base = n.copy(enabled = true, mix = 1f, qual = Qualifier(), win = Window())
            fun err(t: Float, u: Float): Float {
                val c = rgb.copyOf()
                apply(base.copy(temp = t, tint = u).params(), c)
                val l = (c[0] + c[1] + c[2]) / 3f
                if (l < 1e-4f) return 1e9f
                val dr = (c[0] - l) / l; val dg = (c[1] - l) / l; val db = (c[2] - l) / l
                return dr * dr + dg * dg + db * db
            }
            var bt = 0f; var bu = 0f; var be = Float.MAX_VALUE
            for (i in -20..20) for (j in -20..20) {
                val t = i / 20f; val u = j / 20f
                val e = err(t, u)
                if (e < be) { be = e; bt = t; bu = u }
            }
            var step = 0.05f
            repeat(4) {
                val ct = bt; val cu = bu
                for (i in -5..5) for (j in -5..5) {
                    val t = (ct + i * step / 5f).coerceIn(-1f, 1f); val u = (cu + j * step / 5f).coerceIn(-1f, 1f)
                    val e = err(t, u)
                    if (e < be) { be = e; bt = t; bu = u }
                }
                step /= 5f
            }
            return bt to bu
        }

        fun sample(t: FloatArray, x: Float): Float {
            val p = x.coerceIn(0f, 1f) * 255f
            val i = p.toInt().coerceAtMost(254)
            val f = p - i
            return t[i] + (t[i + 1] - t[i]) * f
        }

        /**
         * One node on one pixel (values 0..1, gamma-encoded as in Media3's SDR chain). [x], [y] = the pixel's position
         * (0..1, y down) for windows. Returns the key (for "show key").
         */
        fun apply(p: Params, rgb: FloatArray, x: Float = 0.5f, y: Float = 0.5f, aspect: Float = 16f / 9f, colorIn: FloatArray? = null): Float {
            val inR = rgb[0]; val inG = rgb[1]; val inB = rgb[2]
            // with blur/pixelation: the color is computed on the blurred image, the key and mix on the original
            if (colorIn != null) { rgb[0] = colorIn[0]; rgb[1] = colorIn[1]; rgb[2] = colorIn[2] }
            val yIn = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
            for (i in 0..2) {
                val c = p.lift[i] + p.gain[i] * rgb[i] + p.offset[i]
                rgb[i] = if (p.gammaExp[i] == 1f) c else max(c, 0f).pow(p.gammaExp[i])
            }
            p.ySide?.let { ys ->
                var yv = ys[0] + ys[1] * yIn + 0.2126f * p.offset[0] + 0.7152f * p.offset[1] + 0.0722f * p.offset[2]
                if (ys[2] != 1f) yv = max(yv, 0f).pow(ys[2])
                val d = yv - (0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2])
                for (i in 0..2) rgb[i] += d
            }
            for (i in 0..2) rgb[i] = ((rgb[i] - p.pivot) * p.contrast + p.pivot) * p.wb[i]
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
            val hr = p.hueMat[0] * r + p.hueMat[1] * g + p.hueMat[2] * b
            val hg = p.hueMat[3] * r + p.hueMat[4] * g + p.hueMat[5] * b
            val hb = p.hueMat[6] * r + p.hueMat[7] * g + p.hueMat[8] * b
            val luma = 0.2126f * hr + 0.7152f * hg + 0.0722f * hb
            rgb[0] = (luma + (hr - luma) * p.sat).coerceIn(0f, 1f)
            rgb[1] = (luma + (hg - luma) * p.sat).coerceIn(0f, 1f)
            rgb[2] = (luma + (hb - luma) * p.sat).coerceIn(0f, 1f)
            p.curves?.let { cv -> for (i in 0..2) rgb[i] = sample(cv[i], rgb[i]) }
            p.hsl?.let { t -> HslCurves.apply(t, rgb); for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f) }
            p.lut?.let { l ->
                val o = l.applyTetrahedral(rgb[0], rgb[1], rgb[2])
                for (i in 0..2) rgb[i] = (rgb[i] + (o[i].coerceIn(0f, 1f) - rgb[i]) * p.lutMix)
            }
            val k = if (p.qual != null || p.win != null) key(p, inR, inG, inB, x, y, aspect) else 1f
            val m = p.mix * k
            rgb[0] = inR + (rgb[0] - inR) * m
            rgb[1] = inG + (rgb[1] - inG) * m
            rgb[2] = inB + (rgb[2] - inB) * m
            return k
        }
    }
}

/**
 * A clip's grade: the nodes in order (serial, like Resolve's default chain). Log footage is converted with OpenDRT
 * as an effect in a node (wherever the user puts it), not automatically first in the chain.
 */
data class ClipGrade(
    val nodes: List<GradeNode> = listOf(GradeNode(1, "Primär")), val stab: Stabilization? = null,
    /** The clip as a layer: green screen, mask, blend mode (after the grade; see [LayerFx]). */
    val layer: LayerFx? = null,
) {
    /** Needs the frames' source time (tracked windows, stabilization, the mask's keyframes). */
    val needsClock get() = hasTrack || stab != null || layer?.mask?.animated == true
    /** The whole chain that is computed (the nodes in order). */
    val chain: List<GradeNode> get() = nodes
    val isNeutral get() = chain.all { it.isNeutral } && stab == null
    val hasTrack get() = nodes.any { it.win.track != null }

    /**
     * The grade for another clip: the stabilization and the tracks belong to this clip's file (its camera and
     * objects, in its source time) and are not carried over – the windows stay where they are. [keepStab] = the recipient's own.
     */
    fun forOtherClip(keepStab: Stabilization?, keepLayer: LayerFx? = null): ClipGrade =
        copy(stab = keepStab, layer = keepLayer, nodes = nodes.map { n -> if (n.win.track == null) n else n.copy(win = n.win.copy(track = null)) })

    /** The grade at source time [src]: tracked windows moved there (stills, scopes, the CPU path). */
    fun at(src: Double?): ClipGrade = if (!hasTrack) this else copy(nodes = nodes.map { n -> if (n.win.track == null) n else n.copy(win = n.win.at(src)) })

    fun toJson(): JSONObject = JSONObject().put("nodes", JSONArray().also { a -> nodes.forEach { a.put(it.toJson()) } })
        .apply { stab?.let { put("stab", it.toJson()) }; layer?.let { put("layer", it.toJson()) } }

    /**
     * The whole chain on a bitmap (preview and scopes). With [keyOf] = a node id, that node's key is shown
     * in grayscale instead (Resolve's "highlight"): white = the node applies, black = it doesn't.
     */
    fun applyTo(src: android.graphics.Bitmap, keyOf: Int? = null): android.graphics.Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // the node whose key is shown is computed even if it doesn't color anything yet
        val active = chain.filter { (!it.isNeutral) || (keyOf != null && it.id == keyOf && it.enabled) }
        val ps = active.map { it.params() }
        val keyIdx = if (keyOf == null) -1 else active.indexOfFirst { it.id == keyOf }
        if (ps.isNotEmpty()) {
            val aspect = w.toFloat() / h
            // the whole image as floats, node by node (blur/pixelation needs the neighbors)
            var img = FloatArray(w * h * 3)
            for (i in px.indices) { val c = px[i]; img[i * 3] = ((c shr 16) and 255) / 255f; img[i * 3 + 1] = ((c shr 8) and 255) / 255f; img[i * 3 + 2] = (c and 255) / 255f }
            val rgb = FloatArray(3)
            val ci = FloatArray(3)
            for ((j, p) in ps.withIndex()) {
                val blurred = when {
                    j == keyIdx || !p.neighbors -> null
                    p.blur > 0f || p.pixel > 0f -> Blur.apply(img, w, h, p.blur * w, p.pixel * w)
                    else -> Denoise.apply(img, w, h, p.denoise)
                }
                for (i in 0 until w * h) {
                    rgb[0] = img[i * 3]; rgb[1] = img[i * 3 + 1]; rgb[2] = img[i * 3 + 2]
                    val x = ((i % w) + 0.5f) / w; val y = ((i / w) + 0.5f) / h
                    if (j == keyIdx) {
                        val k = GradeNode.key(p, rgb[0], rgb[1], rgb[2], x, y, aspect)
                        img[i * 3] = k; img[i * 3 + 1] = k; img[i * 3 + 2] = k
                        continue
                    }
                    if (blurred != null) { ci[0] = blurred[i * 3]; ci[1] = blurred[i * 3 + 1]; ci[2] = blurred[i * 3 + 2] }
                    GradeNode.apply(p, rgb, x, y, aspect, if (blurred != null) ci else null)
                    img[i * 3] = rgb[0]; img[i * 3 + 1] = rgb[1]; img[i * 3 + 2] = rgb[2]
                }
                if (j == keyIdx) break
                // the film after the node, on the whole image (stills: the same grain every time)
                p.film?.let { f -> img = FilmFx.applyEncoded(FilmFx.setup(f), img, w, h, 0L) }
            }
            for (i in px.indices) {
                px[i] = (px[i] and -0x1000000) or ((img[i * 3].coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or
                    ((img[i * 3 + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or (img[i * 3 + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
        }
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Media3 effects: one shader pass per active node. With [clip], the passes read the node's current values from
     * [LiveGrades] every frame (playback) – otherwise the values are fixed (export).
     */
    fun effects(clip: Long? = null, clock: ((Long) -> Double?)? = null): List<androidx.media3.common.Effect> =
        chain.filter { !it.isNeutral }.flatMap<GradeNode, androidx.media3.common.Effect> { n ->
            val p = n.params()
            val key = clip?.let { c -> c to n.id }
            listOf(GradeNodeEffect(p, key, clock)) + listOfNotNull(p.film?.let { FilmEffect(it, key) })
        } +
            // stabilization last (the windows are in the source image's coordinates)
            listOfNotNull(stab?.takeIf { clock != null }?.let { StabMatrix(it, clock!!) })

    /** The node cache point: the last enabled node with cache on (−1 = none). */
    val cachePoint: Int get() = nodes.indexOfLast { it.cache && it.enabled }

    /** Playback effects when the image up to and including node [point] comes from the node cache: the remaining nodes and the stabilization. */
    fun effectsAfter(point: Int, clip: Long?, clock: ((Long) -> Double?)?): List<androidx.media3.common.Effect> =
        copy(nodes = nodes.drop(point + 1).ifEmpty { listOf(GradeNode(Int.MIN_VALUE)) }).effects(clip, clock)

    companion object {
        /** Older projects may have "input" (removed log conversion) – it is ignored. */
        fun fromJson(o: JSONObject?): ClipGrade {
            val stab = Stabilization.fromJson(o?.optJSONObject("stab"))
            val layer = LayerFx.fromJson(o?.optJSONObject("layer"))
            val a = o?.optJSONArray("nodes") ?: return ClipGrade(stab = stab, layer = layer)
            val n = (0 until a.length()).map { GradeNode.fromJson(a.getJSONObject(it)) }
            return if (n.isEmpty()) ClipGrade(stab = stab, layer = layer) else ClipGrade(n, stab, layer)
        }
    }
}

/** An affine map in pixels (y down): x' = a·x + b·y + tx, y' = c·x + d·y + ty. */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    fun map(x: Float, y: Float) = floatArrayOf(a * x + b * y + tx, c * x + d * y + ty)
    fun inverse(): Affine {
        val det = a * d - b * c
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }
    companion object { val ID = Affine(1f, 0f, 0f, 1f, 0f, 0f) }
}

/**
 * Stabilization: the camera path ([path], the whole image's motion frame by frame, see [WindowTracker.cameraPath]) and a
 * smooth path the image is moved, rotated and scaled to. The image is zoomed ([zoom]) so no edges show.
 *
 * As in Resolve and Premiere: [mode] 0 = smooth camera ([strength] 0..1 = how smooth), 1 = tripod (the camera stands
 * still, "Camera Lock"). The zoom is limited by [maxZoom]; if it isn't enough for the desired path, the path is pulled
 * toward the real one where needed – softly, by alternately smoothing and projecting into the allowed region
 * (the same principle as the "crop window" in Grundmann et al., Auto-Directed Video Stabilization with Robust L1
 * Optimal Camera Paths, CVPR 2011) – so the edges never show and no jerks appear.
 */
class Stabilization(val path: WindowTrack, val strength: Float = 0.6f, val mode: Int = 0, val maxZoom: Float = 1.2f) {
    private val sigma get() = 3f + strength.coerceIn(0f, 1f) * 57f // frames

    /** The solved path for an aspect ratio: [x, y] in pixels (image 1000 high), log scale, rotation – and the zoom. */
    private class Solved(val sx: FloatArray, val sy: FloatArray, val sl: FloatArray, val sr: FloatArray, val zoom: Float)

    private val solvedCache = HashMap<Int, Solved>()
    // the key rounded (not truncated) to three decimals: the video's and the preview's aspect ratios often differ
    // only in the fourth decimal – same solution (the margin in the coverage check covers that difference)
    private fun solved(aspect: Float): Solved = synchronized(solvedCache) { solvedCache.getOrPut(Math.round(aspect * 1000f)) { solve(aspect) } }

    /** The correction for one frame: the content (raw path) → the smooth path, around the image center. */
    private fun corr(px: Float, py: Float, pl: Float, pr: Float, sx: Float, sy: Float, sl: Float, sr: Float): Affine {
        val k = kotlin.math.exp(sl - pl)
        val th = sr - pr
        val co = kotlin.math.cos(th) * k; val si = kotlin.math.sin(th) * k
        return Affine(co, -si, si, co, sx - (co * px - si * py), sy - (si * px + co * py))
    }

    /** Does the zoomed, corrected image cover the whole frame (corners inside the source image)? */
    private fun covers(c: Affine, z: Float, hw: Float, hh: Float): Boolean {
        val inv = Affine(c.a * z, c.b * z, c.c * z, c.d * z, c.tx * z, c.ty * z).inverse()
        for (i in 0..3) {
            val p = inv.map(if (i and 1 == 0) -hw else hw, if (i < 2) -hh else hh)
            if (kotlin.math.abs(p[0]) > hw + 0.5f || kotlin.math.abs(p[1]) > hh + 0.5f) return false
        }
        return true
    }

    private fun solve(aspect: Float): Solved {
        val n = path.size
        val w = 1000f * aspect; val h = 1000f
        val hw = w / 2; val hh = h / 2
        if (n == 0) return Solved(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), 1f)
        val px = FloatArray(n) { path.dx[it] * w }
        val py = FloatArray(n) { path.dy[it] * h }
        val pl = FloatArray(n) { kotlin.math.ln(path.s[it].coerceAtLeast(1e-3f)) }
        val pr = path.r.copyOf()
        // desired path
        fun target(v: FloatArray): FloatArray = if (mode == 1) FloatArray(n) { v.average().toFloat() } else WindowTrack.gauss(v, sigma)
        var sx = target(px); var sy = target(py); var sl = target(pl); var sr = target(pr)
        fun fits(i: Int, z: Float) = covers(corr(px[i], py[i], pl[i], pr[i], sx[i], sy[i], sl[i], sr[i]), z, hw, hh)
        // smallest zoom that suffices for the desired path
        fun allFit(z: Float): Boolean { for (i in 0 until n) if (!fits(i, z)) return false; return true }
        val zMax = maxZoom.coerceIn(1f, 2f)
        var z: Float
        if (allFit(1f)) z = 1f
        else if (allFit(zMax)) {
            var lo = 1f; var hi = zMax
            repeat(14) { val m = (lo + hi) / 2; if (allFit(m)) hi = m else lo = m }
            z = hi
        } else {
            z = zMax
            // alternately project into the allowed region (per frame: as much of the correction as fits) and smooth;
            // a pure projection last so the edges are guaranteed not to show
            val smoothSigma = if (mode == 1) 6f + 30f * strength.coerceIn(0f, 1f) else (sigma / 3f).coerceAtLeast(2f)
            repeat(25) { iter ->
                for (i in 0 until n) {
                    if (fits(i, z)) continue
                    var lo = 0f; var hi = 1f
                    val ox = sx[i]; val oy = sy[i]; val ol = sl[i]; val orr = sr[i]
                    repeat(12) {
                        val m = (lo + hi) / 2
                        val c = corr(px[i], py[i], pl[i], pr[i], px[i] + (ox - px[i]) * m, py[i] + (oy - py[i]) * m, pl[i] + (ol - pl[i]) * m, pr[i] + (orr - pr[i]) * m)
                        if (covers(c, z, hw, hh)) lo = m else hi = m
                    }
                    sx[i] = px[i] + (ox - px[i]) * lo; sy[i] = py[i] + (oy - py[i]) * lo
                    sl[i] = pl[i] + (ol - pl[i]) * lo; sr[i] = pr[i] + (orr - pr[i]) * lo
                }
                if (iter < 24) { sx = WindowTrack.gauss(sx, smoothSigma); sy = WindowTrack.gauss(sy, smoothSigma); sl = WindowTrack.gauss(sl, smoothSigma); sr = WindowTrack.gauss(sr, smoothSigma) }
            }
        }
        return Solved(sx, sy, sl, sr, z)
    }

    private fun lerp(a: FloatArray, f: Double): Float {
        val n = a.size
        if (n == 0) return 0f
        val i = f.toInt().coerceIn(0, n - 1); val j = (i + 1).coerceAtMost(n - 1); val k = (f - i).toFloat()
        return a[i] + (a[j] - a[i]) * k
    }

    /** The correction (without zoom) at source time [src] for an image [w]×[h], around the image center. */
    fun correction(src: Double, w: Float, h: Float): Affine {
        val n = path.size
        if (n == 0) return Affine.ID
        val sv = solved(w / h)
        val f = ((src - path.t0) / path.dt).coerceIn(0.0, (n - 1).toDouble())
        // the solution is in an image 1000 high – scale to the image's pixels
        val u = h / 1000f
        val px = lerp(path.dx, f) * w; val py = lerp(path.dy, f) * h
        val pl = kotlin.math.ln(lerp(path.s, f).coerceAtLeast(1e-3f)); val pr = lerp(path.r, f)
        return corr(px, py, pl, pr, lerp(sv.sx, f) * u, lerp(sv.sy, f) * u, lerp(sv.sl, f), lerp(sv.sr, f))
    }

    /** Correction with zoom at [src]. */
    fun transform(src: Double, w: Float, h: Float): Affine {
        val z = zoom(w / h)
        val c = correction(src, w, h)
        return Affine(c.a * z, c.b * z, c.c * z, c.d * z, c.tx * z, c.ty * z)
    }

    /** The zoom that keeps the edges out of frame (at most [maxZoom]). */
    fun zoom(aspect: Float): Float = solved(aspect).zoom

    fun withStrength(v: Float) = Stabilization(path, v.coerceIn(0f, 1f), mode, maxZoom)
    fun withMode(m: Int) = Stabilization(path, strength, m.coerceIn(0, 1), maxZoom)
    fun withMaxZoom(z: Float) = Stabilization(path, strength, mode, z.coerceIn(1f, 1.5f))

    fun toJson(): JSONObject = JSONObject().put("path", path.toJson()).put("str", strength.toDouble()).put("mode", mode).put("zmax", maxZoom.toDouble())

    override fun equals(other: Any?) = other is Stabilization && other.path == path && other.strength == strength && other.mode == mode && other.maxZoom == maxZoom
    override fun hashCode() = ((path.hashCode() * 31 + strength.hashCode()) * 31 + mode) * 31 + maxZoom.hashCode()

    companion object {
        fun fromJson(o: JSONObject?): Stabilization? {
            val p = WindowTrack.fromJson(o?.optJSONObject("path")) ?: return null
            return Stabilization(p, o!!.optDouble("str", 0.6).toFloat(), o.optInt("mode", 0).coerceIn(0, 1), o.optDouble("zmax", 1.3).toFloat().coerceIn(1f, 1.5f))
        }
    }
}

/** Stabilization as a Media3 effect: the frame's source time → the correction (in NDC, y up). */
@OptIn(UnstableApi::class)
class StabMatrix(private val stab: Stabilization, private val clock: (Long) -> Double?) : androidx.media3.effect.MatrixTransformation {
    @Volatile private var w = 1920f
    @Volatile private var h = 1080f

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        if (inputWidth > 0 && inputHeight > 0) { w = inputWidth.toFloat(); h = inputHeight.toFloat() }
        return androidx.media3.common.util.Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        val src = clock(presentationTimeUs) ?: return android.graphics.Matrix()
        val t = stab.transform(src, w, h)
        val hw = w / 2; val hh = h / 2
        // the correction is in pixels (y down, around the center); NDC has y up (b, c, ty flip sign)
        // and different scales in x and y – hence via pixels, so the rotation comes out right
        val toPx = android.graphics.Matrix().apply { setScale(hw, hh) }
        val fromPx = android.graphics.Matrix().apply { setScale(1f / hw, 1f / hh) }
        val r = android.graphics.Matrix()
        r.setValues(floatArrayOf(t.a, -t.b, t.tx, -t.c, t.d, -t.ty, 0f, 0f, 1f))
        return android.graphics.Matrix(toPx).apply { postConcat(r); postConcat(fromPx) }
    }
}

/**
 * Noise reduction: each pixel becomes the mean of its 5 × 5 neighbors weighted by distance (σ 1.5 px) and by how
 * alike their color is (σ grows with [strength] from 0.012 to 0.112 in code values) – flat areas smooth out, edges
 * stay. The same weights as the shaders (GradeShaders, AgslShaders); edges read the nearest pixel.
 */
object Denoise {
    const val R = 2
    fun sigma(strength: Float) = 0.012f + 0.1f * strength.coerceIn(0f, 1f)

    fun apply(img: FloatArray, w: Int, h: Int, strength: Float): FloatArray {
        val out = FloatArray(img.size)
        val sr = sigma(strength)
        val inv = 1f / (2f * sr * sr)
        val sp = FloatArray((2 * R + 1) * (2 * R + 1)) { k -> val dx = k % (2 * R + 1) - R; val dy = k / (2 * R + 1) - R; kotlin.math.exp(-(dx * dx + dy * dy) / 4.5f) }
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = (y * w + x) * 3
            val r0 = img[i0]; val g0 = img[i0 + 1]; val b0 = img[i0 + 2]
            var ar = 0f; var ag = 0f; var ab = 0f; var ws = 0f
            var k = 0
            for (dy in -R..R) for (dx in -R..R) {
                val i = ((y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)) * 3
                val r = img[i]; val g = img[i + 1]; val b = img[i + 2]
                val d = (r - r0) * (r - r0) + (g - g0) * (g - g0) + (b - b0) * (b - b0)
                val wt = sp[k++] * kotlin.math.exp(-d * inv)
                ar += r * wt; ag += g * wt; ab += b * wt; ws += wt
            }
            out[i0] = ar / ws; out[i0 + 1] = ag / ws; out[i0 + 2] = ab / ws
        }
        return out
    }
}

/** Blur and pixelation on the CPU (stills, scopes): three box blurs ≈ Gaussian, block mean for pixelation. */
object Blur {
    fun apply(img: FloatArray, w: Int, h: Int, radiusPx: Float, pixelPx: Float): FloatArray {
        var a = img
        if (pixelPx >= 1f) a = pixelate(a, w, h, pixelPx.toInt().coerceAtLeast(2))
        if (radiusPx >= 0.5f) {
            // three boxes of radius r give roughly a Gaussian with sigma ≈ r; the disc radius ≈ 2 sigma
            val r = (radiusPx / 2f).toInt().coerceAtLeast(1)
            repeat(3) { a = box(a, w, h, r, true); a = box(a, w, h, r, false) }
        }
        return if (a === img) img.copyOf() else a
    }

    private fun box(src: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean): FloatArray {
        val out = FloatArray(src.size)
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val acc = FloatArray(3)
        for (l in 0 until lines) {
            fun idx(k: Int) = (if (horizontal) l * w + k.coerceIn(0, n - 1) else k.coerceIn(0, n - 1) * w + l) * 3
            acc.fill(0f)
            for (k in -r..r) { val i = idx(k); acc[0] += src[i]; acc[1] += src[i + 1]; acc[2] += src[i + 2] }
            val inv = 1f / (2 * r + 1)
            for (k in 0 until n) {
                val o = idx(k)
                out[o] = acc[0] * inv; out[o + 1] = acc[1] * inv; out[o + 2] = acc[2] * inv
                val add = idx(k + r + 1); val sub = idx(k - r)
                acc[0] += src[add] - src[sub]; acc[1] += src[add + 1] - src[sub + 1]; acc[2] += src[add + 2] - src[sub + 2]
            }
        }
        return out
    }

    private fun pixelate(src: FloatArray, w: Int, h: Int, b: Int): FloatArray {
        val out = FloatArray(src.size)
        var by = 0
        while (by < h) {
            var bx = 0
            while (bx < w) {
                val x1 = minOf(w, bx + b); val y1 = minOf(h, by + b)
                var r = 0f; var g = 0f; var bl = 0f; var n = 0
                for (y in by until y1) for (x in bx until x1) { val i = (y * w + x) * 3; r += src[i]; g += src[i + 1]; bl += src[i + 2]; n++ }
                r /= n; g /= n; bl /= n
                for (y in by until y1) for (x in bx until x1) { val i = (y * w + x) * 3; out[i] = r; out[i + 1] = g; out[i + 2] = bl }
                bx += b
            }
            by += b
        }
        return out
    }
}

object GradeShaders {
    const val VERTEX = TetraShaders.VERTEX

    const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uCurves;
uniform sampler2D uHsl;
uniform float uUseHsl;
uniform sampler2D uLut;
uniform vec3 uLift;
uniform vec3 uGammaExp;
uniform vec3 uGain;
uniform vec3 uOffset;
uniform vec3 uYSide;
uniform float uUseY;
uniform float uContrast;
uniform float uPivot;
uniform vec3 uWb;
uniform float uSat;
uniform mat3 uHue;
uniform float uMix;
uniform float uUseCurves;
uniform float uUseLut;
uniform float uLutSize;
uniform float uLutTiles;
uniform vec2 uLutTex;
uniform vec3 uLutMin;
uniform vec3 uLutMax;
uniform float uLutMix;
uniform float uUseQual;
uniform vec3 uQualHue;
uniform vec3 uQualSat;
uniform vec3 uQualLum;
uniform float uQualInv;
uniform float uUseWin;
uniform vec4 uWin;
uniform vec3 uWinOpt;
uniform vec2 uWinRot;
uniform float uAspect;
uniform float uBlur;
// noise reduction (Denoise): strength 0..1 and the size of a pixel in texture coordinates
uniform float uDenoise;
uniform vec2 uTexel;
uniform float uPixel;
varying vec2 vTexSamplingCoord;

vec3 fetchLut(vec3 i) {
  float tx = mod(i.b, uLutTiles);
  float ty = floor(i.b / uLutTiles);
  vec2 uv = vec2((tx * uLutSize + i.r + 0.5) / uLutTex.x, (ty * uLutSize + i.g + 0.5) / uLutTex.y);
  return texture2D(uLut, uv).rgb;
}

vec3 lut3(vec3 rgb) {
  vec3 p = clamp((rgb - uLutMin) / (uLutMax - uLutMin), 0.0, 1.0) * (uLutSize - 1.0);
  vec3 b = min(floor(p), vec3(uLutSize - 2.0));
  vec3 f = p - b;
  vec3 c000 = fetchLut(b);
  vec3 c111 = fetchLut(b + vec3(1.0));
  vec3 res;
  if (f.r >= f.g) {
    if (f.g >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.g) * fetchLut(b + vec3(1.0, 0.0, 0.0)) + (f.g - f.b) * fetchLut(b + vec3(1.0, 1.0, 0.0)) + f.b * c111;
    } else if (f.r >= f.b) {
      res = (1.0 - f.r) * c000 + (f.r - f.b) * fetchLut(b + vec3(1.0, 0.0, 0.0)) + (f.b - f.g) * fetchLut(b + vec3(1.0, 0.0, 1.0)) + f.g * c111;
    } else {
      res = (1.0 - f.b) * c000 + (f.b - f.r) * fetchLut(b + vec3(0.0, 0.0, 1.0)) + (f.r - f.g) * fetchLut(b + vec3(1.0, 0.0, 1.0)) + f.g * c111;
    }
  } else {
    if (f.b >= f.g) {
      res = (1.0 - f.b) * c000 + (f.b - f.g) * fetchLut(b + vec3(0.0, 0.0, 1.0)) + (f.g - f.r) * fetchLut(b + vec3(0.0, 1.0, 1.0)) + f.r * c111;
    } else if (f.b >= f.r) {
      res = (1.0 - f.g) * c000 + (f.g - f.b) * fetchLut(b + vec3(0.0, 1.0, 0.0)) + (f.b - f.r) * fetchLut(b + vec3(0.0, 1.0, 1.0)) + f.r * c111;
    } else {
      res = (1.0 - f.g) * c000 + (f.g - f.r) * fetchLut(b + vec3(0.0, 1.0, 0.0)) + (f.r - f.b) * fetchLut(b + vec3(1.0, 1.0, 0.0)) + f.b * c111;
    }
  }
  return res;
}

vec3 toHsl(vec3 c) {
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float l = (mx + mn) * 0.5;
  float d = mx - mn;
  float s = 0.0;
  float h = 0.0;
  if (d > 0.00001) {
    s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
    if (mx == c.r) {
      h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    } else if (mx == c.g) {
      h = (c.b - c.r) / d + 2.0;
    } else {
      h = (c.r - c.g) / d + 4.0;
    }
    h = h / 6.0;
  }
  return vec3(h, s, l);
}

float band(float v, float lo, float hi, float soft) {
  float s = max(soft, 0.001);
  return smoothstep(lo - s, lo, v) * (1.0 - smoothstep(hi, hi + s, v));
}

float keyOf(vec3 inRgb) {
  float k = 1.0;
  if (uUseQual > 0.5) {
    vec3 hsl = toHsl(inRgb);
    float kh = 1.0;
    if (uQualHue.y < 0.5) {
      float dh = abs(fract(hsl.x - uQualHue.x + 0.5) - 0.5);
      kh = 1.0 - smoothstep(uQualHue.y, uQualHue.y + max(uQualHue.z, 0.001), dh);
    }
    float kq = kh * band(hsl.y, uQualSat.x, uQualSat.y, uQualSat.z) * band(hsl.z, uQualLum.x, uQualLum.y, uQualLum.z);
    if (uQualInv > 0.5) kq = 1.0 - kq;
    k = k * kq;
  }
  if (uUseWin > 0.5) {
    vec2 p = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
    vec2 e = p - uWin.xy;
    e.x *= uAspect;
    e = vec2(e.x * uWinRot.x + e.y * uWinRot.y, -e.x * uWinRot.y + e.y * uWinRot.x);
    e.x /= uAspect;
    vec2 d2 = e / max(uWin.zw, vec2(0.0001));
    float d = uWinOpt.x > 1.5 ? max(abs(d2.x), abs(d2.y)) : length(d2);
    float s = max(uWinOpt.y * 0.5, 0.001);
    float kw = 1.0 - smoothstep(1.0 - s, 1.0 + s, d);
    if (uWinOpt.z > 0.5) kw = 1.0 - kw;
    k = k * kw;
  }
  return k;
}

vec3 colorIn(vec4 src) {
  vec2 uv = vTexSamplingCoord;
  vec3 cin = src.rgb;
  if (uPixel > 0.0) {
    vec2 cell = vec2(uPixel, uPixel * uAspect);
    // the grid from the image's top edge (texture y runs bottom-up) – the same blocks as the preview and the CPU path
    vec2 q = vec2(uv.x, 1.0 - uv.y);
    q = (floor(q / cell) + 0.5) * cell;
    uv = vec2(q.x, 1.0 - q.y);
    cin = texture2D(uTexSampler, uv).rgb;
  }
  if (uBlur > 0.0) {
    // disc of 32 points at the golden angle (even coverage), slightly heavier in the center
    vec3 acc = vec3(0.0);
    float ws = 0.0;
    for (int i = 0; i < 32; i++) {
      float fi = float(i);
      float rr = sqrt((fi + 0.5) / 32.0);
      float a = fi * 2.39996323;
      vec2 o = vec2(cos(a), sin(a)) * rr * uBlur;
      o.y *= uAspect;
      float wgt = 1.0 - 0.5 * rr;
      acc += texture2D(uTexSampler, uv + o).rgb * wgt;
      ws += wgt;
    }
    cin = acc / ws;
  }
  if (uDenoise > 0.0 && uBlur <= 0.0 && uPixel <= 0.0) {
    // edge-keeping: neighbors weighted by distance and by how alike their color is (as Denoise on the CPU)
    float sr = 0.012 + 0.1 * uDenoise;
    float inv = 1.0 / (2.0 * sr * sr);
    vec3 acc = vec3(0.0);
    float ws = 0.0;
    for (int dy = -2; dy <= 2; dy++) {
      for (int dx = -2; dx <= 2; dx++) {
        vec2 o = vec2(float(dx), float(dy));
        vec3 c = texture2D(uTexSampler, clamp(uv + o * uTexel, 0.5 * uTexel, 1.0 - 0.5 * uTexel)).rgb;
        vec3 d = c - cin;
        float wgt = exp(-dot(o, o) / 4.5) * exp(-dot(d, d) * inv);
        acc += c * wgt;
        ws += wgt;
      }
    }
    cin = acc / ws;
  }
  return cin;
}

// an HSL curve (a row of the table: 16 bits as high, low byte) at x 0..1, linearly interpolated
float hslTexel(float i, float row) {
  vec2 t = floor(texture2D(uHsl, vec2((i + 0.5) / 512.0, (row + 0.5) / 6.0)).rg * 255.0 + 0.5);
  return (t.r * 256.0 + t.g) / 65535.0;
}
float hslAt(float row, float x) {
  float p = clamp(x, 0.0, 1.0) * 511.0;
  float i = min(floor(p), 510.0);
  return mix(hslTexel(i, row), hslTexel(i + 1.0, row), p - i);
}

// the HSL curves (see HslCurves.apply): hue and saturation in the plane across the gray axis, luma Rec. 709
vec3 hslCurves(vec3 c) {
  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
  vec3 dm = c - vec3((c.r + c.g + c.b) / 3.0);
  float a1 = (dm.r - dm.g) / 1.4142135;
  float a2 = (dm.r + dm.g - 2.0 * dm.b) / 2.4494898;
  float sat = length(vec2(a1, a2)) * 1.2247449;
  float hue = 0.0;
  if (sat > 1e-6) hue = fract((atan(a2, a1) - 0.5235988) / 6.2831853 + 1.0);
  // the hue curves fade out towards gray, where the hue is mostly noise (see HslCurves.apply)
  float w = sat * sat / (sat * sat + 0.0009);
  float turn = (0.5 - hslAt(0.0, hue)) * 6.2831853 * w;
  float gain = 2.0 * mix(0.5, hslAt(1.0, hue), w) * 4.0 * hslAt(3.0, y) * hslAt(4.0, sat);
  float lum = (2.0 * hslAt(2.0, hue) - 1.0) * w + (2.0 * hslAt(5.0, sat) - 1.0);
  vec3 d = c - vec3(y);
  if (turn != 0.0) {
    float co = cos(turn); float si = sin(turn); float k = 0.57735027;
    float kd = (d.r + d.g + d.b) * k;
    vec3 kx = k * vec3(d.b - d.g, d.r - d.b, d.g - d.r);
    d = d * co + kx * si + vec3(k * kd * (1.0 - co));
    d -= vec3(dot(d, vec3(0.2126, 0.7152, 0.0722)));
  }
  return clamp(vec3(y + lum * y) + d * gain, 0.0, 1.0);
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 cin = colorIn(src);
  vec3 c = uLift + uGain * cin + uOffset;
  // gamma (not where the exponent is 1: there the values below 0 stay until the luminance is set)
  c = mix(pow(max(c, vec3(0.0)), uGammaExp), c, step(abs(uGammaExp - vec3(1.0)), vec3(1e-6)));
  if (uUseY > 0.5) {
    vec3 w = vec3(0.2126, 0.7152, 0.0722);
    float yv = uYSide.x + uYSide.y * dot(cin, w) + dot(uOffset, w);
    if (abs(uYSide.z - 1.0) > 1e-6) yv = pow(max(yv, 0.0), uYSide.z);
    c += vec3(yv - dot(c, w));
  }
  c = (c - vec3(uPivot)) * uContrast + vec3(uPivot);
  c = c * uWb;
  c = uHue * c;
  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = clamp(vec3(y) + (c - vec3(y)) * uSat, 0.0, 1.0);
  if (uUseCurves > 0.5) {
    vec3 u = c * (255.0 / 256.0) + vec3(0.5 / 256.0);
    c = vec3(texture2D(uCurves, vec2(u.r, 0.5)).r, texture2D(uCurves, vec2(u.g, 0.5)).g, texture2D(uCurves, vec2(u.b, 0.5)).b);
  }
  if (uUseHsl > 0.5) c = hslCurves(c);
  if (uUseLut > 0.5) {
    c = mix(c, clamp(lut3(c), 0.0, 1.0), uLutMix);
  }
  float k = keyOf(src.rgb);
  gl_FragColor = vec4(mix(src.rgb, c, uMix * k), src.a);
}
"""
}

/**
 * The grades' current values for playback, per (clip, node). The shader passes read from here every frame,
 * so a changed value needs no player rebuild – just a new frame. (Rebuilding the whole composition
 * on every slider release cost hundreds of MB each time and made everything lag.)
 */
object LiveGrades {
    private val map = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, GradeNode.Params>()

    private val layers = java.util.concurrent.ConcurrentHashMap<Long, LayerFx>()

    fun get(key: Pair<Long, Int>): GradeNode.Params? = map[key]
    /** The clip's key and mask right now (the controls take effect immediately). */
    fun layer(clip: Long): LayerFx? = layers[clip]

    /** One node's values right now, before they are committed (the film panel while a slider is dragged). */
    fun put(clip: Long, n: GradeNode) { if (!n.isNeutral) map[clip to n.id] = n.params() }

    fun publish(grades: Map<Long, ClipGrade>) {
        for ((clip, g) in grades) {
            for (n in g.chain) if (!n.isNeutral) map[clip to n.id] = n.params()
            g.layer?.let { layers[clip] = it }
        }
    }

    /** Which nodes have a shader pass – if this changes, the player must be rebuilt. */
    fun structure(grades: Map<Long, ClipGrade>): Map<Long, List<Int>> = grades.mapValues { (_, g) -> structureOf(g) }.filterValues { it.isNotEmpty() }

    /**
     * A clip's structure: the nodes with a shader pass and the stabilization (fixed in the pass). Tracking doesn't change
     * it – playback always gives the passes the frame's source time, so a newly tracked window follows without a rebuild.
     */
    fun structureOf(g: ClipGrade): List<Int> =
        // (the film's mode too: the fallback when the GPU can't run the film is fixed when built – colors or nothing)
        g.chain.filter { !it.isNeutral }.flatMap { n -> n.film?.let { f -> listOf(n.id, FILM_KEY, if (f.effectsOnly) 1 else 0) } ?: listOf(n.id) } +
            (g.stab?.let { listOf(STAB_KEY, it.hashCode()) } ?: emptyList()) +
            // key/mask (values live) and the blend mode (a compositor setting)
            (g.layer?.let { l -> listOf(LAYER_KEY, if (l.hasAlpha) 1 else 0, l.blend.ordinal, if (l.mask?.animated == true) 1 else 0) } ?: emptyList())

    private const val STAB_KEY = -2
    private const val LAYER_KEY = -4
    /** A node with film has one more pass (the film reads its settings from [get] – only on/off changes the structure). */
    private const val FILM_KEY = -3
}

/** A node as a Media3 effect (one shader pass). [live] = (clip, node) whose values are read from [LiveGrades]. */
@OptIn(UnstableApi::class)
class GradeNodeEffect(
    val p: GradeNode.Params,
    val live: Pair<Long, Int>? = null,
    /** Frame time → source time (seconds in the clip's file), for tracked windows. */
    val clock: ((Long) -> Double?)? = null,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(p, live, clock, useHdr)

    private class Program(private val initial: GradeNode.Params, private val live: Pair<Long, Int>?, private val clock: ((Long) -> Double?)?, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram
        private val curveTex: Int
        private val lutTex: Int
        private val hslTex: Int
        private var p = initial
        /** Without a LUT: a small identity LUT so the sampler always has a valid texture. */
        private var lut: CubeLut = p.lut ?: CubeLut.identity(2)
        // what is in the textures (reloaded only when the curves/LUT have changed)
        private var curvesUp: Array<FloatArray>? = p.curves
        private var lutUp: CubeLut? = p.lut
        private var hslUp: Array<FloatArray>? = p.hsl

        init {
            var pg: GlProgram? = null
            val ids = IntArray(1)
            val ids2 = IntArray(1)
            val ids3 = IntArray(1)
            try {
                pg = GlProgram(GradeShaders.VERTEX, GradeShaders.FRAGMENT)
                program = pg
                program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                GLES20.glGenTextures(1, ids, 0)
                curveTex = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, curveBytes(p.curves))
                GLES20.glGenTextures(1, ids2, 0)
                lutTex = ids2[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.texWidth, lut.texHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lut.textureBytes())
                // the HSL curves: exact texels (the shader interpolates the 16-bit values itself)
                GLES20.glGenTextures(1, ids3, 0)
                hslTex = ids3[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hslTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, HslCurve.TABLE, 6, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, HslCurves.textureBytes(p.hsl))
                GlUtil.checkGlError()
            } catch (e: Exception) {
                // release whatever was created (otherwise GPU memory leaks every time an effect fails to start)
                runCatching { pg?.delete() }
                runCatching { GLES20.glDeleteTextures(1, ids, 0); GLES20.glDeleteTextures(1, ids2, 0); GLES20.glDeleteTextures(1, ids3, 0) }
                throw VideoFrameProcessingException(e)
            }
        }

        private var aspect = 16f / 9f
        private var texW = 1920; private var texH = 1080

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth > 0 && inputHeight > 0) { aspect = inputWidth.toFloat() / inputHeight; texW = inputWidth; texH = inputHeight }
            PassTimes.noteSize(inputWidth, inputHeight)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) = PassTimes.measure("node") { drawTimed(inputTexId, presentationTimeUs) }

        private fun drawTimed(inputTexId: Int, presentationTimeUs: Long) {
            try {
                live?.let { LiveGrades.get(it) }?.let { cur -> if (cur !== p) adopt(cur) }
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uCurves", curveTex, 1)
                program.setFloatsUniform("uLift", p.lift)
                program.setFloatsUniform("uGammaExp", p.gammaExp)
                program.setFloatsUniform("uGain", p.gain)
                program.setFloatsUniform("uOffset", p.offset)
                program.setFloatsUniform("uYSide", p.ySide ?: floatArrayOf(0f, 1f, 1f))
                program.setFloatUniform("uUseY", if (p.ySide != null) 1f else 0f)
                program.setFloatUniform("uContrast", p.contrast)
                program.setFloatUniform("uPivot", p.pivot)
                program.setFloatsUniform("uWb", p.wb)
                program.setFloatUniform("uSat", p.sat)
                // GLSL matrices are column-major
                program.setFloatsUniform("uHue", transpose3(p.hueMat))
                program.setFloatUniform("uMix", p.mix)
                program.setFloatUniform("uUseCurves", if (p.curves != null) 1f else 0f)
                program.setSamplerTexIdUniform("uHsl", hslTex, 3)
                program.setFloatUniform("uUseHsl", if (p.hsl != null) 1f else 0f)
                program.setSamplerTexIdUniform("uLut", lutTex, 2)
                program.setFloatUniform("uUseLut", if (p.lut != null) 1f else 0f)
                program.setFloatUniform("uLutSize", lut.size.toFloat())
                program.setFloatUniform("uLutTiles", lut.tilesPerRow.toFloat())
                program.setFloatsUniform("uLutTex", floatArrayOf(lut.texWidth.toFloat(), lut.texHeight.toFloat()))
                program.setFloatsUniform("uLutMin", lut.domainMin)
                program.setFloatsUniform("uLutMax", lut.domainMax)
                program.setFloatUniform("uLutMix", p.lutMix)
                val q = p.qual
                program.setFloatUniform("uUseQual", if (q != null) 1f else 0f)
                program.setFloatsUniform("uQualHue", floatArrayOf((q?.hue ?: 0f) / 360f, (q?.hueWidth ?: 360f) / 720f, q?.soft ?: 0.1f))
                program.setFloatsUniform("uQualSat", floatArrayOf(q?.satLo ?: 0f, q?.satHi ?: 1f, q?.soft ?: 0.1f))
                program.setFloatsUniform("uQualLum", floatArrayOf(q?.lumLo ?: 0f, q?.lumHi ?: 1f, q?.soft ?: 0.1f))
                program.setFloatUniform("uQualInv", if (q?.invert == true) 1f else 0f)
                // tracked window: the position at this frame's source time
                val w = p.win?.let { win -> if (win.track != null) win.at(clock?.invoke(presentationTimeUs)) else win }
                program.setFloatUniform("uUseWin", if (w != null) 1f else 0f)
                program.setFloatsUniform("uWin", floatArrayOf(w?.cx ?: 0.5f, w?.cy ?: 0.5f, w?.rx ?: 0.3f, w?.ry ?: 0.3f))
                program.setFloatsUniform("uWinOpt", floatArrayOf((w?.shape ?: 1).toFloat(), w?.soft ?: 0.3f, if (w?.invert == true) 1f else 0f))
                val ang = Math.toRadians((w?.angle ?: 0f).toDouble())
                program.setFloatsUniform("uWinRot", floatArrayOf(cos(ang).toFloat(), sin(ang).toFloat()))
                program.setFloatUniform("uAspect", aspect)
                program.setFloatUniform("uBlur", p.blur)
                program.setFloatUniform("uDenoise", p.denoise)
                program.setFloatsUniform("uTexel", floatArrayOf(1f / texW, 1f / texH))
                program.setFloatUniform("uPixel", p.pixel)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        /** New values from [LiveGrades]: the textures are reloaded only if the curves or LUT have changed. */
        private fun adopt(cur: GradeNode.Params) {
            p = cur
            if (cur.curves !== curvesUp && !(cur.curves contentDeepEquals curvesUp)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, curveBytes(cur.curves))
            }
            curvesUp = cur.curves
            if (cur.hsl !== hslUp && !(cur.hsl contentDeepEquals hslUp)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hslTex)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, HslCurve.TABLE, 6, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, HslCurves.textureBytes(cur.hsl))
            }
            hslUp = cur.hsl
            if (cur.lut !== lutUp) {
                lut = cur.lut ?: CubeLut.identity(2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.texWidth, lut.texHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lut.textureBytes())
                lutUp = cur.lut
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
                GLES20.glDeleteTextures(3, intArrayOf(curveTex, lutTex, hslTex), 0)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        fun transpose3(m: FloatArray) = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

        /** The curves as a 256×1 RGBA texture (r, g, b = the channels' composite curves). */
        fun curveBytes(c: Array<FloatArray>?): ByteBuffer {
            val b = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until 256) {
                for (ch in 0..2) b.put(((c?.get(ch)?.get(i) ?: (i / 255f)) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                b.put(255.toByte())
            }
            b.flip()
            return b
        }
    }
}
