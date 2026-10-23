package se.gendut.klipp.engine

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The HSL curves: Hue vs Hue/Sat/Lum, Lum vs Sat, Sat vs Sat, Sat vs Lum. The x axis is the pixel's hue, luma or
 * saturation; the curve's height says how much to change at that x. [periodic] = the x axis is the hue circle.
 */
enum class HslKind(val key: String, val periodic: Boolean) {
    HUE_HUE("hh", true), HUE_SAT("hs", true), HUE_LUM("hl", true),
    LUM_SAT("ls", false), SAT_SAT("ss", false), SAT_LUM("sl", false),
}

/**
 * One HSL curve: points (x, y) in 0..1, y = 0.5 is no change, higher = more (hue: turned, saturation: stronger,
 * luminance: brighter). Each point has a flat tangent whose handles reach half-way to the neighbouring point
 * (the curve rests at every point and turns smoothly between them). Empty = no change.
 */
data class HslCurve(val points: List<Pair<Float, Float>> = emptyList()) {
    val isNeutral get() = points.all { abs(it.second - 0.5f) < 1e-4f }

    /** The curve's height at [n] evenly spaced x from 0 to 1. */
    fun table(periodic: Boolean, n: Int = TABLE): FloatArray {
        val out = FloatArray(n) { 0.5f }
        if (points.isEmpty()) return out
        var a = points.sortedBy { it.first }
        if (periodic) a = a.map { (x, y) -> (x - 1f) to y } + a + a.map { (x, y) -> (x + 1f) to y }
        for (i in 0 until n) out[i] = at(a, i.toFloat() / (n - 1))
        return out
    }

    fun toJson(): JSONArray = JSONArray().apply { points.forEach { (x, y) -> put(JSONArray().put(x.toDouble()).put(y.toDouble())) } }

    companion object {
        const val TABLE = 512

        fun fromJson(a: JSONArray?): HslCurve = if (a == null) HslCurve() else
            HslCurve((0 until a.length()).map { a.getJSONArray(it).let { p -> p.optDouble(0).toFloat() to p.optDouble(1).toFloat() } }.sortedBy { it.first })

        /**
         * The height at [x] on sorted anchors [a]: between two anchors a cubic Bezier with control points at the
         * segment's middle x and each anchor's own height; outside the anchors the end height.
         */
        fun at(a: List<Pair<Float, Float>>, x: Float): Float {
            if (x <= a.first().first) return a.first().second
            if (x >= a.last().first) return a.last().second
            val j = a.indexOfLast { it.first <= x }.coerceIn(0, a.size - 2)
            val (x0, y0) = a[j]; val (x1, y1) = a[j + 1]
            if (x1 - x0 < 1e-6f) return y1
            // x(t) = x0·(B0 + B1) + x1·(B2 + B3) with the middle as both control x – monotone, solved by bisection
            val m = (x0 + x1) / 2f
            var lo = 0f; var hi = 1f
            repeat(26) {
                val t = (lo + hi) / 2f
                val u = 1f - t
                val xt = x0 * u * u * u + 3f * m * t * u * u + 3f * m * t * t * u + x1 * t * t * t
                if (xt < x) lo = t else hi = t
            }
            val t = (lo + hi) / 2f
            val u = 1f - t
            return y0 * (u * u * u + 3f * t * u * u) + y1 * (3f * t * t * u + t * t * t)
        }
    }
}

/** The node's six HSL curves. */
data class HslCurves(
    val hueHue: HslCurve = HslCurve(), val hueSat: HslCurve = HslCurve(), val hueLum: HslCurve = HslCurve(),
    val lumSat: HslCurve = HslCurve(), val satSat: HslCurve = HslCurve(), val satLum: HslCurve = HslCurve(),
) {
    operator fun get(k: HslKind): HslCurve = when (k) {
        HslKind.HUE_HUE -> hueHue; HslKind.HUE_SAT -> hueSat; HslKind.HUE_LUM -> hueLum
        HslKind.LUM_SAT -> lumSat; HslKind.SAT_SAT -> satSat; HslKind.SAT_LUM -> satLum
    }

    fun with(k: HslKind, c: HslCurve): HslCurves = when (k) {
        HslKind.HUE_HUE -> copy(hueHue = c); HslKind.HUE_SAT -> copy(hueSat = c); HslKind.HUE_LUM -> copy(hueLum = c)
        HslKind.LUM_SAT -> copy(lumSat = c); HslKind.SAT_SAT -> copy(satSat = c); HslKind.SAT_LUM -> copy(satLum = c)
    }

    val isNeutral get() = HslKind.entries.all { this[it].isNeutral }

    /** The six tables (in [HslKind] order), as the shaders and the CPU read them; null when nothing changes. */
    fun tables(): Array<FloatArray>? = if (isNeutral) null else Array(6) { i -> HslKind.entries[i].let { k -> this[k].table(k.periodic) } }

    fun toJson(): JSONObject = JSONObject().apply { HslKind.entries.forEach { k -> if (this@HslCurves[k].points.isNotEmpty()) put(k.key, this@HslCurves[k].toJson()) } }

    companion object {
        fun fromJson(o: JSONObject?): HslCurves {
            if (o == null) return HslCurves()
            var c = HslCurves()
            for (k in HslKind.entries) c = c.with(k, HslCurve.fromJson(o.optJSONArray(k.key)))
            return c
        }

        private const val SQ2 = 1.4142135f
        private const val SQ6 = 2.4494898f
        /** Saturation scale: a pure primary (e.g. 1, 0, 0) has saturation 1. */
        private val SAT_K = sqrt(1.5f)

        private fun sample(t: FloatArray, x: Float): Float {
            val p = x.coerceIn(0f, 1f) * (t.size - 1)
            val i = floor(p).toInt().coerceAtMost(t.size - 2)
            return t[i] + (t[i + 1] - t[i]) * (p - i)
        }

        /**
         * The curves on one pixel ([t] = [tables]). Hue and saturation are the angle and length of the color in the
         * plane across the gray axis (red at 0, then yellow, green …; gray has hue 0), luma is Rec. 709 Y – all of the
         * pixel as it comes in. Hue vs Hue turns the color around the gray axis by up to ±180°, the saturation curves
         * scale it (0…2×), the luminance curves add to all channels alike (up to ±Y); luma is kept while turning
         * and scaling. The hue curves fade out towards gray ([hueWeight]).
         */
        /** The pixel's place on the curves' x axes: hue (0..1, red at 0), saturation and Rec. 709 luma – as [apply] measures them. */
        fun measure(rgb: FloatArray): FloatArray {
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
            val mean = (r + g + b) / 3f
            val a1 = ((r - mean) - (g - mean)) / SQ2
            val a2 = ((r - mean) + (g - mean) - 2f * (b - mean)) / SQ6
            val sat = hypot(a1, a2) * SAT_K
            return floatArrayOf(hueOf(a1, a2, sat), sat, 0.2126f * r + 0.7152f * g + 0.0722f * b)
        }

        /**
         * How much of the hue curves a pixel of saturation [sat] gets: half at 0.03, 90 % at 0.09, nearly none at gray.
         * Near gray the hue is mostly noise, so a hue curve would hit neighbouring pixels differently and a pale sky
         * would break into specks (most visibly with Hue vs Lum, which moves the pixel's brightness).
         */
        fun hueWeight(sat: Float): Float = sat * sat / (sat * sat + HUE_FADE * HUE_FADE)
        private const val HUE_FADE = 0.03f

        /** The angle in the plane across the gray axis as 0..1 with red at 0 (gray: 0). */
        private fun hueOf(a1: Float, a2: Float, sat: Float): Float =
            if (sat > 1e-6f) (((Math.toDegrees(atan2(a2, a1).toDouble()) - 30.0) % 360.0 + 360.0) % 360.0 / 360.0).toFloat() else 0f

        /** Where a color lies on curve [k]'s x axis (the dropper). */
        fun axis(k: HslKind, rgb: FloatArray): Float = measure(rgb).let { m ->
            when (k) {
                HslKind.HUE_HUE, HslKind.HUE_SAT, HslKind.HUE_LUM -> m[0]
                HslKind.LUM_SAT -> m[2].coerceIn(0f, 1f)
                HslKind.SAT_SAT, HslKind.SAT_LUM -> m[1].coerceIn(0f, 1f)
            }
        }

        fun apply(t: Array<FloatArray>, rgb: FloatArray) {
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
            val yl = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val mean = (r + g + b) / 3f
            val a1 = ((r - mean) - (g - mean)) / SQ2
            val a2 = ((r - mean) + (g - mean) - 2f * (b - mean)) / SQ6
            val sat = hypot(a1, a2) * SAT_K
            val hue = hueOf(a1, a2, sat)
            // the curves store "up = more": turn by (0.5 − height)·360°, scale by 2·height, add (2·height − 1)·Y
            val w = hueWeight(sat)
            val turn = (0.5f - sample(t[0], hue)) * 2f * Math.PI.toFloat() * w
            val gain = 2f * (0.5f + (sample(t[1], hue) - 0.5f) * w) * 2f * sample(t[3], yl) * 2f * sample(t[4], sat)
            val lum = (2f * sample(t[2], hue) - 1f) * w + (2f * sample(t[5], sat) - 1f)
            // the chroma (pixel − luma) turned about the gray axis, luma restored, then scaled
            var dr = r - yl; var dg = g - yl; var db = b - yl
            if (turn != 0f) {
                val c = cos(turn); val s = sin(turn); val k = 1f / sqrt(3f)
                val kd = (dr + dg + db) * k
                val cx = k * (db - dg); val cy = k * (dr - db); val cz = k * (dg - dr)
                val nr = dr * c + cx * s + k * kd * (1 - c)
                val ng = dg * c + cy * s + k * kd * (1 - c)
                val nb = db * c + cz * s + k * kd * (1 - c)
                val fix = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
                dr = nr - fix; dg = ng - fix; db = nb - fix
            }
            val base = yl + lum * yl
            rgb[0] = base + dr * gain
            rgb[1] = base + dg * gain
            rgb[2] = base + db * gain
        }

        /** The tables as a 512×6 RGBA texture: a row per curve ([HslKind] order), 16 bits in red (high) and green (low). */
        fun textureBytes(t: Array<FloatArray>?): ByteBuffer {
            val n = HslCurve.TABLE
            val buf = ByteBuffer.allocateDirect(n * 6 * 4).order(ByteOrder.nativeOrder())
            for (row in 0 until 6) for (i in 0 until n) {
                val v = ((t?.get(row)?.get(i) ?: 0.5f).coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
                buf.put((v shr 8).toByte()); buf.put((v and 255).toByte()); buf.put(0); buf.put(255.toByte())
            }
            buf.flip()
            return buf
        }
    }
}
