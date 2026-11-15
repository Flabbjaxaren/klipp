package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.FrameSnap
import se.gendut.klipp.engine.SourceSampler

/**
 * A clip's in point on its file's own frames (FrameSnap): with the first frame a few milliseconds after the in point,
 * the rule showed the clip's first picture on two slots and never its last frame; with the in point on the first
 * frame every frame has its slot.
 */
class FrameSnapTest {
    /** A 25 fps file whose frames lie 26.6 ms after the timeline's slots (µs) – more than half a slot: nearer the next. */
    private fun fileTimes(n: Int) = (0 until n).map { 26_600L + it * 40_000L }

    /** The slots (µs) the rule shows a clip's frames on: the clip at [startUs] on the timeline for [frames] slots, in point [inUs]. */
    private fun slots(times: List<Long>, inUs: Long, startUs: Long, frames: Int): List<Long> {
        val end = startUs + frames * 40_000L
        val s = SourceSampler(25.0).apply { startSegment(startUs, 40_000L, end) }
        // the clip reads from 1 ms before its in point up to its end; a frame's timeline time = start + (its time − the in
        // point), and the effects see it the read-ahead later
        return times.filter { it >= inUs - 1_000L && it < inUs + frames * 40_000L - 1_000L }.mapNotNull { s.next(startUs + (it - inUs) + 1_000L) }
    }

    @Test fun theFirstFrameAtOrAfterTheInPoint() {
        val t = fileTimes(300)
        // in point 10.44 s (a slot of the timeline): the frame at 10.4666 s; one within the read-ahead before it counts
        assertEquals(10_466_600L, FrameSnap.firstAtOrAfter(t.asSequence(), 10_440_000L - 1_000L))
        assertEquals(10_466_600L, FrameSnap.firstAtOrAfter(t.asSequence(), 10_467_000L - 1_000L))
        assertEquals(null, FrameSnap.firstAtOrAfter(t.asSequence(), 20_000_000L))
        // decoding order with B-frames: a later sample carries the earlier time
        assertEquals(80_000L, FrameSnap.firstAtOrAfter(sequenceOf(0L, 160_000L, 80_000L, 120_000L, 320_000L, 240_000L), 50_000L))
    }

    @Test fun onItsOwnFramesAClipShowsEveryFrameOnce() {
        val t = fileTimes(300)
        val start = 3_400_000L
        // the timeline's in point: the first picture stands for two slots and five slots show four frames
        assertEquals(listOf(3_400_000L, 3_480_000L, 3_520_000L, 3_560_000L), slots(t, 10_440_000L, start, 5))
        // the in point on the first frame: five frames, five slots
        val snapped = FrameSnap.firstAtOrAfter(t.asSequence(), 10_440_000L - 1_000L)!!
        assertEquals(listOf(3_400_000L, 3_440_000L, 3_480_000L, 3_520_000L, 3_560_000L), slots(t, snapped, start, 5))
    }
}
