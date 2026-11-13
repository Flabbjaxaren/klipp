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
 *   it would reach the segment's first slot, where it would come after (or on) the segment's first frame: dropped;
 * - from the segment on: the [FrameGrid] rule, the grid's stream beginning with the segment's first frame (as the
 *   export's item).
 * [next] gives the frame's presentation time on the timeline, or null to drop it. Pure – tested in FrameGridTest.
 */
class LayerGrid(fps: Double) {
    private val grid = FrameGrid(fps)
    private var segStart = Long.MIN_VALUE

    /** A seek, a new chain: the next frame starts over. */
    fun reset() {
        grid.reset()
        segStart = Long.MIN_VALUE
    }

    fun next(tUs: Long, segStartUs: Long): Long? {
        // another segment: its own stream (its pre-roll's times run on from the last one's, so time needn't go back)
        if (segStartUs != segStart) { grid.reset(); segStart = segStartUs }
        if (tUs < segStartUs - FrameGrid.LEAD_US) return if (tUs < grid.earliestUs(segStartUs)) tUs else null
        return grid.next(tUs)?.let { grid.timeOf(it) }
    }

    /** The earliest time the segment's own frames are shown at (pre-roll frames all come before it). */
    fun earliestUs(segStartUs: Long): Long = grid.earliestUs(segStartUs)
}
