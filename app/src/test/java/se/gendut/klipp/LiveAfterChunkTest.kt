package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.FrameRateEffect
import se.gendut.klipp.engine.RenderCache
import se.gendut.klipp.engine.Seg
import se.gendut.klipp.engine.SegSrc

/**
 * A live clip after a pre-rendered piece plays the export's frames from the piece's end on: it begins inside the
 * piece's last slot (RenderCache.liveAfterChunks), so the source frame lying just before the piece's end – the export's
 * frame for the slot there – is read. The model: the clip's source frames on the timeline, read from the live segment's
 * start, through the main player's rule (FrameRateEffect.Streams, item edges as playback passes them).
 */
class LiveAfterChunkTest {
    /** The clip's frames (timeline µs, id = source frame number) from [from] s: [srcFps] at [speed], placed at [start] from [srcIn]. */
    private fun frames(start: Double, srcIn: Double, speed: Double, srcFps: Double, from: Double, to: Double): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var j = Math.floor((srcIn + (from - start) * speed) * srcFps).toLong() - 1
        while (true) {
            val t = Math.round((start + (j / srcFps - srcIn) / speed) * 1e6)
            if (t >= Math.round(to * 1e6)) break
            if (t >= Math.round(from * 1e6)) out += t to j
            j++
        }
        return out
    }

    /** Slot → frame shown, by the rule over [items] (each a list of frames, in order) with item edges [edges] (µs). */
    private fun shown(items: List<List<Pair<Long, Long>>>, edges: LongArray, fps: Double, itemEnds: Boolean, cuts: LongArray = LongArray(0)): Map<Long, Long> {
        val rule = FrameRateEffect.Streams(fps, edges, itemEnds = itemEnds, cutsUs = cuts)
        val out = items.flatten().mapNotNull { (t, id) -> rule.next(t)?.let { it to id } }
        val st = 1e6 / fps
        return (0L..Math.round(out.last().first / st)).associateWith { k -> out.lastOrNull { it.first <= Math.round(k * st) + 1 }?.second ?: -1L }
    }

    private fun case(fps: Double, pieceA: Double, pieceB: Double, start: Double, srcIn: Double, speed: Double, srcFps: Double) {
        val end = pieceB + 1.0
        // the export: the clip runs on through the piece's end (one item from its own start)
        val export = shown(listOf(frames(start, srcIn, speed, srcFps, start, end)), LongArray(0), fps, itemEnds = false)
        val chunkId = -5_000_000L
        val piece = Seg(pieceA, pieceB - pieceA, SegSrc.Media(chunkId, 0.0, 1.0), null)
        val live = Seg(pieceB, end - pieceB, SegSrc.Media(1L, srcIn + (pieceB - start) * speed, speed), null)
        val segs = RenderCache.liveAfterChunks(listOf(piece, live), listOf(piece), fps)
        val moved = segs[1]
        val step = 1.0 / fps
        assertTrue("begins inside the piece's last slot: ${moved.start}", moved.start < pieceB && moved.start > pieceB - step)
        assertEquals(pieceB, segs[0].end, 1e-6.let { step })
        assertEquals("the piece ends where the clip begins", moved.start, segs[0].end, 1e-9)
        // the same line: the in point moved with the start
        val m = moved.src as SegSrc.Media
        assertEquals(srcIn + (moved.start - start) * speed, m.srcIn, 1e-9)
        // playback: the piece's frames (one per slot, up to its last slot) then the clip from the moved start
        val pieceFrames = (Math.round(pieceA * fps) until Math.round(pieceB * fps)).map { Math.round(it * 1e6 / fps) to -1L }
        val edges = longArrayOf(Math.round(moved.start * 1e6), Math.round(end * 1e6))
        // (the moved start is a cut, not the clip's own: not pinned – as Playback passes it)
        val played = shown(listOf(pieceFrames, frames(start, srcIn, speed, srcFps, moved.start, end)), edges, fps, itemEnds = true, cuts = longArrayOf(edges[0]))
        val kEnd = Math.round(pieceB * fps)
        // a slot the export holds from inside the piece (no source frame of its own) holds the piece's last frame here
        val ownSlot = export.entries.groupBy { it.value }.mapValues { e -> e.value.minOf { it.key } }
        for (k in kEnd until kEnd + Math.round(0.5 * fps)) {
            val want = export[k]!!.let { if (ownSlot[it]!! < kEnd) -1L else it }
            assertEquals("slot $k", want, played[k])
        }
        // and cut at the piece's end, as before: the first slot showed another source frame
        val cutEdges = longArrayOf(Math.round(pieceB * 1e6), Math.round(end * 1e6))
        val old = shown(listOf(pieceFrames, frames(start, srcIn, speed, srcFps, pieceB, end)), cutEdges, fps, itemEnds = true)
        assertNotEquals("the old cut differs at slot $kEnd", export[kEnd], old[kEnd])
    }

    @Test fun quarterSpeed120After25pPush() = case(25.0, 3.68, 4.28, 4.0, 1.0, 0.25, 120.0)

    @Test fun quarterSpeed120After60pWhip() = case(60.0, 5.6833333, 6.3166667, 6.0, 1.0, 0.25, 120.0)

    @Test fun thirtyFpsAfter25pPiece() = case(25.0, 1.0, 1.52, 0.0, 0.513, 1.0, 30.0)
}
