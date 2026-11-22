package se.gendut.klipp.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Auto reframe (pure, testable): a clip whose shape differs from the sequence's – a landscape clip in a portrait
 * sequence – is zoomed until it fills the frame, and moved so the face stays in the middle as it moves, like a camera
 * operator panning. The face's path (from face following) is smoothed (a calm pan, not every twitch), held inside the
 * picture (the frame never shows an edge), and written as few position keyframes as keep it within 2 px.
 */
object Reframe {
    /** Scale (%) and the position keyframes: (timeline seconds, x, y in sequence pixels). */
    class Plan(val scale: Double, val keys: List<Triple<Double, Double, Double>>)

    /** Frames per second of the smoothed path. */
    private const val RATE = 30.0

    /**
     * [centers]: the face's centre per frame (source seconds, x, y as fractions of the clip's picture), only frames
     * where it was found. [aspect]: the clip's picture (width / height). [toTimeline]: source → timeline seconds.
     * Null when there is nothing to follow.
     */
    fun plan(
        centers: List<Triple<Double, Float, Float>>, aspect: Float, w: Int, h: Int, toTimeline: (Double) -> Double,
        sigma: Double = 0.35, tolerance: Double = 2.0,
    ): Plan? {
        if (centers.isEmpty() || aspect <= 0f || w <= 0 || h <= 0) return null
        val c = centers.sortedBy { it.first }
        // the clip as shown at 100 % (fitted inside the frame), and the zoom that makes it cover the frame
        val fa = w.toDouble() / h
        val (fitW, fitH) = if (aspect > fa) w.toDouble() to w / aspect.toDouble() else h * aspect.toDouble() to h.toDouble()
        val k = max(w / fitW, h / fitH)
        val dw = fitW * k; val dh = fitH * k
        // an even time grid, the face interpolated over frames where it wasn't found
        val t0 = c.first().first; val t1 = c.last().first
        val n = max(1, ((t1 - t0) * RATE).toInt() + 1)
        val fx = DoubleArray(n); val fy = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = t0 + i / RATE
            while (j < c.size - 2 && c[j + 1].first < t) j++
            val a = c[j]; val b = c[min(j + 1, c.size - 1)]
            val u = if (b.first - a.first > 1e-9) ((t - a.first) / (b.first - a.first)).coerceIn(0.0, 1.0) else 0.0
            fx[i] = a.second + (b.second - a.second) * u
            fy[i] = a.third + (b.third - a.third) * u
        }
        val sx = gauss(fx, sigma * RATE); val sy = gauss(fy, sigma * RATE)
        // the clip's centre that puts the face in the middle, held so no edge shows
        fun cx(f: Double) = (w / 2.0 + dw * (0.5 - f)).coerceIn(w - dw / 2, dw / 2)
        fun cy(f: Double) = (h / 2.0 + dh * (0.5 - f)).coerceIn(h - dh / 2, dh / 2)
        val pts = (0 until n).map { i -> Triple(toTimeline(t0 + i / RATE), cx(sx[i]), cy(sy[i])) }
        return Plan(100.0 * k, simplify(pts, tolerance))
    }

    fun gauss(v: DoubleArray, sigma: Double): DoubleArray {
        if (sigma < 0.5) return v.copyOf()
        val r = (sigma * 3).toInt()
        val w = DoubleArray(2 * r + 1) { val d = (it - r).toDouble(); exp(-d * d / (2 * sigma * sigma)) }
        return DoubleArray(v.size) { i ->
            var a = 0.0; var s = 0.0
            for (j in -r..r) { val x = i + j; if (x in v.indices) { a += v[x] * w[j + r]; s += w[j + r] } }
            a / s
        }
    }

    /** Ramer–Douglas–Peucker over (t, x, y): the fewest points whose straight lines stay within [tol] px of the path. */
    fun simplify(p: List<Triple<Double, Double, Double>>, tol: Double): List<Triple<Double, Double, Double>> {
        if (p.size <= 2) return p
        val keep = BooleanArray(p.size); keep[0] = true; keep[p.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to p.size - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1.0; var at = -1
            for (i in a + 1 until b) {
                val u = (p[i].first - p[a].first) / (p[b].first - p[a].first).coerceAtLeast(1e-9)
                val ex = p[a].second + (p[b].second - p[a].second) * u
                val ey = p[a].third + (p[b].third - p[a].third) * u
                val d = max(abs(p[i].second - ex), abs(p[i].third - ey))
                if (d > worst) { worst = d; at = i }
            }
            if (worst > tol && at > 0) { keep[at] = true; stack.add(a to at); stack.add(at to b) }
        }
        return p.filterIndexed { i, _ -> keep[i] }
    }
}
