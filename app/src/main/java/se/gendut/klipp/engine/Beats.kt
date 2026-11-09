package se.gendut.klipp.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Finds the beat of a sound from its peak values (see [Peaks]): the tempo from the rise in level, then the grid on it. */
object Beats {
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 180.0
    /** Tempos near this count for more: half and double tempo fit a beat almost equally well. */
    private const val PREFERRED_BPM = 120.0
    /** The strongest rise in level must be at least this many times the mean, otherwise there is no clear beat. */
    private const val MIN_CONTRAST = 3f
    /** The first beats that decide where the grid starts. */
    private const val PHASE_BEATS = 8
    /** The tempo's self-similarity against the average over all tempos: below this it is noise or speech. */
    private const val MIN_RHYTHM = 1.8
    /** How far (peak values) a beat may lie from where the tempo puts it. */
    private const val FOLLOW = 4

    /** The beat times (s) in [peaks] ([rate] values per second); empty when no steady beat is found. */
    fun detect(peaks: FloatArray, rate: Int = Peaks.RATE): List<Double> {
        if (peaks.size < rate * 2) return emptyList()
        val onset = FloatArray(peaks.size)
        for (i in 2 until peaks.size) onset[i] = max(0f, (peaks[i] + peaks[i - 1]) - (peaks[i - 1] + peaks[i - 2]))
        val mean = onset.average().toFloat()
        if (mean <= 1e-5f || onset.max() < mean * MIN_CONTRAST) return emptyList()
        val lag = bestLag(onset, rate) ?: return emptyList()
        val phase = (0 until lag).maxByOrNull { p -> (0 until PHASE_BEATS).sumOf { k -> onset.getOrElse(p + k * lag) { 0f }.toDouble() } } ?: return emptyList()
        val out = ArrayList<Double>()
        var i = phase
        while (i < onset.size) {
            // on the actual rise near the grid, and on from there (the tempo is in whole values, the music isn't)
            val from = (i - FOLLOW).coerceAtLeast(0)
            val to = (i + FOLLOW).coerceAtMost(onset.size - 1)
            val at = (from..to).maxByOrNull { onset[it] } ?: i
            out += at.toDouble() / rate
            i = at + lag
        }
        return out
    }

    private fun bestLag(onset: FloatArray, rate: Int): Int? {
        val lo = (60.0 / MAX_BPM * rate).toInt().coerceAtLeast(1)
        val hi = (60.0 / MIN_BPM * rate).toInt()
        var best = -1
        var score = 0.0
        var total = 0.0
        var strongest = 0.0
        for (lag in lo..hi) {
            var sum = 0.0
            for (i in lag until onset.size) sum += onset[i] * onset[i - lag]
            val bpm = 60.0 * rate / lag
            val d = ln(bpm / PREFERRED_BPM)
            val s = sum / (onset.size - lag) * exp(-d * d)
            total += sum / (onset.size - lag)
            strongest = max(strongest, sum / (onset.size - lag))
            if (s > score) { score = s; best = lag }
        }
        // noise repeats at every lag about as much: a beat stands out from the average
        val mean = total / (hi - lo + 1)
        return best.takeIf { it > 0 && mean > 0.0 && strongest >= mean * MIN_RHYTHM }
    }
}
