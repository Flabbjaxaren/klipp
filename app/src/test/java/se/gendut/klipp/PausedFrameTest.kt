package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.FrameGrid
import se.gendut.klipp.engine.FrameRateEffect
import se.gendut.klipp.engine.PausedPick
import se.gendut.klipp.engine.PausedSeek

/**
 * The paused player shows the export's frame for the playhead's slot. A model of the player: a seek to a position (ms)
 * gives the frames at or after it (Media3 skips the ones before), through the main player's rule (FrameRateEffect.Streams)
 * and PausedPick; the shown frame is the first PausedPick lets through. The caller's side is Playback's (attempts from
 * PausedSeek, the second seek for a held frame, generations). The reference: the export's rule over the whole timeline.
 */
class PausedFrameTest {
    /** One segment's frames on the timeline (µs) from [segStart] s. */
    private fun frames(segStart: Double, dur: Double, srcFps: Double, speed: Double, srcIn: Double, gap: LongRange? = null): List<Long> {
        val out = ArrayList<Long>()
        var j = Math.ceil(srcIn * srcFps - 1e-9).toLong()
        while (true) {
            val f = j / srcFps
            val t = Math.round((segStart + (f - srcIn) / speed) * 1e6)
            if (t >= Math.round((segStart + dur) * 1e6)) break
            if (gap == null || Math.round(f * 1e6) !in gap) out += t
            j++
        }
        return out
    }

    private class Timeline(val fps: Double, val segs: List<Pair<Double, List<Long>>>) {
        val all = segs.flatMap { it.second }
        val starts = segs.map { it.first }.filter { it > 0.0 }
        val startsUs = starts.map { Math.round(it * 1e6) }.toLongArray()
        /** The export: slot → frame. */
        val export: Map<Long, Long> = run {
            val rule = FrameRateEffect.Streams(fps, startsUs, itemEnds = false)
            val shown = all.mapNotNull { t -> rule.next(t)?.let { it to t } }
            val g = FrameGrid(fps)
            val end = Math.round(segs.last().second.last() / g.stepUs)
            (0..end).associateWith { k -> shown.lastOrNull { it.first <= g.timeOf(k) + 1 }?.second ?: -1L }
        }
    }

    /** The paused player at slot [k]: what it shows, and how many seeks it took. */
    private fun paused(tl: Timeline, k: Long): Pair<Long?, Int> {
        val results = ArrayList<PausedPick.Result>()
        val pick = PausedPick { results += it }
        val g = FrameGrid(tl.fps)
        val slotSec = g.timeOf(k) / 1e6
        val segStart = tl.starts.lastOrNull { it <= slotSec + 1e-6 } ?: 0.0
        var attempt = 0; var seeks = 0; var gen = 1
        var req: PausedPick.Request; var ms: Long
        val first = PausedSeek.position(slotSec, segStart, 0, tl.fps)!!
        req = PausedPick.Request(gen, g.timeOf(k), first.second); ms = first.first
        var atStart = first.second; var pickMs = ms
        while (seeks < 6) {
            seeks++
            pick.request(req)
            pick.onFlush()
            // the seek: frames from the position on (an item's start: rounded down, the item before has no frame there)
            val from = ms * 1000
            val rule = FrameRateEffect.Streams(tl.fps, tl.startsUs, itemEnds = false)
            results.clear()
            var shown: Long? = null
            for (t in tl.all.filter { it >= from }) {
                val d = pick.onFrame(t, rule.next(t))
                if (d == PausedPick.SHOW) { shown = t; break }
                // (held: no more frames until the next seek)
                if (d == PausedPick.HOLD) break
            }
            if (shown != null) return shown to seeks
            when (val r = results.firstOrNull() ?: return null to seeks) {
                is PausedPick.Result.Exact -> {
                    var e = Math.floorDiv(r.frameUs, 1000L) - 1
                    if (atStart) e = maxOf(e, pickMs)
                    req = PausedPick.Request(gen, g.timeOf(k), atStart, exactUs = r.frameUs); ms = e
                }
                is PausedPick.Result.Earlier -> {
                    attempt++
                    val p = PausedSeek.position(slotSec, segStart, attempt, tl.fps) ?: return null to seeks
                    req = PausedPick.Request(gen, g.timeOf(k), p.second); ms = p.first; atStart = p.second; pickMs = ms
                }
                is PausedPick.Result.Found -> return r.frameUs to seeks
            }
        }
        return null to seeks
    }

    private fun assertAllSlots(tl: Timeline, maxSeeks: Int = 3) {
        for ((k, want) in tl.export) {
            if (want < 0) continue
            val (got, seeks) = paused(tl, k)
            assertEquals("slot $k", want, got)
            assertTrue("slot $k: $seeks seeks", seeks <= maxSeeks)
        }
    }

    @Test fun thirtyFpsOnA25pTimelineEverySlot() {
        val tl = Timeline(25.0, listOf(0.0 to frames(0.0, 4.0, 30.0, 1.0, 0.513), 4.0 to frames(4.0, 2.0, 120.0, 0.25, 1.0)))
        assertAllSlots(tl)
        // the old paused picture – the first frame at or after the slot – differs on most slots (measured on the vivo)
        val g = FrameGrid(25.0)
        val naive = tl.export.keys.count { k -> tl.all.firstOrNull { it >= g.timeOf(k) } != tl.export[k] }
        assertTrue("$naive", naive > tl.export.size / 2)
    }

    @Test fun quarterSpeed120On60p() =
        assertAllSlots(Timeline(60.0, listOf(0.0 to frames(0.0, 2.0, 120.0, 0.25, 1.0), 2.0 to frames(2.0, 1.5, 119.88, 0.25, 7.0313))))

    @Test fun slotsWithoutAFrameOfTheirOwnHoldTheFrameBefore() {
        // 30 fps at 10 %: a frame every 333 ms on a 60 fps timeline – most slots hold
        assertAllSlots(Timeline(60.0, listOf(0.0 to frames(0.0, 3.0, 30.0, 0.1, 0.4))), maxSeeks = 4)
    }

    @Test fun aLongGapInAVariableFrameRateFile() {
        // 0.6 s without frames: the slots in it hold the frame before the gap
        assertAllSlots(Timeline(25.0, listOf(0.0 to frames(0.0, 3.0, 30.0, 1.0, 0.48, gap = 1_000_001L..1_599_999L))), maxSeeks = 4)
    }

    @Test fun theFirstAndLastSlotsAndTheSlotsAtACut() {
        val tl = Timeline(25.0, listOf(0.0 to frames(0.0, 1.0, 30.0, 1.0, 0.513), 1.0 to frames(1.0, 1.0, 30.0, 1.0, 2.02)))
        for (k in listOf(0L, 1L, 24L, 25L, 26L, 49L)) assertEquals("slot $k", tl.export[k], paused(tl, k).first)
    }

    @Test fun anOlderSeeksResultNeverReplacesALaterOne() {
        val results = ArrayList<PausedPick.Result>()
        val pick = PausedPick { results += it }
        pick.request(PausedPick.Request(1, 400_000L, false))
        // a later seek before the first one's frames came: they belong to no request until its flush
        pick.request(PausedPick.Request(2, 800_000L, false))
        // (and no more are taken until it: nothing runs the decoder on)
        assertEquals(PausedPick.HOLD, pick.onFrame(380_000L, 400_000L))
        pick.onFlush()
        assertEquals(PausedPick.DROP, pick.onFrame(760_000L, 760_000L))
        assertEquals(PausedPick.SHOW, pick.onFrame(787_000L, 800_000L))
        assertEquals(listOf(2), results.map { it.gen })
        // after the pick the frames pass (playback starts with a plain seek)
        assertEquals(PausedPick.SHOW, pick.onFrame(820_000L, 840_000L))
    }

    @Test fun attemptsAreBounded() {
        // three attempts, at most 2 s back, then the item's start only if within 4 s – never an unbounded scan
        var n = 0
        while (PausedSeek.position(12.0, 2.0, n, 25.0) != null) n++
        assertEquals(3, n)
        assertEquals(4000L to true, PausedSeek.position(7.0, 4.0, 3, 25.0))
        // never before the item's start: its start rounded up to the ms (6.2333 s → 6234)
        assertEquals(6234L to true, PausedSeek.position(6.2833333, 6.2333333, 0, 60.0))
        assertEquals(null, PausedSeek.position(6.2833333, 6.2333333, 1, 60.0))
        assertEquals(0L to true, PausedSeek.position(0.08, 0.0, 0, 25.0))
    }
}
