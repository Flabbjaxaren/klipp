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
        val stream = chunk + c.itemFrames + d.itemFrames
        val rule = FrameRateEffect.Streams(fps, longArrayOf(7_240_000L, 7_760_000L))
        val played = stream.mapNotNull { rule.next(it) }
        val export = FrameGrid.keep(chunk, fps) + FrameGrid.keep(c.itemFrames, fps) + FrameGrid.keep(d.itemFrames, fps)
        assertEquals(export, played)
    }

    @Test fun mainPlayerAfterASeekFindsItsItem() {
        val c = Clip(7.76, 7.03, 2.0, 0.25, 120.0, prerollS = 0.0)
        val rule = FrameRateEffect.Streams(fps, longArrayOf(7_760_000L, 9_760_000L))
        rule.reset()
        val out = c.itemFrames.filter { it >= 8_000_000L }.mapNotNull { rule.next(it) }
        out.forEach { assertEquals(0L, it % step) }
        assertTrue(out.zipWithNext().all { (a, b) -> b - a == step })
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
}
