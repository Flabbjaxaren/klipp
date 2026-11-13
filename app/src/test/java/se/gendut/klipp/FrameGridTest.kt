package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.FrameGrid
import se.gendut.klipp.engine.FrameRateEffect
import se.gendut.klipp.engine.LayerGrid
import kotlin.math.abs

/**
 * The timeline grid in playback picks the export's frames. The export's reference: one stream per item (the export
 * rebuilds its chain for each), the item's frames from its start less the 1 ms read-ahead – FrameGrid.keep.
 */
class FrameGridTest {
    private val fps = 25.0
    private val step = 40_000L

    /** A clip on the timeline: its source frames (file s) mapped to the timeline (µs), pre-roll included. */
    private class Clip(val segStartS: Double, val srcInS: Double, val durS: Double, val speed: Double, val srcFps: Double,
                       val jitterUs: Long = 0, val prerollS: Double = 0.25) {
        val segStartUs = Math.round(segStartS * 1e6)
        val segEndUs = Math.round((segStartS + durS) * 1e6)
        /** Every source frame's time on the timeline, from the pre-roll to the segment's end. */
        val frames: List<Long> = run {
            val d = 1.0 / srcFps
            val from = srcInS - prerollS * speed
            val to = srcInS + durS * speed
            var j = Math.ceil(from / d).toLong()
            val out = ArrayList<Long>()
            while (j * d < to) {
                // uneven phone frame times: a fixed pattern, ± jitter
                val f = j * d + (if (jitterUs == 0L) 0.0 else ((j % 3) - 1) * jitterUs / 1e6)
                out += Math.round((segStartS + (f - srcInS) / speed) * 1e6)
                j++
            }
            out
        }
        /** The export's item: its frames from the start (read 1 ms early) on. */
        val itemFrames get() = frames.filter { it >= segStartUs - FrameGrid.LEAD_US && it < segEndUs }
    }

    /** The layer's frames shown (presentation times), pre-roll left out, and all its output in order. */
    private fun layer(c: Clip, g: LayerGrid = LayerGrid(fps)): Pair<List<Long>, List<Long>> {
        val all = c.frames.filter { it < c.segEndUs }.mapNotNull { t -> g.next(t, c.segStartUs)?.let { t to it } }
        return all.filter { it.first >= c.segStartUs - FrameGrid.LEAD_US }.map { it.second } to all.map { it.second }
    }

    private fun assertMatchesExport(c: Clip) {
        val export = FrameGrid.keep(c.itemFrames, fps)
        val (shown, all) = layer(c)
        assertEquals("the export's frames, at the export's times", export, shown)
        // the timeline's common grid: every shown time a whole slot – also when the segment starts between two
        shown.forEach { assertEquals("on the grid: $it", 0L, it % step) }
        // pre-roll before the segment's first frame, everything rising (the video sink needs rising times)
        assertTrue("rising: $all", all.zipWithNext().all { (a, b) -> b > a })
        // no slot skipped while the source has frames for it (a slower source leaves its own gaps)
        val srcStepTl = 1e6 / (c.srcFps / c.speed)
        if (srcStepTl <= step) assertTrue("no gaps: $shown", shown.zipWithNext().all { (a, b) -> b - a == step })
    }

    @Test fun normalSpeedAtTheTimelinesRate() = assertMatchesExport(Clip(4.48, 2.0, 3.28, 1.0, 25.0))

    @Test fun normalSpeed30fpsIn25() = assertMatchesExport(Clip(4.48, 2.0, 3.28, 1.0, 30.0))

    @Test fun quarterSpeed120fps() = assertMatchesExport(Clip(7.76, 7.03, 3.0, 0.25, 120.0))

    @Test fun quarterSpeedUnevenPhone120fps() = assertMatchesExport(Clip(7.76, 7.03, 3.0, 0.25, 119.4, jitterUs = 300))

    @Test fun segmentStartingBetweenTwoSlots() {
        assertMatchesExport(Clip(8.3712, 7.03, 2.0, 0.25, 120.0))
        assertMatchesExport(Clip(8.3712, 2.0, 2.0, 1.0, 30.0))
        assertMatchesExport(Clip(8.0201, 2.0, 2.0, 1.0, 25.0))
    }

    @Test fun inPointBetweenTwoSourceFrames() {
        assertMatchesExport(Clip(4.48, 2.0313, 3.0, 1.0, 25.0))
        assertMatchesExport(Clip(4.48, 7.0313, 3.0, 0.25, 120.0))
    }

    @Test fun unevenFramesHalfASlotOffTheGridKeepEverySlot() {
        // 25 fps phone frames (± 0.1 ms) sitting 20 ms off the grid: counted from the first frame, never two in one slot
        assertMatchesExport(Clip(4.48, 2.02, 3.0, 1.0, 25.0, jitterUs = 100))
    }

    @Test fun aNewSegmentIsANewStream() {
        // the edit point layer plays one segment after another: the second's pre-roll runs on from the first's frames
        val g = LayerGrid(fps)
        val a = Clip(4.48, 2.0, 1.5, 0.25, 120.0)
        val b = Clip(7.76, 7.0313, 2.0, 0.25, 120.0)
        assertEquals(FrameGrid.keep(a.itemFrames, fps), layer(a, g).first)
        assertEquals(FrameGrid.keep(b.itemFrames, fps), layer(b, g).first)
    }

    @Test fun aSeekStartsOverOnTheCommonGrid() {
        // after a seek into the clip (reset): still on the grid, one frame per slot, each within a slot of its own time
        val c = Clip(7.76, 7.03, 3.0, 0.25, 120.0)
        val g = LayerGrid(fps)
        val from = c.frames.filter { it >= 8_413_000L && it < c.segEndUs }
        g.reset()
        val out = from.mapNotNull { t -> g.next(t, c.segStartUs)?.let { t to it } }
        out.forEach { (t, at) -> assertEquals(0L, at % step); assertTrue(abs(at - t) < step) }
        assertTrue(out.map { it.second }.zipWithNext().all { (a, b) -> b - a == step })
    }

    @Test fun preRollKeepsHalfASlotFromTheSegmentsFirstFrame() {
        // pre-roll frames close to the first slot would be released for the same refresh as the segment's first frame
        // (seen on the vivo: 8.0733 s unmoved and 8.1067 s moved to 8.08 s, both for one refresh): dropped
        for (c in listOf(Clip(8.37, 7.03, 1.0, 0.25, 120.0), Clip(8.08, 7.11, 1.0, 0.25, 120.0), Clip(8.08, 2.0, 1.0, 1.0, 30.0))) {
            val g = LayerGrid(fps)
            val first = g.earliestUs(c.segStartUs)
            c.frames.filter { it < c.segStartUs - FrameGrid.LEAD_US }.forEach { t ->
                val at = g.next(t, c.segStartUs)
                if (t >= first - step / 2) assertEquals(null, at) else assertEquals(t, at)
            }
            val (_, all) = layer(c)
            assertTrue("half a slot before the first: $all", all.zipWithNext().all { (a, b) -> b - a >= minOf(step / 2, Math.round(1e6 / (c.srcFps / c.speed)) - 1) })
        }
    }

    @Test fun mainPlayerStartsOverAtEachItemAsTheExport() {
        // a pre-rendered piece (25 fps on the grid), then the slowed clip, then another clip – one continuous stream in
        // the player; the export's reference is one stream per item
        val chunk = (0 until 70).map { 4_480_000L + it * step }.filter { it < 7_240_000L }
        val c = Clip(7.24, 7.03, 0.52, 0.25, 120.0, prerollS = 0.0)
        val d = Clip(7.76, 7.0313, 1.0, 0.25, 119.4, jitterUs = 300, prerollS = 0.0)
        val items = listOf(chunk, c.itemFrames, d.itemFrames)
        val edges = longArrayOf(7_240_000L, 7_760_000L, 8_760_000L)
        val rule = FrameRateEffect.Streams(fps, edges)
        val played = items.map { it.map { t -> t to rule.next(t) } }
        for ((k, item) in played.withIndex()) {
            val export = FrameGrid.keep(items[k], fps)
            // the item's last frame always comes out, at or after its own time (the player's end of item)
            val (lastIn, lastOut) = item.last()
            assertTrue("item $k: last frame out at $lastOut, in at $lastIn", lastOut != null && lastOut >= lastIn)
            // everything else as the export – the frames before the item's last source frame and a half
            val body = item.filter { (t, _) -> t + 2 * (item[1].first - item[0].first) < edges[k] - FrameGrid.LEAD_US }
            assertEquals("item $k", export.take(body.mapNotNull { it.second }.size), body.mapNotNull { it.second })
        }
        val all = played.flatten().mapNotNull { it.second }
        assertTrue("rising: $all", all.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun aLayerSegmentsLastFrameAlwaysComesOut() {
        for (c in listOf(Clip(7.76, 7.03, 0.52, 0.25, 120.0), Clip(8.08, 2.0, 0.6, 1.0, 30.0), Clip(4.48, 2.0, 1.0, 1.0, 25.0, jitterUs = 100))) {
            val g = LayerGrid(fps)
            val ins = c.frames.filter { it < c.segEndUs }
            val outs = ins.map { g.next(it, c.segStartUs, c.segEndUs) }
            assertTrue("${c.segStartUs}: last ${ins.last()} -> ${outs.last()}", outs.last() != null && outs.last()!! >= ins.last())
            val kept = outs.filterNotNull()
            assertTrue("rising: $kept", kept.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test fun mainPlayerAfterASeekFindsItsItem() {
        val c = Clip(7.76, 7.03, 2.0, 0.25, 120.0, prerollS = 0.0)
        val rule = FrameRateEffect.Streams(fps, longArrayOf(7_760_000L, 9_760_000L))
        rule.reset()
        val out = c.itemFrames.filter { it >= 8_000_000L }.mapNotNull { rule.next(it) }
        // (the item's last frame at its own time, see mainPlayerStartsOverAtEachItemAsTheExport)
        out.dropLast(1).forEach { assertEquals(0L, it % step) }
        assertTrue(out.dropLast(1).zipWithNext().all { (a, b) -> b - a == step })
        assertTrue(out.last() > out[out.size - 2] && out.last() < 9_760_000L)
    }

    @Test fun onlyAClipWhoseFramesAlreadySitOnTheGridSkipsIt() {
        assertTrue(FrameGrid.onGrid(12.0, 3.0, 1.0, 25.0, fps))
        assertTrue(FrameGrid.onGrid(12.04, 3.08, 1.0, 25.0, fps))
        // a start between two slots, an in point between two source frames
        assertEquals(false, FrameGrid.onGrid(12.02, 3.0, 1.0, 25.0, fps))
        assertEquals(false, FrameGrid.onGrid(12.0, 3.013, 1.0, 25.0, fps))
        // another speed or rate, or a rate not known
        assertEquals(false, FrameGrid.onGrid(12.0, 3.0, 0.25, 25.0, fps))
        assertEquals(false, FrameGrid.onGrid(12.0, 3.0, 1.0, 120.0, fps))
        assertEquals(false, FrameGrid.onGrid(12.0, 3.0, 1.0, 0.0, fps))
        // and where it says on the grid, the rule indeed keeps every frame at its own time
        val c = Clip(12.04, 3.08, 2.0, 1.0, 25.0, prerollS = 0.0)
        assertEquals(c.itemFrames, FrameGrid.keep(c.itemFrames, fps))
    }

    @Test fun aTransitionGetsAFrameForEverySlotOfTheTimeline() {
        // 120 fps at 25 % = 30 frames a second in a 60 fps timeline; the whip's window 7.44–8.08 s, the piece ends at 8.08
        val fps60 = 60.0; val step60 = 1e6 / fps60
        val src = (0 until 40).map { 7_000_000L + Math.round(it * 1e6 / 30) }.filter { it < 8_080_000L }
        val p = se.gendut.klipp.engine.FillPlanner(fps60, 0L, 8_080_000L, listOf(7_440_000L..8_080_000L))
        val out = src.flatMap { p.next(it).toList() } + p.end().toList()
        // rising, every time on the 60 fps grid
        assertTrue(out.zipWithNext().all { (a, b) -> b > a })
        out.forEach { assertTrue("$it", kotlin.math.abs(it / step60 - Math.round(it / step60)) < 1e-3) }
        // inside the window every slot has a frame, up to the piece's end; before it only the source's own frames
        val inside = out.filter { it >= 7_440_000L }
        assertEquals(Math.round((8_080_000L - 7_440_000L) / step60).toInt(), inside.size)
        assertTrue(out.last() < 8_080_000L)
        val before = out.filter { it < 7_440_000L - 1 }
        assertEquals(FrameGrid.keep(src, fps60).filter { it < 7_440_000L - 1 }, before)
        // the frames kept are the export's (FrameGrid), the rest repeats
        assertTrue(out.containsAll(FrameGrid.keep(src, fps60)))
    }

    @Test fun aSourceAsFastAsTheTimelineNeedsNoFill() {
        val src = (0 until 30).map { 7_000_000L + it * step }
        val p = se.gendut.klipp.engine.FillPlanner(fps, 0L, 8_200_000L, listOf(7_000_000L..8_200_000L))
        assertEquals(FrameGrid.keep(src, fps), src.flatMap { p.next(it).toList() })
        assertEquals(0, p.end().size)
    }

    @Test fun anItemsLastFramesStayOnTheGridWhereTheyCanAndAreOnlyInternalWhereTheyCant() {
        // the vivo's case: 60 fps, APV proxy at 119.94 fps at 25 % (a frame every 33.35 ms), the next item (a pre-rendered
        // whip) at 6.2333 s; the last frames decoded at 6.1967 and 6.2300 s
        val step60 = 1e6 / 60; val end = 6_233_333L
        fun slotOk(t: Long) = kotlin.math.abs(t / step60 - Math.round(t / step60)) < 1e-3
        // its own slot earlier than itself: the next slot instead (on the grid, not before its own time)
        val a = FrameGrid.lastFrameAt(6_196_667L, 6_183_333L, end, 6_150_000L, step60)
        assertTrue("$a", a >= 6_196_667L && slotOk(a) && a < end)
        // after the last slot before the next item: the end-of-item frame, 1 µs before the next item's first
        val b = FrameGrid.lastFrameAt(6_230_000L, null, end, a, step60)
        assertEquals(end - 1, b)
        // never before its own time, never at the next item's start (Media3: a frame at or after the last decoded one)
        assertTrue(b >= 6_230_000L && b < end)
        // the main player's rule over such an item: rising, every output on the grid but the last, which ends the item
        val src = (0 until 60).map { Math.round(4_233_333L + it * 1e6 / (119.94 * 0.25)) }.filter { it < end }
        val rule = FrameRateEffect.Streams(60.0, longArrayOf(end, 9_000_000L))
        val outs = src.mapNotNull { rule.next(it) }
        assertTrue(outs.zipWithNext().all { (x, y) -> y > x })
        outs.dropLast(1).forEach { assertTrue("$it", slotOk(it)) }
        assertTrue(outs.last() >= src.last() && outs.last() < end)
    }

    @Test fun aChunkStartingBetweenSlotsKeepsTheTimelinesGrid() {
        // a 60 fps timeline whose cut lies at 6.56 s (made at 25 fps): the chunk [6.56, 6.8333) is rendered with its
        // composition starting there – its frames must sit on the timeline's slots (6.5667 …), one per slot, not on a grid
        // counted from 6.56 (17 frames for 16 slots, each 6.7 ms off)
        val step60 = 1e6 / 60; val origin = 6_560_000L; val end = 6_833_333L
        // the chunk's frames as it receives them (composition time): every slot of the transition, from the fill
        val ins = (394 until 410).map { Math.round(it * step60) - origin }
        val rule = FrameRateEffect.Streams(60.0, LongArray(0), origin)
        val outs = ins.mapNotNull { rule.next(it) }.map { it + origin }
        assertEquals(16, outs.size)
        outs.forEach { assertTrue("$it", kotlin.math.abs(it / step60 - Math.round(it / step60)) < 1e-3) }
        assertTrue(outs.first() >= origin && outs.last() < end)
        // a chunk on the grid is as before
        val on = FrameRateEffect.Streams(60.0, LongArray(0), 6_233_333L)
        assertEquals(listOf(0L, 16_667L), listOf(0L, 16_667L).mapNotNull { on.next(it) })
    }

    @Test fun aPartsFirstFrameIsPinnedToItsFirstSlot() {
        // a 60 fps part from 5.6833 s whose first frame comes at 5.70 s (a 30 fps source): the frame on 5.6833 too, so the
        // file's first frame (moved to 0 by the muxer) is the part's start
        val step60 = 1e6 / 60; val origin = 5_683_333L
        val p = se.gendut.klipp.engine.StartPinPlanner(60.0, origin)
        val first = p.next(5_700_000L - origin)
        assertEquals(listOf(0L, 16_667L), first.toList().map { Math.round((it + origin) / step60) - 341L }.map { it * 16_667L })
        assertEquals(listOf(33_333L), p.next(33_333L).toList())
        // on its slot already: nothing added; and never again after
        val q = se.gendut.klipp.engine.StartPinPlanner(60.0, origin)
        assertEquals(listOf(0L), q.next(0L).toList())
        q.reset(); assertEquals(listOf(50_000L), q.next(50_000L).toList())
    }

    @Test fun rationalRatesKeepTheGridToTheMicrosecond() {
        // 29.97 and 59.94 fps (30000/1001, 60000/1001): a slot isn't a whole number of µs – times on the grid within 1 µs
        for (rate in listOf(30000.0 / 1001, 60000.0 / 1001)) {
            val st = 1e6 / rate
            fun onGrid(t: Long) = kotlin.math.abs(t - Math.round(t / st) * st) <= 1.0
            // a 120 fps source at 25 % through a transition window (fill), the part starting between slots
            val origin = Math.round(4_000_000.0 + st / 3)
            val p = se.gendut.klipp.engine.FillPlanner(rate, origin, 5_000_000L)
            val src = (0 until 40).map { Math.round(it * 1e6 / 30) }.filter { it + origin < 5_000_000L }
            val out = src.flatMap { p.next(it).toList() } + p.end().toList()
            assertTrue(out.zipWithNext().all { (x, y) -> y > x })
            out.forEach { assertTrue("$rate: $it", onGrid(it + origin)) }
            // every slot of the window filled, none at or past its end
            val slots = out.map { Math.round((it + origin) / st) }
            assertEquals((slots.first()..slots.last()).toList(), slots)
            assertTrue((out.last() + origin) < 5_000_000L)
            // the main player's rule over a rendered part from between two slots: its output on the timeline's grid
            val rule = FrameRateEffect.Streams(rate, LongArray(0), origin)
            out.mapNotNull { rule.next(it) }.forEach { assertTrue("$rate: $it", onGrid(it + origin)) }
            // chunk edges: a cut between slots moves up to the next slot
            val e = Math.ceil(4.5 * rate - 1e-6) / rate
            assertTrue(onGrid(Math.round(e * 1e6)) && e >= 4.5 && e - 4.5 < 1 / rate)
        }
    }
}

