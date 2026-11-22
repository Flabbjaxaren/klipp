package se.gendut.klipp.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Face following – the algorithm, without MediaPipe (pure, testable). The same algorithm as the prototype
 * tools/face-eval/follow.py, line by line; FaceFollowTest replays fixtures recorded with it.
 *
 * 1. [observe]: one face's 478 MediaPipe landmarks → centre, size, roll and five keypoints. The size is the landmarks'
 *    RMS radius in 3D (MediaPipe's z is on the x scale), which a head turn does not change – a face box or the eye
 *    distance shrinks as the head turns away, and a window following it pumped.
 * 2. [Follower]: which face is the chosen one in every frame. Every face seen is a track (the chosen one and the
 *    others); faces and tracks are matched greedily by position (predicted with the track's velocity) and size, so
 *    another person is matched to their own track and never takes the chosen face's place – also while the chosen face
 *    is hidden. A lost face is predicted for a moment and its search window grows; it is taken again only by a face
 *    that fits its position and size. It also says where to look in the next frame ([Request]: a crop around the
 *    predicted face, and when to search wider).
 * 3. [smooth]: One Euro filter (Casiez et al. 2012), forwards and then backwards over the found frames – no lag (the
 *    track is computed offline), little smoothing while the face moves fast, much while it is still.
 */
object FaceFollow {
    // MediaPipe face mesh landmark indices
    private val R_EYE = intArrayOf(33, 133) // the person's right eye (image left): outer and inner corner
    private val L_EYE = intArrayOf(362, 263)
    private const val NOSE = 1
    private const val MOUTH_R = 61
    private const val MOUTH_L = 291

    // Matching
    const val GATE_D0 = 0.8 // centre distance gate in face sizes (one frame after the last sighting)
    const val GATE_D1 = 0.25 // … growing per frame missed
    const val GATE_DMAX = 3.0
    val GATE_SIZE = ln(1.6) // size ratio gate
    const val SIZE_WEIGHT = 2.0
    const val HOLD = 15 // frames the velocity keeps predicting a lost face
    const val OTHER_LIFE = 45 // frames another face's track is kept unseen
    const val VEL_KEEP = 0.5 // velocity update: old * keep + new * (1 - keep)
    const val VEL_DAMP = 0.8 // velocity of a missed track, per frame

    // Where to look
    const val CROP_K = 3.2 // crop side in face sizes
    const val CROP_GROW = 0.25 // per frame missed
    const val CROP_MIN = 48.0 // px of the analysed frame
    const val SEARCH_EVERY = 5 // frames between wide searches while lost (or not yet found)
    const val SEARCH_R = 4.0 // wide search radius in face sizes around the last position

    // Smoothing (One Euro, forwards and backwards). Units: centre in face sizes, size as ln, angle in radians; per second.
    const val POS_MIN_CUTOFF = 2.0
    const val POS_BETA = 0.6
    const val SIZE_MIN_CUTOFF = 0.4
    const val SIZE_BETA = 0.5
    const val ANGLE_MIN_CUTOFF = 0.6
    const val ANGLE_BETA = 0.4
    const val D_CUTOFF = 1.0

    /**
     * One face in a frame (display pixels of the analysed w×h frame): centre, size (RMS radius), roll (radians,
     * clockwise from the image x axis), five keypoints [x0, y0, …] normalized to the frame (eyes, nose tip, mouth
     * corners – as YuNet's, for the point cloud and auto reframe).
     */
    class Obs(val cx: Double, val cy: Double, val size: Double, val angle: Double, val pts: FloatArray)

    /** [x], [y], [z]: the landmarks normalized to the frame (x and z by the width, y by the height). */
    fun observe(x: FloatArray, y: FloatArray, z: FloatArray, w: Int, h: Int): Obs {
        val n = x.size
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in 0 until n) { sx += x[i] * w.toDouble(); sy += y[i] * h.toDouble(); sz += z[i] * w.toDouble() }
        val cx = sx / n; val cy = sy / n; val cz = sz / n
        var acc = 0.0
        for (i in 0 until n) {
            val dx = x[i] * w.toDouble() - cx; val dy = y[i] * h.toDouble() - cy; val dz = z[i] * w.toDouble() - cz
            acc += dx * dx + dy * dy + dz * dz
        }
        val size = sqrt(acc / n)
        val erx = (x[R_EYE[0]].toDouble() + x[R_EYE[1]]) / 2; val ery = (y[R_EYE[0]].toDouble() + y[R_EYE[1]]) / 2
        val elx = (x[L_EYE[0]].toDouble() + x[L_EYE[1]]) / 2; val ely = (y[L_EYE[0]].toDouble() + y[L_EYE[1]]) / 2
        val angle = atan2((ely - ery) * h, (elx - erx) * w)
        val pts = floatArrayOf(erx.toFloat(), ery.toFloat(), elx.toFloat(), ely.toFloat(), x[NOSE], y[NOSE], x[MOUTH_R], y[MOUTH_R], x[MOUTH_L], y[MOUTH_L])
        return Obs(cx, cy, size, angle, pts)
    }

    class Track(o: Obs) {
        var cx = o.cx; var cy = o.cy; var size = o.size
        var vx = 0.0; var vy = 0.0
        var missed = 0
        fun px() = cx + vx
        fun py() = cy + vy

        /** Matching cost, or null outside the gate. */
        fun cost(o: Obs): Double? {
            val d = hypot(o.cx - px(), o.cy - py()) / size
            val r = abs(ln(o.size / size))
            if (d > min(GATE_D0 + GATE_D1 * missed, GATE_DMAX) || r > GATE_SIZE) return null
            return d + SIZE_WEIGHT * r
        }

        fun hit(o: Obs) {
            val n = missed + 1
            vx = vx * VEL_KEEP + (o.cx - cx) / n * (1 - VEL_KEEP)
            vy = vy * VEL_KEEP + (o.cy - cy) / n * (1 - VEL_KEEP)
            cx = o.cx; cy = o.cy; size = o.size
            missed = 0
        }

        fun miss() {
            missed++
            if (missed <= HOLD) {
                cx += vx; cy += vy
                vx *= VEL_DAMP; vy *= VEL_DAMP
            } else {
                vx = 0.0; vy = 0.0
            }
        }
    }

    /** Where to look in the next frame (display pixels): a square crop, and optionally a wide search ([x, y, r]). */
    class Request(val cx: Double, val cy: Double, val side: Double, val search: DoubleArray?)

    /** [wantX], [wantY], [wantR]: the window (display pixels) – the face nearest it is the one followed. */
    class Follower(val w: Int, val h: Int, wantX: Double, wantY: Double, wantR: Double) {
        private val wx = wantX
        private val wy = wantY
        private val wr = max(wantR, 0.02 * min(w, h))
        var target: Track? = null
            private set
        private var others = ArrayList<Track>()
        private var frame = 0

        private fun acquire(faces: List<Obs>): Obs? {
            var best: Obs? = null
            var bd = 0.0
            for (o in faces) {
                val d = hypot(o.cx - wx, o.cy - wy)
                if (d < max(wr + o.size * 2.0, 0.08 * min(w, h)) && (best == null || d < bd)) { best = o; bd = d }
            }
            return best
        }

        /** The faces found in this frame → the chosen face (or null when it isn't found). */
        fun step(faces: List<Obs>): Obs? {
            frame++
            val tg = target
            if (tg == null) {
                val pick = acquire(faces)
                if (pick != null) target = Track(pick)
                for (o in faces) if (o !== pick) others.add(Track(o))
                return pick
            }
            val tracks = listOf(tg) + others
            class Pair(val c: Double, val ti: Int, val oi: Int)
            val pairs = ArrayList<Pair>()
            for ((ti, t) in tracks.withIndex()) for ((oi, o) in faces.withIndex()) t.cost(o)?.let { pairs += Pair(it, ti, oi) }
            pairs.sortWith(compareBy<Pair>({ it.c }, { it.ti }, { it.oi }))
            val usedT = HashSet<Int>(); val usedO = HashSet<Int>(); val match = HashMap<Int, Int>()
            for (p in pairs) {
                if (p.ti in usedT || p.oi in usedO) continue
                usedT += p.ti; usedO += p.oi; match[p.ti] = p.oi
            }
            for ((ti, t) in tracks.withIndex()) match[ti]?.let { t.hit(faces[it]) } ?: t.miss()
            others = others.filterTo(ArrayList()) { it.missed <= OTHER_LIFE }
            for ((oi, o) in faces.withIndex()) if (oi !in usedO) others.add(Track(o))
            return match[0]?.let { faces[it] }
        }

        /** Where to look in the next frame. */
        fun request(): Request {
            val t = target
            if (t == null) {
                val side = min(max(CROP_K * wr, CROP_MIN), max(w, h).toDouble())
                // the whole window is searched at the start and then every few frames until a face is found
                return Request(wx, wy, side, if (frame % SEARCH_EVERY == 0) doubleArrayOf(wx, wy, 2 * wr) else null)
            }
            val m = min(t.missed, 8)
            val side = min(max(CROP_K * t.size * (1 + CROP_GROW * m), CROP_MIN), max(w, h).toDouble())
            val search = if (t.missed >= 3 && t.missed % SEARCH_EVERY == 0) doubleArrayOf(t.px(), t.py(), SEARCH_R * t.size) else null
            return Request(t.px(), t.py(), side, search)
        }
    }

    // ---- smoothing ----

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    /** One pass over samples [x] at times [t] (seconds); the speed is measured in units of [scale] (per sample). */
    fun oneEuro(t: DoubleArray, x: DoubleArray, scale: DoubleArray, minCutoff: Double, beta: Double, dCutoff: Double = D_CUTOFF): DoubleArray {
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        var prev = x[0]
        var dprev = 0.0
        out[0] = prev
        for (i in 1 until x.size) {
            val dt = max(abs(t[i] - t[i - 1]), 1e-4)
            val dx = (x[i] - prev) / dt
            dprev += alpha(dCutoff, dt) * (dx - dprev)
            val cutoff = minCutoff + beta * abs(dprev) / scale[i]
            prev += alpha(cutoff, dt) * (x[i] - prev)
            out[i] = prev
        }
        return out
    }

    /** Forwards, then backwards over the result: no lag. */
    fun oneEuroFb(t: DoubleArray, x: DoubleArray, scale: DoubleArray, minCutoff: Double, beta: Double): DoubleArray {
        val f = oneEuro(t, x, scale, minCutoff, beta)
        val rt = DoubleArray(t.size) { -t[t.size - 1 - it] }
        val b = oneEuro(rt, f.reversedArray(), scale.reversedArray(), minCutoff, beta)
        return b.reversedArray()
    }

    fun unwrap(a: DoubleArray): DoubleArray {
        val out = a.copyOf()
        for (i in 1 until out.size) {
            var d = out[i] - out[i - 1]
            while (d > PI) { out[i] -= 2 * PI; d -= 2 * PI }
            while (d < -PI) { out[i] += 2 * PI; d += 2 * PI }
        }
        return out
    }

    /** A found frame: source time and the face (display pixels). */
    class State(val t: Double, val cx: Double, val cy: Double, val size: Double, val angle: Double)

    /** The frames where the face was found, sorted by time → the same, smoothed. */
    fun smooth(s: List<State>): List<State> {
        if (s.size < 3) return s
        val t = DoubleArray(s.size) { s[it].t }
        val ones = DoubleArray(s.size) { 1.0 }
        val ls = oneEuroFb(t, DoubleArray(s.size) { ln(s[it].size) }, ones, SIZE_MIN_CUTOFF, SIZE_BETA)
        val size = DoubleArray(s.size) { exp(ls[it]) }
        val cx = oneEuroFb(t, DoubleArray(s.size) { s[it].cx }, size, POS_MIN_CUTOFF, POS_BETA)
        val cy = oneEuroFb(t, DoubleArray(s.size) { s[it].cy }, size, POS_MIN_CUTOFF, POS_BETA)
        val an = oneEuroFb(t, unwrap(DoubleArray(s.size) { s[it].angle }), ones, ANGLE_MIN_CUTOFF, ANGLE_BETA)
        return List(s.size) { State(t[it], cx[it], cy[it], size[it], an[it]) }
    }
}
