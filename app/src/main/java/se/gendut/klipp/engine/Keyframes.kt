package se.gendut.klipp.engine

/**
 * Keyframes from the engine. [mt] = media time in ticks (exact, for engine commands), [t] = timeline time in
 * seconds, [v] = value(s) (position: x, y), [interp] = the engine's interpolation for the segment after the keyframe,
 * [inInf]/[outInf] = the influence (0..1) of the incoming/outgoing ease handles.
 */
data class Kf(
    val mt: Long, val t: Double, val v: List<Double>, val interp: String = "Linear",
    val inInf: Double = DEFAULT_INFLUENCE, val outInf: Double = DEFAULT_INFLUENCE,
)

/** The engine's default influence (and Easy ease's): a third of the segment. */
const val DEFAULT_INFLUENCE = 1.0 / 3.0

/** The keyframe menu's interpolations. */
object KfEase {
    const val LINEAR = "linear"
    /** Bezier with a third of the segment's influence on both sides (Premiere's/FilmCraft's Easy Ease). */
    const val EASY = "easy"
    const val IN = "in"
    const val OUT = "out"
    const val HOLD = "hold"

    /** Which menu entry a keyframe's interpolation shows as. */
    fun of(interp: String): String = when (interp) {
        "Hold" -> HOLD
        "EaseIn" -> IN
        "EaseOut" -> OUT
        "Bezier", "AutoBezier", "ContinuousBezier" -> EASY
        else -> LINEAR
    }
}

object KfKeys {
    const val POSITION = "position"
    const val SCALE = "scale"
    const val ROTATION = "rotation"
    const val OPACITY = "opacity"
    const val LEVEL = "level"

    /** Key → (effect, parameter) in the engine. */
    fun engine(key: String): Pair<String, String> = when (key) {
        POSITION -> "motion" to "position"
        SCALE -> "motion" to "scale"
        ROTATION -> "motion" to "rotation"
        OPACITY -> "opacity" to "opacity"
        else -> "volume" to "level"
    }
}

/** The segment after a keyframe with [interp] leaves it eased (flat handle). */
fun easesOut(interp: String): Boolean = interp == "Bezier" || interp == "AutoBezier" || interp == "ContinuousBezier" || interp == "EaseOut"

/** The segment from a keyframe with [aInterp] lands eased in the next one (with [bInterp]). */
fun easesIn(aInterp: String, bInterp: String): Boolean =
    bInterp == "Bezier" || bInterp == "AutoBezier" || bInterp == "ContinuousBezier" || bInterp == "EaseIn" ||
        aInterp == "Bezier" || aInterp == "ContinuousBezier"

/**
 * The curve for the segment from [a] to [b] (0..1 → 0..1), exactly as the engine evaluates it (keyframe.rs `ease`):
 * a 1D cubic Bezier in normalized (time, value) space; eased ends have flat handles as long as their influence.
 */
fun ease(a: Kf, b: Kf, u: Double): Double = ease(a.interp, a.outInf, b.interp, b.inInf, u)

fun ease(aInterp: String, aOut: Double, bInterp: String, bIn: Double, u: Double): Double {
    if (aInterp == "Hold") return 0.0
    val out = easesOut(aInterp)
    val inn = easesIn(aInterp, bInterp)
    if (!out && !inn) return u
    val x1 = if (out) aOut.coerceIn(0.01, 1.0) else 1.0 / 3.0
    val y1 = if (out) 0.0 else 1.0 / 3.0
    val x2 = if (inn) 1.0 - bIn.coerceIn(0.01, 1.0) else 2.0 / 3.0
    val y2 = if (inn) 1.0 else 2.0 / 3.0
    return cubicBezierYForX(x1, y1, x2, y2, u)
}

/** CSS-style cubic-bezier(x1, y1, x2, y2): y at [x] (Newton, bisection as fallback) – the engine's solver. */
fun cubicBezierYForX(x1: Double, y1: Double, x2: Double, y2: Double, x: Double): Double {
    fun bx(s: Double) = 3 * (1 - s) * (1 - s) * s * x1 + 3 * (1 - s) * s * s * x2 + s * s * s
    fun by(s: Double) = 3 * (1 - s) * (1 - s) * s * y1 + 3 * (1 - s) * s * s * y2 + s * s * s
    fun dbx(s: Double) = 3 * (1 - s) * (1 - s) * x1 + 6 * (1 - s) * s * (x2 - x1) + 3 * s * s * (1 - x2)
    var s = x
    for (n in 0 until 8) {
        val d = dbx(s)
        if (kotlin.math.abs(d) < 1e-9) break
        val ns = s - (bx(s) - x) / d
        if (ns !in 0.0..1.0) break
        s = ns
    }
    if (kotlin.math.abs(bx(s) - x) > 1e-7) {
        var lo = 0.0; var hi = 1.0
        for (n in 0 until 60) {
            s = (lo + hi) / 2
            if (bx(s) < x) lo = s else hi = s
        }
    }
    return by(s)
}

/** The value (component [i]) at timeline time [t]; before the first / after the last keyframe: its value. */
fun List<Kf>.valueAt(t: Double, i: Int = 0): Double? {
    if (isEmpty()) return null
    if (t <= first().t) return first().v.getOrNull(i)
    if (t >= last().t) return last().v.getOrNull(i)
    for (k in 0 until size - 1) {
        val a = this[k]; val b = this[k + 1]
        if (t >= a.t && t < b.t) {
            val va = a.v.getOrNull(i) ?: return null
            val vb = b.v.getOrNull(i) ?: return va
            val p = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 1.0
            return va + (vb - va) * ease(a, b, p)
        }
    }
    return last().v.getOrNull(i)
}

/** The clip's properties at timeline time [t] (keyframes evaluated). Without keyframes: unchanged. */
fun ClipSnap.at(t: Double): ClipSnap {
    if (kf.isEmpty()) return this
    val p = kf[KfKeys.POSITION]
    return copy(
        x = p?.valueAt(t, 0) ?: x,
        y = p?.valueAt(t, 1) ?: y,
        scale = kf[KfKeys.SCALE]?.valueAt(t) ?: scale,
        rotation = kf[KfKeys.ROTATION]?.valueAt(t) ?: rotation,
        opacity = kf[KfKeys.OPACITY]?.valueAt(t) ?: opacity,
        levelDb = kf[KfKeys.LEVEL]?.valueAt(t) ?: levelDb,
    )
}

/**
 * Media time (ticks) for a timeline time within the clip, as the engine evaluates effects there (its
 * `moving_source_time_at`): reversed clips count down from the source out point; a freeze frame keeps moving.
 */
fun ClipSnap.mediaTicksAt(t: Double): Long {
    val sp = kotlin.math.abs(speed)
    val rel = secToTicks((t.coerceIn(start, end) - start) * sp)
    return if (reverse) secToTicks(sourceIn) + secToTicks(duration * sp) - rel - 1 else secToTicks(sourceIn) + rel
}
