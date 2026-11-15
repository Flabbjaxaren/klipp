package se.gendut.klipp.engine

/**
 * The timeline's frame grid: slot k is at k / [fps] on the timeline (µs), never counted from a clip's or a render's
 * start. Which source frame each slot shows is [SourceSampler]'s rule.
 */
class FrameGrid(fps: Double) {
    val stepUs = 1_000_000.0 / fps.coerceAtLeast(1.0)
    private val fpsV = fps.coerceAtLeast(1.0)

    /** Where slot [slot] is on the timeline (µs). */
    fun timeOf(slot: Long): Long = Math.round(slot * stepUs)

    /** The first slot at or after [tUs] (timeline µs). */
    fun slotAtOrAfter(tUs: Long): Long = Math.ceil(tUs * fpsV / 1e6 - 1e-6).toLong()

    /**
     * How far after a slot a frame may lie and still belong to it (µs): half a slot – a frame belongs to the slot
     * nearest its time. Camera files' frame times wander a millisecond or two around their rate: with frames lying on
     * the slots (a clip's in point on its own frames, see FrameSnap) and no room after a slot, a frame a little late
     * went to the next slot – its own slot held the frame before and the frame after it was dropped.
     */
    val tolUs: Long = Math.round(stepUs / 2)

    /** The slot a frame at [tauUs] (as the effects see it: read [LEAD_US] early) belongs to. */
    fun slotOf(tauUs: Long): Long = slotAtOrAfter(tauUs - LEAD_US - tolUs)

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
         * Where one of an item's last frames (at [tUs], its grid time [out] or dropped) is shown. Media3 needs a frame at
         * or after the item's last decoded frame (see [nearEnd]) – compared in the composition's time base (µs), the
         * effects' output against the decoded buffer's time. So such a frame is never earlier than its own time, never on
         * or after the next item's start [endUs] (that slot is the next item's first frame), and after the frame before
         * ([lastOut]). Within that, on the grid: its own slot when that isn't earlier than itself. A frame lying just after
         * its slot (it belongs to the slot nearest it, [slotOf] – a clip's frames lie on the slots, the read-ahead and a
         * camera's jitter after them) is shown at its own time, which is that slot on screen: moved to the next slot, the
         * clip's last frame fell on the next clip's first slot – the frame before it stood for two slots before every
         * unrendered cut and the last one was never seen. Further from its slot than the rule allows: the next slot.
         * When no slot is left before the next item (the last decoded frame lies after the last slot but before the next
         * item's start), it is an end-of-item frame only: 2 µs before the next item's start (a second one 1 µs), where the
         * next item's first frame replaces it at once. Before, these frames were shown at their own times – off the grid next to the next
         * item's (APV proxies at 119.94 fps: 6.197 s and 6.230 s, the latter 3 ms before a pre-rendered whip's first frame;
         * a 16–41 ms hold just before the whip, measured on the vivo).
         */
        fun lastFrameAt(tUs: Long, out: Long?, endUs: Long, lastOut: Long, stepUs: Double, originUs: Long = 0L): Long {
            val cap = if (endUs == Long.MAX_VALUE) Long.MAX_VALUE else endUs - 1
            val after = if (lastOut == Long.MIN_VALUE) Long.MIN_VALUE else lastOut + 1
            // (the slots are the timeline's: times here are [originUs] earlier)
            fun ceilSlot(t: Long) = Math.round(Math.ceil((t + originUs - 0.5) / stepUs) * stepUs) - originUs
            val own = Math.round(stepUs / 2) + LEAD_US
            var g = if (out != null && out >= tUs) out else if (out != null && tUs - out <= own) tUs else ceilSlot(tUs)
            if (g < after) g = ceilSlot(after)
            // (no slot left: 2 µs before the next item, a second such frame 1 µs – a frame belongs to the first slot at or
            // after it, so both of an item's last two frames can find the next item's slot; times must keep rising)
            return if (g <= cap) g else maxOf(tUs, after, cap - 1)
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

        /**
         * The shown frames' slot times for one segment's frame times ([SourceSampler] from the segment's start
         * [segStartUs], nominal frame time [nominalUs]) – the reference for tests.
         */
        fun keep(timesUs: List<Long>, fps: Double, segStartUs: Long = timesUs.firstOrNull() ?: 0L, nominalUs: Long = 0L, segEndUs: Long = Long.MAX_VALUE): List<Long> {
            val g = SourceSampler(fps)
            g.startSegment(segStartUs, nominalUs, segEndUs)
            return timesUs.mapNotNull { g.next(it) }
        }
    }
}

/**
 * Which source frame each slot of the timeline shows – one rule for the export, pre-rendering, playback's main player
 * and its layers, so the same slot shows the same frame however the timeline is rendered (whole, in chunks, a transition
 * window alone).
 *
 * Time bases: a decoded frame's **source time** (its buffer timestamp in the file – real times, so variable frame rates
 * and 119.94 fps keep theirs) is mapped through the clip's own placement, in point and speed to its **timeline time** τ
 * (in the effects: composition time + the composition's start). A rendered part maps by the same line (a slice only
 * moves the in point along it), so τ doesn't depend on where a render begins. Slot k is at T_k = k / fps. A frame's
 * **presentation time** is the slot it is first shown on; it holds until the next frame's.
 *
 * The rule: a frame belongs to the slot nearest its time ([FrameGrid.slotOf]: up to half a slot after the slot, the
 * read-ahead [FrameGrid.LEAD_US] taken off), and slot k shows the first frame that belongs to it; a slot no frame
 * belongs to (a source slower than the timeline) holds the frame before. So a frame is shown at most half a slot from
 * its time, and the decision for a frame depends only on its own τ and the frame before it (the later frames of a slot are
 * dropped) – exact for any timestamps, no frame time guessed. Every render that has the frame before decides alike; a
 * rendered part therefore reads from before its visible start (RenderCache's pre-roll).
 *
 * A segment's first frame (one less than a source frame time – [startSegment]'s nominal, or a slot – after the segment's
 * start: at a clip's in point between two source frames nothing before the in point is read, in any render) shows from
 * the segment's first slot. A frame whose slot is the segment's end or later shows on none of its own.
 */
class SourceSampler(fps: Double) {
    private val grid = FrameGrid(fps)
    val stepUs get() = grid.stepUs
    private var segStart = Long.MIN_VALUE
    private var segEnd = Long.MAX_VALUE
    private var k0 = Long.MIN_VALUE
    private var nominal = 0L
    private var first = true
    private var pin = true
    private var lastSlot = Long.MIN_VALUE

    /**
     * A segment begins at [segStartUs] (timeline); its source frames last [nominalUs] each on the timeline (0: not known);
     * it ends at [segEndUs] (Long.MAX_VALUE: not known) – the next segment's first frame takes the slot there.
     * [pinFirst] false: the segment only begins here in this render (a pre-rolled part cut into it, see RenderCache) –
     * its first frame isn't the segment's first and keeps its own slot, as in the whole timeline (pinned to the cut, it
     * changed the choice for the frame after it: with a long gap in a variable-frame-rate file, inside the part).
     */
    fun startSegment(segStartUs: Long, nominalUs: Long, segEndUs: Long = Long.MAX_VALUE, pinFirst: Boolean = true) {
        segStart = segStartUs
        segEnd = segEndUs
        pin = pinFirst
        k0 = grid.slotAtOrAfter(segStartUs - FrameGrid.LEAD_US)
        nominal = nominalUs
        reset()
    }

    /** A seek: the next frame has no frame before it. */
    fun reset() {
        first = true
        lastSlot = Long.MIN_VALUE
    }

    /** The frame at [tauUs] (timeline): its presentation time (a slot, µs), or null when it is shown on no slot. */
    fun next(tauUs: Long): Long? {
        var c = grid.slotOf(tauUs)
        if (k0 != Long.MIN_VALUE) {
            // the segment's first frame shows from its first slot
            val firstTime = if (nominal > 0) nominal else Math.round(grid.stepUs)
            if (pin && first && c > k0 && tauUs < segStart + firstTime) c = k0
            if (pin && c < k0) c = k0
        }
        first = false
        // a later frame of a slot already shown (slots only rise with τ: the frame before had this one)
        if (lastSlot != Long.MIN_VALUE && c <= lastSlot) return null
        if (segEnd != Long.MAX_VALUE && grid.timeOf(c) >= segEnd - FrameGrid.LEAD_US) return null
        lastSlot = c
        return grid.timeOf(c)
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
    private val sampler = SourceSampler(fps)
    private var segStart = Long.MIN_VALUE
    private var prevIn = Long.MIN_VALUE
    private var lastOut = Long.MIN_VALUE

    /** A seek, a new chain: the next frame starts over. */
    fun reset() {
        sampler.reset()
        segStart = Long.MIN_VALUE
        prevIn = Long.MIN_VALUE
        lastOut = Long.MIN_VALUE
    }

    /**
     * [segEndUs]: where the segment's item ends (Long.MAX_VALUE: not known) – its last frames are kept (FrameGrid.nearEnd);
     * [nominalUs]: the segment's source frame time on the timeline (0: not known).
     */
    fun next(tUs: Long, segStartUs: Long, segEndUs: Long = Long.MAX_VALUE, nominalUs: Long = 0L, pinFirst: Boolean = true): Long? {
        // another segment: its own stream (its pre-roll's times run on from the last one's, so time needn't go back)
        if (segStartUs != segStart) { sampler.startSegment(segStartUs, nominalUs, segEndUs, pinFirst); segStart = segStartUs; lastOut = Long.MIN_VALUE }
        val step = if (prevIn != Long.MIN_VALUE && tUs > prevIn) tUs - prevIn else 0L
        prevIn = tUs
        val last = step > 0 && FrameGrid.nearEnd(tUs, segEndUs, step)
        val out = if (tUs < segStartUs - FrameGrid.LEAD_US) { if (tUs < grid.earliestUs(segStartUs) - grid.stepUs / 2) tUs else null }
            else sampler.next(tUs)
        // the item's last frames: never dropped, never earlier than their own time
        val at = if (last) FrameGrid.lastFrameAt(tUs, out, segEndUs, lastOut, grid.stepUs) else out
        if (at != null) lastOut = at
        return at
    }

    /** The earliest time the segment's own frames are shown at (pre-roll frames all come before it). */
    fun earliestUs(segStartUs: Long): Long = grid.earliestUs(segStartUs)
}

/**
 * Which slots each source frame of an item is shown in when every slot needs a frame of its own (KlippFrameFill: the
 * effects after it draw a transition per slot). Timestamps are the composition's (timeline = timestamp + [originUs]);
 * the frames kept are FrameGrid's (as the export's frame rate keeps them), and the slots between two kept frames, and
 * after the last one up to the item's end [endUs] (timeline), repeat the frame before. Pure – tested in FrameGridTest.
 */
class FillPlanner(
    fps: Double, private val originUs: Long, private val endUs: Long,
    /** Where slots are filled (timeline µs, the transitions' windows); elsewhere the frames are only kept or dropped. */
    private val windows: List<LongRange> = listOf(Long.MIN_VALUE..Long.MAX_VALUE),
    /** The segment's start on the timeline and its source frame time (SourceSampler). */
    private val segStartUs: Long = Long.MIN_VALUE, private val nominalUs: Long = 0L,
    /**
     * Where the item's frames end (timeline µs): an upper layer's piece plays on past [endUs] (Playback's UPPER_TAIL,
     * hidden by the compositor), and the compositor's output follows those frames – they must come out (dropped, the
     * timeline lost 0.2 s after a transition). Frames whose slot lies at or past it are dropped (the next item's first
     * frame takes that slot).
     */
    private val itemEndUs: Long = endUs,
    /** False: the segment only begins at [segStartUs] in this render (see SourceSampler.startSegment). */
    private val pinFirst: Boolean = true,
) : androidx.media3.effect.KlippFrameFill.Planner {
    private val grid = FrameGrid(fps)
    private val sampler = SourceSampler(fps).also { if (segStartUs != Long.MIN_VALUE) it.startSegment(segStartUs, nominalUs, itemEndUs, pinFirst) }
    private var last = Long.MIN_VALUE
    private var started = segStartUs != Long.MIN_VALUE
    // which source frame (its time) each slot shows: the frame itself, or the one kept before it (held)
    private var keptPts = Long.MIN_VALUE
    private val sources = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 512
    }

    /** The source frame's own time (composition µs) the frame at [slotPts] carries – held frames give the frame they repeat. */
    fun sourceOf(slotPts: Long): Long? = synchronized(sources) { sources[slotPts] }

    private fun filled(t: Long) = windows.any { t in it }

    override fun next(ptsUs: Long): LongArray {
        val tl = ptsUs + originUs
        if (!started) { sampler.startSegment(tl, nominalUs, itemEndUs, pinFirst); started = true }
        val at = sampler.next(tl) ?: return LongArray(0).also { CutTrace.log("fill", "seg=$segStartUs in=$ptsUs drop") }
        val slot = Math.round(at / grid.stepUs)
        val out = ArrayList<Long>()
        if (last != Long.MIN_VALUE) for (k in last + 1 until slot) grid.timeOf(k).let { if (filled(it)) out += it - originUs }
        out += at - originUs
        synchronized(sources) {
            // (before the first kept frame the fill repeats the frame itself, see KlippFrameFill.Queue)
            for (t in out) sources[t] = if (t == at - originUs || keptPts == Long.MIN_VALUE) ptsUs else keptPts
        }
        keptPts = ptsUs
        last = slot
        CutTrace.log("fill", "seg=$segStartUs in=$ptsUs out=$out")
        return out.toLongArray()
    }

    override fun end(): LongArray {
        if (last == Long.MIN_VALUE) return LongArray(0)
        val out = ArrayList<Long>()
        var k = last + 1
        // (slots strictly before the item's end: the next item's first frame takes the slot at it)
        while (grid.timeOf(k) < endUs - FrameGrid.LEAD_US) { if (filled(grid.timeOf(k))) out += grid.timeOf(k) - originUs; k++ }
        synchronized(sources) { for (t in out) sources[t] = keptPts }
        last = Long.MIN_VALUE
        CutTrace.log("fill", "seg=$segStartUs end out=$out")
        return out.toLongArray()
    }

    override fun reset() {
        sampler.reset()
        last = Long.MIN_VALUE
        keptPts = Long.MIN_VALUE
    }
}

/**
 * Where a pre-rendered part must begin reading so its first slot gets the whole timeline's frame (SourceSampler): the
 * slot shows the first frame of the last slot at or before it that has a frame of its own – after a long gap in a
 * variable-frame-rate file, or for a slow source, a frame from well before the part. The rule decides that frame by
 * the frame before it, so reading begins there. Pure (the times come from the file's sample table, see RenderCache).
 */
object PreRollRule {
    /**
     * [tausUs]: a clip's source frames on the timeline (µs, rising); the part is shown from [visibleUs]. The time of the
     * frame reading must begin at, or null when [tausUs] begins too late to tell (read further back) or has no frame
     * at or before the part's first slot.
     */
    fun startTau(tausUs: List<Long>, visibleUs: Long, fps: Double): Long? {
        val g = FrameGrid(fps)
        val ka = g.slotAtOrAfter(visibleUs - FrameGrid.LEAD_US)
        fun slot(t: Long) = g.slotOf(t)
        val last = tausUs.indexOfLast { slot(it) <= ka }
        if (last < 0) return null
        val j = slot(tausUs[last])
        val first = tausUs.indexOfFirst { slot(it) == j }
        return if (first <= 0) null else tausUs[first - 1]
    }
}

/**
 * A pre-rendered part's first frame on its first slot (KlippFrameFill, last in the chain): the muxer puts a file's
 * first frame at 0, so a part whose first frame came a slot after its start (a 30 fps source in a 60 fps timeline) was
 * played a slot early, all of it. The part is read from before its start (RenderCache's pre-roll, [originUs] = where its
 * composition begins); what the frame rate presents before the part's start [visibleUs] (timeline) is kept unseen
 * (KlippFrameFill.UNSEEN), and the slots from the part's start up to its first own frame show the frame kept before –
 * the frame the whole timeline holds there (a source slower than the timeline) – or, without one, the first frame
 * itself. Once per render (an item's end doesn't start it over). Pure – tested in FrameGridTest.
 */
class StartPinPlanner(
    fps: Double, private val originUs: Long,
    /** Where the part is shown from (timeline µs; after a pre-roll later than [originUs]). */
    private val visibleUs: Long = originUs,
    /** The composition's item edges (composition µs, rising): where the item of a frame kept unseen ends. */
    private val edgesUs: LongArray = LongArray(0),
) : androidx.media3.effect.KlippFrameFill.Planner {
    private val stepUs = 1_000_000.0 / fps.coerceAtLeast(1.0)
    private var shown = false
    private var keptAt = Long.MIN_VALUE

    override fun next(ptsUs: Long): LongArray {
        if (shown) return longArrayOf(ptsUs)
        if (ptsUs + originUs < visibleUs - FrameGrid.LEAD_US) { keptAt = ptsUs; return longArrayOf(androidx.media3.effect.KlippFrameFill.UNSEEN) }
        shown = true
        // the part's first slot, in the composition's time
        val k0 = Math.ceil((visibleUs - 0.5) / stepUs).toLong()
        val out = ArrayList<Long>()
        var k = k0
        while (true) {
            val t = Math.round(k * stepUs) - originUs
            if (t >= ptsUs - stepUs / 2) break
            out += t; k++
        }
        out += ptsUs
        return out.toLongArray()
    }

    /**
     * An item ends. A part held all through by a frame from before it (a slow source, a long gap in a variable-frame-
     * rate file) has no frame of its own: when the item of the frame kept unseen reaches past the part's start, that
     * frame is shown from the part's first slot – before, such a part came out without any frame.
     */
    override fun end(): LongArray {
        if (shown || keptAt == Long.MIN_VALUE) return LongArray(0)
        val itemEnd = edgesUs.firstOrNull { it > keptAt } ?: Long.MAX_VALUE
        val first = Math.round(Math.ceil((visibleUs - 0.5) / stepUs) * stepUs) - originUs
        if (itemEnd <= first) return LongArray(0)
        shown = true
        return longArrayOf(first)
    }

    // (not at an item's end: the pin is the render's start only)
    override fun reset() {}
}
