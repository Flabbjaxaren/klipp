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
}
