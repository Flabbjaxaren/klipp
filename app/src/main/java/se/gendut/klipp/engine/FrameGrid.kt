package se.gendut.klipp.engine

/**
 * The timeline's frame grid: at most one frame per slot of [fps] frames a second – the export's frame rate rule
 * ([FrameRateEffect]), shared with playback's main player and its layers so all three pick the same frames.
 *
 * The slots are the timeline's own (slot k is at k / fps on the timeline – never counted from a clip's start). Which
 * frame takes which slot is counted from a stream's first frame: it takes the slot it falls in (read up to [LEAD_US]
 * early), and the rest are rounded from it. Counting from a frame rather than rounding each time on its own matters:
 * phone clips have uneven times (39.9–40.1 ms), and a clip whose frames sit half a slot off the grid would otherwise
 * round some frames up and some down – two frames in one slot and an empty one next to it.
 *
 * Times are µs on the timeline. A stream is one item (the export rebuilds its effect chain for each, so the rule starts
 * over there): callers [reset] at an item's start, a seek and a new chain. Pure – tested in FrameGridTest.
 */
class FrameGrid(fps: Double) {
    val stepUs = 1_000_000.0 / fps.coerceAtLeast(1.0)
    private var first = Long.MIN_VALUE
    private var base = 0L
    private var last = Long.MIN_VALUE
    private var prev = Long.MIN_VALUE

    /** The next frame begins a new stream. */
    fun reset() {
        first = Long.MIN_VALUE
        last = Long.MIN_VALUE
        prev = Long.MIN_VALUE
    }

    /** The slot of the frame at [tUs], or null when its slot already has a frame (the frame is dropped). */
    fun next(tUs: Long): Long? {
        // time went backwards (a new stream the caller didn't announce): start over
        if (prev != Long.MIN_VALUE && tUs < prev) first = Long.MIN_VALUE
        prev = tUs
        if (first == Long.MIN_VALUE) {
            first = tUs
            base = Math.floor((tUs + LEAD_US) / stepUs).toLong()
            last = base
            return base
        }
        val s = base + Math.round((tUs - first) / stepUs)
        if (s <= last) return null
        last = s
        return s
    }

    /** Where slot [slot] is on the timeline (µs). */
    fun timeOf(slot: Long): Long = Math.round(slot * stepUs)

    /** The earliest slot time (µs) a stream starting at [startUs] can show its first frame at. */
    fun earliestUs(startUs: Long): Long = timeOf(Math.floor(startUs / stepUs).toLong())

    companion object {
        /**
         * Whether the frame at [tUs] may be the last of its item ending at [endUs] (µs): within one and a half source frames
         * ([stepUs], the time since the frame before) of the end, read [LEAD_US] early. Playback's video sink ends an item
         * only once a frame at or after the item's last decoded frame comes out of the effects (Media3's
         * PlaybackVideoGraphWrapper: finalFramePresentationTimeUs) – and it waits for that before it changes decoders (a
         * proxy's HEVC to a pre-rendered piece's AVC). A last frame dropped, or moved to an earlier slot, never came: the
         * player stood still at the edit point until a seek (0.7–1.1 s before a whip at 25 %). Such a frame is therefore
         * never dropped and never moved earlier. The export ends its items otherwise and doesn't need this.
         */
        /**
         * Where an item's last frame (at [tUs], its grid time [out] or dropped) is shown: never before its own time (the
         * end of item), never on or after the next item's start [endUs] (its first frame is shown there), after the frame
         * before ([lastOut]).
         */
        fun lastFrameAt(tUs: Long, out: Long?, endUs: Long, lastOut: Long): Long {
            val cap = if (endUs == Long.MAX_VALUE) Long.MAX_VALUE else endUs - 1
            return maxOf(tUs, minOf(out ?: tUs, cap), if (lastOut == Long.MIN_VALUE) tUs else lastOut + 1)
        }

        fun nearEnd(tUs: Long, endUs: Long, stepUs: Long): Boolean =
            endUs != Long.MAX_VALUE && tUs + Math.round(1.5 * stepUs.coerceIn(1_000L, 200_000L)) >= endUs - LEAD_US

        /**
         * Whether a clip's frames already fall on the timeline's grid (then the grid changes nothing): played at speed 1,
         * at the timeline's rate, with its source frames' phase on the grid's – a segment starting between two slots, or
         * an in point between two source frames, puts every frame off it. [srcFps] 0 = not known: assumed off the grid.
         */
        fun onGrid(segStartS: Double, srcInS: Double, speed: Double, srcFps: Double, fps: Double): Boolean {
            if (fps <= 0.0) return true
            if (srcFps <= 0.0 || kotlin.math.abs(speed - 1.0) > 1e-3 || kotlin.math.abs(srcFps - fps) > 0.01) return false
            // source frame k is at k / srcFps in the file: on the timeline at segStart + k / fps - srcIn
            val phase = (segStartS - srcInS) * fps
            return kotlin.math.abs(phase - Math.round(phase)) * 1e6 / fps <= LEAD_US
        }

        /** A clip's video is read this much before its start (Playback's VIDEO_LEAD_US): its first frame may sit there. */
        const val LEAD_US = 1_000L

        /** The kept frames' slot times for one stream of frame times – the export's result, for tests. */
        fun keep(timesUs: List<Long>, fps: Double): List<Long> {
            val g = FrameGrid(fps)
            return timesUs.mapNotNull { t -> g.next(t)?.let { g.timeOf(it) } }
        }
    }
}

/**
 * A layer player's frames on the timeline grid (the edit point layer and the upper tracks). A layer item begins before
 * its segment (the edit point layer's pre-roll, played unseen), so per frame:
 * - before the segment ([FrameGrid.LEAD_US] early, as the export reads it): pre-roll, passed on at its own time – unless
 *   it lies within half a slot of the segment's first slot: it would be released for the same display refresh as the
 *   segment's first frame (moved there from a later source frame), and the layer, reading which frame its view holds
 *   by release time, took the pre-roll one and showed itself a frame late (vivo, whip at 25 %): dropped;
 * - from the segment on: the [FrameGrid] rule, the grid's stream beginning with the segment's first frame (as the
 *   export's item).
 * [next] gives the frame's presentation time on the timeline, or null to drop it. Pure – tested in FrameGridTest.
 */
class LayerGrid(fps: Double) {
    private val grid = FrameGrid(fps)
    private var segStart = Long.MIN_VALUE
    private var prevIn = Long.MIN_VALUE
    private var lastOut = Long.MIN_VALUE

    /** A seek, a new chain: the next frame starts over. */
    fun reset() {
        grid.reset()
        segStart = Long.MIN_VALUE
        prevIn = Long.MIN_VALUE
        lastOut = Long.MIN_VALUE
    }

    /** [segEndUs]: where the segment's item ends (Long.MAX_VALUE: not known) – its last frames are kept (FrameGrid.nearEnd). */
    fun next(tUs: Long, segStartUs: Long, segEndUs: Long = Long.MAX_VALUE): Long? {
        // another segment: its own stream (its pre-roll's times run on from the last one's, so time needn't go back)
        if (segStartUs != segStart) { grid.reset(); segStart = segStartUs; lastOut = Long.MIN_VALUE }
        val step = if (prevIn != Long.MIN_VALUE && tUs > prevIn) tUs - prevIn else 0L
        prevIn = tUs
        val last = step > 0 && FrameGrid.nearEnd(tUs, segEndUs, step)
        val out = if (tUs < segStartUs - FrameGrid.LEAD_US) { if (tUs < grid.earliestUs(segStartUs) - grid.stepUs / 2) tUs else null }
            else grid.next(tUs)?.let { grid.timeOf(it) }
        // the item's last frames: never dropped, never earlier than their own time
        val at = if (last) FrameGrid.lastFrameAt(tUs, out, segEndUs, lastOut) else out
        if (at != null) lastOut = at
        return at
    }

    /** The earliest time the segment's own frames are shown at (pre-roll frames all come before it). */
    fun earliestUs(segStartUs: Long): Long = grid.earliestUs(segStartUs)
}
