package se.gendut.klipp.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Ducking from the words (pure, testable): with a transcript, the music is lowered where words are spoken – not
 * wherever the other tracks make a sound, so a cough, a door or room noise doesn't pull the music down, and short
 * pauses between words don't make it pump. Words closer than [MERGE] become one stretch; the music starts down
 * [PRE] before a stretch and comes back [POST] after it, over [FADE].
 */
object SpeechDuck {
    const val MERGE = 0.6
    const val PRE = 0.15
    const val POST = 0.25
    const val FADE = 0.35

    /** Spoken stretches (timeline seconds) from the words' (start, end). */
    fun regions(words: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for ((a, b) in words.sortedBy { it.first }) {
            val s = a - PRE; val e = b + POST
            if (out.isNotEmpty() && s - out.last().second <= MERGE + FADE) out[out.lastIndex] = out.last().first to max(out.last().second, e)
            else out += s to e
        }
        return out
    }

    /**
     * Volume keyframes (timeline seconds, dB) for a clip from [start] to [end] at [base] dB, lowered by [reduce] dB
     * (negative) over [regions]; empty if no region touches the clip.
     */
    fun keys(regions: List<Pair<Double, Double>>, start: Double, end: Double, base: Double, reduce: Double): List<Pair<Double, Double>> {
        val low = base + reduce
        val out = ArrayList<Pair<Double, Double>>()
        for ((s, e) in regions) {
            if (e + FADE <= start || s - FADE >= end) continue
            val pts = listOf(s - FADE to base, s to low, e to low, e + FADE to base)
            for ((t, v) in pts) {
                // a fade cut by the clip's edge: the level where the edge cuts it
                val tc = t.coerceIn(start, end)
                val vc = if (tc == t) v else interp(pts, tc)
                if (out.isEmpty() || tc > out.last().first + 1e-3) out += tc to vc
            }
        }
        return out
    }

    private fun interp(p: List<Pair<Double, Double>>, t: Double): Double {
        if (t <= p.first().first) return p.first().second
        for (i in 0 until p.size - 1) {
            val (a, va) = p[i]; val (b, vb) = p[i + 1]
            if (t <= b) return if (b - a < 1e-9) vb else va + (vb - va) * (t - a) / (b - a)
        }
        return p.last().second
    }

    /** Words that are speech: spoken while some clip on another track than the music plays (not the music's own lyrics). */
    fun speechOnly(words: List<Pair<Double, Double>>, others: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        words.filter { (a, b) -> others.any { (s, e) -> min(b, e) - max(a, s) > 0.02 } }
}
