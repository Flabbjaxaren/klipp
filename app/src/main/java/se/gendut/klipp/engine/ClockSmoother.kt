package se.gendut.klipp.engine

/**
 * The playback clock without steps: the player's time is the audio output's position, and that estimate is corrected
 * now and then (Android hands out a new audio timestamp about every ten seconds; the position counted from the last
 * one has drifted by then) – a step of some 25 ms that the picture follows: the frames on screen at that moment stand
 * a refresh or two longer (measured on the vivo: two to four frames of 65–85 ms about ten seconds after every start).
 *
 * Here a step is taken up gradually: the reported time runs with the system clock and closes in on the audio position
 * by at most [SLEW] of the time passed – 25 ms in two and a half seconds, a quarter of a millisecond per frame. Only
 * while the audio position itself is running: until it has started after play (the picture must not start before the
 * sound is heard), while it stands (buffering) and after anything larger than [JUMP_US] the position passes as it is.
 * Pure – tested in ClockSmootherTest.
 */
class ClockSmoother {
    private var lastRaw = Long.MIN_VALUE
    private var lastRawNs = 0L
    private var movingSinceNs = Long.MIN_VALUE
    private var out = Long.MIN_VALUE
    private var outNs = 0L

    /** Diagnostics: a step of [it] µs is being taken up. */
    var onStep: (Long) -> Unit = {}
    private var stepping = false

    /** A seek, a pause, a new track: the next position starts over. */
    fun reset() { lastRaw = Long.MIN_VALUE; movingSinceNs = Long.MIN_VALUE; out = Long.MIN_VALUE; stepping = false }

    /** The audio position [rawUs] at system time [nowNs] with playback speed [speed]: the time to report. */
    fun next(rawUs: Long, nowNs: Long, speed: Float = 1f): Long {
        // is the audio position running? (it stands until the track has started, and while the player buffers)
        if (lastRaw == Long.MIN_VALUE || rawUs < lastRaw - JUMP_US) { lastRaw = rawUs; lastRawNs = nowNs; movingSinceNs = Long.MIN_VALUE; out = Long.MIN_VALUE; return rawUs }
        // (a little backwards is a correction of the estimate – it is running)
        if (rawUs != lastRaw) {
            if (movingSinceNs == Long.MIN_VALUE) movingSinceNs = nowNs
            lastRaw = rawUs; lastRawNs = nowNs
        } else if (nowNs - lastRawNs > STILL_NS) { movingSinceNs = Long.MIN_VALUE; out = Long.MIN_VALUE; return rawUs }
        if (movingSinceNs == Long.MIN_VALUE || nowNs - movingSinceNs < SETTLE_NS) { out = Long.MIN_VALUE; return rawUs }
        if (out == Long.MIN_VALUE) { out = rawUs; outNs = nowNs; return rawUs }
        val dtUs = (nowNs - outNs) / 1000
        if (dtUs <= 0) return out
        val expected = out + Math.round(dtUs * speed.toDouble())
        val diff = rawUs - expected
        val next = if (kotlin.math.abs(diff) > JUMP_US) rawUs else {
            val slew = Math.round(dtUs * SLEW).coerceAtLeast(1)
            if (kotlin.math.abs(diff) > STEP_US && !stepping) { stepping = true; onStep(diff) } else if (kotlin.math.abs(diff) <= slew) stepping = false
            expected + diff.coerceIn(-slew, slew)
        }
        // (never backwards: the player's time only rises while it plays)
        out = maxOf(next, out); outNs = nowNs
        return out
    }

    companion object {
        /** How fast a difference is taken up: this share of the time passed. */
        const val SLEW = 0.01
        /** Larger than this is no correction of the estimate (a seek's first positions, a stall): passed as it is. */
        const val JUMP_US = 200_000L
        /** The audio position has stood this long: it isn't running. */
        const val STILL_NS = 60_000_000L
        /** Smoothing begins this long after the position started running (its first estimates settle). */
        const val SETTLE_NS = 300_000_000L
        /** A difference worth a diagnostics line (µs). */
        const val STEP_US = 3_000L
    }
}
