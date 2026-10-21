package se.gendut.klipp.engine

/**
 * Keyframes from the engine. [mt] = media time in ticks (exact, for engine commands), [t] = timeline time in
 * seconds, [v] = value(s) (position: x, y), [interp] = the engine's interpolation for the segment after the keyframe.
 */
data class Kf(val mt: Long, val t: Double, val v: List<Double>, val interp: String = "Linear")

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

/** The curve for the segment between two keyframes (0..1 → 0..1), per the first keyframe's interpolation. */
fun ease(interp: String, p: Double): Double = when (interp) {
    "Hold" -> 0.0
    "EaseOut" -> p * p // leaves the keyframe smoothly
    "EaseIn" -> 1 - (1 - p) * (1 - p) // lands smoothly in the next
    "Bezier", "AutoBezier", "ContinuousBezier" -> p * p * (3 - 2 * p)
    else -> p
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
            return va + (vb - va) * ease(a.interp, p)
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
