package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.FrameGrid
import se.gendut.klipp.engine.FrameRateEffect
import se.gendut.klipp.engine.StartPinPlanner

/**
 * The same slot shows the same source frame however the timeline is rendered: whole (the export), as one part read from
 * before its start (a pre-rendered chunk: pre-roll, the frame rate, the start pin), or split into several parts at any
 * point inside a clip. A model of the render: each clip's decoded frames (source time through placement, in point and
 * speed onto the timeline), cut to the rendered range as LayerPlanner.slice does, through FrameRateEffect.Streams and,
 * for a part, StartPinPlanner in KlippFrameFill's queue; the file's frames held from their presentation time.
 */
class SourceSelectionTest {
    /** A clip: [start] s on the timeline for [dur] s from [srcIn] s in a file of [srcFps] (± [jitterUs] per frame) at [speed]. */
    private data class Clip(val start: Double, val dur: Double, val srcIn: Double, val speed: Double, val srcFps: Double, val jitterUs: Long = 0) {
        val end get() = start + dur
        /** Every source frame of the file: (source time µs, its id). */
        fun srcFrame(j: Long): Long = Math.round(j * 1e6 / srcFps) + (if (jitterUs == 0L) 0L else ((j % 3) - 1) * jitterUs)
        fun tau(srcUs: Long): Long = Math.round((start + (srcUs / 1e6 - srcIn) / speed) * 1e6)
        /** The frames decoded for the part of the clip from [from] (timeline s) to [to]: (τ µs, frame id). */
        fun decoded(from: Double, to: Double): List<Pair<Long, Long>> {
            val out = ArrayList<Pair<Long, Long>>()
            // the item begins at its in point (moved along the clip's line by a slice), read LEAD early
            var j = Math.floor((srcIn + (from - start) * speed) * srcFps).toLong() - 2
            while (true) {
                val t = tau(srcFrame(j))
                if (t >= Math.round(to * 1e6)) break
                if (t >= Math.round(from * 1e6) - FrameGrid.LEAD_US) out += t to (j + Math.round(start * 1000) * 100_000)
                j++
            }
            return out
        }
    }

    /**
     * The timeline [clips] rendered from [from] (s) to [to] at [fps], shown from [visible] (null: a whole render):
     * slot → frame id, for the slots in [visible or from, to).
     */
    private fun render(clips: List<Clip>, fps: Double, from: Double, to: Double, visible: Double?): Map<Long, Long> {
        val origin = Math.round(from * 1e6)
        val parts = clips.mapNotNull { c ->
            val s0 = maxOf(c.start, from); val s1 = minOf(c.end, to)
            if (s1 - s0 <= 1e-9) null else Triple(s0, s1, c)
        }
        val frames = parts.flatMap { (s0, s1, c) -> c.decoded(s0, s1) }
        val edges = (parts.map { it.first } + parts.map { it.second }).map { Math.round(it * 1e6) - origin }.filter { it > 0 }.distinct().sorted()
        val rule = FrameRateEffect.Streams(fps, edges.toLongArray(), origin, itemEnds = false)
        val presented = frames.mapNotNull { (t, id) -> rule.next(t - origin)?.let { it to id } }
        // the start pin (a part), through the fill's queue: what goes into the file
        val file = ArrayList<Pair<Long, Long>>()
        if (visible == null) file += presented else {
            val pin = StartPinPlanner(fps, origin, Math.round(visible * 1e6))
            var cur = -1L; var kept = -1L; var now = -1L
            val q = androidx.media3.effect.KlippFrameFill.Queue(pin, object : androidx.media3.effect.KlippFrameFill.Sink {
                override fun copyCurrent() { cur = now }
                override fun emit(current: Boolean, timeUs: Long) { file += timeUs to (if (current) cur else kept) }
                override fun keepCurrent() { kept = cur }
                override fun outputFree() = true
                override fun inputProcessed() {}
                override fun readyForInput() {}
                override fun endOfStream() {}
            })
            for ((t, id) in presented) { now = id; q.input(t) }
        }
        val st = 1e6 / fps
        val shownFrom = visible ?: from
        val out = HashMap<Long, Long>()
        var k = Math.ceil(shownFrom * fps - 1e-6).toLong()
        while (Math.round(k * st) < Math.round(to * 1e6)) {
            val t = Math.round(k * st) - origin
            file.lastOrNull { it.first <= t + 1 }?.let { out[k] = it.second }
            k++
        }
        return out
    }

    /** A part [a, b) as RenderCache renders it: read [pre] s before a and a slot past b. */
    private fun part(clips: List<Clip>, fps: Double, a: Double, b: Double, pre: Double): Map<Long, Long> {
        val total = clips.maxOf { it.end }
        val end = if (b + 1 / fps <= total + 1e-9) b + 1 / fps else b
        return render(clips, fps, maxOf(0.0, a - pre), end, a).filterKeys { Math.round(it * 1e6 / fps) < Math.round(b * 1e6) - 500 }
    }

    private fun preRoll(clips: List<Clip>, fps: Double, a: Double): Double {
        if (a <= 1e-9) return 0.0
        val p = clips.filter { it.start <= a + 1e-9 && it.end > a + 1e-9 }.maxOfOrNull { 2.5 / (it.srcFps * it.speed) + 1 / fps } ?: 0.0
        return minOf(p, 1.0, a)
    }

    private fun assertSameEverywhere(clips: List<Clip>, fps: Double, splits: List<Double>) {
        val total = clips.maxOf { it.end }
        val whole = render(clips, fps, 0.0, total, null)
        // every slot has a frame
        val slots = (0 until Math.round(total * fps - 1e-6)).toList()
        assertEquals("$fps: every slot", slots, whole.keys.sorted())
        // one part from each split point to the end, and the timeline cut at all of them
        for (a in splits) {
            val p = part(clips, fps, a, total, preRoll(clips, fps, a))
            p.forEach { (k, id) -> assertEquals("$fps, part from $a: slot $k", whole[k], id) }
            assertEquals("$fps, part from $a: slots", whole.keys.filter { it >= Math.ceil(a * fps - 1e-6).toLong() }.sorted(), p.keys.sorted())
        }
        val edges = (listOf(0.0) + splits.sorted() + total).distinct()
        val joined = HashMap<Long, Long>()
        for ((a, b) in edges.zipWithNext()) part(clips, fps, a, b, preRoll(clips, fps, a)).forEach { (k, id) -> assertTrue("slot $k twice", joined.put(k, id) == null) }
        assertEquals("$fps: the parts joined", whole, joined)
    }

    /** Split points inside the clips, snapped up to slots as RenderCache's chunk edges are. */
    private fun splits(fps: Double, vararg at: Double) = at.map { Math.ceil(it * fps - 1e-6) / fps }

    @Test fun thirtyFpsOnA25pTimeline() {
        // the vivo's S25: a 30 fps clip from 0.513 s in, then 120 fps at 25 %
        val clips = listOf(Clip(0.0, 4.0, 0.513, 1.0, 30.0), Clip(4.0, 3.0, 0.0, 0.25, 120.0))
        assertSameEverywhere(clips, 25.0, splits(25.0, 0.37, 1.0, 1.51, 2.02, 3.68, 4.28, 5.13, 6.5))
    }

    @Test fun quarterSpeed120On60p() {
        val clips = listOf(Clip(0.0, 5.6833, 2.0, 0.25, 120.0), Clip(5.6833, 3.0, 7.0313, 0.25, 120.0))
        assertSameEverywhere(clips, 60.0, splits(60.0, 0.41, 2.0, 3.333, 5.6833, 6.3167, 7.01))
    }

    @Test fun phone11994fpsWithUnevenFrames() {
        val clips = listOf(Clip(0.0, 3.0, 1.0, 0.25, 119.94, jitterUs = 300), Clip(3.0, 2.0, 0.21, 1.0, 119.94, jitterUs = 300))
        assertSameEverywhere(clips, 60.0, splits(60.0, 0.5, 1.25, 2.9, 3.4, 4.0))
        assertSameEverywhere(clips, 25.0, splits(25.0, 0.5, 1.25, 2.9, 3.4, 4.0))
    }

    @Test fun rationalTimelines() {
        for (fps in listOf(30000.0 / 1001, 60000.0 / 1001)) {
            val clips = listOf(Clip(0.0, 2.5, 0.37, 1.0, 24.0), Clip(2.5, 2.0, 1.0, 0.5, 59.94), Clip(4.5, 1.5, 0.0, 1.0, 30000.0 / 1001))
            assertSameEverywhere(clips, fps, splits(fps, 0.3, 1.1, 2.6, 3.33, 4.6, 5.2))
        }
    }

    @Test fun speedOneAtTheTimelinesRateIsUntouched() {
        // frames already on the grid: each its own slot, wherever the render begins
        val clips = listOf(Clip(0.0, 3.0, 1.0, 1.0, 25.0))
        assertSameEverywhere(clips, 25.0, splits(25.0, 0.4, 1.0, 2.2))
        val whole = render(clips, 25.0, 0.0, 3.0, null)
        assertEquals((0L until 75L).map { it + 25L }, (0L until 75L).map { whole[it]!! })
    }

    @Test fun aSlowerSourceHoldsAcrossAPartsStart() {
        // 30 fps at 10 %: a frame every 333 ms on a 60 fps timeline – a part starting between two source frames shows the
        // frame held there (from its pre-roll), not the next one early
        val clips = listOf(Clip(0.0, 4.0, 0.4, 0.1, 30.0))
        assertSameEverywhere(clips, 60.0, splits(60.0, 0.2, 0.55, 1.71, 2.05, 3.3))
    }
}
