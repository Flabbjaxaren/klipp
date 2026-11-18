package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Setting a caption's edge at the playhead (the drag's limits) and which track a caption style applies to. */
class CaptionEditsTest {
    private val f = 1f / 25
    private val a = UiCaption(1, 1f, 2f, "a", track = 7)
    private val b = UiCaption(2, 3f, 4f, "b", track = 7)
    private val other = UiCaption(3, 2.2f, 3.5f, "on another track", track = 8)
    private val all = listOf(a, b, other)

    @Test fun insideTheCaptionBothEdgesMove() {
        assertEquals(CaptionEdgeMove(0.5f, null), captionEdgeTo(a, all, 1.5f, true, f))
        assertEquals(CaptionEdgeMove(-0.5f, null), captionEdgeTo(a, all, 1.5f, false, f))
    }

    @Test fun theEndCanGrowUntilTheNextCaptionOnItsTrack() {
        // the caption on another track does not count
        assertNull(captionEdgeTo(a, all, 2.8f, false, f).problem)
        assertEquals(CaptionEdgeProblem.INTO_NEXT, captionEdgeTo(a, all, 3.2f, false, f).problem)
        assertNull(captionEdgeTo(a, all, 3f, false, f).problem)
    }

    @Test fun theStartCanGrowUntilThePreviousCaptionOrZero() {
        assertNull(captionEdgeTo(b, all, 2.5f, true, f).problem)
        assertEquals(CaptionEdgeProblem.INTO_PREVIOUS, captionEdgeTo(b, all, 1.5f, true, f).problem)
        assertNull(captionEdgeTo(a, all, 0f, true, f).problem)
    }

    @Test fun theEdgesNeverCrossOrGoBelowAFrame() {
        assertEquals(CaptionEdgeProblem.AFTER_END, captionEdgeTo(a, all, 2.5f, true, f).problem)
        assertEquals(CaptionEdgeProblem.BEFORE_START, captionEdgeTo(a, all, 0.5f, false, f).problem)
        assertEquals(CaptionEdgeProblem.TOO_SHORT, captionEdgeTo(a, all, 2f - f / 1.5f, true, f).problem)
        assertEquals(CaptionEdgeProblem.TOO_SHORT, captionEdgeTo(a, all, 1f + f / 1.5f, false, f).problem)
        assertEquals(CaptionEdgeProblem.ALREADY_THERE, captionEdgeTo(a, all, 1f + f / 4, true, f).problem)
    }

    @Test fun theStyleTrackIsTheOnlyOneOrTheChosenOne() {
        val t1 = CaptionTrackUi(7, "Intervju.srt", 2, true)
        val t2 = CaptionTrackUi(8, "Automatiska undertexter", 1, true)
        assertEquals(7L, captionStyleTrack(listOf(t1), null))
        assertNull(captionStyleTrack(listOf(t1, t2), null))
        assertEquals(8L, captionStyleTrack(listOf(t1, t2), 8))
        // a chosen track that is gone: choose again
        assertNull(captionStyleTrack(listOf(t1, t2), 99))
        assertNull(captionStyleTrack(emptyList(), null))
    }

    @Test fun aWrittenLineGoesInTheRoomThereIs() {
        // a: 1–2, b: 3–4 on track 7; another track's caption doesn't count
        assertEquals(CaptionSlot(2f, 1f, null), captionWriteSlot(all, 7, 2f, 2f, 0.3f))
        assertEquals(CaptionSlot(0f, 1f, null), captionWriteSlot(all, 7, 0f, 2f, 0.3f))
        assertEquals(CaptionSlot(4.5f, 2f, null), captionWriteSlot(all, 7, 4.5f, 2f, 0.3f))
        assertEquals(CaptionSlotProblem.INSIDE_CAPTION, captionWriteSlot(all, 7, 1.5f, 2f, 0.3f).problem)
        assertEquals(CaptionSlotProblem.INSIDE_CAPTION, captionWriteSlot(all, 7, 3f, 2f, 0.3f).problem)
        assertEquals(CaptionSlotProblem.NO_ROOM, captionWriteSlot(all, 7, 2.8f, 2f, 0.3f).problem)
        assertEquals(null, captionWriteSlot(all, 7, 2.5f, 2f, 0.3f).problem)
    }

    @Test fun aLineAtACaptionsEndStartsOnThatFrame() {
        // the engine floors a time to its frame: a Float playhead at 5.2 s (5.1999998) went in a frame early – inside
        // the caption ending at 5.2 – and the line was refused. Sent as the nearest frame's tick, it starts there.
        fun frameOf(ticks: Long, n: Long, d: Long) = java.math.BigInteger.valueOf(ticks).multiply(java.math.BigInteger.valueOf(n))
            .divide(java.math.BigInteger.valueOf(se.gendut.klipp.engine.TICKS_PER_SECOND * d)).toLong()
        val (t25, len25) = se.gendut.klipp.engine.captionAddTiming(5.2f, 2.0, 25.0)
        assertEquals(130L, frameOf(t25, 25, 1))
        assertEquals(50L, Math.floor(len25 * 25).toLong())
        for (frame in listOf(1L, 179L, 1799L, 53946L)) {
            val at = (frame * 1001.0 / 30000.0).toFloat()
            assertEquals(frame, frameOf(se.gendut.klipp.engine.captionAddTiming(at, 2.0, 30000.0 / 1001).first, 30000, 1001))
        }
        // a room shorter than the length: whole frames, never less than one
        assertEquals(34L, Math.floor(se.gendut.klipp.engine.captionAddTiming(1f, 1.36, 25.0).second * 25).toLong())
        assertEquals(1L, Math.floor(se.gendut.klipp.engine.captionAddTiming(1f, 0.001, 25.0).second * 25).toLong())
    }
}
