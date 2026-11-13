package se.gendut.klipp.engine

/**
 * The paused player's picture: at project slot k the source frame the export shows there (SourceSampler's rule).
 *
 * Three times are kept apart: the playhead's slot (the app's state, never moved for this), the position the decoder is
 * sought to (earlier: Media3 shows the first frame at or after a position, at ms resolution – a frame 8 ms from the next
 * can't be hit by position alone), and the shown frame's own time (from the frames the effects receive).
 *
 * How: the player is sought a little before the slot and the main player's frame rate effect ([FrameRateEffect]) runs
 * its rule over the frames coming in, showing none, until it knows the slot's frame:
 * - a frame the rule puts on slot k: the slot's own frame – shown at once (it is the first that belongs there);
 * - a frame the rule puts after k: slot k has none of its own and holds the frame kept before – already passed by, so a
 *   second seek shows exactly that frame ([Result.Exact]);
 * - the rule's choice needs the frame before (the first frame after a seek has none): a frame is only trusted when one
 *   came before it in the same scan, or when the scan began at its segment's start (the segment's first frame);
 *   otherwise [Result.Earlier] – the caller seeks further back (a bounded number of times, then gives up).
 * Results carry the request's generation: an older seek's result never replaces a later one's. Pure apart from the
 * callback – tested in PausedFrameTest; the effect calls [onFlush] and [onFrame] on its GL thread.
 */
class PausedPick(private val post: (Result) -> Unit) {
    sealed class Result(val gen: Int) {
        /** The slot's frame is shown (its own time [frameUs], composition µs). */
        class Found(gen: Int, val frameUs: Long) : Result(gen)
        /** The slot holds the frame at [frameUs]: seek again and show exactly it. */
        class Exact(gen: Int, val frameUs: Long) : Result(gen)
        /** The scan began too late to know: seek further back. */
        class Earlier(gen: Int) : Result(gen)
    }

    /**
     * A request: show slot [slotUs] (composition µs). [atSegmentStart]: the seek goes to the start of the slot's segment
     * (its first frame is the segment's). [exactUs]: show exactly the frame at that time (the second seek of a hold).
     */
    data class Request(val gen: Int, val slotUs: Long, val atSegmentStart: Boolean, val exactUs: Long = Long.MIN_VALUE)

    private enum class State { IDLE, WAIT_FLUSH, SCAN, PASS, DROP }

    private var req: Request? = null
    private var state = State.IDLE
    private var seen = 0
    private var keptUs = Long.MIN_VALUE
    private var keptIsFirst = false

    /** A new request (before the seek it goes with): frames until the seek's flush are old ones. */
    @Synchronized fun request(r: Request) {
        req = r; state = State.WAIT_FLUSH; seen = 0; keptUs = Long.MIN_VALUE; keptIsFirst = false
        trace("request $r")
    }

    /** Diagnostics (CutTrace while it records). */
    var trace: (String) -> Unit = {}

    /** Playback (or a plain seek): the rule's frames pass as they are. */
    @Synchronized fun clear() { if (state != State.IDLE) trace("clear from $state"); req = null; state = State.IDLE }

    /** Whether frames are being chosen here (the effect drops what [onFrame] doesn't show). */
    val active: Boolean @Synchronized get() = state != State.IDLE && state != State.PASS

    /** The effect was flushed (a seek): the scan begins. */
    @Synchronized fun onFlush() {
        trace("flush in $state")
        if (state == State.WAIT_FLUSH || state == State.SCAN || state == State.DROP) { state = State.SCAN; seen = 0; keptUs = Long.MIN_VALUE; keptIsFirst = false }
    }

    /**
     * A frame at [tUs] that the rule presents at [presentedUs] (null: the rule drops it): [SHOW] it (the paused picture),
     * [DROP] it and take the next, or [HOLD] – drop it and take no more until the next seek's flush (the player's
     * scrubbing mode is off during a pick, see Playback: in it a seek waits until the one before showed a frame, and a
     * pick's first seek shows none). Without a frame shown
     * nothing holds the decoder back (a paused player's sink holds the one shown frame): while a seek is awaited, or
     * once the scan has its answer, frames dropped one after the other ran the decoder to the clip's end – the player
     * then stood (measured on the vivo: no flush for the next seek, every later seek timed out).
     */
    fun onFrame(tUs: Long, presentedUs: Long?): Int {
        var out: Result? = null
        val show = synchronized(this) {
            val r = req
            when {
                state == State.IDLE || state == State.PASS -> SHOW
                r == null || state != State.SCAN -> HOLD
                else -> {
                    seen++
                    if (r.exactUs != Long.MIN_VALUE) {
                        when {
                            kotlin.math.abs(tUs - r.exactUs) <= MATCH_US -> { state = State.PASS; out = Result.Found(r.gen, tUs); SHOW }
                            tUs > r.exactUs + MATCH_US -> { state = State.DROP; out = Result.Earlier(r.gen); HOLD }
                            else -> DROP
                        }
                    } else if (presentedUs == null) DROP
                    else if (presentedUs < r.slotUs - SLOT_US) { keptUs = tUs; keptIsFirst = seen == 1; DROP }
                    else if (presentedUs <= r.slotUs + SLOT_US) {
                        if (seen > 1 || r.atSegmentStart) { state = State.PASS; out = Result.Found(r.gen, tUs); SHOW }
                        else { state = State.DROP; out = Result.Earlier(r.gen); HOLD }
                    } else {
                        state = State.DROP
                        out = if (keptUs != Long.MIN_VALUE && (!keptIsFirst || r.atSegmentStart)) Result.Exact(r.gen, keptUs) else Result.Earlier(r.gen)
                        HOLD
                    }
                }
            }
        }
        if (state != State.IDLE) trace("frame t=$tUs presented=$presentedUs show=$show state=$state result=${out?.javaClass?.simpleName}")
        out?.let(post)
        return show
    }

    companion object {
        const val SHOW = 0
        const val DROP = 1
        const val HOLD = 2

        /** A frame's time against the one asked for (µs): the same frame decoded again. */
        private const val MATCH_US = 300L
        /** A presented time against the slot's (µs): slot times are rounded. */
        private const val SLOT_US = 2L
    }
}

/**
 * Where to seek for a paused slot (pure, tested): the attempts' starting points before the slot, never before the start
 * of the slot's item in the player. A scan that crossed into it from the item before stood: Media3 ends an item only
 * once a frame at or after its last decoded one leaves the effects (PlaybackVideoGraphWrapper), and the scan shows none
 * (measured on the vivo). An item's start is sought at its start rounded down to the ms – at most a fraction of a ms
 * inside the item before, after that item's last frame, so no frame of it is decoded for the output and the item ends
 * at once (Media3: no final frame); rounded up, a pre-rendered piece's first frame (on the item's first µs) was skipped.
 * Timeline seconds in, (position ms, whether it is the item's start) out; null when out of attempts.
 */
object PausedSeek {
    const val MAX_BACK_S = 4.0

    /** How far before the slot each attempt begins: a few slots, then further for slow sources and long gaps. */
    fun backs(fps: Double): DoubleArray = doubleArrayOf(3.0 / fps.coerceAtLeast(1.0), 0.5, 2.0)

    fun position(slotSec: Double, segStartSec: Double, attempt: Int, fps: Double): Pair<Long, Boolean>? {
        val b = backs(fps)
        val slot = 1.0 / fps.coerceAtLeast(1.0)
        // (on a whole ms: the item's own first ms; between two: the next ms, inside the item – Media3 then still hands on
        // the item's first frame, measured on the vivo; the ms before lies in the item before, whose last frame comes)
        val startMs = Math.ceil(segStartSec * 1000 - 1e-6).toLong().coerceAtLeast(0)
        // (a scan beginning within a slot after the item's start would take its first frame there for the item's first)
        fun reaches(i: Int) = slotSec - b[i] <= segStartSec + slot
        if (attempt < b.size && !reaches(attempt)) return Math.floor((slotSec - b[attempt]) * 1000).toLong() to false
        // the item's start: once, and never far (no unbounded scan)
        val first = (0 until b.size).firstOrNull { reaches(it) } ?: b.size
        if (attempt != first) return null
        return if (slotSec - segStartSec <= MAX_BACK_S) startMs to true else null
    }
}
